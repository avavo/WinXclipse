#pragma once

#include "mdiex_xclipse_policy.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum MdiExCpuClass {
    MDIEX_CPU_UNKNOWN = 0,
    MDIEX_CPU_OCTA = 8,
    MDIEX_CPU_DECA = 10
} MdiExCpuClass;

typedef struct MdiExDeviceInfo {
    const char* soc;
    const char* gpu;
    const char* gpu_arch;
    const char* cpu_topology;
    int cpu_class;
    int ram_gb;
    const char* ram_class;
    const char* package_profile;
} MdiExDeviceInfo;

int mdiex_init_auto(void);
int mdiex_init_from_meta(const char* meta_path);
int mdiex_init_from_env_or_auto(void);
const MdiExDeviceInfo* mdiex_get_device_info(void);

const char* mdiex_get_soc_name(void);
const char* mdiex_get_gpu_name(void);
const char* mdiex_get_gpu_arch(void);
const char* mdiex_get_cpu_topology(void);
int mdiex_get_cpu_class(void);
int mdiex_get_ram_gb(void);
const char* mdiex_get_ram_class(void);
const char* mdiex_get_profile_name(void);

void mdiex_log_line(const char* tag, const char* message);

void mdiex_send_hint(int hint);
int mdiex_get_hint_state(int hint);
int mdiex_consume_hint(int hint);
void mdiex_clear_hint(int hint);

#define MDIEX_HINT_PIPELINE_COMPILE       1
#define MDIEX_HINT_GPU_UPLOAD_BURST       2
#define MDIEX_HINT_TEXTURE_CACHE_PRESSURE 3
#define MDIEX_HINT_EMULATOR_MODE          4
#define MDIEX_HINT_THERMAL_PRESSURE       5
#define MDIEX_HINT_MEMORY_PRESSURE        6
#define MDIEX_HINT_SUBMIT_BURST           7
#define MDIEX_HINT_BARRIER_WIDE           8
#define MDIEX_HINT_RENDERPASS_BANDWIDTH   9
#define MDIEX_HINT_DESCRIPTOR_CHURN       10
#define MDIEX_HINT_GPU_PAGE_PRESSURE      11
#define MDIEX_HINT_ROBUST_BUFFER_ACCESS_ON 12
#define MDIEX_HINT_TIMELINE_SEMAPHORE_AVAILABLE 13
#define MDIEX_HINT_SYNC2_ENABLED          14
#define MDIEX_HINT_DESCRIPTOR_INDEXING_ENABLED 15
#define MDIEX_HINT_BLIT_HEAVY             16
#define MDIEX_HINT_GPU_MEMORY_FRAGMENTATION 17
#define MDIEX_HINT_GPU_SMALL_ALLOC_CHURN  18
#define MDIEX_HINT_GPU_HEAP_PRESSURE      19
#define MDIEX_HINT_FORMAT_QUERY           20
#define MDIEX_HINT_DEPTH_FORMAT_SUBSTITUTED 21
#define MDIEX_HINT_DEVICE_POLICY_MUTATION 22
#define MDIEX_HINT_DIRECT_HELIX_PRESSURE  23
#define MDIEX_HINT_SUBMIT_COALESCED       24
#define MDIEX_HINT_DEPTH_SUBSTITUTION_SKIPPED 25
#define MDIEX_HINT_BLIT_REPLACEMENT_UNSAFE 26

#define MDIEX_HINT_MAX                    64

#ifdef __cplusplus
}
#endif
