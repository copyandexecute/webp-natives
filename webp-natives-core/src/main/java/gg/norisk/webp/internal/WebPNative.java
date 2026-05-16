package gg.norisk.webp.internal;

import gg.norisk.webp.WebPException;

/**
 * Raw JNI surface — package-internal. Consumers should use {@link gg.norisk.webp.WebP} instead.
 *
 * <p>Loading the native library is deferred: the static initializer triggers
 * {@link NativeLoader#load()} the first time this class is touched. If the
 * load fails, the {@code ExceptionInInitializerError} is wrapped on every
 * subsequent native-method dispatch so callers see a {@link WebPException}.
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

    /** Magic-bytes check; returns {@code true} if {@code data} starts with a valid WEBP RIFF header. */
    public static native boolean isWebP(byte[] data);

    /**
     * Read WEBP width/height without decoding pixels.
     *
     * @return {@code int[]{width, height}} or {@code null} if {@code data} is not a parseable WEBP
     */
    public static native int[] getInfo(byte[] data);

    /**
     * Decode a WEBP byte stream to a row-major RGBA8 buffer.
     *
     * <p>On success, {@code outDims} is set to {@code {width, height}} and the
     * returned array has length {@code width * height * 4}. Returns {@code null}
     * on failure.
     */
    public static native byte[] decodeRGBA(byte[] data, int[] outDims);

    /**
     * Encode row-major RGBA8 pixels to a WEBP byte stream.
     *
     * @param rgba   row-major RGBA8, length must be {@code width * height * 4}
     * @param width  positive image width
     * @param height positive image height
     * @param quality 0.0 .. 100.0 (ignored if {@code lossless})
     * @param lossless if {@code true}, emit a lossless WEBP
     * @return encoded WEBP bytes, or {@code null} on failure
     */
    public static native byte[] encodeRGBA(byte[] rgba, int width, int height, float quality, boolean lossless);
}
