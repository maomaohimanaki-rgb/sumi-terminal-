#pragma once

#include <jni.h>
#include <sys/types.h>
#include <pthread.h>
#include <atomic>

namespace sumi::pty {

struct Session {
    std::atomic<int> fd{-1};
    pid_t pid{-1};
    std::atomic<int> readerTid{0};
    pthread_t thread{};
    jobject listener{nullptr};
    jmethodID onOutput{nullptr};
    jmethodID onExit{nullptr};
    jmethodID onReaderStarted{nullptr};
};

} // namespace sumi::pty
