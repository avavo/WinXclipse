// texture_cache.cpp
//
// Conservative texture upload recorder / dedupe helper.
//
// It records payloads seen in vkCmdCopyBufferToImage* into:
//   Level 1 – RAM LRU
//   Level 2 – Disk cache
//
// Cache HIT semantics:
//   A hit means "we have seen this exact upload payload before."
//   We do NOT skip the actual driver copy.
//   The caller still forwards vkCmdCopyBufferToImage* to the driver.
//
// This is intentionally conservative. Skipping real uploads safely needs
// image lifetime/layout/usage tracking. Without that, pretending is how
// render bugs crawl out of the swamp wearing your project name.

#include "texture_cache.h"
#include "dispatch_table.h"
#include "layer_settings.h"
#include "log.h"
#include "xxhash.h"
#include "perf_metrics.h"

#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cstdlib>

#include <dirent.h>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <limits>
#include <mutex>
#include <shared_mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

// ─────────────────────────────────────────────────────────────────────────────
// On-disk header
// ─────────────────────────────────────────────────────────────────────────────

static constexpr uint32_t TEXCACHE_MAGIC   = 0x54584343u; // "TXCC"
static constexpr uint32_t TEXCACHE_MAGIC_C = 0x5a435854u; // "TXCZ"
static constexpr uint32_t TEXCACHE_VERSION = 1u;

#pragma pack(push, 1)
struct TexCacheHeader {
    uint32_t magic;
    uint32_t version;
    uint32_t width;
    uint32_t height;
    uint32_t vk_format;
    uint32_t mip_levels;
    uint64_t data_size;     // uncompressed byte count
};
#pragma pack(pop)

static_assert(sizeof(TexCacheHeader) == 32, "header must be 32 bytes");

// ─────────────────────────────────────────────────────────────────────────────
// Per-entry
// ─────────────────────────────────────────────────────────────────────────────

struct TexEntry {
    uint64_t             key = 0;
    std::vector<uint8_t> data;
    uint64_t             last_access = 0;
    uint32_t             width = 0;
    uint32_t             height = 0;
    uint32_t             vk_format = 0;
    uint32_t             mip_levels = 1;
};

// ─────────────────────────────────────────────────────────────────────────────
// State
// ─────────────────────────────────────────────────────────────────────────────

static std::shared_mutex                      s_rw;
static std::unordered_map<uint64_t, TexEntry> s_ram_cache;
static uint64_t                               s_ram_bytes = 0;
static uint64_t                               s_tick = 0;

static std::mutex                             s_tick_lock;
static std::mutex                             s_disk_lock;

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

static uint64_t next_tick() {
    std::lock_guard<std::mutex> lk(s_tick_lock);
    return ++s_tick;
}

static void ensure_dir(const std::string& dir) {
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

            if (mkdir(cur.c_str(), 0755) != 0 && errno != EEXIST) {
                LOGE("texture_cache: mkdir failed for %s: %s",
                     cur.c_str(), strerror(errno));
                return;
            }
        }

        if (next == std::string::npos)
            break;

        pos = next + 1;
    }
}

static std::string key_to_path(uint64_t key) {
    char name[32]{};
    snprintf(name, sizeof(name), "%016llx.bin",
             static_cast<unsigned long long>(key));

    std::string path = g_settings.texture_cache_dir;
    if (!path.empty() && path.back() != '/')
        path += '/';

    path += name;
    return path;
}

static std::string dirname_of(const std::string& path) {
    size_t slash = path.find_last_of('/');
    if (slash == std::string::npos) return {};
    if (slash == 0) return "/";
    return path.substr(0, slash);
}

// Mantida para referência: a gravação do cache deixou de fsyncar (best-effort;
// hits nunca afetam rendering), então o fsync de diretório também saiu.
[[maybe_unused]] static void fsync_parent_dir(const std::string& path) {
    std::string dir = dirname_of(path);
    if (dir.empty()) return;

    int fd = open(dir.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) return;

    fsync(fd);
    close(fd);
}

