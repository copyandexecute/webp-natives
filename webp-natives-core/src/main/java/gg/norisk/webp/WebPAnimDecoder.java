package gg.norisk.webp;

import gg.norisk.webp.internal.WebPNative;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Frame-by-frame decoder for animated WEBP files.
 *
 * <p>Implements {@link AutoCloseable} (call {@link #close()} or use
 * try-with-resources to free the underlying libwebp decoder) and
 * {@link Iterable}{@code <}{@link WebPAnimFrame}{@code >} for foreach
 * iteration:
 *
 * <pre>{@code
 * try (WebPAnimDecoder dec = WebP.decodeAnimated(webpBytes)) {
 *     WebPAnimInfo info = dec.info();
 *     for (WebPAnimFrame frame : dec) {
 *         render(frame.image(), frame.durationMs());
 *     }
 * }
 * }</pre>
 *
 * <p>Static (non-animated) WEBPs are also accepted — they decode as a
 * 1-frame animation. Call {@link #reset()} to restart from frame 0 for
 * looped playback.
 *
 * <p>Not thread-safe; one decoder instance per thread.
 */
public final class WebPAnimDecoder implements AutoCloseable, Iterable<WebPAnimFrame> {

    private long handle;
    private final WebPAnimInfo info;
    private int lastEndTimestampMs = 0;

    private WebPAnimDecoder(long handle, WebPAnimInfo info) {
        this.handle = handle;
        this.info = info;
    }

    /** Open an animated WEBP from bytes. Throws if the stream is not a valid WEBP. */
    public static WebPAnimDecoder open(byte[] data) throws WebPException {
        if (data == null || data.length == 0) {
            throw new WebPException("input bytes are null or empty");
        }
        long h = WebPNative.animDecoderOpen(data);
        if (h == 0L) {
            throw new WebPException("Failed to open animated WEBP (invalid stream or unsupported features)");
        }
        int[] raw = WebPNative.animDecoderGetInfo(h);
        if (raw == null || raw.length < 5) {
            WebPNative.animDecoderClose(h);
            throw new WebPException("Failed to read animated WEBP info");
        }
        WebPAnimInfo info = new WebPAnimInfo(raw[0], raw[1], raw[2], raw[3], raw[4]);
        return new WebPAnimDecoder(h, info);
    }

    /** Canvas dimensions, loop count, frame count, background colour. */
    public WebPAnimInfo info() {
        return info;
    }

    /** @return {@code true} if there are more frames left to read. */
    public boolean hasMoreFrames() {
        if (handle == 0L) return false;
        return WebPNative.animDecoderHasMoreFrames(handle);
    }

    /**
     * Decode and return the next frame.
     *
     * @throws WebPException if the decoder has been closed or the next
     *         frame can't be decoded
     * @throws NoSuchElementException if no more frames are available
     */
    public WebPAnimFrame nextFrame() throws WebPException {
        if (handle == 0L) throw new WebPException("decoder is closed");
        if (!WebPNative.animDecoderHasMoreFrames(handle)) {
            throw new NoSuchElementException("no more frames");
        }

        int w = info.canvasWidth();
        int h = info.canvasHeight();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();

        int endTs = WebPNative.animDecoderNextFrame(handle, pixels);
        if (endTs < 0) throw new WebPException("Failed to decode animated frame");

        int duration = endTs - lastEndTimestampMs;
        lastEndTimestampMs = endTs;
        return new WebPAnimFrame(img, duration, endTs);
    }

    /** Restart iteration from frame 0 (e.g. for looped playback). */
    public void reset() {
        if (handle != 0L) {
            WebPNative.animDecoderReset(handle);
            lastEndTimestampMs = 0;
        }
    }

    @Override
    public void close() {
        if (handle != 0L) {
            WebPNative.animDecoderClose(handle);
            handle = 0L;
        }
    }

    /**
     * Iterator over remaining frames. Stops when {@link #hasMoreFrames()}
     * returns false. Wraps {@link WebPException} from {@link #nextFrame()}
     * as a {@link RuntimeException} since {@code Iterator.next()} has no
     * checked exception in its signature.
     */
    @Override
    public Iterator<WebPAnimFrame> iterator() {
        return new Iterator<WebPAnimFrame>() {
            @Override
            public boolean hasNext() {
                return hasMoreFrames();
            }

            @Override
            public WebPAnimFrame next() {
                try {
                    return nextFrame();
                } catch (WebPException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }
}
