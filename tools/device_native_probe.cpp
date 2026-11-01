// SPDX-License-Identifier: MIT
// Standalone Android diagnostics. No Activity, APK, app data or display settings.
#include <android/hardware_buffer.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <ctime>
#include <pthread.h>
#include <unistd.h>
#include <dlfcn.h>
#include <poll.h>
#include <sys/un.h>
#include <atomic>
#include <chrono>
#include <thread>
#include <vector>
#include <stdexcept>
#include "../app/src/main/cpp/winlator/renderer/displayx/Server.hpp"
#define LOGI(...) ((void)0)
#include "../app/src/main/cpp/winlator/audio_pacer.h"

static void require(bool okay,const char* phase) {if(!okay)throw std::runtime_error(phase);}
static long long nanos(const timespec& time) {return time.tv_sec*1000000000LL+time.tv_nsec;}
static void audio() {
    PacerContext context{};context.capacity_bytes=8192;context.available_bytes=3840;
    context.frame_size_bytes=4;context.sample_rate=48000;context.running=2;
    pthread_mutex_init(&context.mutex,nullptr);pthread_cond_init(&context.cond_not_empty,nullptr);
    pthread_cond_init(&context.cond_not_full,nullptr);pthread_t thread;
    require(pthread_create(&thread,nullptr,pacer_consumer_thread_func,&context)==0,"audio thread");
    clockid_t cpu;pthread_getcpuclockid(thread,&cpu);timespec before{},after{};
    clock_gettime(cpu,&before);std::this_thread::sleep_for(std::chrono::milliseconds(150));clock_gettime(cpu,&after);
    pthread_mutex_lock(&context.mutex);bool idle=context.available_bytes==3840;
    context.running=1;pthread_cond_broadcast(&context.cond_not_empty);pthread_mutex_unlock(&context.mutex);
    std::this_thread::sleep_for(std::chrono::milliseconds(150));
    pthread_mutex_lock(&context.mutex);bool drained=context.available_bytes==0;
    context.running=0;pthread_cond_broadcast(&context.cond_not_empty);pthread_mutex_unlock(&context.mutex);
    pthread_join(thread,nullptr);pthread_mutex_destroy(&context.mutex);
    pthread_cond_destroy(&context.cond_not_empty);pthread_cond_destroy(&context.cond_not_full);
    double cpuMs=(nanos(after)-nanos(before))/1e6;
    printf("Audio paused CPU %.3f ms / 150 ms; retained=%d resumed/drained=%d\n",cpuMs,idle,drained);
    require(idle&&drained&&cpuMs<20,"audio pause/resume");
}

