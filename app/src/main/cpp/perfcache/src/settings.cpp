// settings.cpp
// Reads PERFCACHE_* environment variables and selects LIGHT / MEDIUM / HIGH
// automatically from device pressure/capability signals, not hardcoded emulator
// package names. App-specific guessing was cute. Also brittle.

#include "layer_settings.h"
#include "xclipse_detect.h"
#include "log.h"

#include <algorithm>
#include <cerrno>
#include <climits>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <cctype>
#include <strings.h>

#include <thread>
#include <fstream>
#include <sstream>
#include <string>
#include <mutex>

LayerSettings g_settings;

// ─────────────────────────────────────────────────────────────────────────────
// Small helpers
// ─────────────────────────────────────────────────────────────────────────────

static const char* env_or(const char* key, const char* fallback) {
    const char* v = getenv(key);
    return (v && *v) ? v : fallback;
}

static uint32_t clamp_u32(uint32_t v, uint32_t lo, uint32_t hi) {
    return std::max(lo, std::min(v, hi));
}

static uint32_t env_u32(const char* key, uint32_t fallback) {
    const char* v = getenv(key);
    if (!v || !*v) return fallback;

    errno = 0;
    char* end = nullptr;
    unsigned long val = strtoul(v, &end, 10);

    if (errno != 0 || end == v || *end != '\0' || val > UINT32_MAX)
        return fallback;

    return static_cast<uint32_t>(val);
}

static bool parse_bool_text(const char* v, bool fallback) {
    if (!v || !*v) return fallback;

    if (!strcmp(v, "1") ||
        !strcasecmp(v, "true") ||
        !strcasecmp(v, "on") ||
        !strcasecmp(v, "yes") ||
        !strcasecmp(v, "y"))
        return true;

    if (!strcmp(v, "0") ||
        !strcasecmp(v, "false") ||
        !strcasecmp(v, "off") ||
        !strcasecmp(v, "no") ||
        !strcasecmp(v, "n"))
        return false;

    return fallback;
}

static bool env_bool(const char* key, bool fallback) {
    return parse_bool_text(getenv(key), fallback);
}

static std::string read_process_name() {
    char buf[256]{};

    FILE* f = fopen("/proc/self/cmdline", "rb");
    if (!f) return "unknown";

    size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);

    if (n == 0) return "unknown";

    buf[n] = 0;
    return std::string(buf);
}

static uint64_t read_meminfo_kb(const char* wanted) {
    if (!wanted || !*wanted) return 0;

    FILE* f = fopen("/proc/meminfo", "r");
    if (!f) return 0;

    char line[256]{};
    uint64_t out = 0;

    while (fgets(line, sizeof(line), f)) {
        char key[64]{};
        unsigned long long value = 0;
        char unit[16]{};

        if (sscanf(line, "%63[^:]: %llu %15s", key, &value, unit) >= 2) {
            if (!strcmp(key, wanted)) {
                out = static_cast<uint64_t>(value);
                break;
            }
        }
    }

    fclose(f);
    return out;
}

// ─────────────────────────────────────────────────────────────────────────────
// Name helpers
// ─────────────────────────────────────────────────────────────────────────────

const char* xcache_profile_name(PerfProfile p) {
    switch (p) {
        case PerfProfile::LIGHT: return "LIGHT";
        case PerfProfile::HIGH: return "HIGH";
        case PerfProfile::MEDIUM:
        default: return "MEDIUM";
    }
}

const char* xcache_warmup_name(WarmupMode m) {
    switch (m) {
        case WarmupMode::OFF: return "OFF";
        case WarmupMode::AGGRESSIVE: return "AGGRESSIVE";
        case WarmupMode::LIGHT:
        default: return "LIGHT";
    }
}

