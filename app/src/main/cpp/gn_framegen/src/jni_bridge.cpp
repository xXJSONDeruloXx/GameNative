#include "gn_framegen/session.hpp"
#include "framegen_context.hpp"
#include "logging.hpp"

#include <jni.h>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace gn::framegen {

static jstring toJStr(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

static Session* sessionFromHandle(jlong h) {
    return reinterpret_cast<Session*>(static_cast<uintptr_t>(h));
}
static jlong sessionToHandle(Session* p) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(p));
}
static FramegenContext* ctxFromHandle(jlong h) {
    return reinterpret_cast<FramegenContext*>(static_cast<uintptr_t>(h));
}
static jlong ctxToHandle(FramegenContext* p) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(p));
}

} // namespace gn::framegen

// ─── Session bootstrap JNI ───────────────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_app_gamenative_framegen_GNFramegenNative_nativeIsReady(
    JNIEnv*, jclass) {
    using namespace gn::framegen;
    return embedded::kShaderRegistry.size() > 0 &&
           Session::EmbeddedShaderCount() == Session::ValidEmbeddedShaderCount();
}

extern "C" JNIEXPORT jint JNICALL
Java_app_gamenative_framegen_GNFramegenNative_nativeShaderCount(JNIEnv*, jclass) {
    return static_cast<jint>(gn::framegen::Session::EmbeddedShaderCount());
}

