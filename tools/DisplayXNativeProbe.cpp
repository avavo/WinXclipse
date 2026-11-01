// On-device test of the real guest layer, AHB socket and compositor imports.
// Only X11 surface creation is replaced; Vulkan, buffers, fences and IPC are real.
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>
#include <jni.h>
#include <dlfcn.h>
#include <atomic>
#include <thread>
#include <string>
#include <stdexcept>
#include <chrono>
#include <vector>
#include <cstring>
#include "../app/src/main/cpp/winlator/renderer/displayx/Server.hpp"

namespace {
void check(VkResult result, const char* where) {
    if (result != VK_SUCCESS) throw std::runtime_error(std::string(where)+"="+std::to_string(result));
}
VKAPI_ATTR VkResult VKAPI_CALL fakeSurface(VkInstance, const void*, const VkAllocationCallbacks*, VkSurfaceKHR* out) {
    *out = reinterpret_cast<VkSurfaceKHR>(uintptr_t(0x1234)); return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL freeSurface(VkInstance, VkSurfaceKHR, const VkAllocationCallbacks*) {}
VKAPI_ATTR VkResult VKAPI_CALL fallbackSwapchain(VkDevice, const VkSwapchainCreateInfoKHR*, const VkAllocationCallbacks*, VkSwapchainKHR*) {
    return VK_ERROR_FEATURE_NOT_PRESENT;
}
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL nextDevice(VkDevice device, const char* name) {
    if (!strcmp(name,"vkCreateSwapchainKHR")) return reinterpret_cast<PFN_vkVoidFunction>(fallbackSwapchain);
    return vkGetDeviceProcAddr(device,name);
}
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL nextInstance(VkInstance instance, const char* name) {
    if (!strcmp(name,"vkCreateXcbSurfaceKHR")) return reinterpret_cast<PFN_vkVoidFunction>(fakeSurface);
    if (!strcmp(name,"vkDestroySurfaceKHR")) return reinterpret_cast<PFN_vkVoidFunction>(freeSurface);
    return vkGetInstanceProcAddr(instance,name);
}
VKAPI_ATTR VkResult VKAPI_CALL loaderData(VkDevice, void*) { return VK_SUCCESS; }
struct Probe {
    std::shared_ptr<displayx::Server> server;
    std::thread producer;
    std::atomic<int> received{0};
    std::atomic<bool> stop{false}, done{false};
    std::string error;
    void run(const std::string& layer, const std::string& socket) {
        try {
            setenv("WINXCLIPSE_DISPLAYX_SOCKET",socket.c_str(),1);
            setenv("WINXCLIPSE_DISPLAYX_FORMAT","rgba",1);
            void* library = dlopen(layer.c_str(),RTLD_NOW|RTLD_LOCAL);
            if (!library) throw std::runtime_error(dlerror());
            auto negotiate = reinterpret_cast<PFN_vkNegotiateLoaderLayerInterfaceVersion>(dlsym(library,"vkNegotiateLoaderLayerInterfaceVersion"));
            VkNegotiateLayerInterface interface{};interface.sType=LAYER_NEGOTIATE_INTERFACE_STRUCT;interface.loaderLayerInterfaceVersion=2;
            check(negotiate(&interface),"layer negotiation");
            auto get = interface.pfnGetInstanceProcAddr;auto getDevice = interface.pfnGetDeviceProcAddr;
            VkLayerInstanceLink next{};next.pfnNextGetInstanceProcAddr=nextInstance;
            VkLayerInstanceCreateInfo link{};link.sType=VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO;
            link.function=VK_LAYER_LINK_INFO;link.u.pLayerInfo=&next;
            VkApplicationInfo application{VK_STRUCTURE_TYPE_APPLICATION_INFO};application.apiVersion=VK_API_VERSION_1_1;
            VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};info.pNext=&link;info.pApplicationInfo=&application;
            VkInstance instance{};
            check(reinterpret_cast<PFN_vkCreateInstance>(get(nullptr,"vkCreateInstance"))(&info,nullptr,&instance),"instance");
            uint32_t count=1;VkPhysicalDevice gpu{};
            check(reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(get(instance,"vkEnumeratePhysicalDevices"))(instance,&count,&gpu),"physical device");
            uint32_t extensionCount=0;
            auto enumerate=reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(get(instance,"vkEnumerateDeviceExtensionProperties"));
            check(enumerate(gpu,nullptr,&extensionCount,nullptr),"extension count");
            std::vector<VkExtensionProperties> extensions(extensionCount);
            check(enumerate(gpu,nullptr,&extensionCount,extensions.data()),"extensions");
            for (const auto& e:extensions) if (!strcmp(e.extensionName,"VK_GOOGLE_display_timing") || !strcmp(e.extensionName,"VK_EXT_swapchain_maintenance1"))
                throw std::runtime_error("incompatible WSI extension still advertised");
            vkGetPhysicalDeviceQueueFamilyProperties(gpu,&count,nullptr);
            std::vector<VkQueueFamilyProperties> families(count);vkGetPhysicalDeviceQueueFamilyProperties(gpu,&count,families.data());
            uint32_t family=0;while (family<count && !(families[family].queueFlags&VK_QUEUE_GRAPHICS_BIT)) family++;
            if (family==count) throw std::runtime_error("no graphics queue");
            float priority=1;VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
            queueInfo.queueFamilyIndex=family;queueInfo.queueCount=1;queueInfo.pQueuePriorities=&priority;
            VkLayerDeviceCreateInfo callback{};callback.sType=VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO;
            callback.function=VK_LOADER_DATA_CALLBACK;callback.u.pfnSetDeviceLoaderData=loaderData;
            VkLayerDeviceLink nextDeviceLink{};nextDeviceLink.pfnNextGetInstanceProcAddr=nextInstance;nextDeviceLink.pfnNextGetDeviceProcAddr=nextDevice;
            VkLayerDeviceCreateInfo deviceLink{};deviceLink.sType=VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO;
            deviceLink.function=VK_LAYER_LINK_INFO;deviceLink.u.pLayerInfo=&nextDeviceLink;deviceLink.pNext=&callback;
            const char* swapchainExtension=VK_KHR_SWAPCHAIN_EXTENSION_NAME;
            VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};deviceInfo.pNext=&deviceLink;
            deviceInfo.queueCreateInfoCount=1;deviceInfo.pQueueCreateInfos=&queueInfo;
            deviceInfo.enabledExtensionCount=1;deviceInfo.ppEnabledExtensionNames=&swapchainExtension;
            VkDevice device{};
            check(reinterpret_cast<PFN_vkCreateDevice>(get(instance,"vkCreateDevice"))(gpu,&deviceInfo,nullptr,&device),"device");
            VkQueue queue{};
            reinterpret_cast<PFN_vkGetDeviceQueue>(getDevice(device,"vkGetDeviceQueue"))(device,family,0,&queue);
            struct XcbInfo {VkStructureType type;const void* next;VkFlags flags;void* connection;uint32_t window;};
            XcbInfo xcb{VK_STRUCTURE_TYPE_XCB_SURFACE_CREATE_INFO_KHR,nullptr,0,nullptr,85};VkSurfaceKHR surface{};
            using CreateSurface=VkResult(VKAPI_PTR*)(VkInstance,const XcbInfo*,const VkAllocationCallbacks*,VkSurfaceKHR*);
            check(reinterpret_cast<CreateSurface>(get(instance,"vkCreateXcbSurfaceKHR"))(instance,&xcb,nullptr,&surface),"X11 surface registration");
            VkSwapchainCreateInfoKHR swapInfo{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
            swapInfo.surface=surface;swapInfo.minImageCount=3;swapInfo.imageFormat=VK_FORMAT_R8G8B8A8_UNORM;
            swapInfo.imageColorSpace=VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;swapInfo.imageExtent={64,64};swapInfo.imageArrayLayers=1;
            swapInfo.imageUsage=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            swapInfo.preTransform=VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;swapInfo.compositeAlpha=VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
            swapInfo.presentMode=VK_PRESENT_MODE_FIFO_KHR;swapInfo.clipped=true;
            VkSwapchainKHR swapchain{};
            check(reinterpret_cast<PFN_vkCreateSwapchainKHR>(getDevice(device,"vkCreateSwapchainKHR"))(device,&swapInfo,nullptr,&swapchain),"direct AHB swapchain");
            count=0;auto getImages=reinterpret_cast<PFN_vkGetSwapchainImagesKHR>(getDevice(device,"vkGetSwapchainImagesKHR"));
            check(getImages(device,swapchain,&count,nullptr),"image count");std::vector<VkImage> images(count);
            check(getImages(device,swapchain,&count,images.data()),"images");
            // Exercise the render-target path used by DXVK as well as transfer
            // clears. A transfer-only test misses attachment/DCC/layout bugs.
            VkAttachmentDescription attachment{};attachment.format=swapInfo.imageFormat;
            attachment.samples=VK_SAMPLE_COUNT_1_BIT;attachment.loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR;
            attachment.storeOp=VK_ATTACHMENT_STORE_OP_STORE;attachment.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
            attachment.finalLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
            VkAttachmentReference reference{0,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
            VkSubpassDescription subpass{};subpass.pipelineBindPoint=VK_PIPELINE_BIND_POINT_GRAPHICS;
            subpass.colorAttachmentCount=1;subpass.pColorAttachments=&reference;
            VkRenderPassCreateInfo passInfo{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};passInfo.attachmentCount=1;
            passInfo.pAttachments=&attachment;passInfo.subpassCount=1;passInfo.pSubpasses=&subpass;
            VkRenderPass pass{};
            check(reinterpret_cast<PFN_vkCreateRenderPass>(getDevice(device,"vkCreateRenderPass"))(device,&passInfo,nullptr,&pass),"attachment pass");
            std::vector<VkImageView> views(count);std::vector<VkFramebuffer> frames(count);
            for(uint32_t n=0;n<count;n++) {
                VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};view.image=images[n];
                view.viewType=VK_IMAGE_VIEW_TYPE_2D;view.format=swapInfo.imageFormat;
                view.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
                check(reinterpret_cast<PFN_vkCreateImageView>(getDevice(device,"vkCreateImageView"))(device,&view,nullptr,&views[n]),"attachment view");
                VkFramebufferCreateInfo frame{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};frame.renderPass=pass;
                frame.attachmentCount=1;frame.pAttachments=&views[n];frame.width=frame.height=64;frame.layers=1;
                check(reinterpret_cast<PFN_vkCreateFramebuffer>(getDevice(device,"vkCreateFramebuffer"))(device,&frame,nullptr,&frames[n]),"attachment framebuffer");
            }
            VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};poolInfo.queueFamilyIndex=family;poolInfo.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
            VkCommandPool pool{};check(vkCreateCommandPool(device,&poolInfo,nullptr,&pool),"command pool");
            VkCommandBufferAllocateInfo allocate{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};allocate.commandPool=pool;allocate.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;allocate.commandBufferCount=1;
            VkCommandBuffer command{};check(vkAllocateCommandBuffers(device,&allocate,&command),"command buffer");
            VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};VkFence fence{};check(vkCreateFence(device,&fenceInfo,nullptr,&fence),"fence");
            for (int frame=0;frame<3;frame++) {
                uint32_t index=0;
                check(reinterpret_cast<PFN_vkAcquireNextImageKHR>(getDevice(device,"vkAcquireNextImageKHR"))(device,swapchain,1'000'000'000,{},fence,&index),"acquire");
                check(vkWaitForFences(device,1,&fence,true,1'000'000'000),"acquire fence");vkResetFences(device,1,&fence);
                vkResetCommandBuffer(command,0);VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};check(vkBeginCommandBuffer(command,&begin),"begin clear");
                VkClearColorValue color{{float(frame==0),float(frame==1),float(frame==2),1}};
                if(frame==0) {
                    VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};vkCmdClearColorImage(command,images[index],VK_IMAGE_LAYOUT_GENERAL,&color,1,&range);
                } else {
                    VkClearValue clear{};clear.color=color;
                    VkRenderPassBeginInfo render{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};render.renderPass=pass;
                    render.framebuffer=frames[index];render.renderArea={{0,0},{64,64}};render.clearValueCount=1;render.pClearValues=&clear;
                    reinterpret_cast<PFN_vkCmdBeginRenderPass>(getDevice(device,"vkCmdBeginRenderPass"))(command,&render,VK_SUBPASS_CONTENTS_INLINE);
                    vkCmdEndRenderPass(command);
                }
                check(vkEndCommandBuffer(command),"end clear");VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};submit.commandBufferCount=1;submit.pCommandBuffers=&command;
                check(reinterpret_cast<PFN_vkQueueSubmit>(getDevice(device,"vkQueueSubmit"))(queue,1,&submit,{}),"submit clear");
                VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};present.swapchainCount=1;present.pSwapchains=&swapchain;present.pImageIndices=&index;
                check(reinterpret_cast<PFN_vkQueuePresentKHR>(getDevice(device,"vkQueuePresentKHR"))(queue,&present),"present IPC");
            }
            for(int n=0;n<500&&!stop;n++) std::this_thread::sleep_for(std::chrono::milliseconds(20));
            vkDeviceWaitIdle(device);
            for(uint32_t n=0;n<count;n++) {
                reinterpret_cast<PFN_vkDestroyFramebuffer>(getDevice(device,"vkDestroyFramebuffer"))(device,frames[n],nullptr);
                reinterpret_cast<PFN_vkDestroyImageView>(getDevice(device,"vkDestroyImageView"))(device,views[n],nullptr);
            }
            reinterpret_cast<PFN_vkDestroyRenderPass>(getDevice(device,"vkDestroyRenderPass"))(device,pass,nullptr);
            reinterpret_cast<PFN_vkDestroySwapchainKHR>(getDevice(device,"vkDestroySwapchainKHR"))(device,swapchain,nullptr);
            vkDestroyFence(device,fence,nullptr);vkDestroyCommandPool(device,pool,nullptr);
            reinterpret_cast<PFN_vkDestroyDevice>(getDevice(device,"vkDestroyDevice"))(device,nullptr);
            reinterpret_cast<PFN_vkDestroyInstance>(get(instance,"vkDestroyInstance"))(instance,nullptr);
            dlclose(library);
        } catch(const std::exception& failure) {error=failure.what();}
        done=true;
    }
};
}
extern "C" JNIEXPORT jlong JNICALL Java_com_winlator_cmod_renderer_DisplayXNativeProbe_start(JNIEnv* env,jclass,jstring layer,jstring path) {
    const char* l=env->GetStringUTFChars(layer,nullptr);const char* p=env->GetStringUTFChars(path,nullptr);
    std::string library=l,socket=p;env->ReleaseStringUTFChars(layer,l);env->ReleaseStringUTFChars(path,p);
    auto* test=new Probe;
    try {test->server=std::make_shared<displayx::Server>(socket.c_str(),[test](uint32_t){test->received++;return true;});
        test->producer=std::thread([test,library,socket]{test->run(library,socket);});}
    catch(const std::exception& error){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),error.what());delete test;return 0;}
    return reinterpret_cast<jlong>(test);
}
extern "C" JNIEXPORT jlong JNICALL Java_com_winlator_cmod_renderer_DisplayXNativeProbe_server(JNIEnv*,jclass,jlong handle) {return reinterpret_cast<jlong>(&reinterpret_cast<Probe*>(handle)->server);}
extern "C" JNIEXPORT jboolean JNICALL Java_com_winlator_cmod_renderer_DisplayXNativeProbe_pending(JNIEnv*,jclass,jlong handle) {return reinterpret_cast<Probe*>(handle)->server->hasPending(85);}
extern "C" JNIEXPORT jstring JNICALL Java_com_winlator_cmod_renderer_DisplayXNativeProbe_error(JNIEnv* env,jclass,jlong handle) {
    auto* test=reinterpret_cast<Probe*>(handle);return env->NewStringUTF(test->done?test->error.c_str():"");
}
extern "C" JNIEXPORT void JNICALL Java_com_winlator_cmod_renderer_DisplayXNativeProbe_close(JNIEnv*,jclass,jlong handle) {
    auto* test=reinterpret_cast<Probe*>(handle);test->stop=true;if(test->producer.joinable())test->producer.join();delete test;
}
