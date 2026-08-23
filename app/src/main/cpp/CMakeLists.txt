cmake_minimum_required(VERSION 3.22.1)

project(Winlator)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

add_subdirectory(OpenXR-SDK)
add_subdirectory(patchelf)

# Add Winlator shared library
add_library(winlator SHARED
        xr/engine.c
        xr/framebuffer.c
        xr/input.c
        xr/main.c
        xr/math.c
        xr/renderer.c
        winlator/drawable.c
        winlator/gpu_image.c
        winlator/sysvshared_memory.c
        winlator/xconnector_epoll.c
        winlator/alsa_client.c
        winlator/patchelf_wrapper.cpp
        winlator/vulkan.cpp
        # MdiEx runtime (Core/Policy, Loader, Sched, MemShim, AHB, Surface)
        mdiex/MdiEx_Core_1.0.0/mdiex_core.c
        mdiex/MdiEx_Core_1.0.0/mdiex_xclipse_policy.c
        mdiex/MdiEx_Loader_1.0.0/mdiex_loader.c
        mdiex/MdiEx_Sched_1.0.0/mdiex_sched.c
        mdiex/MdiEx_MemShim_0.5.0/mdiex_memshim.c
        mdiex/MdiEx_AHB_0.5.0/mdiex_ahb.c
        mdiex/MdiEx_Surface_0.5.0/mdiex_surface.c
        # NRAMV unified-memory manager + app-side bridge
        nramv/nramv_core.c
        nramv/nramv_vm.c
        nramv/nramv_heap.c
        nramv/nramv_gtt_react.c
        nramv/nramv_pressure.c
        nramv/nramv_thermal.c
        nramv/nramv_mallopt.c
        nramv/nramv_ringbuf.c
        nramv/nramv_sgpu.c
        nramv/nramv_emulator.c
        nramv/nramv_feedback.c
        nramv/nramv_debug.c
        nramv/nramv_compat.c
        winlator/nramv_bridge.c
        winlator/mdiex_bridge.c
)

# The memshim's malloc/mmap interpose symbols are meant for the guest-side
# LD_PRELOAD case only; inside libwinlator they must stay compiled out so the
# app process keeps using libc's allocator untouched.
set_source_files_properties(mdiex/MdiEx_MemShim_0.5.0/mdiex_memshim.c PROPERTIES
        COMPILE_DEFINITIONS MDIEX_MEMSHIM_ENABLE_INTERPOSE_SYMBOLS=0)

target_include_directories(winlator PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/mdiex/MdiEx_Core_1.0.0
        ${CMAKE_CURRENT_SOURCE_DIR}/mdiex/MdiEx_Loader_1.0.0
        ${CMAKE_CURRENT_SOURCE_DIR}/mdiex/MdiEx_Sched_1.0.0
        ${CMAKE_CURRENT_SOURCE_DIR}/mdiex/MdiEx_MemShim_0.5.0
        ${CMAKE_CURRENT_SOURCE_DIR}/mdiex/MdiEx_AHB_0.5.0
        ${CMAKE_CURRENT_SOURCE_DIR}/mdiex/MdiEx_Surface_0.5.0
        ${CMAKE_CURRENT_SOURCE_DIR}/nramv
)

target_link_libraries(winlator
        log
        android
        jnigraphics
        openxr_loader
        aaudio
        EGL
        GLESv2
        GLESv3
)


# ---- EVSHIM (phase-0 stub) -----------------------------------
add_library(evshim SHARED winlator/evshim.c)

# ---------- SDL2 library (Build from source) ----------
# Add SDL2 as a library target.
# This assumes cpp/sdl2 has its own CMakeLists.txt or you list its sources here.
# Most commonly, SDL2 comes with its own CMake build system.
# If cpp/sdl2 has a CMakeLists.txt:
add_subdirectory(sdl2) # This will create an SDL2 target (often named SDL2::SDL2 or just SDL2)

# If you need to list SDL2 source files manually (less ideal if SDL2 is complex):
# file(GLOB SDL2_SOURCES "sdl2/*.c" "sdl2/src/*/*.c" ...) # Adjust glob patterns
# add_library(MySDL2 STATIC ${SDL2_SOURCES}) # Or SHARED
# target_include_directories(MySDL2 PUBLIC "${CMAKE_CURRENT_LIST_DIR}/sdl2/include") # Assuming headers are in sdl2/include


# Tell evshim's compiler where to find SDL2 headers (still needed)
target_include_directories(evshim PRIVATE
        "${CMAKE_CURRENT_LIST_DIR}/sdl2/include" # Adjust if SDL headers are elsewhere in your sdl2 dir
)

# ---------- link everything ----------
target_link_libraries(evshim PRIVATE # Or PUBLIC
        SDL2            # If add_subdirectory(sdl2) created a target named SDL2
        # Or MySDL2     # If you used add_library(MySDL2 ...)
        # Or SDL2::SDL2 # Common imported target name for modern CMake SDL2
        log
        dl
)

# drop .so into jniLibs/<abi> so Gradle packs it automatically
set_target_properties(evshim PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY
        "${CMAKE_CURRENT_SOURCE_DIR}/jniLibs/${ANDROID_ABI}")

# ---------- LayerCache Helix (Vulkan layer, built from our sources) ----------
add_library(VkLayer_PerfCache SHARED
        perfcache/src/layer_entry.cpp
        perfcache/src/pipeline_cache.cpp
        perfcache/src/texture_cache.cpp
        perfcache/src/pipeline_sanitizer.cpp
        perfcache/src/xclipse_detect.cpp
        perfcache/src/settings.cpp
        perfcache/src/conflict_detect.cpp
        perfcache/src/pipeline_control.cpp
        perfcache/src/perf_metrics.cpp
)
target_include_directories(VkLayer_PerfCache PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/perfcache/include
)
target_compile_options(VkLayer_PerfCache PRIVATE -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden -fstack-protector-strong)
target_link_libraries(VkLayer_PerfCache log dl)
set_target_properties(VkLayer_PerfCache PROPERTIES OUTPUT_NAME "VkLayer_PerfCache")
