#include "wxp_core.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>
#include <pthread.h>
#include <ctype.h>

#ifdef __ANDROID__
#include <android/log.h>
#include <sys/system_properties.h>
#endif

static WinXclipsePolicyDeviceInfo g_info = {
    "unknown", "unknown", "unknown", "unknown", WXP_CPU_UNKNOWN, 0, "ram_unknown", "native_fallback"
};

typedef enum WinXclipsePolicyInitSource {
    WXP_INIT_NONE = 0,
    WXP_INIT_AUTO = 1,
    WXP_INIT_META = 2,
    WXP_INIT_META_FALLBACK = 3
} WinXclipsePolicyInitSource;

static atomic_int g_initialized = 0;
static atomic_int g_init_source = WXP_INIT_NONE;
static atomic_uint g_hints[WXP_HINT_MAX];
static pthread_mutex_t g_info_lock = PTHREAD_MUTEX_INITIALIZER;
static _Thread_local WinXclipsePolicyDeviceInfo g_tls_info;

static void core_log(const char* msg) {
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "WinXclipsePolicyCore", "%s", msg ? msg : "");
#else
    fprintf(stderr, "[WinXclipsePolicyCore] %s\n", msg ? msg : "");
#endif
}

void wxp_log_line(const char* tag, const char* message) {
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, tag ? tag : "WinXclipsePolicy", "%s", message ? message : "");
#else
    fprintf(stderr, "[%s] %s\n", tag ? tag : "WinXclipsePolicy", message ? message : "");
#endif
}

static void reset_info(void) {
    g_info.soc = "unknown";
    g_info.gpu = "unknown";
    g_info.gpu_arch = "unknown";
    g_info.cpu_topology = "unknown";
    g_info.cpu_class = WXP_CPU_UNKNOWN;
    g_info.ram_gb = 0;
    g_info.ram_class = "ram_unknown";
    g_info.package_profile = "native_fallback";
}

static int read_prop(const char* key, char* out, size_t out_sz) {
    if (!key || !out || out_sz == 0) return 0;
    out[0] = 0;
#ifdef __ANDROID__
    int n = __system_property_get(key, out);
    if (n > 0) return 1;
#endif
    const char* env = getenv(key);
    if (env && env[0]) {
        snprintf(out, out_sz, "%s", env);
        return 1;
    }
    return 0;
}

static int ascii_lower(int ch) {
    return tolower((unsigned char)ch);
}

static int contains_ci(const char* s, const char* needle) {
    if (!s || !needle || !*needle) return 0;
    const size_t nlen = strlen(needle);
    for (const char* p = s; *p; ++p) {
        size_t i = 0;
        while (i < nlen && p[i] && ascii_lower(p[i]) == ascii_lower(needle[i])) {
            ++i;
        }
        if (i == nlen) return 1;
    }
    return 0;
}

static int contains_any(const char* s, const char* a, const char* b, const char* c) {
    if (!s) return 0;
    return (a && contains_ci(s, a)) || (b && contains_ci(s, b)) || (c && contains_ci(s, c));
}

static int detect_ram_gb(void) {
    FILE* f = fopen("/proc/meminfo", "r");
    if (!f) return 0;
    char line[256];
    long kb = 0;
    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "MemTotal: %ld kB", &kb) == 1) break;
    }
    fclose(f);
    if (kb <= 0) return 0;
    long gb = (kb + 1024L * 1024L - 1) / (1024L * 1024L);
    return (int)gb;
}

static char* read_text_file(const char* path) {
    if (!path || !path[0]) return NULL;

    FILE* f = fopen(path, "rb");
    if (!f) return NULL;

    if (fseek(f, 0, SEEK_END) != 0) {
        fclose(f);
        return NULL;
    }

    long len = ftell(f);
    if (len <= 0 || len > (1024L * 1024L)) {
        fclose(f);
        return NULL;
    }

    if (fseek(f, 0, SEEK_SET) != 0) {
        fclose(f);
        return NULL;
    }

    char* buf = (char*)calloc((size_t)len + 1u, 1u);
    if (!buf) {
        fclose(f);
        return NULL;
    }

    size_t got = fread(buf, 1, (size_t)len, f);
    fclose(f);

    if (got == 0) {
        free(buf);
        return NULL;
    }

    buf[got] = 0;
    return buf;
}

