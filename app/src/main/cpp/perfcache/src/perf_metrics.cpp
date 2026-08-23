#include "perf_metrics.h"
#include "log.h"

#include <atomic>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <string>

#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

// ─────────────────────────────────────────────────────────────────────────────
// mkdir -p for parent directory of a file path
// ─────────────────────────────────────────────────────────────────────────────

static void mkdir_p_for_file(const char* file_path) {
    if (!file_path || !*file_path) return;

    std::string path(file_path);
    size_t slash = path.find_last_of('/');
    if (slash == std::string::npos) return;

    std::string dir = path.substr(0, slash);
    if (dir.empty()) return;

    std::string cur;
    size_t pos = 0;

    if (dir[0] == '/') {
        cur = "/";
        pos = 1;
    }

    while (pos < dir.size()) {
        while (pos < dir.size() && dir[pos] == '/')
            ++pos;

        if (pos >= dir.size())
            break;

        size_t next = dir.find('/', pos);
        std::string part = dir.substr(
            pos,
            next == std::string::npos ? std::string::npos : next - pos);

        if (!part.empty()) {
            if (!cur.empty() && cur.back() != '/')
                cur += '/';

            cur += part;

            if (mkdir(cur.c_str(), 0775) != 0 && errno != EEXIST) {
                LOGE("metrics: mkdir failed for %s: %s", cur.c_str(), strerror(errno));
                return;
            }
        }

        if (next == std::string::npos)
            break;

        pos = next + 1;
    }
}

static unsigned long long ull(uint64_t v) {
    return static_cast<unsigned long long>(v);
}

// ─────────────────────────────────────────────────────────────────────────────
// Atomic counters
// ─────────────────────────────────────────────────────────────────────────────

static std::atomic<uint64_t> g_graphics_pipeline_calls{0};
static std::atomic<uint64_t> g_compute_pipeline_calls{0};
static std::atomic<uint64_t> g_graphics_pipeline_fallbacks{0};
static std::atomic<uint64_t> g_compute_pipeline_fallbacks{0};

static std::atomic<uint64_t> g_graphics_pipeline_create_time_us{0};
static std::atomic<uint64_t> g_compute_pipeline_create_time_us{0};

static std::atomic<uint64_t> g_warmup_precreate_calls{0};
static std::atomic<uint64_t> g_warmup_precreate_time_us{0};
static std::atomic<uint64_t> g_warmup_cache_touches{0};

static std::atomic<uint64_t> g_blacklisted_pipeline_hits{0};
static std::atomic<uint64_t> g_sanitizer_skips_by_renderpass{0};

static std::atomic<uint64_t> g_renderpass_shapes{0};
static std::atomic<uint64_t> g_framebuffer_shapes{0};

static std::atomic<uint64_t> g_upload_fastpath_hits{0};
static std::atomic<uint64_t> g_upload_dedupe_hits{0};

static std::atomic<uint64_t> g_texture_cache_hits{0};
static std::atomic<uint64_t> g_texture_cache_misses{0};

// ─────────────────────────────────────────────────────────────────────────────
// Increment helpers
//
// These functions take uint64_t n.
// If the header declares default args (= 1), calls with no argument work.
// Example:
//   void perf_metrics_inc_renderpass_shapes(uint64_t n = 1);
// ─────────────────────────────────────────────────────────────────────────────

#define INC_FN(name, var) \
    void name(uint64_t n) { \
        var.fetch_add(n, std::memory_order_relaxed); \
    }

INC_FN(perf_metrics_inc_graphics_pipeline_calls, g_graphics_pipeline_calls)
INC_FN(perf_metrics_inc_compute_pipeline_calls, g_compute_pipeline_calls)

INC_FN(perf_metrics_inc_graphics_pipeline_fallbacks, g_graphics_pipeline_fallbacks)
INC_FN(perf_metrics_inc_compute_pipeline_fallbacks, g_compute_pipeline_fallbacks)

INC_FN(perf_metrics_add_graphics_pipeline_create_time_us, g_graphics_pipeline_create_time_us)
INC_FN(perf_metrics_add_compute_pipeline_create_time_us, g_compute_pipeline_create_time_us)

