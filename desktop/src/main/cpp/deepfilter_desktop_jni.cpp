#include <jni.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <string>

#if defined(_WIN32)
#define NOMINMAX
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace {
constexpr int kFrameSize = 480;

using DfCreate = void* (*)(const char*, float, const char*);
using DfGetFrameLength = size_t (*)(void*);
using DfProcessFrame = float (*)(void*, const float*, float*);
using DfSetAttenLim = void (*)(void*, float);
using DfSetPostFilterBeta = void (*)(void*, float);
using DfFree = void (*)(void*);

struct DeepFilterDesktopHandle {
#if defined(_WIN32)
    HMODULE library = nullptr;
#else
    void* library = nullptr;
#endif
    void* state = nullptr;
    DfGetFrameLength getFrameLength = nullptr;
    DfProcessFrame processFrame = nullptr;
    DfSetAttenLim setAttenLim = nullptr;
    DfSetPostFilterBeta setPostFilterBeta = nullptr;
    DfFree freeState = nullptr;
};

DeepFilterDesktopHandle* fromHandle(jlong handle) {
    return reinterpret_cast<DeepFilterDesktopHandle*>(static_cast<intptr_t>(handle));
}

void throwIllegalState(JNIEnv* env, const std::string& message) {
    const jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) env->ThrowNew(exception, message.c_str());
}

#if defined(_WIN32)
std::wstring utf8ToWide(const char* value) {
    if (value == nullptr) return {};
    const int size = MultiByteToWideChar(CP_UTF8, 0, value, -1, nullptr, 0);
    if (size <= 0) return {};
    std::wstring result(static_cast<size_t>(size), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, value, -1, result.data(), size);
    result.resize(result.size() - 1);
    return result;
}

template <typename T>
T loadSymbol(HMODULE library, const char* name) {
    return reinterpret_cast<T>(GetProcAddress(library, name));
}
#else
template <typename T>
T loadSymbol(void* library, const char* name) {
    return reinterpret_cast<T>(dlsym(library, name));
}
#endif

