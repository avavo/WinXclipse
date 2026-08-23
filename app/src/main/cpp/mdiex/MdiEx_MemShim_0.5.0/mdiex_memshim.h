#pragma once
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
int mdiex_memshim_init(void);
void mdiex_memshim_record_alloc(size_t size);
unsigned long long mdiex_memshim_get_large_alloc_count(void);
unsigned long long mdiex_memshim_get_malloc_call_count(void);
unsigned long long mdiex_memshim_get_mmap_call_count(void);
#ifdef __cplusplus
}
#endif
