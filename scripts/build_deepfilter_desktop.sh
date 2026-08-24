#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd "$script_dir/.." && pwd)"
commit="d375b2d8309e0935d165700c91da9de862a99c31"
archive_sha256="49471f3633a24c097d82f3b0d2dbd83a0c1bac3e2f6f6c9a675ef0020ebe5c51"
build_root="${MELINA_DEEPFILTER_BUILD_DIR:-${MICHELINA_DEEPFILTER_BUILD_DIR:-$project_root/desktop/build/deepfilter}}"
archive="$build_root/DeepFilterNet-$commit.tar.gz"
source_root="$build_root/source-$commit"
target_root="$build_root/target"
tract_vendor="$project_root/desktop/deepfilter/vendor/tract-linalg-0.21.4"

command -v cargo >/dev/null || {
    echo "cargo non trovato: installa Rust 1.97.1 con rustup." >&2
    exit 1
}
command -v curl >/dev/null || {
    echo "curl non trovato." >&2
    exit 1
}

sha256_file() {
    if command -v shasum >/dev/null; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        sha256sum "$1" | awk '{print $1}'
    fi
}

mkdir -p "$build_root"
if [[ ! -f "$archive" ]] || [[ "$(sha256_file "$archive")" != "$archive_sha256" ]]; then
    temporary_archive="$archive.download"
    curl -fL \
        "https://github.com/Rikorose/DeepFilterNet/archive/$commit.tar.gz" \
        -o "$temporary_archive"
    actual_sha256="$(sha256_file "$temporary_archive")"
    if [[ "$actual_sha256" != "$archive_sha256" ]]; then
        echo "Checksum DeepFilterNet non valido: $actual_sha256" >&2
        exit 1
    fi
    mv -f "$temporary_archive" "$archive"
fi

if [[ ! -f "$source_root/libDF/Cargo.toml" ]]; then
    mkdir -p "$source_root"
    tar -xzf "$archive" -C "$source_root" --strip-components=1
fi

kernel="$(uname -s | tr '[:upper:]' '[:lower:]')"
machine="${RUNNER_ARCH:-$(uname -m)}"
machine="$(printf '%s' "$machine" | tr '[:upper:]' '[:lower:]')"

# tract-linalg 0.21.4 sends generated ARM64 GNU assembly to cl.exe on a native Windows
# ARM64 host. Use Melina's checksum-pinned source patch and LLVM's GNU-compatible driver
# for that target; other platforms retain the upstream compiler selection and code path.
patched_tract="$source_root/vendor/tract-linalg-0.21.4"
mkdir -p "$patched_tract"
cp -R "$tract_vendor/." "$patched_tract/"
if ! grep -Fq 'tract-linalg = { path = "vendor/tract-linalg-0.21.4" }' "$source_root/Cargo.toml"; then
    printf '\n[patch.crates-io]\ntract-linalg = { path = "vendor/tract-linalg-0.21.4" }\n' \
        >> "$source_root/Cargo.toml"
fi

if [[ "$kernel" == mingw* || "$kernel" == msys* || "$kernel" == cygwin* ]] &&
    [[ "$machine" == arm64 || "$machine" == aarch64 ]]; then
    command -v clang >/dev/null || {
        echo "clang non trovato: necessario per DeepFilterNet su Windows ARM64." >&2
        exit 1
    }
    export CC_aarch64_pc_windows_msvc="${CC_aarch64_pc_windows_msvc:-clang}"
fi

# The official C API enables an embedded default model. Melina passes the same official model
# as a resource, so remove only that redundant feature, matching the Android libDF build.
perl -0pi -e \
    's/capi = \["tract", "default-model", "dep:ndarray", "logging"\]/capi = ["tract", "dep:ndarray", "logging"]/' \
    "$source_root/libDF/Cargo.toml"
cp "$project_root/desktop/deepfilter/Cargo.lock" "$source_root/Cargo.lock"

CARGO_TARGET_DIR="$target_root" cargo build \
    --release \
    --locked \
    --manifest-path "$source_root/Cargo.toml" \
    -p deep_filter \
    --no-default-features \
    --features capi

case "$machine" in
    arm64|aarch64) architecture="aarch64" ;;
    x64|x86_64|amd64) architecture="x64" ;;
    *) echo "Architettura desktop non supportata: $machine" >&2; exit 1 ;;
esac

case "$kernel" in
    darwin*) platform="osx-$architecture"; source_library="$target_root/release/libdf.dylib"; library_name="libdf.dylib" ;;
    linux*) platform="linux-$architecture"; source_library="$target_root/release/libdf.so"; library_name="libdf.so" ;;
    mingw*|msys*|cygwin*)
        [[ "$architecture" == "aarch64" ]] && architecture="arm64"
        platform="win-$architecture"; source_library="$target_root/release/df.dll"; library_name="df.dll"
        ;;
    *) echo "Sistema desktop non supportato: $kernel" >&2; exit 1 ;;
esac

output_directory="$build_root/native/$platform"
mkdir -p "$output_directory"
cp "$source_library" "$output_directory/$library_name"
echo "$output_directory/$library_name"
