#pragma once
#include <android/log.h>
#include "layer_settings.h"

#define PERFCACHE_TAG "VkLayer_PerfCache"

// log_level: 0=off  1=error  2=info  3=verbose
#define LOGE(...) do { if (g_settings.log_level >= 1) __android_log_print(ANDROID_LOG_ERROR,   PERFCACHE_TAG, __VA_ARGS__); } while(0)
#define LOGI(...) do { if (g_settings.log_level >= 2) __android_log_print(ANDROID_LOG_INFO,    PERFCACHE_TAG, __VA_ARGS__); } while(0)
#define LOGV(...) do { if (g_settings.log_level >= 3) __android_log_print(ANDROID_LOG_VERBOSE, PERFCACHE_TAG, __VA_ARGS__); } while(0)
