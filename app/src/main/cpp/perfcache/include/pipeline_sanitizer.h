#pragma once
#include <vulkan/vulkan.h>
#include <unordered_map>
#include <vector>

// ─────────────────────────────────────────────────────────────────────────────
//  pipeline_sanitizer.h
//
//  PipelineSanitizerContext manages all sidecar allocations needed to safely
//  patch VkGraphicsPipelineCreateInfo arrays without touching the caller's
//  originals.
//
//  Usage in layer_entry.cpp:
//
//    PipelineSanitizerContext ctx;
//    ctx.prepare(createInfoCount, pCreateInfos, dev_props2);
//    result = next_CreateGraphicsPipelines(..., ctx.patched.data(), ...);
// ─────────────────────────────────────────────────────────────────────────────

// Minimal pNext-chain node header used for chain surgery.
struct PNextHeader {
    VkStructureType sType;
    void*           pNext;
};

struct PipelineSanitizerContext {
    // The patched array passed to the driver.
    std::vector<VkGraphicsPipelineCreateInfo>         patched;

    // Sidecars for corrections 1 & 2 (viewport / scissor static arrays).
    std::vector<VkPipelineViewportStateCreateInfo>    vp_states;

    // Sidecar for correction 3 (zero-count color blend attachment array).
    std::vector<VkPipelineColorBlendStateCreateInfo>  cb_states;

    // Sidecar for correction 4 (zero-count dynamic state array).
    std::vector<VkPipelineDynamicStateCreateInfo>     dyn_states;

    // Sidecar for corrections 5 & 7 (spec-info / subgroup stage clones).
    // Outer index = pipeline, inner = stages.
    std::vector<std::vector<VkPipelineShaderStageCreateInfo>> stage_copies;

    // Sidecars for correction 6 (pNext chain surgery for rendering create info).
    // pnext_buf[i]  — copy of the node immediately before the removed node.
    // pnext_pred[i] — copy of the first node if a deeper predecessor needed it.
    // Indexed per pipeline; only populated when the node is found in the chain.
    std::unordered_map<uint32_t, PNextHeader> pnext_buf;
    std::unordered_map<uint32_t, PNextHeader> pnext_pred;

    void prepare(uint32_t count,
                 const VkGraphicsPipelineCreateInfo* src,
                 const VkPhysicalDeviceProperties2*  dev_props2);
};

// Low-level batch function: fills patched with shallow copies.
// All actual corrections are applied in PipelineSanitizerContext::prepare().
void xcache_sanitize_pipelines(
    uint32_t                              count,
    const VkGraphicsPipelineCreateInfo*   src,
    std::vector<VkGraphicsPipelineCreateInfo>& patched,
    const VkPhysicalDeviceProperties2*    dev_props2);
