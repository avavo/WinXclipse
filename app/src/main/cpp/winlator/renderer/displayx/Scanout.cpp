// SPDX-License-Identifier: MIT
#include "Scanout.hpp"
#include "FenceWaitPolicy.hpp"
#include <android/surface_control.h>
#include <android/api-level.h>
#include <dlfcn.h>
#include <poll.h>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <thread>

namespace displayx {
namespace {
struct Api {
    void* library=nullptr;
    using CompleteCallback=void(*)(void*,ASurfaceTransactionStats*);
#define SURFACE_PROCS(X) \
    X(ASurfaceControl_createFromWindow,ASurfaceControl*,(ANativeWindow*,const char*)) \
    X(ASurfaceControl_release,void,(ASurfaceControl*)) \
    X(ASurfaceTransaction_create,ASurfaceTransaction*,()) \
    X(ASurfaceTransaction_delete,void,(ASurfaceTransaction*)) \
    X(ASurfaceTransaction_apply,void,(ASurfaceTransaction*)) \
    X(ASurfaceTransaction_setBuffer,void,(ASurfaceTransaction*,ASurfaceControl*,AHardwareBuffer*,int)) \
    X(ASurfaceTransaction_setGeometry,void,(ASurfaceTransaction*,ASurfaceControl*,const ARect&,const ARect&,int32_t)) \
    X(ASurfaceTransaction_setVisibility,void,(ASurfaceTransaction*,ASurfaceControl*,int8_t)) \
    X(ASurfaceTransaction_setZOrder,void,(ASurfaceTransaction*,ASurfaceControl*,int32_t)) \
    X(ASurfaceTransaction_setBufferTransparency,void,(ASurfaceTransaction*,ASurfaceControl*,int8_t)) \
    X(ASurfaceTransaction_reparent,void,(ASurfaceTransaction*,ASurfaceControl*,ASurfaceControl*)) \
    X(ASurfaceTransaction_setOnComplete,void,(ASurfaceTransaction*,void*,CompleteCallback)) \
    X(ASurfaceTransactionStats_getPreviousReleaseFenceFd,int,(ASurfaceTransactionStats*,ASurfaceControl*))
#define FIELD(n,result,args) result (*n##Fn) args {};
    SURFACE_PROCS(FIELD)
#undef FIELD
    void (*desiredPresentTime)(ASurfaceTransaction*,int64_t)=nullptr;
    bool available=false;
    Api() {
        if(android_get_device_api_level()<29)return;
        library=dlopen("libandroid.so",RTLD_NOW|RTLD_LOCAL);if(!library)return;
        desiredPresentTime=reinterpret_cast<decltype(desiredPresentTime)>(dlsym(library,"ASurfaceTransaction_setDesiredPresentTime"));
        available=true;
#define LOAD(n,result,args) n##Fn=reinterpret_cast<decltype(n##Fn)>(dlsym(library,#n));available&=n##Fn!=nullptr;
        SURFACE_PROCS(LOAD)
#undef LOAD
    }
    ~Api(){if(library)dlclose(library);}
};
struct Retired {std::shared_ptr<Frame> frame;int fence=-1;bool completion=false;};
class Reaper {
    std::mutex mutex;
    std::condition_variable changed;
    std::deque<Retired> pending;
    bool stopping=false;
    std::thread worker;
    void run() {
        for(;;) {
            Retired task;
            {std::unique_lock<std::mutex> lock(mutex);changed.wait(lock,[&]{return stopping||!pending.empty();});
                if(pending.empty())break;task=std::move(pending.front());pending.pop_front();}
            if(task.completion){task.frame->complete();continue;}
            bool signalled=task.fence<0;
            while(!signalled) {
                pollfd fd{task.fence,POLLIN,0};int result=poll(&fd,1,50);
                auto outcome=fenceOutcome(result,fd.revents&POLLIN,fd.revents&(POLLERR|POLLHUP|POLLNVAL),errno==EINTR);
                if(outcome==FenceOutcome::Complete)signalled=true;
                bool stop;{std::lock_guard<std::mutex> lock(mutex);stop=stopping;}
                if(stop||outcome==FenceOutcome::Failed)break;
            }
            if(task.fence>=0)close(task.fence);
            // If Android did not release a buffer before shutdown, disconnect
            // the producer instead of permitting it to overwrite that buffer.
            // SurfaceFlinger retains its own AHB ref for any remaining scanout.
            if(!signalled)task.frame->disconnect();
            task.frame->externalReaders.fetch_sub(1);task.frame.reset();
        }
    }
public:
    Reaper():worker([this]{run();}){}
    ~Reaper(){ {std::lock_guard<std::mutex> lock(mutex);stopping=true;}changed.notify_all();worker.join();}
    void retire(std::shared_ptr<Frame> frame,int fence) {
        if(!frame){if(fence>=0)close(fence);return;}
        {std::lock_guard<std::mutex> lock(mutex);
            if(pending.size()>=16){frame->disconnect();if(fence>=0)close(fence);frame->externalReaders.fetch_sub(1);return;}
            pending.push_back({std::move(frame),fence});}changed.notify_one();
    }
    void completed(std::shared_ptr<Frame> frame) {
        if(!frame)return;
        {std::lock_guard<std::mutex> lock(mutex);if(pending.size()>=16)return;pending.push_back({std::move(frame),-1,true});}changed.notify_one();
    }
};
std::shared_ptr<Reaper> retirementWorker(){static auto worker=std::make_shared<Reaper>();return worker;}
}
struct Scanout::State {
    std::shared_ptr<Api> api;
    ASurfaceControl* control=nullptr;
    std::shared_ptr<Reaper> reaper;
    std::shared_ptr<Frame> current;
    std::array<int32_t,4> destination{};
    explicit State(ANativeWindow* window):api(std::make_shared<Api>()) {
        if(api->available)control=api->ASurfaceControl_createFromWindowFn(window,"WinXclipse DisplayX");
        if(control)reaper=retirementWorker();
    }
    ~State(){if(current)current->disconnect();if(control)api->ASurfaceControl_releaseFn(control);}
    struct Completion {std::shared_ptr<State> state;std::shared_ptr<Frame> previous,presented;};
    static void completed(void* context,ASurfaceTransactionStats* stats) {
        std::unique_ptr<Completion> result(static_cast<Completion*>(context));
        auto& state=*result->state;
        int fence=state.api->ASurfaceTransactionStats_getPreviousReleaseFenceFdFn(stats,state.control);
        state.reaper->completed(std::move(result->presented));
        state.reaper->retire(std::move(result->previous),fence);
    }
};
Scanout::Scanout(ANativeWindow* parent):window(parent){ANativeWindow_acquire(window);}
Scanout::~Scanout(){hide();ANativeWindow_release(window);}
bool Scanout::show(const std::shared_ptr<Frame>& frame,const std::array<int32_t,4>& destination,uint64_t desiredNanos) {
    if(destination[0]<0||destination[1]<0||destination[2]<=destination[0]||destination[3]<=destination[1])return false;
    if(!state)state=std::make_shared<State>(window);
    if(!state->control)return false;
    if(state->current==frame&&state->destination==destination)return true;
    auto& api=*state->api;
    ASurfaceTransaction* transaction=api.ASurfaceTransaction_createFn();if(!transaction)return false;
    auto* completion=new State::Completion{state,state->current,frame};
    // Repeated geometry changes retain the same buffer as a separate SF ref.
    // Each setBuffer reference gets one matching release-fence retirement.
    frame->externalReaders.fetch_add(1);
    if(desiredNanos&&api.desiredPresentTime)api.desiredPresentTime(transaction,int64_t(desiredNanos));
    api.ASurfaceTransaction_setBufferFn(transaction,state->control,frame->buffer,-1);
    ARect source{0,0,int32_t(frame->message.width),int32_t(frame->message.height)};
    ARect target{destination[0],destination[1],destination[2],destination[3]};
    api.ASurfaceTransaction_setGeometryFn(transaction,state->control,source,target,0);
    api.ASurfaceTransaction_setZOrderFn(transaction,state->control,1);
    api.ASurfaceTransaction_setBufferTransparencyFn(transaction,state->control,ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
    api.ASurfaceTransaction_setVisibilityFn(transaction,state->control,ASURFACE_TRANSACTION_VISIBILITY_SHOW);
    api.ASurfaceTransaction_setOnCompleteFn(transaction,completion,State::completed);
    state->current=frame;state->destination=destination;
    api.ASurfaceTransaction_applyFn(transaction);api.ASurfaceTransaction_deleteFn(transaction);return true;
}
void Scanout::hide() {
    if(!state||!state->control)return;
    auto& api=*state->api;
    ASurfaceTransaction* transaction=api.ASurfaceTransaction_createFn();if(!transaction)return;
    auto* completion=new State::Completion{state,std::move(state->current),{}};
    api.ASurfaceTransaction_setVisibilityFn(transaction,state->control,ASURFACE_TRANSACTION_VISIBILITY_HIDE);
    api.ASurfaceTransaction_reparentFn(transaction,state->control,nullptr);
    api.ASurfaceTransaction_setOnCompleteFn(transaction,completion,State::completed);
    api.ASurfaceTransaction_applyFn(transaction);api.ASurfaceTransaction_deleteFn(transaction);
    state.reset();
}
}
