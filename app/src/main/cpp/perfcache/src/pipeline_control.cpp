#include "pipeline_control.h"
#include "layer_settings.h"
#include "log.h"

#include <vulkan/vulkan.h>

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <mutex>
#include <string>
#include <unordered_set>

static constexpr size_t kMaxControlEntries = 65536;

static std::mutex s_lock;
static std::unordered_set<uint64_t> s_blacklist;
static std::unordered_set<uint64_t> s_warmup_seen;
static std::string s_blacklist_path;
static std::string s_warmup_path;

// ─────────────────────────────────────────────────────────────────────────────
// Hash helpers
// ─────────────────────────────────────────────────────────────────────────────

static uint64_t fnv1a_mix(uint64_t h, uint64_t v) {
    h ^= v;
    h *= 1099511628211ull;
    return h;
}

static uint64_t fnv1a_bytes(uint64_t h, const void* data, size_t n) {
    if (!data || n == 0) return fnv1a_mix(h, 0);

    const unsigned char* p = static_cast<const unsigned char*>(data);
    for (size_t i = 0; i < n; ++i) {
        h ^= p[i];
        h *= 1099511628211ull;
    }

    return h;
}

static uint64_t fnv1a_cstr(uint64_t h, const char* s) {
    if (!s) return fnv1a_mix(h, 0);

    while (*s) {
        h ^= static_cast<unsigned char>(*s++);
        h *= 1099511628211ull;
    }

    return h;
}

static uint64_t fnv1a_float(uint64_t h, float f) {
    return fnv1a_bytes(h, &f, sizeof(float));
}

// ─────────────────────────────────────────────────────────────────────────────
// pNext hashing
//
// Only hashes stable struct types and selected known payloads.
// No pointers, no handles. Vulkan handles are basically haunted addresses.
// ─────────────────────────────────────────────────────────────────────────────

struct PNextHeader {
    VkStructureType sType;
    const void* pNext;
};

static uint64_t stable_pnext_hash(uint64_t h, const void* pNext) {
    const auto* cur = static_cast<const PNextHeader*>(pNext);
    uint32_t depth = 0;

    while (cur && depth++ < 32) {
        h = fnv1a_mix(h, static_cast<uint64_t>(cur->sType));

        switch (cur->sType) {
            case VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO: {
                const auto* r =
                    reinterpret_cast<const VkPipelineRenderingCreateInfo*>(cur);

                h = fnv1a_mix(h, r->viewMask);
                h = fnv1a_mix(h, r->colorAttachmentCount);

                if (r->pColorAttachmentFormats && r->colorAttachmentCount) {
                    for (uint32_t i = 0; i < r->colorAttachmentCount; ++i)
                        h = fnv1a_mix(h, r->pColorAttachmentFormats[i]);
                }

                h = fnv1a_mix(h, r->depthAttachmentFormat);
                h = fnv1a_mix(h, r->stencilAttachmentFormat);
                break;
            }

            case VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_REQUIRED_SUBGROUP_SIZE_CREATE_INFO: {
                const auto* sg =
                    reinterpret_cast<const VkPipelineShaderStageRequiredSubgroupSizeCreateInfo*>(cur);

                h = fnv1a_mix(h, sg->requiredSubgroupSize);
                break;
            }

            default:
                // Unknown extension node: hash sType only.
                // Safer than touching unknown memory sizes like a gremlin with scissors.
                break;
        }

        cur = static_cast<const PNextHeader*>(cur->pNext);
    }

    return h;
}

// ─────────────────────────────────────────────────────────────────────────────
// Stage hash
// ─────────────────────────────────────────────────────────────────────────────

static uint64_t stable_stage_hash(uint64_t h,
                                  const VkPipelineShaderStageCreateInfo& st) {
    // Do NOT hash Vulkan handles. Shader module/layout/renderPass handles are
    // process-local values and change across launches.
    //
    // Important limitation:
    // Without vkCreateShaderModule tracking, we cannot hash SPIR-V contents here.
    // So this signature is stable but not perfect. Stable-but-imperfect beats
    // "address hash that forgets everything after restart", that tiny tragedy.

    h = fnv1a_mix(h, st.flags);
    h = fnv1a_mix(h, st.stage);
    h = fnv1a_cstr(h, st.pName);
    h = stable_pnext_hash(h, st.pNext);

    if (st.pSpecializationInfo) {
        const VkSpecializationInfo* sp = st.pSpecializationInfo;

        h = fnv1a_mix(h, sp->mapEntryCount);
        h = fnv1a_mix(h, sp->dataSize);

        if (sp->pMapEntries && sp->mapEntryCount) {
            h = fnv1a_bytes(
                h,
                sp->pMapEntries,
                sizeof(VkSpecializationMapEntry) * sp->mapEntryCount);
        }

        if (sp->pData && sp->dataSize) {
            h = fnv1a_bytes(h, sp->pData, sp->dataSize);
        }
    }

    return h;
}

