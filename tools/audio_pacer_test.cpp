// Execute the production consumer loop with deterministic condition/clock events.
using size_t = unsigned long long;
using uint8_t = unsigned char;
using uint64_t = unsigned long long;
using pthread_t = void*;
using pthread_mutex_t = int;
using pthread_cond_t = int;
struct timespec { long long tv_sec, tv_nsec; };
#define CLOCK_MONOTONIC 0
#define NULL 0
#define LOGI(...) ((void)0)
static int locks, waits, sleeps, scenario;
static long long now, slept;
static int pthread_mutex_lock(pthread_mutex_t*);
static int pthread_mutex_unlock(pthread_mutex_t*) { return 0; }
static int pthread_cond_wait(pthread_cond_t*, pthread_mutex_t*);
static int pthread_cond_broadcast(pthread_cond_t*) { return 0; }
static int clock_gettime(int, timespec* time) {
    time->tv_sec=now/1000000000;time->tv_nsec=now%1000000000;return 0;
}
static int nanosleep(const timespec*,timespec*);
#include "../app/src/main/cpp/winlator/audio_pacer.h"
static PacerContext context;
static int pthread_mutex_lock(pthread_mutex_t*) {
    if(++locks>20)context.running=0; // Bound a regression instead of hanging the runner.
    return 0;
}
static int pthread_cond_wait(pthread_cond_t*,pthread_mutex_t*) {
    ++waits;
    if(scenario==1) { if(waits==2)context.running=1; }
    else if(scenario==2) { now=5000000000;context.available_bytes=1920; }
    else context.running=0;
    return 0;
}
static int nanosleep(const timespec* duration,timespec*) {
    ++sleeps;slept=duration->tv_sec*1000000000+duration->tv_nsec;now+=slept;
    context.running=scenario==4?2:0;return 0;
}
extern "C" __declspec(dllexport) int runAudioCase(int index) {
    scenario=index;locks=waits=sleeps=0;now=slept=0;
    context.running=(index==0||index==1)?2:index==5?0:1;
    context.sample_rate=48000;context.frame_size_bytes=4;
    context.capacity_bytes=8192;context.read_pos_bytes=0;
    context.available_bytes=(index==2||index==3)?0:3840;
    pacer_consumer_thread_func(&context);
    if(locks>3)return 1;
    if(index==0)return waits!=1||sleeps!=0||context.available_bytes!=3840;
    if(index==1)return waits!=2||sleeps!=1||slept!=10000000||context.available_bytes!=1920;
    if(index==2)return waits!=1||sleeps!=1||slept!=10000000||context.available_bytes!=0;
    if(index==3)return waits!=1||sleeps!=0||context.available_bytes!=0;
    if(index==4)return waits!=1||sleeps!=1||context.available_bytes!=1920;
    if(index==5)return waits!=0||sleeps!=0||locks!=1;
    return 99;
}
