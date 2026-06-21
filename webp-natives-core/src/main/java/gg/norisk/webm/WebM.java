package gg.norisk.webm;

import gg.norisk.webp.internal.NativeLoader;
import gg.norisk.webm.internal.WebMNative;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Locale;

public final class WebM {

    public static final int CPU_USED_FAST = 8;
    public static final int CPU_USED_BALANCED = 4;
    public static final int CPU_USED_QUALITY = 0;

    private WebM() {}

    public static boolean loadNativeLibrary() {
        return NativeLoader.tryLoad();
    }

    public static boolean isSupported() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") && NativeLoader.tryLoad();
    }

    public static byte[] encode(BufferedImage[] frames, int[] durationsMs,
                                int cpuUsed, int bitrateKbps) throws WebMException {
        if (!isSupported()) {
            throw new WebMException("WebM VP9 encode is not supported on this platform");
        }
        if (frames == null || frames.length == 0) {
            throw new WebMException("frames are null or empty");
        }
        if (durationsMs == null || durationsMs.length != frames.length) {
            throw new WebMException("durationsMs length must match frames");
        }

        int width = frames[0].getWidth();
        int height = frames[0].getHeight();
        int[][] argb = new int[frames.length][];
        for (int i = 0; i < frames.length; i++) {
            BufferedImage img = frames[i];
            if (img.getWidth() != width || img.getHeight() != height) {
                throw new WebMException("all frames must share the same dimensions");
            }
            if (img.getType() != BufferedImage.TYPE_INT_ARGB) {
                throw new WebMException("frames must be TYPE_INT_ARGB (got type " + img.getType() + ")");
            }
            argb[i] = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        }

        byte[] out = WebMNative.encodeVp9(width, height, argb, durationsMs, cpuUsed, bitrateKbps);
        if (out == null || out.length == 0) {
            throw new WebMException("WebM VP9 encode failed");
        }
        return out;
    }

    public static byte[] encodeFast(BufferedImage[] frames, int[] durationsMs) throws WebMException {
        int kbps = Math.max(500, frames[0].getWidth() * frames[0].getHeight() / 500);
        return encode(frames, durationsMs, CPU_USED_FAST, kbps);
    }

    public static WebMDecoder decode(byte[] data) throws WebMException {
        return WebMDecoder.open(data);
    }
}
