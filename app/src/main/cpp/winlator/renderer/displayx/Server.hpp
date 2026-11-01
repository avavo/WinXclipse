// SPDX-License-Identifier: MIT
#pragma once
#include "Protocol.hpp"
#include <android/hardware_buffer.h>
#include <memory>
#include <functional>
#include <atomic>

namespace displayx {
class Connection;
struct Frame {
    std::shared_ptr<Connection> connection;
    AHardwareBuffer* buffer=nullptr;
    Message message;
    uint64_t bufferId=0;
    std::atomic<bool> completed{false};
    std::atomic<uint32_t> externalReaders{0};
    ~Frame(); // Only release after the consumer GPU fence, never on submission.
    void complete();
    bool connected() const;
    void disconnect();
};
class Server {
    struct Impl;
    std::unique_ptr<Impl> impl;
public:
    Server(const char* path,std::function<bool(uint32_t)> notify);
    ~Server();
    Server(const Server&)=delete;
    Server& operator=(const Server&)=delete;
    std::shared_ptr<Frame> take(uint32_t window);
    void setFpsLimit(uint32_t fps);
    void setOptions(bool everyBuffer,bool performance);
    bool hasPending(uint32_t window);
};
}