struct Vulkan {
    VkInstance instance{};VkPhysicalDevice gpu{};VkDevice device{};VkQueue queue{};
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID ahb{};PFN_vkGetFenceFdKHR fenceFd{};
    ~Vulkan(){if(device){vkDeviceWaitIdle(device);vkDestroyDevice(device,nullptr);}if(instance)vkDestroyInstance(instance,nullptr);}
    Vulkan() {
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};app.pApplicationName="WinXclipse native probe";app.apiVersion=VK_API_VERSION_1_1;
        VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};info.pApplicationInfo=&app;
        require(vkCreateInstance(&info,nullptr,&instance)==VK_SUCCESS,"Vulkan instance");
        uint32_t count=0;vkEnumeratePhysicalDevices(instance,&count,nullptr);require(count>0&&count<32,"Vulkan GPU count");
        std::vector<VkPhysicalDevice> gpus(count);vkEnumeratePhysicalDevices(instance,&count,gpus.data());gpu=gpus[0];
        VkPhysicalDeviceProperties properties;vkGetPhysicalDeviceProperties(gpu,&properties);
        printf("GPU %s; Vulkan %u.%u.%u\n",properties.deviceName,VK_VERSION_MAJOR(properties.apiVersion),
                VK_VERSION_MINOR(properties.apiVersion),VK_VERSION_PATCH(properties.apiVersion));
        vkEnumerateDeviceExtensionProperties(gpu,nullptr,&count,nullptr);std::vector<VkExtensionProperties> extensions(count);
        vkEnumerateDeviceExtensionProperties(gpu,nullptr,&count,extensions.data());
        const char* required[]={VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
                VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,VK_KHR_EXTERNAL_FENCE_FD_EXTENSION_NAME};
        for(auto* name:required) {
            bool found=false;for(auto& extension:extensions)found|=!strcmp(name,extension.extensionName);
            printf("Extension %s %s\n",name,found?"available":"MISSING");require(found,name);
        }
        vkGetPhysicalDeviceQueueFamilyProperties(gpu,&count,nullptr);std::vector<VkQueueFamilyProperties> families(count);
        vkGetPhysicalDeviceQueueFamilyProperties(gpu,&count,families.data());uint32_t family=0;
        while(family<count&&!(families[family].queueFlags&VK_QUEUE_GRAPHICS_BIT))++family;require(family<count,"graphics queue");
        float priority=1;VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};qi.queueFamilyIndex=family;qi.queueCount=1;qi.pQueuePriorities=&priority;
        VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};di.queueCreateInfoCount=1;di.pQueueCreateInfos=&qi;
        di.enabledExtensionCount=3;di.ppEnabledExtensionNames=required;
        require(vkCreateDevice(gpu,&di,nullptr,&device)==VK_SUCCESS,"Vulkan device");vkGetDeviceQueue(device,family,0,&queue);
        ahb=reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(vkGetDeviceProcAddr(device,"vkGetAndroidHardwareBufferPropertiesANDROID"));
        fenceFd=reinterpret_cast<PFN_vkGetFenceFdKHR>(vkGetDeviceProcAddr(device,"vkGetFenceFdKHR"));require(ahb&&fenceFd,"extension functions");
    }
    int producerFence() {
        VkExportFenceCreateInfo exportInfo{VK_STRUCTURE_TYPE_EXPORT_FENCE_CREATE_INFO};exportInfo.handleTypes=VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT;
        VkFenceCreateInfo info{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};info.pNext=&exportInfo;VkFence fence{};
        require(vkCreateFence(device,&info,nullptr,&fence)==VK_SUCCESS,"export fence create");
        VkResult result=vkQueueSubmit(queue,0,nullptr,fence);int fd=-1;
        if(result==VK_SUCCESS) {
            VkFenceGetFdInfoKHR get{VK_STRUCTURE_TYPE_FENCE_GET_FD_INFO_KHR};get.fence=fence;get.handleType=VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT;
            result=fenceFd(device,&get,&fd);
        }
        vkDeviceWaitIdle(device);vkDestroyFence(device,fence,nullptr);require(result==VK_SUCCESS,"SYNC_FD export");return fd;
    }
    void import(AHardwareBuffer* buffer,VkFormat expected) {
        VkAndroidHardwareBufferFormatPropertiesANDROID format{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
        VkAndroidHardwareBufferPropertiesANDROID properties{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};properties.pNext=&format;
        require(ahb(device,buffer,&properties)==VK_SUCCESS,"AHB properties");
        printf("AHB expected=%u actual=%u memoryTypes=0x%x\n",expected,format.format,properties.memoryTypeBits);
        require(format.format==expected&&properties.memoryTypeBits,"AHB format/memory");
        VkExternalMemoryImageCreateInfo external{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};external.handleTypes=VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
        VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};info.pNext=&external;info.imageType=VK_IMAGE_TYPE_2D;
        info.format=expected;info.extent={16,16,1};info.mipLevels=1;info.arrayLayers=1;info.samples=VK_SAMPLE_COUNT_1_BIT;
        info.tiling=VK_IMAGE_TILING_OPTIMAL;info.usage=VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        VkImage image{};require(vkCreateImage(device,&info,nullptr,&image)==VK_SUCCESS,"AHB image create");
        VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};dedicated.image=image;
        VkImportAndroidHardwareBufferInfoANDROID imported{VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};imported.pNext=&dedicated;imported.buffer=buffer;
        VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};allocation.pNext=&imported;allocation.allocationSize=properties.allocationSize;
        while(!(properties.memoryTypeBits&(1u<<allocation.memoryTypeIndex)))++allocation.memoryTypeIndex;
        VkDeviceMemory memory{};VkResult result=vkAllocateMemory(device,&allocation,nullptr,&memory);
        if(result==VK_SUCCESS)result=vkBindImageMemory(device,image,memory,0);
        vkDestroyImage(device,image,nullptr);if(memory)vkFreeMemory(device,memory,nullptr);require(result==VK_SUCCESS,"AHB memory import/bind");
    }
};

