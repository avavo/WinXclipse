// pipeline_cache.cpp
//
// Intercepts vkCreateDevice / vkDestroyDevice to maintain a private
// VkPipelineCache that is:
//   • loaded from disk on device creation, if a matching file exists
//   • injected as the pipelineCache argument for every
//     vkCreateGraphicsPipelines / vkCreateComputePipelines call
//   • persisted back to disk on device destruction
//
// Cache file name:
//   <pipeline_cache_dir>/<uuid_hex>_<driverVer>.bin
//
// The uuid is the 16-byte pipelineCacheUUID from VkPhysicalDeviceProperties,
// hex-encoded. The driverVersion is the raw uint32 in decimal.
//
// One cache handle is kept per VkDevice in s_entries.

#include "pipeline_cache.h"
#include "dispatch_table.h"
#include "layer_settings.h"
#include "log.h"

#include <cstdio>
#include <cstring>
#include <cerrno>
#include <cstdint>

#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

static constexpr size_t kMaxPipelineCacheBlobSize =
    64ull * 1024ull * 1024ull;

struct DeviceCacheEntry {
    VkPipelineCache cache = VK_NULL_HANDLE;
    std::string path;
    VkDevice device = VK_NULL_HANDLE;
};

static std::mutex s_lock;
static std::unordered_map<VkDevice, DeviceCacheEntry> s_entries;

// ─────────────────────────────────────────────────────────────────────────────
// Path helpers
// ─────────────────────────────────────────────────────────────────────────────

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
                LOGE("pipeline_cache: mkdir failed for %s: %s",
                     cur.c_str(), strerror(errno));
                return;
            }
        }

        if (next == std::string::npos)
            break;

        pos = next + 1;
    }
}

static std::string dirname_of(const std::string& path) {
    size_t slash = path.find_last_of('/');
    if (slash == std::string::npos) return {};
    if (slash == 0) return "/";
    return path.substr(0, slash);
}

static std::string make_cache_path(const VkPhysicalDeviceProperties& props) {
    char uuid_hex[33]{};

    for (int i = 0; i < 16; ++i) {
        snprintf(uuid_hex + i * 2, 3, "%02x",
                 static_cast<unsigned int>(
                     static_cast<uint8_t>(props.pipelineCacheUUID[i])));
    }

    char path[512]{};
    snprintf(path, sizeof(path), "%s/%s_%u.bin",
             g_settings.pipeline_cache_dir.c_str(),
             uuid_hex,
             props.driverVersion);

    return path;
}

static void fsync_parent_dir(const std::string& path) {
    std::string dir = dirname_of(path);
    if (dir.empty()) return;

    int fd = open(dir.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) return;

    fsync(fd);
    close(fd);
}

// ─────────────────────────────────────────────────────────────────────────────
// Cross-process file lock
// ─────────────────────────────────────────────────────────────────────────────

struct PipelineCacheFileLock {
    int fd = -1;

    explicit PipelineCacheFileLock(const std::string& dir, int mode) {
        ensure_dir(dir);

        std::string lock_path = dir + "/.pipelinecache.lock";
        fd = open(lock_path.c_str(), O_CREAT | O_RDWR | O_CLOEXEC, 0600);

        if (fd >= 0) {
            if (flock(fd, mode) != 0) {
                LOGE("pipeline_cache: flock failed for %s: %s",
                     lock_path.c_str(), strerror(errno));
                close(fd);
                fd = -1;
            }
        }
    }

    ~PipelineCacheFileLock() {
        if (fd >= 0) {
            flock(fd, LOCK_UN);
            close(fd);
        }
    }

    explicit operator bool() const {
        return fd >= 0;
    }

    PipelineCacheFileLock(const PipelineCacheFileLock&) = delete;
    PipelineCacheFileLock& operator=(const PipelineCacheFileLock&) = delete;
};

// ─────────────────────────────────────────────────────────────────────────────
// Disk I/O
// ─────────────────────────────────────────────────────────────────────────────

static std::vector<uint8_t> load_cache_file(const std::string& path) {
    PipelineCacheFileLock lk(g_settings.pipeline_cache_dir, LOCK_SH);
    if (!lk) return {};

    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return {};

    if (fseek(f, 0, SEEK_END) != 0) {
        fclose(f);
        return {};
    }

    long sz = ftell(f);
    if (sz <= 0 || static_cast<size_t>(sz) > kMaxPipelineCacheBlobSize) {
        fclose(f);
        return {};
    }

    if (fseek(f, 0, SEEK_SET) != 0) {
        fclose(f);
        return {};
    }

    std::vector<uint8_t> buf(static_cast<size_t>(sz));

    size_t n = fread(buf.data(), 1, buf.size(), f);
    fclose(f);

    if (n != buf.size())
        return {};

    return buf;
}

