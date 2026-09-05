#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define TAG "PulseAudioReload"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define PA_CONTEXT_READY 4
#define PA_CONTEXT_FAILED 5
#define PA_CONTEXT_TERMINATED 6
#define PA_CONTEXT_NOAUTOSPAWN 1
#define PA_OPERATION_RUNNING 0
#define PA_OPERATION_DONE 1
#define PA_INVALID_INDEX UINT32_MAX
#define PA_VOLUME_NORM 65536U
#define PA_CHANNELS_MAX 32
#define MAX_AAUDIO_MODULES 16
#define MAX_SINK_INPUTS 128
#define OPERATION_TIMEOUT_MS 2500

typedef struct pa_mainloop pa_mainloop;
typedef struct pa_mainloop_api pa_mainloop_api;
typedef struct pa_context pa_context;
typedef struct pa_operation pa_operation;
typedef struct pa_proplist pa_proplist;

typedef struct pa_module_info {
    uint32_t index;
    const char *name;
    const char *argument;
    uint32_t n_used;
    pa_proplist *proplist;
} pa_module_info;

/* Only the first ABI field is used by the callback below. */
typedef struct pa_sink_input_info {
    uint32_t index;
} pa_sink_input_info;

typedef struct pa_cvolume {
    uint8_t channels;
    uint32_t values[PA_CHANNELS_MAX];
} pa_cvolume;

typedef void (*pa_module_info_cb_t)(pa_context *, const pa_module_info *, int, void *);
typedef void (*pa_sink_input_info_cb_t)(pa_context *, const pa_sink_input_info *, int, void *);
typedef void (*pa_context_success_cb_t)(pa_context *, int, void *);
typedef void (*pa_context_index_cb_t)(pa_context *, uint32_t, void *);

typedef struct pulse_api {
    pa_mainloop *(*mainloop_new)(void);
    void (*mainloop_free)(pa_mainloop *);
    pa_mainloop_api *(*mainloop_get_api)(pa_mainloop *);
    int (*mainloop_iterate)(pa_mainloop *, int, int *);
    pa_context *(*context_new)(pa_mainloop_api *, const char *);
    int (*context_connect)(pa_context *, const char *, int, const void *);
    int (*context_get_state)(const pa_context *);
    void (*context_disconnect)(pa_context *);
    void (*context_unref)(pa_context *);
    int (*context_errno)(const pa_context *);
    const char *(*strerror_fn)(int);
    pa_operation *(*context_get_module_info_list)(pa_context *, pa_module_info_cb_t, void *);
    pa_operation *(*context_load_module)(pa_context *, const char *, const char *, pa_context_index_cb_t, void *);
    pa_operation *(*context_unload_module)(pa_context *, uint32_t, pa_context_success_cb_t, void *);
    pa_operation *(*context_get_sink_input_info_list)(pa_context *, pa_sink_input_info_cb_t, void *);
    pa_operation *(*context_move_sink_input_by_name)(pa_context *, uint32_t, const char *, pa_context_success_cb_t, void *);
    pa_operation *(*context_set_default_sink)(pa_context *, const char *, pa_context_success_cb_t, void *);
    pa_operation *(*context_set_sink_volume_by_name)(pa_context *, const char *, const pa_cvolume *, pa_context_success_cb_t, void *);
    pa_operation *(*context_set_sink_mute_by_name)(pa_context *, const char *, int, pa_context_success_cb_t, void *);
    int (*operation_get_state)(const pa_operation *);
    void (*operation_unref)(pa_operation *);
} pulse_api;

typedef struct module_scan {
    uint32_t indices[MAX_AAUDIO_MODULES];
    char arguments[MAX_AAUDIO_MODULES][128];
    unsigned count;
    int failed;
} module_scan;

typedef struct sink_input_scan {
    uint32_t indices[MAX_SINK_INPUTS];
    unsigned count;
    int failed;
} sink_input_scan;

typedef struct operation_result {
    int called;
    int success;
} operation_result;

typedef struct index_result {
    int called;
    uint32_t index;
} index_result;

static int64_t monotonic_ms(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (int64_t) now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static int load_pulse_api(void *handle, pulse_api *api) {
#define LOAD(field, symbol) do { \
    *(void **) (&api->field) = dlsym(handle, #symbol); \
    if (!api->field) { LOGE("missing libpulse symbol %s", #symbol); return 0; } \
} while (0)
    memset(api, 0, sizeof(*api));
    LOAD(mainloop_new, pa_mainloop_new);
    LOAD(mainloop_free, pa_mainloop_free);
    LOAD(mainloop_get_api, pa_mainloop_get_api);
    LOAD(mainloop_iterate, pa_mainloop_iterate);
    LOAD(context_new, pa_context_new);
    LOAD(context_connect, pa_context_connect);
    LOAD(context_get_state, pa_context_get_state);
    LOAD(context_disconnect, pa_context_disconnect);
    LOAD(context_unref, pa_context_unref);
    LOAD(context_errno, pa_context_errno);
    LOAD(strerror_fn, pa_strerror);
    LOAD(context_get_module_info_list, pa_context_get_module_info_list);
    LOAD(context_load_module, pa_context_load_module);
    LOAD(context_unload_module, pa_context_unload_module);
    LOAD(context_get_sink_input_info_list, pa_context_get_sink_input_info_list);
    LOAD(context_move_sink_input_by_name, pa_context_move_sink_input_by_name);
    LOAD(context_set_default_sink, pa_context_set_default_sink);
    LOAD(context_set_sink_volume_by_name, pa_context_set_sink_volume_by_name);
    LOAD(context_set_sink_mute_by_name, pa_context_set_sink_mute_by_name);
    LOAD(operation_get_state, pa_operation_get_state);
    LOAD(operation_unref, pa_operation_unref);
#undef LOAD
    return 1;
}

static int iterate_until_context_ready(const pulse_api *api, pa_mainloop *mainloop,
                                       pa_context *context) {
    const int64_t deadline = monotonic_ms() + OPERATION_TIMEOUT_MS;
    while (monotonic_ms() < deadline) {
        int retval = 0;
        if (api->mainloop_iterate(mainloop, 0, &retval) < 0) return 0;
        const int state = api->context_get_state(context);
        if (state == PA_CONTEXT_READY) return 1;
        if (state == PA_CONTEXT_FAILED || state == PA_CONTEXT_TERMINATED) return 0;
        usleep(1000);
    }
    return 0;
}

static int wait_for_operation(const pulse_api *api, pa_mainloop *mainloop,
                              pa_context *context, pa_operation *operation) {
    if (!operation) return 0;
    const int64_t deadline = monotonic_ms() + OPERATION_TIMEOUT_MS;
    int completed = 0;
    while (monotonic_ms() < deadline) {
        int retval = 0;
        if (api->mainloop_iterate(mainloop, 0, &retval) < 0) break;
        const int context_state = api->context_get_state(context);
        if (context_state == PA_CONTEXT_FAILED || context_state == PA_CONTEXT_TERMINATED) break;
        const int operation_state = api->operation_get_state(operation);
        if (operation_state != PA_OPERATION_RUNNING) {
            completed = operation_state == PA_OPERATION_DONE;
            break;
        }
        usleep(1000);
    }
    api->operation_unref(operation);
    return completed;
}

static void module_info_callback(pa_context *context, const pa_module_info *info,
                                 int eol, void *userdata) {
    (void) context;
    module_scan *scan = userdata;
    if (eol < 0) {
        scan->failed = 1;
        return;
    }
    if (eol || !info || !info->name || strcmp(info->name, "module-aaudio-sink") != 0) return;
    if (scan->count >= MAX_AAUDIO_MODULES) return;
    const unsigned slot = scan->count++;
    scan->indices[slot] = info->index;
    snprintf(scan->arguments[slot], sizeof(scan->arguments[slot]), "%s",
             info->argument ? info->argument : "");
}

static void sink_input_info_callback(pa_context *context, const pa_sink_input_info *info,
                                     int eol, void *userdata) {
    (void) context;
    sink_input_scan *scan = userdata;
    if (eol < 0) {
        scan->failed = 1;
        return;
    }
    if (eol || !info || scan->count >= MAX_SINK_INPUTS) return;
    scan->indices[scan->count++] = info->index;
}

static void success_callback(pa_context *context, int success, void *userdata) {
    (void) context;
    operation_result *result = userdata;
    result->called = 1;
    result->success = success != 0;
}

static void index_callback(pa_context *context, uint32_t index, void *userdata) {
    (void) context;
    index_result *result = userdata;
    result->called = 1;
    result->index = index;
}

static int run_success_operation(const pulse_api *api, pa_mainloop *mainloop,
                                 pa_context *context, pa_operation *operation,
                                 operation_result *result) {
    return wait_for_operation(api, mainloop, context, operation)
            && result->called && result->success;
}

static int argument_uses_sink_name(const char *argument, const char *sink_name) {
    char expected[96];
    snprintf(expected, sizeof(expected), "sink_name=%s", sink_name);
    return argument && strstr(argument, expected) != NULL;
}

static const char *choose_unused_sink_name(const module_scan *modules,
                                           char *fallback, size_t fallback_size) {
    static const char *candidates[] = {
            "AAudioSink", "AAudioSinkReload", "AAudioSinkRecovery"
    };
    int used[3] = {0, 0, 0};
    for (unsigned i = 0; i < modules->count; i++) {
        const char *argument = modules->arguments[i];
        if (!argument[0]) {
            used[0] = 1; /* The module's default is AAudioSink. */
            continue;
        }
        for (unsigned candidate = 0; candidate < 3; candidate++) {
            if (argument_uses_sink_name(argument, candidates[candidate])) used[candidate] = 1;
        }
    }
    for (unsigned candidate = 0; candidate < 3; candidate++) {
        if (!used[candidate]) return candidates[candidate];
    }
    snprintf(fallback, fallback_size, "AAudioSinkRecovery%lld",
             (long long) monotonic_ms());
    return fallback;
}

static int reload_aaudio_sink(const char *server_path, int volume_percent) {
    int result = 0;
    void *pulse_handle = dlopen("libpulse.so", RTLD_NOW | RTLD_LOCAL);
    if (!pulse_handle) {
        LOGE("dlopen(libpulse.so) failed: %s", dlerror());
        return 0;
    }

    pulse_api api;
    if (!load_pulse_api(pulse_handle, &api)) {
        dlclose(pulse_handle);
        return 0;
    }

    pa_mainloop *mainloop = api.mainloop_new();
    pa_context *context = mainloop
            ? api.context_new(api.mainloop_get_api(mainloop), "WinXclipse AAudio recovery")
            : NULL;
    if (!mainloop || !context) {
        LOGE("failed to create PulseAudio client context");
        goto cleanup;
    }
    if (api.context_connect(context, server_path, PA_CONTEXT_NOAUTOSPAWN, NULL) < 0
            || !iterate_until_context_ready(&api, mainloop, context)) {
        const int error = api.context_errno(context);
        LOGE("failed to connect to %s: %s", server_path, api.strerror_fn(error));
        goto cleanup;
    }

    module_scan old_modules;
    memset(&old_modules, 0, sizeof(old_modules));
    if (!wait_for_operation(&api, mainloop, context,
            api.context_get_module_info_list(context, module_info_callback, &old_modules))
            || old_modules.failed) {
        LOGE("failed to enumerate PulseAudio modules");
        goto cleanup;
    }

    char fallback_name[64];
    const char *new_sink_name = choose_unused_sink_name(&old_modules, fallback_name,
                                                         sizeof(fallback_name));
    char module_argument[96];
    snprintf(module_argument, sizeof(module_argument), "sink_name=%s", new_sink_name);
    index_result loaded = {0, PA_INVALID_INDEX};
    if (!wait_for_operation(&api, mainloop, context,
            api.context_load_module(context, "module-aaudio-sink", module_argument,
                                    index_callback, &loaded))
            || !loaded.called || loaded.index == PA_INVALID_INDEX) {
        LOGE("failed to load replacement AAudio sink");
        goto cleanup;
    }

    operation_result default_result = {0, 0};
    if (!run_success_operation(&api, mainloop, context,
            api.context_set_default_sink(context, new_sink_name, success_callback,
                                         &default_result), &default_result)) {
        LOGE("failed to make %s the default sink", new_sink_name);
        operation_result unload_replacement = {0, 0};
        run_success_operation(&api, mainloop, context,
                api.context_unload_module(context, loaded.index, success_callback,
                                          &unload_replacement), &unload_replacement);
        goto cleanup;
    }

    sink_input_scan inputs;
    memset(&inputs, 0, sizeof(inputs));
    if (wait_for_operation(&api, mainloop, context,
            api.context_get_sink_input_info_list(context, sink_input_info_callback, &inputs))
            && !inputs.failed) {
        for (unsigned i = 0; i < inputs.count; i++) {
            operation_result moved = {0, 0};
            if (!run_success_operation(&api, mainloop, context,
                    api.context_move_sink_input_by_name(context, inputs.indices[i], new_sink_name,
                                                        success_callback, &moved), &moved)) {
                LOGE("failed to move sink input %u to %s", inputs.indices[i], new_sink_name);
            }
        }
    }
    else {
        LOGE("failed to enumerate sink inputs; keeping old module loaded");
        goto cleanup;
    }

    if (volume_percent < 0) volume_percent = 0;
    if (volume_percent > 100) volume_percent = 100;
    pa_cvolume volume;
    memset(&volume, 0, sizeof(volume));
    volume.channels = 2;
    const uint32_t level = (uint32_t) ((uint64_t) PA_VOLUME_NORM
                                      * (unsigned) volume_percent / 100U);
    volume.values[0] = level;
    volume.values[1] = level;
    operation_result volume_result = {0, 0};
    run_success_operation(&api, mainloop, context,
            api.context_set_sink_volume_by_name(context, new_sink_name, &volume,
                                                success_callback, &volume_result), &volume_result);
    operation_result mute_result = {0, 0};
    run_success_operation(&api, mainloop, context,
            api.context_set_sink_mute_by_name(context, new_sink_name, 0,
                                              success_callback, &mute_result), &mute_result);

    for (unsigned i = 0; i < old_modules.count; i++) {
        operation_result unloaded = {0, 0};
        if (!run_success_operation(&api, mainloop, context,
                api.context_unload_module(context, old_modules.indices[i], success_callback,
                                          &unloaded), &unloaded)) {
            LOGE("failed to unload stale AAudio module %u", old_modules.indices[i]);
        }
    }

    LOGI("loaded module %u as %s, moved %u sink input(s), removed %u stale module(s)",
         loaded.index, new_sink_name, inputs.count, old_modules.count);
    result = 1;

cleanup:
    if (context) {
        api.context_disconnect(context);
        api.context_unref(context);
    }
    if (mainloop) api.mainloop_free(mainloop);
    dlclose(pulse_handle);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_xenvironment_components_PulseAudioComponent_nativeReloadAaudioSink(
        JNIEnv *env, jclass clazz, jstring server_path, jint volume_percent) {
    (void) clazz;
    if (!server_path) return JNI_FALSE;
    const char *path = (*env)->GetStringUTFChars(env, server_path, NULL);
    if (!path) return JNI_FALSE;
    const int reloaded = reload_aaudio_sink(path, volume_percent);
    (*env)->ReleaseStringUTFChars(env, server_path, path);
    return reloaded ? JNI_TRUE : JNI_FALSE;
}
