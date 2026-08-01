package gg.norisk.webp;

import gg.norisk.webp.internal.NativeLoader;
import gg.norisk.webp.internal.WebPNative;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;

/**
 * Public WEBP encode/decode facade. Backed by libwebp via JNI.
 *
 * <p>The native library is loaded lazily on first call. Use
 * {@link #loadNativeLibrary()} to force-load eagerly.
 *
 * <p>Fast path: when the source / destination is a {@link BufferedImage} of
 * {@link BufferedImage#TYPE_INT_ARGB}, the JNI bridge reads from / writes to
 * the int[] backing buffer directly — no per-pixel format conversion on the
 * Java side. Other {@code BufferedImage} types fall back to a byte[] RGBA
 * round-trip via {@link #imageToRgba(BufferedImage)}.
 *
 * <p>All methods are thread-safe.
 */
public final class WebP {

    private WebP() {}

    /** Eagerly load the JNI native. Safe to call repeatedly. */
    public static boolean loadNativeLibrary() {
        return NativeLoader.tryLoad();
    }

    /** @return {@code true} if the JNI native is loaded and usable. */
    public static boolean isAvailable() {
        return NativeLoader.tryLoad();
    }

    /**
     * Cheap magic-bytes check: does {@code data} start with a valid WEBP RIFF header?
     *
     * <p>Deliberately pure Java. Callers use this to decide whether to enter the
     * native path at all, so routing it through JNI would make it throw on exactly
     * the hosts where the answer matters most — and a caller that swallows that
     * failure sees every webp reported as "not webp". The native counterpart
     * compares the same eight bytes, but copies the whole array across the JNI
     * boundary to do it.
     */
    public static boolean isWebP(byte[] data) {
        if (data == null || data.length < 12) return false;
        return data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
            && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }

    /** @return {@code {width, height}} for the given WEBP bytes, or {@code null} if invalid. */
    public static int[] getInfo(byte[] data) throws WebPException {
        requireBytes(data);
        return WebPNative.getInfo(data);
    }

    // ─────────────────────────────────────────────────────────────────
    //  Decode
    // ─────────────────────────────────────────────────────────────────

    /** Decode WEBP bytes to a {@link BufferedImage} ({@link BufferedImage#TYPE_INT_ARGB}). */
    public static BufferedImage decode(byte[] data) throws WebPException {
        requireBytes(data);
        int[] info = WebPNative.getInfo(data);
        if (info == null) {
            throw new WebPException("Failed to decode WEBP (invalid stream or unsupported features)");
        }
        int w = info[0];
        int h = info[1];

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        int[] outDims = new int[2];
        int rc = WebPNative.decodeARGBInto(data, pixels, outDims);
        if (rc != 0 || outDims[0] != w || outDims[1] != h) {
            throw new WebPException("WEBP decode failed (code " + rc + ")");
        }
        return img;
    }

    /** Decode WEBP read from {@code file}. */
    public static BufferedImage decode(File file) throws IOException {
        return decode(Files.readAllBytes(file.toPath()));
    }

    /** Decode WEBP read from {@code in} (fully consumed). */
    public static BufferedImage decode(InputStream in) throws IOException {
        return decode(readAllBytes(in));
    }

