#pragma once
#include <vulkan/vulkan.h>

// Lightweight pipeline control: blacklist known-bad pipeline signatures and
// expose warmup policy selected by AutoProfile. This deliberately does not
// touch NRamV territory; it only affects Vulkan pipeline creation behavior.

void pipeline_control_on_device_created(const VkPhysicalDeviceProperties& props);

bool pipeline_blacklist_should_skip_graphics(const VkGraphicsPipelineCreateInfo& ci);
bool pipeline_blacklist_should_skip_compute(const VkComputePipelineCreateInfo& ci);

void pipeline_blacklist_mark_graphics(const VkGraphicsPipelineCreateInfo& ci);
void pipeline_blacklist_mark_compute(const VkComputePipelineCreateInfo& ci);

uint64_t pipeline_signature_graphics(const VkGraphicsPipelineCreateInfo& ci);
uint64_t pipeline_signature_compute(const VkComputePipelineCreateInfo& ci);
bool pipeline_warmup_should_precreate_graphics(const VkGraphicsPipelineCreateInfo& ci);
bool pipeline_warmup_should_precreate_compute(const VkComputePipelineCreateInfo& ci);
void pipeline_warmup_mark_graphics(const VkGraphicsPipelineCreateInfo& ci);
void pipeline_warmup_mark_compute(const VkComputePipelineCreateInfo& ci);