static void transport(Vulkan& vk) {
    AHardwareBuffer* buffers[2]{};
    struct Buffers {AHardwareBuffer** values;~Buffers(){for(int i=0;i<2;i++)if(values[i])AHardwareBuffer_release(values[i]);}} cleanup{buffers};
    AHardwareBuffer_Desc description{};description.width=description.height=16;description.layers=1;
    description.format=AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;description.usage=AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE|AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER;
    for(auto& buffer:buffers)require(AHardwareBuffer_allocate(&description,&buffer)==0,"AHB allocation");
    vk.import(buffers[0],VK_FORMAT_R8G8B8A8_UNORM);
    int sockets[2],pipeFds[2];require(socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,sockets)==0,"socketpair");
    require(pipe(pipeFds)==0,"descriptor pipe");
    displayx::Message sentMessage;sentMessage.type=displayx::Complete;sentMessage.window=71;sentMessage.sequence=19;
    bool sentDescriptor=displayx::sendMessage(sockets[0],sentMessage,pipeFds[0]);
    displayx::Message received;int receivedFd=-1;
    bool receivedDescriptor=displayx::receiveMessage(sockets[1],received,receivedFd);
    char value=0;bool identity=false;
    if(receivedDescriptor&&receivedFd>=0){write(pipeFds[1],"x",1);identity=read(receivedFd,&value,1)==1&&value=='x';close(receivedFd);}
    close(pipeFds[0]);close(pipeFds[1]);
    bool sentBuffer=AHardwareBuffer_sendHandleToUnixSocket(buffers[0],sockets[0])==0;AHardwareBuffer* shared=nullptr;
    bool receivedBuffer=sentBuffer&&AHardwareBuffer_recvHandleFromUnixSocket(sockets[1],&shared)==0;
    close(sockets[0]);close(sockets[1]);
    require(sentDescriptor&&receivedDescriptor&&identity&&received.window==71&&received.sequence==19,"real SCM_RIGHTS message");
    require(receivedBuffer&&shared,"real AHB handle transfer");
    try{vk.import(shared,VK_FORMAT_R8G8B8A8_UNORM);}catch(...){AHardwareBuffer_release(shared);throw;}
    AHardwareBuffer_release(shared);printf("Anonymous Unix socket AHB handle + protocol/SCM_RIGHTS transfer PASS\n");
    int exported=vk.producerFence();
    if(exported>=0){pollfd ready{exported,POLLIN,0};bool signalled=poll(&ready,1,1000)>0&&(ready.revents&POLLIN);close(exported);require(signalled,"SYNC_FD completion");}
    printf("Vulkan SYNC_FD export/completion PASS\n");
    AHardwareBuffer* bgra=nullptr;description.format=5;
    int allocated=AHardwareBuffer_allocate(&description,&bgra);
    if(allocated==0){try{vk.import(bgra,VK_FORMAT_B8G8R8A8_UNORM);}catch(...){AHardwareBuffer_release(bgra);throw;}AHardwareBuffer_release(bgra);printf("BGRA import PASS\n");}
    else printf("BGRA allocation unavailable (%d): format falls back to normal WSI\n",allocated);
    char path[100];snprintf(path,sizeof(path),"/data/local/tmp/winx-native-%d.sock",getpid());
    std::atomic<unsigned> notices{0};displayx::Server server(path,[&](uint32_t){++notices;return true;});
    int socketFd=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);require(socketFd>=0,"client socket");
    struct Socket {int fd;~Socket(){shutdown(fd,SHUT_RDWR);close(fd);}} client{socketFd};
    timeval timeout{2,0};setsockopt(socketFd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
    sockaddr_un address{};address.sun_family=AF_UNIX;strcpy(address.sun_path,path);
    require(connect(socketFd,reinterpret_cast<sockaddr*>(&address),sizeof(address))==0,"client connect");
    displayx::Message pool;pool.type=displayx::Hello;pool.window=7;pool.width=pool.height=16;pool.count=2;pool.format=37;pool.flags=2;
    require(displayx::sendMessage(socketFd,pool),"pool send");
    for(auto* buffer:buffers)require(AHardwareBuffer_sendHandleToUnixSocket(buffer,socketFd)==0,"AHB handle send");
    displayx::Message reply;int fd=-1;
    require(displayx::receiveMessage(socketFd,reply,fd)&&reply.type==displayx::Ready&&fd<0,"pool ready");
    require(displayx::receiveMessage(socketFd,reply,fd)&&reply.type==displayx::Pacing&&fd<0,"pacing");
    pool.type=displayx::Present;pool.sequence=1;int producer=vk.producerFence();
    bool sent=displayx::sendMessage(socketFd,pool,producer);if(producer>=0)close(producer);require(sent,"present send");
    for(int n=0;n<200&&!notices;n++)std::this_thread::sleep_for(std::chrono::milliseconds(5));
    auto frame=server.take(7);require(bool(frame)&&frame->buffer&&frame->message.sequence==1,"received frame");
    require(poll(nullptr,0,0)==0,"poll availability");
    pollfd pending{socketFd,POLLIN,0};require(poll(&pending,1,20)==0,"no early slot release");
    frame->complete();require(displayx::receiveMessage(socketFd,reply,fd)&&reply.type==displayx::Complete,"frame complete");
    frame.reset();require(displayx::receiveMessage(socketFd,reply,fd)&&reply.type==displayx::Release&&(reply.flags&1),"frame release");
    printf("DisplayX real AHB IPC + SYNC_FD + complete/release PASS\n");
}

