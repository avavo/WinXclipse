#pragma once

#include "wxp_xclipse_policy.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum WinXclipsePolicyCpuClass {
    WXP_CPU_UNKNOWN = 0,
    WXP_CPU_OCTA = 8,
    WXP_CPU_DECA = 10
} WinXclipsePolicyCpuClass;

typedef struct WinXclipsePolicyDeviceInfo {
    const char* soc;
    const char* gpu;
    const char* gpu_arch;
    const char* cpu_topology;
    int cpu_class;
    int ram_gb;
    const char* ram_class;
    const char* package_profile;
} WinXclipsePolicyDeviceInfo;

int wxp_init_auto(void);
int wxp_init_from_meta(const char* meta_path);
int wxp_init_from_env_or_auto(void);
const WinXclipsePolicyDeviceInfo* wxp_get_device_info(void);

const char* wxp_get_soc_name(void);
const char* wxp_get_gpu_name(void);
const char* wxp_get_gpu_arch(void);
const char* wxp_get_cpu_topology(void);
int wxp_get_cpu_class(void);
int wxp_get_ram_gb(void);
const char* wxp_get_ram_class(void);
const char* wxp_get_profile_name(void);

void wxp_log_line(const char* tag, const char* message);

void wxp_send_hint(int hint);
int wxp_get_hint_state(int hint);
int wxp_consume_hint(int hint);
void wxp_clear_hint(int hint);

#define WXP_HINT_PIPELINE_COMPILE       1
#define WXP_HINT_GPU_UPLOAD_BURST       2
#define WXP_HINT_TEXTURE_CACHE_PRESSURE 3
#define WXP_HINT_EMULATOR_MODE          4
#define WXP_HINT_THERMAL_PRESSURE       5
#define WXP_HINT_MEMORY_PRESSURE        6
#define WXP_HINT_SUBMIT_BURST           7
#define WXP_HINT_BARRIER_WIDE           8
#define WXP_HINT_RENDERPASS_BANDWIDTH   9
#define WXP_HINT_DESCRIPTOR_CHURN       10
#define WXP_HINT_GPU_PAGE_PRESSURE      11
#define WXP_HINT_ROBUST_BUFFER_ACCESS_ON 12
#define WXP_HINT_TIMELINE_SEMAPHORE_AVAILABLE 13
#define WXP_HINT_SYNC2_ENABLED          14
#define WXP_HINT_DESCRIPTOR_INDEXING_ENABLED 15
#define WXP_HINT_BLIT_HEAVY             16
#define WXP_HINT_GPU_MEMORY_FRAGMENTATION 17
#define WXP_HINT_GPU_SMALL_ALLOC_CHURN  18
#define WXP_HINT_GPU_HEAP_PRESSURE      19
#define WXP_HINT_FORMAT_QUERY           20
#define WXP_HINT_DEPTH_FORMAT_SUBSTITUTED 21
#define WXP_HINT_DEVICE_POLICY_MUTATION 22
#define WXP_HINT_DIRECT_HELIX_PRESSURE  23
#define WXP_HINT_SUBMIT_COALESCED       24
#define WXP_HINT_DEPTH_SUBSTITUTION_SKIPPED 25
#define WXP_HINT_BLIT_REPLACEMENT_UNSAFE 26

#define WXP_HINT_MAX                    64

#ifdef __cplusplus
}
#endif