    // ─────────────────────────────────────────────────────────────────
    //  Direct-ByteBuffer decode (for GPU upload / off-heap pipelines)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Decode WEBP bytes into a {@link WebPImage} whose pixel data lives in a
     * <em>direct</em> {@link ByteBuffer} (off-heap, BGRA byte order). Use this
     * when you want to feed the result straight to {@code glTexImage2D} or
     * another native consumer without paying for a Java-heap roundtrip.
     */
    public static WebPImage decodeToBuffer(byte[] data) throws WebPException {
        requireBytes(data);
        int[] info = WebPNative.getInfo(data);
        if (info == null) {
            throw new WebPException("Failed to decode WEBP (invalid stream or unsupported features)");
        }
        int w = info[0];
        int h = info[1];

        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4);
        int[] outDims = new int[2];
        int rc = WebPNative.decodeBGRAIntoBuffer(data, buf, outDims);
        if (rc != 0 || outDims[0] != w || outDims[1] != h) {
            throw new WebPException("WEBP direct decode failed (code " + rc + ")");
        }
        buf.position(0).limit(w * h * 4);
        return new WebPImage(buf, w, h);
    }

    /** Direct-buffer decode from {@code file}. */
    public static WebPImage decodeToBuffer(File file) throws IOException {
        return decodeToBuffer(Files.readAllBytes(file.toPath()));
    }

    /** Direct-buffer decode from {@code in} (fully consumed). */
    public static WebPImage decodeToBuffer(InputStream in) throws IOException {
        return decodeToBuffer(readAllBytes(in));
    }

    // ─────────────────────────────────────────────────────────────────
    //  Animated decode
    // ─────────────────────────────────────────────────────────────────

    /**
     * Open an animated WEBP for frame-by-frame decoding. Accepts both
     * animated and static streams (static decodes as a 1-frame animation).
     *
     * <p>The returned decoder owns native resources — close it with
     * try-with-resources or {@link WebPAnimDecoder#close()}.
     */
    public static WebPAnimDecoder decodeAnimated(byte[] data) throws WebPException {
        return WebPAnimDecoder.open(data);
    }

    /** Animated decode from {@code file}. */
    public static WebPAnimDecoder decodeAnimated(File file) throws IOException {
        return decodeAnimated(Files.readAllBytes(file.toPath()));
    }

    /** Animated decode from {@code in} (fully consumed). */
    public static WebPAnimDecoder decodeAnimated(InputStream in) throws IOException {
        return decodeAnimated(readAllBytes(in));
    }

    // ─────────────────────────────────────────────────────────────────
    //  Encode
    // ─────────────────────────────────────────────────────────────────

    /** Encode {@code image} as lossy WEBP with {@link EncodePreset#BALANCED}. Quality in {@code [0..1]}. */
    public static byte[] encode(BufferedImage image, float quality) throws WebPException {
        return encode(image, quality, /* lossless = */ false, EncodePreset.BALANCED);
    }

    /** Encode with a custom preset. Quality in {@code [0..1]}; if {@code lossless}, quality is ignored. */
    public static byte[] encode(BufferedImage image, float quality, boolean lossless) throws WebPException {
        return encode(image, quality, lossless, EncodePreset.BALANCED);
    }

    /** Encode with a custom or built-in {@link EncodePreset}. */
    public static byte[] encode(BufferedImage image, float quality, boolean lossless, EncodePreset preset) throws WebPException {
        requireImage(image);
        if (preset == null) preset = EncodePreset.BALANCED;
        int w = image.getWidth();
        int h = image.getHeight();
        float q  = clamp(quality, 0f, 1f) * 100f;
        int   m  = preset.method();
        float lq = preset.losslessQuality();

        // Fast path: TYPE_INT_ARGB → libwebp can read the int[] directly as BGRA.
        if (image.getType() == BufferedImage.TYPE_INT_ARGB) {
            int[] argb = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            byte[] out = WebPNative.encodeARGB(argb, w, h, q, lossless, m, lq);
            if (out == null) throw new WebPException("Failed to encode WEBP (fast path returned null)");
            return out;
        }

        // Fallback: copy into a row-major RGBA byte buffer.
        byte[] rgba = imageToRgba(image);
        byte[] out = WebPNative.encodeRGBA(rgba, w, h, q, lossless, m, lq);
        if (out == null) {
            throw new WebPException("Failed to encode WEBP (fallback path returned null)");
        }
        return out;
    }

    /** Encode {@code image} as lossless WEBP with {@link EncodePreset#BALANCED}. */
    public static byte[] encodeLossless(BufferedImage image) throws WebPException {
        return encode(image, 1f, /* lossless = */ true, EncodePreset.BALANCED);
    }

    /** Encode {@code image} as lossless WEBP with the given preset. */
    public static byte[] encodeLossless(BufferedImage image, EncodePreset preset) throws WebPException {
        return encode(image, 1f, /* lossless = */ true, preset);
    }

    // ─────────────────────────────────────────────────────────────────
    //  File + Stream encode convenience
    // ─────────────────────────────────────────────────────────────────

    /** Encode lossy WEBP and write to {@code out}. */
    public static void encode(BufferedImage image, float quality, OutputStream out) throws IOException {
        out.write(encode(image, quality));
    }

    /** Encode lossy WEBP and write to {@code file}. */
    public static void encode(BufferedImage image, float quality, File file) throws IOException {
        Files.write(file.toPath(), encode(image, quality));
    }

    /** Encode lossless WEBP and write to {@code out}. */
    public static void encodeLossless(BufferedImage image, OutputStream out) throws IOException {
        out.write(encodeLossless(image));
    }

    /** Encode lossless WEBP and write to {@code file}. */
    public static void encodeLossless(BufferedImage image, File file) throws IOException {
        Files.write(file.toPath(), encodeLossless(image));
    }

    // ─────────────────────────────────────────────────────────────────
    //  Pixel format conversion — fallback path only
    // ─────────────────────────────────────────────────────────────────

    /** Any BufferedImage → RGBA8 row-major buffer. Used only for non-INT_ARGB inputs. */
    private static byte[] imageToRgba(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] out = new byte[w * h * 4];

        int type = image.getType();
        if (type == BufferedImage.TYPE_INT_RGB || type == BufferedImage.TYPE_INT_BGR) {
            int[] argb = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            boolean bgr = (type == BufferedImage.TYPE_INT_BGR);
            for (int i = 0; i < argb.length; i++) {
                int p = argb[i];
                int r = bgr ? (p & 0xFF)         : ((p >> 16) & 0xFF);
                int g =        (p >> 8) & 0xFF;
                int b = bgr ? ((p >> 16) & 0xFF) : ( p        & 0xFF);
                out[i * 4]     = (byte) r;
                out[i * 4 + 1] = (byte) g;
                out[i * 4 + 2] = (byte) b;
                out[i * 4 + 3] = (byte) 0xFF;
            }
            return out;
        }
        if (type == BufferedImage.TYPE_4BYTE_ABGR || type == BufferedImage.TYPE_4BYTE_ABGR_PRE) {
            byte[] abgr = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < w * h; i++) {
                out[i * 4]     = abgr[i * 4 + 3]; // R
                out[i * 4 + 1] = abgr[i * 4 + 2]; // G
                out[i * 4 + 2] = abgr[i * 4 + 1]; // B
                out[i * 4 + 3] = abgr[i * 4];     // A
            }
            return out;
        }
        // Slow path: ask the BufferedImage to convert pixel-by-pixel via getRGB().
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            out[i * 4]     = (byte) ((p >> 16) & 0xFF);
            out[i * 4 + 1] = (byte) ((p >> 8)  & 0xFF);
            out[i * 4 + 2] = (byte) ( p        & 0xFF);
            out[i * 4 + 3] = (byte) ((p >> 24) & 0xFF);
        }
        return out;
    }

    private static void requireBytes(byte[] data) throws WebPException {
        if (data == null || data.length == 0) {
            throw new WebPException("input bytes are null or empty");
        }
    }

    private static void requireImage(BufferedImage image) throws WebPException {
        if (image == null) {
            throw new WebPException("image is null");
        }
        if (image.getWidth() <= 0 || image.getHeight() <= 0) {
            throw new WebPException("image has non-positive dimensions: " + image.getWidth() + "x" + image.getHeight());
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Java 8 doesn't have InputStream.readAllBytes — minimal local impl. */
    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }
}