static int json_string_value(const char* text, const char* key, char* out, size_t out_sz) {
    if (!text || !key || !out || out_sz == 0) return 0;
    out[0] = 0;

    char pat[96];
    snprintf(pat, sizeof(pat), "\"%s\"", key);

    const char* p = text;
    while ((p = strstr(p, pat)) != NULL) {
        const char* colon = strchr(p + strlen(pat), ':');
        if (!colon) return 0;
        const char* q = colon + 1;
        while (*q == ' ' || *q == '\t' || *q == '\r' || *q == '\n') ++q;
        if (*q != '"') { p = q; continue; }
        ++q;
        size_t n = 0;
        int esc = 0;
        while (*q && (esc || *q != '"')) {
            if (n + 1 < out_sz) out[n++] = *q;
            if (esc) esc = 0;
            else if (*q == '\\') esc = 1;
            ++q;
        }
        out[n] = 0;
        return n > 0;
    }
    return 0;
}

static int json_string_value_top_level(const char* text, const char* key, char* out, size_t out_sz) {
    if (!text || !key || !out || out_sz == 0) return 0;
    out[0] = 0;

    const size_t key_len = strlen(key);
    int depth = 0;
    int in_str = 0;
    int esc = 0;

    for (const char* p = text; *p; ++p) {
        char c = *p;

        if (in_str) {
            if (esc) {
                esc = 0;
            } else if (c == '\\') {
                esc = 1;
            } else if (c == '"') {
                in_str = 0;
            }
            continue;
        }

        if (c == '"') {
            const char* start = p + 1;
            const char* q = start;
            int key_esc = 0;
            while (*q && (key_esc || *q != '"')) {
                if (key_esc) key_esc = 0;
                else if (*q == '\\') key_esc = 1;
                ++q;
            }
            if (!*q) break;

            if (depth == 1 && (size_t)(q - start) == key_len && strncmp(start, key, key_len) == 0) {
                const char* colon = q + 1;
                while (*colon == ' ' || *colon == '\t' || *colon == '\r' || *colon == '\n') ++colon;
                if (*colon != ':') { p = q; continue; }
                const char* v = colon + 1;
                while (*v == ' ' || *v == '\t' || *v == '\r' || *v == '\n') ++v;
                if (*v != '"') { p = q; continue; }
                ++v;
                size_t n = 0;
                int val_esc = 0;
                while (*v && (val_esc || *v != '"')) {
                    if (n + 1 < out_sz) out[n++] = *v;
                    if (val_esc) val_esc = 0;
                    else if (*v == '\\') val_esc = 1;
                    ++v;
                }
                out[n] = 0;
                return n > 0;
            }

            p = q;
            continue;
        }

        if (c == '{' || c == '[') ++depth;
        else if ((c == '}' || c == ']') && depth > 0) --depth;
    }

    return 0;
}

