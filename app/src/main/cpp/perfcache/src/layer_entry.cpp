// layer_entry.cpp
//
// Entry point for VK_LAYER_PERFCACHE.
//
// Responsibilities:
//   1. Export the symbols the Android Vulkan loader requires.
//   2. Intercept vkCreateInstance / vkCreateDevice to build the dispatch tables.
//   3. Intercept vkDestroyInstance / vkDestroyDevice to clean up.
//   4. Intercept vkCreateGraphicsPipelines / vkCreateComputePipelines to inject
//      the persistent pipeline cache and run the sanitizer.
//   5. Intercept vkMapMemory / vkUnmapMemory / vkCmdCopyBufferToImage* for the
//      texture cache.
//   6. Wire LD_PRELOAD / wrapper mode: dlopen("libvulkan.so") + manual table.
//
// Modules initialised on vkCreateDevice:
//   xclipse_detect -> settings -> pipeline_cache -> texture_cache ready
//
// Thread safety:
//   Global dispatch maps are protected by their mutexes.
//   Dispatch lookups return copies, not raw pointers into maps.

#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>

#include "dispatch_table.h"
#include "layer_settings.h"
#include "log.h"
#include "pipeline_cache.h"
#include "pipeline_sanitizer.h"
#include "pipeline_control.h"
#include "texture_cache.h"
#include "xclipse_detect.h"
#include "conflict_detect.h"
#include "perf_metrics.h"

#include <dlfcn.h>
#include <cstring>
#include <mutex>
#include <unordered_map>
#include <vector>
#include <chrono>
#include <functional>

// ─────────────────────────────────────────────────────────────────────────────
//  Global dispatch tables  (declared extern in dispatch_table.h)
// ─────────────────────────────────────────────────────────────────────────────

std::mutex                                  g_instance_lock;
std::unordered_map<void*, InstanceDispatch> g_instance_dispatch;

std::mutex                                  g_device_lock;
std::unordered_map<void*, DeviceDispatch>   g_device_dispatch;

// Physical-device handles are not keyed by VkInstance in the public API, so
// vkEnumerateDeviceExtensionProperties needs a small side table populated by
// our vkEnumeratePhysicalDevices intercept. Calling GIPA(VK_NULL_HANDLE, ...)
// for that command is not valid on normal loaders. Vulkan: strict, fussy,
// and somehow still surprised when people work around it.
static std::mutex g_physdev_lock;
static std::unordered_map<VkPhysicalDevice, VkInstance> g_physdev_instance;

static VkInstance lookup_instance_for_physdev(VkPhysicalDevice physDev) {
    std::lock_guard<std::mutex> lk(g_physdev_lock);
    auto it = g_physdev_instance.find(physDev);
    return (it != g_physdev_instance.end()) ? it->second : VK_NULL_HANDLE;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Per-device subgroup size properties
// ─────────────────────────────────────────────────────────────────────────────

static std::mutex g_subgroup_lock;
static std::unordered_map<VkDevice, VkPhysicalDeviceSubgroupSizeControlProperties>
    g_subgroup_props;

// ─────────────────────────────────────────────────────────────────────────────
//  Visibility macro
// ─────────────────────────────────────────────────────────────────────────────

#define LAYER_EXPORT extern "C" __attribute__((visibility("default")))

// ─────────────────────────────────────────────────────────────────────────────
//  Exported enumerate declarations used by vkGetInstanceProcAddr
// ─────────────────────────────────────────────────────────────────────────────

LAYER_EXPORT VKAPI_ATTR VkResult VKAPI_CALL
vkEnumerateInstanceLayerProperties(uint32_t*, VkLayerProperties*);

LAYER_EXPORT VKAPI_ATTR VkResult VKAPI_CALL
vkEnumerateInstanceExtensionProperties(const char*, uint32_t*, VkExtensionProperties*);

LAYER_EXPORT VKAPI_ATTR VkResult VKAPI_CALL
vkEnumerateDeviceLayerProperties(VkPhysicalDevice, uint32_t*, VkLayerProperties*);

LAYER_EXPORT VKAPI_ATTR VkResult VKAPI_CALL
vkEnumerateDeviceExtensionProperties(VkPhysicalDevice, const char*, uint32_t*, VkExtensionProperties*);

// ─────────────────────────────────────────────────────────────────────────────
//  LD_PRELOAD / wrapper mode support
// ─────────────────────────────────────────────────────────────────────────────

static PFN_vkGetInstanceProcAddr s_loader_gipa = nullptr;
static void* s_libvulkan_handle = nullptr;
static std::once_flag s_loader_once;

static PFN_vkGetInstanceProcAddr get_loader_gipa() {
    std::call_once(s_loader_once, [] {
        s_libvulkan_handle = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        if (!s_libvulkan_handle)
            s_libvulkan_handle = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);

        if (s_libvulkan_handle) {
            s_loader_gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
                dlsym(s_libvulkan_handle, "vkGetInstanceProcAddr"));
        }
    });

    return s_loader_gipa;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Forward declarations for intercept functions
// ─────────────────────────────────────────────────────────────────────────────

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateInstance(
    const VkInstanceCreateInfo*,
    const VkAllocationCallbacks*,
    VkInstance*);

static VKAPI_ATTR void VKAPI_CALL layer_DestroyInstance(
    VkInstance, const VkAllocationCallbacks*);

static VKAPI_ATTR VkResult VKAPI_CALL layer_EnumeratePhysicalDevices(
    VkInstance, uint32_t*, VkPhysicalDevice*);

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateDevice(
    VkPhysicalDevice,
    const VkDeviceCreateInfo*,
    const VkAllocationCallbacks*,
    VkDevice*);

static VKAPI_ATTR void VKAPI_CALL layer_DestroyDevice(
    VkDevice, const VkAllocationCallbacks*);

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateGraphicsPipelines(
    VkDevice, VkPipelineCache, uint32_t,
    const VkGraphicsPipelineCreateInfo*,
    const VkAllocationCallbacks*, VkPipeline*);

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateComputePipelines(
    VkDevice, VkPipelineCache, uint32_t,
    const VkComputePipelineCreateInfo*,
    const VkAllocationCallbacks*, VkPipeline*);

static VKAPI_ATTR VkResult VKAPI_CALL layer_MapMemory(
    VkDevice, VkDeviceMemory, VkDeviceSize, VkDeviceSize, VkMemoryMapFlags, void**);

static VKAPI_ATTR void VKAPI_CALL layer_UnmapMemory(
    VkDevice, VkDeviceMemory);

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateBuffer(
    VkDevice, const VkBufferCreateInfo*, const VkAllocationCallbacks*, VkBuffer*);

static VKAPI_ATTR void VKAPI_CALL layer_DestroyBuffer(
    VkDevice, VkBuffer, const VkAllocationCallbacks*);

static VKAPI_ATTR VkResult VKAPI_CALL layer_BindBufferMemory(
    VkDevice, VkBuffer, VkDeviceMemory, VkDeviceSize);

static VKAPI_ATTR VkResult VKAPI_CALL layer_BindBufferMemory2(
    VkDevice, uint32_t, const VkBindBufferMemoryInfo*);

static VKAPI_ATTR VkResult VKAPI_CALL layer_BindBufferMemory2KHR(
    VkDevice, uint32_t, const VkBindBufferMemoryInfo*);

static VKAPI_ATTR void VKAPI_CALL layer_CmdCopyBufferToImage(
    VkCommandBuffer, VkBuffer, VkImage, VkImageLayout,
    uint32_t, const VkBufferImageCopy*);