// ─────────────────────────────────────────────────────────────────────────────
// Graphics pipeline signature
// ─────────────────────────────────────────────────────────────────────────────

static uint64_t hash_graphics(const VkGraphicsPipelineCreateInfo& ci) {
    uint64_t h = 1469598103934665603ull;

    h = fnv1a_mix(h, 0x475058u); // GPX namespace
    h = fnv1a_mix(h, ci.flags);
    h = fnv1a_mix(h, ci.stageCount);
    h = fnv1a_mix(h, ci.renderPass == VK_NULL_HANDLE ? 0u : 1u);
    h = fnv1a_mix(h, ci.subpass);
    h = stable_pnext_hash(h, ci.pNext);

    for (uint32_t i = 0; i < ci.stageCount && ci.pStages; ++i)
        h = stable_stage_hash(h, ci.pStages[i]);

    if (ci.pVertexInputState) {
        const auto* s = ci.pVertexInputState;

        h = fnv1a_mix(h, s->flags);
        h = fnv1a_mix(h, s->vertexBindingDescriptionCount);
        h = fnv1a_mix(h, s->vertexAttributeDescriptionCount);

        for (uint32_t i = 0;
             i < s->vertexBindingDescriptionCount && s->pVertexBindingDescriptions;
             ++i) {
            const auto& b = s->pVertexBindingDescriptions[i];
            h = fnv1a_mix(h, b.binding);
            h = fnv1a_mix(h, b.stride);
            h = fnv1a_mix(h, b.inputRate);
        }

        for (uint32_t i = 0;
             i < s->vertexAttributeDescriptionCount && s->pVertexAttributeDescriptions;
             ++i) {
            const auto& a = s->pVertexAttributeDescriptions[i];
            h = fnv1a_mix(h, a.location);
            h = fnv1a_mix(h, a.binding);
            h = fnv1a_mix(h, a.format);
            h = fnv1a_mix(h, a.offset);
        }
    }

    if (ci.pInputAssemblyState) {
        const auto* s = ci.pInputAssemblyState;

        h = fnv1a_mix(h, s->flags);
        h = fnv1a_mix(h, s->topology);
        h = fnv1a_mix(h, s->primitiveRestartEnable);
    }

    if (ci.pTessellationState) {
        const auto* s = ci.pTessellationState;

        h = fnv1a_mix(h, s->flags);
        h = fnv1a_mix(h, s->patchControlPoints);
    }

    if (ci.pViewportState) {
        const auto* s = ci.pViewportState;

        h = fnv1a_mix(h, s->flags);
        h = fnv1a_mix(h, s->viewportCount);
        h = fnv1a_mix(h, s->scissorCount);

        if (s->pViewports && s->viewportCount) {
            for (uint32_t i = 0; i < s->viewportCount; ++i) {
                const auto& v = s->pViewports[i];

                h = fnv1a_float(h, v.x);
                h = fnv1a_float(h, v.y);
                h = fnv1a_float(h, v.width);
                h = fnv1a_float(h, v.height);
                h = fnv1a_float(h, v.minDepth);
                h = fnv1a_float(h, v.maxDepth);
            }
        }

        if (s->pScissors && s->scissorCount) {
            for (uint32_t i = 0; i < s->scissorCount; ++i) {
                const auto& r = s->pScissors[i];

                h = fnv1a_mix(h, static_cast<uint32_t>(r.offset.x));
                h = fnv1a_mix(h, static_cast<uint32_t>(r.offset.y));
                h = fnv1a_mix(h, r.extent.width);
                h = fnv1a_mix(h, r.extent.height);
            }
        }
    }

    if (ci.pRasterizationState) {
        const auto* s = ci.pRasterizationState;

        h = fnv1a_mix(h, s->flags);
        h = fnv1a_mix(h, s->depthClampEnable);
        h = fnv1a_mix(h, s->rasterizerDiscardEnable);
        h = fnv1a_mix(h, s->polygonMode);
        h = fnv1a_mix(h, s->cullMode);
        h = fnv1a_mix(h, s->frontFace);
        h = fnv1a_mix(h, s->depthBiasEnable);
        h = fnv1a_float(h, s->depthBiasConstantFactor);
        h = fnv1a_float(h, s->depthBiasClamp);
        h = fnv1a_float(h, s->depthBiasSlopeFactor);
        h = fnv1a_float(h, s->lineWidth);
    }

    if (ci.pMultisampleState) {
        const auto* s = ci.pMultisampleState;

        h = fnv1a_mix(h, s->flags);
        h = fnv1a_mix(h, s->rasterizationSamples);
        h = fnv1a_mix(h, s->sampleShadingEnable);
        h = fnv1a_float(h, s->minSampleShading);
        h = fnv1a_mix(h, s->alphaToCoverageEnable);
        h = fnv1a_mix(h, s->alphaToOneEnable);

        if (s->pSampleMask) {
            const uint32_t words = (static_cast<uint32_t>(s->rasterizationSamples) + 31u) / 32u;
            h = fnv1a_bytes(h, s->pSampleMask, sizeof(VkSampleMask) * words);
        }
    }

    if (ci.pDepthStencilState) {
        const auto* s = ci.pDepthStencilState;

        h = fnv1a_mix(h, s->flags);
        h = fnv1a_mix(h, s->depthTestEnable);
        h = fnv1a_mix(h, s->depthWriteEnable);
        h = fnv1a_mix(h, s->depthCompareOp);
        h = fnv1a_mix(h, s->depthBoundsTestEnable);
        h = fnv1a_mix(h, s->stencilTestEnable);
        h = fnv1a_bytes(h, &s->front, sizeof(VkStencilOpState));
        h = fnv1a_bytes(h, &s->back, sizeof(VkStencilOpState));
        h = fnv1a_float(h, s->minDepthBounds);
        h = fnv1a_float(h, s->maxDepthBounds);
    }

    if (ci.pColorBlendState) {
        const auto* s = ci.pColorBlendState;

        h = fnv1a_mix(h, s->flags);
        h = fnv1a_mix(h, s->logicOpEnable);
        h = fnv1a_mix(h, s->logicOp);
        h = fnv1a_mix(h, s->attachmentCount);

        for (uint32_t i = 0; i < s->attachmentCount && s->pAttachments; ++i) {
            const auto& a = s->pAttachments[i];

            h = fnv1a_mix(h, a.blendEnable);
            h = fnv1a_mix(h, a.srcColorBlendFactor);
            h = fnv1a_mix(h, a.dstColorBlendFactor);
            h = fnv1a_mix(h, a.colorBlendOp);
            h = fnv1a_mix(h, a.srcAlphaBlendFactor);
            h = fnv1a_mix(h, a.dstAlphaBlendFactor);
            h = fnv1a_mix(h, a.alphaBlendOp);
            h = fnv1a_mix(h, a.colorWriteMask);
        }

        h = fnv1a_float(h, s->blendConstants[0]);
        h = fnv1a_float(h, s->blendConstants[1]);
        h = fnv1a_float(h, s->blendConstants[2]);
        h = fnv1a_float(h, s->blendConstants[3]);
    }

    if (ci.pDynamicState) {
        const auto* s = ci.pDynamicState;

        h = fnv1a_mix(h, s->flags);
        h = fnv1a_mix(h, s->dynamicStateCount);

        for (uint32_t i = 0; i < s->dynamicStateCount && s->pDynamicStates; ++i)
            h = fnv1a_mix(h, s->pDynamicStates[i]);
    }

    return h;
}