static int extract_object_for_key_top_level(const char* text, const char* key, char* out, size_t out_sz) {
    if (!text || !key || !out || out_sz == 0) return 0;
    out[0] = 0;

    const size_t key_len = strlen(key);
    int depth = 0;
    int in_str = 0;
    int esc = 0;

    for (const char* p = text; *p; ++p) {
        char c = *p;

        if (in_str) {
            if (esc) esc = 0;
            else if (c == '\\') esc = 1;
            else if (c == '"') in_str = 0;
            continue;
        }

        if (c == '"') {
            const char* start = p + 1;
            const char* q = start;
            int key_esc = 0;
            while (*q && (key_esc || *q != '"')) {
                if (key_esc) key_esc = 0;
                else if (*q == '\\') key_esc = 1;
                ++q;
            }
            if (!*q) break;

            if (depth == 1 && (size_t)(q - start) == key_len && strncmp(start, key, key_len) == 0) {
                const char* colon = q + 1;
                while (*colon == ' ' || *colon == '\t' || *colon == '\r' || *colon == '\n') ++colon;
                if (*colon != ':') { p = q; continue; }
                const char* obj = colon + 1;
                while (*obj == ' ' || *obj == '\t' || *obj == '\r' || *obj == '\n') ++obj;
                if (*obj != '{') { p = q; continue; }

                const char* end = obj;
                int obj_depth = 0;
                int obj_str = 0;
                int obj_esc = 0;
                for (; *end; ++end) {
                    char oc = *end;
                    if (obj_str) {
                        if (obj_esc) obj_esc = 0;
                        else if (oc == '\\') obj_esc = 1;
                        else if (oc == '"') obj_str = 0;
                        continue;
                    }
                    if (oc == '"') { obj_str = 1; continue; }
                    if (oc == '{') ++obj_depth;
                    else if (oc == '}') {
                        --obj_depth;
                        if (obj_depth == 0) {
                            size_t n = (size_t)(end - obj + 1);
                            if (n >= out_sz) n = out_sz - 1;
                            memcpy(out, obj, n);
                            out[n] = 0;
                            return n > 0;
                        }
                    }
                }
                return 0;
            }

            p = q;
            continue;
        }

        if (c == '{' || c == '[') ++depth;
        else if ((c == '}' || c == ']') && depth > 0) --depth;
    }

    return 0;
}

static int meta_value_names_specific_enough(const char* value) {
    if (!value || !value[0]) return 0;
    /* Must identify a concrete SoC. Generic text like
     * "Samsung Exynos / Xclipse" is not a target; it is a brochure. */
    return contains_any(value, "1480", "1580", "1680") ||
           contains_any(value, "2200", "2400", "2500") ||
           contains_any(value, "2600", "s5e", "S5E");
}

static int copy_if_specific(const char* value, char* out, size_t out_sz) {
    if (!meta_value_names_specific_enough(value)) return 0;
    snprintf(out, out_sz, "%s", value);
    return 1;
}

static int json_specific_string_value(const char* text, const char* key, char* out, size_t out_sz) {
    char value[128];
    if (!json_string_value(text, key, value, sizeof(value))) return 0;
    return copy_if_specific(value, out, out_sz);
}

static int json_specific_string_value_top_level(const char* text, const char* key, char* out, size_t out_sz) {
    char value[128];
    if (!json_string_value_top_level(text, key, value, sizeof(value))) return 0;
    return copy_if_specific(value, out, out_sz);
}

static int extract_meta_soc(const char* meta, char* out, size_t out_sz) {
    if (!meta || !out || out_sz == 0) return 0;
    out[0] = 0;

    /* Prefer an explicit target object over root marketing fields. Never treat
     * supportedSoCs/support lists as the active target; otherwise a generic
     * meta.json politely becomes 1480 and ruins everybody's afternoon. */
    char target_obj[1024];
    if (extract_object_for_key_top_level(meta, "target", target_obj, sizeof(target_obj))) {
        if (json_specific_string_value(target_obj, "soc", out, out_sz)) return 1;
        if (json_specific_string_value(target_obj, "SoC", out, out_sz)) return 1;
        if (json_specific_string_value(target_obj, "target_soc", out, out_sz)) return 1;
        if (json_specific_string_value(target_obj, "targetSoC", out, out_sz)) return 1;
    }

    const char* keys[] = { "target_soc", "targetSoc", "targetSoC", "target_soc_name" };
    for (size_t i = 0; i < sizeof(keys) / sizeof(keys[0]); ++i) {
        if (json_specific_string_value_top_level(meta, keys[i], out, out_sz)) return 1;
    }

    /* Root-level "soc" is allowed only at object depth 1 and only if concrete.
     * Nested supportedSoCs entries are ignored. Tiny parser, tiny ego. */
    if (json_specific_string_value_top_level(meta, "soc", out, out_sz)) return 1;
    if (json_specific_string_value_top_level(meta, "SoC", out, out_sz)) return 1;

    return 0;
}

