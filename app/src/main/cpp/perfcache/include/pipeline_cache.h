#pragma once
#include <vulkan/vulkan.h>
#include "dispatch_table.h"

void pipeline_cache_on_device_created(
    VkDevice                          device,
    VkPhysicalDevice                  phys_dev,
    const DeviceDispatch&             dispatch,
    const VkPhysicalDeviceProperties& props);

void pipeline_cache_on_device_destroyed(VkDevice              device,
                                        const DeviceDispatch& dispatch);

// Returns the internal VkPipelineCache for this device (VK_NULL_HANDLE if none).
VkPipelineCache pipeline_cache_get(VkDevice device);
