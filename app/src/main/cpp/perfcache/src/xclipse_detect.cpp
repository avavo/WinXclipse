// xclipse_detect.cpp
//
// GPU detection helper for LayerCache Helix.
//
// Purpose:
//   - Detect Samsung Xclipse GPUs for Xclipse-specific driver workarounds.
//   - Keep the layer usable on ALL SoCs.
//   - Do NOT use Xclipse detection as a global enable/disable gate.
//
// Generic features should remain available everywhere:
//   - pipeline cache
//   - pipeline blacklist
//   - warmup
//   - texture upload dedupe/cache
//   - render-pass heuristics
//
// Xclipse-only behavior should stay behind perfcache_is_xclipse():
//   - aggressive sanitizer defaults
//   - required subgroup size fixes
//   - dynamic-rendering/renderPass pNext fixes
//
// In short: LayerCache is universal. Xclipse detection only decides which
// dangerous little driver goblins need special handling.

#include "xclipse_detect.h"
#include "log.h"

#include <atomic>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <climits>

std::atomic_bool g_is_xclipse{false};
std::atomic_uint32_t g_xclipse_gen{0};

// Known vendor IDs seen around Samsung/ARM Vulkan stacks.
//
// Do not gate solely on vendorID:
//   - Some Xclipse stacks may report Samsung.
//   - Some may report ARM.
//   - Some Android GPU stacks enjoy making identity a guessing game,
//     because apparently even silicon needs a masquerade ball.
static constexpr uint32_t SAMSUNG_VENDOR_ID  = 0x144Du;
static constexpr uint32_t ARM_VENDOR_ID      = 0x13B5u;
static constexpr uint32_t QUALCOMM_VENDOR_ID = 0x5143u; // Adreno / Qualcomm, log hint only
static constexpr uint32_t IMG_VENDOR_ID      = 0x1010u; // PowerVR / Imagination, log hint only

static bool ascii_ieq(char a, char b) {
    return std::tolower(static_cast<unsigned char>(a)) ==
           std::tolower(static_cast<unsigned char>(b));
}

static bool contains_icase(const char* haystack, const char* needle) {
    if (!haystack || !needle || !*needle) return false;

    const size_t nlen = std::strlen(needle);

    for (const char* p = haystack; *p; ++p) {
        size_t i = 0;

        while (i < nlen && p[i] && ascii_ieq(p[i], needle[i]))
            ++i;

        if (i == nlen)
            return true;
    }

    return false;
}

static uint32_t first_number_after_icase(const char* text, const char* token) {
    if (!text || !token || !*token) return 0;

    const size_t tlen = std::strlen(token);

    for (const char* p = text; *p; ++p) {
        size_t i = 0;

        while (i < tlen && p[i] && ascii_ieq(p[i], token[i]))
            ++i;

        if (i != tlen)
            continue;

        const char* q = p + tlen;

        while (*q && (*q < '0' || *q > '9'))
            ++q;

        if (*q >= '0' && *q <= '9') {
            unsigned long v = std::strtoul(q, nullptr, 10);
            if (v > UINT32_MAX) return 0;
            return static_cast<uint32_t>(v);
        }

        return 0;
    }

    return 0;
}

static const char* vendor_hint(uint32_t vendor_id) {
    switch (vendor_id) {
        case SAMSUNG_VENDOR_ID:  return "Samsung";
        case ARM_VENDOR_ID:      return "ARM";
        case QUALCOMM_VENDOR_ID: return "Qualcomm";
        case IMG_VENDOR_ID:      return "Imagination";
        default:                 return "unknown";
    }
}

void perfcache_detect_device(VkPhysicalDevice /*phys_dev*/,
                             const VkPhysicalDeviceProperties& props) {
    g_is_xclipse.store(false, std::memory_order_release);
    g_xclipse_gen.store(0, std::memory_order_release);

    const char* name = props.deviceName;

    const bool name_says_xclipse =
        contains_icase(name, "xclipse");

    if (name_says_xclipse) {
        const uint32_t gen = first_number_after_icase(name, "xclipse");

        g_xclipse_gen.store(gen, std::memory_order_release);
        g_is_xclipse.store(true, std::memory_order_release);

        LOGI("Detected Samsung Xclipse GPU: \"%s\" (gen %u, vendor=0x%04x/%s, device=0x%04x, driver=0x%08x)",
             props.deviceName,
             gen,
             props.vendorID,
             vendor_hint(props.vendorID),
             props.deviceID,
             props.driverVersion);
        return;
    }

    // Non-Xclipse is NOT an error. It only means Xclipse-specific sanitizer
    // workarounds should stay off. Generic cache/warmup/dedupe features may
    // still be used by settings.cpp based on RAM/CPU/profile.
    const bool looks_mali =
        contains_icase(name, "mali") ||
        contains_icase(name, "immortalis");

    const bool looks_adreno =
        contains_icase(name, "adreno");

    const bool looks_powervr =
        contains_icase(name, "powervr") ||
        contains_icase(name, "power vr") ||
        props.vendorID == IMG_VENDOR_ID;

    const char* family = "generic";
    if (looks_mali) {
        family = "Mali/Immortalis";
    } else if (looks_adreno) {
        family = "Adreno";
    } else if (looks_powervr) {
        family = "PowerVR";
    }

    LOGI("Non-Xclipse GPU: vendor=0x%04x/%s device=0x%04x \"%s\" family=%s driver=0x%08x — generic LayerCache features remain available",
         props.vendorID,
         vendor_hint(props.vendorID),
         props.deviceID,
         props.deviceName,
         family,
         props.driverVersion);
}
