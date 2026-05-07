#include "direct_rendering_server.h"

#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define TAG "GamescopeDR"

// ---------------------------------------------------------------------------
// DirectRendering server implementation
//
// Protocol (reverse-engineered from libGameScopeVK.so strings):
//
//   1. Server listens on Unix socket at DR_SOCK_PATH
//   2. Client (GameScopeVK) connects
//   3. Server sends a pipe fd to the client (for completion signaling)
//   4. For each frame:
//      a. Client writes a uint32_t image_index
//      b. Client sends AHardwareBuffer via AHardwareBuffer_sendHandleToUnixSocket
//   5. Server receives the buffer, blits to ANativeWindow, signals via pipe
//
// The pipe fd is created via socketpair(); the server keeps one end,
// the client gets the other. The server writes a byte back when the
// frame has been consumed, so the client knows it can reuse the buffer.
// ---------------------------------------------------------------------------

struct DRServer {
    pthread_t accept_thread;
    pthread_t frame_thread;
    int listen_fd;
    int client_fd;
    int signal_fd;       // our end of the socketpair for completion signaling
    ANativeWindow* window;
    volatile bool running;
    volatile bool connected;
    char sock_path[256];
};

// ---- helpers ----

static void set_nonblock(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

// ---- frame receive loop ----

static void* frame_thread_fn(void* arg) {
    DRServer* srv = (DRServer*)arg;
    __android_log_print(ANDROID_LOG_INFO, TAG, "Frame thread started");

    while (srv->running) {
        // 1. Read image index (uint32_t)
        uint32_t image_index = 0;
        ssize_t n = recv(srv->client_fd, &image_index, sizeof(image_index), MSG_WAITALL);
        if (n <= 0) {
            if (n == 0) {
                __android_log_print(ANDROID_LOG_INFO, TAG, "Client disconnected (image index)");
                break;
            }
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                usleep(1000);
                continue;
            }
            __android_log_print(ANDROID_LOG_ERROR, TAG, "recv image_index failed: %s", strerror(errno));
            break;
        }

        __android_log_print(ANDROID_LOG_DEBUG, TAG, "Image index: %u", image_index);

        // 2. Receive AHardwareBuffer handle
        AHardwareBuffer* ahwb = NULL;
        int ret = AHardwareBuffer_recvHandleFromUnixSocket(srv->client_fd, &ahwb);
        if (ret != 0 || ahwb == NULL) {
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                "recv AHardwareBuffer failed: ret=%d errno=%d (%s)",
                ret, errno, strerror(errno));
            break;
        }

        __android_log_print(ANDROID_LOG_DEBUG, TAG,
            "Received AHardwareBuffer for frame %u", image_index);

        // 3. Blit to ANativeWindow if available
        if (srv->window) {
            AHardwareBuffer_Desc desc;
            AHardwareBuffer_describe(ahwb, &desc);

            // Try to present via ANativeWindow
            ANativeWindow_Buffer buffer;
            ANativeWindow_lock(srv->window, &buffer, NULL);

            // Lock the AHardwareBuffer to read pixels
            void* src_data = NULL;
            int lock_ret = AHardwareBuffer_lock(ahwb,
                AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, &src_data);

            if (lock_ret == 0 && src_data && buffer.bits) {
                // Simple memcpy blit (stride may differ)
                uint32_t* dst = (uint32_t*)buffer.bits;
                uint32_t* src = (uint32_t*)src_data;
                int min_stride = buffer.stride < (int)(desc.stride)
                    ? buffer.stride : (int)(desc.stride);
                for (int y = 0; y < desc.height && y < buffer.height; y++) {
                    memcpy(dst + y * buffer.stride, src + y * desc.stride,
                           min_stride * 4);
                }
                AHardwareBuffer_unlock(ahwb, NULL);
            } else {
                if (lock_ret == 0) AHardwareBuffer_unlock(ahwb, NULL);
                __android_log_print(ANDROID_LOG_WARN, TAG,
                    "Could not lock AHardwareBuffer: ret=%d", lock_ret);
            }

            ANativeWindow_unlockAndPost(srv->window);
        }

        // 4. Signal completion via pipe
        if (srv->signal_fd >= 0) {
            uint8_t done = 1;
            write(srv->signal_fd, &done, 1);
        }

        // Release our reference to the AHardwareBuffer
        AHardwareBuffer_release(ahwb);
    }

    srv->connected = false;
    __android_log_print(ANDROID_LOG_INFO, TAG, "Frame thread exited");
    return NULL;
}

// ---- accept loop ----

