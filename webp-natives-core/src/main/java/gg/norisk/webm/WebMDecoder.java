package gg.norisk.webm;

import gg.norisk.webm.internal.WebMNative;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.NoSuchElementException;

public final class WebMDecoder implements AutoCloseable {

    private long handle;
    private final WebMInfo info;

    private WebMDecoder(long handle, WebMInfo info) {
        this.handle = handle;
        this.info = info;
    }

    public static WebMDecoder open(byte[] data) throws WebMException {
        return open(data, 0, 0);
    }

    /**
     * Opens the stream with a decode-time downscale: frames larger than
     * maxWidth x maxHeight are scaled (on the YUV planes, before the ARGB
     * conversion) to fit inside. {@link #info()} and frames report the
     * scaled size. Pass 0/0 for native size.
     */
    public static WebMDecoder open(byte[] data, int maxWidth, int maxHeight) throws WebMException {
        if (data == null || data.length == 0) {
            throw new WebMException("input bytes are null or empty");
        }
        long h = WebMNative.decodeOpen(data, maxWidth, maxHeight);
        if (h == 0L) {
            throw new WebMException("Failed to open WebM (not a valid VP9/WebM stream)");
        }
        int[] raw = WebMNative.decodeGetInfo(h);
        if (raw == null || raw.length < 4) {
            WebMNative.decodeClose(h);
            throw new WebMException("Failed to read WebM info");
        }
        WebMInfo info = new WebMInfo(raw[0], raw[1], raw[2], raw[3]);
        return new WebMDecoder(h, info);
    }

    public WebMInfo info() {
        return info;
    }

    public boolean hasMoreFrames() {
        return handle != 0L && WebMNative.decodeHasMoreFrames(handle);
    }

    public WebMFrame nextFrame() throws WebMException {
        if (handle == 0L) throw new WebMException("decoder is closed");
        if (!WebMNative.decodeHasMoreFrames(handle)) {
            throw new NoSuchElementException("no more frames");
        }

        int[] packed = WebMNative.decodeNextFrame(handle);
        if (packed == null || packed.length < 2) {
            throw new WebMException("Failed to decode WebM frame");
        }

        int metaOffset = packed.length - 2;
        int durationMs = packed[metaOffset];
        int timestampMs = packed[metaOffset + 1];
        int pixelCount = metaOffset;

        int w = info.width();
        int h = info.height();
        if (pixelCount < w * h) {
            throw new WebMException("Decoded frame buffer too small");
        }

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        System.arraycopy(packed, 0, pixels, 0, w * h);
        return new WebMFrame(img, durationMs, timestampMs);
    }

    @Override
    public void close() {
        if (handle != 0L) {
            WebMNative.decodeClose(handle);
            handle = 0L;
        }
    }
}
