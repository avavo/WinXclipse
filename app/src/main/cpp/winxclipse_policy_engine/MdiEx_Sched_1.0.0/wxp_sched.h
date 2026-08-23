#pragma once
#ifdef __cplusplus
extern "C" {
#endif
int wxp_sched_init(void);
int wxp_sched_apply_current_thread(const char* role);
#ifdef __cplusplus
}
#endif
