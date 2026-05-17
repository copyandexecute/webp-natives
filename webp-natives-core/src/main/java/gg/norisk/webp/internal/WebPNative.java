package gg.norisk.webp.internal;

import gg.norisk.webp.WebPException;

/**
 * Raw JNI surface — package-internal. Consumers should use {@link gg.norisk.webp.WebP} instead.
 *
 * <p>Two parallel encode/decode entry points are exposed:
 *
 * <ul>
 *   <li>{@link #decodeARGBInto(byte[], int[], int[])} / {@link #encodeARGB(int[], int, int, float, boolean)}
 *       — fast path. Caller supplies an {@code int[]} that is read/written as
 *       BGRA bytes in memory (on little-endian, equivalent to {@code TYPE_INT_ARGB}
 *       packed integers). Zero pixel-format conversion on the Java side.</li>
 *   <li>{@link #decodeRGBA(byte[], int[])} / {@link #encodeRGBA(byte[], int, int, float, boolean)}
 *       — fallback. Plain RGBA byte arrays for callers that can't use the
 *       fast path (unusual {@code BufferedImage} types, byte-oriented I/O).</li>
 * </ul>
 */
public final class WebPNative {

    static {
        try {
            NativeLoader.load();
        } catch (WebPException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private WebPNative() {}

    public static native boolean isWebP(byte[] data);
    public static native int[] getInfo(byte[] data);

    /**
     * Fast-path decode: write decoded BGRA bytes directly into {@code outPixels}.
     * {@code outPixels.length} must be at least {@code width*height} (sniffed
     * from the WEBP header before any output is touched).
     *
     * @return 0 on success; negative on error (see source for codes)
     */
    public static native int decodeARGBInto(byte[] data, int[] outPixels, int[] outDims);

    /**
     * Fast-path encode: read source pixels directly from {@code argb} as
     * BGRA-in-memory ints.
     *
     * @param method libwebp encoder method, 0..6
     * @param losslessQuality libwebp lossless compression effort 0..100 (ignored in lossy mode)
     * @return encoded WEBP bytes, or {@code null} on failure
     */
    public static native byte[] encodeARGB(int[] argb, int width, int height, float quality, boolean lossless, int method, float losslessQuality);

    /** Fallback decode to a freshly-allocated row-major RGBA byte buffer. */
    public static native byte[] decodeRGBA(byte[] data, int[] outDims);

    /** Fallback encode from a row-major RGBA byte buffer. */
    public static native byte[] encodeRGBA(byte[] rgba, int width, int height, float quality, boolean lossless, int method, float losslessQuality);

    // ─────────────────────────────────────────────────────────────────
    //  Animated WEBP decode — handle-based lifecycle.
    // ─────────────────────────────────────────────────────────────────

    /**
     * Open an animated WEBP decoder.
     *
     * @param data WEBP bytes (animated or static — static is treated as
     *             a 1-frame animation)
     * @return opaque native handle, or 0 on failure
     */
    public static native long animDecoderOpen(byte[] data);

    /**
     * @return {@code {canvasWidth, canvasHeight, loopCount, bgcolor, frameCount}}
     *         or {@code null} on failure. {@code bgcolor} is RGBA packed as ARGB int.
     */
    public static native int[] animDecoderGetInfo(long handle);

    /** @return {@code true} if there are more frames left to read. */
    public static native boolean animDecoderHasMoreFrames(long handle);

    /**
     * Decode the next frame into {@code outPixels} (TYPE_INT_ARGB backing).
     *
     * @return end-timestamp of the decoded frame in ms, or -1 on failure
     */
    public static native int animDecoderNextFrame(long handle, int[] outPixels);

    /** Restart iteration from frame 0 (e.g. for looped playback). */
    public static native void animDecoderReset(long handle);

    /** Free all native resources. Safe to call multiple times. */
    public static native void animDecoderClose(long handle);
}
