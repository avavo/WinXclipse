#pragma once
#include <vulkan/vulkan.h>

// ─────────────────────────────────────────────────────────────────────────────
//  dispatch_table.h
//  Per-instance and per-device function pointer tables.
//
//  The Vulkan loader identifies dispatchable objects by their first word
//  (the dispatch key), which is a pointer to the driver's vtable.  We store
//  our own tables keyed on that same pointer so that multiple VkInstance /
//  VkDevice objects can coexist correctly.
// ─────────────────────────────────────────────────────────────────────────────

// Canonical "dispatch key" helper used by the Vulkan loader convention.
static inline void* dispatch_key(const void* handle) {
    return *reinterpret_cast<void* const*>(handle);
}

struct InstanceDispatch {
    // Core instance functions we need
    PFN_vkGetInstanceProcAddr        GetInstanceProcAddr        = nullptr;
    PFN_vkDestroyInstance            DestroyInstance            = nullptr;
    PFN_vkEnumeratePhysicalDevices   EnumeratePhysicalDevices   = nullptr;
    PFN_vkEnumerateDeviceExtensionProperties EnumerateDeviceExtensionProperties = nullptr;
    PFN_vkGetPhysicalDeviceProperties GetPhysicalDeviceProperties = nullptr;
    PFN_vkGetPhysicalDeviceProperties2 GetPhysicalDeviceProperties2 = nullptr;
};

struct DeviceDispatch {
    // Core device functions we intercept or chain
    PFN_vkGetDeviceProcAddr          GetDeviceProcAddr          = nullptr;
    PFN_vkDestroyDevice              DestroyDevice              = nullptr;

    // Pipeline cache
    PFN_vkCreatePipelineCache        CreatePipelineCache        = nullptr;
    PFN_vkDestroyPipelineCache       DestroyPipelineCache       = nullptr;
    PFN_vkGetPipelineCacheData       GetPipelineCacheData       = nullptr;

    // Pipeline creation
    PFN_vkCreateGraphicsPipelines    CreateGraphicsPipelines    = nullptr;
    PFN_vkCreateComputePipelines     CreateComputePipelines     = nullptr;
    PFN_vkDestroyPipeline           DestroyPipeline           = nullptr;

    // Texture upload interception
    PFN_vkCmdCopyBufferToImage       CmdCopyBufferToImage       = nullptr;
    PFN_vkCmdCopyBufferToImage2KHR   CmdCopyBufferToImage2KHR   = nullptr;

    // Buffer/memory tracking for texture cache
    PFN_vkCreateBuffer               CreateBuffer               = nullptr;
    PFN_vkDestroyBuffer              DestroyBuffer              = nullptr;
    PFN_vkBindBufferMemory           BindBufferMemory           = nullptr;
    PFN_vkBindBufferMemory2          BindBufferMemory2          = nullptr;
    PFN_vkBindBufferMemory2KHR       BindBufferMemory2KHR       = nullptr;

    // Memory / image queries for texture cache
    PFN_vkMapMemory                  MapMemory                  = nullptr;
    PFN_vkUnmapMemory                UnmapMemory                = nullptr;
    PFN_vkGetBufferMemoryRequirements GetBufferMemoryRequirements = nullptr;
    PFN_vkGetImageMemoryRequirements  GetImageMemoryRequirements  = nullptr;

    // pNext struct helpers
    PFN_vkCmdCopyBufferToImage2      CmdCopyBufferToImage2      = nullptr;

    // Render-pass / framebuffer heuristics
    PFN_vkCreateRenderPass           CreateRenderPass           = nullptr;
    PFN_vkDestroyRenderPass          DestroyRenderPass          = nullptr;
    PFN_vkCreateRenderPass2          CreateRenderPass2          = nullptr;
    PFN_vkCreateRenderPass2KHR       CreateRenderPass2KHR       = nullptr;
    PFN_vkCreateFramebuffer          CreateFramebuffer          = nullptr;
    PFN_vkDestroyFramebuffer         DestroyFramebuffer         = nullptr;
};

// ─────────────────────────────────────────────────────────────────────────────
//  Global table registries (defined in layer_entry.cpp)
// ─────────────────────────────────────────────────────────────────────────────
#include <unordered_map>
#include <mutex>

extern std::mutex                                     g_instance_lock;
extern std::unordered_map<void*, InstanceDispatch>    g_instance_dispatch;
extern std::mutex                                     g_device_lock;
extern std::unordered_map<void*, DeviceDispatch>      g_device_dispatch;

// Safe copy helpers. Returning pointers out of the maps is tempting, but then
// another thread can erase the entry while the caller is still using it. So we
// copy the dispatch table under the lock and let the caller use the copy.
inline bool get_device_dispatch_copy(void* handle, DeviceDispatch& out) {
    if (!handle) return false;
    void* key = dispatch_key(handle);
    std::lock_guard<std::mutex> lk(g_device_lock);
    auto it = g_device_dispatch.find(key);
    if (it == g_device_dispatch.end()) return false;
    out = it->second;
    return true;
}

inline bool get_instance_dispatch_copy(void* handle, InstanceDispatch& out) {
    if (!handle) return false;
    void* key = dispatch_key(handle);
    std::lock_guard<std::mutex> lk(g_instance_lock);
    auto it = g_instance_dispatch.find(key);
    if (it == g_instance_dispatch.end()) return false;
    out = it->second;
    return true;
}