extern "C" JNIEXPORT jint JNICALL
Java_app_gamenative_framegen_GNFramegenNative_nativeValidShaderCount(JNIEnv*, jclass) {
    return static_cast<jint>(gn::framegen::Session::ValidEmbeddedShaderCount());
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_gamenative_framegen_GNFramegenNative_nativeDescribeBundle(JNIEnv* env, jclass) {
    return gn::framegen::toJStr(env, gn::framegen::Session::DescribeEmbeddedBundle());
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_gamenative_framegen_GNFramegenNative_nativeCreateSession(
    JNIEnv*, jclass, jint width, jint height, jint multiplier,
    jfloat flowScale, jint model) {
    gn::framegen::Config cfg;
    cfg.width      = static_cast<uint32_t>(width > 0 ? width : 0);
    cfg.height     = static_cast<uint32_t>(height > 0 ? height : 0);
    cfg.multiplier = static_cast<uint32_t>(multiplier > 0 ? multiplier : 2);
    cfg.flowScale  = flowScale;
    cfg.model      = static_cast<uint32_t>(model >= 0 ? model : 0);
    auto* s = new gn::framegen::Session(cfg);
    return gn::framegen::sessionToHandle(s);
}

extern "C" JNIEXPORT void JNICALL
Java_app_gamenative_framegen_GNFramegenNative_nativeDestroySession(
    JNIEnv*, jclass, jlong h) {
    delete gn::framegen::sessionFromHandle(h);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_gamenative_framegen_GNFramegenNative_nativeUpdateSessionConfig(
    JNIEnv*, jclass, jlong h, jint w, jint ht, jint mult, jfloat fs, jint model) {
    auto* s = gn::framegen::sessionFromHandle(h);
    if (!s) return JNI_FALSE;
    gn::framegen::Config cfg;
    cfg.width = static_cast<uint32_t>(w > 0 ? w : 0);
    cfg.height = static_cast<uint32_t>(ht > 0 ? ht : 0);
    cfg.multiplier = static_cast<uint32_t>(mult > 0 ? mult : 2);
    cfg.flowScale = fs;
    cfg.model = static_cast<uint32_t>(model >= 0 ? model : 0);
    s->updateConfig(cfg);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_gamenative_framegen_GNFramegenNative_nativeDescribeSession(
    JNIEnv* env, jclass, jlong h) {
    auto* s = gn::framegen::sessionFromHandle(h);
    if (!s) return gn::framegen::toJStr(env, "GNFramegenSession{null}");
    return gn::framegen::toJStr(env, s->describe());
}

// ─── FramegenContext JNI (AHB path) ─────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_app_gamenative_framegen_GNFramegenNative_nativeCreateContext(
    JNIEnv* env, jclass,
    jobject prevAhb, jobject currAhb,
    jobjectArray outputAhbs,
    jint width, jint height,
    jint multiplier, jfloat flowScale, jint model) {
#ifdef __ANDROID__
    auto* prevBuf = static_cast<AHardwareBuffer*>(env->GetDirectBufferAddress(prevAhb));
    auto* currBuf = static_cast<AHardwareBuffer*>(env->GetDirectBufferAddress(currAhb));

    if (!prevBuf || !currBuf) {
        GNFG_LOGE("nativeCreateContext: null AHB (not a direct buffer?)");
        return 0L;
    }

    jsize outCount = env->GetArrayLength(outputAhbs);
    std::vector<AHardwareBuffer*> outBufs;
    outBufs.reserve(static_cast<size_t>(outCount));
    for (jsize i = 0; i < outCount; ++i) {
        auto* buf = static_cast<AHardwareBuffer*>(
            env->GetDirectBufferAddress(env->GetObjectArrayElement(outputAhbs, i)));
        if (!buf) { GNFG_LOGE("nativeCreateContext: null output AHB at index %d", i); return 0L; }
        outBufs.push_back(buf);
    }

    gn::framegen::Config cfg;
    cfg.width      = static_cast<uint32_t>(width);
    cfg.height     = static_cast<uint32_t>(height);
    cfg.multiplier = static_cast<uint32_t>(multiplier);
    cfg.flowScale  = flowScale;
    cfg.model      = static_cast<uint32_t>(model);

    VkExtent2D extent;
    extent.width  = static_cast<uint32_t>(width);
    extent.height = static_cast<uint32_t>(height);

    auto ctx = gn::framegen::FramegenContext::create(
        prevBuf, currBuf, outBufs, extent, VK_FORMAT_R8G8B8A8_UNORM, cfg);
    if (!ctx) { GNFG_LOGE("nativeCreateContext: FramegenContext::create failed"); return 0L; }
    return gn::framegen::ctxToHandle(ctx.release());
#else
    (void)env; (void)prevAhb; (void)currAhb; (void)outputAhbs;
    (void)width; (void)height; (void)multiplier; (void)flowScale; (void)model;
    return 0L;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_app_gamenative_framegen_GNFramegenNative_nativeDestroyContext(
    JNIEnv*, jclass, jlong h) {
    auto* ctx = gn::framegen::ctxFromHandle(h);
    if (ctx) { ctx->destroy(); delete ctx; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_gamenative_framegen_GNFramegenNative_nativePresent(
    JNIEnv* env, jclass, jlong h, jobject prevAhb, jobject currAhb) {
#ifdef __ANDROID__
    auto* ctx = gn::framegen::ctxFromHandle(h);
    if (!ctx) return JNI_FALSE;
    auto* prev = static_cast<AHardwareBuffer*>(env->GetDirectBufferAddress(prevAhb));
    auto* curr = static_cast<AHardwareBuffer*>(env->GetDirectBufferAddress(currAhb));
    ctx->present(prev, curr);
    return JNI_TRUE;
#else
    (void)env; (void)h; (void)prevAhb; (void)currAhb;
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_gamenative_framegen_GNFramegenNative_nativeDescribeContext(
    JNIEnv* env, jclass, jlong h) {
    auto* ctx = gn::framegen::ctxFromHandle(h);
    if (!ctx) return gn::framegen::toJStr(env, "FramegenContext{null}");
    return gn::framegen::toJStr(env, ctx->describe());
}

extern "C" JNIEXPORT void JNICALL
Java_app_gamenative_framegen_GNFramegenNative_nativeContextUpdateConfig(
    JNIEnv*, jclass, jlong h, jint mult, jfloat flowScale, jint model) {
    auto* ctx = gn::framegen::ctxFromHandle(h);
    if (!ctx) return;
    gn::framegen::Config cfg = ctx->valid()
        ? gn::framegen::Config{0, 0,
            static_cast<uint32_t>(mult),
            flowScale,
            static_cast<uint32_t>(model)}
        : gn::framegen::Config{};
    ctx->updateConfig(cfg);
}
