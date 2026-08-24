<p align="center">
  <img src="docs/assets/melina-mark.png" width="92" alt="Melina logo">
</p>

<h1 align="center">Melina</h1>

<p align="center"><strong>Bring every voice forward.</strong></p>

<p align="center">
  <a href="https://michaelxbelmonte.github.io/melina-audio/">Website</a> ·
  <a href="UNIVERSAL.md">Platforms</a> ·
  <a href="BRAND.md">Brand guide</a>
</p>

Melina is an experimental, local assistive-listening processor for Android, macOS, Windows, and Linux. It captures a selectable microphone, applies real-time speech enhancement and hearing-oriented processing, and routes the result to headphones without uploading audio.

> [!CAUTION]
> Melina is a research prototype, not a medical device or a substitute for hearing aids, an audiological assessment, or professional fitting. The limiter prevents digital clipping but does not measure sound pressure at the ear. Start at a low volume and stop immediately if you hear feedback, distortion, or experience discomfort.

## Highlights

- Fully local real-time processing; no audio is uploaded.
- Shared audio core used by Android and desktop rather than separate DSP implementations.
- Native 48 kHz media I/O with a tested polyphase 48↔16 kHz resampler for 16 kHz models.
- Nine enhancement backends: GTCRN, RNNoise, UL-UNAS, DeepFilterNet3, four DPDFNet profiles, and a classic causal DSP baseline.
- Silero VAD and an optional experimental TEN VAD backend.
- Seven-band speech fitting, adaptive quiet-speech boost, compression, and a stateful limiter.
- Android routing for built-in, USB, wired, and Bluetooth HFP microphones.
- Desktop GUI and CLI with selectable Java Sound input/output devices.
- Real-time input/output waveforms, voice probability, DSP load, and per-stage telemetry.
- Local session logging with input/output WAV files, metrics, events, settings changes, and A/B outcomes.
- Equal-input benchmarks that process the same recording through every backend on a physical Android device.

## Processing pipeline

```text
Microphone 48 kHz
  → voice activity detection
  → one selected denoiser
  → seven-band speech fitting
  → wide dynamic range compression
  → limiter
  → headphones
```

Only the selected enhancement backend is loaded into memory. macOS, Linux, Windows x64, and Android ARM64 expose all nine processors. Windows ARM64 exposes eight while upstream tract/libDF lacks a compatible Windows ARM64 assembly build; Android x86_64 exposes the seven backends whose Android libraries support that ABI. RNNoise uses native C, GTCRN/DPDFNet use sherpa-onnx, UL-UNAS uses ONNX Runtime, and DeepFilterNet3 uses the official libDF/Tract implementation through JNI. The platform-neutral STFT, resampling, alignment, fitting, dynamics, and telemetry live in `audio-core`.

## Backends

| Backend | Rate | Intended use |
|---|---:|---|
| GTCRN Fast | 16 kHz | Default daily profile with good compute headroom |
| RNNoise Native 0.2 | 48 kHz | Lightweight full-band baseline for steady noise |
| UL-UNAS Stream | 16 kHz | Compact stateful challenger |
| DeepFilterNet3 HQ | 48 kHz | Experimental full-band deep-filtering profile |
| DPDFNet2 Balanced | 16 kHz | Distant voices and stronger reduction |
| DPDFNet4 Strong | 16 kHz | Difficult noise with lower thermal headroom |
| DPDFNet8 Speech | 16 kHz | Maximum-quality lab profile with overload risk |
| DPDFNet2 HQ | 48 kHz | Experimental full-band DPDFNet profile |
| Classic DSP | 48 kHz | Neural-free baseline and Bluetooth HFP fallback |

Model quality cannot be inferred from CPU time alone. Compare intelligibility, naturalness, artifacts, latency, thermal behavior, and listening fatigue at matched loudness.

## Android requirements

- Android Studio with JDK 17
- Android SDK 36
- Android NDK `28.2.13676358`
- An ARM64 or x86_64 Android device running Android 12 or newer
- A Pixel 8 Pro only when reproducing the original device-specific measurements

The APK contains `arm64-v8a` and `x86_64`. DeepFilterNet3 and UL-UNAS are shown only on ARM64 because their current Android native bridges are ARM64-only; the other backends remain available on x86_64.

## Build and test

Clone the repository and run:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

With a compatible Android device connected through ADB, run the instrumentation suite:

```bash
./gradlew connectedDebugAndroidTest
```

## Desktop build and use

The desktop target supports macOS (Apple Silicon and Intel), Windows (x64 and ARM64), and Linux
(x64 and ARM64). A full nine-backend distribution also builds the official libDF runtime from its
pinned source commit; install Rust 1.97.1 and run:

