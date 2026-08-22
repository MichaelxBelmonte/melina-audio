#include <jni.h>

#include <dlfcn.h>

#include <algorithm>
#include <array>
#include <cstdint>

#include "rnnoise.h"

namespace {
constexpr int kExpectedFrameSize = 480;

DenoiseState* fromHandle(jlong handle) {
    return reinterpret_cast<DenoiseState*>(static_cast<intptr_t>(handle));
}

using DfCreate = void* (*)(const char*, float, const char*);
using DfGetFrameLength = size_t (*)(void*);
using DfProcessFrame = float (*)(void*, float*, float*);
using DfSetAttenLim = void (*)(void*, float);
using DfSetPostFilterBeta = void (*)(void*, float);
using DfFree = void (*)(void*);

struct DeepFilterHandle {
    void* library = nullptr;
    void* state = nullptr;
    DfGetFrameLength getFrameLength = nullptr;
    DfProcessFrame processFrame = nullptr;
    DfSetAttenLim setAttenLim = nullptr;
    DfSetPostFilterBeta setPostFilterBeta = nullptr;
    DfFree freeState = nullptr;
};

template <typename T>
T loadSymbol(void* library, const char* name) {
    return reinterpret_cast<T>(dlsym(library, name));
}

DeepFilterHandle* deepFilterFromHandle(jlong handle) {
    return reinterpret_cast<DeepFilterHandle*>(static_cast<intptr_t>(handle));
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_it_michelina_focus_audio_RnnoiseBridge_nativeCreate(JNIEnv*, jobject) {
    auto* state = rnnoise_create(nullptr);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(state));
}

extern "C" JNIEXPORT jint JNICALL
Java_it_michelina_focus_audio_RnnoiseBridge_nativeFrameSize(JNIEnv*, jobject) {
    return rnnoise_get_frame_size();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_it_michelina_focus_audio_RnnoiseBridge_nativeProcess(
    JNIEnv* env,
    jobject,
    jlong handle,
    jshortArray input,
    jfloatArray output) {
    auto* state = fromHandle(handle);
    if (state == nullptr || input == nullptr || output == nullptr) return -1.0f;
    if (env->GetArrayLength(input) < kExpectedFrameSize ||
        env->GetArrayLength(output) < kExpectedFrameSize) {
        return -1.0f;
    }

    std::array<jshort, kExpectedFrameSize> pcm{};
    std::array<float, kExpectedFrameSize> inputFloat{};
    std::array<float, kExpectedFrameSize> outputFloat{};
    env->GetShortArrayRegion(input, 0, kExpectedFrameSize, pcm.data());
    if (env->ExceptionCheck()) return -1.0f;

    for (int i = 0; i < kExpectedFrameSize; ++i) {
        inputFloat[i] = static_cast<float>(pcm[i]);
    }
    const float speechProbability =
        rnnoise_process_frame(state, outputFloat.data(), inputFloat.data());
    for (float& value : outputFloat) {
        value = std::clamp(value / 32768.0f, -2.0f, 2.0f);
    }
    env->SetFloatArrayRegion(output, 0, kExpectedFrameSize, outputFloat.data());
    return env->ExceptionCheck() ? -1.0f : speechProbability;
}

extern "C" JNIEXPORT void JNICALL
Java_it_michelina_focus_audio_RnnoiseBridge_nativeDestroy(
    JNIEnv*,
    jobject,
    jlong handle) {
    if (auto* state = fromHandle(handle)) rnnoise_destroy(state);
}

extern "C" JNIEXPORT jlong JNICALL
Java_it_michelina_focus_audio_DeepFilterBridge_nativeCreate(
    JNIEnv* env,
    jobject,
    jstring modelPath,
    jfloat attenuationLimitDb) {
    if (modelPath == nullptr) return 0;
    void* library = dlopen("libdf.so", RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr) return 0;

    auto create = loadSymbol<DfCreate>(library, "df_create");
    auto getFrameLength = loadSymbol<DfGetFrameLength>(library, "df_get_frame_length");
    auto processFrame = loadSymbol<DfProcessFrame>(library, "df_process_frame");
    auto setAttenLim = loadSymbol<DfSetAttenLim>(library, "df_set_atten_lim");
    auto setPostFilterBeta =
        loadSymbol<DfSetPostFilterBeta>(library, "df_set_post_filter_beta");
    auto freeState = loadSymbol<DfFree>(library, "df_free");
    if (create == nullptr || getFrameLength == nullptr || processFrame == nullptr ||
        setAttenLim == nullptr || setPostFilterBeta == nullptr || freeState == nullptr) {
        dlclose(library);
        return 0;
    }

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        dlclose(library);
        return 0;
    }
    void* state = create(path, attenuationLimitDb, nullptr);
    env->ReleaseStringUTFChars(modelPath, path);
    if (state == nullptr) {
        dlclose(library);
        return 0;
    }

    auto* handle = new DeepFilterHandle{
        library,
        state,
        getFrameLength,
        processFrame,
        setAttenLim,
        setPostFilterBeta,
        freeState,
    };
    return static_cast<jlong>(reinterpret_cast<intptr_t>(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_it_michelina_focus_audio_DeepFilterBridge_nativeFrameSize(
    JNIEnv*,
    jobject,
    jlong nativeHandle) {
    auto* handle = deepFilterFromHandle(nativeHandle);
    if (handle == nullptr || handle->state == nullptr) return 0;
    return static_cast<jint>(handle->getFrameLength(handle->state));
}

extern "C" JNIEXPORT jfloat JNICALL
Java_it_michelina_focus_audio_DeepFilterBridge_nativeProcess(
    JNIEnv* env,
    jobject,
    jlong nativeHandle,
    jshortArray input,
    jfloatArray output) {
    auto* handle = deepFilterFromHandle(nativeHandle);
    if (handle == nullptr || handle->state == nullptr || input == nullptr || output == nullptr) {
        return -200.0f;
    }
    const int frameSize = static_cast<int>(handle->getFrameLength(handle->state));
    if (frameSize != kExpectedFrameSize || env->GetArrayLength(input) < frameSize ||
        env->GetArrayLength(output) < frameSize) {
        return -200.0f;
    }

    // DeepFilterNet3 is a fixed 480-sample streaming model. Stack buffers avoid
    // three native heap allocations every 10 ms on the realtime audio thread.
    std::array<jshort, kExpectedFrameSize> pcm{};
    std::array<float, kExpectedFrameSize> inputFloat{};
    std::array<float, kExpectedFrameSize> outputFloat{};
    env->GetShortArrayRegion(input, 0, frameSize, pcm.data());
    if (env->ExceptionCheck()) return -200.0f;
    for (int i = 0; i < frameSize; ++i) inputFloat[i] = pcm[i] / 32768.0f;
    const float localSnr =
        handle->processFrame(handle->state, inputFloat.data(), outputFloat.data());
    for (float& value : outputFloat) value = std::clamp(value, -2.0f, 2.0f);
    env->SetFloatArrayRegion(output, 0, frameSize, outputFloat.data());
    return env->ExceptionCheck() ? -200.0f : localSnr;
}

extern "C" JNIEXPORT void JNICALL
Java_it_michelina_focus_audio_DeepFilterBridge_nativeSetParameters(
    JNIEnv*,
    jobject,
    jlong nativeHandle,
    jfloat attenuationLimitDb,
    jfloat postFilterBeta) {
    auto* handle = deepFilterFromHandle(nativeHandle);
    if (handle == nullptr || handle->state == nullptr) return;
    handle->setAttenLim(handle->state, attenuationLimitDb);
    handle->setPostFilterBeta(handle->state, postFilterBeta);
}

extern "C" JNIEXPORT void JNICALL
Java_it_michelina_focus_audio_DeepFilterBridge_nativeDestroy(
    JNIEnv*,
    jobject,
    jlong nativeHandle) {
    auto* handle = deepFilterFromHandle(nativeHandle);
    if (handle == nullptr) return;
    if (handle->state != nullptr) handle->freeState(handle->state);
    if (handle->library != nullptr) dlclose(handle->library);
    delete handle;
}
