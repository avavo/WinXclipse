// WinXclipse display compositor. All entry points run on one Java-owned render
// thread. The previous submission's fence is waited BEFORE mapped pixels,
// descriptors or temporal images can be reused. No per-frame DeviceWaitIdle.
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <android/native_window_jni.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include "../displayx/Server.hpp"
#include "../displayx/Scanout.hpp"
#include "../displayx/ScanoutPolicy.hpp"
#include <jni.h>
#include <array>
#include <vector>
#include <unordered_map>
#include <memory>
#include <stdexcept>
#include <cstring>
#include <algorithm>
#include <limits>

namespace {
const uint32_t windowVert[] =
#include "window_vert.inc"
;
const uint32_t windowFrag[] =
#include "window_frag.inc"
;
const uint32_t postVert[] =
#include "post_vert.inc"
;
const uint32_t presentVert[] =
#include "present_vert.inc"
;
#define SHADER(name) const uint32_t name[] =
SHADER(copyFrag)
#include "copy_frag.inc"
;
SHADER(colorFrag)
#include "color_frag.inc"
;
SHADER(crtFrag)
#include "crt_frag.inc"
;
SHADER(easuFrag)
#include "fsr_easu_frag.inc"
;
SHADER(rcasFrag)
#include "fsr_rcas_frag.inc"
;
SHADER(fxaaFrag)
#include "fxaa_frag.inc"
;
SHADER(hdrFrag)
#include "hdr_frag.inc"
;
SHADER(ntscFrag)
#include "ntsc_frag.inc"
;
SHADER(toonFrag)
#include "toon_frag.inc"
;
SHADER(framegenFrag)
#include "framegen_frag.inc"
;
SHADER(motionComp)
#include "motion_comp.inc"
;
#undef SHADER
void check(VkResult result, const char* action) {
    if(result != VK_SUCCESS) throw std::runtime_error(std::string(action)+" (VkResult="+std::to_string(result)+")");
}
struct Image {
    VkImage image{}; VkDeviceMemory memory{}; VkImageView view{};
    VkFramebuffer framebuffer{}; VkBuffer staging{}; VkDeviceMemory stagingMemory{};
    void* mapped{};
    VkFormat format{}; uint32_t width{},height{}; VkImageLayout layout=VK_IMAGE_LAYOUT_UNDEFINED;
    bool pendingUpload=false,seen=false;
    AHardwareBuffer* hardwareBuffer=nullptr;
    bool linearSupported=true;
};
struct Draw { Image* image; std::array<float,4> rect; bool swap,alpha; std::shared_ptr<displayx::Frame> frame; };
class Display {
public:
    VkInstance instance{}; VkPhysicalDevice gpu{}; VkDevice device{};
    VkSurfaceKHR surface{}; VkQueue queue{}; uint32_t queueFamily{};
    ANativeWindow* window{}; VkSwapchainKHR swapchain{}; VkExtent2D extent{},logicalExtent{};
    VkSurfaceTransformFlagBitsKHR preTransform{}; uint32_t quarterTurns{},mirror{},transformPoll{};
    VkFormat swapFormat{}; VkCommandPool commandPool{}; VkCommandBuffer cb{};
    VkFence fence{}; VkSemaphore acquired{}; std::vector<VkSemaphore> presented;
    std::vector<VkImageView> swapViews; std::vector<VkFramebuffer> swapFrames;
    VkRenderPass offPass{},screenPass{}; VkDescriptorPool descriptors{};
    VkDescriptorSetLayout sampledLayout{},flowLayout{},motionLayout{};
    VkPipelineLayout sampledPipeLayout{},flowPipeLayout{},motionPipeLayout{};
    VkSampler linearSampler{},nearestSampler{};
    VkPipeline windowPipe{},screenWindowPipe{},screenCopyPipe{},screenFlowPipe{},screenCursorPipe{},motionPipe{};
    std::array<VkPipeline,9> effects{}; // copy, color, CRT, EASU, RCAS, FXAA, HDR, NTSC, toon
    Image scene, ping[2], history[2], flow[2];
    std::unordered_map<jlong,Image> textures;
    std::unordered_map<uint64_t,Image> externalTextures;
    std::unordered_map<uint32_t,std::shared_ptr<displayx::Frame>> directFrames;
    std::vector<std::shared_ptr<displayx::Frame>> submittedFrames;
    std::shared_ptr<displayx::Server> directServer;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID getHardwareProperties{};
    bool ahbSupported=false;
    VkFormat preferredFormat=VK_FORMAT_B8G8R8A8_UNORM;
    bool presentAtRefresh=true,precisePresentation=false,displayTiming=false;
    uint32_t timingPresentId=0;
    uint64_t desiredPresentationNanos=0;
    std::unique_ptr<displayx::Scanout> scanout;
    bool scanoutAllowed=true,waitingOnScanout=false,scanoutActive=false;
    int reportedTransport=-1;
    std::vector<Draw> windows,cursors;
    std::array<float,4> contentRect{0,0,1,1};
    uint32_t imageIndex{},historyCount{},currentHistory{},currentFlow{},sceneWidth{},sceneHeight{};
    bool resizePending=true,frameOpen=false,nearest=false;
    ~Display() { destroy(); }

