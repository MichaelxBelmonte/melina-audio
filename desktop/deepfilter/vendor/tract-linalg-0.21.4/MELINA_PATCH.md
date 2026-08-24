# Melina Windows ARM64 patch

This directory contains the official `tract-linalg 0.21.4` crate downloaded from crates.io.

- Archive: `https://static.crates.io/crates/tract-linalg/tract-linalg-0.21.4.crate`
- Archive SHA-256: `b58f074c94c74ea736a75b7ac6f696add05c62fc4745d1c420cf7d4d42eb7b2b`
- Upstream source: `https://github.com/sonos/tract/tree/v0.21.4/linalg`
- License: MIT OR Apache-2.0; upstream license files are preserved in this directory.

Melina changes one condition in `build.rs`: `use_masm()` returns false for `aarch64` targets.
On native Windows ARM64, `scripts/build_deepfilter_desktop.sh` sets the C compiler to LLVM
`clang`, which accepts the generated GNU ARM64 assembly and emits MSVC-compatible COFF objects.
The x64 Windows path continues to use the upstream MSVC/MASM behavior; non-Windows targets are
unchanged.

Do not update this directory without downloading the new crate archive, verifying its checksum,
reviewing the diff against upstream, regenerating `desktop/deepfilter/Cargo.lock`, and running the
complete desktop CI matrix.
