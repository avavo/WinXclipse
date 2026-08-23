#pragma once
#ifdef __cplusplus
extern "C" {
#endif
#define MDIEX_SURFACE_PACING_UNKNOWN  0
#define MDIEX_SURFACE_PACING_STABLE   1
#define MDIEX_SURFACE_PACING_UNSTABLE 2
#define MDIEX_SURFACE_PACING_BAD      3

int mdiex_surface_init(void);
void mdiex_surface_record_frame(unsigned int width, unsigned int height);
unsigned long long mdiex_surface_get_frame_count(void);
unsigned long long mdiex_surface_get_avg_frame_delta_ns(void);
unsigned long long mdiex_surface_get_jitter_ns(void);
int mdiex_surface_get_frame_pacing_state(void);
void mdiex_surface_get_last_extent(unsigned int* width, unsigned int* height);
#ifdef __cplusplus
}
#endif
