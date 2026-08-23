#pragma once
#ifdef __cplusplus
extern "C" {
#endif
#define WXP_SURFACE_PACING_UNKNOWN  0
#define WXP_SURFACE_PACING_STABLE   1
#define WXP_SURFACE_PACING_UNSTABLE 2
#define WXP_SURFACE_PACING_BAD      3

int wxp_surface_init(void);
void wxp_surface_record_frame(unsigned int width, unsigned int height);
unsigned long long wxp_surface_get_frame_count(void);
unsigned long long wxp_surface_get_avg_frame_delta_ns(void);
unsigned long long wxp_surface_get_jitter_ns(void);
int wxp_surface_get_frame_pacing_state(void);
void wxp_surface_get_last_extent(unsigned int* width, unsigned int* height);
#ifdef __cplusplus
}
#endif
