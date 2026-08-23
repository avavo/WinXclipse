// pipeline_sanitizer.cpp
//
// Repairs common VkGraphicsPipelineCreateInfo defects before handing them
// to the driver. Seven classes of defects are handled:
//
//   1. Static viewport arrays when VK_DYNAMIC_STATE_VIEWPORT is active
//   2. Static scissor  arrays when VK_DYNAMIC_STATE_SCISSOR  is active
//   3. Zero-count but non-null color-blend attachment arrays
//   4. Zero-count but non-null dynamic-state arrays
//   5. Empty / incomplete VkSpecializationInfo
//   6. VkPipelineRenderingCreateInfo in pNext when renderPass != VK_NULL_HANDLE
//   7. VkPipelineShaderStageRequiredSubgroupSizeCreateInfo with out-of-range
//      subgroup size (Xclipse-only)
//
// Corrections 1-5 are safe on all drivers.
// Corrections 6-7 are applied only on Xclipse.
//
// All patching is done on context-owned sidecar storage.
// The caller's structs are never modified.

#include "pipeline_sanitizer.h"
#include "xclipse_detect.h"
#include "layer_settings.h"
#include "log.h"

#include <cstdint>
#include <limits>
#include <vector>

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

static bool has_dynamic_state(const VkPipelineDynamicStateCreateInfo* ds,
                              VkDynamicState target) {
    if (!ds || ds->dynamicStateCount == 0 || !ds->pDynamicStates)
        return false;

    for (uint32_t i = 0; i < ds->dynamicStateCount; ++i) {
        if (ds->pDynamicStates[i] == target)
            return true;
    }

    return false;
}

static bool specialization_info_is_bad(const VkSpecializationInfo* sp) {
    if (!sp) return false;

    // Empty specialization blocks are legal-ish, but some drivers still touch
    // the pointers because apparently "do nothing" was too advanced.
    if (sp->mapEntryCount == 0)
        return true;

    if (sp->mapEntryCount > 0 && !sp->pMapEntries)
        return true;

    if (sp->dataSize > 0 && !sp->pData)
        return true;

    return false;
}

static bool is_required_subgroup_size_node(VkStructureType sType) {
    return sType ==
        VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_REQUIRED_SUBGROUP_SIZE_CREATE_INFO;
}

// ─────────────────────────────────────────────────────────────────────────────
// Shallow copy helper
// ─────────────────────────────────────────────────────────────────────────────
//
// This function only fills patched with shallow copies.
// All real corrections happen inside PipelineSanitizerContext::prepare(),
// because prepare() owns the sidecar storage needed for safe mutation.

void xcache_sanitize_pipelines(
    uint32_t count,
    const VkGraphicsPipelineCreateInfo* src,
    std::vector<VkGraphicsPipelineCreateInfo>& patched,
    const VkPhysicalDeviceProperties2* /*dev_props2*/)
{
    patched.clear();

    if (count == 0)
        return;

    if (!src) {
        LOGE("Sanitizer: src is null with count=%u", count);
        return;
    }

    patched.assign(src, src + count);
}

// ─────────────────────────────────────────────────────────────────────────────
// PipelineSanitizerContext::prepare
// ─────────────────────────────────────────────────────────────────────────────

