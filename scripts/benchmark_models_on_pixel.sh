#!/bin/zsh
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: scripts/benchmark_models_on_pixel.sh <input-48k-mono-pcm16.wav> [output-directory]"
  exit 2
fi

PROJECT_DIR="${0:A:h:h}"
INPUT_WAV="${1:A}"
OUTPUT_DIR="${2:-$PROJECT_DIR/reports/model-comparison-$(date +%Y%m%d-%H%M%S)}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
DEVICE_DIR="/sdcard/Android/data/it.michelina.focus/files/benchmark"
TEST_CLASS="it.michelina.focus.audio.ModelComparisonInstrumentedTest"

if [[ ! -f "$INPUT_WAV" ]]; then
  echo "File not found: $INPUT_WAV"
  exit 2
fi

cd "$PROJECT_DIR"
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew \
  installDebug installDebugAndroidTest --console=plain
"$ADB" shell mkdir -p "$DEVICE_DIR"
"$ADB" push "$INPUT_WAV" "$DEVICE_DIR/input.wav"
"$ADB" shell am instrument -w \
  -e modelComparison true \
  -e class "$TEST_CLASS" \
  it.michelina.focus.test/androidx.test.runner.AndroidJUnitRunner
mkdir -p "$OUTPUT_DIR"
"$ADB" pull "$DEVICE_DIR/." "$OUTPUT_DIR"
cp "$PROJECT_DIR/scripts/model_comparison_template.html" "$OUTPUT_DIR/index.html"

echo "Comparison ready: $OUTPUT_DIR"
echo "Listen to output_*.wav at the same volume; benchmark.csv measures load, not perceived quality."
