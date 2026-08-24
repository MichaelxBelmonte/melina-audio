#include <jni.h>

#include <algorithm>
#include <array>
#include <cstdint>

#include "rnnoise.h"

namespace {
constexpr int kFrameSize = 480;

DenoiseState* fromHandle(jlong handle) {
    return reinterpret_cast<DenoiseState*>(static_cast<intptr_t>(handle));
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_it_michelina_focus_desktop_DesktopRnnoiseBridge_nativeCreate(JNIEnv*, jobject) {
    auto* state = rnnoise_create(nullptr);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(state));
}

extern "C" JNIEXPORT jint JNICALL
Java_it_michelina_focus_desktop_DesktopRnnoiseBridge_nativeFrameSize(JNIEnv*, jobject) {
    return rnnoise_get_frame_size();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_it_michelina_focus_desktop_DesktopRnnoiseBridge_nativeProcess(
    JNIEnv* env,
    jobject,
    jlong handle,
    jshortArray input,
    jfloatArray output) {
    auto* state = fromHandle(handle);
    if (state == nullptr || input == nullptr || output == nullptr) return -1.0f;
    if (env->GetArrayLength(input) < kFrameSize || env->GetArrayLength(output) < kFrameSize) {
        return -1.0f;
    }

    std::array<jshort, kFrameSize> pcm{};
    std::array<float, kFrameSize> inputFloat{};
    std::array<float, kFrameSize> outputFloat{};
    env->GetShortArrayRegion(input, 0, kFrameSize, pcm.data());
    if (env->ExceptionCheck()) return -1.0f;
    for (int index = 0; index < kFrameSize; ++index) {
        inputFloat[index] = static_cast<float>(pcm[index]);
    }
    const float speechProbability =
        rnnoise_process_frame(state, outputFloat.data(), inputFloat.data());
    for (float& value : outputFloat) {
        value = std::clamp(value / 32768.0f, -2.0f, 2.0f);
    }
    env->SetFloatArrayRegion(output, 0, kFrameSize, outputFloat.data());
    return env->ExceptionCheck() ? -1.0f : speechProbability;
}

extern "C" JNIEXPORT void JNICALL
Java_it_michelina_focus_desktop_DesktopRnnoiseBridge_nativeDestroy(
    JNIEnv*,
    jobject,
    jlong handle) {
    if (auto* state = fromHandle(handle)) rnnoise_destroy(state);
}
