#pragma once

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum MdiExXclipseGpu {
    MDIEX_XCLIPSE_UNKNOWN = 0,
    MDIEX_XCLIPSE_530 = 530,
    MDIEX_XCLIPSE_540 = 540,
    MDIEX_XCLIPSE_550 = 550,
    MDIEX_XCLIPSE_920 = 920,
    MDIEX_XCLIPSE_940 = 940,
    MDIEX_XCLIPSE_950 = 950,
    MDIEX_XCLIPSE_960 = 960
} MdiExXclipseGpu;

typedef enum MdiExBandwidthPolicy {
    MDIEX_BANDWIDTH_UNKNOWN = 0,
    MDIEX_BANDWIDTH_LOW = 1,
    MDIEX_BANDWIDTH_BALANCED = 2,
    MDIEX_BANDWIDTH_HIGH = 3,
    MDIEX_BANDWIDTH_CRITICAL = 4
} MdiExBandwidthPolicy;

typedef enum MdiExThermalBudget {
    MDIEX_THERMAL_UNKNOWN = 0,
    MDIEX_THERMAL_TIGHT = 1,
    MDIEX_THERMAL_NORMAL = 2,
    MDIEX_THERMAL_RELAXED = 3
} MdiExThermalBudget;

typedef enum MdiExSubmitPolicy {
    MDIEX_SUBMIT_UNKNOWN = 0,
    MDIEX_SUBMIT_SAFE = 1,
    MDIEX_SUBMIT_BATCH_PREFERRED = 2,
    MDIEX_SUBMIT_AGGRESSIVE_BATCH = 3
} MdiExSubmitPolicy;

typedef struct MdiExXclipsePolicy {
    MdiExXclipseGpu gpu;
    int cu_estimate;
    int is_deca_cpu;
    int ram_gb;

    MdiExBandwidthPolicy bandwidth;
    MdiExThermalBudget thermal;
    MdiExSubmitPolicy submit;

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
} MdiExXclipsePolicy;

int mdiex_get_xclipse_policy(MdiExXclipsePolicy* out_policy);
const char* mdiex_xclipse_gpu_name(MdiExXclipseGpu gpu);
const char* mdiex_bandwidth_policy_name(MdiExBandwidthPolicy policy);
const char* mdiex_thermal_budget_name(MdiExThermalBudget budget);
const char* mdiex_submit_policy_name(MdiExSubmitPolicy policy);
int mdiex_xclipse_policy_summary(char* out, size_t out_size);

#ifdef __cplusplus
}
#endif
