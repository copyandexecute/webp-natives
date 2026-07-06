# webp-natives

WEBP (still images) and VP9/WebM (video) encode/decode for the JVM. JNI
wrappers around Google's [libwebp](https://chromium.googlesource.com/webm/libwebp),
[libvpx](https://chromium.googlesource.com/webm/libvpx) and
[libwebm](https://github.com/webmproject/libwebm), with a small, opinionated
Java API (`gg.norisk.webp` + `gg.norisk.webm`). Bundles native binaries for
win/linux/mac × x64+aarch64.

The VP9/WebM stack builds on every platform: libvpx is pulled in via
[vcpkg](https://vcpkg.io) (it handles NASM and the per-arch build), libwebm
is compiled from a pinned source tag. Set `-Pwebm=false` for a WebP-only build
that needs no vcpkg.

## Modules

```
webp-natives-core      → public API (webp + webm) + classpath native loader  (Java 8)
webp-natives-windows   → JNI + win x64/arm64 DLL, built via CMake
webp-natives-linux     → JNI + linux x64/arm64 .so (CI: ubuntu runners)
webp-natives-macos     → JNI + macos x64/arm64 .dylib, per-arch (CI: macos runners)
webp-natives-all       → aggregator artifact for consumers
```

## Consuming

### From the GitHub Pages Maven repo

```kotlin
repositories {
    maven("https://<owner>.github.io/<repo>/")
}
dependencies {
    implementation("gg.norisk.webp:all:<VERSION>")
}
```

Versions are auto-bumped on every CI run:
- branch push → `YY.Q.BUILD-branch` (e.g. `26.2.5-main`)
- tag push (`v*`) → clean semver (`v1.0.0` → `1.0.0`)

The gh-pages branch is published with `keep_files: true`, so old versions
accumulate; pin a specific version in your build for reproducibility.

### From mavenLocal (local dev)

```bash
./gradlew publishToMavenLocal
```

```kotlin
repositories { mavenLocal() }
dependencies { implementation("gg.norisk.webp:all:<gradle.properties version>") }
```

## Usage

```java
import gg.norisk.webp.WebP;
import gg.norisk.webp.EncodePreset;

// One-time eager load (optional — lazy on first call anyway)
WebP.loadNativeLibrary();

// Decode
BufferedImage img = WebP.decode(webpBytes);

// Encode — defaults (BALANCED, lossy, q=0.85 typical)
byte[] lossy    = WebP.encode(img, 0.85f);
byte[] lossless = WebP.encodeLossless(img);

// Tune speed vs. size: built-in presets
byte[] fast     = WebP.encodeLossless(img, EncodePreset.FAST);
byte[] best     = WebP.encodeLossless(img, EncodePreset.BEST);

// Or custom — from named constants:
EncodePreset custom = EncodePreset.of(
    EncodePreset.METHOD_BALANCED,
    EncodePreset.LOSSLESS_DEFAULT);
byte[] custom1 = WebP.encodeLossless(img, custom);

// Or custom — raw libwebp numbers:
byte[] custom2 = WebP.encodeLossless(img, new EncodePreset(3, 50f));
```

## Performance

Measured on Win10 amd64, JDK 21, photo-like 3840×2160 test image,
vs. Java ImageIO PNG/JPG:

| Op                            | webp-natives | ImageIO    | Δ                    |
|-------------------------------|--------------|------------|----------------------|
| Decode lossless               | 98 ms        | 363 ms (PNG)| **3.7× faster**     |
| Decode lossy                  | 160 ms       | 92 ms (JPG) | JPG 1.7× faster     |
| Encode lossy q=0.85           | 459 ms       | 237 ms (JPG)| JPG 1.9× faster     |
| Encode lossless (BALANCED)    | 6 675 ms     | 1 167 ms (PNG)| PNG 5.7× faster   |
| **Output size lossless**      | **7.6 MB**   | **20.3 MB (PNG)** | **62% smaller** |
| Output size lossy q=0.85      | 2.9 MB       | 2.4 MB (JPG)| JPG 17% smaller     |

**Tradeoff TL;DR**:
- WEBP decode is significantly faster than PNG decode at any size
- WEBP lossless saves ~60% disk vs PNG but `BALANCED` encode is ~5× slower
- `EncodePreset.FAST` is **12× faster than BALANCED** and still produces output
  56% smaller than PNG — for typical screenshot saving this is the right preset
- JPG remains faster for lossy, but doesn't carry alpha — WEBP wins for cosmetics / UI sprites

### Lossless preset comparison (HD 1280×720, photo-like)

| Preset    | Time      | vs BALANCED | Output  | vs PNG output |
|-----------|-----------|-------------|---------|---------------|
| FAST      | **42 ms** | **12× faster** | 1108 KB | **56% smaller** |
| BALANCED  | 512 ms    | baseline    | 947 KB  | 63% smaller   |
| BEST      | 6976 ms   | 14× slower  | 940 KB  | 63% smaller   |
| PNG (ref) | 158 ms    |             | 2540 KB |               |

Key takeaway: **BEST is almost never worth it** — 14× the runtime for a 1% size
improvement over BALANCED. Reach for FAST when latency matters; BALANCED is
the default for general use; pick BEST only when you're encoding once and the
file will be hit many times (CDN, cold storage).

See `benchmark/` for a runnable microbenchmark.

## Building locally

```
./gradlew publishToMavenLocal
./gradlew :smoke-test:run     # WebP correctness check
./gradlew :smoke-test:runWebm # VP9/WebM encode→decode roundtrip + fidelity
./gradlew :benchmark:run      # latency + throughput tables
```

The native library is rebuilt via CMake (libwebp v1.6.0 fetched as a
`FetchContent` dependency) every time the matching platform module's
`processResources` runs. Requires:

- A matching host OS (each platform module's CMake task is `onlyIf isWindows/isLinux/isMac`)
- A C/C++ toolchain — Visual Studio 2022 Build Tools / Xcode / gcc
- CMake 3.20+
- **For WebM (default on):** a vcpkg checkout. The build finds it via
  `$VCPKG_ROOT`, `$VCPKG_INSTALLATION_ROOT` (set on GitHub runners), or a
  `vcpkg/` directory at the repo root. vcpkg fetches libvpx and the NASM
  assembler itself — nothing else to install. Use `-Pwebm=false` to skip it.

## Design notes

- **Pure libwebp wrapping**: we link libwebp statically into our JNI DLL,
  no separate runtime install needed. ~150 lines of C bridge.
- **Java-8 bytecode target**: works on legacy Minecraft (1.7.10/1.8.9)
  JVMs. Build-toolchain JDK 21, output `--release 8`.
- **Zero-copy fast path on little-endian**: libwebp writes
  `MODE_BGRA` bytes which are bit-identical to a Java
  `BufferedImage.TYPE_INT_ARGB` `int[]` on little-endian. We pin the
  `int[]` via `GetPrimitiveArrayCritical` and hand libwebp the pointer,
  so decode is one malloc per call (the `BufferedImage` itself) instead
  of three.
- **Multi-threaded decode/encode**: `WebPDecoderConfig.options.use_threads = 1`
  and `WebPConfig.thread_level = 1` enable libwebp's internal parallelism.

## WebM / VP9 video

```java
import gg.norisk.webm.*;
import java.awt.image.BufferedImage;

// Streaming encode — feed frames as they are captured. Multi-threaded VP9
// (row-mt + tile columns), SIMD colour conversion (libyuv), constrained-
// quality rate control. Only one frame is buffered natively at a time.
try (WebMEncoder enc = WebMEncoder.create(1280, 720, WebMEncoder.Options.realtime())) {
    enc.addFrame(argbPixels, 50);          // int[] TYPE_INT_ARGB layout
    enc.addFrameRgba(directRgbaBuffer, 50); // or zero-copy GL_RGBA readback
    byte[] webm = enc.finish();
}

// Batch encode a sequence of TYPE_INT_ARGB frames to a VP9/WebM byte[].
BufferedImage[] frames = ...;
int[] durationsMs = ...;            // one per frame
byte[] webm = WebM.encodeFast(frames, durationsMs);

// Decode — streaming, one frame at a time (never holds the whole clip).
try (WebMDecoder decoder = WebM.decode(webm)) {
    WebMInfo info = decoder.info();           // width/height/frameCount/durationMs
    while (decoder.hasMoreFrames()) {
        WebMFrame frame = decoder.nextFrame(); // frame.image(), durationMs(), timestampMs()
    }
}
```

`WebM.isSupported()` reports whether the native loaded for this OS/arch.

## License

Apache 2.0. libwebp / libsharpyuv are BSD-3-Clause (Google). libvpx is
BSD-3-Clause (WebM Project). libwebm is BSD-3-Clause (WebM Project).
