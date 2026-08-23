#include "conflict_detect.h"
#include "log.h"

#include <cstring>
#include <mutex>

ConflictFlags g_conflict{};

static std::mutex g_conflict_mutex;

struct KnownLayer {
    const char* name_fragment;
    bool covers_pipeline_cache;
    bool covers_texture_intercept;
    bool covers_sanitizer;
};

static constexpr KnownLayer k_known_layers[] = {
    { "VORTEK_XCLIPSE",  true, true, true },
    { "VK_LAYER_VORTEK", true, true, true },
    { "MDIEX",           true, true, true },
    { "MdiEx",           true, true, true },
};

static constexpr size_t k_known_layers_count =
    sizeof(k_known_layers) / sizeof(k_known_layers[0]);

void detect_conflicting_layers(const VkInstanceCreateInfo* pci) {
    std::lock_guard<std::mutex> lock(g_conflict_mutex);

    g_conflict.has_pipeline_cache.store(false, std::memory_order_release);
    g_conflict.has_texture_intercept.store(false, std::memory_order_release);
    g_conflict.has_sanitizer.store(false, std::memory_order_release);

    if (!pci) return;

    for (uint32_t i = 0; i < pci->enabledLayerCount; ++i) {
        const char* name = pci->ppEnabledLayerNames[i];
        if (!name) continue;

        for (size_t k = 0; k < k_known_layers_count; ++k) {
            const KnownLayer& known = k_known_layers[k];

            if (!std::strstr(name, known.name_fragment)) continue;

            if (known.covers_pipeline_cache) {
                g_conflict.has_pipeline_cache.store(true, std::memory_order_release);
                LOGI("ConflictDetect: layer '%s' covers pipeline_cache — disabling ours", name);
            }

            if (known.covers_texture_intercept) {
                g_conflict.has_texture_intercept.store(true, std::memory_order_release);
                LOGI("ConflictDetect: layer '%s' covers texture_intercept — disabling ours", name);
            }

            if (known.covers_sanitizer) {
                g_conflict.has_sanitizer.store(true, std::memory_order_release);
                LOGI("ConflictDetect: layer '%s' covers sanitizer — disabling ours", name);
            }
        }
    }
}
