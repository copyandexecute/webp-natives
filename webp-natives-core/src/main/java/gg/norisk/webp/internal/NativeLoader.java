package gg.norisk.webp.internal;

import gg.norisk.webp.WebPException;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads the libwebp JNI native for the current OS/arch from the classpath.
 *
 * <p>Native artifacts are packaged inside the platform-specific jars
 * ({@code webp-natives-windows}, {@code webp-natives-linux},
 * {@code webp-natives-macos}) at the path:
 *
 * <pre>/native/&lt;os&gt;/&lt;arch&gt;/&lt;libname&gt;</pre>
 *
 * where {@code os} is one of {@code windows|linux|macos}, {@code arch} is
 * {@code x64|arm64}, and {@code libname} is the platform-default name
 * ({@code webp_natives.dll}, {@code libwebp_natives.so}, {@code libwebp_natives.dylib}).
 *
 * <p>The matching resource is extracted to a temp directory once per JVM
 * and loaded via {@link System#load(String)}.
 */
public final class NativeLoader {

    /** Resource basename — both the {@code System.load} target and JNI {@code lib} prefix logic agree. */
    private static final String LIB_BASENAME = "webp_natives";

    private static final AtomicReference<Throwable> LOAD_FAILURE = new AtomicReference<>();
    private static volatile boolean loaded;

    private NativeLoader() {}

    /**
     * Ensure the native library is loaded. Idempotent and thread-safe.
     *
     * @throws WebPException if no native is available for this OS/arch or loading fails
     */
    public static void load() throws WebPException {
        if (loaded) return;
        synchronized (NativeLoader.class) {
            if (loaded) return;
            Throwable prev = LOAD_FAILURE.get();
            if (prev != null) {
                throw new WebPException("Previous native load attempt failed", prev);
            }
            try {
                doLoad();
                loaded = true;
            } catch (Throwable t) {
                LOAD_FAILURE.set(t);
                throw new WebPException("Failed to load webp-natives JNI library: " + t.getMessage(), t);
            }
        }
    }

    /** @return {@code true} if the native is loaded; never throws. */
    public static boolean tryLoad() {
        try {
            load();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void doLoad() throws IOException {
        String os = detectOs();
        String arch = detectArch();
        String libFileName = libFileName(os);
        String resource = "/native/" + os + "/" + arch + "/" + libFileName;

        InputStream in = NativeLoader.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("No native library bundled for " + os + "/" + arch + " (looked for " + resource + ")");
        }

        Path tempDir = Files.createTempDirectory("webp-natives-");
        tempDir.toFile().deleteOnExit();
        File target = new File(tempDir.toFile(), libFileName);
        target.deleteOnExit();

        try (InputStream resStream = in;
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = resStream.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }

        System.load(target.getAbsolutePath());
    }

    private static String libFileName(String os) {
        switch (os) {
            case "windows": return LIB_BASENAME + ".dll";
            case "linux":   return "lib" + LIB_BASENAME + ".so";
            case "macos":   return "lib" + LIB_BASENAME + ".dylib";
            default: throw new IllegalStateException("Unknown OS: " + os);
        }
    }

    private static String detectOs() {
        String s = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (s.contains("win")) return "windows";
        if (s.contains("mac") || s.contains("darwin")) return "macos";
        if (s.contains("nux") || s.contains("nix")) return "linux";
        throw new IllegalStateException("Unsupported OS: " + s);
    }

    private static String detectArch() {
        String a = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (a.contains("aarch64") || a.contains("arm64")) return "arm64";
        if (a.contains("amd64") || a.contains("x86_64") || a.equals("x64")) return "x64";
        throw new IllegalStateException("Unsupported arch: " + a);
    }
}