    uint32_t memoryType(uint32_t bits,VkMemoryPropertyFlags flags) {
        VkPhysicalDeviceMemoryProperties p; vkGetPhysicalDeviceMemoryProperties(gpu,&p);
        for(uint32_t i=0;i<p.memoryTypeCount;i++)
            if((bits&(1u<<i)) && (p.memoryTypes[i].propertyFlags&flags)==flags) return i;
        throw std::runtime_error("Vulkan memory type unavailable");
    }
    void freeImage(Image& i) {
        if(i.framebuffer) vkDestroyFramebuffer(device,i.framebuffer,nullptr);
        if(i.view) vkDestroyImageView(device,i.view,nullptr);
        if(i.image) vkDestroyImage(device,i.image,nullptr);
        if(i.memory) vkFreeMemory(device,i.memory,nullptr);
        if(i.mapped) vkUnmapMemory(device,i.stagingMemory);
        if(i.staging) vkDestroyBuffer(device,i.staging,nullptr);
        if(i.stagingMemory) vkFreeMemory(device,i.stagingMemory,nullptr);
        if(i.hardwareBuffer)AHardwareBuffer_release(i.hardwareBuffer);
        i={};
    }
    void makeView(Image& i,VkFormat format) {
        VkImageViewCreateInfo v{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        v.image=i.image;v.viewType=VK_IMAGE_VIEW_TYPE_2D;v.format=format;
        v.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        check(vkCreateImageView(device,&v,nullptr,&i.view),"image view");
    }
    void makeImage(Image& i,uint32_t w,uint32_t h,bool target,bool storage=false,VkFormat format=VK_FORMAT_R8G8B8A8_UNORM) {
        i.width=w;i.height=h;i.format=format;
        VkImageCreateInfo c{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        c.imageType=VK_IMAGE_TYPE_2D;c.format=format;
        c.extent={w,h,1};c.mipLevels=c.arrayLayers=1;c.samples=VK_SAMPLE_COUNT_1_BIT;
        c.tiling=VK_IMAGE_TILING_OPTIMAL;c.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
        c.usage=VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        if(target)c.usage|=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        if(storage)c.usage|=VK_IMAGE_USAGE_STORAGE_BIT;
        check(vkCreateImage(device,&c,nullptr,&i.image),"image");
        VkMemoryRequirements r;vkGetImageMemoryRequirements(device,i.image,&r);
        VkMemoryAllocateInfo a{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        a.allocationSize=r.size;a.memoryTypeIndex=memoryType(r.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        check(vkAllocateMemory(device,&a,nullptr,&i.memory),"image memory");
        check(vkBindImageMemory(device,i.image,i.memory,0),"bind image");
        makeView(i,c.format);
        if(target) {
            VkFramebufferCreateInfo f{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
            f.renderPass=offPass;f.attachmentCount=1;f.pAttachments=&i.view;
            f.width=w;f.height=h;f.layers=1;
            check(vkCreateFramebuffer(device,&f,nullptr,&i.framebuffer),"offscreen framebuffer");
        }
    }
    void staging(Image& i) {
        VkBufferCreateInfo b{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        b.size=VkDeviceSize(i.width)*i.height*4;b.usage=VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        check(vkCreateBuffer(device,&b,nullptr,&i.staging),"staging buffer");
        VkMemoryRequirements r;vkGetBufferMemoryRequirements(device,i.staging,&r);
        VkMemoryAllocateInfo a{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};a.allocationSize=r.size;
        a.memoryTypeIndex=memoryType(r.memoryTypeBits,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        check(vkAllocateMemory(device,&a,nullptr,&i.stagingMemory),"staging memory");
        check(vkBindBufferMemory(device,i.staging,i.stagingMemory,0),"bind staging");
        check(vkMapMemory(device,i.stagingMemory,0,b.size,0,&i.mapped),"map staging");
    }
    VkShaderModule shader(const uint32_t* data,size_t size) {
        VkShaderModuleCreateInfo c{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};c.codeSize=size;c.pCode=data;
        VkShaderModule module{};check(vkCreateShaderModule(device,&c,nullptr,&module),"shader module");return module;
    }
    VkRenderPass pass(VkFormat format,bool screen) {
        VkAttachmentDescription a{};a.format=format;a.samples=VK_SAMPLE_COUNT_1_BIT;
        a.loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR;a.storeOp=VK_ATTACHMENT_STORE_OP_STORE;
        a.stencilLoadOp=VK_ATTACHMENT_LOAD_OP_DONT_CARE;a.stencilStoreOp=VK_ATTACHMENT_STORE_OP_DONT_CARE;
        a.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
        a.finalLayout=screen?VK_IMAGE_LAYOUT_PRESENT_SRC_KHR:VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        VkAttachmentReference ref{0,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription sub{};sub.pipelineBindPoint=VK_PIPELINE_BIND_POINT_GRAPHICS;sub.colorAttachmentCount=1;sub.pColorAttachments=&ref;
        VkSubpassDependency deps[2]{};
        deps[0]={VK_SUBPASS_EXTERNAL,0,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                 VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT,VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,VK_DEPENDENCY_BY_REGION_BIT};
        deps[1]={0,VK_SUBPASS_EXTERNAL,VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                 VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,VK_ACCESS_MEMORY_READ_BIT,VK_DEPENDENCY_BY_REGION_BIT};
        VkRenderPassCreateInfo c{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};c.attachmentCount=1;c.pAttachments=&a;
        c.subpassCount=1;c.pSubpasses=&sub;c.dependencyCount=2;c.pDependencies=deps;
        VkRenderPass result{};check(vkCreateRenderPass(device,&c,nullptr,&result),"render pass");return result;
    }
    VkPipeline graphics(const uint32_t* frag,size_t size,VkPipelineLayout layout,VkRenderPass rp,bool win=false,bool blend=false,bool presentation=false) {
        VkShaderModule vert=shader(win?windowVert:(presentation?presentVert:postVert),win?sizeof(windowVert):(presentation?sizeof(presentVert):sizeof(postVert)));
        VkShaderModule fs=shader(frag,size);
        VkPipelineShaderStageCreateInfo stages[2]{};
        for(int n=0;n<2;n++){stages[n].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;stages[n].pName="main";}
        stages[0].stage=VK_SHADER_STAGE_VERTEX_BIT;stages[0].module=vert;
        stages[1].stage=VK_SHADER_STAGE_FRAGMENT_BIT;stages[1].module=fs;
        VkPipelineVertexInputStateCreateInfo vi{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
        VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};ia.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};vp.viewportCount=vp.scissorCount=1;
        VkPipelineRasterizationStateCreateInfo rs{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};rs.polygonMode=VK_POLYGON_MODE_FILL;rs.cullMode=VK_CULL_MODE_NONE;rs.lineWidth=1;
        VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};ms.rasterizationSamples=VK_SAMPLE_COUNT_1_BIT;
        VkPipelineColorBlendAttachmentState ba{};ba.colorWriteMask=15;ba.blendEnable=blend;
        ba.srcColorBlendFactor=VK_BLEND_FACTOR_SRC_ALPHA;ba.dstColorBlendFactor=VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;ba.colorBlendOp=VK_BLEND_OP_ADD;
        ba.srcAlphaBlendFactor=VK_BLEND_FACTOR_ONE;ba.dstAlphaBlendFactor=VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;ba.alphaBlendOp=VK_BLEND_OP_ADD;
        VkPipelineColorBlendStateCreateInfo bs{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};bs.attachmentCount=1;bs.pAttachments=&ba;
        VkDynamicState dyn[]={VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR};
        VkPipelineDynamicStateCreateInfo ds{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};ds.dynamicStateCount=2;ds.pDynamicStates=dyn;
        VkGraphicsPipelineCreateInfo p{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};p.stageCount=2;p.pStages=stages;
        p.pVertexInputState=&vi;p.pInputAssemblyState=&ia;p.pViewportState=&vp;p.pRasterizationState=&rs;
        p.pMultisampleState=&ms;p.pColorBlendState=&bs;p.pDynamicState=&ds;p.layout=layout;p.renderPass=rp;
        VkPipeline result{};VkResult status=vkCreateGraphicsPipelines(device,VK_NULL_HANDLE,1,&p,nullptr,&result);
        vkDestroyShaderModule(device,fs,nullptr);vkDestroyShaderModule(device,vert,nullptr);
        check(status,"graphics pipeline");return result;
    }
    VkDescriptorSetLayout setLayout(int sampled,bool storage) {
        std::vector<VkDescriptorSetLayoutBinding> b;
        for(int n=0;n<sampled;n++)b.push_back({uint32_t(n),VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,1,VK_SHADER_STAGE_FRAGMENT_BIT|VK_SHADER_STAGE_COMPUTE_BIT,nullptr});
        if(storage)b.push_back({uint32_t(sampled),VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,1,VK_SHADER_STAGE_COMPUTE_BIT,nullptr});
        VkDescriptorSetLayoutCreateInfo c{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};c.bindingCount=b.size();c.pBindings=b.data();
        VkDescriptorSetLayout result{};check(vkCreateDescriptorSetLayout(device,&c,nullptr,&result),"descriptor layout");return result;
    }
    VkPipelineLayout pipeLayout(VkDescriptorSetLayout set,VkShaderStageFlags stages) {
        VkPushConstantRange range{stages,0,128};
        VkPipelineLayoutCreateInfo c{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};c.setLayoutCount=1;c.pSetLayouts=&set;c.pushConstantRangeCount=1;c.pPushConstantRanges=&range;
        VkPipelineLayout result{};check(vkCreatePipelineLayout(device,&c,nullptr,&result),"pipeline layout");return result;
    }
    void init(ANativeWindow* ownedWindow) {
        window=ownedWindow;
        scanout=std::make_unique<displayx::Scanout>(window);
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};app.pApplicationName="WinXclipse compositor";app.apiVersion=VK_API_VERSION_1_1;
        const char* extensions[]={VK_KHR_SURFACE_EXTENSION_NAME,VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
        VkInstanceCreateInfo ic{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};ic.pApplicationInfo=&app;ic.enabledExtensionCount=2;ic.ppEnabledExtensionNames=extensions;
        check(vkCreateInstance(&ic,nullptr,&instance),"Vulkan 1.1 instance");
        VkAndroidSurfaceCreateInfoKHR ac{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};ac.window=window;
        check(vkCreateAndroidSurfaceKHR(instance,&ac,nullptr,&surface),"Android surface");
        uint32_t count=0;check(vkEnumeratePhysicalDevices(instance,&count,nullptr),"physical devices");
        std::vector<VkPhysicalDevice> devices(count);check(vkEnumeratePhysicalDevices(instance,&count,devices.data()),"physical devices");
        for(auto candidate:devices) {
            VkPhysicalDeviceProperties props{};vkGetPhysicalDeviceProperties(candidate,&props);
            if(props.apiVersion<VK_API_VERSION_1_1)continue;
            uint32_t n=0;vkGetPhysicalDeviceQueueFamilyProperties(candidate,&n,nullptr);
            std::vector<VkQueueFamilyProperties> q(n);vkGetPhysicalDeviceQueueFamilyProperties(candidate,&n,q.data());
            for(uint32_t j=0;j<n;j++) {
                VkBool32 present=false;check(vkGetPhysicalDeviceSurfaceSupportKHR(candidate,j,surface,&present),"present support");
                if(present && (q[j].queueFlags&(VK_QUEUE_GRAPHICS_BIT|VK_QUEUE_COMPUTE_BIT))==(VK_QUEUE_GRAPHICS_BIT|VK_QUEUE_COMPUTE_BIT)){gpu=candidate;queueFamily=j;break;}
            }
            if(gpu)break;
        }
        if(!gpu)throw std::runtime_error("Vulkan 1.1 graphics/compute presentation unavailable");
        std::vector<const char*> enabled{VK_KHR_SWAPCHAIN_EXTENSION_NAME};
        uint32_t extensionCount=0;
        check(vkEnumerateDeviceExtensionProperties(gpu,nullptr,&extensionCount,nullptr),"device extensions");
        std::vector<VkExtensionProperties> available(extensionCount);
        check(vkEnumerateDeviceExtensionProperties(gpu,nullptr,&extensionCount,available.data()),"device extensions");
        auto has=[&](const char* name){return std::any_of(available.begin(),available.end(),[&](const auto& p){return !strcmp(p.extensionName,name);});};
        ahbSupported=has(VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME)&&has(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
        if(ahbSupported){enabled.push_back(VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME);enabled.push_back(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);}
        displayTiming=precisePresentation&&has(VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME);
        if(displayTiming)enabled.push_back(VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME);
        float priority=1;VkDeviceQueueCreateInfo qc{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};qc.queueFamilyIndex=queueFamily;qc.queueCount=1;qc.pQueuePriorities=&priority;
        VkDeviceCreateInfo dc{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};dc.queueCreateInfoCount=1;dc.pQueueCreateInfos=&qc;
        dc.enabledExtensionCount=enabled.size();dc.ppEnabledExtensionNames=enabled.data();
        check(vkCreateDevice(gpu,&dc,nullptr,&device),"device");vkGetDeviceQueue(device,queueFamily,0,&queue);
        if(ahbSupported)getHardwareProperties=reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(vkGetDeviceProcAddr(device,"vkGetAndroidHardwareBufferPropertiesANDROID"));
        ahbSupported&=getHardwareProperties!=nullptr;
        VkFormatProperties fp{};vkGetPhysicalDeviceFormatProperties(gpu,VK_FORMAT_R8G8B8A8_UNORM,&fp);
        if(!(fp.optimalTilingFeatures&VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT))throw std::runtime_error("RGBA8 compute storage unavailable");
        VkCommandPoolCreateInfo cp{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};cp.queueFamilyIndex=queueFamily;cp.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        check(vkCreateCommandPool(device,&cp,nullptr,&commandPool),"command pool");
        VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ca.commandPool=commandPool;ca.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ca.commandBufferCount=1;
        check(vkAllocateCommandBuffers(device,&ca,&cb),"command buffer");
        VkFenceCreateInfo fc{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};fc.flags=VK_FENCE_CREATE_SIGNALED_BIT;check(vkCreateFence(device,&fc,nullptr,&fence),"fence");
        VkSemaphoreCreateInfo sc{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};check(vkCreateSemaphore(device,&sc,nullptr,&acquired),"acquire semaphore");
        sampledLayout=setLayout(1,false);flowLayout=setLayout(3,false);motionLayout=setLayout(3,true);
        sampledPipeLayout=pipeLayout(sampledLayout,VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT);
        flowPipeLayout=pipeLayout(flowLayout,VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT);
        motionPipeLayout=pipeLayout(motionLayout,VK_SHADER_STAGE_COMPUTE_BIT);
        VkDescriptorPoolSize sizes[]={{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,1536},{VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,64}};
        VkDescriptorPoolCreateInfo dp{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};dp.maxSets=512;dp.poolSizeCount=2;dp.pPoolSizes=sizes;
        check(vkCreateDescriptorPool(device,&dp,nullptr,&descriptors),"descriptor pool");
        VkSamplerCreateInfo sp{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};sp.magFilter=sp.minFilter=VK_FILTER_LINEAR;
        sp.mipmapMode=VK_SAMPLER_MIPMAP_MODE_NEAREST;sp.addressModeU=sp.addressModeV=sp.addressModeW=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        check(vkCreateSampler(device,&sp,nullptr,&linearSampler),"linear sampler");
        sp.magFilter=sp.minFilter=VK_FILTER_NEAREST;check(vkCreateSampler(device,&sp,nullptr,&nearestSampler),"nearest sampler");
        offPass=pass(VK_FORMAT_R8G8B8A8_UNORM,false);
        windowPipe=graphics(windowFrag,sizeof(windowFrag),sampledPipeLayout,offPass,true);
        const uint32_t* codes[]={copyFrag,colorFrag,crtFrag,easuFrag,rcasFrag,fxaaFrag,hdrFrag,ntscFrag,toonFrag};
        size_t sizesCode[]={sizeof(copyFrag),sizeof(colorFrag),sizeof(crtFrag),sizeof(easuFrag),sizeof(rcasFrag),sizeof(fxaaFrag),sizeof(hdrFrag),sizeof(ntscFrag),sizeof(toonFrag)};
        for(size_t j=0;j<effects.size();j++)effects[j]=graphics(codes[j],sizesCode[j],sampledPipeLayout,offPass);
        VkShaderModule module=shader(motionComp,sizeof(motionComp));
        VkComputePipelineCreateInfo cc{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};cc.stage={VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
        cc.stage.stage=VK_SHADER_STAGE_COMPUTE_BIT;cc.stage.module=module;cc.stage.pName="main";cc.layout=motionPipeLayout;
        VkResult status=vkCreateComputePipelines(device,VK_NULL_HANDLE,1,&cc,nullptr,&motionPipe);vkDestroyShaderModule(device,module,nullptr);check(status,"motion compute pipeline");
    }
    void freeSwapchain() {
        for(auto f:swapFrames)vkDestroyFramebuffer(device,f,nullptr);swapFrames.clear();
        for(auto v:swapViews)vkDestroyImageView(device,v,nullptr);swapViews.clear();
        for(auto s:presented)vkDestroySemaphore(device,s,nullptr);presented.clear();
        for(VkPipeline* p:{&screenWindowPipe,&screenCopyPipe,&screenFlowPipe,&screenCursorPipe}){if(*p)vkDestroyPipeline(device,*p,nullptr);*p={};}
        if(screenPass)vkDestroyRenderPass(device,screenPass,nullptr);screenPass={};
        if(swapchain)vkDestroySwapchainKHR(device,swapchain,nullptr);swapchain={};
    }
    void makeSwapchain() {
        check(vkDeviceWaitIdle(device),"resize wait");freeSwapchain();
        VkSurfaceCapabilitiesKHR caps{};check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(gpu,surface,&caps),"surface capabilities");
        uint32_t n=0;check(vkGetPhysicalDeviceSurfaceFormatsKHR(gpu,surface,&n,nullptr),"surface formats");
        if(!n)throw std::runtime_error("No surface format");
        std::vector<VkSurfaceFormatKHR> formats(n);check(vkGetPhysicalDeviceSurfaceFormatsKHR(gpu,surface,&n,formats.data()),"surface formats");
        auto chosen=formats[0];for(auto f:formats)if(f.format==VK_FORMAT_R8G8B8A8_UNORM||f.format==VK_FORMAT_B8G8R8A8_UNORM){chosen=f;break;}
        for(auto f:formats)if(f.format==preferredFormat){chosen=f;break;}
        if(chosen.format==VK_FORMAT_UNDEFINED)chosen.format=preferredFormat;
        swapFormat=chosen.format;extent=caps.currentExtent;
        if(extent.width==std::numeric_limits<uint32_t>::max())extent={uint32_t(std::max(1,ANativeWindow_getWidth(window))),uint32_t(std::max(1,ANativeWindow_getHeight(window)))};
        extent.width=std::clamp(extent.width,caps.minImageExtent.width,caps.maxImageExtent.width);
        extent.height=std::clamp(extent.height,caps.minImageExtent.height,caps.maxImageExtent.height);
        VkSwapchainCreateInfoKHR c{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};c.surface=surface;
        c.minImageCount=std::max(2u,caps.minImageCount);if(caps.maxImageCount)c.minImageCount=std::min(c.minImageCount,caps.maxImageCount);
        c.imageFormat=chosen.format;c.imageColorSpace=chosen.colorSpace;c.imageExtent=extent;c.imageArrayLayers=1;
        preTransform=caps.currentTransform;
        quarterTurns=preTransform==VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR||preTransform==VK_SURFACE_TRANSFORM_HORIZONTAL_MIRROR_ROTATE_90_BIT_KHR?1:
            preTransform==VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR||preTransform==VK_SURFACE_TRANSFORM_HORIZONTAL_MIRROR_ROTATE_180_BIT_KHR?2:
            preTransform==VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR||preTransform==VK_SURFACE_TRANSFORM_HORIZONTAL_MIRROR_ROTATE_270_BIT_KHR?3:0;
        mirror=(preTransform& (VK_SURFACE_TRANSFORM_HORIZONTAL_MIRROR_BIT_KHR|VK_SURFACE_TRANSFORM_HORIZONTAL_MIRROR_ROTATE_90_BIT_KHR|
            VK_SURFACE_TRANSFORM_HORIZONTAL_MIRROR_ROTATE_180_BIT_KHR|VK_SURFACE_TRANSFORM_HORIZONTAL_MIRROR_ROTATE_270_BIT_KHR))?1:0;
        logicalExtent=extent;
        if(quarterTurns&1)std::swap(extent.width,extent.height);
        c.imageExtent=extent;c.imageUsage=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;c.preTransform=preTransform;
        c.compositeAlpha=(caps.supportedCompositeAlpha&VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)?VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR:
            VkCompositeAlphaFlagBitsKHR(caps.supportedCompositeAlpha&(~caps.supportedCompositeAlpha+1));
        c.presentMode=VK_PRESENT_MODE_FIFO_KHR;c.clipped=VK_TRUE;
        if(!presentAtRefresh) {
            uint32_t count=0;check(vkGetPhysicalDeviceSurfacePresentModesKHR(gpu,surface,&count,nullptr),"present modes");
            std::vector<VkPresentModeKHR> modes(count);check(vkGetPhysicalDeviceSurfacePresentModesKHR(gpu,surface,&count,modes.data()),"present modes");
            if(std::find(modes.begin(),modes.end(),VK_PRESENT_MODE_MAILBOX_KHR)!=modes.end())c.presentMode=VK_PRESENT_MODE_MAILBOX_KHR;
        }
        check(vkCreateSwapchainKHR(device,&c,nullptr,&swapchain),"swapchain");
        screenPass=pass(swapFormat,true);
        screenWindowPipe=graphics(windowFrag,sizeof(windowFrag),sampledPipeLayout,screenPass,true);
        screenCopyPipe=graphics(copyFrag,sizeof(copyFrag),sampledPipeLayout,screenPass,false,false,true);
        screenFlowPipe=graphics(framegenFrag,sizeof(framegenFrag),flowPipeLayout,screenPass,false,false,true);
        screenCursorPipe=graphics(windowFrag,sizeof(windowFrag),sampledPipeLayout,screenPass,true,true);
        check(vkGetSwapchainImagesKHR(device,swapchain,&n,nullptr),"swapchain images");
        std::vector<VkImage> images(n);check(vkGetSwapchainImagesKHR(device,swapchain,&n,images.data()),"swapchain images");
        for(auto image:images) {
            Image temp{};temp.image=image;makeView(temp,swapFormat);swapViews.push_back(temp.view);
            VkFramebufferCreateInfo f{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};f.renderPass=screenPass;f.attachmentCount=1;f.pAttachments=&swapViews.back();f.width=extent.width;f.height=extent.height;f.layers=1;
            VkFramebuffer fb{};check(vkCreateFramebuffer(device,&f,nullptr,&fb),"screen framebuffer");swapFrames.push_back(fb);
            VkSemaphoreCreateInfo s{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};VkSemaphore sem{};check(vkCreateSemaphore(device,&s,nullptr,&sem),"present semaphore");presented.push_back(sem);
        }
        for(Image* i:{&scene,&ping[0],&ping[1],&history[0],&history[1],&flow[0],&flow[1]})freeImage(*i);
        historyCount=0;sceneWidth=sceneHeight=0;resizePending=false;
    }
    void barrier(Image& i,VkImageLayout layout) {
        VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};b.image=i.image;
        b.oldLayout=i.layout;b.newLayout=layout;b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
        b.srcAccessMask=i.layout==VK_IMAGE_LAYOUT_UNDEFINED?0:VK_ACCESS_MEMORY_WRITE_BIT|VK_ACCESS_MEMORY_READ_BIT;
        b.dstAccessMask=VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT;
        vkCmdPipelineBarrier(cb,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,0,0,nullptr,0,nullptr,1,&b);i.layout=layout;
    }
    bool begin(uint32_t sw,uint32_t sh,bool reset,bool point) {
        if(!sw||!sh||sw>16384||sh>16384)throw std::runtime_error("Invalid Vulkan scene size");
        check(vkWaitForFences(device,1,&fence,VK_TRUE,5'000'000'000ULL),"previous frame fence");
        // This is GPU completion, not the caller returning from vkQueuePresent.
        // Keep the latest buffer leased for cursor redraws; notify present-wait
        // separately, then return the slot only when it has been replaced.
        for(auto& frame:submittedFrames)frame->complete();submittedFrames.clear();
        prune();
        if(++transformPoll>=120&&!resizePending) {
            transformPoll=0;VkSurfaceCapabilitiesKHR caps{};
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(gpu,surface,&caps),"orientation capabilities");
            if(caps.currentTransform!=preTransform)resizePending=true;
        }
        if(resizePending){makeSwapchain();return false;}
        VkResult r=vkAcquireNextImageKHR(device,swapchain,1'000'000'000ULL,acquired,VK_NULL_HANDLE,&imageIndex);
        if(r==VK_TIMEOUT||r==VK_NOT_READY)return false;
        if(r==VK_ERROR_OUT_OF_DATE_KHR){resizePending=true;return false;}
        if(r==VK_SUBOPTIMAL_KHR)resizePending=true;else if(r!=VK_SUCCESS)check(r,"acquire image");
        check(vkResetDescriptorPool(device,descriptors,0),"reset descriptors");
        check(vkResetCommandBuffer(cb,0),"reset commands");
        if(sceneWidth!=sw||sceneHeight!=sh){freeImage(scene);sceneWidth=sw;sceneHeight=sh;reset=true;}
        if(reset)historyCount=0;
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};bi.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        check(vkBeginCommandBuffer(cb,&bi),"begin commands");
        windows.clear();cursors.clear();for(auto& pair:textures)pair.second.seen=false;
        for(auto& pair:externalTextures)pair.second.seen=false;
        nearest=point;frameOpen=true;return true;
    }
    void draw(jlong id,const void* data,size_t capacity,uint32_t w,uint32_t h,uint32_t stride,
              const std::array<float,4>& rect,bool cursor,bool swap,bool update,bool bgra) {
        if(!frameOpen)return;
        if(!w||!h||w>16384||h>16384||stride<w||uint64_t(stride)*(h-1)*4+uint64_t(w)*4>capacity)
            throw std::runtime_error("Invalid drawable pixels/stride");
        Image& i=textures[id];
        VkFormat format=bgra?VK_FORMAT_B8G8R8A8_UNORM:VK_FORMAT_R8G8B8A8_UNORM;
        if(i.image&&(i.width!=w||i.height!=h||i.format!=format)){freeImage(i);}
        if(!i.image){
            makeImage(i,w,h,false,false,format);staging(i);update=true;
        }
        i.seen=true;
        if(update) {
            if(!data)throw std::runtime_error("Unmapped drawable pixels");
            for(uint32_t y=0;y<h;y++)memcpy((char*)i.mapped+size_t(y)*w*4,(const char*)data+size_t(y)*stride*4,size_t(w)*4);
            i.pendingUpload=true;
        }
        (cursor?cursors:windows).push_back({&i,rect,swap,cursor});
    }
    void externalBarrier(Image& i,bool acquire) {
        VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};b.image=i.image;
        b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        b.oldLayout=acquire?VK_IMAGE_LAYOUT_GENERAL:VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        b.newLayout=acquire?VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL:VK_IMAGE_LAYOUT_GENERAL;
        b.srcQueueFamilyIndex=acquire?VK_QUEUE_FAMILY_FOREIGN_EXT:queueFamily;
        b.dstQueueFamilyIndex=acquire?queueFamily:VK_QUEUE_FAMILY_FOREIGN_EXT;
        b.srcAccessMask=acquire?0:VK_ACCESS_SHADER_READ_BIT;
        b.dstAccessMask=acquire?VK_ACCESS_SHADER_READ_BIT:0;
        vkCmdPipelineBarrier(cb,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,0,0,nullptr,0,nullptr,1,&b);
        i.layout=b.newLayout;
    }
    void importHardwareBuffer(Image& i,const displayx::Frame& frame) {
        const auto& m=frame.message;
        VkAndroidHardwareBufferFormatPropertiesANDROID format{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
        VkAndroidHardwareBufferPropertiesANDROID props{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};props.pNext=&format;
        check(getHardwareProperties(device,frame.buffer,&props),"DisplayX buffer properties");
        if(format.format!=VkFormat(m.format)||!(format.formatFeatures&VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT))throw std::runtime_error("DisplayX buffer format unsupported");
        VkExternalMemoryImageCreateInfo external{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};external.handleTypes=VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
        VkImageCreateInfo create{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};create.pNext=&external;create.imageType=VK_IMAGE_TYPE_2D;
        create.format=format.format;create.extent={m.width,m.height,1};create.mipLevels=create.arrayLayers=1;
        create.samples=VK_SAMPLE_COUNT_1_BIT;create.tiling=VK_IMAGE_TILING_OPTIMAL;create.usage=VK_IMAGE_USAGE_SAMPLED_BIT;
        create.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
        check(vkCreateImage(device,&create,nullptr,&i.image),"DisplayX image");
        VkImportAndroidHardwareBufferInfoANDROID imported{VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};imported.buffer=frame.buffer;
        VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};dedicated.image=i.image;dedicated.pNext=&imported;
        VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};allocation.pNext=&dedicated;
        allocation.allocationSize=props.allocationSize;allocation.memoryTypeIndex=memoryType(props.memoryTypeBits,0);
        check(vkAllocateMemory(device,&allocation,nullptr,&i.memory),"DisplayX memory");
        check(vkBindImageMemory(device,i.image,i.memory,0),"DisplayX bind");makeView(i,format.format);
        i.width=m.width;i.height=m.height;i.format=format.format;i.layout=VK_IMAGE_LAYOUT_GENERAL;
        i.linearSupported=(format.formatFeatures&VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT)!=0;
        i.hardwareBuffer=frame.buffer;AHardwareBuffer_acquire(i.hardwareBuffer);
    }
    bool drawDirect(uint32_t window,const std::array<float,4>& rect,bool swap) {
        if(!frameOpen||!directServer)return false;
        auto incoming=directServer->take(window);
        auto& frame=directFrames[window];
        // begin() has waited the previous submission. Replacing this lease is
        // now safe, including when the producer recreated the entire swapchain.
        if(incoming)frame=std::move(incoming);
        if(frame&&!frame->connected())frame.reset();
        if(!frame)return false;
        auto& image=externalTextures[frame->bufferId];
        image.seen=true;windows.push_back({&image,rect,swap,false,frame});return true;
    }
    VkDescriptorSet set(VkDescriptorSetLayout layout,const std::vector<Image*>& sampled,Image* output=nullptr,bool point=false) {
        VkDescriptorSetAllocateInfo a{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};a.descriptorPool=descriptors;a.descriptorSetCount=1;a.pSetLayouts=&layout;
        VkDescriptorSet result{};check(vkAllocateDescriptorSets(device,&a,&result),"descriptor set");
        std::vector<VkDescriptorImageInfo> infos(sampled.size()+(output?1:0));std::vector<VkWriteDescriptorSet> writes(infos.size());
        for(size_t n=0;n<infos.size();n++) {
            bool storage=n==sampled.size();Image* i=storage?output:sampled[n];
            infos[n]={storage?VK_NULL_HANDLE:(point||!i->linearSupported?nearestSampler:linearSampler),i->view,storage?VK_IMAGE_LAYOUT_GENERAL:VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
            writes[n]={VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};writes[n].dstSet=result;writes[n].dstBinding=n;writes[n].descriptorCount=1;
            writes[n].descriptorType=storage?VK_DESCRIPTOR_TYPE_STORAGE_IMAGE:VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;writes[n].pImageInfo=&infos[n];
        }
        vkUpdateDescriptorSets(device,writes.size(),writes.data(),0,nullptr);return result;
    }
    void startPass(VkRenderPass rp,VkFramebuffer fb,uint32_t w,uint32_t h) {
        VkClearValue clear={{{0,0,0,1}}};VkRenderPassBeginInfo b{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
        b.renderPass=rp;b.framebuffer=fb;b.renderArea={{0,0},{w,h}};b.clearValueCount=1;b.pClearValues=&clear;
        vkCmdBeginRenderPass(cb,&b,VK_SUBPASS_CONTENTS_INLINE);
        VkViewport v{0,0,float(w),float(h),0,1};VkRect2D sc{{0,0},{w,h}};vkCmdSetViewport(cb,0,1,&v);vkCmdSetScissor(cb,0,1,&sc);
    }
    void fullscreen(VkPipeline pipeline,VkPipelineLayout layout,VkDescriptorSet ds,const void* push=nullptr) {
        vkCmdBindPipeline(cb,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline);
        vkCmdBindDescriptorSets(cb,VK_PIPELINE_BIND_POINT_GRAPHICS,layout,0,1,&ds,0,nullptr);
        std::array<uint32_t,32> parameters{};if(push)memcpy(parameters.data(),push,128);
        if(pipeline==screenCopyPipe||pipeline==screenFlowPipe){parameters[24]=quarterTurns;parameters[25]=mirror;}
        vkCmdPushConstants(cb,layout,VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,128,parameters.data());
        vkCmdDraw(cb,3,1,0,0);
    }
    void windowDraws(const std::vector<Draw>& list,VkPipeline pipeline,uint32_t width,uint32_t height,bool presentation=false) {
        auto clip=contentRect;
        if(presentation) {
            float x=clip[0],y=clip[1],w=clip[2],h=clip[3];
            if(mirror)x=1-x-w;
            if(quarterTurns==1)clip={1-y-h,x,h,w};
            else if(quarterTurns==2)clip={1-x-w,1-y-h,w,h};
            else if(quarterTurns==3)clip={y,1-x-w,h,w};
            else clip={x,y,w,h};
        }
        int x=std::max(0,int(clip[0]*width)),y=std::max(0,int(clip[1]*height));
        VkRect2D sc{{x,y},{std::min(width-std::min(width,uint32_t(x)),uint32_t(std::max(0.f,clip[2]*width))),
                          std::min(height-std::min(height,uint32_t(y)),uint32_t(std::max(0.f,clip[3]*height)))}};
        vkCmdSetScissor(cb,0,1,&sc);
        for(const auto& d:list) {
            std::array<uint32_t,32> push{};memcpy(push.data(),d.rect.data(),16);push[4]=d.swap;push[5]=d.alpha;
            if(presentation){push[24]=quarterTurns;push[25]=mirror;}
            VkDescriptorSet ds=set(sampledLayout,{d.image},nullptr,nearest);
            vkCmdBindPipeline(cb,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline);
            vkCmdBindDescriptorSets(cb,VK_PIPELINE_BIND_POINT_GRAPHICS,sampledPipeLayout,0,1,&ds,0,nullptr);
            vkCmdPushConstants(cb,sampledPipeLayout,VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,128,push.data());vkCmdDraw(cb,6,1,0,0);
        }
    }
    void copy(Image& src,Image& dst) {
        barrier(src,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);barrier(dst,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        VkImageCopy region{};region.srcSubresource=region.dstSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};region.extent={logicalExtent.width,logicalExtent.height,1};
        vkCmdCopyImage(cb,src.image,src.layout,dst.image,dst.layout,1,&region);
        barrier(src,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);barrier(dst,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }
    void clearFlow(Image& i) {
        barrier(i,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);VkClearColorValue zero{{0.5f,0.5f,0,0}};VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        vkCmdClearColorImage(cb,i.image,i.layout,&zero,1,&range);barrier(i,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }
    bool present(const std::vector<int>& ids,const uint8_t* pushes,bool fg,bool capture,bool generated,float alpha,int quality,float stability,uint32_t motionWidth,uint32_t motionHeight) {
        if(!frameOpen)return false;
        waitingOnScanout=false;bool direct=false;
        if(scanoutAllowed&&ids.empty()&&!fg&&windows.size()==1&&cursors.empty()) {
            auto& draw=windows[0];auto& r=draw.rect;
            float x=(r[0]+1)*.5f,y=(r[1]+1)*.5f,w=(r[2]-r[0])*.5f,h=(r[3]-r[1])*.5f;
            // Only the complete visible game rectangle: no magnifier cropping,
            // stacked Wine windows, emulated cursor or color-channel override.
            if(displayx::canScanout(windows.size(),cursors.size(),ids.size(),fg,bool(draw.frame),draw.swap,r.data(),contentRect.data())) {
                int32_t width=ANativeWindow_getWidth(window),height=ANativeWindow_getHeight(window);
                std::array<int32_t,4> target{int32_t(x*width),int32_t(y*height),int32_t((x+w)*width),int32_t((y+h)*height)};
                direct=scanout->show(draw.frame,target,desiredPresentationNanos);
            }
        }
        scanoutActive=direct;if(!direct)scanout->hide();
        for(auto it=windows.begin();it!=windows.end();) {
            if(!it->frame){++it;continue;}
            if(direct){it=windows.erase(it);continue;}
            // A plane detached for effects/menus can still be scanning out its
            // previous buffer. Do not acquire its foreign ownership prematurely.
            if(it->frame->externalReaders.load()) {waitingOnScanout=true;it=windows.erase(it);continue;}
            try {
                if(!ahbSupported)throw std::runtime_error("Compositor has no AHB import support");
                if(!it->image->image)importHardwareBuffer(*it->image,*it->frame);
                externalBarrier(*it->image,true);submittedFrames.push_back(it->frame);++it;
            } catch(const std::exception& error) {
                __android_log_print(ANDROID_LOG_WARN,"DisplayX","%s; retaining X11 fallback",error.what());
                freeImage(*it->image);it->frame->disconnect();it=windows.erase(it);
            }
        }
        if(waitingOnScanout){fg=false;capture=false;generated=false;historyCount=0;}
        int transport=direct?2:!submittedFrames.empty()?1:0;
        if(transport!=reportedTransport) {
            reportedTransport=transport;
            __android_log_print(ANDROID_LOG_INFO,"DisplayX","Presentation: %s",transport==2?"SurfaceFlinger direct AHB":transport==1?"AHB Vulkan composition":"X11 pixel upload");
        }
        for(auto& pair:textures) {
            Image& i=pair.second;if(!i.seen||!i.pendingUpload)continue;
            barrier(i,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            VkBufferImageCopy region{};region.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};region.imageExtent={i.width,i.height,1};
            vkCmdCopyBufferToImage(cb,i.staging,i.image,i.layout,1,&region);barrier(i,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);i.pendingUpload=false;
        }
        if (!fg) {
            for(Image* i:{&history[0],&history[1],&flow[0],&flow[1]})freeImage(*i);
            historyCount=0;
        } else if(!history[0].image) {
            for(auto& i:history)makeImage(i,logicalExtent.width,logicalExtent.height,false);
            historyCount=0;
        }
        if(fg&&(flow[0].width!=motionWidth||flow[0].height!=motionHeight)) {
            if(!motionWidth||!motionHeight||motionWidth>logicalExtent.width||motionHeight>logicalExtent.height)
                throw std::runtime_error("Invalid motion-vector dimensions");
            for(auto& i:flow){freeImage(i);makeImage(i,motionWidth,motionHeight,false,true);}
            historyCount=0;
        }
        if(ids.size()<2)freeImage(ping[1]);
        if(ids.empty()&&!fg) {
            freeImage(scene);for(auto& i:ping)freeImage(i);
            startPass(screenPass,swapFrames[imageIndex],extent.width,extent.height);
            windowDraws(windows,screenWindowPipe,extent.width,extent.height,true);
        } else {
            if(!scene.image)makeImage(scene,sceneWidth,sceneHeight,true);
            Image* result=&scene;
            if(!generated||!fg||historyCount<2) {
                startPass(offPass,scene.framebuffer,scene.width,scene.height);windowDraws(windows,windowPipe,scene.width,scene.height);vkCmdEndRenderPass(cb);scene.layout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                for(size_t n=0;n<ids.size();n++) {
                    if(ids[n]<0||ids[n]>=int(effects.size()))throw std::runtime_error("Unknown Vulkan effect");
                    Image& out=ping[n%2];
                    if(!out.image)makeImage(out,logicalExtent.width,logicalExtent.height,true);
                    startPass(offPass,out.framebuffer,out.width,out.height);
                    fullscreen(effects[ids[n]],sampledPipeLayout,set(sampledLayout,{result}),pushes+n*128);vkCmdEndRenderPass(cb);
                    out.layout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;result=&out;
                }
                // Without an effect, copy/scale to a display-sized image before history capture.
                if(result->width!=logicalExtent.width||result->height!=logicalExtent.height) {
                    Image& out=ping[ids.size()%2];if(!out.image)makeImage(out,logicalExtent.width,logicalExtent.height,true);
                    startPass(offPass,out.framebuffer,out.width,out.height);
                    fullscreen(effects[0],sampledPipeLayout,set(sampledLayout,{result}));vkCmdEndRenderPass(cb);out.layout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;result=&out;
                }
                if(fg&&capture) {
                    currentHistory=historyCount?1-currentHistory:0;copy(*result,history[currentHistory]);
                    if(!historyCount){copy(*result,history[1-currentHistory]);clearFlow(flow[0]);clearFlow(flow[1]);currentFlow=0;}
                    else {
                        uint32_t dest=1-currentFlow;barrier(flow[dest],VK_IMAGE_LAYOUT_GENERAL);
                        VkDescriptorSet ds=set(motionLayout,{&history[currentHistory],&history[1-currentHistory],&flow[currentFlow]},&flow[dest]);
                        vkCmdBindPipeline(cb,VK_PIPELINE_BIND_POINT_COMPUTE,motionPipe);
                        vkCmdBindDescriptorSets(cb,VK_PIPELINE_BIND_POINT_COMPUTE,motionPipeLayout,0,1,&ds,0,nullptr);
                        std::array<uint32_t,32> push{};push[0]=quality;vkCmdPushConstants(cb,motionPipeLayout,VK_SHADER_STAGE_COMPUTE_BIT,0,128,push.data());
                        vkCmdDispatch(cb,(motionWidth+15)/16,(motionHeight+7)/8,1);
                        barrier(flow[dest],VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);currentFlow=dest;
                    }
                    historyCount=std::min(2u,historyCount+1);
                }
            }
            startPass(screenPass,swapFrames[imageIndex],extent.width,extent.height);
            if(fg&&historyCount>=2) {
                std::array<float,32> push{};push[0]=alpha;push[1]=quality;push[2]=stability;push[3]=0; // interpolation only, same as 0.9.5
                fullscreen(screenFlowPipe,flowPipeLayout,set(flowLayout,{&history[1-currentHistory],&history[currentHistory],&flow[currentFlow]}),push.data());
            } else if(fg&&historyCount)fullscreen(screenCopyPipe,sampledPipeLayout,set(sampledLayout,{&history[currentHistory]}));
            else fullscreen(screenCopyPipe,sampledPipeLayout,set(sampledLayout,{result}));
        }
        // Cursor stays out of temporal history, preventing doubled/warped pointers.
        windowDraws(cursors,screenCursorPipe,extent.width,extent.height,true);vkCmdEndRenderPass(cb);
        for(auto& draw:windows)if(draw.frame)externalBarrier(*draw.image,false);
        check(vkEndCommandBuffer(cb),"end commands");
        VkPipelineStageFlags wait=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};si.waitSemaphoreCount=1;si.pWaitSemaphores=&acquired;si.pWaitDstStageMask=&wait;
        si.commandBufferCount=1;si.pCommandBuffers=&cb;si.signalSemaphoreCount=1;si.pSignalSemaphores=&presented[imageIndex];
        check(vkResetFences(device,1,&fence),"reset frame fence");check(vkQueueSubmit(queue,1,&si,fence),"submit frame");frameOpen=false;
        VkPresentInfoKHR p{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};p.waitSemaphoreCount=1;p.pWaitSemaphores=&presented[imageIndex];p.swapchainCount=1;p.pSwapchains=&swapchain;p.pImageIndices=&imageIndex;
        VkPresentTimeGOOGLE time{++timingPresentId,desiredPresentationNanos};
        VkPresentTimesInfoGOOGLE times{VK_STRUCTURE_TYPE_PRESENT_TIMES_INFO_GOOGLE};times.swapchainCount=1;times.pTimes=&time;
        if(displayTiming&&desiredPresentationNanos)p.pNext=&times;
        VkResult status=vkQueuePresentKHR(queue,&p);if(status==VK_ERROR_OUT_OF_DATE_KHR||status==VK_SUBOPTIMAL_KHR)resizePending=true;else check(status,"present frame");
        // Unused images are retired at next begin, after the submission fence.
        return status!=VK_ERROR_OUT_OF_DATE_KHR&&!waitingOnScanout;
    }
    void prune() {
        for(auto it=textures.begin();it!=textures.end();)if(!it->second.seen){freeImage(it->second);it=textures.erase(it);}else ++it;
        for(auto it=externalTextures.begin();it!=externalTextures.end();)if(!it->second.seen){freeImage(it->second);it=externalTextures.erase(it);}else ++it;
        // Unmapped/hidden windows must not retain their pool forever. Their last
        // draw finished before prune(). A future present can establish a lease.
        for(auto it=directFrames.begin();it!=directFrames.end();) {
            auto frame=it->second;
            if(!frame||!frame->connected()||externalTextures.find(frame->bufferId)==externalTextures.end())it=directFrames.erase(it);else ++it;
        }
    }
    void destroy() {
        if(device) {
            vkDeviceWaitIdle(device);
            if(scanout)scanout->hide();scanout.reset();
            for(auto& frame:submittedFrames)frame->complete();submittedFrames.clear();
            for(auto& p:externalTextures)freeImage(p.second);externalTextures.clear();directFrames.clear();directServer.reset();
            for(auto& p:textures)freeImage(p.second);
            for(Image* i:{&scene,&ping[0],&ping[1],&history[0],&history[1],&flow[0],&flow[1]})freeImage(*i);
            freeSwapchain();
            for(auto p:effects)if(p)vkDestroyPipeline(device,p,nullptr);
            for(auto p:{windowPipe,motionPipe})if(p)vkDestroyPipeline(device,p,nullptr);
            if(offPass)vkDestroyRenderPass(device,offPass,nullptr);
            for(auto p:{sampledPipeLayout,flowPipeLayout,motionPipeLayout})if(p)vkDestroyPipelineLayout(device,p,nullptr);
            for(auto l:{sampledLayout,flowLayout,motionLayout})if(l)vkDestroyDescriptorSetLayout(device,l,nullptr);
            if(descriptors)vkDestroyDescriptorPool(device,descriptors,nullptr);
            for(auto s:{linearSampler,nearestSampler})if(s)vkDestroySampler(device,s,nullptr);
            if(fence)vkDestroyFence(device,fence,nullptr);if(acquired)vkDestroySemaphore(device,acquired,nullptr);
            if(commandPool)vkDestroyCommandPool(device,commandPool,nullptr);vkDestroyDevice(device,nullptr);
        }
        if(surface)vkDestroySurfaceKHR(instance,surface,nullptr);if(instance)vkDestroyInstance(instance,nullptr);
        if(window)ANativeWindow_release(window);
    }
};
void fail(JNIEnv* e,const std::exception& error){
    if(e->ExceptionCheck())return;
    jclass type=e->FindClass("java/lang/IllegalStateException");
    if(type){e->ThrowNew(type,error.what());e->DeleteLocalRef(type);}
}
Display& display(jlong h){if(!h)throw std::runtime_error("Vulkan surface is closed");return *reinterpret_cast<Display*>(h);}
}
#define JNI(name) Java_com_winlator_cmod_renderer_VulkanRenderer_##name
extern "C" JNIEXPORT jlong JNICALL JNI(nativeCreate)(JNIEnv* e,jclass,jobject surface,jboolean rgba,jboolean atRefresh,jboolean precise) {
    try {auto d=std::make_unique<Display>();d->preferredFormat=rgba?VK_FORMAT_R8G8B8A8_UNORM:VK_FORMAT_B8G8R8A8_UNORM;d->presentAtRefresh=atRefresh;d->precisePresentation=precise;auto w=ANativeWindow_fromSurface(e,surface);if(!w)throw std::runtime_error("Invalid Android surface");d->init(w);d->makeSwapchain();return reinterpret_cast<jlong>(d.release());}
    catch(const std::exception& x){fail(e,x);return 0;}
}
extern "C" JNIEXPORT void JNICALL JNI(nativeDestroy)(JNIEnv*,jclass,jlong h){delete reinterpret_cast<Display*>(h);}
extern "C" JNIEXPORT void JNICALL JNI(nativeDisplayX)(JNIEnv* e,jclass,jlong h,jlong server) {
    try {display(h).directServer=server?*reinterpret_cast<std::shared_ptr<displayx::Server>*>(server):nullptr;}
    catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jboolean JNICALL JNI(nativeNeedsCompletion)(JNIEnv* e,jclass,jlong h) {
    try{auto& d=display(h);if(d.waitingOnScanout)return true;for(auto& frame:d.submittedFrames)if(!frame->completed)return true;return false;}
    catch(const std::exception& x){fail(e,x);return false;}
}
extern "C" JNIEXPORT void JNICALL JNI(nativeScanoutAllowed)(JNIEnv* e,jclass,jlong h,jboolean enabled) {
    try{display(h).scanoutAllowed=enabled;}catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jboolean JNICALL JNI(nativeCompositionReady)(JNIEnv* e,jclass,jlong h) {
    try{auto& d=display(h);return !d.scanoutActive&&!d.waitingOnScanout;}
    catch(const std::exception& x){fail(e,x);return false;}
}
extern "C" JNIEXPORT jboolean JNICALL JNI(nativeDrawDirect)(JNIEnv* e,jclass,jlong h,jint window,jfloat x0,jfloat y0,jfloat x1,jfloat y1,jboolean swap) {
    try{return display(h).drawDirect(uint32_t(window),{x0,y0,x1,y1},swap);}catch(const std::exception& x){fail(e,x);return false;}
}
extern "C" JNIEXPORT jlong JNICALL JNI(nativeSize)(JNIEnv* e,jclass,jlong h) {
    try {auto size=display(h).logicalExtent;return jlong((uint64_t(size.width)<<32)|size.height);}
    catch(const std::exception& x){fail(e,x);return 0;}
}
extern "C" JNIEXPORT void JNICALL JNI(nativeContentRect)(JNIEnv* e,jclass,jlong h,jfloat x,jfloat y,jfloat w,jfloat height) {
    try {display(h).contentRect={x,y,w,height};}catch(const std::exception& error){fail(e,error);}
}
extern "C" JNIEXPORT jboolean JNICALL JNI(nativeBegin)(JNIEnv* e,jclass,jlong h,jint w,jint height,jboolean reset,jboolean nearest) {
    try {return display(h).begin(w,height,reset,nearest);}
    catch(const std::exception& x){fail(e,x);return false;}
}
extern "C" JNIEXPORT void JNICALL JNI(nativeDraw)(JNIEnv* e,jclass,jlong h,jlong id,jobject data,jint w,jint height,jint stride,
        jfloat x0,jfloat y0,jfloat x1,jfloat y1,jboolean cursor,jboolean swap,jboolean update,jboolean bgra) {
    try {display(h).draw(id,e->GetDirectBufferAddress(data),size_t(std::max<jlong>(0,e->GetDirectBufferCapacity(data))),w,height,stride,{x0,y0,x1,y1},cursor,swap,update,bgra);}
    catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jboolean JNICALL JNI(nativePresent)(JNIEnv* e,jclass,jlong h,jintArray ids,jobject pushes,jboolean fg,jboolean capture,jboolean generated,jfloat alpha,jint quality,jfloat stability,jint motionWidth,jint motionHeight) {
    try {
        jsize n=e->GetArrayLength(ids);if(n>32||e->GetDirectBufferCapacity(pushes)<jlong(n)*128)throw std::runtime_error("Invalid effect plan");
        std::vector<int> list(n);e->GetIntArrayRegion(ids,0,n,list.data());
        return display(h).present(list,static_cast<const uint8_t*>(e->GetDirectBufferAddress(pushes)),fg,capture,generated,alpha,quality,stability,motionWidth,motionHeight);
    }catch(const std::exception& x){fail(e,x);return false;}
}

extern "C" JNIEXPORT void JNICALL JNI(nativePresentationTime)(JNIEnv* e,jclass,jlong h,jlong nanos) {
    try{display(h).desiredPresentationNanos=nanos>0?uint64_t(nanos):0;}catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jboolean JNICALL JNI(nativeHasPendingFrames)(JNIEnv* e,jclass,jlong h) {
    try{auto& d=display(h);if(d.directServer)for(auto& draw:d.windows)if(draw.frame&&d.directServer->hasPending(draw.frame->message.window))return true;return false;}
    catch(const std::exception& x){fail(e,x);return false;}
}