uint64_t pipeline_signature_graphics(const VkGraphicsPipelineCreateInfo& ci) {
    return hash_graphics(ci);
}

// ─────────────────────────────────────────────────────────────────────────────
// Compute pipeline signature
// ─────────────────────────────────────────────────────────────────────────────

static uint64_t hash_compute(const VkComputePipelineCreateInfo& ci) {
    uint64_t h = 1469598103934665603ull;

    h = fnv1a_mix(h, 0x435058u); // CPX namespace
    h = fnv1a_mix(h, ci.flags);
    h = stable_pnext_hash(h, ci.pNext);
    h = stable_stage_hash(h, ci.stage);

    return h;
}

uint64_t pipeline_signature_compute(const VkComputePipelineCreateInfo& ci) {
    return hash_compute(ci);
}

// ─────────────────────────────────────────────────────────────────────────────
// Path helpers
// ─────────────────────────────────────────────────────────────────────────────

static void ensure_dir(const std::string& dir) {
    if (dir.empty()) return;

    std::string cur;
    size_t pos = 0;

    if (dir[0] == '/') {
        cur = "/";
        pos = 1;
    }

    while (pos < dir.size()) {
        while (pos < dir.size() && dir[pos] == '/')
            ++pos;

        if (pos >= dir.size())
            break;

        size_t next = dir.find('/', pos);
        std::string part = dir.substr(
            pos,
            next == std::string::npos ? std::string::npos : next - pos);

        if (!part.empty()) {
            if (!cur.empty() && cur.back() != '/')
                cur += '/';

            cur += part;

            if (mkdir(cur.c_str(), 0755) != 0 && errno != EEXIST) {
                LOGE("pipeline_control: mkdir failed for %s: %s",
                     cur.c_str(), strerror(errno));
                return;
            }
        }

        if (next == std::string::npos)
            break;

        pos = next + 1;
    }
}

