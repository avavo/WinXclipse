// SPDX-License-Identifier: MIT
#pragma once
#include <cstring>
#include <initializer_list>
namespace displayx {
// Commands from these extensions accept native swapchain handles. Advertising
// them makes DXVK enable them and forces our synthetic AHB swapchains to fall back.
inline bool nativeSwapchainExtension(const char* name) {
    for (const char* extension : {"VK_EXT_swapchain_maintenance1", "VK_KHR_swapchain_maintenance1",
            "VK_GOOGLE_display_timing", "VK_KHR_shared_presentable_image", "VK_EXT_full_screen_exclusive",
            "VK_KHR_present_wait", "VK_EXT_display_control", "VK_KHR_present_wait2", "VK_KHR_present_id2"})
        if (!std::strcmp(name, extension)) return true;
    return false;
}
}
