#pragma once

#include <vulkan/vulkan.h>
#include <atomic>

// ─────────────────────────────────────────────────────────────────────────────
// conflict_detect.h
//
// Runtime detection of other active Vulkan layers that duplicate features
// provided by VK_LAYER_PERFCACHE.
// ─────────────────────────────────────────────────────────────────────────────

struct ConflictFlags {
    std::atomic_bool has_pipeline_cache{false};
    std::atomic_bool has_texture_intercept{false};
    std::atomic_bool has_sanitizer{false};
};

extern ConflictFlags g_conflict;

void detect_conflicting_layers(const VkInstanceCreateInfo* pCreateInfo);
