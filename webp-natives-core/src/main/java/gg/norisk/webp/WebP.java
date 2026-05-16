package gg.norisk.webp;

import gg.norisk.webp.internal.NativeLoader;
import gg.norisk.webp.internal.WebPNative;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;

/**
 * Public WEBP encode/decode facade. Backed by libwebp via JNI.
 *
 * <p>The native library is loaded lazily on first call. Use
 * {@link #loadNativeLibrary()} to force-load eagerly (returns
 * {@code true}/{@code false} instead of throwing).
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

    /** Cheap magic-bytes check: does {@code data} start with a valid WEBP RIFF header? */
    public static boolean isWebP(byte[] data) {
        if (data == null || data.length < 12) return false;
        return WebPNative.isWebP(data);
    }

    /** @return {@code {width, height}} for the given WEBP bytes, or {@code null} if invalid. */
    public static int[] getInfo(byte[] data) throws WebPException {
        requireBytes(data);
        return WebPNative.getInfo(data);
    }

    /** Decode WEBP bytes to a {@link BufferedImage} (TYPE_INT_ARGB). */
    public static BufferedImage decode(byte[] data) throws WebPException {
        requireBytes(data);
        int[] dims = new int[2];
        byte[] rgba = WebPNative.decodeRGBA(data, dims);
        if (rgba == null) {
            throw new WebPException("Failed to decode WEBP (invalid stream or unsupported features)");
        }
        return rgbaToImage(rgba, dims[0], dims[1]);
    }

    /** Encode {@code image} as lossy WEBP. Quality is in {@code [0..1]}. */
    public static byte[] encode(BufferedImage image, float quality) throws WebPException {
        return encode(image, quality, /* lossless = */ false);
    }

    /** Encode {@code image} as WEBP. Quality in {@code [0..1]}; if {@code lossless}, quality is ignored. */
    public static byte[] encode(BufferedImage image, float quality, boolean lossless) throws WebPException {
        requireImage(image);
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] rgba = imageToRgba(image);
        float q = clamp(quality, 0f, 1f) * 100f;
        byte[] out = WebPNative.encodeRGBA(rgba, w, h, q, lossless);
        if (out == null) {
            throw new WebPException("Failed to encode WEBP (libwebp returned zero bytes)");
        }
        return out;
    }

    /** Encode {@code image} as lossless WEBP. */
    public static byte[] encodeLossless(BufferedImage image) throws WebPException {
        return encode(image, 1f, /* lossless = */ true);
    }

    // ─────────────────────────────────────────────────────────────────
    //  Pixel format conversion
    // ─────────────────────────────────────────────────────────────────

    /** RGBA8 row-major buffer → ARGB BufferedImage. */
    private static BufferedImage rgbaToImage(byte[] rgba, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[w * h];
        for (int i = 0; i < argb.length; i++) {
            int r = rgba[i * 4]     & 0xFF;
            int g = rgba[i * 4 + 1] & 0xFF;
            int b = rgba[i * 4 + 2] & 0xFF;
            int a = rgba[i * 4 + 3] & 0xFF;
            argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        img.setRGB(0, 0, w, h, argb, 0, w);
        return img;
    }

    /** Any BufferedImage → RGBA8 row-major buffer. */
    private static byte[] imageToRgba(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] out = new byte[w * h * 4];

        int type = image.getType();
        if (type == BufferedImage.TYPE_INT_ARGB || type == BufferedImage.TYPE_INT_ARGB_PRE) {
            int[] argb = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < argb.length; i++) {
                int p = argb[i];
                out[i * 4]     = (byte) ((p >> 16) & 0xFF);
                out[i * 4 + 1] = (byte) ((p >> 8)  & 0xFF);
                out[i * 4 + 2] = (byte) ( p        & 0xFF);
                out[i * 4 + 3] = (byte) ((p >> 24) & 0xFF);
            }
            return out;
        }
        if (type == BufferedImage.TYPE_INT_RGB || type == BufferedImage.TYPE_INT_BGR) {
            int[] argb = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            boolean bgr = (type == BufferedImage.TYPE_INT_BGR);
            for (int i = 0; i < argb.length; i++) {
                int p = argb[i];
                int r = bgr ? (p & 0xFF)        : ((p >> 16) & 0xFF);
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
}
