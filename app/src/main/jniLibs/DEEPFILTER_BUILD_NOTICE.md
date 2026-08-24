# DeepFilterNet Android arm64 build

`arm64-v8a/libdf.so` is built from the official DeepFilterNet repository at commit
`d375b2d8309e0935d165700c91da9de862a99c31` using Rust 1.97.1 and Android NDK
28.2.13676358 for `aarch64-linux-android` (API 31).

The official `libDF/Cargo.toml` feature declaration was changed locally from:

```toml
capi = ["tract", "default-model", "dep:ndarray", "logging"]
```

to:

```toml
capi = ["tract", "dep:ndarray", "logging"]
```

This does not change inference code. It avoids embedding the 7.6 MB default model inside the
library because the same official model is supplied separately as an Android asset. The build is
equivalent to:

```sh
cargo build --release --target aarch64-linux-android \
  -p deep_filter --no-default-features --features capi
```

The Android NDK clang linker and archiver must be configured for the Rust target. Exported C API
symbols used by Melina are `df_create`, `df_get_frame_length`, `df_process_frame`,
`df_set_atten_lim`, `df_set_post_filter_beta`, and `df_free`.

SHA-256 of the distributed library:
`5adc94ef81e551be444676ee962f56e219db7ba70812078584d3937e447624a8`.

Upstream: https://github.com/Rikorose/DeepFilterNet  
License: MIT OR Apache-2.0.