static uint64_t max_texture_payload_bytes() {
    uint64_t mb = static_cast<uint64_t>(g_settings.texture_cache_disk_mb);

    if (mb == 0)
        mb = static_cast<uint64_t>(g_settings.texture_cache_mb);

    if (mb == 0)
        mb = 1;

    if (mb > 2048)
        mb = 2048;

    return mb * 1024ull * 1024ull;
}

// Hard ceiling for what try_record_upload will copy off a persistent map.
// Independent of the disk budget: bounds the worst-case per-upload stall.
static constexpr uint64_t kMaxRecordedUploadBytes = 16ull * 1024ull * 1024ull;

static uint64_t disk_limit_bytes() {
    uint64_t mb = static_cast<uint64_t>(g_settings.texture_cache_disk_mb);
    if (mb == 0) return 0;
    if (mb > 4096) mb = 4096;
    return mb * 1024ull * 1024ull;
}

// ─────────────────────────────────────────────────────────────────────────────
// Cross-process disk lock
// ─────────────────────────────────────────────────────────────────────────────

struct DiskFileLock {
    int fd = -1;

    explicit DiskFileLock(const std::string& dir, int mode = LOCK_EX) {
        ensure_dir(dir);

        std::string lock_path = dir;
        if (!lock_path.empty() && lock_path.back() != '/')
            lock_path += '/';
        lock_path += ".layercache.lock";

        fd = open(lock_path.c_str(), O_CREAT | O_RDWR | O_CLOEXEC, 0600);

        if (fd >= 0) {
            if (flock(fd, mode) != 0) {
                LOGE("texture_cache: flock failed for %s: %s",
                     lock_path.c_str(), strerror(errno));
                close(fd);
                fd = -1;
            }
        }
    }

    ~DiskFileLock() {
        if (fd >= 0) {
            flock(fd, LOCK_UN);
            close(fd);
        }
    }

    explicit operator bool() const {
        return fd >= 0;
    }

    DiskFileLock(const DiskFileLock&) = delete;
    DiskFileLock& operator=(const DiskFileLock&) = delete;
};

// ─────────────────────────────────────────────────────────────────────────────
// Disk size
// ─────────────────────────────────────────────────────────────────────────────

static uint64_t disk_used_bytes_locked() {
    const std::string& dir = g_settings.texture_cache_dir;

    DIR* d = opendir(dir.c_str());
    if (!d) return 0;

    uint64_t total = 0;

    while (dirent* ent = readdir(d)) {
        if (ent->d_name[0] == '.')
            continue;

        std::string path = dir;
        if (!path.empty() && path.back() != '/')
            path += '/';
        path += ent->d_name;

        struct stat st{};
        if (stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode)) {
            total += static_cast<uint64_t>(st.st_size);
        }
    }

    closedir(d);
    return total;
}

// ─────────────────────────────────────────────────────────────────────────────
// RAM LRU eviction
// ─────────────────────────────────────────────────────────────────────────────

static void evict_ram_if_needed() {
    const uint64_t limit =
        static_cast<uint64_t>(g_settings.texture_cache_mb) * 1024ull * 1024ull;

    if (limit == 0 || s_ram_bytes <= limit)
        return;

    std::vector<std::pair<uint64_t, uint64_t>> order;
    order.reserve(s_ram_cache.size());

    for (const auto& kv : s_ram_cache)
        order.push_back({ kv.second.last_access, kv.first });

    std::sort(order.begin(), order.end());

    for (const auto& item : order) {
        if (s_ram_bytes <= limit)
            break;

        auto it = s_ram_cache.find(item.second);
        if (it == s_ram_cache.end())
            continue;

        s_ram_bytes -= it->second.data.size();
        s_ram_cache.erase(it);
    }

    LOGV("texture_cache: RAM eviction complete, now %.1f MB",
         s_ram_bytes / (1024.0 * 1024.0));
}

// ─────────────────────────────────────────────────────────────────────────────
// Tiny RLE codec
// ─────────────────────────────────────────────────────────────────────────────

