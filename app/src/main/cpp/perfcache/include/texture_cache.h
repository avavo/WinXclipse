#pragma once
#include <vulkan/vulkan.h>
#include "dispatch_table.h"
#include "layer_settings.h"

// Cache namespace / driver identity
void texture_cache_set_driver_identity(const VkPhysicalDeviceProperties& props);

// Drops tracking maps (mappings/buffer bindings) left over from a destroyed
// device so they cannot accumulate across sessions in the same process.
void texture_cache_on_device_destroyed();

// Memory map tracking (called from layer_entry.cpp intercepts)
void texture_cache_on_map_memory(VkDeviceMemory mem,
                                  VkDeviceSize   offset,
                                  VkDeviceSize   size,
                                  void*          ptr);
void texture_cache_on_unmap_memory(VkDeviceMemory mem);

// Buffer binding tracking (called from layer_entry.cpp intercepts)
void texture_cache_on_create_buffer(VkBuffer buffer, VkDeviceSize size);
void texture_cache_on_destroy_buffer(VkBuffer buffer);
void texture_cache_on_bind_buffer_memory(VkBuffer buffer, VkDeviceMemory memory, VkDeviceSize memory_offset);

// Command buffer intercepts
void texture_cache_cmd_copy_buffer_to_image(
    VkCommandBuffer          cmd,
    VkBuffer                 src,
    VkImage                  dst,
    VkImageLayout            dst_layout,
    uint32_t                 region_count,
    const VkBufferImageCopy* regions,
    const DeviceDispatch&    dispatch);

void texture_cache_cmd_copy_buffer_to_image2(
    VkCommandBuffer               cmd,
    const VkCopyBufferToImageInfo2* info,
    const DeviceDispatch&         dispatch);

void texture_cache_cmd_copy_buffer_to_image2_khr(
    VkCommandBuffer               cmd,
    const VkCopyBufferToImageInfo2* info,
    const DeviceDispatch&         dispatch);
