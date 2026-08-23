#include "mdiex_xclipse_policy.h"
#include "mdiex_core.h"

#include <stdio.h>
#include <string.h>
#include <ctype.h>

static int contains_ci(const char* s, const char* needle) {
    if (!s || !needle || !*needle) return 0;
    const size_t nlen = strlen(needle);
    for (const char* p = s; *p; ++p) {
        size_t i = 0;
        while (i < nlen && p[i] &&
               tolower((unsigned char)p[i]) == tolower((unsigned char)needle[i])) {
            ++i;
        }
        if (i == nlen) return 1;
    }
    return 0;
}

static MdiExXclipseGpu gpu_from_name(const char* gpu) {
    if (contains_ci(gpu, "530")) return MDIEX_XCLIPSE_530;
    if (contains_ci(gpu, "540")) return MDIEX_XCLIPSE_540;
    if (contains_ci(gpu, "550")) return MDIEX_XCLIPSE_550;
    if (contains_ci(gpu, "920")) return MDIEX_XCLIPSE_920;
    if (contains_ci(gpu, "940")) return MDIEX_XCLIPSE_940;
    if (contains_ci(gpu, "950")) return MDIEX_XCLIPSE_950;
    if (contains_ci(gpu, "960")) return MDIEX_XCLIPSE_960;
    return MDIEX_XCLIPSE_UNKNOWN;
}

static void policy_defaults(MdiExXclipsePolicy* p) {
    memset(p, 0, sizeof(*p));
    p->gpu = MDIEX_XCLIPSE_UNKNOWN;
    p->bandwidth = MDIEX_BANDWIDTH_UNKNOWN;
    p->thermal = MDIEX_THERMAL_UNKNOWN;
    p->submit = MDIEX_SUBMIT_SAFE;
    p->avoid_aggressive_upload = 1;
    p->avoid_async_compute = 1;
    p->prefer_timeline_semaphore = 1;
    p->prefer_fullscreen_quad_over_blit = 0;
    p->avoid_runtime_shader_warmup = 1;
    p->prefer_small_pipeline_cache = 1;
    p->prefer_push_constants = 1;
    p->avoid_descriptor_churn = 1;
    p->enable_active_controls_by_default = 1;
    p->enable_device_policy_audit = 1;
    p->enable_memory_tracking = 1;
    p->enable_blit_diagnostics = 1;
    p->enable_format_diagnostics = 1;
    p->enable_depth_format_substitution = 1;
    p->helix_pipeline_cache_mb = 64;
    p->helix_texture_cache_mb = 96;
    p->helix_upload_budget_mb_per_frame = 16;
    p->helix_warmup_pipeline_limit = 0;
    p->nramv_pressure_bias = 0;
}

static void apply_midrange_safe(MdiExXclipsePolicy* p, int cu_estimate) {
    p->cu_estimate = cu_estimate;
    p->bandwidth = MDIEX_BANDWIDTH_LOW;
    p->thermal = MDIEX_THERMAL_TIGHT;
    p->submit = MDIEX_SUBMIT_BATCH_PREFERRED;
    p->avoid_aggressive_upload = 1;
    p->avoid_async_compute = 1;
    p->prefer_timeline_semaphore = 1;
    p->prefer_fullscreen_quad_over_blit = 1;
    p->avoid_runtime_shader_warmup = 1;
    p->prefer_small_pipeline_cache = 1;
    p->prefer_push_constants = 1;
    p->avoid_descriptor_churn = 1;
    p->enable_active_controls_by_default = 1;
    p->enable_device_policy_audit = 1;
    p->enable_memory_tracking = 1;
    p->enable_blit_diagnostics = 1;
    p->enable_format_diagnostics = 1;
    p->enable_depth_format_substitution = 1;
    p->helix_pipeline_cache_mb = 48;
    p->helix_texture_cache_mb = 64;
    p->helix_upload_budget_mb_per_frame = 8;
    p->helix_warmup_pipeline_limit = 0;
    p->nramv_pressure_bias = 2;
}

static void apply_flagship_safe(MdiExXclipsePolicy* p, int cu_estimate) {
    p->cu_estimate = cu_estimate;
    p->bandwidth = MDIEX_BANDWIDTH_BALANCED;
    p->thermal = MDIEX_THERMAL_TIGHT;
    p->submit = MDIEX_SUBMIT_BATCH_PREFERRED;
    p->avoid_aggressive_upload = 1;
    p->avoid_async_compute = 1;
    p->prefer_timeline_semaphore = 1;
    p->prefer_fullscreen_quad_over_blit = 1;
    p->avoid_runtime_shader_warmup = 1;
    p->prefer_small_pipeline_cache = 1;
    p->prefer_push_constants = 1;
    p->avoid_descriptor_churn = 1;
    p->enable_active_controls_by_default = 1;
    p->enable_device_policy_audit = 1;
    p->enable_memory_tracking = 1;
    p->enable_blit_diagnostics = 1;
    p->enable_format_diagnostics = 1;
    p->enable_depth_format_substitution = 1;
    p->helix_pipeline_cache_mb = 64;
    p->helix_texture_cache_mb = 96;
    p->helix_upload_budget_mb_per_frame = 12;
    p->helix_warmup_pipeline_limit = 0;
    p->nramv_pressure_bias = 2;
}