static std::vector<uint8_t> rle_compress(const std::vector<uint8_t>& in) {
    std::vector<uint8_t> out;
    out.reserve(in.size());

    for (size_t i = 0; i < in.size();) {
        const uint8_t b = in[i];

        size_t j = i + 1;
        while (j < in.size() && in[j] == b && (j - i) < 255)
            ++j;

        const size_t run = j - i;

        if (run >= 4 || b == 0xFF) {
            out.push_back(0xFF);
            out.push_back(static_cast<uint8_t>(run));
            out.push_back(b);
        } else {
            for (size_t k = 0; k < run; ++k)
                out.push_back(b);
        }

        i = j;
    }

    return out;
}

static bool rle_decompress(const std::vector<uint8_t>& in,
                           size_t expected,
                           std::vector<uint8_t>& out) {
    out.clear();

    if (expected > max_texture_payload_bytes())
        return false;

    try {
        out.reserve(expected);
    } catch (...) {
        return false;
    }

    for (size_t i = 0; i < in.size();) {
        const uint8_t b = in[i++];

        if (b == 0xFF) {
            if (i + 2 > in.size())
                return false;

            const uint8_t run = in[i++];
            const uint8_t val = in[i++];

            if (out.size() + run > expected)
                return false;

            out.insert(out.end(), run, val);
        } else {
            if (out.size() + 1 > expected)
                return false;

            out.push_back(b);
        }
    }

    return out.size() == expected;
}

// ─────────────────────────────────────────────────────────────────────────────
// Disk I/O
// ─────────────────────────────────────────────────────────────────────────────

static bool write_all_fd(int fd, const void* data, size_t size) {
    const uint8_t* p = static_cast<const uint8_t*>(data);
    size_t remaining = size;

    while (remaining > 0) {
        ssize_t written = write(fd, p, remaining);

        if (written < 0) {
            if (errno == EINTR)
                continue;

            return false;
        }

        if (written == 0)
            return false;

        p += written;
        remaining -= static_cast<size_t>(written);
    }

    return true;
}

// Oldest-first eviction: without it the cache froze permanently once full —
// the first game to fill the quota starved every later session forever.
// Caller must hold s_disk_lock (and the file lock).
static void evict_oldest_locked(uint64_t target_bytes) {
    const std::string dir = g_settings.texture_cache_dir;

    DIR* d = opendir(dir.c_str());
    if (!d) return;

    struct Entry {
        std::string path;
        uint64_t size;
        long long mtime;
    };

    std::vector<Entry> entries;
    struct dirent* de;

    while ((de = readdir(d))) {
        if (de->d_name[0] == '.') continue;

        std::string path = dir;
        if (!path.empty() && path.back() != '/') path += '/';
        path += de->d_name;

        struct stat st{};
        if (stat(path.c_str(), &st) != 0 || !S_ISREG(st.st_mode)) continue;

        entries.push_back({path,
                           static_cast<uint64_t>(st.st_size),
                           static_cast<long long>(st.st_mtime)});
    }

    closedir(d);

    std::sort(entries.begin(), entries.end(),
              [](const Entry& a, const Entry& b) { return a.mtime < b.mtime; });

    uint64_t used = disk_used_bytes_locked();

    for (const auto& e : entries) {
        if (used <= target_bytes) break;

        if (remove(e.path.c_str()) == 0)
            used = (used > e.size) ? used - e.size : 0;
    }
}