static VKAPI_ATTR void VKAPI_CALL layer_CmdCopyBufferToImage2(
    VkCommandBuffer, const VkCopyBufferToImageInfo2*);

static VKAPI_ATTR void VKAPI_CALL layer_CmdCopyBufferToImage2KHR(
    VkCommandBuffer, const VkCopyBufferToImageInfo2*);

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateRenderPass(
    VkDevice, const VkRenderPassCreateInfo*, const VkAllocationCallbacks*, VkRenderPass*);

static VKAPI_ATTR void VKAPI_CALL layer_DestroyRenderPass(
    VkDevice, VkRenderPass, const VkAllocationCallbacks*);

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateRenderPass2(
    VkDevice, const VkRenderPassCreateInfo2*, const VkAllocationCallbacks*, VkRenderPass*);

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateRenderPass2KHR(
    VkDevice, const VkRenderPassCreateInfo2*, const VkAllocationCallbacks*, VkRenderPass*);

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateFramebuffer(
    VkDevice, const VkFramebufferCreateInfo*, const VkAllocationCallbacks*, VkFramebuffer*);

static VKAPI_ATTR void VKAPI_CALL layer_DestroyFramebuffer(
    VkDevice, VkFramebuffer, const VkAllocationCallbacks*);

// ─────────────────────────────────────────────────────────────────────────────
//  vkGetInstanceProcAddr
// ─────────────────────────────────────────────────────────────────────────────

