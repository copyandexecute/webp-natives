package gg.norisk.webm.internal;

import gg.norisk.webp.internal.NativeLoader;
import gg.norisk.webp.WebPException;

public final class WebMNative {

    static {
        try {
            NativeLoader.load();
        } catch (WebPException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private WebMNative() {}

    public static native byte[] encodeVp9(int width, int height,
                                          int[][] frameArrays, int[] durationsMs,
                                          int cpuUsed, int bitrateKbps);

    public static native long decodeOpen(byte[] data);

    public static native int[] decodeGetInfo(long handle);

    public static native boolean decodeHasMoreFrames(long handle);

    public static native int[] decodeNextFrame(long handle);

    public static native void decodeClose(long handle);
}
