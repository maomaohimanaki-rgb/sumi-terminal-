#include "pty_core.h"
#include <jni.h>
#include <pty.h>
#include <termios.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <errno.h>
#include <pthread.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <atomic>
#include <string>
#include <vector>
#include <android/log.h>

#define TAG "pty_core"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

using sumi::pty::Session;

JavaVM* gVm = nullptr;

std::vector<std::string> toVector(JNIEnv* env, jobjectArray arr) {
    std::vector<std::string> out;
    if (!arr) return out;
    jsize n = env->GetArrayLength(arr);
    for (jsize i = 0; i < n; ++i) {
        auto js = (jstring) env->GetObjectArrayElement(arr, i);
        const char* c = env->GetStringUTFChars(js, nullptr);
        out.emplace_back(c);
        env->ReleaseStringUTFChars(js, c);
        env->DeleteLocalRef(js);
    }
    return out;
}

void* readerLoop(void* arg) {
    auto* s = static_cast<Session*>(arg);
    JNIEnv* env = nullptr;
    JavaVMAttachArgs attach{JNI_VERSION_1_6, "pty-reader", nullptr};
    if (gVm->AttachCurrentThread(&env, &attach) != JNI_OK) return nullptr;

    s->readerTid.store(gettid());
    env->CallVoidMethod(s->listener, s->onReaderStarted, (jint) gettid());

    constexpr size_t kBuf = 32 * 1024;
    std::vector<uint8_t> buf(kBuf);
    int fd = s->fd.load();

    while (fd >= 0) {
        ssize_t n = read(fd, buf.data(), kBuf);
        if (n > 0) {
            jbyteArray arr = env->NewByteArray((jsize) n);
            env->SetByteArrayRegion(arr, 0, (jsize) n, reinterpret_cast<const jbyte*>(buf.data()));
            env->CallVoidMethod(s->listener, s->onOutput, arr);
            env->DeleteLocalRef(arr);
            if (env->ExceptionCheck()) env->ExceptionClear();
        } else if (n < 0 && (errno == EINTR || errno == EAGAIN)) {
            continue;
        } else {
            break;
        }
    }

    int status = 0;
    int code = -1;
    if (s->pid > 0 && waitpid(s->pid, &status, 0) > 0) {
        code = WIFEXITED(status) ? WEXITSTATUS(status)
                                 : (WIFSIGNALED(status) ? 128 + WTERMSIG(status) : -1);
    }
    if (fd >= 0) close(fd);
    s->fd.store(-1);

    env->CallVoidMethod(s->listener, s->onExit, (jint) code);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteGlobalRef(s->listener);
    s->listener = nullptr;
    gVm->DetachCurrentThread();
    return nullptr;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    gVm = vm;
    signal(SIGPIPE, SIG_IGN);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_sumi_terminal_nativebridge_PtyNative_nativeStart(
        JNIEnv* env, jobject, jstring jcmd, jobjectArray jargs, jobjectArray jenv,
        jstring jcwd, jint rows, jint cols, jobject listener) {
    signal(SIGPIPE, SIG_IGN);
    const char* cmdC = env->GetStringUTFChars(jcmd, nullptr);
    const char* cwdC = env->GetStringUTFChars(jcwd, nullptr);
    std::string cmd(cmdC), cwd(cwdC);
    env->ReleaseStringUTFChars(jcmd, cmdC);
    env->ReleaseStringUTFChars(jcwd, cwdC);

    std::vector<std::string> args = toVector(env, jargs);
    std::vector<std::string> envs = toVector(env, jenv);
    std::vector<char*> argv, envp;
    for (auto& a : args) argv.push_back(a.data());
    argv.push_back(nullptr);
    for (auto& e : envs) envp.push_back(e.data());
    envp.push_back(nullptr);

    termios tio{};
    cfmakeraw(&tio);
    tio.c_iflag = ICRNL | IXON | IUTF8;
    tio.c_oflag = OPOST | ONLCR;
    tio.c_lflag = ISIG | ICANON | ECHO | ECHOE | ECHOK | IEXTEN;
    tio.c_cflag = CS8 | CREAD;
    tio.c_cc[VINTR] = 3; tio.c_cc[VQUIT] = 28; tio.c_cc[VERASE] = 127; tio.c_cc[VKILL] = 21;
    tio.c_cc[VEOF] = 4; tio.c_cc[VSUSP] = 26; tio.c_cc[VMIN] = 1; tio.c_cc[VTIME] = 0;
    cfsetispeed(&tio, B38400); cfsetospeed(&tio, B38400);

    winsize ws{(unsigned short) rows, (unsigned short) cols, 0, 0};
    int fd = -1;
    pid_t pid = forkpty(&fd, nullptr, &tio, &ws);
    if (pid < 0) {
        LOGE("forkpty error: %d", errno);
        return 0;
    }
    if (pid == 0) {
        signal(SIGPIPE, SIG_DFL);
        if (!cwd.empty()) {
            if (chdir(cwd.c_str()) != 0) { /* continua no diretorio herdado */ }
        }
        execve(cmd.c_str(), argv.data(), envp.data());
        _exit(127);
    }

    auto* s = new Session();
    s->fd.store(fd);
    s->pid = pid;
    s->listener = env->NewGlobalRef(listener);
    jclass cls = env->GetObjectClass(listener);
    s->onOutput = env->GetMethodID(cls, "onOutput", "([B)V");
    s->onExit = env->GetMethodID(cls, "onExit", "(I)V");
    s->onReaderStarted = env->GetMethodID(cls, "onReaderStarted", "(I)V");

    pthread_create(&s->thread, nullptr, readerLoop, s);
    pthread_detach(s->thread);
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_sumi_terminal_nativebridge_PtyNative_nativeWrite(
        JNIEnv* env, jobject, jlong handle, jbyteArray data) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) return -1;
    int fd = s->fd.load();
    if (fd < 0) return -1;
    jsize len = env->GetArrayLength(data);
    jbyte* p = env->GetByteArrayElements(data, nullptr);
    jsize off = 0;
    while (off < len) {
        ssize_t n = write(fd, p + off, (size_t) (len - off));
        if (n < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            break;
        }
        off += (jsize) n;
    }
    env->ReleaseByteArrayElements(data, p, JNI_ABORT);
    return off;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_sumi_terminal_nativebridge_PtyNative_nativeResize(
        JNIEnv*, jobject, jlong handle, jint rows, jint cols) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) return;
    int fd = s->fd.load();
    if (fd < 0) return;
    winsize ws{(unsigned short) rows, (unsigned short) cols, 0, 0};
    ioctl(fd, TIOCSWINSZ, &ws);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_sumi_terminal_nativebridge_PtyNative_nativeKill(
        JNIEnv*, jobject, jlong handle, jint sig) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s || s->pid <= 0) return;
    kill(-s->pid, sig);
    kill(s->pid, sig);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_sumi_terminal_nativebridge_PtyNative_nativeReaderTid(JNIEnv*, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    return s ? s->readerTid.load() : 0;
}
