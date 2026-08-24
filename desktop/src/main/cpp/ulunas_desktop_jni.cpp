#include <jni.h>

#include <array>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#if defined(_WIN32)
#define NOMINMAX
#include <windows.h>
#else
#include <dlfcn.h>
#endif

#include "onnxruntime_c_api.h"

namespace {
constexpr size_t kSpectrumSize = 514;
constexpr size_t kConvCacheSize = 5358;
constexpr size_t kTfaCacheSize = 402;
constexpr size_t kInterCacheSize = 1056;

struct UlunasHandle {
#if defined(_WIN32)
    HMODULE runtime = nullptr;
#else
    void* runtime = nullptr;
#endif
    const OrtApi* api = nullptr;
    OrtEnv* environment = nullptr;
    OrtSession* session = nullptr;
    OrtMemoryInfo* memoryInfo = nullptr;
    std::array<float, kSpectrumSize> inputSpectrum{};
    std::array<float, kConvCacheSize> convCache{};
    std::array<float, kTfaCacheSize> tfaCache{};
    std::array<float, kInterCacheSize> interCache{};
};

UlunasHandle* fromHandle(jlong handle) {
    return reinterpret_cast<UlunasHandle*>(static_cast<intptr_t>(handle));
}

void throwIllegalState(JNIEnv* env, const std::string& message) {
    const jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) env->ThrowNew(exception, message.c_str());
}

