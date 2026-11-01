// SPDX-License-Identifier: MIT
#include "Server.hpp"
#include <android/log.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <poll.h>
#include <atomic>
#include <thread>
#include <mutex>
#include <unordered_map>
#include <vector>
#include <deque>
#include <stdexcept>
#include <algorithm>
#include <chrono>

namespace displayx {
class Connection {
public:
    std::atomic<bool> running{true};
    const int fd;
    const uint64_t id;
    Message pool;
    AHardwareBuffer* images[MaxImages]{};
    std::mutex writer;
    std::mutex slotsMutex;
    bool busy[MaxImages]{};
    Connection(int f,uint64_t i):fd(f),id(i){}
    ~Connection(){for(auto* b:images)if(b)AHardwareBuffer_release(b);close(fd);}
    bool send(const Message& m) {
        std::lock_guard<std::mutex> lock(writer);
        if(!running||!sendMessage(fd,m)){stop();return false;}return true;
    }
    void stop(){if(running.exchange(false))shutdown(fd,SHUT_RDWR);}
};
Frame::~Frame() {
    {std::lock_guard<std::mutex> lock(connection->slotsMutex);connection->busy[message.slot]=false;}
    Message m=message;m.type=Release;m.flags=completed?1:0;
    connection->send(m);
}
void Frame::complete(){if(!completed.exchange(true)){Message m=message;m.type=Complete;connection->send(m);}}
bool Frame::connected() const{return connection->running;}
void Frame::disconnect(){connection->stop();}

struct Server::Impl {
    std::atomic<uint32_t> fpsLimit{0};
    std::atomic<bool> everyBuffer{false},performance{true};
    int listener=-1;
    std::string path;
    std::atomic<bool> running{true};
    std::thread acceptThread;
    std::mutex mutex;
    std::unordered_map<uint32_t,std::deque<std::shared_ptr<Frame>>> pending;
    struct Worker {std::shared_ptr<Connection> client;std::thread thread;std::atomic<bool> done{false};};
    std::vector<std::unique_ptr<Worker>> workers;
    std::function<bool(uint32_t)> notify;
    uint64_t nextId=1,bytes=0;
    Impl(const char* name,std::function<bool(uint32_t)> n):path(name),notify(std::move(n)) {
        if(path.empty()||path.size()>=sizeof(sockaddr_un::sun_path))throw std::runtime_error("Invalid DisplayX socket path");
        listener=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);
        if(listener<0)throw std::runtime_error("DisplayX socket failed");
        sockaddr_un addr{};addr.sun_family=AF_UNIX;memcpy(addr.sun_path,path.c_str(),path.size()+1);
        // Private per-session path; callers must not reuse a path from another session.
        unlink(path.c_str());
        if(bind(listener,reinterpret_cast<sockaddr*>(&addr),sizeof(addr))||listen(listener,MaxClients)) {
            close(listener);listener=-1;throw std::runtime_error("DisplayX socket bind failed");
        }
        chmod(path.c_str(),0600);
        try{acceptThread=std::thread([this]{acceptLoop();});}catch(...){close(listener);unlink(path.c_str());throw;}
    }
    ~Impl() {
        running=false;shutdown(listener,SHUT_RDWR);close(listener);
        if(acceptThread.joinable())acceptThread.join();
        for(auto& w:workers)w->client->stop();
        for(auto& w:workers)if(w->thread.joinable())w->thread.join();
        pending.clear();workers.clear();unlink(path.c_str());
    }
    void acceptLoop() {
        while(running) {
            int fd=accept4(listener,nullptr,nullptr,SOCK_CLOEXEC);
            if(fd<0){if(errno==EINTR)continue;break;}
            ucred peer{};socklen_t len=sizeof(peer);
            if(getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&peer,&len)||peer.uid!=getuid()){close(fd);continue;}
            // Reap ended threads; never accumulate one thread per recreated swapchain.
            for(auto it=workers.begin();it!=workers.end();) {
                if((*it)->done){(*it)->thread.join();it=workers.erase(it);}else ++it;
            }
            if(workers.size()>=MaxClients){close(fd);continue;}
            timeval timeout{3,0};setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
            setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
            auto w=std::make_unique<Worker>();w->client=std::make_shared<Connection>(fd,nextId++);
            auto* worker=w.get();
            try {w->thread=std::thread([this,worker]{serve(worker->client);worker->client->stop();worker->done=true;});}
            catch(...){w->client->stop();continue;}
            workers.push_back(std::move(w));
        }
    }
    void serve(const std::shared_ptr<Connection>& c) {
        Message m;int fd=-1;
        if(!receiveMessage(c->fd,m,fd))return;
        if(fd>=0){close(fd);return;}
        if(!validPool(m))return;
        const uint64_t allocation=uint64_t(m.width)*m.height*4*m.count;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if(bytes+allocation>MaxBytes)return;
            bytes+=allocation;
        }
        struct Budget {Impl* p;uint64_t n;~Budget(){std::lock_guard<std::mutex> l(p->mutex);p->bytes-=n;}} budget{this,allocation};
        c->pool=m;
        for(uint32_t i=0;i<m.count;i++) {
            if(AHardwareBuffer_recvHandleFromUnixSocket(c->fd,&c->images[i]))return;
            AHardwareBuffer_Desc d{};AHardwareBuffer_describe(c->images[i],&d);
            if(d.width!=m.width||d.height!=m.height||d.layers!=1||d.format!=(m.format==37?1u:5u)
                ||!(d.usage&AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE))return;
        }
        m.type=Ready;if(!c->send(m))return;
        uint64_t lastSequence=0;
        uint32_t lastLimit=UINT32_MAX;
        // Idle/paused games may wait indefinitely for their next frame. A partial
        // message still has the bounded SO_RCVTIMEO set above.
        while(running&&c->running) {
            uint32_t limit=fpsLimit.load();
            if(limit!=lastLimit){Message config;config.type=Pacing;config.width=limit;config.window=c->pool.window;if(!c->send(config))break;lastLimit=limit;}
            pollfd input{c->fd,POLLIN,0};int ready;
            do{ready=poll(&input,1,-1);}while(ready<0&&errno==EINTR);
            if(ready<=0||!(input.revents&POLLIN))break;
            if(!receiveMessage(c->fd,m,fd))break;
            if(m.type!=Present||m.window!=c->pool.window||m.slot>=c->pool.count||m.sequence<=lastSequence
                ||m.width!=c->pool.width||m.height!=c->pool.height||m.format!=c->pool.format) {
                if(fd>=0)close(fd);break;
            }
            lastSequence=m.sequence;
            {std::lock_guard<std::mutex> lock(c->slotsMutex);
                if(c->busy[m.slot]){if(fd>=0)close(fd);break;}
                c->busy[m.slot]=true;}
            if(fd>=0) {
                pollfd fence{fd,POLLIN,0};int done;
                auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(5);
                do{done=poll(&fence,1,50);}while((done==0||(done<0&&errno==EINTR))&&running&&c->running&&std::chrono::steady_clock::now()<deadline);
                close(fd);fd=-1;
                if(done<=0||!(fence.revents&POLLIN))break;
            }
            auto frame=std::make_shared<Frame>();frame->connection=c;frame->buffer=c->images[m.slot];
            frame->message=m;frame->bufferId=(c->id<<32)|m.slot;
            std::deque<std::shared_ptr<Frame>> dropped;
            {
                std::lock_guard<std::mutex> lock(mutex);
                auto& latest=pending[m.window];
                if(!everyBuffer&&performance&&c->pool.flags!=2)dropped.swap(latest); // FIFO retains order; mailbox keeps only the latest pending frame.
                latest.push_back(frame);
            }
            // A mailbox drop is not a displayed frame. The producer fence was
            // waited before publishing, so returning the slot is safe here.
            dropped.clear();if(!notify(m.window))break;
        }
        std::deque<std::shared_ptr<Frame>> stale;
        {std::lock_guard<std::mutex> lock(mutex);auto it=pending.find(c->pool.window);
            if(it!=pending.end()) {
                auto& queue=it->second;
                for(auto pos=queue.begin();pos!=queue.end();)if((*pos)->connection==c){stale.push_back(std::move(*pos));pos=queue.erase(pos);}else ++pos;
                if(queue.empty())pending.erase(it);
            }}
    }
};
Server::Server(const char* path,std::function<bool(uint32_t)> notify):impl(new Impl(path,std::move(notify))){}
Server::~Server()=default;
std::shared_ptr<Frame> Server::take(uint32_t window) {
    std::lock_guard<std::mutex> lock(impl->mutex);
    auto it=impl->pending.find(window);if(it==impl->pending.end())return {};
    auto frame=std::move(it->second.front());it->second.pop_front();if(it->second.empty())impl->pending.erase(it);return frame;
}
void Server::setFpsLimit(uint32_t fps){impl->fpsLimit=std::min(fps,1000u);}
void Server::setOptions(bool every,bool fast){impl->everyBuffer=every;impl->performance=fast;}
bool Server::hasPending(uint32_t window){std::lock_guard<std::mutex> lock(impl->mutex);auto it=impl->pending.find(window);return it!=impl->pending.end()&&!it->second.empty();}
}