int main(int argc,char** argv) {
    try {
        audio();Vulkan vk;bool ipc=true;
        try{transport(vk);}catch(const std::exception& error){
            if(strcmp(error.what(),"DisplayX socket bind failed")||errno!=EACCES)throw;
            ipc=false;printf("SKIP DisplayX file-socket IPC: Android denies shell bind in /data/local/tmp; test inside app UID required\n");
        }
        void* android=dlopen("libandroid.so",RTLD_NOW|RTLD_LOCAL);require(android,"libandroid");
        const char* symbols[]={"ASurfaceControl_createFromWindow","ASurfaceTransaction_setBuffer","ASurfaceTransaction_setOnComplete","ASurfaceTransactionStats_getPreviousReleaseFenceFd"};
        for(auto* symbol:symbols){bool available=dlsym(android,symbol)!=nullptr;printf("SurfaceControl %s %s\n",symbol,available?"available":"MISSING");require(available,symbol);}dlclose(android);
        for(int n=1;n<argc;n++){void* library=dlopen(argv[n],RTLD_NOW|RTLD_LOCAL);if(!library)printf("dlopen: %s\n",dlerror());require(library,argv[n]);printf("Module load PASS %s\n",argv[n]);dlclose(library);}
        printf("PASS native capabilities/lifecycle/module loading; IPC=%s; no screen presentation or gameplay tested\n",ipc?"PASS":"SKIPPED");return 0;
    } catch(const std::exception& error){fprintf(stderr,"FAIL: %s (errno=%d %s)\n",error.what(),errno,strerror(errno));return 1;}
}
