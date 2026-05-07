#pragma once

#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <pthread.h>
#include <stdbool.h>
#include <sys/un.h>

#ifdef __cplusplus
extern "C" {
#endif

// Opaque handle for the DR server
typedef struct DRServer DRServer;

// Callback: called when a new frame is available
// buffer: AHardwareBuffer containing the rendered frame (caller does NOT own it)
// image_index: which swapchain image this is
typedef void (*DRFrameCallback)(AHardwareBuffer* buffer, int image_index, void* user_data);

// Create and start the DR server.
// sock_path: Unix socket path to listen on (DR_SOCK_PATH)
// window: ANativeWindow to present frames to (from Android Surface)
// Returns: server handle, or NULL on failure
DRServer* dr_server_start(const char* sock_path, ANativeWindow* window);

// Stop and destroy the DR server
void dr_server_stop(DRServer* server);

// Check if a client is connected
bool dr_server_is_connected(DRServer* server);

#ifdef __cplusplus
}
#endif
