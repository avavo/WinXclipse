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

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_winlator_cmod_core_DriverProbeService_nativeProbe(JNIEnv *env, jclass,
        jstring hooksValue, jstring temporaryValue, jstring directoryValue, jstring libraryValue) {
    const std::string hooks = utf(env, hooksValue), temporary = utf(env, temporaryValue);
    const std::string directory = utf(env, directoryValue), library = utf(env, libraryValue);
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
        return nullptr;
    }
    auto gip = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(loader, "vkGetInstanceProcAddr"));
    auto create = gip ? reinterpret_cast<PFN_vkCreateInstance>(gip(nullptr, "vkCreateInstance")) : nullptr;
    if (!create) return nullptr;
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "WinXclipse driver query";
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    info.pApplicationInfo = &app;
    VkInstance instance = VK_NULL_HANDLE;
    if (create(&info, nullptr, &instance) != VK_SUCCESS) return nullptr;
    auto destroy = reinterpret_cast<PFN_vkDestroyInstance>(gip(instance, "vkDestroyInstance"));
    auto devices = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(gip(instance, "vkEnumeratePhysicalDevices"));
    auto properties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(gip(instance, "vkGetPhysicalDeviceProperties"));
    auto extensions = reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(gip(instance, "vkEnumerateDeviceExtensionProperties"));
    jobjectArray result = nullptr;
    do {
        if (!destroy || !devices || !properties || !extensions) break;
        if (!library.empty() && !driver_mapped(directory + library)) break;
        uint32_t count = 0;
        if (devices(instance, &count, nullptr) != VK_SUCCESS || count == 0 || count > 256) break;
        std::vector<VkPhysicalDevice> list(count);
        if (devices(instance, &count, list.data()) != VK_SUCCESS || count == 0) break;
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
        jclass stringClass = env->FindClass("java/lang/String");
        result = env->NewObjectArray(static_cast<jsize>(names.size() + 1), stringClass, nullptr);
        if (!result) break;
        jstring version = env->NewStringUTF(std::to_string(props.apiVersion).c_str());
        env->SetObjectArrayElement(result, 0, version);
        env->DeleteLocalRef(version);
        for (size_t i = 0; i < names.size(); ++i) {
            jstring name = env->NewStringUTF(names[i].c_str());
            env->SetObjectArrayElement(result, static_cast<jsize>(i + 1), name);
            env->DeleteLocalRef(name);
        }
        env->DeleteLocalRef(stringClass);
    } while (false);
    if (destroy) destroy(instance, nullptr);
    // Handles/namespaces are released when the last client unbinds and this probe process exits.
    return result;
}
