# Contributing to Melina

Thank you for helping improve Melina. The project welcomes focused bug fixes, tests, documentation improvements, performance measurements, and carefully evaluated audio-processing changes.

## Before you start

- Search existing issues before opening a new one.
- Keep changes focused and explain the user-visible or measurable benefit.
- Do not commit session recordings, personal data, generated reports, APKs, local SDK paths, credentials, or signing keys.
- Check the license and redistribution terms of every new dependency, model, dataset, or fixture.
- Discuss large architecture changes in an issue before investing in an implementation.

## Development workflow

1. Fork the repository and create a short-lived branch.
2. Make the smallest coherent change.
3. Add or update tests where practical.
4. Run the local verification suite:

   ```bash
   ./gradlew testDebugUnitTest assembleDebug
   ```

5. Run instrumentation tests on ARM64 hardware when the change affects routing, models, native code, resampling, or real-time behavior:

   ```bash
   ./gradlew connectedDebugAndroidTest
   ```

6. Open a pull request with the motivation, test evidence, device and Android version, and any latency or thermal impact.

## Audio and benchmark evidence

When reporting audio results, include the input source, output route, backend, capture profile, sample rate, device, Android version, test duration, DSP average/peak, underruns, and whether the device was thermally warm. Do not publish recordings without the informed permission of every identifiable speaker.

CPU time alone is not evidence of better audio quality. Claims about improvement should distinguish objective metrics from listening-test observations and should compare at matched loudness.

## Pull-request checklist

- User-facing text and documentation are in English.
- New code follows the existing Kotlin or C++ style.
- Tests pass locally.
- No private recordings, generated outputs, credentials, or signing material are included.
- Third-party provenance, checksums, and license copies are updated when applicable.
- Medical or hearing-safety claims are conservative and supported by appropriate evidence.

By contributing, you agree that your contribution will be licensed under the MIT License. Third-party components remain under their original licenses.