static void classify_from_soc(const char* soc_raw) {
    const char* s = soc_raw ? soc_raw : "";

    if (contains_any(s, "1480", "s5e8845", "exynos1480")) {
        g_info.soc = "exynos1480";
        g_info.gpu = "xclipse530";
        g_info.gpu_arch = "rdna2";
        g_info.cpu_topology = "4+4";
        g_info.cpu_class = WXP_CPU_OCTA;
        g_info.package_profile = "exynos1480_safe";
    } else if (contains_any(s, "1580", "s5e8855", "exynos1580")) {
        g_info.soc = "exynos1580";
        g_info.gpu = "xclipse540";
        g_info.gpu_arch = "rdna3";
        g_info.cpu_topology = "1+3+4";
        g_info.cpu_class = WXP_CPU_OCTA;
        g_info.package_profile = "exynos1580_balanced";
    } else if (contains_any(s, "1680", "s5e8865", "exynos1680")) {
        g_info.soc = "exynos1680";
        g_info.gpu = "xclipse550";
        g_info.gpu_arch = "rdna3";
        g_info.cpu_topology = "1+4+3";
        g_info.cpu_class = WXP_CPU_OCTA;
        g_info.package_profile = "exynos1680_balanced";
    } else if (contains_any(s, "2200", "s5e9925", "exynos2200")) {
        g_info.soc = "exynos2200";
        g_info.gpu = "xclipse920";
        g_info.gpu_arch = "rdna2";
        g_info.cpu_topology = "1+3+4";
        g_info.cpu_class = WXP_CPU_OCTA;
        g_info.package_profile = "exynos2200_safe";
    } else if (contains_any(s, "2400", "s5e9945", "exynos2400")) {
        g_info.soc = "exynos2400";
        g_info.gpu = "xclipse940";
        g_info.gpu_arch = "rdna3";
        g_info.cpu_topology = "1+2+3+4";
        g_info.cpu_class = WXP_CPU_DECA;
        g_info.package_profile = "exynos2400_auto";
    } else if (contains_any(s, "2500", "s5e9955", "exynos2500")) {
        g_info.soc = "exynos2500";
        g_info.gpu = "xclipse950";
        g_info.gpu_arch = "rdna3";
        g_info.cpu_topology = "deca";
        g_info.cpu_class = WXP_CPU_DECA;
        g_info.package_profile = "exynos2500_12gb_safe";
    } else if (contains_any(s, "2600", "s5e9965", "exynos2600")) {
        g_info.soc = "exynos2600";
        g_info.gpu = "xclipse960";
        g_info.gpu_arch = "rdna4";
        g_info.cpu_topology = "deca";
        g_info.cpu_class = WXP_CPU_DECA;
        g_info.package_profile = "exynos2600_12gb_safe";
    }
}


static void classify_from_gpu(const char* gpu_raw) {
    const char* g = gpu_raw ? gpu_raw : "";

    if (contains_ci(g, "xclipse")) {
        if (contains_ci(g, "530")) classify_from_soc("exynos1480");
        else if (contains_ci(g, "540")) classify_from_soc("exynos1580");
        else if (contains_ci(g, "550")) classify_from_soc("exynos1680");
        else if (contains_ci(g, "920")) classify_from_soc("exynos2200");
        else if (contains_ci(g, "940")) classify_from_soc("exynos2400");
        else if (contains_ci(g, "950")) classify_from_soc("exynos2500");
        else if (contains_ci(g, "960")) classify_from_soc("exynos2600");
    }
}

static int extract_meta_gpu(const char* meta, char* out, size_t out_sz) {
    if (!meta || !out || out_sz == 0) return 0;
    out[0] = 0;

    char target_obj[1024];
    if (extract_object_for_key_top_level(meta, "target", target_obj, sizeof(target_obj))) {
        if (json_string_value(target_obj, "gpu", out, out_sz)) return 1;
        if (json_string_value(target_obj, "GPU", out, out_sz)) return 1;
        if (json_string_value(target_obj, "target_gpu", out, out_sz)) return 1;
        if (json_string_value(target_obj, "targetGpu", out, out_sz)) return 1;
        if (json_string_value(target_obj, "targetGPU", out, out_sz)) return 1;
    }

    const char* keys[] = { "target_gpu", "targetGpu", "targetGPU", "gpu", "GPU" };
    for (size_t i = 0; i < sizeof(keys) / sizeof(keys[0]); ++i) {
        if (json_string_value_top_level(meta, keys[i], out, out_sz)) return 1;
    }
    return 0;
}