```bash
bash scripts/build_deepfilter_desktop.sh
./gradlew -Pmelina.desktopOnly :audio-core:test :desktop:test :desktop:installDist
```

If Rust is unavailable, the Gradle command still creates a functional eight-backend distribution
without DeepFilterNet3. Official CI artifacts include libDF on every desktop target except Windows
ARM64, where upstream tract 0.21 cannot currently assemble its ARM64 kernels for the MSVC ABI.

Launch the graphical application:

```bash
desktop/build/install/melina/bin/melina
```

The same distribution includes a command-line interface:

```bash
desktop/build/install/melina/bin/melina --list-devices
desktop/build/install/melina/bin/melina --cli --backend gtcrn
desktop/build/install/melina/bin/melina --cli --backend ulunas
desktop/build/install/melina/bin/melina --cli --backend deepfilter
```

Create a ZIP distribution with `./gradlew -Pmelina.desktopOnly :desktop:distZip`. With a full JDK containing `jpackage`,
`./gradlew -Pmelina.desktopOnly :desktop:packageAppImage` creates a native application image with Java included. See
[UNIVERSAL.md](UNIVERSAL.md) for the platform matrix, architecture, and remaining parity work.

## Basic device setup

1. Pair the headphones and enable media audio in Android's Bluetooth settings.
2. Connect a USB-C receiver or microphone when available.
3. Install the debug APK and grant Microphone and Nearby devices permissions.
4. Select **Automatic** or a specific input and verify the routes shown by the app.
5. Start with low Android volume and `+0 dB` software gain.
6. Compare presets using the same sentences, room, microphone position, and listening volume.

A remote microphone placed near the person speaking often improves the signal-to-noise ratio more than a heavier denoising model. Keep the phone exposed when using its built-in microphone.

### Bluetooth HFP limitation

Android routes both directions through an 8 kHz mono voice profile when a Bluetooth HFP microphone is active and disables high-quality A2DP output. Melina therefore uses Classic DSP for HFP input. For normal neural processing, use the phone’s built-in, USB, or wired input with A2DP or wired output.

## Session logs

Press **LOG** to begin a session. Logging starts the audio pipeline when necessary; **STOP LOG** closes the session without requiring playback to stop. Sessions remain in the app-specific directory:

```text
Android/data/it.michelina.focus/files/sessions
```

Each session contains:

- `input_raw.wav` — mono PCM captured from the microphone.
- `output_processed.wav` — mono PCM actually sent to the output.
- `metrics.csv` — levels, DSP load, model effect, settings, routes, and VAD telemetry.
- `events.csv` — start/stop events, setting changes, and understood/missed annotations.
- `summary.json` — aggregate data used by the in-app log viewer.

Analyze sessions on a workstation with:

```bash
python3 scripts/analyze_phone_reports.py path/to/exported-sessions
```

The script requires NumPy and produces CSV, Markdown, and HTML reports. Raw recordings may contain sensitive speech; review them before sharing and never commit them to the repository.

## Equal-input benchmark

To remove variation between live tests, process one 48 kHz mono PCM16 WAV through every backend on the connected device:

```bash
scripts/benchmark_models_on_pixel.sh path/to/input.wav
```

The output directory contains the original WAV, one processed WAV per backend, `benchmark.csv`, and a local `index.html` comparison page. Gain, presence, and weak-voice boost are disabled for this benchmark.

## Known limitations

- This is a mono speech-enhancement pipeline, not speaker identification or target-speaker extraction. It cannot selectively remove one person's voice while preserving another.
- Android does not expose portable analog microphone-gain control to ordinary apps. System AGC may raise both quiet speech and noise.
- Reported inference time is not end-to-end latency. Android buffers, scheduling, resampling, Bluetooth codecs, and wireless buffering also contribute.
- The heavier DPDFNet profiles may exceed their real-time budget when the device is thermally throttled.
- Hearing safety, maximum output, and personalized fitting have not been clinically validated.
- Desktop session logging and the detailed Android analysis plots are not yet at feature parity.

## Contributing

Contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. Please use GitHub Issues for reproducible bugs, measurement proposals, and focused feature discussions.

## License

Melina source code is available under the [MIT License](LICENSE). Bundled libraries, native code, model files, and test fixtures remain under their respective licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the license copies under `app/src/main/assets/licenses`.

TEN VAD is a special case: its upstream license adds deployment and non-compete restrictions to Apache 2.0. It is therefore not covered by this project's MIT license and may not meet standard open-source definitions. Review its bundled license before redistributing or deploying it. Silero VAD remains the default.
