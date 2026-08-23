#include "mdiex_sched.h"
#include "mdiex_core.h"
#include "mdiex_xclipse_policy.h"
#include <ctype.h>
#include <errno.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <sys/resource.h>
#ifdef __linux__
#include <sched.h>
#include <unistd.h>
#endif

static atomic_int g_sched_ready = 0;

static const char* cpu_class_name(int cpu_class) {
    if (cpu_class == MDIEX_CPU_DECA) return "deca";
    if (cpu_class == MDIEX_CPU_OCTA) return "octa";
    return "unknown";
}

static int contains_token_ci(const char* haystack, const char* needle) {
    if (!haystack || !needle || !*needle) return 0;
    const size_t nlen = strlen(needle);
    for (const char* p = haystack; *p; ++p) {
        size_t i = 0;
        while (i < nlen && p[i] &&
               (unsigned char)tolower((unsigned char)p[i]) ==
               (unsigned char)tolower((unsigned char)needle[i])) {
            ++i;
        }
        if (i == nlen) return 1;
    }
    return 0;
}


static int clamp_nice_value(int value) {
    if (value < -20) return -20;
    if (value > 19) return 19;
    return value;
}

static int apply_policy_nice_bias(int target_nice, const char* role) {
    MdiExXclipsePolicy policy;
    if (!mdiex_get_xclipse_policy(&policy)) return target_nice;

    const int is_latency_role = contains_token_ci(role, "render") ||
                                contains_token_ci(role, "game") ||
                                contains_token_ci(role, "main") ||
                                contains_token_ci(role, "audio");
    const int is_worker_role = contains_token_ci(role, "cache") ||
                               contains_token_ci(role, "io") ||
                               contains_token_ci(role, "worker") ||
                               contains_token_ci(role, "background") ||
                               contains_token_ci(role, "bg");

    int adjusted = target_nice;
    if (is_worker_role) {
        if (policy.thermal == MDIEX_THERMAL_TIGHT) adjusted += 1;
        if (policy.bandwidth == MDIEX_BANDWIDTH_LOW ||
            policy.bandwidth == MDIEX_BANDWIDTH_CRITICAL) adjusted += 1;
        if (policy.ram_gb > 0 && policy.ram_gb < 8) adjusted += 1;
        if (policy.avoid_runtime_shader_warmup || policy.avoid_aggressive_upload) adjusted += 1;
        if (policy.thermal == MDIEX_THERMAL_RELAXED && adjusted > 0) adjusted -= 1;
    }

    /* Latency-critical threads stay neutral unless the policy is extremely
     * conservative. Demoting RenderThread blindly is how you get stutter with
     * a certificate of authenticity. */
    if (is_latency_role && policy.thermal == MDIEX_THERMAL_TIGHT &&
        policy.bandwidth == MDIEX_BANDWIDTH_LOW && adjusted > 0) {
        adjusted -= 1;
    }

    return clamp_nice_value(adjusted);
}

static int apply_nice_checked(int target_nice, const char* role, int cpu_class) {
    errno = 0;
    if (setpriority(PRIO_PROCESS, 0, target_nice) != 0) {
        char msg[256];
        snprintf(msg, sizeof(msg),
                 "setpriority(%d) failed for role=%s cpu_class=%s errno=%d (%s)",
                 target_nice,
                 role ? role : "default",
                 cpu_class_name(cpu_class),
                 errno,
                 strerror(errno));
        mdiex_log_line("MdiExSched", msg);
        return 0;
    }
    return 1;
}

int mdiex_sched_apply_current_thread(const char* role) {
    mdiex_init_from_env_or_auto();
    int cpu_class = mdiex_get_cpu_class();
    int target_nice = 0;

    /* Priority order matters: RenderThread/background-cache should not be
     * demoted just because the app name contains "background" somewhere. */
    if (contains_token_ci(role, "render") || contains_token_ci(role, "game") ||
        contains_token_ci(role, "main") || contains_token_ci(role, "audio")) {
        target_nice = 0;
    } else if (contains_token_ci(role, "cache") || contains_token_ci(role, "io") ||
               contains_token_ci(role, "worker")) {
        target_nice = (cpu_class == MDIEX_CPU_DECA) ? 3 : 2;
    } else if (contains_token_ci(role, "background") || contains_token_ci(role, "bg")) {
        target_nice = (cpu_class == MDIEX_CPU_DECA) ? 6 : 5;
    } else {
        return 1;
    }

    int base_nice = target_nice;
    target_nice = apply_policy_nice_bias(target_nice, role);

    char msg[256];
    snprintf(msg, sizeof(msg), "role=%s cpu_class=%s base_nice=%d policy_nice=%d",
             role ? role : "default", cpu_class_name(cpu_class), base_nice, target_nice);
    mdiex_log_line("MdiExSched", msg);

    return apply_nice_checked(target_nice, role, cpu_class);
}

int mdiex_sched_init(void) {
    if (atomic_exchange(&g_sched_ready, 1)) return 1;
    mdiex_init_from_env_or_auto();
    mdiex_log_line("MdiExSched", "sched initialized in conservative checked mode");
    return 1;
}

__attribute__((constructor)) static void mdiex_sched_ctor(void) { mdiex_sched_init(); }
