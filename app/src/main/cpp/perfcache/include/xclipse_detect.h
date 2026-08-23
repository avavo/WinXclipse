#pragma once

#include <vulkan/vulkan.h>
#include <atomic>
#include <cstdint>

// ─────────────────────────────────────────────────────────────────────────────
// xclipse_detect.h
//
// Detects Samsung Xclipse GPUs for Xclipse-specific driver workarounds.
// LayerCache generic features are not gated by this flag.
// ─────────────────────────────────────────────────────────────────────────────

extern std::atomic_bool g_is_xclipse;
extern std::atomic_uint32_t g_xclipse_gen;

void xcache_detect_device(VkPhysicalDevice phys_dev,
                             const VkPhysicalDeviceProperties& props);

static inline bool xcache_is_xclipse() {
    return g_is_xclipse.load(std::memory_order_acquire);
}

static inline uint32_t xcache_xclipse_gen() {
    return g_xclipse_gen.load(std::memory_order_acquire);
}
