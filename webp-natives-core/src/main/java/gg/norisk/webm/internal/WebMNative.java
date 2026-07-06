package gg.norisk.webm.internal;

import gg.norisk.webp.internal.NativeLoader;
import gg.norisk.webp.WebPException;
import gg.norisk.webm.WebMException;

import java.nio.ByteBuffer;

public final class WebMNative {

    static {
        try {
            NativeLoader.load();
        } catch (WebPException e) {
            // Surface as a WebM-domain failure rather than leaking WebPException.
            throw new ExceptionInInitializerError(
                new WebMException("Failed to load WebM native library", e));
        }
    }

    private WebMNative() {}

    public static native byte[] encodeVp9(int width, int height,
                                          int[][] frameArrays, int[] durationsMs,
                                          int cpuUsed, int bitrateKbps);

    public static native long encoderOpen(int width, int height,
                                          int cpuUsed, int bitrateKbps,
                                          int threads, int kfMaxDist,
                                          int rcMode, int cqLevel,
                                          int minQuantizer, int maxQuantizer);

    public static native boolean encoderAddFrame(long handle, int[] argb, int durationMs);

    public static native boolean encoderAddFrameRgba(long handle, ByteBuffer rgba, int durationMs);

    public static native byte[] encoderFinish(long handle);

    public static native void encoderClose(long handle);

    public static native long decodeOpen(byte[] data, int maxWidth, int maxHeight);

    public static native int[] decodeGetInfo(long handle);

    public static native boolean decodeHasMoreFrames(long handle);

    public static native int[] decodeNextFrame(long handle);

    public static native void decodeClose(long handle);
}