static void finalize_ram_and_profile(int ram) {
    g_info.ram_gb = ram;

    if (ram <= 0) {
        g_info.ram_class = "ram_unknown";
    } else if (ram <= 8) {
        g_info.ram_class = "ram_8gb";
    } else if (ram < 12) {
        g_info.ram_class = "ram_10gb_plus";
    } else {
        g_info.ram_class = "ram_12gb";
    }

    if (strcmp(g_info.soc, "exynos2400") == 0) {
        g_info.package_profile =
            (ram > 0 && ram <= 8) ? "exynos2400_8gb" :
            (ram > 8) ? "exynos2400_12gb" :
            "exynos2400_auto";
    }
    else if (ram > 0 && ram <= 8 &&
             strstr(g_info.package_profile, "_12gb_") != NULL) {
        /* O sufixo _12gb_safe é uma reivindicação real de recurso: um device
         * de 8 GB não deve recebê-lo só porque o SoC tem variante de 12 GB.
         * Cai para o perfil genérico que os guests já conhecem (o mesmo dos
         * SoCs desconhecidos) em vez de anunciar uma classe de memória que
         * o hardware não tem. */
        g_info.package_profile = "native_fallback";
    }
}

static void publish_profile_env_unlocked(void) {
    const char* cur = getenv("MDIEX_PROFILE");
    const char* owner = getenv("MDIEX_PROFILE_SOURCE");

    /* Do not overwrite a profile explicitly set by the user/emulator. If WinXclipsePolicy
     * itself published the previous value, keep it in sync after auto->meta or
     * meta-fallback->meta overrides. Stale env is worse than no env: it lies. */
    if (!cur || !cur[0] || (owner && strcmp(owner, "mdiex") == 0)) {
        setenv("MDIEX_PROFILE", g_info.package_profile, 1);
        setenv("MDIEX_PROFILE_SOURCE", "mdiex", 1);
    }
}

static int wxp_init_common(const char* meta_path, WinXclipsePolicyInitSource source) {
    pthread_mutex_lock(&g_info_lock);

    int old_source = atomic_load(&g_init_source);
    if (atomic_load(&g_initialized)) {
        const int can_meta_override =
            (source == WXP_INIT_META) &&
            (old_source == WXP_INIT_AUTO || old_source == WXP_INIT_META_FALLBACK);
        if (!can_meta_override) {
            pthread_mutex_unlock(&g_info_lock);
            return 1;
        }
        wxp_log_line("WinXclipsePolicyCore", "meta init overriding earlier auto/meta-fallback init");
    }

    reset_info();
    int used_meta = 0;
    int meta_read_ok = 0;

    if (meta_path && meta_path[0]) {
        char* meta = read_text_file(meta_path);
        if (meta) {
            meta_read_ok = 1;
            char explicit_soc[128];
            if (extract_meta_soc(meta, explicit_soc, sizeof(explicit_soc))) {
                classify_from_soc(explicit_soc);
                used_meta = strcmp(g_info.soc, "unknown") != 0;
            }
            if (!used_meta) {
                char explicit_gpu[128];
                if (extract_meta_gpu(meta, explicit_gpu, sizeof(explicit_gpu))) {
                    classify_from_gpu(explicit_gpu);
                    used_meta = strcmp(g_info.soc, "unknown") != 0;
                }
            }
            free(meta);
            if (!used_meta) {
                wxp_log_line("WinXclipsePolicyCore", "meta parsed, but no explicit known Exynos target found; using auto detection");
            }
        } else {
            wxp_log_line("WinXclipsePolicyCore", "meta path could not be read; using auto detection");
        }
    }

    if (!used_meta) {
        char prop[128];
        const char* keys[] = {
            "ro.soc.model", "ro.board.platform", "ro.hardware", "ro.product.board", "ro.vendor.product.device"
        };
        for (size_t i = 0; i < sizeof(keys)/sizeof(keys[0]); ++i) {
            if (read_prop(keys[i], prop, sizeof(prop))) {
                classify_from_soc(prop);
                if (strcmp(g_info.soc, "unknown") != 0) break;
            }
        }
    }

    int ram = detect_ram_gb();
    finalize_ram_and_profile(ram);

    atomic_store(&g_initialized, 1);
    atomic_store(&g_init_source, used_meta ? WXP_INIT_META :
                 ((source == WXP_INIT_META && meta_read_ok) ? WXP_INIT_META_FALLBACK : WXP_INIT_AUTO));

    char msg[320];
    snprintf(msg, sizeof(msg), "soc=%s gpu=%s arch=%s cpu=%s ram=%dGB ram_class=%s profile=%s source=%s",
             g_info.soc, g_info.gpu, g_info.gpu_arch, g_info.cpu_topology,
             g_info.ram_gb, g_info.ram_class, g_info.package_profile,
             used_meta ? "meta" : ((source == WXP_INIT_META && meta_read_ok) ? "meta-fallback" : "auto"));
    publish_profile_env_unlocked();
    core_log(msg);
    pthread_mutex_unlock(&g_info_lock);
    return 1;
}