static void write_to_disk(const TexEntry& e) {
    if (e.data.empty())
        return;

    // Recording cap mirrors try_record_upload; read-side still honors the
    // configured budget for pre-existing larger entries.
    if (e.data.size() > kMaxRecordedUploadBytes)
        return;

    std::lock_guard<std::mutex> lk(s_disk_lock);
    DiskFileLock file_lk(g_settings.texture_cache_dir, LOCK_EX);
    if (!file_lk) return;

    std::vector<uint8_t> payload = e.data;
    uint32_t magic = TEXCACHE_MAGIC;

    if (g_settings.cache_compression && e.data.size() >= 4096) {
        std::vector<uint8_t> compressed = rle_compress(e.data);

        if (!compressed.empty() &&
            compressed.size() + sizeof(TexCacheHeader) < e.data.size()) {
            payload = std::move(compressed);
            magic = TEXCACHE_MAGIC_C;
        }
    }

    const uint64_t limit = disk_limit_bytes();

    if (e.data.size() > max_texture_payload_bytes() ||
        payload.size() > max_texture_payload_bytes()) {
        LOGV("texture_cache: payload too large, skipping key %016llx",
             static_cast<unsigned long long>(e.key));
        return;
    }

    ensure_dir(g_settings.texture_cache_dir);

    // Over budget: evict oldest entries down to ~90% of the quota instead of
    // refusing every new write from now on.
    if (limit > 0 && disk_used_bytes_locked() + payload.size() > limit)
        evict_oldest_locked(limit - limit / 10);

    const std::string path = key_to_path(e.key);

    const auto tid_hash =
        static_cast<unsigned long long>(
            std::hash<std::thread::id>{}(std::this_thread::get_id()));

    const std::string tmp_path =
        path + ".tmp." +
        std::to_string(static_cast<long long>(getpid())) + "." +
        std::to_string(tid_hash);

    int fd = open(tmp_path.c_str(),
                  O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC,
                  0644);

    if (fd < 0) {
        LOGE("texture_cache: open tmp failed for %s: %s",
             tmp_path.c_str(), strerror(errno));
        return;
    }

    TexCacheHeader hdr{};
    hdr.magic      = magic;
    hdr.version    = TEXCACHE_VERSION;
    hdr.width      = e.width;
    hdr.height     = e.height;
    hdr.vk_format  = e.vk_format;
    hdr.mip_levels = e.mip_levels;
    hdr.data_size  = static_cast<uint64_t>(e.data.size());

    // Sem fsync de arquivo nem de diretório: cache hits NUNCA afetam o
    // rendering (só contam métricas), então durabilidade aqui não compra
    // nada — e o fsync por textura era um stall no meio da gravação.
    bool ok = write_all_fd(fd, &hdr, sizeof(hdr)) &&
              write_all_fd(fd, payload.data(), payload.size());

    if (close(fd) != 0)
        ok = false;

    if (!ok) {
        remove(tmp_path.c_str());
        LOGE("texture_cache: failed writing tmp %s", tmp_path.c_str());
        return;
    }

    if (rename(tmp_path.c_str(), path.c_str()) != 0) {
        LOGE("texture_cache: rename failed %s -> %s: %s",
             tmp_path.c_str(), path.c_str(), strerror(errno));
        remove(tmp_path.c_str());
        return;
    }

    LOGV("texture_cache: wrote raw=%zu stored=%zu to disk: %s",
         e.data.size(), payload.size(), path.c_str());
}

static bool read_from_disk(uint64_t key, TexEntry& out) {
    DiskFileLock file_lk(g_settings.texture_cache_dir, LOCK_SH);
    if (!file_lk) return false;

    const std::string path = key_to_path(key);

    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0)
        return false;

    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode)) {
        close(fd);
        return false;
    }

    if (st.st_size <= static_cast<off_t>(sizeof(TexCacheHeader))) {
        close(fd);
        return false;
    }

    if (static_cast<uint64_t>(st.st_size) >
        max_texture_payload_bytes() + sizeof(TexCacheHeader)) {
        close(fd);
        return false;
    }

    TexCacheHeader hdr{};

    ssize_t n = read(fd, &hdr, sizeof(hdr));
    if (n != static_cast<ssize_t>(sizeof(hdr))) {
        close(fd);
        return false;
    }

    if ((hdr.magic != TEXCACHE_MAGIC && hdr.magic != TEXCACHE_MAGIC_C) ||
        hdr.version != TEXCACHE_VERSION ||
        hdr.data_size == 0 ||
        hdr.data_size > max_texture_payload_bytes()) {
        close(fd);
        return false;
    }

    const size_t payload_size =
        static_cast<size_t>(st.st_size - static_cast<off_t>(sizeof(hdr)));

    if (payload_size == 0 || payload_size > max_texture_payload_bytes()) {
        close(fd);
        return false;
    }

    std::vector<uint8_t> payload;

    try {
        payload.resize(payload_size);
    } catch (...) {
        close(fd);
        return false;
    }

    size_t got = 0;
    while (got < payload.size()) {
        ssize_t r = read(fd, payload.data() + got, payload.size() - got);

        if (r < 0) {
            if (errno == EINTR)
                continue;

            close(fd);
            return false;
        }

        if (r == 0) {
            close(fd);
            return false;
        }

        got += static_cast<size_t>(r);
    }

    close(fd);

    TexEntry e{};
    e.key = key;
    e.width = hdr.width;
    e.height = hdr.height;
    e.vk_format = hdr.vk_format;
    e.mip_levels = hdr.mip_levels;

    if (hdr.magic == TEXCACHE_MAGIC_C) {
        if (!rle_decompress(payload,
                            static_cast<size_t>(hdr.data_size),
                            e.data)) {
            return false;
        }
    } else {
        if (payload.size() != static_cast<size_t>(hdr.data_size))
            return false;

        e.data = std::move(payload);
    }

    out = std::move(e);
    return true;
}