static bool save_cache_file(const std::string& path,
                            const void* data,
                            size_t size) {
    if (!data || size == 0 || size > kMaxPipelineCacheBlobSize)
        return false;

    ensure_dir(g_settings.pipeline_cache_dir);

    PipelineCacheFileLock lk(g_settings.pipeline_cache_dir, LOCK_EX);
    if (!lk) return false;

    const auto tid_hash =
        static_cast<unsigned long long>(
            std::hash<std::thread::id>{}(std::this_thread::get_id()));

    std::string tmp =
        path + ".tmp." +
        std::to_string(static_cast<long long>(getpid())) + "." +
        std::to_string(tid_hash);

    int fd = open(tmp.c_str(),
                  O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC,
                  0644);

    if (fd < 0) {
        LOGE("pipeline_cache: open tmp failed for %s: %s",
             tmp.c_str(), strerror(errno));
        return false;
    }

    const uint8_t* p = static_cast<const uint8_t*>(data);
    size_t remaining = size;

    while (remaining > 0) {
        ssize_t written = write(fd, p, remaining);
        if (written < 0) {
            if (errno == EINTR) continue;

            LOGE("pipeline_cache: write failed for %s: %s",
                 tmp.c_str(), strerror(errno));
            close(fd);
            remove(tmp.c_str());
            return false;
        }

        if (written == 0) {
            LOGE("pipeline_cache: short write for %s", tmp.c_str());
            close(fd);
            remove(tmp.c_str());
            return false;
        }

        p += written;
        remaining -= static_cast<size_t>(written);
    }

    if (fsync(fd) != 0) {
        LOGE("pipeline_cache: fsync failed for %s: %s",
             tmp.c_str(), strerror(errno));
        close(fd);
        remove(tmp.c_str());
        return false;
    }

    if (close(fd) != 0) {
        LOGE("pipeline_cache: close failed for %s: %s",
             tmp.c_str(), strerror(errno));
        remove(tmp.c_str());
        return false;
    }

    if (rename(tmp.c_str(), path.c_str()) != 0) {
        LOGE("pipeline_cache: rename %s -> %s failed: %s",
             tmp.c_str(), path.c_str(), strerror(errno));
        remove(tmp.c_str());
        return false;
    }

    fsync_parent_dir(path);
    return true;
}

// ─────────────────────────────────────────────────────────────────────────────
// Public API
// ─────────────────────────────────────────────────────────────────────────────

void pipeline_cache_on_device_created(
    VkDevice device,
    VkPhysicalDevice phys_dev,
    const DeviceDispatch& dispatch,
    const VkPhysicalDeviceProperties& props)
{
    (void)phys_dev;

    if (g_settings.disable) return;

    if (!dispatch.CreatePipelineCache) {
        LOGE("pipeline_cache: missing vkCreatePipelineCache dispatch");
        return;
    }

    ensure_dir(g_settings.pipeline_cache_dir);

    std::string path = make_cache_path(props);
    auto blob = load_cache_file(path);

    VkPipelineCacheCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
    ci.initialDataSize = blob.size();
    ci.pInitialData = blob.empty() ? nullptr : blob.data();

    VkPipelineCache cache = VK_NULL_HANDLE;

    VkResult r = dispatch.CreatePipelineCache(device, &ci, nullptr, &cache);

    if (r != VK_SUCCESS && !blob.empty()) {
        LOGE("pipeline_cache: cached blob rejected (%d), retrying empty cache: %s",
             r, path.c_str());

        remove(path.c_str());

        VkPipelineCacheCreateInfo empty_ci{};
        empty_ci.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;

        cache = VK_NULL_HANDLE;
        r = dispatch.CreatePipelineCache(device, &empty_ci, nullptr, &cache);
    }

    if (r != VK_SUCCESS || cache == VK_NULL_HANDLE) {
        LOGE("pipeline_cache: vkCreatePipelineCache failed (%d) for %s",
             r, path.c_str());
        return;
    }

    LOGI("pipeline_cache: loaded %zu bytes from %s",
         blob.size(), path.c_str());

    std::lock_guard<std::mutex> lk(s_lock);
    s_entries[device] = { cache, path, device };
}

void pipeline_cache_on_device_destroyed(
    VkDevice device,
    const DeviceDispatch& dispatch)
{
    DeviceCacheEntry entry{};

    {
        std::lock_guard<std::mutex> lk(s_lock);

        auto it = s_entries.find(device);
        if (it == s_entries.end())
            return;

        entry = it->second;
        s_entries.erase(it);
    }

    if (entry.cache == VK_NULL_HANDLE)
        return;

    if (!dispatch.GetPipelineCacheData || !dispatch.DestroyPipelineCache) {
        LOGE("pipeline_cache: missing get-data/destroy dispatch; cache handle cannot be saved cleanly");
        if (dispatch.DestroyPipelineCache)
            dispatch.DestroyPipelineCache(device, entry.cache, nullptr);
        return;
    }

    size_t data_size = 0;

    VkResult r = dispatch.GetPipelineCacheData(
        device, entry.cache, &data_size, nullptr);

    if (r == VK_SUCCESS &&
        data_size > 0 &&
        data_size <= kMaxPipelineCacheBlobSize) {
        std::vector<uint8_t> data(data_size);

        size_t out_size = data_size;
        r = dispatch.GetPipelineCacheData(
            device, entry.cache, &out_size, data.data());

        if (r == VK_SUCCESS && out_size > 0 && out_size <= data.size()) {
            data.resize(out_size);

            if (save_cache_file(entry.path, data.data(), data.size())) {
                LOGI("pipeline_cache: persisted %zu bytes to %s",
                     data.size(), entry.path.c_str());
            } else {
                LOGE("pipeline_cache: failed to write %s",
                     entry.path.c_str());
            }
        } else {
            LOGE("pipeline_cache: GetPipelineCacheData copy failed (%d, size=%zu)",
                 r, out_size);
        }
    } else if (r != VK_SUCCESS) {
        LOGE("pipeline_cache: GetPipelineCacheData size query failed (%d)", r);
    } else if (data_size > kMaxPipelineCacheBlobSize) {
        LOGE("pipeline_cache: refusing huge cache blob size=%zu", data_size);
    }

    dispatch.DestroyPipelineCache(device, entry.cache, nullptr);
}

VkPipelineCache pipeline_cache_get(VkDevice device) {
    std::lock_guard<std::mutex> lk(s_lock);

    auto it = s_entries.find(device);
    return (it != s_entries.end()) ? it->second.cache : VK_NULL_HANDLE;
}