static void apply_rdna3_balanced(MdiExXclipsePolicy* p, int ram_gb) {
    p->cu_estimate = 12;
    p->bandwidth = MDIEX_BANDWIDTH_BALANCED;
    p->thermal = MDIEX_THERMAL_NORMAL;
    p->submit = MDIEX_SUBMIT_BATCH_PREFERRED;
    p->avoid_aggressive_upload = 1;
    p->avoid_async_compute = 0;
    p->prefer_timeline_semaphore = 1;
    p->prefer_fullscreen_quad_over_blit = 1;
    p->avoid_runtime_shader_warmup = 0;
    const int low_or_unknown_ram = (ram_gb < 12) ? 1 : 0;

    p->prefer_small_pipeline_cache = low_or_unknown_ram;
    p->prefer_push_constants = 1;
    p->avoid_descriptor_churn = 1;
    p->enable_active_controls_by_default = 1;
    p->enable_device_policy_audit = 1;
    p->enable_memory_tracking = 1;
    p->enable_blit_diagnostics = 1;
    p->enable_format_diagnostics = 1;
    p->enable_depth_format_substitution = 1;
    p->helix_pipeline_cache_mb = low_or_unknown_ram ? 96 : 128;
    p->helix_texture_cache_mb = low_or_unknown_ram ? 128 : 192;
    p->helix_upload_budget_mb_per_frame = low_or_unknown_ram ? 16 : 24;
    p->helix_warmup_pipeline_limit = low_or_unknown_ram ? 8 : 16;
    p->nramv_pressure_bias = low_or_unknown_ram ? 2 : 1;
}

static void apply_next_flagship(MdiExXclipsePolicy* p, int cu_estimate, int ram_gb) {
    p->cu_estimate = cu_estimate;
    const int low_or_unknown_ram = (ram_gb < 12) ? 1 : 0;
    p->bandwidth = low_or_unknown_ram ? MDIEX_BANDWIDTH_BALANCED : MDIEX_BANDWIDTH_HIGH;
    p->thermal = MDIEX_THERMAL_NORMAL;
    p->submit = MDIEX_SUBMIT_BATCH_PREFERRED;
    p->avoid_aggressive_upload = 1;
    p->avoid_async_compute = 0;
    p->prefer_timeline_semaphore = 1;
    p->prefer_fullscreen_quad_over_blit = 1;
    p->avoid_runtime_shader_warmup = 0;
    p->prefer_small_pipeline_cache = low_or_unknown_ram;
    p->prefer_push_constants = 1;
    p->avoid_descriptor_churn = 1;
    p->enable_active_controls_by_default = 1;
    p->enable_device_policy_audit = 1;
    p->enable_memory_tracking = 1;
    p->enable_blit_diagnostics = 1;
    p->enable_format_diagnostics = 1;
    p->enable_depth_format_substitution = 1;
    p->helix_pipeline_cache_mb = low_or_unknown_ram ? 128 : 160;
    p->helix_texture_cache_mb = low_or_unknown_ram ? 160 : 224;
    p->helix_upload_budget_mb_per_frame = low_or_unknown_ram ? 16 : 24;
    p->helix_warmup_pipeline_limit = low_or_unknown_ram ? 12 : 24;
    p->nramv_pressure_bias = low_or_unknown_ram ? 2 : 1;
}