LAYER_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
vkGetInstanceProcAddr(VkInstance instance, const char* pName) {
    if (!pName) return nullptr;

    if (strcmp(pName, "vkEnumerateInstanceLayerProperties") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(vkEnumerateInstanceLayerProperties);

    if (strcmp(pName, "vkEnumerateInstanceExtensionProperties") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(vkEnumerateInstanceExtensionProperties);

    if (strcmp(pName, "vkEnumerateDeviceLayerProperties") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(vkEnumerateDeviceLayerProperties);

    if (strcmp(pName, "vkEnumerateDeviceExtensionProperties") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(vkEnumerateDeviceExtensionProperties);

#define INTERCEPT_INSTANCE(fn) \
    if (strcmp(pName, "vk" #fn) == 0) return reinterpret_cast<PFN_vkVoidFunction>(layer_##fn);

    INTERCEPT_INSTANCE(CreateInstance)
    INTERCEPT_INSTANCE(DestroyInstance)
    INTERCEPT_INSTANCE(EnumeratePhysicalDevices)
    INTERCEPT_INSTANCE(CreateDevice)

#undef INTERCEPT_INSTANCE

    if (instance == VK_NULL_HANDLE) {
        PFN_vkGetInstanceProcAddr next = get_loader_gipa();
        if (next) return next(instance, pName);
        return nullptr;
    }

    InstanceDispatch disp{};
    bool has_disp = get_instance_dispatch_copy(instance, disp);
    if (has_disp && disp.GetInstanceProcAddr)
        return disp.GetInstanceProcAddr(instance, pName);

    return nullptr;
}

// ─────────────────────────────────────────────────────────────────────────────
//  vkGetDeviceProcAddr
// ─────────────────────────────────────────────────────────────────────────────

LAYER_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
vkGetDeviceProcAddr(VkDevice device, const char* pName) {
    if (!pName) return nullptr;

#define INTERCEPT_DEVICE(fn) \
    if (strcmp(pName, "vk" #fn) == 0) return reinterpret_cast<PFN_vkVoidFunction>(layer_##fn);

    INTERCEPT_DEVICE(DestroyDevice)
    INTERCEPT_DEVICE(CreateGraphicsPipelines)
    INTERCEPT_DEVICE(CreateComputePipelines)
    INTERCEPT_DEVICE(MapMemory)
    INTERCEPT_DEVICE(UnmapMemory)
    INTERCEPT_DEVICE(CreateBuffer)
    INTERCEPT_DEVICE(DestroyBuffer)
    INTERCEPT_DEVICE(BindBufferMemory)
    INTERCEPT_DEVICE(BindBufferMemory2)
    INTERCEPT_DEVICE(BindBufferMemory2KHR)
    INTERCEPT_DEVICE(CmdCopyBufferToImage)
    INTERCEPT_DEVICE(CmdCopyBufferToImage2)
    INTERCEPT_DEVICE(CmdCopyBufferToImage2KHR)
    INTERCEPT_DEVICE(CreateRenderPass)
    INTERCEPT_DEVICE(DestroyRenderPass)
    INTERCEPT_DEVICE(CreateRenderPass2)
    INTERCEPT_DEVICE(CreateRenderPass2KHR)
    INTERCEPT_DEVICE(CreateFramebuffer)
    INTERCEPT_DEVICE(DestroyFramebuffer)

#undef INTERCEPT_DEVICE

    DeviceDispatch disp{};
    bool has_disp = get_device_dispatch_copy(device, disp);
    if (has_disp && disp.GetDeviceProcAddr)
        return disp.GetDeviceProcAddr(device, pName);

    return nullptr;
}

// ─────────────────────────────────────────────────────────────────────────────
//  vkNegotiateLoaderLayerInterfaceVersion
// ─────────────────────────────────────────────────────────────────────────────

LAYER_EXPORT VKAPI_ATTR VkResult VKAPI_CALL
vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface* pVersionStruct) {
    if (!pVersionStruct) return VK_ERROR_INITIALIZATION_FAILED;

    if (pVersionStruct->loaderLayerInterfaceVersion >= 2) {
        pVersionStruct->loaderLayerInterfaceVersion = 2;
        pVersionStruct->pfnGetInstanceProcAddr = vkGetInstanceProcAddr;
        pVersionStruct->pfnGetDeviceProcAddr = vkGetDeviceProcAddr;
        pVersionStruct->pfnGetPhysicalDeviceProcAddr = nullptr;
    }

    return VK_SUCCESS;
}

// ─────────────────────────────────────────────────────────────────────────────
//  vkEnumerate* exports
// ─────────────────────────────────────────────────────────────────────────────

LAYER_EXPORT VKAPI_ATTR VkResult VKAPI_CALL
vkEnumerateInstanceLayerProperties(uint32_t* pCount,
                                   VkLayerProperties* pProperties) {
    if (!pCount) return VK_ERROR_INITIALIZATION_FAILED;

    if (!pProperties) {
        *pCount = 1;
        return VK_SUCCESS;
    }

    if (*pCount < 1) {
        *pCount = 1;
        return VK_INCOMPLETE;
    }

    memset(&pProperties[0], 0, sizeof(VkLayerProperties));
    strncpy(pProperties[0].layerName, "VK_LAYER_PERFCACHE",
            VK_MAX_EXTENSION_NAME_SIZE - 1);
    strncpy(pProperties[0].description,
            "Pipeline cache / texture LRU / wave selector / sanitizer",
            VK_MAX_DESCRIPTION_SIZE - 1);

    pProperties[0].specVersion = VK_API_VERSION_1_3;
    pProperties[0].implementationVersion = 116;

    *pCount = 1;
    return VK_SUCCESS;
}

LAYER_EXPORT VKAPI_ATTR VkResult VKAPI_CALL
vkEnumerateInstanceExtensionProperties(const char* /*pLayerName*/,
                                       uint32_t* pCount,
                                       VkExtensionProperties* /*pProperties*/) {
    if (!pCount) return VK_ERROR_INITIALIZATION_FAILED;
    *pCount = 0;
    return VK_SUCCESS;
}

LAYER_EXPORT VKAPI_ATTR VkResult VKAPI_CALL
vkEnumerateDeviceLayerProperties(VkPhysicalDevice /*physDev*/,
                                 uint32_t* pCount,
                                 VkLayerProperties* pProperties) {
    return vkEnumerateInstanceLayerProperties(pCount, pProperties);
}

LAYER_EXPORT VKAPI_ATTR VkResult VKAPI_CALL
vkEnumerateDeviceExtensionProperties(VkPhysicalDevice physDev,
                                     const char* pLayerName,
                                     uint32_t* pCount,
                                     VkExtensionProperties* pProperties) {
    if (!pCount) return VK_ERROR_INITIALIZATION_FAILED;

    if (pLayerName && !strcmp(pLayerName, "VK_LAYER_PERFCACHE")) {
        *pCount = 0;
        return VK_SUCCESS;
    }

    VkInstance instance = lookup_instance_for_physdev(physDev);

    if (instance != VK_NULL_HANDLE) {
        InstanceDispatch disp{};
        bool has_disp = get_instance_dispatch_copy(instance, disp);
        if (has_disp && disp.EnumerateDeviceExtensionProperties)
            return disp.EnumerateDeviceExtensionProperties(
                physDev, pLayerName, pCount, pProperties);
    }

    PFN_vkGetInstanceProcAddr gipa = get_loader_gipa();
    if (gipa) {
        auto fn = reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(
            gipa(VK_NULL_HANDLE, "vkEnumerateDeviceExtensionProperties"));
        if (fn) return fn(physDev, pLayerName, pCount, pProperties);
    }

    *pCount = 0;
    return VK_SUCCESS;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Loader chain helpers
// ─────────────────────────────────────────────────────────────────────────────

static VkLayerInstanceCreateInfo* get_instance_chain_info(
    const VkInstanceCreateInfo* pCreateInfo, VkLayerFunction func) {
    if (!pCreateInfo) return nullptr;

    auto* chain = reinterpret_cast<const VkLayerInstanceCreateInfo*>(pCreateInfo->pNext);
    while (chain) {
        if (chain->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO &&
            chain->function == func) {
            return const_cast<VkLayerInstanceCreateInfo*>(chain);
        }
        chain = reinterpret_cast<const VkLayerInstanceCreateInfo*>(chain->pNext);
    }

    return nullptr;
}

static VkLayerDeviceCreateInfo* get_device_chain_info(
    const VkDeviceCreateInfo* pCreateInfo, VkLayerFunction func) {
    if (!pCreateInfo) return nullptr;

    auto* chain = reinterpret_cast<const VkLayerDeviceCreateInfo*>(pCreateInfo->pNext);
    while (chain) {
        if (chain->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO &&
            chain->function == func) {
            return const_cast<VkLayerDeviceCreateInfo*>(chain);
        }
        chain = reinterpret_cast<const VkLayerDeviceCreateInfo*>(chain->pNext);
    }

    return nullptr;
}

// ─────────────────────────────────────────────────────────────────────────────
//  vkCreateInstance
// ─────────────────────────────────────────────────────────────────────────────

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateInstance(
    const VkInstanceCreateInfo* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkInstance* pInstance) {
    if (!pCreateInfo || !pInstance)
        return VK_ERROR_INITIALIZATION_FAILED;

    VkLayerInstanceCreateInfo* chain_info =
        get_instance_chain_info(pCreateInfo, VK_LAYER_LINK_INFO);

    PFN_vkGetInstanceProcAddr next_gipa = nullptr;

    if (chain_info && chain_info->u.pLayerInfo) {
        next_gipa = chain_info->u.pLayerInfo->pfnNextGetInstanceProcAddr;
        chain_info->u.pLayerInfo = chain_info->u.pLayerInfo->pNext;
    } else {
        next_gipa = get_loader_gipa();
    }

    if (!next_gipa) return VK_ERROR_INITIALIZATION_FAILED;

    detect_conflicting_layers(pCreateInfo);

    auto next_CreateInstance = reinterpret_cast<PFN_vkCreateInstance>(
        next_gipa(VK_NULL_HANDLE, "vkCreateInstance"));
    if (!next_CreateInstance) return VK_ERROR_INITIALIZATION_FAILED;

    VkResult result = next_CreateInstance(pCreateInfo, pAllocator, pInstance);
    if (result != VK_SUCCESS) return result;

    InstanceDispatch disp{};
    disp.GetInstanceProcAddr = next_gipa;

#define LOAD_INST(fn) \
    disp.fn = reinterpret_cast<PFN_vk##fn>(next_gipa(*pInstance, "vk" #fn))

    LOAD_INST(DestroyInstance);
    LOAD_INST(EnumeratePhysicalDevices);
    LOAD_INST(EnumerateDeviceExtensionProperties);
    LOAD_INST(GetPhysicalDeviceProperties);
    LOAD_INST(GetPhysicalDeviceProperties2);

#undef LOAD_INST

    {
        std::lock_guard<std::mutex> lk(g_instance_lock);
        g_instance_dispatch[dispatch_key(*pInstance)] = disp;
    }

    LOGI("CreateInstance: layer attached (instance=%p)", (void*)*pInstance);
    return VK_SUCCESS;
}

// ─────────────────────────────────────────────────────────────────────────────
//  vkEnumeratePhysicalDevices
// ─────────────────────────────────────────────────────────────────────────────

static VKAPI_ATTR VkResult VKAPI_CALL layer_EnumeratePhysicalDevices(
    VkInstance instance,
    uint32_t* pPhysicalDeviceCount,
    VkPhysicalDevice* pPhysicalDevices) {
    if (!pPhysicalDeviceCount) return VK_ERROR_INITIALIZATION_FAILED;

    InstanceDispatch disp{};
    bool has_disp = get_instance_dispatch_copy(instance, disp);
    if (!has_disp || !disp.EnumeratePhysicalDevices)
        return VK_ERROR_INITIALIZATION_FAILED;

    VkResult r = disp.EnumeratePhysicalDevices(
        instance, pPhysicalDeviceCount, pPhysicalDevices);

    if ((r == VK_SUCCESS || r == VK_INCOMPLETE) &&
        pPhysicalDevices && pPhysicalDeviceCount) {
        std::lock_guard<std::mutex> lk(g_physdev_lock);
        for (uint32_t i = 0; i < *pPhysicalDeviceCount; ++i) {
            if (pPhysicalDevices[i] != VK_NULL_HANDLE)
                g_physdev_instance[pPhysicalDevices[i]] = instance;
        }
    }

    return r;
}

// ─────────────────────────────────────────────────────────────────────────────
//  vkDestroyInstance
// ─────────────────────────────────────────────────────────────────────────────

static VKAPI_ATTR void VKAPI_CALL layer_DestroyInstance(
    VkInstance instance,
    const VkAllocationCallbacks* pAllocator) {
    void* key = dispatch_key(instance);

    InstanceDispatch disp{};
    {
        std::lock_guard<std::mutex> lk(g_instance_lock);
        auto it = g_instance_dispatch.find(key);
        if (it != g_instance_dispatch.end()) {
            disp = it->second;
            g_instance_dispatch.erase(it);
        }
    }

    {
        std::lock_guard<std::mutex> lk(g_physdev_lock);
        for (auto it = g_physdev_instance.begin(); it != g_physdev_instance.end();) {
            if (it->second == instance) it = g_physdev_instance.erase(it);
            else ++it;
        }
    }

    if (disp.DestroyInstance)
        disp.DestroyInstance(instance, pAllocator);
}

// ─────────────────────────────────────────────────────────────────────────────
//  vkCreateDevice
// ─────────────────────────────────────────────────────────────────────────────

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateDevice(
    VkPhysicalDevice physDev,
    const VkDeviceCreateInfo* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkDevice* pDevice) {
    if (!pCreateInfo || !pDevice)
        return VK_ERROR_INITIALIZATION_FAILED;

    VkLayerDeviceCreateInfo* chain_info =
        get_device_chain_info(pCreateInfo, VK_LAYER_LINK_INFO);

    PFN_vkGetInstanceProcAddr next_gipa = nullptr;
    PFN_vkGetDeviceProcAddr next_gdpa = nullptr;

    if (chain_info && chain_info->u.pLayerInfo) {
        next_gipa = chain_info->u.pLayerInfo->pfnNextGetInstanceProcAddr;
        next_gdpa = chain_info->u.pLayerInfo->pfnNextGetDeviceProcAddr;
        chain_info->u.pLayerInfo = chain_info->u.pLayerInfo->pNext;
    } else {
        next_gipa = get_loader_gipa();
        if (next_gipa) {
            next_gdpa = reinterpret_cast<PFN_vkGetDeviceProcAddr>(
                next_gipa(VK_NULL_HANDLE, "vkGetDeviceProcAddr"));
        }
    }

    if (!next_gipa || !next_gdpa)
        return VK_ERROR_INITIALIZATION_FAILED;

    VkInstance parent_instance = lookup_instance_for_physdev(physDev);

    auto next_CreateDevice = reinterpret_cast<PFN_vkCreateDevice>(
        next_gipa(parent_instance, "vkCreateDevice"));
    if (!next_CreateDevice) {
        next_CreateDevice = reinterpret_cast<PFN_vkCreateDevice>(
            next_gipa(VK_NULL_HANDLE, "vkCreateDevice"));
    }
    if (!next_CreateDevice) return VK_ERROR_INITIALIZATION_FAILED;

    VkResult result = next_CreateDevice(physDev, pCreateInfo, pAllocator, pDevice);
    if (result != VK_SUCCESS) return result;

    DeviceDispatch disp{};
    disp.GetDeviceProcAddr = next_gdpa;

#define LOAD_DEV(fn) \
    disp.fn = reinterpret_cast<PFN_vk##fn>(next_gdpa(*pDevice, "vk" #fn))

    LOAD_DEV(DestroyDevice);

    LOAD_DEV(CreatePipelineCache);
    LOAD_DEV(DestroyPipelineCache);
    LOAD_DEV(GetPipelineCacheData);

    LOAD_DEV(CreateGraphicsPipelines);
    LOAD_DEV(CreateComputePipelines);
    LOAD_DEV(DestroyPipeline);

    LOAD_DEV(CmdCopyBufferToImage);
    LOAD_DEV(CreateBuffer);
    LOAD_DEV(DestroyBuffer);
    LOAD_DEV(BindBufferMemory);
    LOAD_DEV(MapMemory);
    LOAD_DEV(UnmapMemory);
    LOAD_DEV(GetBufferMemoryRequirements);
    LOAD_DEV(GetImageMemoryRequirements);

    disp.CmdCopyBufferToImage2KHR =
        reinterpret_cast<PFN_vkCmdCopyBufferToImage2KHR>(
            next_gdpa(*pDevice, "vkCmdCopyBufferToImage2KHR"));
    disp.CmdCopyBufferToImage2 =
        reinterpret_cast<PFN_vkCmdCopyBufferToImage2>(
            next_gdpa(*pDevice, "vkCmdCopyBufferToImage2"));

    disp.BindBufferMemory2 =
        reinterpret_cast<PFN_vkBindBufferMemory2>(
            next_gdpa(*pDevice, "vkBindBufferMemory2"));
    disp.BindBufferMemory2KHR =
        reinterpret_cast<PFN_vkBindBufferMemory2KHR>(
            next_gdpa(*pDevice, "vkBindBufferMemory2KHR"));

    LOAD_DEV(CreateRenderPass);
    LOAD_DEV(DestroyRenderPass);
    LOAD_DEV(CreateRenderPass2);

    disp.CreateRenderPass2KHR =
        reinterpret_cast<PFN_vkCreateRenderPass2KHR>(
            next_gdpa(*pDevice, "vkCreateRenderPass2KHR"));

    LOAD_DEV(CreateFramebuffer);
    LOAD_DEV(DestroyFramebuffer);

#undef LOAD_DEV

    {
        std::lock_guard<std::mutex> lk(g_device_lock);
        g_device_dispatch[dispatch_key(*pDevice)] = disp;
    }

    VkPhysicalDeviceProperties props{};
    {
        auto get_props = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(
            next_gipa(parent_instance, "vkGetPhysicalDeviceProperties"));
        if (!get_props) {
            get_props = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(
                next_gipa(VK_NULL_HANDLE, "vkGetPhysicalDeviceProperties"));
        }
        if (get_props)
            get_props(physDev, &props);
    }

    perfcache_detect_device(physDev, props);

    if (perfcache_is_xclipse()) {
        VkPhysicalDeviceSubgroupSizeControlProperties sc_props{};
        sc_props.sType =
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_SIZE_CONTROL_PROPERTIES;

        VkPhysicalDeviceProperties2 props2{};
        props2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
        props2.pNext = &sc_props;

        auto get_props2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
            next_gipa(parent_instance, "vkGetPhysicalDeviceProperties2"));
        if (!get_props2) {
            get_props2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
                next_gipa(VK_NULL_HANDLE, "vkGetPhysicalDeviceProperties2"));
        }

        if (get_props2) {
            get_props2(physDev, &props2);
            std::lock_guard<std::mutex> lk(g_subgroup_lock);
            g_subgroup_props[*pDevice] = sc_props;
        }
    }

    perfcache_settings_init();

    if (g_settings.disable) {
        LOGI("CreateDevice: PERFCACHE_DISABLE=1, layer is pass-through");
        return VK_SUCCESS;
    }

    texture_cache_set_driver_identity(props);

    pipeline_control_on_device_created(props);

    if (!g_conflict.has_pipeline_cache.load(std::memory_order_acquire)) {
        pipeline_cache_on_device_created(*pDevice, physDev, disp, props);
    } else {
        LOGI("CreateDevice: pipeline_cache disabled (conflict detected)");
    }

    LOGI("CreateDevice: layer fully initialised (device=%p, xclipse=%d gen=%u)",
         (void*)*pDevice, (int)perfcache_is_xclipse(), perfcache_xclipse_gen());

    return VK_SUCCESS;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Render-pass / framebuffer heuristics state
// ─────────────────────────────────────────────────────────────────────────────

struct RenderPassShape {
    VkDevice device = VK_NULL_HANDLE;
    uint32_t attachments = 0;
    uint32_t subpasses = 0;
    uint32_t dependencies = 0;
};

struct FramebufferShape {
    VkDevice device = VK_NULL_HANDLE;
    VkRenderPass render_pass = VK_NULL_HANDLE;
    uint32_t attachments = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t layers = 0;
};

static std::mutex s_rp_lock;
static std::unordered_map<VkRenderPass, RenderPassShape> s_renderpasses;
static std::unordered_map<VkFramebuffer, FramebufferShape> s_framebuffers;

static void renderpass_heuristics_clear_device(VkDevice device) {
    std::lock_guard<std::mutex> lk(s_rp_lock);

    for (auto it = s_renderpasses.begin(); it != s_renderpasses.end();) {
        if (it->second.device == device) it = s_renderpasses.erase(it);
        else ++it;
    }

    for (auto it = s_framebuffers.begin(); it != s_framebuffers.end();) {
        if (it->second.device == device) it = s_framebuffers.erase(it);
        else ++it;
    }
}

static bool renderpass_heuristics_prefers_raw_pipeline(VkDevice device, VkRenderPass rp) {
    if (!g_settings.renderpass_heuristics || rp == VK_NULL_HANDLE) return false;

    std::lock_guard<std::mutex> lk(s_rp_lock);
    auto it = s_renderpasses.find(rp);
    if (it == s_renderpasses.end()) return false;
    if (it->second.device != device) return false;

    const RenderPassShape& s = it->second;
    return s.subpasses <= 1 && s.attachments <= 2 && s.dependencies <= 1;
}

static bool renderpass_heuristics_wants_pipeline_warmup(VkDevice device, VkRenderPass rp) {
    if (!g_settings.renderpass_heuristics || rp == VK_NULL_HANDLE) return false;

    std::lock_guard<std::mutex> lk(s_rp_lock);
    auto it = s_renderpasses.find(rp);
    if (it == s_renderpasses.end()) return false;
    if (it->second.device != device) return false;

    const RenderPassShape& s = it->second;
    return s.subpasses > 1 || s.attachments > 2 || s.dependencies > 1;
}

// ─────────────────────────────────────────────────────────────────────────────
//  vkDestroyDevice
// ─────────────────────────────────────────────────────────────────────────────

static VKAPI_ATTR void VKAPI_CALL layer_DestroyDevice(
    VkDevice device,
    const VkAllocationCallbacks* pAllocator) {
    void* key = dispatch_key(device);

    DeviceDispatch disp{};
    {
        std::lock_guard<std::mutex> lk(g_device_lock);
        auto it = g_device_dispatch.find(key);
        if (it != g_device_dispatch.end()) {
            disp = it->second;
            g_device_dispatch.erase(it);
        }
    }

    if (!g_settings.disable) {
        pipeline_cache_on_device_destroyed(device, disp);
        perf_metrics_dump();

        if (g_settings.metrics_dump_file) {
            if (!perf_metrics_dump_to_file(g_settings.metrics_dump_path.c_str())) {
                LOGE("metrics: failed to write %s", g_settings.metrics_dump_path.c_str());
            }
        }
    }

    {
        std::lock_guard<std::mutex> lk(g_subgroup_lock);
        g_subgroup_props.erase(device);
    }

    if (!g_settings.disable && g_settings.renderpass_heuristics)
        renderpass_heuristics_clear_device(device);

    if (disp.DestroyDevice)
        disp.DestroyDevice(device, pAllocator);
}

// ─────────────────────────────────────────────────────────────────────────────
//  Time / warmup helpers
// ─────────────────────────────────────────────────────────────────────────────

static uint64_t now_us() {
    using namespace std::chrono;
    return (uint64_t)duration_cast<microseconds>(
        steady_clock::now().time_since_epoch()).count();
}

static void maybe_prewarm_graphics_pipeline(
    const DeviceDispatch& disp,
    VkDevice device,
    VkPipelineCache cache,
    const VkGraphicsPipelineCreateInfo& ci,
    const VkAllocationCallbacks* allocator,
    bool renderpass_hint) {
    if (!disp.CreateGraphicsPipelines || !disp.DestroyPipeline) return;
    if (g_settings.pipeline_warmup == WarmupMode::OFF ||
        !g_settings.pipeline_warmup_precreate) return;
    if (cache == VK_NULL_HANDLE) return;
    if (g_settings.pipeline_warmup == WarmupMode::LIGHT && !renderpass_hint) return;
    if (!pipeline_warmup_should_precreate_graphics(ci)) return;

    VkPipeline tmp = VK_NULL_HANDLE;
    uint64_t t0 = now_us();

    VkResult r = disp.CreateGraphicsPipelines(
        device, cache, 1, &ci, allocator, &tmp);

    uint64_t dt = now_us() - t0;

    if (r == VK_SUCCESS && tmp != VK_NULL_HANDLE) {
        disp.DestroyPipeline(device, tmp, allocator);
        pipeline_warmup_mark_graphics(ci);
        perf_metrics_inc_warmup_precreate_calls();
        perf_metrics_add_warmup_precreate_time_us(dt);

        LOGV("pipeline_warmup: precreated graphics signature=%016llx time=%lluus",
             (unsigned long long)pipeline_signature_graphics(ci),
             (unsigned long long)dt);
    }
}

static void maybe_prewarm_compute_pipeline(
    const DeviceDispatch& disp,
    VkDevice device,
    VkPipelineCache cache,
    const VkComputePipelineCreateInfo& ci,
    const VkAllocationCallbacks* allocator) {
    if (!disp.CreateComputePipelines || !disp.DestroyPipeline) return;
    if (g_settings.pipeline_warmup == WarmupMode::OFF ||
        !g_settings.pipeline_warmup_precreate) return;
    if (cache == VK_NULL_HANDLE) return;
    if (!pipeline_warmup_should_precreate_compute(ci)) return;

    VkPipeline tmp = VK_NULL_HANDLE;
    uint64_t t0 = now_us();

    VkResult r = disp.CreateComputePipelines(
        device, cache, 1, &ci, allocator, &tmp);

    uint64_t dt = now_us() - t0;

    if (r == VK_SUCCESS && tmp != VK_NULL_HANDLE) {
        disp.DestroyPipeline(device, tmp, allocator);
        pipeline_warmup_mark_compute(ci);
        perf_metrics_inc_warmup_precreate_calls();
        perf_metrics_add_warmup_precreate_time_us(dt);

        LOGV("pipeline_warmup: precreated compute signature=%016llx time=%lluus",
             (unsigned long long)pipeline_signature_compute(ci),
             (unsigned long long)dt);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  vkCreateGraphicsPipelines
// ─────────────────────────────────────────────────────────────────────────────

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateGraphicsPipelines(
    VkDevice device,
    VkPipelineCache pipelineCache,
    uint32_t createInfoCount,
    const VkGraphicsPipelineCreateInfo* pCreateInfos,
    const VkAllocationCallbacks* pAllocator,
    VkPipeline* pPipelines) {
    DeviceDispatch disp{};
    bool has_disp = get_device_dispatch_copy(device, disp);
    if (!has_disp) return VK_ERROR_DEVICE_LOST;
    if (!disp.CreateGraphicsPipelines) return VK_ERROR_INITIALIZATION_FAILED;
    if (createInfoCount > 0 && (!pCreateInfos || !pPipelines))
        return VK_ERROR_INITIALIZATION_FAILED;

    perf_metrics_inc_graphics_pipeline_calls(createInfoCount);

    if (g_settings.disable) {
        return disp.CreateGraphicsPipelines(
            device, pipelineCache, createInfoCount,
            pCreateInfos, pAllocator, pPipelines);
    }

    bool has_blacklisted = false;
    if (g_settings.pipeline_blacklist) {
        for (uint32_t i = 0; i < createInfoCount; ++i) {
            if (pipeline_blacklist_should_skip_graphics(pCreateInfos[i])) {
                has_blacklisted = true;
                perf_metrics_inc_blacklisted_pipeline_hits();
                break;
            }
        }
    }

    if (has_blacklisted) {
        return disp.CreateGraphicsPipelines(
            device, pipelineCache, createInfoCount,
            pCreateInfos, pAllocator, pPipelines);
    }

    VkPipelineCache effective_cache = pipelineCache;
    if (effective_cache == VK_NULL_HANDLE && !g_conflict.has_pipeline_cache.load(std::memory_order_acquire)) {
        effective_cache = pipeline_cache_get(device);
        if (effective_cache != VK_NULL_HANDLE &&
            g_settings.pipeline_warmup != WarmupMode::OFF) {
            perf_metrics_inc_warmup_cache_touches(createInfoCount);
        }
    }

    bool rp_prefers_raw = false;
    if (g_settings.renderpass_heuristics) {
        for (uint32_t i = 0; i < createInfoCount; ++i) {
            if (renderpass_heuristics_prefers_raw_pipeline(
                    device, pCreateInfos[i].renderPass)) {
                rp_prefers_raw = true;
                break;
            }
        }
    }

    if (rp_prefers_raw)
        perf_metrics_inc_sanitizer_skips_by_renderpass();

    bool rp_wants_warmup = false;
    if (g_settings.renderpass_heuristics) {
        for (uint32_t i = 0; i < createInfoCount; ++i) {
            if (renderpass_heuristics_wants_pipeline_warmup(
                    device, pCreateInfos[i].renderPass)) {
                rp_wants_warmup = true;
                break;
            }
        }
    }

    if (g_settings.sanitize_pipelines && !g_conflict.has_sanitizer.load(std::memory_order_acquire) && !rp_prefers_raw) {
        VkPhysicalDeviceSubgroupSizeControlProperties sc_props{};
        sc_props.sType =
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_SIZE_CONTROL_PROPERTIES;

        {
            std::lock_guard<std::mutex> lk(g_subgroup_lock);
            auto it = g_subgroup_props.find(device);
            if (it != g_subgroup_props.end())
                sc_props = it->second;
        }

        VkPhysicalDeviceProperties2 props2{};
        props2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
        props2.pNext = &sc_props;

        PipelineSanitizerContext ctx;
        ctx.prepare(createInfoCount, pCreateInfos, &props2);

        if (effective_cache != VK_NULL_HANDLE) {
            for (uint32_t i = 0; i < createInfoCount; ++i) {
                maybe_prewarm_graphics_pipeline(
                    disp, device, effective_cache, ctx.patched[i],
                    pAllocator, rp_wants_warmup);
            }
        }

        uint64_t t0 = now_us();

        VkResult r = disp.CreateGraphicsPipelines(
            device, effective_cache, createInfoCount,
            ctx.patched.data(), pAllocator, pPipelines);

        perf_metrics_add_graphics_pipeline_create_time_us(now_us() - t0);

        if (r != VK_SUCCESS && g_settings.pipeline_blacklist) {
            VkResult fallback = disp.CreateGraphicsPipelines(
                device, pipelineCache, createInfoCount,
                pCreateInfos, pAllocator, pPipelines);

            if (fallback == VK_SUCCESS) {
                for (uint32_t i = 0; i < createInfoCount; ++i)
                    pipeline_blacklist_mark_graphics(pCreateInfos[i]);

                perf_metrics_inc_graphics_pipeline_fallbacks();
                LOGI("pipeline_control: graphics fallback succeeded, signatures blacklisted");
                return fallback;
            }
        }

        return r;
    }

    const bool raw_prewarm_is_safe =
        !perfcache_is_xclipse() || !g_settings.sanitize_pipelines;

    if (effective_cache != VK_NULL_HANDLE && raw_prewarm_is_safe) {
        for (uint32_t i = 0; i < createInfoCount; ++i) {
            maybe_prewarm_graphics_pipeline(
                disp, device, effective_cache, pCreateInfos[i],
                pAllocator, rp_wants_warmup);
        }
    }

    uint64_t t0 = now_us();

    VkResult r = disp.CreateGraphicsPipelines(
        device, effective_cache, createInfoCount,
        pCreateInfos, pAllocator, pPipelines);

    perf_metrics_add_graphics_pipeline_create_time_us(now_us() - t0);

    if (r != VK_SUCCESS && effective_cache != pipelineCache &&
        g_settings.pipeline_blacklist) {
        VkResult fallback = disp.CreateGraphicsPipelines(
            device, pipelineCache, createInfoCount,
            pCreateInfos, pAllocator, pPipelines);

        if (fallback == VK_SUCCESS) {
            for (uint32_t i = 0; i < createInfoCount; ++i)
                pipeline_blacklist_mark_graphics(pCreateInfos[i]);

            perf_metrics_inc_graphics_pipeline_fallbacks();
            LOGI("pipeline_control: graphics cache fallback succeeded, signatures blacklisted");
            return fallback;
        }
    }

    return r;
}

// ─────────────────────────────────────────────────────────────────────────────
//  vkCreateComputePipelines
// ─────────────────────────────────────────────────────────────────────────────

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateComputePipelines(
    VkDevice device,
    VkPipelineCache pipelineCache,
    uint32_t createInfoCount,
    const VkComputePipelineCreateInfo* pCreateInfos,
    const VkAllocationCallbacks* pAllocator,
    VkPipeline* pPipelines) {
    DeviceDispatch disp{};
    bool has_disp = get_device_dispatch_copy(device, disp);
    if (!has_disp) return VK_ERROR_DEVICE_LOST;
    if (!disp.CreateComputePipelines) return VK_ERROR_INITIALIZATION_FAILED;
    if (createInfoCount > 0 && (!pCreateInfos || !pPipelines))
        return VK_ERROR_INITIALIZATION_FAILED;

    perf_metrics_inc_compute_pipeline_calls(createInfoCount);

    if (g_settings.disable) {
        return disp.CreateComputePipelines(
            device, pipelineCache, createInfoCount,
            pCreateInfos, pAllocator, pPipelines);
    }

    bool has_blacklisted = false;
    if (g_settings.pipeline_blacklist) {
        for (uint32_t i = 0; i < createInfoCount; ++i) {
            if (pipeline_blacklist_should_skip_compute(pCreateInfos[i])) {
                has_blacklisted = true;
                perf_metrics_inc_blacklisted_pipeline_hits();
                break;
            }
        }
    }

    if (has_blacklisted) {
        return disp.CreateComputePipelines(
            device, pipelineCache, createInfoCount,
            pCreateInfos, pAllocator, pPipelines);
    }

    VkPipelineCache effective_cache = pipelineCache;
    if (effective_cache == VK_NULL_HANDLE && !g_conflict.has_pipeline_cache.load(std::memory_order_acquire)) {
        effective_cache = pipeline_cache_get(device);
        if (effective_cache != VK_NULL_HANDLE &&
            g_settings.pipeline_warmup != WarmupMode::OFF) {
            perf_metrics_inc_warmup_cache_touches(createInfoCount);
        }
    }

    if (effective_cache != VK_NULL_HANDLE) {
        for (uint32_t i = 0; i < createInfoCount; ++i) {
            maybe_prewarm_compute_pipeline(
                disp, device, effective_cache, pCreateInfos[i], pAllocator);
        }
    }

    uint64_t t0 = now_us();

    VkResult r = disp.CreateComputePipelines(
        device, effective_cache, createInfoCount,
        pCreateInfos, pAllocator, pPipelines);

    perf_metrics_add_compute_pipeline_create_time_us(now_us() - t0);

    if (r != VK_SUCCESS && effective_cache != pipelineCache &&
        g_settings.pipeline_blacklist) {
        VkResult fallback = disp.CreateComputePipelines(
            device, pipelineCache, createInfoCount,
            pCreateInfos, pAllocator, pPipelines);

        if (fallback == VK_SUCCESS) {
            for (uint32_t i = 0; i < createInfoCount; ++i)
                pipeline_blacklist_mark_compute(pCreateInfos[i]);

            perf_metrics_inc_compute_pipeline_fallbacks();
            LOGI("pipeline_control: compute cache fallback succeeded, signatures blacklisted");
            return fallback;
        }
    }

    return r;
}

// ─────────────────────────────────────────────────────────────────────────────
//  vkMapMemory / vkUnmapMemory
// ─────────────────────────────────────────────────────────────────────────────

static VKAPI_ATTR VkResult VKAPI_CALL layer_MapMemory(
    VkDevice device,
    VkDeviceMemory memory,
    VkDeviceSize offset,
    VkDeviceSize size,
    VkMemoryMapFlags flags,
    void** ppData) {
    DeviceDispatch disp{};
    bool has_disp = get_device_dispatch_copy(device, disp);
    if (!has_disp) return VK_ERROR_DEVICE_LOST;
    if (!disp.MapMemory) return VK_ERROR_INITIALIZATION_FAILED;

    VkResult r = disp.MapMemory(device, memory, offset, size, flags, ppData);

    if (r == VK_SUCCESS && !g_settings.disable && ppData)
        texture_cache_on_map_memory(memory, offset, size, *ppData);

    return r;
}

static VKAPI_ATTR void VKAPI_CALL layer_UnmapMemory(
    VkDevice device,
    VkDeviceMemory memory) {
    DeviceDispatch disp{};
    bool has_disp = get_device_dispatch_copy(device, disp);
    if (!has_disp || !disp.UnmapMemory) return;

    if (!g_settings.disable)
        texture_cache_on_unmap_memory(memory);

    disp.UnmapMemory(device, memory);
}

// ─────────────────────────────────────────────────────────────────────────────
//  Buffer tracking
// ─────────────────────────────────────────────────────────────────────────────

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateBuffer(
    VkDevice device,
    const VkBufferCreateInfo* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkBuffer* pBuffer) {
    DeviceDispatch disp{};
    bool has_disp = get_device_dispatch_copy(device, disp);
    if (!has_disp || !disp.CreateBuffer) return VK_ERROR_DEVICE_LOST;

    VkResult r = disp.CreateBuffer(device, pCreateInfo, pAllocator, pBuffer);

    if (r == VK_SUCCESS && !g_settings.disable && pCreateInfo && pBuffer)
        texture_cache_on_create_buffer(*pBuffer, pCreateInfo->size);

    return r;
}

static VKAPI_ATTR void VKAPI_CALL layer_DestroyBuffer(
    VkDevice device,
    VkBuffer buffer,
    const VkAllocationCallbacks* pAllocator) {
    DeviceDispatch disp{};
    bool has_disp = get_device_dispatch_copy(device, disp);

    if (!g_settings.disable)
        texture_cache_on_destroy_buffer(buffer);

    if (has_disp && disp.DestroyBuffer)
        disp.DestroyBuffer(device, buffer, pAllocator);
}

static VKAPI_ATTR VkResult VKAPI_CALL layer_BindBufferMemory(
    VkDevice device,
    VkBuffer buffer,
    VkDeviceMemory memory,
    VkDeviceSize memoryOffset) {
    DeviceDispatch disp{};
    bool has_disp = get_device_dispatch_copy(device, disp);
    if (!has_disp || !disp.BindBufferMemory) return VK_ERROR_DEVICE_LOST;

    VkResult r = disp.BindBufferMemory(device, buffer, memory, memoryOffset);

    if (r == VK_SUCCESS && !g_settings.disable)
        texture_cache_on_bind_buffer_memory(buffer, memory, memoryOffset);

    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL layer_BindBufferMemory2(
    VkDevice device,
    uint32_t bindInfoCount,
    const VkBindBufferMemoryInfo* pBindInfos) {
    DeviceDispatch disp{};
    bool has_disp = get_device_dispatch_copy(device, disp);
    if (!has_disp || !disp.BindBufferMemory2)
        return VK_ERROR_EXTENSION_NOT_PRESENT;

    VkResult r = disp.BindBufferMemory2(device, bindInfoCount, pBindInfos);

    if (r == VK_SUCCESS && !g_settings.disable && pBindInfos) {
        for (uint32_t i = 0; i < bindInfoCount; ++i) {
            texture_cache_on_bind_buffer_memory(
                pBindInfos[i].buffer,
                pBindInfos[i].memory,
                pBindInfos[i].memoryOffset);
        }
    }

    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL layer_BindBufferMemory2KHR(
    VkDevice device,
    uint32_t bindInfoCount,
    const VkBindBufferMemoryInfo* pBindInfos) {
    DeviceDispatch disp{};
    bool has_disp = get_device_dispatch_copy(device, disp);
    if (!has_disp || !disp.BindBufferMemory2KHR)
        return VK_ERROR_EXTENSION_NOT_PRESENT;

    VkResult r = disp.BindBufferMemory2KHR(device, bindInfoCount, pBindInfos);

    if (r == VK_SUCCESS && !g_settings.disable && pBindInfos) {
        for (uint32_t i = 0; i < bindInfoCount; ++i) {
            texture_cache_on_bind_buffer_memory(
                pBindInfos[i].buffer,
                pBindInfos[i].memory,
                pBindInfos[i].memoryOffset);
        }
    }

    return r;
}

// ─────────────────────────────────────────────────────────────────────────────
//  vkCmdCopyBufferToImage*
// ─────────────────────────────────────────────────────────────────────────────

static VKAPI_ATTR void VKAPI_CALL layer_CmdCopyBufferToImage(
    VkCommandBuffer commandBuffer,
    VkBuffer srcBuffer,
    VkImage dstImage,
    VkImageLayout dstImageLayout,
    uint32_t regionCount,
    const VkBufferImageCopy* pRegions) {
    DeviceDispatch disp{};
    bool has_disp = get_device_dispatch_copy(commandBuffer, disp);
    if (!has_disp) return;

    if (!g_settings.disable && !g_conflict.has_texture_intercept.load(std::memory_order_acquire)) {
        texture_cache_cmd_copy_buffer_to_image(
            commandBuffer, srcBuffer, dstImage, dstImageLayout,
            regionCount, pRegions, disp);
    }

    if (disp.CmdCopyBufferToImage) {
        disp.CmdCopyBufferToImage(
            commandBuffer, srcBuffer, dstImage, dstImageLayout,
            regionCount, pRegions);
    } else {
        LOGE("CmdCopyBufferToImage: no dispatch available");
    }
}

static VKAPI_ATTR void VKAPI_CALL layer_CmdCopyBufferToImage2(
    VkCommandBuffer commandBuffer,
    const VkCopyBufferToImageInfo2* pCopyBufferToImageInfo) {
    DeviceDispatch disp{};
    bool has_disp = get_device_dispatch_copy(commandBuffer, disp);
    if (!has_disp) return;

    if (!g_settings.disable &&
        !g_conflict.has_texture_intercept.load(std::memory_order_acquire) &&
        pCopyBufferToImageInfo) {
        texture_cache_cmd_copy_buffer_to_image2(
            commandBuffer, pCopyBufferToImageInfo, disp);
    }

    if (disp.CmdCopyBufferToImage2) {
        disp.CmdCopyBufferToImage2(commandBuffer, pCopyBufferToImageInfo);
    } else if (disp.CmdCopyBufferToImage2KHR) {
        disp.CmdCopyBufferToImage2KHR(commandBuffer, pCopyBufferToImageInfo);
    } else {
        LOGE("CmdCopyBufferToImage2: no core/KHR dispatch available");
    }
}

static VKAPI_ATTR void VKAPI_CALL layer_CmdCopyBufferToImage2KHR(
    VkCommandBuffer commandBuffer,
    const VkCopyBufferToImageInfo2* pCopyBufferToImageInfo) {
    DeviceDispatch disp{};
    bool has_disp = get_device_dispatch_copy(commandBuffer, disp);
    if (!has_disp) return;

    if (!g_settings.disable &&
        !g_conflict.has_texture_intercept.load(std::memory_order_acquire) &&
        pCopyBufferToImageInfo) {
        texture_cache_cmd_copy_buffer_to_image2_khr(
            commandBuffer, pCopyBufferToImageInfo, disp);
    }

    if (disp.CmdCopyBufferToImage2KHR) {
        disp.CmdCopyBufferToImage2KHR(commandBuffer, pCopyBufferToImageInfo);
    } else if (disp.CmdCopyBufferToImage2) {
        disp.CmdCopyBufferToImage2(commandBuffer, pCopyBufferToImageInfo);
    } else {
        LOGE("CmdCopyBufferToImage2KHR: no KHR/core dispatch available");
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Render-pass / framebuffer hooks
// ─────────────────────────────────────────────────────────────────────────────

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateRenderPass(
    VkDevice device,
    const VkRenderPassCreateInfo* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkRenderPass* pRenderPass) {
    DeviceDispatch disp{};
    bool has_disp = get_device_dispatch_copy(device, disp);
    if (!has_disp || !disp.CreateRenderPass) return VK_ERROR_DEVICE_LOST;

    VkResult r = disp.CreateRenderPass(device, pCreateInfo, pAllocator, pRenderPass);

    if (r == VK_SUCCESS && !g_settings.disable &&
        g_settings.renderpass_heuristics && pCreateInfo && pRenderPass) {
        std::lock_guard<std::mutex> lk(s_rp_lock);
        s_renderpasses[*pRenderPass] = {
            device,
            pCreateInfo->attachmentCount,
            pCreateInfo->subpassCount,
            pCreateInfo->dependencyCount
        };

        perf_metrics_inc_renderpass_shapes();

        LOGV("renderpass_heuristics: rp=%p attachments=%u subpasses=%u deps=%u",
             (void*)*pRenderPass,
             pCreateInfo->attachmentCount,
             pCreateInfo->subpassCount,
             pCreateInfo->dependencyCount);
    }

    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateRenderPass2(
    VkDevice device,
    const VkRenderPassCreateInfo2* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkRenderPass* pRenderPass) {
    DeviceDispatch disp{};
    bool has_disp = get_device_dispatch_copy(device, disp);
    if (!has_disp) return VK_ERROR_DEVICE_LOST;

    PFN_vkCreateRenderPass2 fn = disp.CreateRenderPass2;
    if (!fn && disp.CreateRenderPass2KHR) {
        fn = reinterpret_cast<PFN_vkCreateRenderPass2>(disp.CreateRenderPass2KHR);
    }

    if (!fn) return VK_ERROR_EXTENSION_NOT_PRESENT;

    VkResult r = fn(device, pCreateInfo, pAllocator, pRenderPass);

    if (r == VK_SUCCESS && !g_settings.disable &&
        g_settings.renderpass_heuristics && pCreateInfo && pRenderPass) {
        std::lock_guard<std::mutex> lk(s_rp_lock);
        s_renderpasses[*pRenderPass] = {
            device,
            pCreateInfo->attachmentCount,
            pCreateInfo->subpassCount,
            pCreateInfo->dependencyCount
        };

        perf_metrics_inc_renderpass_shapes();

        LOGV("renderpass_heuristics: rp2=%p attachments=%u subpasses=%u deps=%u",
             (void*)*pRenderPass,
             pCreateInfo->attachmentCount,
             pCreateInfo->subpassCount,
             pCreateInfo->dependencyCount);
    }

    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateRenderPass2KHR(
    VkDevice device,
    const VkRenderPassCreateInfo2* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkRenderPass* pRenderPass) {
    DeviceDispatch disp{};
    bool has_disp = get_device_dispatch_copy(device, disp);
    if (!has_disp) return VK_ERROR_DEVICE_LOST;

    PFN_vkCreateRenderPass2KHR fn = disp.CreateRenderPass2KHR;
    if (!fn && disp.CreateRenderPass2) {
        fn = reinterpret_cast<PFN_vkCreateRenderPass2KHR>(disp.CreateRenderPass2);
    }

    if (!fn) return VK_ERROR_EXTENSION_NOT_PRESENT;

    VkResult r = fn(device, pCreateInfo, pAllocator, pRenderPass);

    if (r == VK_SUCCESS && !g_settings.disable &&
        g_settings.renderpass_heuristics && pCreateInfo && pRenderPass) {
        std::lock_guard<std::mutex> lk(s_rp_lock);
        s_renderpasses[*pRenderPass] = {
            device,
            pCreateInfo->attachmentCount,
            pCreateInfo->subpassCount,
            pCreateInfo->dependencyCount
        };

        perf_metrics_inc_renderpass_shapes();

        LOGV("renderpass_heuristics: rp2khr=%p attachments=%u subpasses=%u deps=%u",
             (void*)*pRenderPass,
             pCreateInfo->attachmentCount,
             pCreateInfo->subpassCount,
             pCreateInfo->dependencyCount);
    }

    return r;
}

static VKAPI_ATTR void VKAPI_CALL layer_DestroyRenderPass(
    VkDevice device,
    VkRenderPass renderPass,
    const VkAllocationCallbacks* pAllocator) {
    DeviceDispatch disp{};
    bool has_disp = get_device_dispatch_copy(device, disp);

    if (!g_settings.disable && g_settings.renderpass_heuristics) {
        std::lock_guard<std::mutex> lk(s_rp_lock);
        auto it = s_renderpasses.find(renderPass);
        if (it != s_renderpasses.end() && it->second.device == device)
            s_renderpasses.erase(it);
    }

    if (has_disp && disp.DestroyRenderPass)
        disp.DestroyRenderPass(device, renderPass, pAllocator);
}

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateFramebuffer(
    VkDevice device,
    const VkFramebufferCreateInfo* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkFramebuffer* pFramebuffer) {
    DeviceDispatch disp{};
    bool has_disp = get_device_dispatch_copy(device, disp);
    if (!has_disp || !disp.CreateFramebuffer) return VK_ERROR_DEVICE_LOST;

    VkResult r = disp.CreateFramebuffer(device, pCreateInfo, pAllocator, pFramebuffer);

    if (r == VK_SUCCESS && !g_settings.disable &&
        g_settings.renderpass_heuristics && pCreateInfo && pFramebuffer) {
        std::lock_guard<std::mutex> lk(s_rp_lock);
        s_framebuffers[*pFramebuffer] = {
            device,
            pCreateInfo->renderPass,
            pCreateInfo->attachmentCount,
            pCreateInfo->width,
            pCreateInfo->height,
            pCreateInfo->layers
        };

        perf_metrics_inc_framebuffer_shapes();

        LOGV("renderpass_heuristics: fb=%p rp=%p %ux%u layers=%u att=%u",
             (void*)*pFramebuffer,
             (void*)pCreateInfo->renderPass,
             pCreateInfo->width,
             pCreateInfo->height,
             pCreateInfo->layers,
             pCreateInfo->attachmentCount);
    }

    return r;
}

static VKAPI_ATTR void VKAPI_CALL layer_DestroyFramebuffer(
    VkDevice device,
    VkFramebuffer framebuffer,
    const VkAllocationCallbacks* pAllocator) {
    DeviceDispatch disp{};
    bool has_disp = get_device_dispatch_copy(device, disp);

    if (!g_settings.disable && g_settings.renderpass_heuristics) {
        std::lock_guard<std::mutex> lk(s_rp_lock);
        auto it = s_framebuffers.find(framebuffer);
        if (it != s_framebuffers.end() && it->second.device == device)
            s_framebuffers.erase(it);
    }

    if (has_disp && disp.DestroyFramebuffer)
        disp.DestroyFramebuffer(device, framebuffer, pAllocator);
}