const char* xcache_sanitizer_name(SanitizerMode m) {
    switch (m) {
        case SanitizerMode::OFF: return "OFF";
        case SanitizerMode::SAFE: return "SAFE";
        case SanitizerMode::AGGRESSIVE: return "AGGRESSIVE";
        case SanitizerMode::BALANCED:
        default: return "BALANCED";
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Parsers
// ─────────────────────────────────────────────────────────────────────────────

static PerfProfile parse_profile(const char* v, PerfProfile fallback) {
    if (!v || !*v) return fallback;

    if (!strcasecmp(v, "light") ||
        !strcasecmp(v, "leve") ||
        !strcmp(v, "0"))
        return PerfProfile::LIGHT;

    if (!strcasecmp(v, "medium") ||
        !strcasecmp(v, "medio") ||
        !strcasecmp(v, "médio") ||
        !strcasecmp(v, "padrao") ||
        !strcasecmp(v, "padrão") ||
        !strcmp(v, "1"))
        return PerfProfile::MEDIUM;

    if (!strcasecmp(v, "high") ||
        !strcasecmp(v, "alto") ||
        !strcmp(v, "2"))
        return PerfProfile::HIGH;

    return fallback;
}

static WarmupMode parse_warmup_mode(const std::string& v, WarmupMode fallback) {
    if (v.empty()) return fallback;

    if (!strcasecmp(v.c_str(), "off") ||
        !strcasecmp(v.c_str(), "false") ||
        !strcasecmp(v.c_str(), "disabled") ||
        v == "0")
        return WarmupMode::OFF;

    if (!strcasecmp(v.c_str(), "aggressive") ||
        !strcasecmp(v.c_str(), "alto") ||
        v == "2")
        return WarmupMode::AGGRESSIVE;

    if (!strcasecmp(v.c_str(), "light") ||
        !strcasecmp(v.c_str(), "leve") ||
        v == "1")
        return WarmupMode::LIGHT;

    return fallback;
}

static SanitizerMode parse_sanitizer_mode(const std::string& v,
                                          SanitizerMode fallback) {
    if (v.empty()) return fallback;

    if (!strcasecmp(v.c_str(), "off") ||
        !strcasecmp(v.c_str(), "false") ||
        !strcasecmp(v.c_str(), "disabled") ||
        v == "0")
        return SanitizerMode::OFF;

    if (!strcasecmp(v.c_str(), "safe") ||
        !strcasecmp(v.c_str(), "seguro") ||
        v == "1")
        return SanitizerMode::SAFE;

    if (!strcasecmp(v.c_str(), "balanced") ||
        !strcasecmp(v.c_str(), "medio") ||
        !strcasecmp(v.c_str(), "médio") ||
        v == "2")
        return SanitizerMode::BALANCED;

    if (!strcasecmp(v.c_str(), "aggressive") ||
        !strcasecmp(v.c_str(), "alto") ||
        v == "3")
        return SanitizerMode::AGGRESSIVE;

    return fallback;
}

// ─────────────────────────────────────────────────────────────────────────────
// AutoTune
// ─────────────────────────────────────────────────────────────────────────────

static PerfProfile auto_profile_from_device_signals() {
    const uint64_t total_kb = read_meminfo_kb("MemTotal");
    const uint64_t avail_kb = read_meminfo_kb("MemAvailable");
    const uint32_t cores =
        std::max(1u, std::thread::hardware_concurrency());

    if (env_bool("PERFCACHE_BENCHMARK_MODE", false))
        return PerfProfile::LIGHT;

    if (!xcache_is_xclipse())
        return PerfProfile::LIGHT;

    if (total_kb && total_kb < 7ull * 1024ull * 1024ull)
        return PerfProfile::LIGHT;

    if (avail_kb && avail_kb < 1024ull * 1024ull)
        return PerfProfile::LIGHT;

    if (cores >= 8 &&
        total_kb >= 10ull * 1024ull * 1024ull &&
        (!avail_kb || avail_kb >= 1800ull * 1024ull))
        return PerfProfile::HIGH;

    return PerfProfile::MEDIUM;
}

static void apply_profile(PerfProfile p) {
    g_settings.profile = p;
    g_settings.profile_name = xcache_profile_name(p);

    switch (p) {
        case PerfProfile::LIGHT:
            g_settings.pipeline_warmup = WarmupMode::OFF;
            g_settings.pipeline_blacklist = true;
            g_settings.small_upload_fast_kb = 64;
            g_settings.upload_deduplication = false;
            g_settings.cache_compression = true;
            g_settings.renderpass_heuristics = false;
            g_settings.sanitizer_mode =
                xcache_is_xclipse() ? SanitizerMode::SAFE : SanitizerMode::OFF;
            g_settings.texture_cache_mb = 128;
            g_settings.texture_cache_disk_mb = 256;
            break;

        case PerfProfile::HIGH:
            g_settings.pipeline_warmup = WarmupMode::AGGRESSIVE;
            g_settings.pipeline_blacklist = true;
            g_settings.small_upload_fast_kb = 32;
            g_settings.upload_deduplication = true;
            g_settings.cache_compression = true;
            g_settings.renderpass_heuristics = true;
            g_settings.sanitizer_mode =
                xcache_is_xclipse() ? SanitizerMode::AGGRESSIVE : SanitizerMode::BALANCED;
            g_settings.texture_cache_mb = 384;
            g_settings.texture_cache_disk_mb = 1024;
            break;

        case PerfProfile::MEDIUM:
        default:
            g_settings.pipeline_warmup = WarmupMode::LIGHT;
            g_settings.pipeline_blacklist = true;
            g_settings.small_upload_fast_kb = 64;
            g_settings.upload_deduplication = true;
            g_settings.cache_compression = true;
            g_settings.renderpass_heuristics = true;
            g_settings.sanitizer_mode =
                xcache_is_xclipse() ? SanitizerMode::BALANCED : SanitizerMode::OFF;
            g_settings.texture_cache_mb = 256;
            g_settings.texture_cache_disk_mb = 512;
            break;
    }

    g_settings.sanitize_pipelines =
        (g_settings.sanitizer_mode != SanitizerMode::OFF);
}

// ─────────────────────────────────────────────────────────────────────────────
// Tiny JSON-ish config reader
// ─────────────────────────────────────────────────────────────────────────────

static std::string slurp_file(const std::string& path) {
    if (path.empty()) return {};

    std::ifstream in(path.c_str(), std::ios::in | std::ios::binary);
    if (!in) return {};

    std::ostringstream ss;
    ss << in.rdbuf();
    return ss.str();
}

static std::string strip_json_comments(const std::string& in) {
    std::string out;
    out.reserve(in.size());

    bool in_str = false;
    bool esc = false;

    for (size_t i = 0; i < in.size(); ++i) {
        char c = in[i];

        if (in_str) {
            out.push_back(c);

            if (esc) {
                esc = false;
                continue;
            }

            if (c == '\\') {
                esc = true;
                continue;
            }

            if (c == '"')
                in_str = false;

            continue;
        }

        if (c == '"') {
            in_str = true;
            out.push_back(c);
            continue;
        }

        if (c == '/' && i + 1 < in.size() && in[i + 1] == '/') {
            while (i < in.size() && in[i] != '\n')
                ++i;

            if (i < in.size())
                out.push_back('\n');

            continue;
        }

        if (c == '/' && i + 1 < in.size() && in[i + 1] == '*') {
            i += 2;

            while (i + 1 < in.size() &&
                   !(in[i] == '*' && in[i + 1] == '/'))
                ++i;

            if (i + 1 < in.size())
                ++i;

            continue;
        }

        out.push_back(c);
    }

    return out;
}

static size_t find_json_key_value(const std::string& text, const char* key) {
    if (!key || !*key) return std::string::npos;

    std::string pat = std::string("\"") + key + "\"";
    size_t p = text.find(pat);
    if (p == std::string::npos) return std::string::npos;

    p = text.find(':', p);
    if (p == std::string::npos) return std::string::npos;

    ++p;

    while (p < text.size() &&
           std::isspace(static_cast<unsigned char>(text[p])))
        ++p;

    return p;
}

static bool json_boolish(const std::string& text,
                         const char* key,
                         bool fallback) {
    size_t p = find_json_key_value(text, key);
    if (p == std::string::npos) return fallback;

    if (text.compare(p, 4, "true") == 0)
        return true;

    if (text.compare(p, 5, "false") == 0)
        return false;

    if (text[p] == '"') {
        ++p;
        std::string v;

        while (p < text.size() && text[p] != '"')
            v.push_back(text[p++]);

        return parse_bool_text(v.c_str(), fallback);
    }

    if (text.compare(p, 1, "1") == 0)
        return true;

    if (text.compare(p, 1, "0") == 0)
        return false;

    return fallback;
}

static uint32_t json_u32ish(const std::string& text,
                            const char* key,
                            uint32_t fallback) {
    size_t p = find_json_key_value(text, key);
    if (p == std::string::npos) return fallback;

    if (p >= text.size()) return fallback;

    bool quoted = false;
    if (text[p] == '"') {
        quoted = true;
        ++p;
    }

    errno = 0;
    char* end = nullptr;
    unsigned long v = strtoul(text.c_str() + p, &end, 10);

    if (errno != 0 || end == text.c_str() + p || v > UINT32_MAX)
        return fallback;

    if (quoted && (!end || *end != '"'))
        return fallback;

    return static_cast<uint32_t>(v);
}

static std::string json_stringish(const std::string& text,
                                  const char* key,
                                  const std::string& fallback) {
    size_t p = find_json_key_value(text, key);
    if (p == std::string::npos) return fallback;

    if (p >= text.size() || text[p] != '"')
        return fallback;

    ++p;

    std::string out;
    bool esc = false;

    for (; p < text.size(); ++p) {
        char c = text[p];

        if (esc) {
            switch (c) {
                case 'n': out.push_back('\n'); break;
                case 't': out.push_back('\t'); break;
                case 'r': out.push_back('\r'); break;
                case '"': out.push_back('"'); break;
                case '\\': out.push_back('\\'); break;
                default: out.push_back(c); break;
            }

            esc = false;
            continue;
        }

        if (c == '\\') {
            esc = true;
            continue;
        }

        if (c == '"')
            return out;

        out.push_back(c);
    }

    return fallback;
}

// ─────────────────────────────────────────────────────────────────────────────
// Bounds / sanity
// ─────────────────────────────────────────────────────────────────────────────

static void clamp_settings() {
    g_settings.texture_cache_mb =
        clamp_u32(g_settings.texture_cache_mb, 16, 1024);

    g_settings.texture_cache_disk_mb =
        clamp_u32(g_settings.texture_cache_disk_mb, 32, 4096);

    g_settings.small_upload_fast_kb =
        clamp_u32(g_settings.small_upload_fast_kb, 4, 1024);

    g_settings.log_level =
        std::max(0, std::min(g_settings.log_level, 5));

    g_settings.sanitize_pipelines =
        (g_settings.sanitizer_mode != SanitizerMode::OFF);

    if (g_settings.pipeline_warmup == WarmupMode::OFF)
        g_settings.pipeline_warmup_precreate = false;
}

static void apply_external_config_if_present() {
    const std::string raw = slurp_file(g_settings.config_path);
    if (raw.empty()) return;

    const std::string text = strip_json_comments(raw);
    if (text.empty()) return;

    std::string profile = json_stringish(text, "profile", "");
    if (!profile.empty())
        apply_profile(parse_profile(profile.c_str(), g_settings.profile));

    std::string warmup = json_stringish(text, "pipelineWarmup", "");
    if (!warmup.empty()) {
        g_settings.pipeline_warmup =
            parse_warmup_mode(warmup, g_settings.pipeline_warmup);
    }

    std::string sanitizer = json_stringish(text, "sanitizerMode", "");
    if (!sanitizer.empty()) {
        g_settings.sanitizer_mode =
            parse_sanitizer_mode(sanitizer, g_settings.sanitizer_mode);
    }

    g_settings.pipeline_warmup_precreate =
        json_boolish(text, "pipelineWarmupPrecreate",
                     g_settings.pipeline_warmup_precreate);

    g_settings.pipeline_blacklist =
        json_boolish(text, "pipelineBlacklist",
                     g_settings.pipeline_blacklist);

    g_settings.upload_deduplication =
        json_boolish(text, "uploadDeduplication",
                     g_settings.upload_deduplication);

    g_settings.cache_compression =
        json_boolish(text, "cacheCompression",
                     g_settings.cache_compression);

    g_settings.renderpass_heuristics =
        json_boolish(text, "renderpassHeuristics",
                     g_settings.renderpass_heuristics);

    g_settings.driver_versioned_cache =
        json_boolish(text, "driverVersionedCache",
                     g_settings.driver_versioned_cache);

    g_settings.metrics_dump_file =
        json_boolish(text, "metricsDumpFile",
                     g_settings.metrics_dump_file);

    g_settings.metrics_dump_path =
        json_stringish(text, "metricsPath",
                       g_settings.metrics_dump_path);

    g_settings.small_upload_fast_kb =
        json_u32ish(text, "smallUploadFastKB",
                    g_settings.small_upload_fast_kb);

    g_settings.texture_cache_mb =
        json_u32ish(text, "textureCacheMB",
                    g_settings.texture_cache_mb);

    g_settings.texture_cache_disk_mb =
        json_u32ish(text, "textureCacheDiskMB",
                    g_settings.texture_cache_disk_mb);

    clamp_settings();

    LOGI("settings: loaded external config %s",
         g_settings.config_path.c_str());
}

// ─────────────────────────────────────────────────────────────────────────────
// Public init
// ─────────────────────────────────────────────────────────────────────────────

void xcache_settings_init() {
    static std::mutex init_lock;
    static bool initialized = false;

    std::lock_guard<std::mutex> init_guard(init_lock);

    if (initialized) {
        LOGV("settings: already initialized; keeping first device AutoTune snapshot");
        return;
    }

    LayerSettings next{};

    g_settings = next;

    g_settings.disable =
        env_bool("XCACHE_DISABLE", false);

    g_settings.pipeline_cache_dir =
        env_or("PERFCACHE_PIPELINE_CACHE_DIR",
               "/data/local/tmp/layercache/pipeline");

    g_settings.texture_cache_dir =
        env_or("PERFCACHE_TEXTURE_CACHE_DIR",
               "/data/local/tmp/layercache/textures");

    g_settings.config_path =
        env_or("PERFCACHE_CONFIG_PATH",
               "/data/local/tmp/layercache/profiles.json");

    g_settings.metrics_dump_path =
        env_or("PERFCACHE_METRICS_PATH",
               "/data/local/tmp/layercache/metrics.jsonl");

    g_settings.log_level =
        static_cast<int>(env_u32("PERFCACHE_LOG_LEVEL", 2));

    g_settings.auto_profile =
        env_bool("PERFCACHE_AUTO_PROFILE", true);

    g_settings.process_name = read_process_name();

    g_settings.mem_total_kb =
        read_meminfo_kb("MemTotal");

    g_settings.mem_available_kb =
        read_meminfo_kb("MemAvailable");

    g_settings.cpu_cores =
        std::max(1u, std::thread::hardware_concurrency());

    PerfProfile selected =
        g_settings.auto_profile
            ? auto_profile_from_device_signals()
            : PerfProfile::MEDIUM;

    selected = parse_profile(getenv("PERFCACHE_PROFILE"), selected);

    apply_profile(selected);

    // External config after AutoTune/profile selection.
    // Env vars below still win, because chaos needs hierarchy.
    apply_external_config_if_present();

    // Manual overrides after profile/config.
    g_settings.texture_cache_mb =
        env_u32("PERFCACHE_TEXTURE_CACHE_MB",
                g_settings.texture_cache_mb);

    g_settings.texture_cache_disk_mb =
        env_u32("PERFCACHE_TEXTURE_CACHE_DISK_MB",
                g_settings.texture_cache_disk_mb);

    g_settings.small_upload_fast_kb =
        env_u32("PERFCACHE_SMALL_UPLOAD_FAST_KB",
                g_settings.small_upload_fast_kb);

    g_settings.pipeline_blacklist =
        env_bool("PERFCACHE_PIPELINE_BLACKLIST",
                 g_settings.pipeline_blacklist);

    g_settings.pipeline_warmup_precreate =
        env_bool("PERFCACHE_PIPELINE_WARMUP_PRECREATE",
                 g_settings.pipeline_warmup_precreate);

    g_settings.upload_deduplication =
        env_bool("PERFCACHE_UPLOAD_DEDUP",
                 g_settings.upload_deduplication);

    g_settings.cache_compression =
        env_bool("PERFCACHE_CACHE_COMPRESSION",
                 g_settings.cache_compression);

    g_settings.renderpass_heuristics =
        env_bool("PERFCACHE_RENDERPASS_HEURISTICS",
                 g_settings.renderpass_heuristics);

    g_settings.driver_versioned_cache =
        env_bool("PERFCACHE_DRIVER_VERSIONED_CACHE",
                 g_settings.driver_versioned_cache);

    g_settings.metrics_dump_file =
        env_bool("PERFCACHE_METRICS_DUMP_FILE",
                 g_settings.metrics_dump_file);

    g_settings.metrics_dump_path =
        env_or("PERFCACHE_METRICS_PATH",
               g_settings.metrics_dump_path.c_str());

    const char* san = getenv("PERFCACHE_SANITIZE_PIPELINES");
    if (san) {
        const bool enabled =
            parse_bool_text(san, g_settings.sanitize_pipelines);

        g_settings.sanitize_pipelines = enabled;

        if (!enabled) {
            g_settings.sanitizer_mode = SanitizerMode::OFF;
        } else if (g_settings.sanitizer_mode == SanitizerMode::OFF) {
            g_settings.sanitizer_mode =
                xcache_is_xclipse()
                    ? SanitizerMode::BALANCED
                    : SanitizerMode::SAFE;
        }
    }

    const char* warmup_env = getenv("PERFCACHE_PIPELINE_WARMUP");
    if (warmup_env) {
        g_settings.pipeline_warmup =
            parse_warmup_mode(warmup_env, g_settings.pipeline_warmup);
    }

    const char* sanitizer_env = getenv("PERFCACHE_SANITIZER_MODE");
    if (sanitizer_env) {
        g_settings.sanitizer_mode =
            parse_sanitizer_mode(sanitizer_env, g_settings.sanitizer_mode);
    }

    clamp_settings();

    initialized = true;

    LOGI("AutoTune: process=%s profile=%s warmup=%s precreate=%d sanitizer=%s blacklist=%d dedup=%d compression=%d renderpass=%d metrics_file=%d metrics=%s config=%s mem=%lluMB avail=%lluMB cores=%u",
         g_settings.process_name.c_str(),
         g_settings.profile_name.c_str(),
         xcache_warmup_name(g_settings.pipeline_warmup),
         static_cast<int>(g_settings.pipeline_warmup_precreate),
         xcache_sanitizer_name(g_settings.sanitizer_mode),
         static_cast<int>(g_settings.pipeline_blacklist),
         static_cast<int>(g_settings.upload_deduplication),
         static_cast<int>(g_settings.cache_compression),
         static_cast<int>(g_settings.renderpass_heuristics),
         static_cast<int>(g_settings.metrics_dump_file),
         g_settings.metrics_dump_path.c_str(),
         g_settings.config_path.c_str(),
         static_cast<unsigned long long>(g_settings.mem_total_kb / 1024),
         static_cast<unsigned long long>(g_settings.mem_available_kb / 1024),
         g_settings.cpu_cores);
}