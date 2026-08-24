# Melina universal architecture

Melina uses one platform-neutral audio pipeline and small platform adapters for devices,
model runtimes, storage, and user interfaces. The shared code lives in `audio-core`; Android and
desktop applications consume the same processor contracts, DSP, resampling, VAD state machine,
neural alignment, hearing-oriented fitting, compressor, limiter, settings, and telemetry.

## Supported targets

| Target | Architecture | Application | Status |
|---|---|---|---|
| Android 12+ | ARM64 | APK | Full Android backend set |
| Android 12+ | x86_64 | APK | GTCRN, DPDFNet, RNNoise, Classic DSP |
| macOS | Apple Silicon, Intel | Desktop GUI/CLI | Full nine-backend set |
| Windows | x64, ARM64 | Desktop GUI/CLI | Full nine-backend set |
| Linux | x64, ARM64 | Desktop GUI/CLI | Full nine-backend set |

The desktop Gradle build selects the official sherpa-onnx native library matching the build host.
UL-UNAS calls the C API of the same packaged ONNX Runtime, including on Apple Silicon and ARM64 PCs.
DeepFilterNet3 uses the official libDF source at a pinned commit and dependency lock. The CI matrix
builds all six OS/architecture combinations independently, so each artifact contains only its own
native runtimes. `packageAppImage` uses `jpackage` to include Java; users do not need to install a
JDK. Desktop-only builds pass `-Pmelina.desktopOnly`, so no Android SDK is required or configured.

## Module boundaries

```text
audio-core
  RealtimeAudioProcessor
  Classic spectral DSP
  neural wet/dry alignment
  48↔16 kHz streaming conversion
  VAD resampling, smoothing, and hangover
  seven-band speech fitting
  compressor and limiter
  settings and telemetry

app (Android)
  AudioRecord / AudioTrack and route management
  Android AssetManager model adapters
  Android sherpa-onnx, ONNX Runtime, RNNoise, libDF
  Android UI and session storage

desktop
  Java Sound device I/O
  filesystem/classpath model adapter
  desktop sherpa-onnx and ONNX Runtime C API adapters
  desktop RNNoise and libDF native adapters
  Swing GUI and command-line interface
  native app-image and ZIP distributions
```

## Remaining parity work

All nine enhancement backends are available on Android ARM64 and every desktop target; Android
x86_64 retains the seven portable Android backends. Desktop session logging and the detailed
Android plots still need desktop UI/storage adapters for complete UX parity. iOS/iPadOS requires a
separate Swift audio-device adapter and is not currently a supported target.
