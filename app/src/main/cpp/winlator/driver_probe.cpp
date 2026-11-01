#include <vulkan/vulkan.h>
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <algorithm>
#include <cstdio>
#include <string>
#include <vector>

// Existing bundled libadrenotools ABI, documented by bylaws/libadrenotools/driver.h.
using OpenVulkan = void *(*)(int, int, const char *, const char *, const char *, const char *, const char *, void **);

static std::string utf(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *bytes = env->GetStringUTFChars(value, nullptr);
    if (!bytes) return {};
    std::string result(bytes);
    env->ReleaseStringUTFChars(value, bytes);
    return result;
}

static bool driver_mapped(const std::string &path) {
    FILE *maps = fopen("/proc/self/maps", "r");
    if (!maps) return false;
    char line[4096];
    bool found = false;
    while (fgets(line, sizeof(line), maps)) if (std::string(line).find(path) != std::string::npos) {
        found = true;
        break;
    }
    fclose(maps);
    return found;
}

static std::vector<std::string> probe_driver(const std::string &hooks, const std::string &temporary,
        const std::string &directory, const std::string &library) {
    // Match Ludashi's vendor dependency preload before creating the isolated loader namespace.
    for (const char *dependency : {"/system/lib64/libjpeg.so", "/system_ext/lib64/libjpeg.so", "libjpeg.so", "libcrypto.so"})
        dlopen(dependency, RTLD_NOW | RTLD_GLOBAL);
    void *loader = nullptr;
    if (library.empty()) loader = dlopen("/system/lib64/libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    else {
        void *tools = dlopen((hooks + "/libadrenotools.so").c_str(), RTLD_NOW | RTLD_LOCAL);
        auto openVulkan = tools ? reinterpret_cast<OpenVulkan>(dlsym(tools, "adrenotools_open_libvulkan")) : nullptr;
        if (openVulkan) loader = openVulkan(RTLD_NOW | RTLD_LOCAL, 1, temporary.c_str(), hooks.c_str(),
                directory.c_str(), library.c_str(), nullptr, nullptr);
    }
    // No fallback to the stock loader for a failed custom driver query.
    if (!loader) {
        const char *error = dlerror();
        __android_log_print(ANDROID_LOG_WARN, "DriverProbe", "Unable to load selected Vulkan library: %s", error ? error : "unknown error");
        return {};
    }
    auto gip = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(loader, "vkGetInstanceProcAddr"));
    auto create = reinterpret_cast<PFN_vkCreateInstance>(dlsym(loader, "vkCreateInstance"));
    if (!create && gip) create = reinterpret_cast<PFN_vkCreateInstance>(gip(nullptr, "vkCreateInstance"));
    if (!gip || !create) {
        __android_log_print(ANDROID_LOG_WARN, "DriverProbe", "Selected loader has no instance entry points");
        return {};
    }
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "WinXclipse driver query";
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    info.pApplicationInfo = &app;
    VkInstance instance = VK_NULL_HANDLE;
    VkResult created = create(&info, nullptr, &instance);
    if (created != VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_WARN, "DriverProbe", "vkCreateInstance failed: %d", created);
        return {};
    }
    auto destroy = reinterpret_cast<PFN_vkDestroyInstance>(gip(instance, "vkDestroyInstance"));
    auto devices = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(gip(instance, "vkEnumeratePhysicalDevices"));
    auto properties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(gip(instance, "vkGetPhysicalDeviceProperties"));
    auto extensions = reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(gip(instance, "vkEnumerateDeviceExtensionProperties"));
    std::vector<std::string> result;
    do {
        if (!destroy || !devices || !properties || !extensions) break;
        uint32_t count = 0;
        if (devices(instance, &count, nullptr) != VK_SUCCESS || count == 0 || count > 256) break;
        std::vector<VkPhysicalDevice> list(count);
        if (devices(instance, &count, list.data()) != VK_SUCCESS || count == 0) break;
        // Some ICDs are loaded lazily during physical-device discovery.
        if (!library.empty() && !driver_mapped(directory + library)) {
            __android_log_print(ANDROID_LOG_WARN, "DriverProbe", "Selected ICD was not mapped: %s%s", directory.c_str(), library.c_str());
            break;
        }
        VkPhysicalDeviceProperties props{};
        properties(list[0], &props);
        count = 0;
        if (extensions(list[0], nullptr, &count, nullptr) != VK_SUCCESS || count > 16384) break;
        std::vector<VkExtensionProperties> supported(count);
        if (extensions(list[0], nullptr, &count, supported.data()) != VK_SUCCESS) break;
        std::vector<std::string> names;
        for (uint32_t i = 0; i < count; ++i) names.emplace_back(supported[i].extensionName);
        std::sort(names.begin(), names.end());
        names.erase(std::unique(names.begin(), names.end()), names.end());
        result.emplace_back(std::to_string(props.apiVersion));
        result.insert(result.end(), names.begin(), names.end());
    } while (false);
    if (destroy) destroy(instance, nullptr);
    // Handles/namespaces are released when the last client unbinds and this probe process exits.
    return result;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_winlator_cmod_core_DriverProbeService_nativeProbe(JNIEnv *env, jclass,
        jstring hooksValue, jstring temporaryValue, jstring directoryValue, jstring libraryValue) {
    auto values = probe_driver(utf(env, hooksValue), utf(env, temporaryValue),
            utf(env, directoryValue), utf(env, libraryValue));
    if (values.empty()) return nullptr;
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(values.size()), stringClass, nullptr);
    if (result) for (size_t i = 0; i < values.size(); ++i) {
        jstring value = env->NewStringUTF(values[i].c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(stringClass);
    return result;
}
