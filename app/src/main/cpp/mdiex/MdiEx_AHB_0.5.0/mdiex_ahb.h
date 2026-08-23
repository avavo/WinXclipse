#pragma once
#ifdef __cplusplus
extern "C" {
#endif
int mdiex_ahb_init(void);
void mdiex_ahb_record_allocate(unsigned int width, unsigned int height, unsigned int layers);
void mdiex_ahb_record_release(void);
unsigned long long mdiex_ahb_get_live_estimate(void);
#ifdef __cplusplus
}
#endif
