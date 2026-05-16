# webp-natives

WEBP encode/decode for NoRisk modules. Java 8-compatible facade backed by
libwebp JNI natives for **win/linux/mac × x64 + aarch64**.

Internally delegates to [`dev.matrixlab.webp4j:webp4j-core`](https://github.com/MrNanko/webp4j) —
the facade exists so consumers (e.g. `mcreal`) depend on a stable NoRisk-owned
artifact and we can swap the implementation later without touching call sites.

## Coordinate

```kotlin
gg.norisk.webp:webp-natives:1.0.0
```

## Usage

```java
import gg.norisk.webp.WebP;
import java.awt.image.BufferedImage;

// optional eager native load (e.g. at mod init)
WebP.loadNativeLibrary();

BufferedImage img = WebP.decode(bytes);
byte[] lossy    = WebP.encode(img, 0.9f);
byte[] lossless = WebP.encodeLossless(img);
```

## Building

```bash
./gradlew publishToMavenLocal
```

Publishes `gg.norisk.webp:webp-natives:1.0.0` to `~/.m2/repository/`.
Clientside modules pick it up via the existing `mavenLocal()` entry in
`nrcRepositories()`.
