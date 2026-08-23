#pragma once
#ifdef __cplusplus
extern "C" {
#endif
int mdiex_sched_init(void);
int mdiex_sched_apply_current_thread(const char* role);
#ifdef __cplusplus
}
#endif