bool checkStatus(JNIEnv* env, const OrtApi* api, OrtStatus* status, const char* operation) {
    if (status == nullptr) return true;
    std::string message(operation);
    message += ": ";
    message += api->GetErrorMessage(status);
    api->ReleaseStatus(status);
    throwIllegalState(env, message);
    return false;
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
#endif

void unloadRuntime(UlunasHandle* handle) {
    if (handle == nullptr) return;
    if (handle->api != nullptr) {
        if (handle->memoryInfo != nullptr) handle->api->ReleaseMemoryInfo(handle->memoryInfo);
        if (handle->session != nullptr) handle->api->ReleaseSession(handle->session);
        if (handle->environment != nullptr) handle->api->ReleaseEnv(handle->environment);
    }
#if defined(_WIN32)
    if (handle->runtime != nullptr) FreeLibrary(handle->runtime);
#else
    if (handle->runtime != nullptr) dlclose(handle->runtime);
#endif
    delete handle;
}

bool copyFromJava(
    JNIEnv* env,
    jfloatArray source,
    float* destination,
    size_t expectedSize,
    const char* label) {
    if (source == nullptr || static_cast<size_t>(env->GetArrayLength(source)) < expectedSize) {
        throwIllegalState(env, std::string("UL-UNAS: invalid ") + label + " buffer");
        return false;
    }
    env->GetFloatArrayRegion(source, 0, static_cast<jsize>(expectedSize), destination);
    return !env->ExceptionCheck();
}

bool makeTensor(
    JNIEnv* env,
    UlunasHandle* handle,
    float* data,
    size_t elementCount,
    const int64_t* shape,
    size_t shapeSize,
    OrtValue** output) {
    return checkStatus(
        env,
        handle->api,
        handle->api->CreateTensorWithDataAsOrtValue(
            handle->memoryInfo,
            data,
            elementCount * sizeof(float),
            shape,
            shapeSize,
            ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,
            output),
        "UL-UNAS tensor creation");
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_it_michelina_focus_desktop_DesktopUlunasBridge_nativeCreate(
    JNIEnv* env,
    jobject,
    jstring runtimePath,
    jstring modelPath) {
    if (runtimePath == nullptr || modelPath == nullptr) {
        throwIllegalState(env, "UL-UNAS: missing runtime or model path");
        return 0;
    }

    const char* runtimeUtf8 = env->GetStringUTFChars(runtimePath, nullptr);
    const char* modelUtf8 = env->GetStringUTFChars(modelPath, nullptr);
    if (runtimeUtf8 == nullptr || modelUtf8 == nullptr) {
        if (runtimeUtf8 != nullptr) env->ReleaseStringUTFChars(runtimePath, runtimeUtf8);
        if (modelUtf8 != nullptr) env->ReleaseStringUTFChars(modelPath, modelUtf8);
        return 0;
    }

    auto* handle = new UlunasHandle();
#if defined(_WIN32)
    const std::wstring runtimeWide = utf8ToWide(runtimeUtf8);
    const std::wstring modelWide = utf8ToWide(modelUtf8);
    handle->runtime = LoadLibraryW(runtimeWide.c_str());
    const auto getApiBase = reinterpret_cast<const OrtApiBase*(ORT_API_CALL*)()>(
        handle->runtime == nullptr ? nullptr : GetProcAddress(handle->runtime, "OrtGetApiBase"));
#else
    const std::string modelNative(modelUtf8);
    handle->runtime = dlopen(runtimeUtf8, RTLD_NOW | RTLD_LOCAL);
    const auto getApiBase = reinterpret_cast<const OrtApiBase*(ORT_API_CALL*)()>(
        handle->runtime == nullptr ? nullptr : dlsym(handle->runtime, "OrtGetApiBase"));
#endif
    env->ReleaseStringUTFChars(runtimePath, runtimeUtf8);
    env->ReleaseStringUTFChars(modelPath, modelUtf8);

    if (getApiBase == nullptr) {
        unloadRuntime(handle);
        throwIllegalState(env, "UL-UNAS: unable to load OrtGetApiBase from the desktop runtime");
        return 0;
    }
    handle->api = getApiBase()->GetApi(ORT_API_VERSION);
    if (handle->api == nullptr) {
        unloadRuntime(handle);
        throwIllegalState(env, "UL-UNAS: ONNX Runtime C API version mismatch");
        return 0;
    }

    OrtSessionOptions* options = nullptr;
    bool ok = checkStatus(
        env,
        handle->api,
        handle->api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "michelina-ulunas", &handle->environment),
        "UL-UNAS environment creation");
    if (ok) ok = checkStatus(
        env, handle->api, handle->api->CreateSessionOptions(&options),
        "UL-UNAS session options creation");
    if (ok) ok = checkStatus(
        env, handle->api, handle->api->SetIntraOpNumThreads(options, 1),
        "UL-UNAS intra-op configuration");
    if (ok) ok = checkStatus(
        env, handle->api, handle->api->SetInterOpNumThreads(options, 1),
        "UL-UNAS inter-op configuration");
    if (ok) ok = checkStatus(
        env, handle->api, handle->api->SetSessionGraphOptimizationLevel(options, ORT_ENABLE_ALL),
        "UL-UNAS graph optimization");
    if (ok) {
#if defined(_WIN32)
        ok = checkStatus(
            env, handle->api,
            handle->api->CreateSession(handle->environment, modelWide.c_str(), options, &handle->session),
            "UL-UNAS model loading");
#else
        ok = checkStatus(
            env, handle->api,
            handle->api->CreateSession(handle->environment, modelNative.c_str(), options, &handle->session),
            "UL-UNAS model loading");
#endif
    }
    if (options != nullptr) handle->api->ReleaseSessionOptions(options);
    if (ok) ok = checkStatus(
        env, handle->api,
        handle->api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &handle->memoryInfo),
        "UL-UNAS memory configuration");
    if (!ok) {
        unloadRuntime(handle);
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_it_michelina_focus_desktop_DesktopUlunasBridge_nativeProcess(
    JNIEnv* env,
    jobject,
    jlong nativeHandle,
    jfloatArray inputSpectrum,
    jfloatArray convCache,
    jfloatArray tfaCache,
    jfloatArray interCache,
    jfloatArray enhancedSpectrum) {
    auto* handle = fromHandle(nativeHandle);
    if (handle == nullptr || handle->session == nullptr) {
        throwIllegalState(env, "UL-UNAS has already been released");
        return;
    }
    if (!copyFromJava(env, inputSpectrum, handle->inputSpectrum.data(), kSpectrumSize, "spectrum") ||
        !copyFromJava(env, convCache, handle->convCache.data(), kConvCacheSize, "conv cache") ||
        !copyFromJava(env, tfaCache, handle->tfaCache.data(), kTfaCacheSize, "TFA cache") ||
        !copyFromJava(env, interCache, handle->interCache.data(), kInterCacheSize, "inter cache")) {
        return;
    }

    const int64_t mixShape[] = {1, 257, 1, 2};
    const int64_t convShape[] = {1, static_cast<int64_t>(kConvCacheSize)};
    const int64_t tfaShape[] = {1, static_cast<int64_t>(kTfaCacheSize)};
    const int64_t interShape[] = {1, static_cast<int64_t>(kInterCacheSize)};
    std::array<OrtValue*, 4> inputs{};
    std::array<OrtValue*, 4> outputs{};
    bool ok = makeTensor(env, handle, handle->inputSpectrum.data(), kSpectrumSize, mixShape, 4, &inputs[0]);
    if (ok) ok = makeTensor(env, handle, handle->convCache.data(), kConvCacheSize, convShape, 2, &inputs[1]);
    if (ok) ok = makeTensor(env, handle, handle->tfaCache.data(), kTfaCacheSize, tfaShape, 2, &inputs[2]);
    if (ok) ok = makeTensor(env, handle, handle->interCache.data(), kInterCacheSize, interShape, 2, &inputs[3]);

    static constexpr const char* inputNames[] = {
        "mix", "conv_cache", "tfa_cache", "inter_cache"};
    static constexpr const char* outputNames[] = {
        "enh", "conv_cache_out", "tfa_cache_out", "inter_cache_out"};
    if (ok) ok = checkStatus(
        env,
        handle->api,
        handle->api->Run(
            handle->session,
            nullptr,
            inputNames,
            inputs.data(),
            inputs.size(),
            outputNames,
            outputs.size(),
            outputs.data()),
        "UL-UNAS inference");

    std::array<void*, 4> outputData{};
    for (size_t index = 0; ok && index < outputData.size(); ++index) {
        ok = checkStatus(
            env, handle->api, handle->api->GetTensorMutableData(outputs[index], &outputData[index]),
            "UL-UNAS output access");
    }
    if (ok) {
        env->SetFloatArrayRegion(
            enhancedSpectrum, 0, static_cast<jsize>(kSpectrumSize),
            static_cast<const float*>(outputData[0]));
        env->SetFloatArrayRegion(
            convCache, 0, static_cast<jsize>(kConvCacheSize),
            static_cast<const float*>(outputData[1]));
        env->SetFloatArrayRegion(
            tfaCache, 0, static_cast<jsize>(kTfaCacheSize),
            static_cast<const float*>(outputData[2]));
        env->SetFloatArrayRegion(
            interCache, 0, static_cast<jsize>(kInterCacheSize),
            static_cast<const float*>(outputData[3]));
    }
    for (OrtValue* value : inputs) {
        if (value != nullptr) handle->api->ReleaseValue(value);
    }
    for (OrtValue* value : outputs) {
        if (value != nullptr) handle->api->ReleaseValue(value);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_it_michelina_focus_desktop_DesktopUlunasBridge_nativeDestroy(
    JNIEnv*,
    jobject,
    jlong nativeHandle) {
    unloadRuntime(fromHandle(nativeHandle));
}