static void* accept_thread_fn(void* arg) {
    DRServer* srv = (DRServer*)arg;
    __android_log_print(ANDROID_LOG_INFO, TAG, "Accept thread started on %s", srv->sock_path);

    while (srv->running) {
        fd_set fds;
        FD_ZERO(&fds);
        FD_SET(srv->listen_fd, &fds);

        struct timeval tv = {1, 0}; // 1 second timeout
        int sel = select(srv->listen_fd + 1, &fds, NULL, NULL, &tv);
        if (sel <= 0) continue;

        struct sockaddr_un addr;
        socklen_t len = sizeof(addr);
        srv->client_fd = accept(srv->listen_fd, (struct sockaddr*)&addr, &len);
        if (srv->client_fd < 0) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "accept failed: %s", strerror(errno));
            continue;
        }

        __android_log_print(ANDROID_LOG_INFO, TAG, "Client connected!");
        srv->connected = true;

        // Create a socketpair for completion signaling
        int sp[2];
        if (socketpair(AF_UNIX, SOCK_STREAM, 0, sp) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "socketpair failed: %s", strerror(errno));
            close(srv->client_fd);
            srv->connected = false;
            continue;
        }

        // Send one end of the socketpair to the client
        // The protocol expects the server to send a pipe fd first
        struct msghdr msg = {0};
        struct iovec iov;
        uint8_t dummy = 0x42; // handshake byte
        iov.iov_base = &dummy;
        iov.iov_len = 1;
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;

        char cmsgbuf[CMSG_SPACE(sizeof(int))];
        msg.msg_control = cmsgbuf;
        msg.msg_controllen = sizeof(cmsgbuf);
        struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
        cmsg->cmsg_level = SOL_SOCKET;
        cmsg->cmsg_type = SCM_RIGHTS;
        cmsg->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(cmsg), &sp[1], sizeof(int));

        ssize_t sent = sendmsg(srv->client_fd, &msg, 0);
        if (sent < 0) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "sendmsg pipe fd failed: %s", strerror(errno));
            close(sp[0]);
            close(sp[1]);
            close(srv->client_fd);
            srv->connected = false;
            continue;
        }

        close(sp[1]); // close client's end in our process
        srv->signal_fd = sp[0]; // keep our end

        __android_log_print(ANDROID_LOG_INFO, TAG, "Sent pipe fd to client, starting frame thread");

        // Start frame receive loop
        pthread_create(&srv->frame_thread, NULL, frame_thread_fn, srv);
        pthread_join(srv->frame_thread, NULL);

        // Client disconnected, clean up
        close(srv->signal_fd);
        srv->signal_fd = -1;
        close(srv->client_fd);
        srv->client_fd = -1;

        __android_log_print(ANDROID_LOG_INFO, TAG, "Ready for next client");
    }

    return NULL;
}

// ---- public API ----

DRServer* dr_server_start(const char* sock_path, ANativeWindow* window) {
    DRServer* srv = (DRServer*)calloc(1, sizeof(DRServer));
    if (!srv) return NULL;

    strncpy(srv->sock_path, sock_path, sizeof(srv->sock_path) - 1);
    srv->window = window;
    srv->running = true;
    srv->connected = false;
    srv->client_fd = -1;
    srv->signal_fd = -1;

    // Remove stale socket
    unlink(sock_path);

    // Create listening socket
    srv->listen_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (srv->listen_fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "socket() failed: %s", strerror(errno));
        free(srv);
        return NULL;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, sock_path, sizeof(addr.sun_path) - 1);

    if (bind(srv->listen_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "bind(%s) failed: %s", sock_path, strerror(errno));
        close(srv->listen_fd);
        free(srv);
        return NULL;
    }

    if (listen(srv->listen_fd, 1) < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "listen() failed: %s", strerror(errno));
        close(srv->listen_fd);
        unlink(sock_path);
        free(srv);
        return NULL;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "Listening on %s", sock_path);

    pthread_create(&srv->accept_thread, NULL, accept_thread_fn, srv);
    return srv;
}

void dr_server_stop(DRServer* srv) {
    if (!srv) return;
    srv->running = false;

    // Shutdown the listening socket to unblock accept
    shutdown(srv->listen_fd, SHUT_RDWR);
    close(srv->listen_fd);

    if (srv->client_fd >= 0) {
        shutdown(srv->client_fd, SHUT_RDWR);
        close(srv->client_fd);
    }
    if (srv->signal_fd >= 0) {
        close(srv->signal_fd);
    }

    pthread_join(srv->accept_thread, NULL);

    unlink(srv->sock_path);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Server stopped");
    free(srv);
}

bool dr_server_is_connected(DRServer* srv) {
    return srv && srv->connected;
}

// ---------------------------------------------------------------------------
// JNI bridge
// ---------------------------------------------------------------------------

static DRServer* g_server = NULL;

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_gamenative_utils_GamescopeDirectRendering_nativeStart(
    JNIEnv* env, jclass clazz, jstring sock_path, jobject surface) {

    const char* path = env->GetStringUTFChars(sock_path, NULL);
    ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : NULL;

    if (!path) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "sock_path is null");
        return 0;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "Starting DR server: %s, window=%p", path, window);

    DRServer* srv = dr_server_start(path, window);
    env->ReleaseStringUTFChars(sock_path, path);

    if (!srv) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to start DR server");
        if (window) ANativeWindow_release(window);
        return 0;
    }

    g_server = srv;
    return (jlong)srv;
}

JNIEXPORT void JNICALL
Java_app_gamenative_utils_GamescopeDirectRendering_nativeStop(
    JNIEnv* env, jclass clazz, jlong handle) {

    DRServer* srv = (DRServer*)handle;
    if (srv) {
        dr_server_stop(srv);
        if (srv == g_server) g_server = NULL;
    }
}

JNIEXPORT jboolean JNICALL
Java_app_gamenative_utils_GamescopeDirectRendering_nativeIsConnected(
    JNIEnv* env, jclass clazz, jlong handle) {

    DRServer* srv = (DRServer*)handle;
    return srv && dr_server_is_connected(srv) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
