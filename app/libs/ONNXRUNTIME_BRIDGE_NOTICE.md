# ONNX Runtime Java bridge

`onnxruntime-java-android-1.27.0-ort1.27.1.aar` contains the unmodified official Microsoft
Android `classes.jar` and arm64 Java JNI bridge from ONNX Runtime 1.27.0. It intentionally omits
the duplicate `libonnxruntime.so`, because sherpa-onnx already bundles ONNX Runtime 1.27.1.

The bridge's ELF dependency version string was changed from `VERS_1.27.0` to `VERS_1.27.1`.
No code or API was changed. This makes the published Java bridge bind to the patch-level runtime
already used by sherpa and prevents two 20+ MB runtime copies from being packaged.

Upstream source and license: https://github.com/microsoft/onnxruntime (MIT).
