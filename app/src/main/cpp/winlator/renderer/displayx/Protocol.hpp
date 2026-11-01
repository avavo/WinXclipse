// SPDX-License-Identifier: MIT
// Private, versioned WinXclipse DisplayX protocol. No C ABI padding on the wire.
#pragma once
#ifndef DISPLAYX_PROTOCOL_TEST_PLATFORM
#include <cstdint>
#include <cstddef>
#include <cerrno>
#include <cstring>
#include <sys/socket.h>
#include <unistd.h>
#endif

namespace displayx {
constexpr uint32_t Magic=0x58585044, Version=1, MaxImages=4, MaxClients=4;
constexpr uint64_t MaxBytes=256ULL*1024*1024;
enum Type:uint32_t {Hello=1, Ready, Present, Release, Complete, Reject, Pacing};
struct Message {
    uint32_t magic=Magic,version=Version,type=0,window=0,width=0,height=0;
    uint32_t count=0,format=0,slot=0,flags=0;
    uint64_t sequence=0;
};
static_assert(sizeof(Message)==48 && offsetof(Message,sequence)==40,"DisplayX wire ABI");
inline bool valid(const Message& m) {return m.magic==Magic&&m.version==Version;}
inline bool validPool(const Message& m) {
    return valid(m)&&m.type==Hello&&m.window&&m.width&&m.height&&m.width<=8192&&m.height<=8192
        &&m.count>=2&&m.count<=MaxImages&&(m.format==37||m.format==44)
        &&m.flags<=2 // IMMEDIATE=0, MAILBOX=1, FIFO=2
        &&uint64_t(m.width)*m.height*4*m.count<=MaxBytes;
}
// SCM_RIGHTS belongs to the first bytes of a stream message. A short send must
// finish with plain send(), never resend the descriptor. Never raise SIGPIPE.
inline bool sendMessage(int socket,const Message& message,int fd=-1) {
    iovec io{const_cast<Message*>(&message),sizeof(message)};
    alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int))]{};
    msghdr msg{};msg.msg_iov=&io;msg.msg_iovlen=1;
    if(fd>=0) {
        msg.msg_control=control;msg.msg_controllen=sizeof(control);
        auto* c=CMSG_FIRSTHDR(&msg);c->cmsg_level=SOL_SOCKET;c->cmsg_type=SCM_RIGHTS;
        c->cmsg_len=CMSG_LEN(sizeof(int));memcpy(CMSG_DATA(c),&fd,sizeof(fd));
    }
    ssize_t n;do{n=sendmsg(socket,&msg,MSG_NOSIGNAL);}while(n<0&&errno==EINTR);
    if(n<=0)return false;
    size_t done=size_t(n);
    while(done<sizeof(message)) {
        do{n=send(socket,reinterpret_cast<const char*>(&message)+done,sizeof(message)-done,MSG_NOSIGNAL);}while(n<0&&errno==EINTR);
        if(n<=0)return false;done+=size_t(n);
    }
    return true;
}
// recvmsg on every fragment preserves descriptor ownership even with malicious
// fragmentation. Reject truncation/extra fds and close EVERY received fd.
inline bool receiveMessage(int socket,Message& message,int& fd) {
    fd=-1;size_t done=0;bool okay=true;
    while(done<sizeof(message)) {
        iovec io{reinterpret_cast<char*>(&message)+done,sizeof(message)-done};
        alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int)*8)]{};
        msghdr msg{};msg.msg_iov=&io;msg.msg_iovlen=1;msg.msg_control=control;msg.msg_controllen=sizeof(control);
        ssize_t n;do{n=recvmsg(socket,&msg,MSG_CMSG_CLOEXEC);}while(n<0&&errno==EINTR);
        if(n<=0){okay=false;break;}
        if(msg.msg_flags&(MSG_TRUNC|MSG_CTRUNC))okay=false;
        for(auto* c=CMSG_FIRSTHDR(&msg);c;c=CMSG_NXTHDR(&msg,c)) {
            if(c->cmsg_level!=SOL_SOCKET||c->cmsg_type!=SCM_RIGHTS)continue;
            size_t count=(c->cmsg_len-CMSG_LEN(0))/sizeof(int);
            auto* incoming=reinterpret_cast<int*>(CMSG_DATA(c));
            for(size_t i=0;i<count;i++) {
                if(fd<0&&okay)fd=incoming[i];else{close(incoming[i]);okay=false;}
            }
        }
        done+=size_t(n);
    }
    if(!okay||!valid(message)){if(fd>=0)close(fd);fd=-1;return false;}
    return true;
}
}