// ─────────────────────────────────────────────────────────────────────────────
// Record / lookup
// ─────────────────────────────────────────────────────────────────────────────

static bool texture_cache_record(const void* src_data,
                                 size_t src_size,
                                 uint32_t width,
                                 uint32_t height,
                                 VkFormat fmt,
                                 uint32_t mip_levels) {
    if (!src_data || src_size == 0)
        return false;

    if (!g_settings.upload_deduplication)
        return false;

    if (src_size > max_texture_payload_bytes())
        return false;

    const uint64_t key = xxhash::xxh64(src_data, src_size);

    {
        std::shared_lock<std::shared_mutex> rd(s_rw);

        if (s_ram_cache.find(key) != s_ram_cache.end()) {
            rd.unlock();

            std::unique_lock<std::shared_mutex> wr(s_rw);
            auto it = s_ram_cache.find(key);

            if (it != s_ram_cache.end()) {
                it->second.last_access = next_tick();
                perf_metrics_inc_texture_cache_hits();

                LOGV("texture_cache: RAM hit key=%016llx",
                     static_cast<unsigned long long>(key));
                return true;
            }
        }
    }

    TexEntry disk_entry{};

    if (read_from_disk(key, disk_entry)) {
        disk_entry.last_access = next_tick();

        std::unique_lock<std::shared_mutex> wr(s_rw);

        auto existing = s_ram_cache.find(key);
        if (existing != s_ram_cache.end()) {
            existing->second.last_access = next_tick();
        } else {
            s_ram_bytes += disk_entry.data.size();
            s_ram_cache[key] = std::move(disk_entry);
            evict_ram_if_needed();
        }

        perf_metrics_inc_texture_cache_hits();

        LOGV("texture_cache: disk hit key=%016llx (%.1f kB)",
             static_cast<unsigned long long>(key),
             src_size / 1024.0);

        return true;
    }

    perf_metrics_inc_texture_cache_misses();

    TexEntry e{};
    e.key = key;

    try {
        e.data.assign(static_cast<const uint8_t*>(src_data),
                      static_cast<const uint8_t*>(src_data) + src_size);
    } catch (...) {
        return false;
    }

    e.last_access = next_tick();
    e.width = width;
    e.height = height;
    e.vk_format = static_cast<uint32_t>(fmt);
    e.mip_levels = mip_levels;

    TexEntry e_copy = e;

    {
        std::unique_lock<std::shared_mutex> wr(s_rw);

        if (s_ram_cache.find(key) == s_ram_cache.end()) {
            s_ram_bytes += e.data.size();
            s_ram_cache[key] = std::move(e);
            evict_ram_if_needed();
        }
    }

    write_to_disk(e_copy);
    return false;
}

// ─────────────────────────────────────────────────────────────────────────────
// Driver identity namespace
// ─────────────────────────────────────────────────────────────────────────────

void texture_cache_set_driver_identity(const VkPhysicalDeviceProperties& props) {
    if (!g_settings.driver_versioned_cache)
        return;

    static std::mutex identity_lock;
    static bool applied = false;
    static std::string base_dir;

    std::lock_guard<std::mutex> lk(identity_lock);

    if (applied)
        return;

    base_dir = g_settings.texture_cache_dir;

    char suffix[256]{};
    snprintf(suffix,
             sizeof(suffix),
             "/%s/%u_%u_%u",
             g_settings.profile_name.empty() ? "default" : g_settings.profile_name.c_str(),
             props.vendorID,
             props.deviceID,
             props.driverVersion);

    g_settings.texture_cache_dir = base_dir + suffix;
    applied = true;
}