static std::string uuid_hex(const VkPhysicalDeviceProperties& props) {
    char buf[33]{};

    for (int i = 0; i < 16; ++i) {
        snprintf(buf + i * 2, 3, "%02x",
                 static_cast<unsigned int>(
                     static_cast<uint8_t>(props.pipelineCacheUUID[i])));
    }

    return std::string(buf);
}

static std::string make_control_path(const VkPhysicalDeviceProperties& props,
                                     const char* prefix) {
    std::string path = g_settings.pipeline_cache_dir;
    if (!path.empty() && path.back() != '/')
        path += '/';

    path += prefix;
    path += "_";
    path += uuid_hex(props);
    path += "_driver";
    path += std::to_string(props.driverVersion);
    path += "_vendor";
    path += std::to_string(props.vendorID);
    path += "_device";
    path += std::to_string(props.deviceID);
    path += "_";
    path += g_settings.profile_name.empty() ? "default" : g_settings.profile_name;
    path += ".txt";

    return path;
}

// ─────────────────────────────────────────────────────────────────────────────
// File lock
// ─────────────────────────────────────────────────────────────────────────────

struct ControlFileLock {
    int fd = -1;

    explicit ControlFileLock(int mode) {
        ensure_dir(g_settings.pipeline_cache_dir);

        std::string path = g_settings.pipeline_cache_dir;
        if (!path.empty() && path.back() != '/')
            path += '/';
        path += ".pipelinecontrol.lock";

        fd = open(path.c_str(), O_CREAT | O_RDWR | O_CLOEXEC, 0600);

        if (fd >= 0) {
            if (flock(fd, mode) != 0) {
                LOGE("pipeline_control: flock failed for %s: %s",
                     path.c_str(), strerror(errno));
                close(fd);
                fd = -1;
            }
        }
    }

    ~ControlFileLock() {
        if (fd >= 0) {
            flock(fd, LOCK_UN);
            close(fd);
        }
    }

    explicit operator bool() const {
        return fd >= 0;
    }

    ControlFileLock(const ControlFileLock&) = delete;
    ControlFileLock& operator=(const ControlFileLock&) = delete;
};

// ─────────────────────────────────────────────────────────────────────────────
// Disk sets
// ─────────────────────────────────────────────────────────────────────────────

static void load_set_locked(const std::string& path,
                            std::unordered_set<uint64_t>& set) {
    set.clear();

    if (path.empty()) return;

    ControlFileLock file_lock(LOCK_SH);
    if (!file_lock) return;

    FILE* f = fopen(path.c_str(), "r");
    if (!f) return;

    char line[128];
    size_t count = 0;

    while (fgets(line, sizeof(line), f) && count < kMaxControlEntries) {
        char* end = nullptr;
        unsigned long long v = strtoull(line, &end, 16);

        if (end != line) {
            set.insert(static_cast<uint64_t>(v));
            ++count;
        }
    }

    fclose(f);
}

static void append_set_locked(const std::string& path, uint64_t h) {
    if (path.empty()) return;

    ensure_dir(g_settings.pipeline_cache_dir);

    ControlFileLock file_lock(LOCK_EX);
    if (!file_lock) return;

    int fd = open(path.c_str(), O_CREAT | O_WRONLY | O_APPEND | O_CLOEXEC, 0644);
    if (fd < 0) {
        LOGE("pipeline_control: open append failed for %s: %s",
             path.c_str(), strerror(errno));
        return;
    }

    char line[32];
    int len = snprintf(line, sizeof(line), "%016llx\n",
                       static_cast<unsigned long long>(h));

    if (len > 0 && static_cast<size_t>(len) < sizeof(line)) {
        const char* p = line;
        size_t remaining = static_cast<size_t>(len);

        while (remaining > 0) {
            ssize_t written = write(fd, p, remaining);
            if (written < 0) {
                if (errno == EINTR) continue;

                LOGE("pipeline_control: write append failed for %s: %s",
                     path.c_str(), strerror(errno));
                break;
            }

            if (written == 0) {
                LOGE("pipeline_control: short append write for %s", path.c_str());
                break;
            }

            p += written;
            remaining -= static_cast<size_t>(written);
        }

        fsync(fd);
    }

    close(fd);
}

