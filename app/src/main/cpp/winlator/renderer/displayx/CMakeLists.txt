# Host objects are linked into the compositor: one C++ runtime owns the shared
# frame leases. The guest layer remains an independent library/process runtime.
add_library(winx_displayx OBJECT Server.cpp Jni.cpp Scanout.cpp)
target_compile_features(winx_displayx PRIVATE cxx_std_17)
target_compile_options(winx_displayx PRIVATE -Wall -Wextra -Werror)
target_compile_options(winx_displayx PRIVATE -Wno-deprecated-declarations)
set_target_properties(winx_displayx PROPERTIES POSITION_INDEPENDENT_CODE ON)

# A separate guest Vulkan layer: never loaded into the app's compositor.
add_library(winx_displayx_layer SHARED Layer.cpp)
target_compile_features(winx_displayx_layer PRIVATE cxx_std_17)
target_compile_options(winx_displayx_layer PRIVATE -Wall -Wextra -Werror -Wno-missing-field-initializers -fvisibility=hidden)
target_link_libraries(winx_displayx_layer android log dl)
target_link_options(winx_displayx_layer PRIVATE -Wl,--no-undefined -Wl,-Bsymbolic-functions)
