#pragma once

#ifdef __ANDROID__
#include <android/log.h>
#define GNFG_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GN-Framegen", __VA_ARGS__)
#define GNFG_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "GN-Framegen", __VA_ARGS__)
#define GNFG_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GN-Framegen", __VA_ARGS__)
#else
#include <cstdio>
#define GNFG_LOGI(...) do { std::printf(__VA_ARGS__); std::printf("\n"); } while (0)
#define GNFG_LOGW(...) do { std::printf(__VA_ARGS__); std::printf("\n"); } while (0)
#define GNFG_LOGE(...) do { std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif
