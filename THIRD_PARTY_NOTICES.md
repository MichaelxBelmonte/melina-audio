# Third-party notices

Michelina Focus v0.13 bundles the following third-party runtime and model files.

## RNNoise 0.2

- Project: https://github.com/xiph/rnnoise
- Source release: https://github.com/xiph/rnnoise/releases/tag/v0.2
- Bundled form: native arm64 build compiled from the official C sources and default weights.
- Default-weight source SHA-256: `dece58eabca6a722a0bd9dbc8dfadba27abac1271760fad65e1071f54717dc78`
- License: BSD 3-Clause
- License copy in APK: `assets/licenses/rnnoise-BSD-3.txt`

## UL-UNAS

- Project: https://github.com/Xiaobin-Rong/ul-unas
- Source commit: `00f7c700da43d38347f30a6ccebd86fcbc798e07`
- Bundled model: `assets/models/ulunas_stream_simple.onnx`
- SHA-256: `f2e804d54d6a88f4f82f44d86c9f1cf646db2509bfca935cfbfc5fcd8cbfac3b`
- License: MIT
- License copy in APK: `assets/licenses/ul-unas-MIT.txt`

## DeepFilterNet3 / libDF

- Project: https://github.com/Rikorose/DeepFilterNet
- Source commit: `d375b2d8309e0935d165700c91da9de862a99c31`
- Bundled model: `assets/models/deepfilternet3_onnx.dfmodel` (the official gzipped ONNX archive, renamed only to prevent Android aapt from expanding it)
- Model SHA-256: `c94d91f70911001c946e0fabb4aa9adc37045f45a03b56008cb0c8244cb63616`
- Android arm64 library: `lib/arm64-v8a/libdf.so`
- Library SHA-256: `5adc94ef81e551be444676ee962f56e219db7ba70812078584d3937e447624a8`
- License: MIT OR Apache License 2.0
- License copies in APK: `assets/licenses/deepfilternet-MIT.txt`, `assets/licenses/deepfilternet-APACHE-2.0.txt`
- Android build details: `app/src/main/jniLibs/DEEPFILTER_BUILD_NOTICE.md`

## Silero VAD

- Project: https://github.com/snakers4/silero-vad
- Bundled model: `assets/models/silero_vad.onnx`
- SHA-256: `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`
- License: MIT
- License copy in APK: `assets/licenses/silero-vad-MIT.txt`

## sherpa-onnx 1.13.6

- Project: https://github.com/k2-fsa/sherpa-onnx
- License: Apache License 2.0
- Distributed file: `app/libs/sherpa-onnx-1.13.6.aar`
- SHA-256: `0012d9a28f15bd6fb966b62b70a75da3990512fdccce28b83098248ce4be1698`
- License copy in APK: `assets/licenses/sherpa-onnx-APACHE-2.0.txt`

## TEN VAD

- Project: https://github.com/TEN-framework/ten-vad
- Bundled model: `assets/models/ten-vad.onnx`
- SHA-256: `718cb7eef47e3cf5ddbe7e967a7503f46b8b469c0706872f494dfa921b486206`
- License: Apache License 2.0 with additional deployment and non-compete restrictions from Agora.
- Status in Michelina Focus: optional experimental VAD for internal evaluation; review the additional terms before external or commercial distribution.
- License and notices in APK: `assets/licenses/ten-vad-LICENSE.txt`, `assets/licenses/ten-vad-NOTICES.txt`

## ONNX Runtime

- Project: https://github.com/microsoft/onnxruntime
- License: MIT
- Native runtime libraries are distributed inside the sherpa-onnx AAR.
- The Java/JNI bridge for UL-UNAS is distributed as `app/libs/onnxruntime-java-android-1.27.0-ort1.27.1.aar`; its provenance and compatibility patch are documented in `app/libs/ONNXRUNTIME_BRIDGE_NOTICE.md`.
- Bridge AAR SHA-256: `c48e10b86c403f4208f1b82ceaabe31340789eca1ccd96c2571296cd97d9e50d`
- License copy in APK: `assets/licenses/onnxruntime-MIT.txt`

## GTCRN

- Project: https://github.com/Xiaobin-Rong/gtcrn
- License: MIT
- Model source: https://github.com/k2-fsa/sherpa-onnx/releases/tag/speech-enhancement-models
- Distributed file: `assets/models/gtcrn_simple.onnx`
- SHA-256: `e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534`
- License copy in APK: `assets/licenses/gtcrn-MIT.txt`

## DPDFNet

- Project: https://github.com/ceva-ip/DPDFNet
- License: Apache License 2.0
- Model source: https://github.com/k2-fsa/sherpa-onnx/releases/tag/speech-enhancement-models
- Distributed files:
  - `assets/models/dpdfnet2.onnx`
    - SHA-256: `ce35d6025fc71df0ef10d1540e1b7916837bbfe5f6896deb744508d2cad487a9`
  - `assets/models/dpdfnet4.onnx`
    - SHA-256: `71b588bc26163941aa82a592cce924b08c1fbdc0879fe44f5a2d4eac44bd8420`
  - `assets/models/dpdfnet2_48khz_hr.onnx`
    - SHA-256: `0b399f8a58dc4d70d8cd97541f5c39869406145193b957d00a03b66070944928`
  - `assets/models/dpdfnet8.onnx`
    - SHA-256: `2751c1f5a4e849d23a07c675b4c838158b249b42152f10cc318522dd339134f0`
- License copy in APK: `assets/licenses/dpdfnet-APACHE-2.0.txt`

The instrumentation-only noisy-speech WAV fixture is taken from the same sherpa-onnx speech-enhancement release. Its 48 kHz copy is mechanically resampled for testing and neither fixture is included in the application APK.
