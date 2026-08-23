#pragma once
#include <cstdint>
#include <string>

// ─────────────────────────────────────────────────────────────────────────────
//  layer_settings.h
//  All tunable knobs, loaded once from environment variables at layer init.
//
//  Environment variables:
//
//   PERFCACHE_DISABLE              1  →  disable everything; layer becomes a
//                                        transparent pass-through
//   PERFCACHE_PIPELINE_CACHE_DIR   path for pipeline cache files
//                                  default: /data/local/tmp/layercache/pipeline
//   PERFCACHE_TEXTURE_CACHE_DIR    path for texture cache files
//                                  default: /data/local/tmp/layercache/textures
//   PERFCACHE_TEXTURE_CACHE_MB     RAM limit for in-memory texture LRU (MB)
//                                  default: 256
//   PERFCACHE_TEXTURE_CACHE_DISK_MB disk limit for texture cache (MB)
//                                  default: 512
//   PERFCACHE_SANITIZE_PIPELINES   0 | 1
//                                  default: 1 on Xclipse, 0 on generic
//   PERFCACHE_LOG_LEVEL            0=off  1=error  2=info  3=verbose
//                                  default: 2
// ─────────────────────────────────────────────────────────────────────────────

enum class PerfProfile : uint32_t {
    LIGHT  = 0,
    MEDIUM = 1,
    HIGH   = 2,
};

enum class WarmupMode : uint32_t {
    OFF        = 0,
    LIGHT      = 1,
    AGGRESSIVE = 2,
};

enum class SanitizerMode : uint32_t {
    OFF        = 0,
    SAFE       = 1,
    BALANCED   = 2,
    AGGRESSIVE = 3,
};

struct LayerSettings {
    bool        disable                  = false;

    std::string pipeline_cache_dir       = "/data/local/tmp/layercache/pipeline";
    std::string texture_cache_dir        = "/data/local/tmp/layercache/textures";
    std::string config_path              = "/data/local/tmp/layercache/profiles.json";
    std::string metrics_dump_path        = "/data/local/tmp/layercache/metrics.jsonl";

    uint32_t    texture_cache_mb         = 256;
    uint32_t    texture_cache_disk_mb    = 512;

    bool        sanitize_pipelines       = false;   // compatibility alias
    int         log_level                = 2;  // 0=off 1=error 2=info 3=verbose

    // AutoTune: chosen from device capability/pressure signals unless overridden.
    bool        auto_profile             = true;
    PerfProfile profile                  = PerfProfile::MEDIUM;
    std::string profile_name             = "MEDIUM";
    std::string process_name             = "unknown";
    uint64_t    mem_total_kb             = 0;
    uint64_t    mem_available_kb         = 0;
    uint32_t    cpu_cores                = 1;

    // Performance features selected for LayerCache/MdiEx cooperation.
    WarmupMode    pipeline_warmup        = WarmupMode::LIGHT;
    bool          pipeline_warmup_precreate = true;
    bool          pipeline_blacklist     = true;
    uint32_t      small_upload_fast_kb   = 64;
    bool          upload_deduplication   = true;
    bool          cache_compression      = true;
    bool          renderpass_heuristics  = false;
    SanitizerMode sanitizer_mode         = SanitizerMode::BALANCED;
    bool          driver_versioned_cache = true;
    bool          metrics_dump_file      = true;
};

// Populated once in settings.cpp::perfcache_settings_init()
extern LayerSettings g_settings;

// Call once after g_is_xclipse is known
void perfcache_settings_init();

const char* perfcache_profile_name(PerfProfile profile);
const char* perfcache_warmup_name(WarmupMode mode);
const char* perfcache_sanitizer_name(SanitizerMode mode);
