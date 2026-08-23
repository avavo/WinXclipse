#pragma once
#ifdef __cplusplus
extern "C" {
#endif
int wxp_ahb_init(void);
void wxp_ahb_record_allocate(unsigned int width, unsigned int height, unsigned int layers);
void wxp_ahb_record_release(void);
unsigned long long wxp_ahb_get_live_estimate(void);
#ifdef __cplusplus
}
#endif
