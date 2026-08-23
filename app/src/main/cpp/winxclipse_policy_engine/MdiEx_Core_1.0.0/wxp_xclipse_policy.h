#pragma once

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum WinXclipsePolicyXclipseGpu {
    WXP_XCLIPSE_UNKNOWN = 0,
    WXP_XCLIPSE_530 = 530,
    WXP_XCLIPSE_540 = 540,
    WXP_XCLIPSE_550 = 550,
    WXP_XCLIPSE_920 = 920,
    WXP_XCLIPSE_940 = 940,
    WXP_XCLIPSE_950 = 950,
    WXP_XCLIPSE_960 = 960
} WinXclipsePolicyXclipseGpu;

typedef enum WinXclipsePolicyBandwidthPolicy {
    WXP_BANDWIDTH_UNKNOWN = 0,
    WXP_BANDWIDTH_LOW = 1,
    WXP_BANDWIDTH_BALANCED = 2,
    WXP_BANDWIDTH_HIGH = 3,
    WXP_BANDWIDTH_CRITICAL = 4
} WinXclipsePolicyBandwidthPolicy;

typedef enum WinXclipsePolicyThermalBudget {
    WXP_THERMAL_UNKNOWN = 0,
    WXP_THERMAL_TIGHT = 1,
    WXP_THERMAL_NORMAL = 2,
    WXP_THERMAL_RELAXED = 3
} WinXclipsePolicyThermalBudget;

typedef enum WinXclipsePolicySubmitPolicy {
    WXP_SUBMIT_UNKNOWN = 0,
    WXP_SUBMIT_SAFE = 1,
    WXP_SUBMIT_BATCH_PREFERRED = 2,
    WXP_SUBMIT_AGGRESSIVE_BATCH = 3
} WinXclipsePolicySubmitPolicy;

typedef struct WinXclipsePolicyXclipsePolicy {
    WinXclipsePolicyXclipseGpu gpu;
    int cu_estimate;
    int is_deca_cpu;
    int ram_gb;

    WinXclipsePolicyBandwidthPolicy bandwidth;
    WinXclipsePolicyThermalBudget thermal;
    WinXclipsePolicySubmitPolicy submit;

    int avoid_aggressive_upload;
    int avoid_async_compute;
    int prefer_timeline_semaphore;
    int prefer_fullscreen_quad_over_blit;
    int avoid_runtime_shader_warmup;
    int prefer_small_pipeline_cache;
    int prefer_push_constants;
    int avoid_descriptor_churn;
    int enable_active_controls_by_default;
    int enable_device_policy_audit;
    int enable_memory_tracking;
    int enable_blit_diagnostics;
    int enable_format_diagnostics;
    int enable_depth_format_substitution;

    int helix_pipeline_cache_mb;
    int helix_texture_cache_mb;
    int helix_upload_budget_mb_per_frame;
    int helix_warmup_pipeline_limit;
    int nramv_pressure_bias;
} WinXclipsePolicyXclipsePolicy;

int wxp_get_xclipse_policy(WinXclipsePolicyXclipsePolicy* out_policy);
const char* wxp_xclipse_gpu_name(WinXclipsePolicyXclipseGpu gpu);
const char* wxp_bandwidth_policy_name(WinXclipsePolicyBandwidthPolicy policy);
const char* wxp_thermal_budget_name(WinXclipsePolicyThermalBudget budget);
const char* wxp_submit_policy_name(WinXclipsePolicySubmitPolicy policy);
int wxp_xclipse_policy_summary(char* out, size_t out_size);

#ifdef __cplusplus
}
#endif