// ─────────────────────────────────────────────────────────────────────────────
// Public API
// ─────────────────────────────────────────────────────────────────────────────

void pipeline_control_on_device_created(const VkPhysicalDeviceProperties& props) {
    if (!g_settings.pipeline_blacklist &&
        g_settings.pipeline_warmup == WarmupMode::OFF) {
        std::lock_guard<std::mutex> lk(s_lock);
        s_blacklist.clear();
        s_warmup_seen.clear();
        s_blacklist_path.clear();
        s_warmup_path.clear();
        return;
    }

    ensure_dir(g_settings.pipeline_cache_dir);

    std::string blacklist_path = make_control_path(props, "bad_pipeline_hashes");
    std::string warmup_path = make_control_path(props, "warm_pipeline_hashes");

    std::lock_guard<std::mutex> lk(s_lock);

    s_blacklist_path = blacklist_path;
    s_warmup_path = warmup_path;

    load_set_locked(s_blacklist_path, s_blacklist);
    load_set_locked(s_warmup_path, s_warmup_seen);

    LOGI("pipeline_control: blacklist=%s entries=%zu warmup=%s warm_seen=%zu precreate=%d",
         s_blacklist_path.c_str(),
         s_blacklist.size(),
         perfcache_warmup_name(g_settings.pipeline_warmup),
         s_warmup_seen.size(),
         static_cast<int>(g_settings.pipeline_warmup_precreate));
}

bool pipeline_blacklist_should_skip_graphics(const VkGraphicsPipelineCreateInfo& ci) {
    if (!g_settings.pipeline_blacklist) return false;

    const uint64_t h = hash_graphics(ci);

    std::lock_guard<std::mutex> lk(s_lock);
    return s_blacklist.find(h) != s_blacklist.end();
}

bool pipeline_blacklist_should_skip_compute(const VkComputePipelineCreateInfo& ci) {
    if (!g_settings.pipeline_blacklist) return false;

    const uint64_t h = hash_compute(ci);

    std::lock_guard<std::mutex> lk(s_lock);
    return s_blacklist.find(h) != s_blacklist.end();
}

void pipeline_blacklist_mark_graphics(const VkGraphicsPipelineCreateInfo& ci) {
    if (!g_settings.pipeline_blacklist) return;

    const uint64_t h = hash_graphics(ci);

    std::lock_guard<std::mutex> lk(s_lock);
    if (s_blacklist.insert(h).second)
        append_set_locked(s_blacklist_path, h);
}

void pipeline_blacklist_mark_compute(const VkComputePipelineCreateInfo& ci) {
    if (!g_settings.pipeline_blacklist) return;

    const uint64_t h = hash_compute(ci);

    std::lock_guard<std::mutex> lk(s_lock);
    if (s_blacklist.insert(h).second)
        append_set_locked(s_blacklist_path, h);
}

bool pipeline_warmup_should_precreate_graphics(const VkGraphicsPipelineCreateInfo& ci) {
    if (!g_settings.pipeline_warmup_precreate ||
        g_settings.pipeline_warmup == WarmupMode::OFF) {
        return false;
    }

    const uint64_t h = hash_graphics(ci);

    std::lock_guard<std::mutex> lk(s_lock);
    return s_warmup_seen.find(h) == s_warmup_seen.end();
}

bool pipeline_warmup_should_precreate_compute(const VkComputePipelineCreateInfo& ci) {
    if (!g_settings.pipeline_warmup_precreate ||
        g_settings.pipeline_warmup == WarmupMode::OFF) {
        return false;
    }

    const uint64_t h = hash_compute(ci);

    std::lock_guard<std::mutex> lk(s_lock);
    return s_warmup_seen.find(h) == s_warmup_seen.end();
}

void pipeline_warmup_mark_graphics(const VkGraphicsPipelineCreateInfo& ci) {
    if (g_settings.pipeline_warmup == WarmupMode::OFF) return;

    const uint64_t h = hash_graphics(ci);

    std::lock_guard<std::mutex> lk(s_lock);
    if (s_warmup_seen.insert(h).second)
        append_set_locked(s_warmup_path, h);
}

void pipeline_warmup_mark_compute(const VkComputePipelineCreateInfo& ci) {
    if (g_settings.pipeline_warmup == WarmupMode::OFF) return;

    const uint64_t h = hash_compute(ci);

    std::lock_guard<std::mutex> lk(s_lock);
    if (s_warmup_seen.insert(h).second)
        append_set_locked(s_warmup_path, h);
}