// ─────────────────────────────────────────────────────────────────────────────
// Buffer / memory tracking
// ─────────────────────────────────────────────────────────────────────────────

struct BufferMapping {
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkDeviceSize offset = 0;
    VkDeviceSize size = 0;
    void* host_ptr = nullptr;
};

struct BufferBinding {
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkDeviceSize memory_offset = 0;
    VkDeviceSize size = 0;
};

static std::mutex s_buf_lock;
static std::unordered_map<VkDeviceMemory, BufferMapping> s_mappings;
static std::unordered_map<VkBuffer, BufferBinding> s_buffer_bindings;

void texture_cache_on_device_destroyed() {
    // The maps are process-global (not keyed per device), so a destroyed
    // device drops them all — same discipline as the other per-device maps.
    std::lock_guard<std::mutex> lk(s_buf_lock);
    s_mappings.clear();
    s_buffer_bindings.clear();
}

void texture_cache_on_map_memory(VkDeviceMemory mem,
                                  VkDeviceSize offset,
                                  VkDeviceSize size,
                                  void* ptr) {
    if (mem == VK_NULL_HANDLE || !ptr)
        return;

    std::lock_guard<std::mutex> lk(s_buf_lock);
    s_mappings[mem] = { mem, offset, size, ptr };
}

void texture_cache_on_unmap_memory(VkDeviceMemory mem) {
    std::lock_guard<std::mutex> lk(s_buf_lock);
    s_mappings.erase(mem);
}

void texture_cache_on_create_buffer(VkBuffer buffer, VkDeviceSize size) {
    if (buffer == VK_NULL_HANDLE)
        return;

    std::lock_guard<std::mutex> lk(s_buf_lock);

    auto& b = s_buffer_bindings[buffer];
    b.size = size;
}

void texture_cache_on_destroy_buffer(VkBuffer buffer) {
    std::lock_guard<std::mutex> lk(s_buf_lock);
    s_buffer_bindings.erase(buffer);
}

void texture_cache_on_bind_buffer_memory(VkBuffer buffer,
                                         VkDeviceMemory memory,
                                         VkDeviceSize memory_offset) {
    if (buffer == VK_NULL_HANDLE)
        return;

    std::lock_guard<std::mutex> lk(s_buf_lock);

    auto& b = s_buffer_bindings[buffer];
    b.memory = memory;
    b.memory_offset = memory_offset;
}

// ─────────────────────────────────────────────────────────────────────────────
// Upload recording
// ─────────────────────────────────────────────────────────────────────────────

static uint64_t estimate_region_bytes(const VkBufferImageCopy& r) {
    const uint64_t w = r.imageExtent.width;
    const uint64_t h = r.imageExtent.height;
    const uint64_t d = std::max(1u, r.imageExtent.depth);

    // Format is unknown here without image tracking.
    // Use 4 Bpp as a conservative-ish common case.
    // This is an estimate for dedupe/hash sizing, not correctness logic.
    return w * h * d * 4ull;
}