void PipelineSanitizerContext::prepare(
    uint32_t count,
    const VkGraphicsPipelineCreateInfo* src,
    const VkPhysicalDeviceProperties2* dev_props2)
{
    xcache_sanitize_pipelines(count, src, patched, dev_props2);

    if (patched.empty())
        return;

    if (!g_settings.sanitize_pipelines)
        return;

    // ── Resolve subgroup size range for correction 7 ─────────────────────────

    uint32_t min_sg = 0;
    uint32_t max_sg = std::numeric_limits<uint32_t>::max();

    if (xcache_is_xclipse() && dev_props2) {
        const VkBaseInStructure* p =
            reinterpret_cast<const VkBaseInStructure*>(dev_props2->pNext);

        uint32_t depth = 0;
        while (p && depth++ < 32) {
            if (p->sType ==
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_SIZE_CONTROL_PROPERTIES)
            {
                const auto* sc =
                    reinterpret_cast<
                        const VkPhysicalDeviceSubgroupSizeControlProperties*>(p);

                min_sg = sc->minSubgroupSize;
                max_sg = sc->maxSubgroupSize;
                break;
            }

            p = p->pNext;
        }
    }

    if (max_sg < min_sg) {
        LOGE("Sanitizer: invalid subgroup range min=%u max=%u, disabling correction 7",
             min_sg, max_sg);
        min_sg = 0;
        max_sg = std::numeric_limits<uint32_t>::max();
    }

    // ── Allocate sidecar arrays ──────────────────────────────────────────────

    vp_states.clear();
    cb_states.clear();
    dyn_states.clear();
    stage_copies.clear();

    vp_states.resize(count);
    cb_states.resize(count);
    dyn_states.resize(count);
    stage_copies.resize(count);

    // ── Counters ─────────────────────────────────────────────────────────────

    uint32_t n_viewport = 0;
    uint32_t n_scissor = 0;
    uint32_t n_blend = 0;
    uint32_t n_dynstate = 0;
    uint32_t n_specinfo = 0;
    uint32_t n_rendering = 0;
    uint32_t n_rendering_deep_dropped = 0;
    uint32_t n_subgroup = 0;
    uint32_t n_subgroup_deep_dropped = 0;

    // ── Per-pipeline corrections ─────────────────────────────────────────────

    for (uint32_t i = 0; i < count; ++i) {
        VkGraphicsPipelineCreateInfo& ci = patched[i];

        const VkPipelineDynamicStateCreateInfo* ds = ci.pDynamicState;

        // ─────────────────────────────────────────────────────────────────────
        // Correction 3:
        // Zero-count non-null pAttachments in color blend state
        // ─────────────────────────────────────────────────────────────────────

        if (ci.pColorBlendState &&
            ci.pColorBlendState->attachmentCount == 0 &&
            ci.pColorBlendState->pAttachments != nullptr)
        {
            cb_states[i] = *ci.pColorBlendState;
            cb_states[i].pAttachments = nullptr;
            ci.pColorBlendState = &cb_states[i];
            ++n_blend;
        }

        // ─────────────────────────────────────────────────────────────────────
        // Correction 4:
        // Zero-count non-null pDynamicStates
        // ─────────────────────────────────────────────────────────────────────

        if (ds &&
            ds->dynamicStateCount == 0 &&
            ds->pDynamicStates != nullptr)
        {
            dyn_states[i] = *ds;
            dyn_states[i].pDynamicStates = nullptr;
            ci.pDynamicState = &dyn_states[i];
            ds = ci.pDynamicState;
            ++n_dynstate;
        }

        // ─────────────────────────────────────────────────────────────────────
        // Corrections 1 & 2:
        // Static viewport/scissor arrays with dynamic viewport/scissor enabled
        // ─────────────────────────────────────────────────────────────────────

        if (ci.pViewportState) {
            const bool dyn_vp =
                has_dynamic_state(ds, VK_DYNAMIC_STATE_VIEWPORT);
            const bool dyn_sc =
                has_dynamic_state(ds, VK_DYNAMIC_STATE_SCISSOR);

            if (dyn_vp || dyn_sc) {
                vp_states[i] = *ci.pViewportState;

                if (dyn_vp && vp_states[i].pViewports) {
                    vp_states[i].pViewports = nullptr;
                    ++n_viewport;
                }

                if (dyn_sc && vp_states[i].pScissors) {
                    vp_states[i].pScissors = nullptr;
                    ++n_scissor;
                }

                ci.pViewportState = &vp_states[i];
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // Correction 5:
        // Empty / incomplete VkSpecializationInfo
        // ─────────────────────────────────────────────────────────────────────

        if (ci.pStages && ci.stageCount > 0) {
            bool need_copy = false;

            for (uint32_t s = 0; s < ci.stageCount; ++s) {
                const VkSpecializationInfo* sp =
                    ci.pStages[s].pSpecializationInfo;

                if (specialization_info_is_bad(sp)) {
                    need_copy = true;
                    ++n_specinfo;
                }
            }

            if (need_copy) {
                stage_copies[i].assign(ci.pStages, ci.pStages + ci.stageCount);

                for (uint32_t s = 0; s < ci.stageCount; ++s) {
                    const VkSpecializationInfo* sp =
                        stage_copies[i][s].pSpecializationInfo;

                    if (specialization_info_is_bad(sp))
                        stage_copies[i][s].pSpecializationInfo = nullptr;
                }

                ci.pStages = stage_copies[i].data();
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // Correction 6:
        // VkPipelineRenderingCreateInfo with legacy renderPass
        //
        // If the rendering node is first, skip only that node.
        // If it appears deeper, drop the whole pNext chain. Brutal, yes.
        // Safer than manufacturing fake predecessor structs with unknown sizes.
        // ─────────────────────────────────────────────────────────────────────

        if (xcache_is_xclipse() &&
            ci.renderPass != VK_NULL_HANDLE &&
            ci.pNext)
        {
            const VkBaseInStructure* first =
                reinterpret_cast<const VkBaseInStructure*>(ci.pNext);

            if (first &&
                first->sType == VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
            {
                ci.pNext = first->pNext;
                ++n_rendering;
            } else {
                const VkBaseInStructure* cur = first;
                bool found_deep = false;
                uint32_t depth = 0;

                while (cur && depth++ < 32) {
                    const VkBaseInStructure* next =
                        reinterpret_cast<const VkBaseInStructure*>(cur->pNext);

                    if (next &&
                        next->sType ==
                            VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
                    {
                        found_deep = true;
                        break;
                    }

                    cur = next;
                }

                if (found_deep) {
                    ci.pNext = nullptr;
                    ++n_rendering_deep_dropped;
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // Correction 7:
        // Invalid required subgroup size in shader stage pNext
        //
        // If the offending node is first, skip only it.
        // If it is deeper, drop the stage pNext chain.
        // Again: conservative, because fake pNext cloning is where sanity dies.
        // ─────────────────────────────────────────────────────────────────────

        if (xcache_is_xclipse() &&
            min_sg != 0 &&
            ci.pStages &&
            ci.stageCount > 0)
        {
            for (uint32_t s = 0; s < ci.stageCount; ++s) {
                const VkBaseInStructure* p =
                    reinterpret_cast<const VkBaseInStructure*>(
                        ci.pStages[s].pNext);

                bool invalid_required_subgroup = false;
                bool invalid_is_first = false;

                uint32_t depth = 0;
                while (p && depth++ < 32) {
                    if (is_required_subgroup_size_node(p->sType)) {
                        const auto* rs =
                            reinterpret_cast<
                                const VkPipelineShaderStageRequiredSubgroupSizeCreateInfo*>(p);

                        if (rs->requiredSubgroupSize < min_sg ||
                            rs->requiredSubgroupSize > max_sg)
                        {
                            invalid_required_subgroup = true;
                            invalid_is_first = (depth == 1);
                        }

                        break;
                    }

                    p = p->pNext;
                }

                if (!invalid_required_subgroup)
                    continue;

                if (stage_copies[i].empty()) {
                    stage_copies[i].assign(ci.pStages, ci.pStages + ci.stageCount);
                    ci.pStages = stage_copies[i].data();
                }

                const VkBaseInStructure* first_pnext =
                    reinterpret_cast<const VkBaseInStructure*>(
                        stage_copies[i][s].pNext);

                if (invalid_is_first &&
                    first_pnext &&
                    is_required_subgroup_size_node(first_pnext->sType))
                {
                    stage_copies[i][s].pNext = first_pnext->pNext;
                    ++n_subgroup;
                } else {
                    stage_copies[i][s].pNext = nullptr;
                    ++n_subgroup;
                    ++n_subgroup_deep_dropped;
                }
            }
        }
    }

    // ── Log summary ──────────────────────────────────────────────────────────

    if (n_viewport)
        LOGV("Sanitizer: cleared %u static viewport arrays", n_viewport);

    if (n_scissor)
        LOGV("Sanitizer: cleared %u static scissor arrays", n_scissor);

    if (n_blend)
        LOGV("Sanitizer: zeroed pAttachments on %u zero-count color blend states",
             n_blend);

    if (n_dynstate)
        LOGV("Sanitizer: zeroed pDynamicStates on %u zero-count dynamic state structs",
             n_dynstate);

    if (n_specinfo)
        LOGV("Sanitizer: nulled %u empty/incomplete VkSpecializationInfo blocks",
             n_specinfo);

    if (n_rendering)
        LOGI("Sanitizer: stripped %u first-node VkPipelineRenderingCreateInfo blocks "
             "(renderPass != NULL)",
             n_rendering);

    if (n_rendering_deep_dropped)
        LOGI("Sanitizer: dropped %u full pipeline pNext chains to avoid deep "
             "VkPipelineRenderingCreateInfo on Xclipse",
             n_rendering_deep_dropped);

    if (n_subgroup)
        LOGI("Sanitizer: stripped %u invalid required-subgroup-size pNext blocks",
             n_subgroup);

    if (n_subgroup_deep_dropped)
        LOGI("Sanitizer: dropped %u stage pNext chains to avoid deep invalid "
             "required-subgroup-size nodes",
             n_subgroup_deep_dropped);
}