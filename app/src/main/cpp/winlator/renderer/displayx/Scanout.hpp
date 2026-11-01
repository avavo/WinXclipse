// SPDX-License-Identifier: MIT
#pragma once
#include "Server.hpp"
#include <android/native_window.h>
#include <array>

namespace displayx {
// Optional Android 10+ direct SurfaceFlinger plane. It owns independent leases
// until the PREVIOUS buffer's release fence signals, including during teardown.
class Scanout {
    struct State;
    ANativeWindow* window;
    std::shared_ptr<State> state;
public:
    explicit Scanout(ANativeWindow* parent);
    ~Scanout();
    bool show(const std::shared_ptr<Frame>& frame,const std::array<int32_t,4>& destination);
    void hide();
};
}
