#pragma once

namespace GN {
namespace Framegen {

// Version information for the GN Frame Generation Layer
constexpr const char* LAYER_VERSION = "1.0.0";
constexpr const char* LAYER_NAME = "VK_LAYER_GN_gamescope_framegen";
constexpr const char* LAYER_DESCRIPTION = "GameNative Vulkan Frame Generation Layer";

// Build information
#if defined(__ANDROID__)
constexpr const char* BUILD_PLATFORM = "Android";
#elif defined(__linux__)
constexpr const char* BUILD_PLATFORM = "Linux";
#elif defined(_WIN32)
constexpr const char* BUILD_PLATFORM = "Windows";
#else
constexpr const char* BUILD_PLATFORM = "Unknown";
#endif

// Architecture
#if defined(__aarch64__)
constexpr const char* BUILD_ARCH = "arm64-v8a";
#elif defined(__arm__)
constexpr const char* BUILD_ARCH = "armeabi-v7a";
#elif defined(__x86_64__)
constexpr const char* BUILD_ARCH = "x86_64";
#elif defined(__i386__)
constexpr const char* BUILD_ARCH = "x86";
#else
constexpr const char* BUILD_ARCH = "unknown";
#endif

// Shader embedding status
#ifdef EMBED_SHADERS
constexpr bool SHADERS_EMBEDDED = true;
#else
constexpr bool SHADERS_EMBEDDED = false;
#endif

// Get full version string for logging/debugging
inline const char* GetVersionString() {
    static const char* version = 
        LAYER_NAME " " LAYER_VERSION " (" BUILD_PLATFORM " " BUILD_ARCH ")";
    return version;
}

} // namespace Framegen
} // namespace GN
