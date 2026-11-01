// SPDX-License-Identifier: MIT
#pragma once
namespace displayx {
inline bool canScanout(unsigned windows,unsigned cursors,unsigned effects,bool generated,
                       bool ahb,bool swap,const float* rect,const float* content) {
    if(windows!=1||cursors||effects||generated||!ahb||swap)return false;
    float destination[]{(rect[0]+1)*.5f,(rect[1]+1)*.5f,(rect[2]-rect[0])*.5f,(rect[3]-rect[1])*.5f};
    for(unsigned n=0;n<4;n++) {
        float difference=destination[n]-content[n];
        // This also rejects NaNs. SurfaceControl must not bypass a cropped or
        // magnified viewport, an effect, an emulated cursor or another window.
        if(!(difference<.0001f&&difference>-.0001f))return false;
    }
    return destination[0]>=0&&destination[1]>=0&&destination[2]>0&&destination[3]>0
        &&destination[0]+destination[2]<=1.0001f&&destination[1]+destination[3]<=1.0001f;
}
}
