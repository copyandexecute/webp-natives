package gg.norisk.webm;

import gg.norisk.webm.internal.WebMNative;

import java.nio.ByteBuffer;

/**
 * Streaming VP9/WebM encoder. Feed frames one at a time as they are captured —
 * encode cost is paid per {@link #addFrame} call on the caller's thread instead
 * of one giant blocking batch, and only one frame is buffered natively.
 *
 * <pre>
 * try (WebMEncoder enc = WebMEncoder.create(1280, 720, WebMEncoder.Options.realtime())) {
 *     for (...) enc.addFrame(argbPixels, 50);
 *     byte[] webm = enc.finish();
 * }
 * </pre>
 *
 * Not thread-safe; feed frames from a single thread (or synchronize externally).
 */
public final class WebMEncoder implements AutoCloseable {

    /** Rate-control modes. */
    public static final int RC_CBR = 0;
    public static final int RC_VBR = 1;
    /** Constrained quality: constant visual quality up to the bitrate ceiling. */
    public static final int RC_CQ = 2;

    /** Encoder tuning. Obtain via {@link #realtime()} / {@link #quality()} and adjust. */
    public static final class Options {
        public int cpuUsed = WebM.CPU_USED_FAST;
        public int bitrateKbps = 4000;
        /** 0 = auto (hardware cores, capped at 8). */
        public int threads = 0;
        public int keyframeInterval = 240;
        public int rcMode = RC_CQ;
        /** 0..63, lower = better; only used with {@link #RC_CQ}. */
        public int cqLevel = 32;
        public int minQuantizer = -1;
        public int maxQuantizer = -1;

        /** Fastest settings: realtime capture alongside a running game. */
        public static Options realtime() {
            return new Options();
        }

        /** Slower, higher-fidelity settings for offline re-encodes. */
        public static Options quality() {
            Options o = new Options();
            o.cpuUsed = WebM.CPU_USED_BALANCED;
            o.cqLevel = 24;
            return o;
        }

        /** Bitrate target/ceiling for a WxH clip, scaled by a quality factor (1.0 = default). */
        public static int suggestBitrateKbps(int width, int height, float qualityFactor) {
            int base = Math.max(500, width * height / 250);
            return (int) (base * qualityFactor);
        }
    }

    private final int width;
    private final int height;
    private long handle;

    private WebMEncoder(long handle, int width, int height) {
        this.handle = handle;
        this.width = width;
        this.height = height;
    }

    public static WebMEncoder create(int width, int height, Options options) throws WebMException {
        if (!WebM.isSupported()) {
            throw new WebMException("WebM native library is not available for this OS/arch");
        }
        if (width <= 0 || height <= 0) {
            throw new WebMException("invalid dimensions " + width + "x" + height);
        }
        Options o = options != null ? options : Options.realtime();
        int bitrate = o.bitrateKbps > 0 ? o.bitrateKbps : Options.suggestBitrateKbps(width, height, 1f);
        long h = WebMNative.encoderOpen(width, height, o.cpuUsed, bitrate, o.threads,
            o.keyframeInterval, o.rcMode, o.cqLevel, o.minQuantizer, o.maxQuantizer);
        if (h == 0L) {
            throw new WebMException("Failed to open VP9 encoder (" + width + "x" + height + ")");
        }
        return new WebMEncoder(h, width, height);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** @param argb width*height pixels, TYPE_INT_ARGB layout. */
    public void addFrame(int[] argb, int durationMs) throws WebMException {
        if (handle == 0L) throw new WebMException("encoder is closed");
        if (argb == null || argb.length < width * height) {
            throw new WebMException("frame buffer too small");
        }
        if (!WebMNative.encoderAddFrame(handle, argb, durationMs)) {
            throw new WebMException("VP9 encode failed for frame at " + durationMs + "ms");
        }
    }

    /**
     * Zero-copy path for GL readbacks: {@code rgba} must be a DIRECT buffer of
     * width*height*4 bytes in R,G,B,A order (GL_RGBA / UNSIGNED_BYTE, top-down rows).
     */
    public void addFrameRgba(ByteBuffer rgba, int durationMs) throws WebMException {
        if (handle == 0L) throw new WebMException("encoder is closed");
        if (rgba == null || !rgba.isDirect() || rgba.capacity() < width * height * 4) {
            throw new WebMException("rgba must be a direct buffer of at least width*height*4 bytes");
        }
        if (!WebMNative.encoderAddFrameRgba(handle, rgba, durationMs)) {
            throw new WebMException("VP9 encode failed for frame at " + durationMs + "ms");
        }
    }

    /** Flush + finalize the container. The encoder is unusable afterwards. */
    public byte[] finish() throws WebMException {
        if (handle == 0L) throw new WebMException("encoder is closed");
        byte[] out;
        try {
            out = WebMNative.encoderFinish(handle);
        } finally {
            close();
        }
        if (out == null || out.length == 0) {
            throw new WebMException("WebM finalize failed");
        }
        return out;
    }

    @Override
    public void close() {
        if (handle != 0L) {
            WebMNative.encoderClose(handle);
            handle = 0L;
        }
    }
}