void releaseHandle(DeepFilterDesktopHandle* handle) {
    if (handle == nullptr) return;
    if (handle->state != nullptr && handle->freeState != nullptr) handle->freeState(handle->state);
#if defined(_WIN32)
    if (handle->library != nullptr) FreeLibrary(handle->library);
#else
    if (handle->library != nullptr) dlclose(handle->library);
#endif
    delete handle;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_it_michelina_focus_desktop_DesktopDeepFilterBridge_nativeCreate(
    JNIEnv* env,
    jobject,
    jstring libraryPath,
    jstring modelPath,
    jfloat attenuationLimitDb) {
    if (libraryPath == nullptr || modelPath == nullptr) {
        throwIllegalState(env, "DeepFilterNet3: missing library or model path");
        return 0;
    }
    const char* libraryUtf8 = env->GetStringUTFChars(libraryPath, nullptr);
    const char* modelUtf8 = env->GetStringUTFChars(modelPath, nullptr);
    if (libraryUtf8 == nullptr || modelUtf8 == nullptr) {
        if (libraryUtf8 != nullptr) env->ReleaseStringUTFChars(libraryPath, libraryUtf8);
        if (modelUtf8 != nullptr) env->ReleaseStringUTFChars(modelPath, modelUtf8);
        return 0;
    }

    auto* handle = new DeepFilterDesktopHandle();
#if defined(_WIN32)
    const std::wstring libraryWide = utf8ToWide(libraryUtf8);
    handle->library = LoadLibraryW(libraryWide.c_str());
#else
    handle->library = dlopen(libraryUtf8, RTLD_NOW | RTLD_LOCAL);
#endif
    if (handle->library == nullptr) {
        env->ReleaseStringUTFChars(libraryPath, libraryUtf8);
        env->ReleaseStringUTFChars(modelPath, modelUtf8);
        releaseHandle(handle);
        throwIllegalState(env, "DeepFilterNet3: unable to load the desktop libDF runtime");
        return 0;
    }

    const auto create = loadSymbol<DfCreate>(handle->library, "df_create");
    handle->getFrameLength = loadSymbol<DfGetFrameLength>(handle->library, "df_get_frame_length");
    handle->processFrame = loadSymbol<DfProcessFrame>(handle->library, "df_process_frame");
    handle->setAttenLim = loadSymbol<DfSetAttenLim>(handle->library, "df_set_atten_lim");
    handle->setPostFilterBeta =
        loadSymbol<DfSetPostFilterBeta>(handle->library, "df_set_post_filter_beta");
    handle->freeState = loadSymbol<DfFree>(handle->library, "df_free");
    if (create == nullptr || handle->getFrameLength == nullptr || handle->processFrame == nullptr ||
        handle->setAttenLim == nullptr || handle->setPostFilterBeta == nullptr ||
        handle->freeState == nullptr) {
        env->ReleaseStringUTFChars(libraryPath, libraryUtf8);
        env->ReleaseStringUTFChars(modelPath, modelUtf8);
        releaseHandle(handle);
        throwIllegalState(env, "DeepFilterNet3: the libDF C API is incomplete");
        return 0;
    }

    handle->state = create(modelUtf8, attenuationLimitDb, nullptr);
    env->ReleaseStringUTFChars(libraryPath, libraryUtf8);
    env->ReleaseStringUTFChars(modelPath, modelUtf8);
    if (handle->state == nullptr) {
        releaseHandle(handle);
        throwIllegalState(env, "DeepFilterNet3: model initialization failed");
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_it_michelina_focus_desktop_DesktopDeepFilterBridge_nativeFrameSize(
    JNIEnv*,
    jobject,
    jlong nativeHandle) {
    auto* handle = fromHandle(nativeHandle);
    if (handle == nullptr || handle->state == nullptr) return 0;
    return static_cast<jint>(handle->getFrameLength(handle->state));
}

extern "C" JNIEXPORT jfloat JNICALL
Java_it_michelina_focus_desktop_DesktopDeepFilterBridge_nativeProcess(
    JNIEnv* env,
    jobject,
    jlong nativeHandle,
    jshortArray input,
    jfloatArray output) {
    auto* handle = fromHandle(nativeHandle);
    if (handle == nullptr || handle->state == nullptr || input == nullptr || output == nullptr) {
        return -200.0f;
    }
    const int frameSize = static_cast<int>(handle->getFrameLength(handle->state));
    if (frameSize != kFrameSize || env->GetArrayLength(input) < frameSize ||
        env->GetArrayLength(output) < frameSize) {
        return -200.0f;
    }

    std::array<jshort, kFrameSize> pcm{};
    std::array<float, kFrameSize> inputFloat{};
    std::array<float, kFrameSize> outputFloat{};
    env->GetShortArrayRegion(input, 0, frameSize, pcm.data());
    if (env->ExceptionCheck()) return -200.0f;
    for (int index = 0; index < frameSize; ++index) inputFloat[index] = pcm[index] / 32768.0f;
    const float localSnr =
        handle->processFrame(handle->state, inputFloat.data(), outputFloat.data());
    for (float& value : outputFloat) value = std::clamp(value, -2.0f, 2.0f);
    env->SetFloatArrayRegion(output, 0, frameSize, outputFloat.data());
    return env->ExceptionCheck() ? -200.0f : localSnr;
}

extern "C" JNIEXPORT void JNICALL
Java_it_michelina_focus_desktop_DesktopDeepFilterBridge_nativeSetParameters(
    JNIEnv*,
    jobject,
    jlong nativeHandle,
    jfloat attenuationLimitDb,
    jfloat postFilterBeta) {
    auto* handle = fromHandle(nativeHandle);
    if (handle == nullptr || handle->state == nullptr) return;
    handle->setAttenLim(handle->state, attenuationLimitDb);
    handle->setPostFilterBeta(handle->state, postFilterBeta);
}

extern "C" JNIEXPORT void JNICALL
Java_it_michelina_focus_desktop_DesktopDeepFilterBridge_nativeDestroy(
    JNIEnv*,
    jobject,
    jlong nativeHandle) {
    releaseHandle(fromHandle(nativeHandle));
}
