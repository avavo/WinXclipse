// SPDX-License-Identifier: MIT
// Fault injection around the actual transport implementation. The shim replaces
// syscalls, not sendMessage/receiveMessage. Builds without a Windows SDK or CRT.
#include <stdint.h>
#include <stddef.h>
typedef long long ssize_t;
extern "C" {int _fltused=0;} // Windows linker marker; no floating-point CRT calls.
extern "C" void* memcpy(void* dst,const void* src,size_t n){volatile char* d=(volatile char*)dst;const volatile char* s=(const volatile char*)src;for(size_t i=0;i<n;i++)d[i]=s[i];return dst;}
extern "C" void* memset(void* dst,int value,size_t n){volatile char* d=(volatile char*)dst;for(size_t i=0;i<n;i++)d[i]=(char)value;return dst;}
struct iovec {void* iov_base;size_t iov_len;};
struct msghdr {iovec* msg_iov;size_t msg_iovlen;void* msg_control;size_t msg_controllen;int msg_flags;};
struct cmsghdr {size_t cmsg_len;int cmsg_level,cmsg_type;};
#define SOL_SOCKET 1
#define SCM_RIGHTS 1
#define MSG_NOSIGNAL 1
#define MSG_CMSG_CLOEXEC 2
#define MSG_TRUNC 4
#define MSG_CTRUNC 8
#define CMSG_LEN(n) (sizeof(cmsghdr)+(n))
#define CMSG_SPACE(n) ((CMSG_LEN(n)+7)&~size_t(7))
#define CMSG_FIRSTHDR(m) ((m)->msg_controllen>=sizeof(cmsghdr)?(cmsghdr*)(m)->msg_control:nullptr)
#define CMSG_DATA(c) ((unsigned char*)(c)+sizeof(cmsghdr))
static cmsghdr* nextHeader(msghdr* m,cmsghdr* c){auto* next=(unsigned char*)c+CMSG_SPACE(c->cmsg_len-sizeof(cmsghdr));return next+sizeof(cmsghdr)<=(unsigned char*)m->msg_control+m->msg_controllen?(cmsghdr*)next:nullptr;}
#define CMSG_NXTHDR(m,c) nextHeader(m,c)
#define EINTR 4
static int errno;
static unsigned char wire[256];
static size_t wireSize,position,chunk;
static int sendCalls,rightsSent,closed,fdPosition,fdCount,controlFlags,interruptions;
static int close(int fd){if(fd>=0&&fd<30)closed|=1<<fd;return 0;}
static ssize_t send(int,const void* data,size_t n,int){n=n<chunk?n:chunk;memcpy(wire+wireSize,data,n);wireSize+=n;return (ssize_t)n;}
static ssize_t sendmsg(int fd,const msghdr* message,int flags){sendCalls++;if(interruptions){interruptions--;errno=EINTR;return -1;}if(message->msg_controllen)rightsSent++;return send(fd,message->msg_iov[0].iov_base,message->msg_iov[0].iov_len,flags);}
static ssize_t recvmsg(int,msghdr* m,int){
    if(interruptions){interruptions--;errno=EINTR;return -1;}
    if(position>=wireSize)return 0;
    size_t n=wireSize-position;n=n<chunk?n:chunk;n=n<m->msg_iov[0].iov_len?n:m->msg_iov[0].iov_len;
    memcpy(m->msg_iov[0].iov_base,wire+position,n);
    m->msg_flags=controlFlags;
    if(fdCount&&int(position)>=fdPosition){auto* c=(cmsghdr*)m->msg_control;c->cmsg_len=CMSG_LEN(size_t(fdCount)*sizeof(int));c->cmsg_level=SOL_SOCKET;c->cmsg_type=SCM_RIGHTS;
        auto* fds=(int*)CMSG_DATA(c);for(int i=0;i<fdCount;i++)fds[i]=7+i;m->msg_controllen=CMSG_SPACE(size_t(fdCount)*sizeof(int));fdCount=0;
    }else m->msg_controllen=0;
    position+=n;return (ssize_t)n;
}
#define DISPLAYX_PROTOCOL_TEST_PLATFORM
#include "../app/src/main/cpp/winlator/renderer/displayx/Protocol.hpp"
#include "../app/src/main/cpp/winlator/renderer/displayx/ScanoutPolicy.hpp"
#include "../app/src/main/cpp/winlator/renderer/displayx/FenceWaitPolicy.hpp"
#include "../app/src/main/cpp/winlator/renderer/displayx/BufferPolicy.hpp"
using namespace displayx;
static void reset(){wireSize=position=0;chunk=3;sendCalls=rightsSent=closed=fdCount=controlFlags=interruptions=0;fdPosition=0;}
extern "C" __declspec(dllexport) int runProtocolCase(int test) {
    reset();Message m;m.type=Hello;m.window=12;m.width=1920;m.height=1080;m.count=3;m.format=37;
    if(test==23)return cpuReadableBuffer(0x144d,"Samsung GPU")&&cpuReadableBuffer(0x1002,"AMD RADV")&&cpuReadableBuffer(0,"samsung XcLiPsE 940")?0:1;
    if(test==24)return !cpuReadableBuffer(0x5143,"Adreno")&&!cpuReadableBuffer(0x13b5,"Mali")&&!cpuReadableBuffer(0,nullptr)?0:1;
    if(test==20)return fenceOutcome(1,false,true,false)==FenceOutcome::Failed?0:1;
    if(test==21)return fenceOutcome(-1,false,false,true)==FenceOutcome::Pending&&fenceOutcome(0,false,false,false)==FenceOutcome::Pending?0:1;
    if(test==22)return fenceOutcome(1,true,false,false)==FenceOutcome::Complete&&fenceOutcome(-1,false,false,false)==FenceOutcome::Failed?0:1;
    if(test>=12&&test<=19) {
        float rect[]{-.8f,-1,.8f,1},content[]{.1f,0,.8f,1};
        unsigned windows=test==18?2:1,cursors=test==13?1:0,effects=test==14?1:0;
        if(test==17)rect[0]=-.7f;
        if(test==19){rect[0]=-1.2f;content[0]=-.1f;rect[2]=1.2f;content[2]=1.2f;}
        bool result=canScanout(windows,cursors,effects,test==15,true,test==16,rect,content);
        return result==(test==12)?0:1;
    }
    if(test==0){if(!validPool(m))return 1;m.width=0;if(validPool(m))return 2;m.width=UINT32_MAX;if(validPool(m))return 3;return 0;}
    if(test==1){m.width=8192;m.height=8192;m.count=4;if(validPool(m))return 1;m.count=1;if(validPool(m))return 2;m.count=5;if(validPool(m))return 3;return 0;}
    if(test==2){m.version=2;if(validPool(m))return 1;m.version=Version;m.format=50;if(validPool(m))return 2;m.format=44;m.flags=3;if(validPool(m))return 3;return 0;}
    if(test==3||test==4){interruptions=test==4?1:0;if(!sendMessage(1,m,7))return 1;if(wireSize!=sizeof(m)||rightsSent!=1||sendCalls!=(test==4?2:1))return 2;for(size_t i=0;i<sizeof(m);i++)if(wire[i]!=((unsigned char*)&m)[i])return 3;return 0;}
    wireSize=sizeof(m);memcpy(wire,&m,sizeof(m));Message out;int fd=-1;
    if(test==5){fdCount=1;fdPosition=12;interruptions=1;if(!receiveMessage(1,out,fd)||fd!=7||closed||position!=sizeof(m))return 1;return 0;}
    if(test==6){fdCount=1;wireSize=17;if(receiveMessage(1,out,fd)||fd!=-1||closed!=(1<<7))return 1;return 0;}
    if(test==7){fdCount=2;if(receiveMessage(1,out,fd)||fd!=-1||closed!=((1<<7)|(1<<8)))return 1;return 0;}
    if(test==8){fdCount=1;controlFlags=MSG_CTRUNC;if(receiveMessage(1,out,fd)||fd!=-1||closed!=(1<<7))return 1;return 0;}
    if(test==9){fdCount=1;wire[0]=0;if(receiveMessage(1,out,fd)||fd!=-1||closed!=(1<<7))return 1;return 0;}
    if(test==10){fdCount=1;wire[4]=2;if(receiveMessage(1,out,fd)||fd!=-1||closed!=(1<<7))return 1;return 0;}
    if(test==11){if(!receiveMessage(1,out,fd)||fd!=-1||closed||out.window!=12)return 1;return 0;}
    return 99;
}