INC_FN(perf_metrics_inc_warmup_precreate_calls, g_warmup_precreate_calls)
INC_FN(perf_metrics_add_warmup_precreate_time_us, g_warmup_precreate_time_us)
INC_FN(perf_metrics_inc_warmup_cache_touches, g_warmup_cache_touches)

INC_FN(perf_metrics_inc_blacklisted_pipeline_hits, g_blacklisted_pipeline_hits)
INC_FN(perf_metrics_inc_sanitizer_skips_by_renderpass, g_sanitizer_skips_by_renderpass)

INC_FN(perf_metrics_inc_renderpass_shapes, g_renderpass_shapes)
INC_FN(perf_metrics_inc_framebuffer_shapes, g_framebuffer_shapes)

INC_FN(perf_metrics_inc_upload_fastpath_hits, g_upload_fastpath_hits)
INC_FN(perf_metrics_inc_upload_dedupe_hits, g_upload_dedupe_hits)

INC_FN(perf_metrics_inc_texture_cache_hits, g_texture_cache_hits)
INC_FN(perf_metrics_inc_texture_cache_misses, g_texture_cache_misses)

#undef INC_FN

// ─────────────────────────────────────────────────────────────────────────────
// Snapshot
// ─────────────────────────────────────────────────────────────────────────────

PerfMetricsSnapshot perf_metrics_snapshot() {
    PerfMetricsSnapshot s{};

    s.graphics_pipeline_calls =
        g_graphics_pipeline_calls.load(std::memory_order_relaxed);
    s.compute_pipeline_calls =
        g_compute_pipeline_calls.load(std::memory_order_relaxed);

    s.graphics_pipeline_fallbacks =
        g_graphics_pipeline_fallbacks.load(std::memory_order_relaxed);
    s.compute_pipeline_fallbacks =
        g_compute_pipeline_fallbacks.load(std::memory_order_relaxed);

    s.graphics_pipeline_create_time_us =
        g_graphics_pipeline_create_time_us.load(std::memory_order_relaxed);
    s.compute_pipeline_create_time_us =
        g_compute_pipeline_create_time_us.load(std::memory_order_relaxed);

    s.warmup_precreate_calls =
        g_warmup_precreate_calls.load(std::memory_order_relaxed);
    s.warmup_precreate_time_us =
        g_warmup_precreate_time_us.load(std::memory_order_relaxed);
    s.warmup_cache_touches =
        g_warmup_cache_touches.load(std::memory_order_relaxed);

    s.blacklisted_pipeline_hits =
        g_blacklisted_pipeline_hits.load(std::memory_order_relaxed);
    s.sanitizer_skips_by_renderpass =
        g_sanitizer_skips_by_renderpass.load(std::memory_order_relaxed);

    s.renderpass_shapes =
        g_renderpass_shapes.load(std::memory_order_relaxed);
    s.framebuffer_shapes =
        g_framebuffer_shapes.load(std::memory_order_relaxed);

    s.upload_fastpath_hits =
        g_upload_fastpath_hits.load(std::memory_order_relaxed);
    s.upload_dedupe_hits =
        g_upload_dedupe_hits.load(std::memory_order_relaxed);

    s.texture_cache_hits =
        g_texture_cache_hits.load(std::memory_order_relaxed);
    s.texture_cache_misses =
        g_texture_cache_misses.load(std::memory_order_relaxed);

    return s;
}

// ─────────────────────────────────────────────────────────────────────────────
// Logcat dump
// ─────────────────────────────────────────────────────────────────────────────

