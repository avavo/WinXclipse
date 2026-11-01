// Standalone ARM64 check of the same loader/query implementation used by :driver_probe.
// Build with the pinned Android NDK: clang++ -static-libstdc++ -llog -ldl.
#include "../app/src/main/cpp/winlator/driver_probe.cpp"

int main(int argc, char **argv) {
    if (argc != 5) {
        fprintf(stderr, "usage: driver_query_probe <nativeLibraryDir> <temporaryDir> <driverDir/> <libraryName>\n");
        return 2;
    }
    auto values = probe_driver(argv[1], argv[2], argv[3], argv[4]);
    if (values.empty()) return 1;
    auto api = static_cast<uint32_t>(std::stoul(values.front()));
    printf("Vulkan %u.%u.%u, %zu extensions\n", VK_VERSION_MAJOR(api), VK_VERSION_MINOR(api),
            VK_VERSION_PATCH(api), values.size() - 1);
    return 0;
}
