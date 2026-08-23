#pragma once
#include <cstdint>

struct PerfMetricsSnapshot {
    uint64_t graphics_pipeline_calls = 0;
    uint64_t compute_pipeline_calls = 0;
    uint64_t graphics_pipeline_fallbacks = 0;
    uint64_t graphics_pipeline_create_time_us = 0;
    uint64_t compute_pipeline_create_time_us = 0;
    uint64_t warmup_precreate_calls = 0;
    uint64_t warmup_precreate_time_us = 0;
    uint64_t compute_pipeline_fallbacks = 0;
    uint64_t blacklisted_pipeline_hits = 0;
    uint64_t warmup_cache_touches = 0;
    uint64_t sanitizer_skips_by_renderpass = 0;
    uint64_t renderpass_shapes = 0;
    uint64_t framebuffer_shapes = 0;
    uint64_t upload_fastpath_hits = 0;
    uint64_t upload_dedupe_hits = 0;
    uint64_t texture_cache_hits = 0;
    uint64_t texture_cache_misses = 0;
};

void perf_metrics_inc_graphics_pipeline_calls(uint64_t n = 1);
void perf_metrics_inc_compute_pipeline_calls(uint64_t n = 1);
void perf_metrics_inc_graphics_pipeline_fallbacks(uint64_t n = 1);
void perf_metrics_add_graphics_pipeline_create_time_us(uint64_t n);
void perf_metrics_add_compute_pipeline_create_time_us(uint64_t n);
void perf_metrics_inc_warmup_precreate_calls(uint64_t n = 1);
void perf_metrics_add_warmup_precreate_time_us(uint64_t n);
void perf_metrics_inc_compute_pipeline_fallbacks(uint64_t n = 1);
void perf_metrics_inc_blacklisted_pipeline_hits(uint64_t n = 1);
void perf_metrics_inc_warmup_cache_touches(uint64_t n = 1);
void perf_metrics_inc_sanitizer_skips_by_renderpass(uint64_t n = 1);
void perf_metrics_inc_renderpass_shapes(uint64_t n = 1);
void perf_metrics_inc_framebuffer_shapes(uint64_t n = 1);
void perf_metrics_inc_upload_fastpath_hits(uint64_t n = 1);
void perf_metrics_inc_upload_dedupe_hits(uint64_t n = 1);
void perf_metrics_inc_texture_cache_hits(uint64_t n = 1);
void perf_metrics_inc_texture_cache_misses(uint64_t n = 1);

PerfMetricsSnapshot perf_metrics_snapshot();
void perf_metrics_dump();
bool perf_metrics_dump_to_file(const char* path);