void perf_metrics_dump() {
    PerfMetricsSnapshot s = perf_metrics_snapshot();

    LOGI(
        "metrics: "
        "pipelines_graphics=%llu "
        "pipelines_compute=%llu "
        "graphics_fallbacks=%llu "
        "compute_fallbacks=%llu "
        "graphics_create_time_us=%llu "
        "compute_create_time_us=%llu "
        "pipelines_blacklisted=%llu "
        "warmup_cache_touches=%llu "
        "warmup_precreate_calls=%llu "
        "warmup_precreate_time_us=%llu "
        "sanitizer_skips_renderpass=%llu "
        "renderpass_shapes=%llu "
        "framebuffer_shapes=%llu "
        "uploads_ignored_fastpath=%llu "
        "uploads_deduplicated=%llu "
        "cache_hits=%llu "
        "cache_misses=%llu",
        ull(s.graphics_pipeline_calls),
        ull(s.compute_pipeline_calls),
        ull(s.graphics_pipeline_fallbacks),
        ull(s.compute_pipeline_fallbacks),
        ull(s.graphics_pipeline_create_time_us),
        ull(s.compute_pipeline_create_time_us),
        ull(s.blacklisted_pipeline_hits),
        ull(s.warmup_cache_touches),
        ull(s.warmup_precreate_calls),
        ull(s.warmup_precreate_time_us),
        ull(s.sanitizer_skips_by_renderpass),
        ull(s.renderpass_shapes),
        ull(s.framebuffer_shapes),
        ull(s.upload_fastpath_hits),
        ull(s.upload_dedupe_hits),
        ull(s.texture_cache_hits),
        ull(s.texture_cache_misses));
}

// ─────────────────────────────────────────────────────────────────────────────
// JSONL dump
// ─────────────────────────────────────────────────────────────────────────────

bool perf_metrics_dump_to_file(const char* path) {
    if (!path || !*path) return false;

    mkdir_p_for_file(path);

    int fd = open(path, O_CREAT | O_WRONLY | O_APPEND | O_CLOEXEC, 0664);
    if (fd < 0) {
        LOGE("metrics: open failed for %s: %s", path, strerror(errno));
        return false;
    }

    if (flock(fd, LOCK_EX) != 0) {
        LOGE("metrics: flock failed for %s: %s", path, strerror(errno));
        close(fd);
        return false;
    }

    PerfMetricsSnapshot s = perf_metrics_snapshot();
    const long long now = static_cast<long long>(time(nullptr));

    char line[2048];

    int len = snprintf(
        line,
        sizeof(line),
        "{\"ts\":%lld,"
        "\"pipelines_graphics\":%llu,"
        "\"pipelines_compute\":%llu,"
        "\"graphics_fallbacks\":%llu,"
        "\"compute_fallbacks\":%llu,"
        "\"graphics_create_time_us\":%llu,"
        "\"compute_create_time_us\":%llu,"
        "\"pipelines_blacklisted\":%llu,"
        "\"warmup_cache_touches\":%llu,"
        "\"warmup_precreate_calls\":%llu,"
        "\"warmup_precreate_time_us\":%llu,"
        "\"sanitizer_skips_renderpass\":%llu,"
        "\"renderpass_shapes\":%llu,"
        "\"framebuffer_shapes\":%llu,"
        "\"uploads_ignored_fastpath\":%llu,"
        "\"uploads_deduplicated\":%llu,"
        "\"cache_hits\":%llu,"
        "\"cache_misses\":%llu}\n",
        now,
        ull(s.graphics_pipeline_calls),
        ull(s.compute_pipeline_calls),
        ull(s.graphics_pipeline_fallbacks),
        ull(s.compute_pipeline_fallbacks),
        ull(s.graphics_pipeline_create_time_us),
        ull(s.compute_pipeline_create_time_us),
        ull(s.blacklisted_pipeline_hits),
        ull(s.warmup_cache_touches),
        ull(s.warmup_precreate_calls),
        ull(s.warmup_precreate_time_us),
        ull(s.sanitizer_skips_by_renderpass),
        ull(s.renderpass_shapes),
        ull(s.framebuffer_shapes),
        ull(s.upload_fastpath_hits),
        ull(s.upload_dedupe_hits),
        ull(s.texture_cache_hits),
        ull(s.texture_cache_misses));

    bool ok = false;

    if (len > 0 && static_cast<size_t>(len) < sizeof(line)) {
        ssize_t written = write(fd, line, static_cast<size_t>(len));
        if (written == len) {
            if (fsync(fd) == 0) {
                ok = true;
            } else {
                LOGE("metrics: fsync failed for %s: %s", path, strerror(errno));
            }
        } else {
            LOGE("metrics: write failed for %s: %s", path, strerror(errno));
        }
    } else {
        LOGE("metrics: snprintf overflow for %s", path);
    }

    flock(fd, LOCK_UN);
    close(fd);

    return ok;
}