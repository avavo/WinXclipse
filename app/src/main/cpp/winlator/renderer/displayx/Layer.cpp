// SPDX-License-Identifier: MIT
// WinXclipse DisplayX guest layer. Original implementation, not a renamed X11
// HUD or a copy of the unlicensed DisplayX layer. Unsupported WSI stays intact.
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include "Protocol.hpp"
#include <sys/un.h>
#include <poll.h>
#include <atomic>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <vector>
#include <array>
#include <unordered_map>
#include <unordered_set>
#include <memory>
#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <limits>

#define EXPORT extern "C" __attribute__((visibility("default")))
#define INFO(...) __android_log_print(ANDROID_LOG_INFO,"DisplayXLayer",__VA_ARGS__)
#define WARN(...) __android_log_print(ANDROID_LOG_WARN,"DisplayXLayer",__VA_ARGS__)
namespace {
using namespace displayx;
template<class T> void* key(T h){return h?*reinterpret_cast<void**>(h):nullptr;}
struct Instance {
    VkInstance handle{};PFN_vkGetInstanceProcAddr get{};PFN_vkGetDeviceProcAddr getDevice{};
    uint32_t apiVersion=VK_API_VERSION_1_0;
    std::mutex mutex;std::unordered_map<VkSurfaceKHR,uint32_t> windows;
};
struct Device {
    VkDevice handle{};VkPhysicalDevice gpu{};std::shared_ptr<Instance> instance;
    PFN_vkGetDeviceProcAddr get{};uint32_t family=0;VkQueue acquireQueue{};
    PFN_vkSetDeviceLoaderData setLoaderData{};
    std::atomic<bool> enabled{false};
    std::atomic<uint32_t> activeSwapchains{0};
    // All exposed queue operations use this lock. Internal acquire submissions
    // must obey Vulkan's external queue synchronization too.
    std::recursive_mutex queueMutex;
    std::mutex objectMutex;
    std::unordered_set<VkImage> directImages;
    std::unordered_map<VkImageView,bool> directViews;
    std::unordered_set<VkFramebuffer> directFramebuffers;
    std::unordered_map<VkRenderPass,VkRenderPass> renderPasses;
    std::unordered_set<VkSwapchainKHR> retiredLegacy;
    // Lifetime follows this device; repeated device recreation must not leave
    // stale queue handles in a process-wide family registry.
    std::unordered_map<VkQueue,uint32_t> queueFamilies;
#define DEVICE_PROCS(X) \
    X(DestroyDevice) X(CreateSwapchainKHR) X(DestroySwapchainKHR) X(GetSwapchainImagesKHR) \
    X(AcquireNextImageKHR) X(AcquireNextImage2KHR) X(QueuePresentKHR) X(QueueSubmit) X(QueueSubmit2) \
    X(QueueWaitIdle) X(DeviceWaitIdle) X(QueueBindSparse) X(GetDeviceQueue) X(GetDeviceQueue2) \
    X(CreateCommandPool) X(DestroyCommandPool) X(AllocateCommandBuffers) X(ResetCommandBuffer) \
    X(BeginCommandBuffer) X(EndCommandBuffer) X(CmdPipelineBarrier) \
    X(CreateImage) X(DestroyImage) X(AllocateMemory) X(FreeMemory) X(BindImageMemory) \
    X(GetAndroidHardwareBufferPropertiesANDROID) X(CreateFence) X(DestroyFence) \
    X(WaitForFences) X(ResetFences) X(GetFenceFdKHR) X(WaitForPresentKHR) \
    X(CmdPipelineBarrier2) X(CreateImageView) X(DestroyImageView) X(CreateFramebuffer) X(DestroyFramebuffer) \
    X(CreateRenderPass) X(CreateRenderPass2) X(DestroyRenderPass) X(CmdBeginRenderPass) X(CmdBeginRenderPass2) \
    X(SetHdrMetadataEXT) X(GetSwapchainStatusKHR) X(CmdWaitEvents) X(CmdWaitEvents2)
#define DECLARE(n) PFN_vk##n n{};
    DEVICE_PROCS(DECLARE)
#undef DECLARE
};
struct Slot {
    AHardwareBuffer* buffer{};VkImage image{};VkDeviceMemory memory{};
    VkCommandBuffer acquire{},release{};VkFence exported{};
    bool busy=false,acquired=false,initialized=false;
    uint64_t sequence=0,presentId=0;
};
struct Swapchain {
    std::shared_ptr<Device> d;VkSwapchainKHR handle{};Message pool;
    VkCommandPool commands{};std::array<Slot,MaxImages> images;
    int socket=-1;std::thread reader;std::atomic<bool> connected{false};
    std::atomic<bool> closing{false},retired{false};
    std::mutex mutex,writer;std::condition_variable changed;
    uint64_t nextSequence=0,completedId=0;uint32_t nextSlot=0;
    std::atomic<uint32_t> fpsLimit{0};
    std::mutex pacing;
    std::chrono::steady_clock::time_point nextFrame{};
    ~Swapchain(){cleanup();}
    void cleanup() {
        closing=true;connected=false;if(socket>=0)shutdown(socket,SHUT_RDWR);changed.notify_all();
        if(reader.joinable())reader.join();
        if(socket>=0){close(socket);socket=-1;}
        // The caller holds queueMutex. Swapchain teardown is not a per-frame
        // device stall; queued acquire/release command buffers must finish.
        if(commands&&d->acquireQueue)d->QueueWaitIdle(d->acquireQueue);
        for(auto& s:images) {
            if(s.exported)d->DestroyFence(d->handle,s.exported,nullptr);
            if(s.image)d->DestroyImage(d->handle,s.image,nullptr);
            if(s.memory)d->FreeMemory(d->handle,s.memory,nullptr);
            if(s.buffer)AHardwareBuffer_release(s.buffer);
            s={};
        }
        if(commands){d->DestroyCommandPool(d->handle,commands,nullptr);commands={};}
    }
    bool connect(const char* path) {
        if(!path||strlen(path)>=sizeof(sockaddr_un::sun_path))return false;
        socket=::socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);if(socket<0)return false;
        timeval timeout{3,0};setsockopt(socket,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
        setsockopt(socket,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
        sockaddr_un address{};address.sun_family=AF_UNIX;strcpy(address.sun_path,path);
        if(::connect(socket,reinterpret_cast<sockaddr*>(&address),sizeof(address)))return false;
        if(!sendMessage(socket,pool))return false;
        for(uint32_t i=0;i<pool.count;i++)if(AHardwareBuffer_sendHandleToUnixSocket(images[i].buffer,socket))return false;
        Message reply;int fd=-1;
        if(!receiveMessage(socket,reply,fd))return false;
        if(fd>=0){close(fd);return false;}
        if(reply.type!=Ready||reply.window!=pool.window||reply.count!=pool.count)return false;
        connected=true;
        try{reader=std::thread([this]{readLoop();});}catch(...){connected=false;return false;}
        return true;
    }
    void readLoop() {
        while(connected) {
            pollfd input{socket,POLLIN,0};int ready;
            do{ready=poll(&input,1,-1);}while(ready<0&&errno==EINTR);
            if(ready<=0||!(input.revents&POLLIN))break;
            Message m;int fd=-1;if(!receiveMessage(socket,m,fd))break;
            if(fd>=0){close(fd);break;}
            if(m.type==Pacing&&m.window==pool.window){fpsLimit=std::min(m.width,1000u);continue;}
            if(m.window!=pool.window||m.slot>=pool.count||(m.type!=Release&&m.type!=Complete))break;
            {std::lock_guard<std::mutex> lock(mutex);auto& s=images[m.slot];
                if(!s.busy||s.acquired||m.sequence!=s.sequence)break;
                if(m.type==Complete||m.flags&1)completedId=std::max(completedId,s.presentId);
                if(m.type==Release)s.busy=false;
            }
            changed.notify_all();
        }
        connected=false;if(!closing&&!retired)d->enabled=false;changed.notify_all();
    }
    bool makeImages(const VkSwapchainCreateInfoKHR& info) {
        VkCommandPoolCreateInfo cp{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        cp.queueFamilyIndex=d->family;cp.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        if(d->CreateCommandPool(d->handle,&cp,nullptr,&commands)!=VK_SUCCESS)return false;
        for(uint32_t n=0;n<pool.count;n++) {
            Slot& s=images[n];
            AHardwareBuffer_Desc desc{};desc.width=pool.width;desc.height=pool.height;desc.layers=1;
            // HAL BGRA_8888=5 is accepted only when allocation AND Vulkan's AHB
            // format query confirm it. No reinterpreting RGBA memory as BGRA.
            desc.format=info.imageFormat==VK_FORMAT_R8G8B8A8_UNORM?1:5;
            desc.usage=AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER|AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE|AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
            if(AHardwareBuffer_allocate(&desc,&s.buffer)) {
                desc.usage&=~AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
                if(AHardwareBuffer_allocate(&desc,&s.buffer))return false;
            }
            VkAndroidHardwareBufferFormatPropertiesANDROID format{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
            VkAndroidHardwareBufferPropertiesANDROID props{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};props.pNext=&format;
            if(d->GetAndroidHardwareBufferPropertiesANDROID(d->handle,s.buffer,&props)!=VK_SUCCESS||format.format!=info.imageFormat)return false;
            VkExternalMemoryImageCreateInfo external{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
            external.handleTypes=VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
            VkImageCreateInfo image{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};image.pNext=&external;
            VkImageFormatListCreateInfo formats{VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO};
            if(info.flags&VK_SWAPCHAIN_CREATE_MUTABLE_FORMAT_BIT_KHR) {
                image.flags=VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT|VK_IMAGE_CREATE_EXTENDED_USAGE_BIT;
                for(auto* p=reinterpret_cast<const VkBaseInStructure*>(info.pNext);p;p=p->pNext)
                    if(p->sType==VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO){formats=*reinterpret_cast<const VkImageFormatListCreateInfo*>(p);formats.pNext=nullptr;external.pNext=&formats;}
            }
            image.imageType=VK_IMAGE_TYPE_2D;image.format=info.imageFormat;image.extent={pool.width,pool.height,1};
            image.mipLevels=image.arrayLayers=1;image.samples=VK_SAMPLE_COUNT_1_BIT;image.tiling=VK_IMAGE_TILING_OPTIMAL;
            image.usage=info.imageUsage|VK_IMAGE_USAGE_SAMPLED_BIT;image.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
            if(d->CreateImage(d->handle,&image,nullptr,&s.image)!=VK_SUCCESS)return false;
            VkImportAndroidHardwareBufferInfoANDROID imported{VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};imported.buffer=s.buffer;
            VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};dedicated.image=s.image;dedicated.pNext=&imported;
            VkMemoryAllocateInfo memory{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};memory.pNext=&dedicated;
            memory.allocationSize=props.allocationSize;
            if(!props.memoryTypeBits)return false;
            memory.memoryTypeIndex=uint32_t(__builtin_ctz(props.memoryTypeBits));
            if(d->AllocateMemory(d->handle,&memory,nullptr,&s.memory)!=VK_SUCCESS
                ||d->BindImageMemory(d->handle,s.image,s.memory,0)!=VK_SUCCESS)return false;
            VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ca.commandPool=commands;ca.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ca.commandBufferCount=1;
            if(d->AllocateCommandBuffers(d->handle,&ca,&s.acquire)!=VK_SUCCESS||d->AllocateCommandBuffers(d->handle,&ca,&s.release)!=VK_SUCCESS)return false;
            if(d->setLoaderData&&(d->setLoaderData(d->handle,s.acquire)!=VK_SUCCESS||d->setLoaderData(d->handle,s.release)!=VK_SUCCESS))return false;
            VkExportFenceCreateInfo exported{VK_STRUCTURE_TYPE_EXPORT_FENCE_CREATE_INFO};exported.handleTypes=VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT;
            VkFenceCreateInfo fence{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};fence.pNext=&exported;
            if(d->CreateFence(d->handle,&fence,nullptr,&s.exported)!=VK_SUCCESS)return false;
        }
        return true;
    }
    void pace() {
        std::unique_lock<std::mutex> lock(pacing);uint32_t limit=fpsLimit.load();
        if(!limit){nextFrame={};return;}
        auto now=std::chrono::steady_clock::now();
        if(nextFrame>now)std::this_thread::sleep_until(nextFrame);
        auto interval=std::chrono::nanoseconds(1'000'000'000ULL/limit);
        nextFrame=std::max(nextFrame+interval,now+interval);
    }
    VkResult record(Slot& s,bool acquire) {
        VkCommandBuffer cb=acquire?s.acquire:s.release;
        VkResult result=d->ResetCommandBuffer(cb,0);if(result!=VK_SUCCESS)return result;
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};begin.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        result=d->BeginCommandBuffer(cb,&begin);if(result!=VK_SUCCESS)return result;
        VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};b.image=s.image;
        b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        b.srcQueueFamilyIndex=acquire?VK_QUEUE_FAMILY_FOREIGN_EXT:d->family;
        b.dstQueueFamilyIndex=acquire?d->family:VK_QUEUE_FAMILY_FOREIGN_EXT;
        b.oldLayout=acquire?(s.initialized?VK_IMAGE_LAYOUT_GENERAL:VK_IMAGE_LAYOUT_UNDEFINED):VK_IMAGE_LAYOUT_GENERAL;
        b.newLayout=VK_IMAGE_LAYOUT_GENERAL;
        b.srcAccessMask=acquire?0:VK_ACCESS_MEMORY_WRITE_BIT|VK_ACCESS_MEMORY_READ_BIT;
        b.dstAccessMask=acquire?VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT:0;
        d->CmdPipelineBarrier(cb,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,0,0,nullptr,0,nullptr,1,&b);
        return d->EndCommandBuffer(cb);
    }
};
std::mutex registryMutex;
std::unordered_map<void*,std::shared_ptr<Instance>> instances;
std::unordered_map<void*,std::shared_ptr<Device>> devices;
std::unordered_map<VkSwapchainKHR,std::shared_ptr<Swapchain>> swapchains;
template<class T> std::shared_ptr<Instance> inst(T h){std::lock_guard<std::mutex> l(registryMutex);auto i=instances.find(key(h));return i==instances.end()?nullptr:i->second;}
template<class T> std::shared_ptr<Device> dev(T h){std::lock_guard<std::mutex> l(registryMutex);auto i=devices.find(key(h));return i==devices.end()?nullptr:i->second;}
std::shared_ptr<Swapchain> chain(VkSwapchainKHR h){std::lock_guard<std::mutex> l(registryMutex);auto i=swapchains.find(h);return i==swapchains.end()?nullptr:i->second;}
bool requested(){const char* value=getenv("WINXCLIPSE_DISPLAYX_SOCKET");return value&&*value;}
template<class T> T* linkInfo(const void* next,VkStructureType type) {
    for(auto* node=reinterpret_cast<const VkBaseInStructure*>(next);node;node=node->pNext)
        if(node->sType==type){auto* info=reinterpret_cast<T*>(const_cast<VkBaseInStructure*>(node));if(info->function==VK_LAYER_LINK_INFO)return info;}
    return nullptr;
}
VKAPI_ATTR VkResult VKAPI_CALL createInstance(const VkInstanceCreateInfo* info,const VkAllocationCallbacks* allocator,VkInstance* out) {
    auto* link=linkInfo<VkLayerInstanceCreateInfo>(info->pNext,VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO);
    if(!link||!link->u.pLayerInfo)return VK_ERROR_INITIALIZATION_FAILED;
    auto get=link->u.pLayerInfo->pfnNextGetInstanceProcAddr;link->u.pLayerInfo=link->u.pLayerInfo->pNext;
    auto create=reinterpret_cast<PFN_vkCreateInstance>(get(VK_NULL_HANDLE,"vkCreateInstance"));
    VkResult r=create(info,allocator,out);if(r!=VK_SUCCESS)return r;
    auto i=std::make_shared<Instance>();i->handle=*out;i->get=get;
    if(info->pApplicationInfo&&info->pApplicationInfo->apiVersion)i->apiVersion=info->pApplicationInfo->apiVersion;
    std::lock_guard<std::mutex> lock(registryMutex);instances[key(*out)]=i;return r;
}
VKAPI_ATTR void VKAPI_CALL destroyInstance(VkInstance handle,const VkAllocationCallbacks* allocator) {
    auto i=inst(handle);if(!i)return;
    {std::lock_guard<std::mutex> lock(registryMutex);for(auto it=instances.begin();it!=instances.end();)if(it->second==i)it=instances.erase(it);else ++it;}
    reinterpret_cast<PFN_vkDestroyInstance>(i->get(handle,"vkDestroyInstance"))(handle,allocator);
}
VKAPI_ATTR VkResult VKAPI_CALL enumeratePhysical(VkInstance handle,uint32_t* count,VkPhysicalDevice* out) {
    auto i=inst(handle);if(!i)return VK_ERROR_INITIALIZATION_FAILED;
    VkResult r=reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(i->get(handle,"vkEnumeratePhysicalDevices"))(handle,count,out);
    if(out&&(r==VK_SUCCESS||r==VK_INCOMPLETE)){std::lock_guard<std::mutex> l(registryMutex);for(uint32_t n=0;n<*count;n++)instances[key(out[n])]=i;}
    return r;
}
VKAPI_ATTR VkResult VKAPI_CALL enumerateDeviceExtensions(VkPhysicalDevice gpu,const char* layer,uint32_t* count,VkExtensionProperties* out) {
    auto i=inst(gpu);if(!i)return VK_ERROR_INITIALIZATION_FAILED;
    auto next=reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(i->get(i->handle,"vkEnumerateDeviceExtensionProperties"));
    if(layer&&!strcmp(layer,"VK_LAYER_WINXCLIPSE_displayx")){*count=0;return VK_SUCCESS;}
    if(layer||!requested())return next(gpu,layer,count,out);
    uint32_t n=0;VkResult result=next(gpu,nullptr,&n,nullptr);if(result!=VK_SUCCESS)return result;
    std::vector<VkExtensionProperties> extensions(n);result=next(gpu,nullptr,&n,extensions.data());
    if(result!=VK_SUCCESS)return result;extensions.resize(n);
    // A compositor GPU fence is not proof that SurfaceFlinger displayed a frame.
    // Do not advertise native present-wait while replacing native WSI. DXVK uses
    // its ordinary acquire/FIFO pacing instead. Never fake vkWaitForPresent.
    extensions.erase(std::remove_if(extensions.begin(),extensions.end(),[](const auto& e){return !strcmp(e.extensionName,VK_KHR_PRESENT_WAIT_EXTENSION_NAME);}),extensions.end());
    if(!out){*count=uint32_t(extensions.size());return VK_SUCCESS;}
    n=std::min(*count,uint32_t(extensions.size()));for(uint32_t x=0;x<n;x++)out[x]=extensions[x];*count=n;
    return n<extensions.size()?VK_INCOMPLETE:VK_SUCCESS;
}
// WSI structs contain platform pointers but no platform API is called here.
// Keeping their ABI declarations local avoids linking X11/xcb into a Bionic layer.
struct XcbSurface {VkStructureType sType;const void* pNext;VkFlags flags;void* connection;uint32_t window;};
struct XlibSurface {VkStructureType sType;const void* pNext;VkFlags flags;void* display;unsigned long window;};
template<class T> VkResult makeSurface(VkInstance handle,const T* info,const VkAllocationCallbacks* allocator,VkSurfaceKHR* out,const char* name) {
    auto i=inst(handle);if(!i)return VK_ERROR_INITIALIZATION_FAILED;
    using Create=VkResult(VKAPI_PTR*)(VkInstance,const T*,const VkAllocationCallbacks*,VkSurfaceKHR*);
    auto create=reinterpret_cast<Create>(i->get(handle,name));if(!create)return VK_ERROR_EXTENSION_NOT_PRESENT;
    VkResult r=create(handle,info,allocator,out);
    if(r==VK_SUCCESS){std::lock_guard<std::mutex> l(i->mutex);i->windows[*out]=uint32_t(info->window);}return r;
}
VKAPI_ATTR VkResult VKAPI_CALL xcbSurface(VkInstance h,const XcbSurface* i,const VkAllocationCallbacks* a,VkSurfaceKHR* o){return makeSurface(h,i,a,o,"vkCreateXcbSurfaceKHR");}
VKAPI_ATTR VkResult VKAPI_CALL xlibSurface(VkInstance h,const XlibSurface* i,const VkAllocationCallbacks* a,VkSurfaceKHR* o){return makeSurface(h,i,a,o,"vkCreateXlibSurfaceKHR");}
VKAPI_ATTR void VKAPI_CALL destroySurface(VkInstance h,VkSurfaceKHR s,const VkAllocationCallbacks* a) {
    auto i=inst(h);if(!i)return;{std::lock_guard<std::mutex> l(i->mutex);i->windows.erase(s);}
    reinterpret_cast<PFN_vkDestroySurfaceKHR>(i->get(h,"vkDestroySurfaceKHR"))(h,s,a);
}
VKAPI_ATTR VkResult VKAPI_CALL createDevice(VkPhysicalDevice gpu,const VkDeviceCreateInfo* info,const VkAllocationCallbacks* allocator,VkDevice* out) {
    auto i=inst(gpu);if(!i)return VK_ERROR_INITIALIZATION_FAILED;
    auto* link=linkInfo<VkLayerDeviceCreateInfo>(info->pNext,VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO);
    if(!link||!link->u.pLayerInfo)return VK_ERROR_INITIALIZATION_FAILED;
    auto get=link->u.pLayerInfo->pfnNextGetInstanceProcAddr;auto getDevice=link->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    auto* nextLink=link->u.pLayerInfo->pNext;link->u.pLayerInfo=nextLink;
    auto create=reinterpret_cast<PFN_vkCreateDevice>(get(i->handle,"vkCreateDevice"));
    auto d=std::make_shared<Device>();d->instance=i;d->gpu=gpu;d->get=getDevice;
    for(auto* node=reinterpret_cast<const VkBaseInStructure*>(info->pNext);node;node=node->pNext)
        if(node->sType==VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO) {
            auto* callback=reinterpret_cast<const VkLayerDeviceCreateInfo*>(node);
            if(callback->function==VK_LOADER_DATA_CALLBACK)d->setLoaderData=callback->u.pfnSetDeviceLoaderData;
        }
    std::vector<const char*> extensions(info->ppEnabledExtensionNames,info->ppEnabledExtensionNames+info->enabledExtensionCount);
    bool suitable=requested()&&i->apiVersion>=VK_API_VERSION_1_1;
    // These extensions add commands accepting real swapchain handles. Until a
    // complete implementation of their contracts is available, retain the ICD's
    // WSI instead of letting an unhandled command receive our synthetic handle.
    for(const char* e:extensions)
        if(!strcmp(e,"VK_EXT_swapchain_maintenance1")||!strcmp(e,"VK_GOOGLE_display_timing")
            ||!strcmp(e,"VK_KHR_shared_presentable_image")||!strcmp(e,"VK_EXT_full_screen_exclusive")
            ||!strcmp(e,VK_KHR_PRESENT_WAIT_EXTENSION_NAME)||!strcmp(e,"VK_EXT_display_control")
            ||!strcmp(e,"VK_KHR_swapchain_maintenance1")||!strcmp(e,"VK_KHR_present_wait2")||!strcmp(e,"VK_KHR_present_id2"))suitable=false;
    auto enumerate=reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(get(i->handle,"vkEnumerateDeviceExtensionProperties"));
    uint32_t count=0;enumerate(gpu,nullptr,&count,nullptr);std::vector<VkExtensionProperties> available(count);
    if(enumerate(gpu,nullptr,&count,available.data())!=VK_SUCCESS)suitable=false;
    for(const char* required:{VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,VK_KHR_EXTERNAL_FENCE_FD_EXTENSION_NAME}) {
        bool found=std::any_of(available.begin(),available.end(),[&](const auto& p){return !strcmp(p.extensionName,required);});
        suitable&=found;
        if(found&&std::none_of(extensions.begin(),extensions.end(),[&](const char* e){return !strcmp(e,required);}))extensions.push_back(required);
    }
    auto queueProperties=reinterpret_cast<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(get(i->handle,"vkGetPhysicalDeviceQueueFamilyProperties"));
    count=0;queueProperties(gpu,&count,nullptr);std::vector<VkQueueFamilyProperties> families(count);queueProperties(gpu,&count,families.data());
    bool graphics=false;
    for(uint32_t n=0;n<info->queueCreateInfoCount;n++) {
        const auto& q=info->pQueueCreateInfos[n];
        if(!graphics&&q.queueFamilyIndex<count&&(families[q.queueFamilyIndex].queueFlags&VK_QUEUE_GRAPHICS_BIT)&&q.queueCount&&!q.flags){d->family=q.queueFamilyIndex;graphics=true;}
    }
    suitable&=graphics;
    VkDeviceCreateInfo modified=*info;
    if(suitable){modified.enabledExtensionCount=uint32_t(extensions.size());modified.ppEnabledExtensionNames=extensions.data();}
    VkResult result=create(gpu,&modified,allocator,out);
    // An added extension can have driver-specific requirements. Retrying the
    // unchanged request keeps ordinary Wine/Vulkan available.
    if(result!=VK_SUCCESS&&suitable){suitable=false;link->u.pLayerInfo=nextLink;result=create(gpu,info,allocator,out);}
    if(result!=VK_SUCCESS)return result;
    d->handle=*out;
#define LOAD(n) d->n=reinterpret_cast<PFN_vk##n>(getDevice(*out,"vk" #n));
    DEVICE_PROCS(LOAD)
#undef LOAD
    if(!d->QueueSubmit2)d->QueueSubmit2=reinterpret_cast<PFN_vkQueueSubmit2>(getDevice(*out,"vkQueueSubmit2KHR"));
    if(!d->CmdPipelineBarrier2)d->CmdPipelineBarrier2=reinterpret_cast<PFN_vkCmdPipelineBarrier2>(getDevice(*out,"vkCmdPipelineBarrier2KHR"));
    if(!d->CmdWaitEvents2)d->CmdWaitEvents2=reinterpret_cast<PFN_vkCmdWaitEvents2>(getDevice(*out,"vkCmdWaitEvents2KHR"));
    if(!d->CreateRenderPass2)d->CreateRenderPass2=reinterpret_cast<PFN_vkCreateRenderPass2>(getDevice(*out,"vkCreateRenderPass2KHR"));
    if(!d->CmdBeginRenderPass2)d->CmdBeginRenderPass2=reinterpret_cast<PFN_vkCmdBeginRenderPass2>(getDevice(*out,"vkCmdBeginRenderPass2KHR"));
    d->enabled=suitable&&d->GetAndroidHardwareBufferPropertiesANDROID&&d->GetFenceFdKHR&&d->setLoaderData;
    if(d->enabled){d->GetDeviceQueue(*out,d->family,0,&d->acquireQueue);if(d->setLoaderData)d->setLoaderData(*out,d->acquireQueue);}
    {std::lock_guard<std::mutex> lock(registryMutex);devices[key(*out)]=d;}
    INFO("Device %p: AHB transport %s",*out,d->enabled?"available":"passthrough");return result;
}
VKAPI_ATTR void VKAPI_CALL destroyDevice(VkDevice handle,const VkAllocationCallbacks* a) {
    auto d=dev(handle);if(!d)return;std::lock_guard<std::recursive_mutex> q(d->queueMutex);
    d->DeviceWaitIdle(handle);
    std::vector<std::shared_ptr<Swapchain>> retired;
    {std::lock_guard<std::mutex> l(registryMutex);for(auto it=swapchains.begin();it!=swapchains.end();)if(it->second->d==d){retired.push_back(std::move(it->second));it=swapchains.erase(it);}else ++it;
        for(auto it=devices.begin();it!=devices.end();)if(it->second==d)it=devices.erase(it);else ++it;}
    for(auto& s:retired)s->cleanup();retired.clear();d->DestroyDevice(handle,a);
}
VKAPI_ATTR VkResult VKAPI_CALL createSwapchain(VkDevice handle,const VkSwapchainCreateInfoKHR* info,const VkAllocationCallbacks* a,VkSwapchainKHR* out) {
    auto d=dev(handle);if(!d)return VK_ERROR_INITIALIZATION_FAILED;
    std::lock_guard<std::recursive_mutex> q(d->queueMutex);
    if(auto old=chain(info->oldSwapchain)){old->retired=true;old->changed.notify_all();}
    else if(info->oldSwapchain){std::lock_guard<std::mutex> l(d->objectMutex);d->retiredLegacy.insert(info->oldSwapchain);}
    uint32_t window=0;{std::lock_guard<std::mutex> l(d->instance->mutex);auto it=d->instance->windows.find(info->surface);if(it!=d->instance->windows.end())window=it->second;}
    bool eligible=d->enabled&&window&&info->imageArrayLayers==1&&info->minImageCount<=MaxImages
        &&info->imageExtent.width&&info->imageExtent.height&&!(info->flags&~VK_SWAPCHAIN_CREATE_MUTABLE_FORMAT_BIT_KHR)&&info->presentMode<=VK_PRESENT_MODE_FIFO_KHR
        &&info->preTransform==VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
        &&info->imageColorSpace==VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
        &&info->compositeAlpha==VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
        &&(info->imageFormat==VK_FORMAT_R8G8B8A8_UNORM||info->imageFormat==VK_FORMAT_B8G8R8A8_UNORM);
    for(auto* p=reinterpret_cast<const VkBaseInStructure*>(info->pNext);p;p=p->pNext) {
        if(p->sType!=VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO){eligible=false;continue;}
        auto* list=reinterpret_cast<const VkImageFormatListCreateInfo*>(p);
        for(uint32_t n=0;n<list->viewFormatCount;n++) {
            VkFormat f=list->pViewFormats[n];
            bool rgba=info->imageFormat==VK_FORMAT_R8G8B8A8_UNORM;
            eligible&=rgba?(f==VK_FORMAT_R8G8B8A8_UNORM||f==VK_FORMAT_R8G8B8A8_SRGB):(f==VK_FORMAT_B8G8R8A8_UNORM||f==VK_FORMAT_B8G8R8A8_SRGB);
        }
    }
    if(info->imageSharingMode==VK_SHARING_MODE_CONCURRENT)
        for(uint32_t n=0;n<info->queueFamilyIndexCount;n++)eligible&=info->pQueueFamilyIndices[n]==d->family;
    if(eligible) {
        auto s=std::make_shared<Swapchain>();s->d=d;
        s->pool.type=Hello;s->pool.window=window;s->pool.width=info->imageExtent.width;s->pool.height=info->imageExtent.height;
        s->pool.count=std::max(3u,info->minImageCount);s->pool.format=info->imageFormat;s->pool.flags=info->presentMode;
        if(validPool(s->pool)&&s->makeImages(*info)&&s->connect(getenv("WINXCLIPSE_DISPLAYX_SOCKET"))) {
            s->handle=reinterpret_cast<VkSwapchainKHR>(s.get());*out=s->handle;
            {std::lock_guard<std::mutex> l(registryMutex);swapchains[s->handle]=s;}
            d->activeSwapchains.fetch_add(1);
            {std::lock_guard<std::mutex> l(d->objectMutex);for(uint32_t n=0;n<s->pool.count;n++)d->directImages.insert(s->images[n].image);}
            INFO("Direct AHB: window=%u %ux%u images=%u format=%u",window,s->pool.width,s->pool.height,s->pool.count,s->pool.format);return VK_SUCCESS;
        }
        WARN("AHB swapchain unsupported/unavailable; using original X11 WSI");
    }
    // A synthetic oldSwapchain is not a driver handle. Vulkan allows retiring
    // it while creating a new independent swapchain with oldSwapchain=NULL.
    VkSwapchainCreateInfoKHR passthrough=*info;if(chain(passthrough.oldSwapchain))passthrough.oldSwapchain=VK_NULL_HANDLE;
    return d->CreateSwapchainKHR(handle,&passthrough,a,out);
}
VKAPI_ATTR void VKAPI_CALL destroySwapchain(VkDevice handle,VkSwapchainKHR h,const VkAllocationCallbacks* a) {
    auto d=dev(handle);if(!d)return;std::lock_guard<std::recursive_mutex> q(d->queueMutex);
    std::shared_ptr<Swapchain> s;
    {std::lock_guard<std::mutex> l(registryMutex);auto it=swapchains.find(h);if(it!=swapchains.end()){s=std::move(it->second);swapchains.erase(it);}}
    if(s){d->DeviceWaitIdle(handle);{std::lock_guard<std::mutex> l(d->objectMutex);for(auto& slot:s->images)d->directImages.erase(slot.image);}s->cleanup();d->activeSwapchains.fetch_sub(1);}
    else {{std::lock_guard<std::mutex> l(d->objectMutex);d->retiredLegacy.erase(h);}d->DestroySwapchainKHR(handle,h,a);}
}
VKAPI_ATTR VkResult VKAPI_CALL getImages(VkDevice handle,VkSwapchainKHR h,uint32_t* count,VkImage* out) {
    auto s=chain(h);if(!s){auto d=dev(handle);return d?d->GetSwapchainImagesKHR(handle,h,count,out):VK_ERROR_DEVICE_LOST;}
    if(!out){*count=s->pool.count;return VK_SUCCESS;}
    uint32_t n=std::min(*count,s->pool.count);for(uint32_t i=0;i<n;i++)out[i]=s->images[i].image;
    *count=n;return n<s->pool.count?VK_INCOMPLETE:VK_SUCCESS;
}
VKAPI_ATTR VkResult VKAPI_CALL acquire(VkDevice handle,VkSwapchainKHR h,uint64_t timeout,VkSemaphore semaphore,VkFence fence,uint32_t* index) {
    auto s=chain(h);if(!s){auto d=dev(handle);if(!d)return VK_ERROR_DEVICE_LOST;
        {std::lock_guard<std::mutex> l(d->objectMutex);if(d->retiredLegacy.count(h))return VK_ERROR_OUT_OF_DATE_KHR;}
        return d->AcquireNextImageKHR(handle,h,timeout,semaphore,fence,index);}
    std::unique_lock<std::mutex> lock(s->mutex);
    auto available=[&]{if(!s->connected||s->retired)return true;for(uint32_t i=0;i<s->pool.count;i++)if(!s->images[i].busy)return true;return false;};
    if(!available()) {
        if(!timeout)return VK_NOT_READY;
        if(timeout==UINT64_MAX)s->changed.wait(lock,available);
        else if(!s->changed.wait_for(lock,std::chrono::nanoseconds(std::min(timeout,uint64_t(INT64_MAX))),available))return VK_TIMEOUT;
    }
    if(!s->connected||s->retired)return VK_ERROR_OUT_OF_DATE_KHR;
    uint32_t n=s->nextSlot;
    for(uint32_t i=0;i<s->pool.count;i++){n=(s->nextSlot+i)%s->pool.count;if(!s->images[n].busy)break;}
    Slot& slot=s->images[n];slot.busy=true;slot.acquired=true;s->nextSlot=(n+1)%s->pool.count;lock.unlock();
    auto d=s->d;std::lock_guard<std::recursive_mutex> q(d->queueMutex);
    VkResult result=s->record(slot,true);
    if(result==VK_SUCCESS) {
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};submit.commandBufferCount=1;submit.pCommandBuffers=&slot.acquire;
        if(semaphore){submit.signalSemaphoreCount=1;submit.pSignalSemaphores=&semaphore;}
        result=d->QueueSubmit(d->acquireQueue,1,&submit,fence);
    }
    if(result!=VK_SUCCESS){std::lock_guard<std::mutex> l(s->mutex);slot.busy=slot.acquired=false;s->changed.notify_all();return result;}
    slot.initialized=true;*index=n;return VK_SUCCESS;
}
VKAPI_ATTR VkResult VKAPI_CALL acquire2(VkDevice d,const VkAcquireNextImageInfoKHR* i,uint32_t* out) {
    if(chain(i->swapchain))return i->deviceMask==1?acquire(d,i->swapchain,i->timeout,i->semaphore,i->fence,out):VK_ERROR_FEATURE_NOT_PRESENT;
    auto device=dev(d);if(!device||!device->AcquireNextImage2KHR)return VK_ERROR_EXTENSION_NOT_PRESENT;
    {std::lock_guard<std::mutex> l(device->objectMutex);if(device->retiredLegacy.count(i->swapchain))return VK_ERROR_OUT_OF_DATE_KHR;}
    return device->AcquireNextImage2KHR(d,i,out);
}
VKAPI_ATTR VkResult VKAPI_CALL present(VkQueue queue,const VkPresentInfoKHR* info) {
    auto d=dev(queue);if(!d)return VK_ERROR_DEVICE_LOST;
    std::vector<std::shared_ptr<Swapchain>> direct;bool any=false;
    for(uint32_t n=0;n<info->swapchainCount;n++){auto s=chain(info->pSwapchains[n]);any|=bool(s);direct.push_back(std::move(s));}
    // A guest-side cap may sleep; never hold the shared queue mutex during it.
    for(auto& s:direct)if(s)s->pace();
    std::lock_guard<std::recursive_mutex> q(d->queueMutex);
    if(!any)return d->QueuePresentKHR(queue,info);
    auto unsupported=[&]{d->enabled=false;for(uint32_t n=0;info->pResults&&n<info->swapchainCount;n++)info->pResults[n]=VK_ERROR_OUT_OF_DATE_KHR;return VK_ERROR_OUT_OF_DATE_KHR;};
    // Different queue families cannot execute command buffers allocated for our
    // graphics family. Devices may expose dedicated compute/transfer queues.
    // Queue handles are recorded by vkGetDeviceQueue/2 below.
    uint32_t family=UINT32_MAX;
    // Set by the queue-family registry in getQueue; declaration below.
    extern uint32_t familyOf(VkQueue);
    family=familyOf(queue);if(family!=d->family)return unsupported();
    const VkPresentIdKHR* ids=nullptr;
    const VkPresentRegionsKHR* regions=nullptr;
    const VkDeviceGroupPresentInfoKHR* group=nullptr;
    for(auto* p=reinterpret_cast<const VkBaseInStructure*>(info->pNext);p;p=p->pNext) {
        if(p->sType==VK_STRUCTURE_TYPE_PRESENT_ID_KHR)ids=reinterpret_cast<const VkPresentIdKHR*>(p);
        else if(p->sType==VK_STRUCTURE_TYPE_PRESENT_REGIONS_KHR)regions=reinterpret_cast<const VkPresentRegionsKHR*>(p);
        else if(p->sType==VK_STRUCTURE_TYPE_DEVICE_GROUP_PRESENT_INFO_KHR)group=reinterpret_cast<const VkDeviceGroupPresentInfoKHR*>(p);
        else return unsupported();
    }
    if(group) {
        if(group->mode!=VK_DEVICE_GROUP_PRESENT_MODE_LOCAL_BIT_KHR)return unsupported();
        for(uint32_t n=0;n<group->swapchainCount;n++)if(group->pDeviceMasks[n]!=1)return unsupported();
    }
    bool waited=false;VkResult overall=VK_SUCCESS;
    std::vector<VkSwapchainKHR> normal;std::vector<uint32_t> normalIndices,normalPositions;
    for(uint32_t n=0;n<info->swapchainCount;n++) {
        auto s=direct[n];if(!s){normal.push_back(info->pSwapchains[n]);normalIndices.push_back(info->pImageIndices[n]);normalPositions.push_back(n);continue;}
        uint32_t index=info->pImageIndices[n];VkResult result=VK_SUCCESS;
        if(index>=s->pool.count||!s->connected)result=VK_ERROR_OUT_OF_DATE_KHR;
        if(result==VK_SUCCESS) {
            Slot& slot=s->images[index];Message m=s->pool;m.type=Present;m.slot=index;
            {std::lock_guard<std::mutex> lock(s->mutex);
                if(!slot.busy||!slot.acquired)result=VK_ERROR_OUT_OF_DATE_KHR;
                else {slot.acquired=false;slot.sequence=++s->nextSequence;slot.presentId=ids&&n<ids->swapchainCount?ids->pPresentIds[n]:0;m.sequence=slot.sequence;}}
            if(result==VK_SUCCESS)result=s->record(slot,false);
            if(result==VK_SUCCESS)result=d->ResetFences(d->handle,1,&slot.exported);
            if(result==VK_SUCCESS) {
                VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};submit.commandBufferCount=1;submit.pCommandBuffers=&slot.release;
                std::vector<VkPipelineStageFlags> stages(info->waitSemaphoreCount,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
                if(!waited){submit.waitSemaphoreCount=info->waitSemaphoreCount;submit.pWaitSemaphores=info->pWaitSemaphores;submit.pWaitDstStageMask=stages.data();}
                result=d->QueueSubmit(queue,1,&submit,slot.exported);if(result==VK_SUCCESS)waited=true;
            }
            int fd=-1;
            if(result==VK_SUCCESS) {
                VkFenceGetFdInfoKHR exportInfo{VK_STRUCTURE_TYPE_FENCE_GET_FD_INFO_KHR};exportInfo.fence=slot.exported;exportInfo.handleType=VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT;
                if(d->GetFenceFdKHR(d->handle,&exportInfo,&fd)!=VK_SUCCESS)
                    result=d->WaitForFences(d->handle,1,&slot.exported,VK_TRUE,5'000'000'000ULL);
                // Export fd=-1 means the fence was already signalled, NOT failure.
            }
            if(result==VK_SUCCESS){std::lock_guard<std::mutex> writer(s->writer);if(!sendMessage(s->socket,m,fd))result=VK_ERROR_OUT_OF_DATE_KHR;}
            if(fd>=0)close(fd);
            if(result!=VK_SUCCESS){s->connected=false;shutdown(s->socket,SHUT_RDWR);s->changed.notify_all();}
        }
        if(info->pResults)info->pResults[n]=result;if(result!=VK_SUCCESS)overall=result;
    }
    if(!normal.empty()) {
        // Rare mixed direct/legacy present: never forward synthetic handles.
        // After consuming waits, remap per-swapchain extension arrays too.
        if(waited){VkResult r=d->QueueWaitIdle(queue);if(r!=VK_SUCCESS)return r;}
        std::vector<VkResult> results(normal.size());VkPresentInfoKHR legacy=*info;
        legacy.swapchainCount=uint32_t(normal.size());legacy.pSwapchains=normal.data();legacy.pImageIndices=normalIndices.data();legacy.pResults=results.data();
        if(waited){legacy.waitSemaphoreCount=0;legacy.pWaitSemaphores=nullptr;}
        legacy.pNext=nullptr;
        VkPresentIdKHR nativeIds{VK_STRUCTURE_TYPE_PRESENT_ID_KHR};std::vector<uint64_t> selectedIds;
        VkPresentRegionsKHR nativeRegions{VK_STRUCTURE_TYPE_PRESENT_REGIONS_KHR};std::vector<VkPresentRegionKHR> selectedRegions;
        VkDeviceGroupPresentInfoKHR nativeGroup{VK_STRUCTURE_TYPE_DEVICE_GROUP_PRESENT_INFO_KHR};std::vector<uint32_t> selectedMasks;
        if(ids){for(uint32_t n:normalPositions)selectedIds.push_back(ids->pPresentIds[n]);nativeIds.swapchainCount=uint32_t(normal.size());nativeIds.pPresentIds=selectedIds.data();nativeIds.pNext=legacy.pNext;legacy.pNext=&nativeIds;}
        if(regions){for(uint32_t n:normalPositions)selectedRegions.push_back(regions->pRegions[n]);nativeRegions.swapchainCount=uint32_t(normal.size());nativeRegions.pRegions=selectedRegions.data();nativeRegions.pNext=legacy.pNext;legacy.pNext=&nativeRegions;}
        if(group){for(uint32_t n:normalPositions)selectedMasks.push_back(group->pDeviceMasks[n]);nativeGroup.swapchainCount=uint32_t(normal.size());nativeGroup.pDeviceMasks=selectedMasks.data();nativeGroup.mode=group->mode;nativeGroup.pNext=legacy.pNext;legacy.pNext=&nativeGroup;}
        VkResult r=d->QueuePresentKHR(queue,&legacy);if(r!=VK_SUCCESS)overall=r;
        for(size_t n=0;n<normal.size();n++)if(info->pResults)info->pResults[normalPositions[n]]=results[n];
    }
    return overall;
}
uint32_t familyOf(VkQueue queue){
    std::lock_guard<std::mutex> l(registryMutex);auto d=devices.find(key(queue));
    if(d==devices.end())return UINT32_MAX;
    auto i=d->second->queueFamilies.find(queue);
    return i==d->second->queueFamilies.end()?UINT32_MAX:i->second;
}
VKAPI_ATTR void VKAPI_CALL getQueue(VkDevice handle,uint32_t family,uint32_t index,VkQueue* out) {
    auto d=dev(handle);if(!d)return;d->GetDeviceQueue(handle,family,index,out);
    if(*out){std::lock_guard<std::mutex> l(registryMutex);devices[key(*out)]=d;d->queueFamilies[*out]=family;}
}
VKAPI_ATTR void VKAPI_CALL getQueue2(VkDevice handle,const VkDeviceQueueInfo2* info,VkQueue* out) {
    auto d=dev(handle);if(!d||!d->GetDeviceQueue2)return;d->GetDeviceQueue2(handle,info,out);
    if(*out){std::lock_guard<std::mutex> l(registryMutex);devices[key(*out)]=d;d->queueFamilies[*out]=info->queueFamilyIndex;}
}
VKAPI_ATTR VkResult VKAPI_CALL submit(VkQueue queue,uint32_t count,const VkSubmitInfo* info,VkFence fence) {
    auto d=dev(queue);if(!d)return VK_ERROR_DEVICE_LOST;
    if(!d->enabled&&!d->activeSwapchains)return d->QueueSubmit(queue,count,info,fence);
    std::lock_guard<std::recursive_mutex> l(d->queueMutex);return d->QueueSubmit(queue,count,info,fence);
}
VKAPI_ATTR VkResult VKAPI_CALL submit2(VkQueue queue,uint32_t count,const VkSubmitInfo2* info,VkFence fence) {
    auto d=dev(queue);if(!d||!d->QueueSubmit2)return VK_ERROR_EXTENSION_NOT_PRESENT;std::lock_guard<std::recursive_mutex> l(d->queueMutex);return d->QueueSubmit2(queue,count,info,fence);
}
VKAPI_ATTR VkResult VKAPI_CALL bindSparse(VkQueue queue,uint32_t count,const VkBindSparseInfo* info,VkFence fence) {
    auto d=dev(queue);if(!d)return VK_ERROR_DEVICE_LOST;std::lock_guard<std::recursive_mutex> l(d->queueMutex);return d->QueueBindSparse(queue,count,info,fence);
}
VKAPI_ATTR VkResult VKAPI_CALL queueWait(VkQueue queue){auto d=dev(queue);if(!d)return VK_ERROR_DEVICE_LOST;std::lock_guard<std::recursive_mutex> l(d->queueMutex);return d->QueueWaitIdle(queue);}
VKAPI_ATTR VkResult VKAPI_CALL deviceWait(VkDevice handle){auto d=dev(handle);if(!d)return VK_ERROR_DEVICE_LOST;std::lock_guard<std::recursive_mutex> l(d->queueMutex);return d->DeviceWaitIdle(handle);}

VkImageLayout actualLayout(VkImageLayout layout){return layout==VK_IMAGE_LAYOUT_PRESENT_SRC_KHR?VK_IMAGE_LAYOUT_GENERAL:layout;}
bool isDirect(const std::shared_ptr<Device>& d,VkImage image){std::lock_guard<std::mutex> l(d->objectMutex);return d->directImages.count(image)!=0;}
// AHB images are not ICD-owned swapchain images. Translate the application's
// presentation layout only for our images; real X11 swapchains keep their WSI.
VKAPI_ATTR void VKAPI_CALL pipelineBarrier(VkCommandBuffer cb,VkPipelineStageFlags src,VkPipelineStageFlags dst,VkDependencyFlags flags,
    uint32_t memoryCount,const VkMemoryBarrier* memory,uint32_t bufferCount,const VkBufferMemoryBarrier* buffers,uint32_t imageCount,const VkImageMemoryBarrier* images) {
    auto d=dev(cb);if(!d)return;std::vector<VkImageMemoryBarrier> changed;
    for(uint32_t n=0;n<imageCount;n++)if((images[n].oldLayout==VK_IMAGE_LAYOUT_PRESENT_SRC_KHR||images[n].newLayout==VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)&&isDirect(d,images[n].image)) {
        if(changed.empty())changed.assign(images,images+imageCount);
        changed[n].oldLayout=actualLayout(changed[n].oldLayout);changed[n].newLayout=actualLayout(changed[n].newLayout);
    }
    d->CmdPipelineBarrier(cb,src,dst,flags,memoryCount,memory,bufferCount,buffers,imageCount,changed.empty()?images:changed.data());
}
VKAPI_ATTR void VKAPI_CALL pipelineBarrier2(VkCommandBuffer cb,const VkDependencyInfo* info) {
    auto d=dev(cb);if(!d||!d->CmdPipelineBarrier2)return;std::vector<VkImageMemoryBarrier2> changed;
    for(uint32_t n=0;n<info->imageMemoryBarrierCount;n++) {
        const auto& b=info->pImageMemoryBarriers[n];
        if((b.oldLayout==VK_IMAGE_LAYOUT_PRESENT_SRC_KHR||b.newLayout==VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)&&isDirect(d,b.image)) {
            if(changed.empty())changed.assign(info->pImageMemoryBarriers,info->pImageMemoryBarriers+info->imageMemoryBarrierCount);
            changed[n].oldLayout=actualLayout(b.oldLayout);changed[n].newLayout=actualLayout(b.newLayout);
        }
    }
    VkDependencyInfo copy=*info;if(!changed.empty())copy.pImageMemoryBarriers=changed.data();d->CmdPipelineBarrier2(cb,&copy);
}
VKAPI_ATTR void VKAPI_CALL waitEvents(VkCommandBuffer cb,uint32_t eventCount,const VkEvent* events,VkPipelineStageFlags src,VkPipelineStageFlags dst,
    uint32_t memoryCount,const VkMemoryBarrier* memory,uint32_t bufferCount,const VkBufferMemoryBarrier* buffers,uint32_t imageCount,const VkImageMemoryBarrier* images) {
    auto d=dev(cb);if(!d)return;std::vector<VkImageMemoryBarrier> changed;
    for(uint32_t n=0;n<imageCount;n++)if((images[n].oldLayout==VK_IMAGE_LAYOUT_PRESENT_SRC_KHR||images[n].newLayout==VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)&&isDirect(d,images[n].image)) {
        if(changed.empty())changed.assign(images,images+imageCount);
        changed[n].oldLayout=actualLayout(changed[n].oldLayout);changed[n].newLayout=actualLayout(changed[n].newLayout);
    }
    d->CmdWaitEvents(cb,eventCount,events,src,dst,memoryCount,memory,bufferCount,buffers,imageCount,changed.empty()?images:changed.data());
}
VKAPI_ATTR void VKAPI_CALL waitEvents2(VkCommandBuffer cb,uint32_t count,const VkEvent* events,const VkDependencyInfo* infos) {
    auto d=dev(cb);if(!d||!d->CmdWaitEvents2)return;
    std::vector<VkDependencyInfo> dependencies;
    if(count)dependencies.assign(infos,infos+count);
    std::vector<std::vector<VkImageMemoryBarrier2>> storage(count);
    for(uint32_t event=0;event<count;event++) {
        const auto& info=infos[event];auto& changed=storage[event];
        for(uint32_t n=0;n<info.imageMemoryBarrierCount;n++) {
            const auto& b=info.pImageMemoryBarriers[n];
            if((b.oldLayout==VK_IMAGE_LAYOUT_PRESENT_SRC_KHR||b.newLayout==VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)&&isDirect(d,b.image)) {
                if(changed.empty())changed.assign(info.pImageMemoryBarriers,info.pImageMemoryBarriers+info.imageMemoryBarrierCount);
                changed[n].oldLayout=actualLayout(b.oldLayout);changed[n].newLayout=actualLayout(b.newLayout);
            }
        }
        if(!changed.empty())dependencies[event].pImageMemoryBarriers=changed.data();
    }
    d->CmdWaitEvents2(cb,count,events,dependencies.data());
}
VKAPI_ATTR VkResult VKAPI_CALL createView(VkDevice handle,const VkImageViewCreateInfo* info,const VkAllocationCallbacks* a,VkImageView* out) {
    auto d=dev(handle);if(!d)return VK_ERROR_DEVICE_LOST;VkResult r=d->CreateImageView(handle,info,a,out);
    if(r==VK_SUCCESS){std::lock_guard<std::mutex> l(d->objectMutex);d->directViews[*out]=d->directImages.count(info->image)!=0;}return r;
}
VKAPI_ATTR void VKAPI_CALL destroyView(VkDevice handle,VkImageView view,const VkAllocationCallbacks* a) {
    auto d=dev(handle);if(!d)return;{std::lock_guard<std::mutex> l(d->objectMutex);d->directViews.erase(view);}d->DestroyImageView(handle,view,a);
}
VKAPI_ATTR VkResult VKAPI_CALL createFramebuffer(VkDevice handle,const VkFramebufferCreateInfo* info,const VkAllocationCallbacks* a,VkFramebuffer* out) {
    auto d=dev(handle);if(!d)return VK_ERROR_DEVICE_LOST;VkResult r=d->CreateFramebuffer(handle,info,a,out);
    if(r==VK_SUCCESS&&!(info->flags&VK_FRAMEBUFFER_CREATE_IMAGELESS_BIT)) {
        std::lock_guard<std::mutex> l(d->objectMutex);
        for(uint32_t n=0;n<info->attachmentCount;n++){auto it=d->directViews.find(info->pAttachments[n]);if(it!=d->directViews.end()&&it->second){d->directFramebuffers.insert(*out);break;}}
    }
    return r;
}
VKAPI_ATTR void VKAPI_CALL destroyFramebuffer(VkDevice handle,VkFramebuffer framebuffer,const VkAllocationCallbacks* a) {
    auto d=dev(handle);if(!d)return;{std::lock_guard<std::mutex> l(d->objectMutex);d->directFramebuffers.erase(framebuffer);}d->DestroyFramebuffer(handle,framebuffer,a);
}
template<class Description> bool translateAttachments(std::vector<Description>& attachments) {
    bool changed=false;
    for(auto& a:attachments) {
        changed|=a.initialLayout==VK_IMAGE_LAYOUT_PRESENT_SRC_KHR||a.finalLayout==VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        a.initialLayout=actualLayout(a.initialLayout);a.finalLayout=actualLayout(a.finalLayout);
    }
    return changed;
}
VKAPI_ATTR VkResult VKAPI_CALL createRenderPass(VkDevice handle,const VkRenderPassCreateInfo* info,const VkAllocationCallbacks* a,VkRenderPass* out) {
    auto d=dev(handle);if(!d)return VK_ERROR_DEVICE_LOST;VkResult r=d->CreateRenderPass(handle,info,a,out);if(r!=VK_SUCCESS)return r;
    if(!d->enabled&&!d->activeSwapchains)return r;
    std::vector<VkAttachmentDescription> attachments;
    if(info->attachmentCount)attachments.assign(info->pAttachments,info->pAttachments+info->attachmentCount);
    if(translateAttachments(attachments)) {
        VkRenderPassCreateInfo adapted=*info;adapted.pAttachments=attachments.data();VkRenderPass replacement{};
        r=d->CreateRenderPass(handle,&adapted,a,&replacement);
        if(r!=VK_SUCCESS){d->DestroyRenderPass(handle,*out,a);*out={};return r;}
        std::lock_guard<std::mutex> l(d->objectMutex);d->renderPasses[*out]=replacement;
    }
    return r;
}
VKAPI_ATTR VkResult VKAPI_CALL createRenderPass2(VkDevice handle,const VkRenderPassCreateInfo2* info,const VkAllocationCallbacks* a,VkRenderPass* out) {
    auto d=dev(handle);if(!d||!d->CreateRenderPass2)return VK_ERROR_EXTENSION_NOT_PRESENT;
    VkResult r=d->CreateRenderPass2(handle,info,a,out);if(r!=VK_SUCCESS)return r;
    if(!d->enabled&&!d->activeSwapchains)return r;
    std::vector<VkAttachmentDescription2> attachments;
    if(info->attachmentCount)attachments.assign(info->pAttachments,info->pAttachments+info->attachmentCount);
    if(translateAttachments(attachments)) {
        VkRenderPassCreateInfo2 adapted=*info;adapted.pAttachments=attachments.data();VkRenderPass replacement{};
        r=d->CreateRenderPass2(handle,&adapted,a,&replacement);
        if(r!=VK_SUCCESS){d->DestroyRenderPass(handle,*out,a);*out={};return r;}
        std::lock_guard<std::mutex> l(d->objectMutex);d->renderPasses[*out]=replacement;
    }
    return r;
}
VKAPI_ATTR void VKAPI_CALL destroyRenderPass(VkDevice handle,VkRenderPass pass,const VkAllocationCallbacks* a) {
    auto d=dev(handle);if(!d)return;VkRenderPass replacement{};
    {std::lock_guard<std::mutex> l(d->objectMutex);auto it=d->renderPasses.find(pass);if(it!=d->renderPasses.end()){replacement=it->second;d->renderPasses.erase(it);}}
    if(replacement)d->DestroyRenderPass(handle,replacement,a);d->DestroyRenderPass(handle,pass,a);
}
VkRenderPass renderPassFor(const std::shared_ptr<Device>& d,const VkRenderPassBeginInfo* info) {
    std::lock_guard<std::mutex> l(d->objectMutex);bool direct=d->directFramebuffers.count(info->framebuffer)!=0;
    // Imageless framebuffers supply their views on each begin, not at creation.
    for(auto* p=reinterpret_cast<const VkBaseInStructure*>(info->pNext);p;p=p->pNext)
        if(p->sType==VK_STRUCTURE_TYPE_RENDER_PASS_ATTACHMENT_BEGIN_INFO) {
            auto* attachments=reinterpret_cast<const VkRenderPassAttachmentBeginInfo*>(p);
            for(uint32_t n=0;n<attachments->attachmentCount;n++) {
                auto it=d->directViews.find(attachments->pAttachments[n]);if(it!=d->directViews.end()&&it->second)direct=true;
            }
        }
    auto it=d->renderPasses.find(info->renderPass);return direct&&it!=d->renderPasses.end()?it->second:info->renderPass;
}
VKAPI_ATTR void VKAPI_CALL beginRenderPass(VkCommandBuffer cb,const VkRenderPassBeginInfo* info,VkSubpassContents contents) {
    auto d=dev(cb);if(!d)return;auto changed=*info;changed.renderPass=renderPassFor(d,info);d->CmdBeginRenderPass(cb,&changed,contents);
}
VKAPI_ATTR void VKAPI_CALL beginRenderPass2(VkCommandBuffer cb,const VkRenderPassBeginInfo* info,const VkSubpassBeginInfo* begin) {
    auto d=dev(cb);if(!d||!d->CmdBeginRenderPass2)return;auto changed=*info;changed.renderPass=renderPassFor(d,info);d->CmdBeginRenderPass2(cb,&changed,begin);
}
VKAPI_ATTR VkResult VKAPI_CALL swapchainStatus(VkDevice handle,VkSwapchainKHR h) {
    auto s=chain(h);if(s)return s->connected?VK_SUCCESS:VK_ERROR_OUT_OF_DATE_KHR;
    auto d=dev(handle);return d&&d->GetSwapchainStatusKHR?d->GetSwapchainStatusKHR(handle,h):VK_ERROR_EXTENSION_NOT_PRESENT;
}
VKAPI_ATTR void VKAPI_CALL hdrMetadata(VkDevice handle,uint32_t count,const VkSwapchainKHR* chains,const VkHdrMetadataEXT* metadata) {
    auto d=dev(handle);if(!d||!d->SetHdrMetadataEXT)return;
    // Direct AHB is restricted to SDR UNORM; never hand synthetic handles to an ICD.
    for(uint32_t n=0;n<count;n++)if(!chain(chains[n]))d->SetHdrMetadataEXT(handle,1,chains+n,metadata+n);
}
}
EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance,const char*);
EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice handle,const char* name) {
#define RETURN(n,f) if(!strcmp(name,"vk" #n))return reinterpret_cast<PFN_vkVoidFunction>(f)
    RETURN(GetDeviceProcAddr,vkGetDeviceProcAddr);RETURN(CreateSwapchainKHR,createSwapchain);RETURN(DestroySwapchainKHR,destroySwapchain);
    RETURN(GetSwapchainImagesKHR,getImages);RETURN(AcquireNextImageKHR,acquire);RETURN(AcquireNextImage2KHR,acquire2);
    RETURN(QueuePresentKHR,present);RETURN(DestroyDevice,destroyDevice);
    RETURN(GetDeviceQueue,getQueue);RETURN(GetDeviceQueue2,getQueue2);RETURN(QueueSubmit,submit);
    RETURN(QueueSubmit2,submit2);RETURN(QueueSubmit2KHR,submit2);RETURN(QueueBindSparse,bindSparse);
    RETURN(QueueWaitIdle,queueWait);RETURN(DeviceWaitIdle,deviceWait);
    RETURN(CmdPipelineBarrier,pipelineBarrier);RETURN(CmdPipelineBarrier2,pipelineBarrier2);RETURN(CmdPipelineBarrier2KHR,pipelineBarrier2);
    RETURN(CmdWaitEvents,waitEvents);RETURN(CmdWaitEvents2,waitEvents2);RETURN(CmdWaitEvents2KHR,waitEvents2);
    RETURN(CreateImageView,createView);RETURN(DestroyImageView,destroyView);RETURN(CreateFramebuffer,createFramebuffer);RETURN(DestroyFramebuffer,destroyFramebuffer);
    RETURN(CreateRenderPass,createRenderPass);RETURN(CreateRenderPass2,createRenderPass2);RETURN(CreateRenderPass2KHR,createRenderPass2);RETURN(DestroyRenderPass,destroyRenderPass);
    RETURN(CmdBeginRenderPass,beginRenderPass);RETURN(CmdBeginRenderPass2,beginRenderPass2);RETURN(CmdBeginRenderPass2KHR,beginRenderPass2);
    RETURN(GetSwapchainStatusKHR,swapchainStatus);RETURN(SetHdrMetadataEXT,hdrMetadata);
    auto d=dev(handle);return d?d->get(handle,name):nullptr;
}
EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance handle,const char* name) {
    RETURN(GetInstanceProcAddr,vkGetInstanceProcAddr);RETURN(GetDeviceProcAddr,vkGetDeviceProcAddr);
    RETURN(CreateInstance,createInstance);RETURN(DestroyInstance,destroyInstance);RETURN(EnumeratePhysicalDevices,enumeratePhysical);
    RETURN(EnumerateDeviceExtensionProperties,enumerateDeviceExtensions);
    RETURN(CreateDevice,createDevice);RETURN(CreateXcbSurfaceKHR,xcbSurface);RETURN(CreateXlibSurfaceKHR,xlibSurface);RETURN(DestroySurfaceKHR,destroySurface);
    // Device functions may be queried through GIPA as required by Vulkan layers.
    auto own=vkGetDeviceProcAddr(VK_NULL_HANDLE,name);if(own)return own;
    auto i=inst(handle);return i?i->get(handle,name):nullptr;
#undef RETURN
}
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL physicalDeviceProc(VkInstance handle,const char* name) {
    return vkGetInstanceProcAddr(handle,name);
}
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface* info) {
    if(!info||info->sType!=LAYER_NEGOTIATE_INTERFACE_STRUCT)return VK_ERROR_INITIALIZATION_FAILED;
    info->loaderLayerInterfaceVersion=std::min(info->loaderLayerInterfaceVersion,2u);
    info->pfnGetInstanceProcAddr=vkGetInstanceProcAddr;info->pfnGetDeviceProcAddr=vkGetDeviceProcAddr;
    info->pfnGetPhysicalDeviceProcAddr=physicalDeviceProc;return VK_SUCCESS;
}
