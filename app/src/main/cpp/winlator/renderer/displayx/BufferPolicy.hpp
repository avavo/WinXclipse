// SPDX-License-Identifier: MIT
#pragma once
#include <stdint.h>
namespace displayx {
// Samsung's GPU-only gralloc layout is private to its driver. Request a CPU-
// readable allocation so both the guest ICD and Android compositor see the
// same linear storage. This does NOT map/copy pixels through the CPU.
// RADV's MESA_GRALLOC_LINEAR workaround cannot affect our direct AHB allocator.
inline bool cpuReadableBuffer(uint32_t vendor, const char* name) {
    if(vendor==0x144d || vendor==0x1002) return true; // Samsung / AMD on Android
    if(!name) return false;
    const char* target="xclipse";
    for(const char* start=name;*start;start++) {
        const char* a=start;const char* b=target;
        while(*a && *b && ((*a>='A'&&*a<='Z')?*a-'A'+'a':*a)==*b){a++;b++;}
        if(!*b)return true;
    }
    return false;
}
}