int mdiex_get_xclipse_policy(MdiExXclipsePolicy* out_policy) {
    if (!out_policy) return 0;

    const MdiExDeviceInfo* info = mdiex_get_device_info();
    MdiExXclipsePolicy p;
    policy_defaults(&p);

    p.gpu = gpu_from_name(info ? info->gpu : NULL);
    p.ram_gb = info ? info->ram_gb : 0;
    p.is_deca_cpu = (info && info->cpu_class == MDIEX_CPU_DECA) ? 1 : 0;

    switch (p.gpu) {
        case MDIEX_XCLIPSE_530:
            apply_midrange_safe(&p, 4);
            break;
        case MDIEX_XCLIPSE_540:
            apply_midrange_safe(&p, 5);
            p.helix_pipeline_cache_mb = 56;
            p.helix_texture_cache_mb = 80;
            break;
        case MDIEX_XCLIPSE_550:
            apply_midrange_safe(&p, 6);
            p.bandwidth = MDIEX_BANDWIDTH_BALANCED;
            p.helix_pipeline_cache_mb = 64;
            p.helix_texture_cache_mb = 96;
            p.helix_upload_budget_mb_per_frame = 12;
            break;
        case MDIEX_XCLIPSE_920:
            apply_flagship_safe(&p, 6);
            break;
        case MDIEX_XCLIPSE_940:
            apply_rdna3_balanced(&p, p.ram_gb);
            break;
        case MDIEX_XCLIPSE_950:
            apply_next_flagship(&p, 12, p.ram_gb);
            p.thermal = MDIEX_THERMAL_NORMAL;
            break;
        case MDIEX_XCLIPSE_960:
            apply_next_flagship(&p, 16, p.ram_gb);
            if (p.ram_gb >= 12) {
                p.thermal = MDIEX_THERMAL_RELAXED;
                p.submit = MDIEX_SUBMIT_AGGRESSIVE_BATCH;
                p.helix_pipeline_cache_mb = 192;
                p.helix_texture_cache_mb = 256;
                p.helix_warmup_pipeline_limit = 32;
            }
            break;
        case MDIEX_XCLIPSE_UNKNOWN:
        default:
            break;
    }

    *out_policy = p;
    return 1;
}

const char* mdiex_xclipse_gpu_name(MdiExXclipseGpu gpu) {
    switch (gpu) {
        case MDIEX_XCLIPSE_530: return "xclipse530";
        case MDIEX_XCLIPSE_540: return "xclipse540";
        case MDIEX_XCLIPSE_550: return "xclipse550";
        case MDIEX_XCLIPSE_920: return "xclipse920";
        case MDIEX_XCLIPSE_940: return "xclipse940";
        case MDIEX_XCLIPSE_950: return "xclipse950";
        case MDIEX_XCLIPSE_960: return "xclipse960";
        case MDIEX_XCLIPSE_UNKNOWN:
        default: return "unknown";
    }
}

const char* mdiex_bandwidth_policy_name(MdiExBandwidthPolicy policy) {
    switch (policy) {
        case MDIEX_BANDWIDTH_LOW: return "low";
        case MDIEX_BANDWIDTH_BALANCED: return "balanced";
        case MDIEX_BANDWIDTH_HIGH: return "high";
        case MDIEX_BANDWIDTH_CRITICAL: return "critical";
        case MDIEX_BANDWIDTH_UNKNOWN:
        default: return "unknown";
    }
}

const char* mdiex_thermal_budget_name(MdiExThermalBudget budget) {
    switch (budget) {
        case MDIEX_THERMAL_TIGHT: return "tight";
        case MDIEX_THERMAL_NORMAL: return "normal";
        case MDIEX_THERMAL_RELAXED: return "relaxed";
        case MDIEX_THERMAL_UNKNOWN:
        default: return "unknown";
    }
}

const char* mdiex_submit_policy_name(MdiExSubmitPolicy policy) {
    switch (policy) {
        case MDIEX_SUBMIT_SAFE: return "safe";
        case MDIEX_SUBMIT_BATCH_PREFERRED: return "batch_preferred";
        case MDIEX_SUBMIT_AGGRESSIVE_BATCH: return "aggressive_batch";
        case MDIEX_SUBMIT_UNKNOWN:
        default: return "unknown";
    }
}

int mdiex_xclipse_policy_summary(char* out, size_t out_size) {
    if (!out || out_size == 0u) return 0;

    MdiExXclipsePolicy p;
    if (!mdiex_get_xclipse_policy(&p)) {
        out[0] = 0;
        return 0;
    }

    int n = snprintf(out, out_size,
                     "gpu=%s cu=%d deca=%d ram=%d bandwidth=%s thermal=%s submit=%s upload_safe=%d async_avoid=%d warmup_limit=%d pipe_cache_mb=%d tex_cache_mb=%d upload_budget_mb=%d nramv_bias=%d",
                     mdiex_xclipse_gpu_name(p.gpu),
                     p.cu_estimate,
                     p.is_deca_cpu,
                     p.ram_gb,
                     mdiex_bandwidth_policy_name(p.bandwidth),
                     mdiex_thermal_budget_name(p.thermal),
                     mdiex_submit_policy_name(p.submit),
                     p.avoid_aggressive_upload,
                     p.avoid_async_compute,
                     p.helix_warmup_pipeline_limit,
                     p.helix_pipeline_cache_mb,
                     p.helix_texture_cache_mb,
                     p.helix_upload_budget_mb_per_frame,
                     p.nramv_pressure_bias);

    if (n < 0) {
        out[0] = 0;
        return 0;
    }
    if ((size_t)n >= out_size) return 0;
    return 1;
}