static void try_record_upload(VkBuffer src_buffer,
                              VkImage dst_image,
                              uint32_t region_count,
                              const VkBufferImageCopy* regions) {
    (void)dst_image;

    if (g_settings.disable || !g_settings.upload_deduplication)
        return;

    if (src_buffer == VK_NULL_HANDLE || region_count == 0 || !regions)
        return;

    const VkBufferImageCopy& r0 = regions[0];

    const uint32_t w = r0.imageExtent.width;
    const uint32_t h = r0.imageExtent.height;

    uint64_t estimated = estimate_region_bytes(r0);

    const uint64_t fast_limit =
        static_cast<uint64_t>(g_settings.small_upload_fast_kb) * 1024ull;

    if (estimated > 0 && estimated <= fast_limit) {
        perf_metrics_inc_upload_fastpath_hits();
        return;
    }

    std::vector<uint8_t> upload_copy;

    {
        std::lock_guard<std::mutex> lk(s_buf_lock);

        auto bit = s_buffer_bindings.find(src_buffer);
        if (bit == s_buffer_bindings.end() ||
            bit->second.memory == VK_NULL_HANDLE) {
            return;
        }

        const BufferBinding& bind = bit->second;

        auto mit = s_mappings.find(bind.memory);
        if (mit == s_mappings.end() || !mit->second.host_ptr)
            return;

        const BufferMapping& map = mit->second;

        if (bind.size != 0 && r0.bufferOffset >= bind.size)
            return;

        const uint64_t src_start =
            static_cast<uint64_t>(bind.memory_offset) +
            static_cast<uint64_t>(r0.bufferOffset);

        const uint64_t map_start =
            static_cast<uint64_t>(map.offset);

        if (src_start < map_start)
            return;

        const uint64_t rel = src_start - map_start;

        uint64_t mapped_size =
            (map.size == VK_WHOLE_SIZE)
                ? std::numeric_limits<uint64_t>::max()
                : static_cast<uint64_t>(map.size);

        if (rel >= mapped_size)
            return;

        uint64_t max_available = mapped_size - rel;

        if (bind.size != 0 && r0.bufferOffset < bind.size) {
            const uint64_t buffer_available =
                static_cast<uint64_t>(bind.size - r0.bufferOffset);

            max_available = std::min(max_available, buffer_available);
        }

        uint64_t want = estimated ? estimated : max_available;

        if (want > max_available)
            want = max_available;

        // Recording cap: a multi-MB memcpy + RLE + directory stat + sync on
        // the command-buffer-recording thread is exactly the mid-frame hitch
        // this cache must not cause. Large atlases rarely dedupe anyway.
        if (want == 0 ||
            want > std::min<uint64_t>(max_texture_payload_bytes(),
                                      kMaxRecordedUploadBytes))
            return;

        const uint8_t* ptr =
            static_cast<const uint8_t*>(map.host_ptr) + rel;

        try {
            upload_copy.assign(ptr, ptr + static_cast<size_t>(want));
        } catch (...) {
            return;
        }
    }

    if (!upload_copy.empty() &&
        texture_cache_record(upload_copy.data(),
                             upload_copy.size(),
                             w,
                             h,
                             VK_FORMAT_UNDEFINED,
                             1u)) {
        perf_metrics_inc_upload_dedupe_hits();
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Public intercept helpers
// ─────────────────────────────────────────────────────────────────────────────

void texture_cache_cmd_copy_buffer_to_image(
    VkCommandBuffer cmd,
    VkBuffer src,
    VkImage dst,
    VkImageLayout /*dstLayout*/,
    uint32_t region_count,
    const VkBufferImageCopy* regions,
    const DeviceDispatch& /*dispatch*/) {
    try_record_upload(src, dst, region_count, regions);
    (void)cmd;
}

static VkBufferImageCopy copy2_to_copy1(const VkBufferImageCopy2& r) {
    VkBufferImageCopy out{};
    out.bufferOffset = r.bufferOffset;
    out.bufferRowLength = r.bufferRowLength;
    out.bufferImageHeight = r.bufferImageHeight;
    out.imageSubresource = r.imageSubresource;
    out.imageOffset = r.imageOffset;
    out.imageExtent = r.imageExtent;
    return out;
}

void texture_cache_cmd_copy_buffer_to_image2(
    VkCommandBuffer cmd,
    const VkCopyBufferToImageInfo2* info,
    const DeviceDispatch& /*dispatch*/) {
    if (info && info->regionCount > 0 && info->pRegions) {
        VkBufferImageCopy compat = copy2_to_copy1(info->pRegions[0]);
        try_record_upload(info->srcBuffer, info->dstImage, 1, &compat);
    }

    (void)cmd;
}

void texture_cache_cmd_copy_buffer_to_image2_khr(
    VkCommandBuffer cmd,
    const VkCopyBufferToImageInfo2* info,
    const DeviceDispatch& /*dispatch*/) {
    if (info && info->regionCount > 0 && info->pRegions) {
        VkBufferImageCopy compat = copy2_to_copy1(info->pRegions[0]);
        try_record_upload(info->srcBuffer, info->dstImage, 1, &compat);
    }

    (void)cmd;
}