int wxp_init_from_meta(const char* meta_path) {
    if (!meta_path || !meta_path[0]) {
        wxp_log_line("WinXclipsePolicyCore", "empty meta path; using auto detection");
        return wxp_init_auto();
    }
    return wxp_init_common(meta_path, WXP_INIT_META);
}

int wxp_init_auto(void) {
    return wxp_init_common(NULL, WXP_INIT_AUTO);
}

int wxp_init_from_env_or_auto(void) {
    const char* meta = getenv("MDIEX_META_PATH");
    if (meta && meta[0]) return wxp_init_from_meta(meta);
    return wxp_init_auto();
}

const WinXclipsePolicyDeviceInfo* wxp_get_device_info(void) {
    wxp_init_from_env_or_auto();
    pthread_mutex_lock(&g_info_lock);
    g_tls_info = g_info;
    pthread_mutex_unlock(&g_info_lock);
    return &g_tls_info;
}
const char* wxp_get_soc_name(void) { return wxp_get_device_info()->soc; }
const char* wxp_get_gpu_name(void) { return wxp_get_device_info()->gpu; }
const char* wxp_get_gpu_arch(void) { return wxp_get_device_info()->gpu_arch; }
const char* wxp_get_cpu_topology(void) { return wxp_get_device_info()->cpu_topology; }
int wxp_get_cpu_class(void) { return wxp_get_device_info()->cpu_class; }
int wxp_get_ram_gb(void) { return wxp_get_device_info()->ram_gb; }
const char* wxp_get_ram_class(void) { return wxp_get_device_info()->ram_class; }
const char* wxp_get_profile_name(void) { return wxp_get_device_info()->package_profile; }

void wxp_send_hint(int hint) {
    if (hint <= 0 || hint >= WXP_HINT_MAX) return;
    unsigned int old = atomic_load(&g_hints[hint]);
    while (old < 0x7fffffffu &&
           !atomic_compare_exchange_weak(&g_hints[hint], &old, old + 1u)) {
        /* retry with updated old */
    }
}

int wxp_get_hint_state(int hint) {
    if (hint > 0 && hint < WXP_HINT_MAX) return atomic_load(&g_hints[hint]) > 0u;
    return 0;
}

int wxp_consume_hint(int hint) {
    if (hint > 0 && hint < WXP_HINT_MAX) return (int)atomic_exchange(&g_hints[hint], 0u);
    return 0;
}

void wxp_clear_hint(int hint) {
    if (hint > 0 && hint < WXP_HINT_MAX) atomic_store(&g_hints[hint], 0u);
}

__attribute__((constructor)) static void wxp_core_ctor(void) {
    const char* meta = getenv("MDIEX_META_PATH");
    if (meta && meta[0]) {
        wxp_init_from_meta(meta);
    } else {
        wxp_log_line("WinXclipsePolicyCore", "constructor ready; deferred auto init until first query");
    }
}
