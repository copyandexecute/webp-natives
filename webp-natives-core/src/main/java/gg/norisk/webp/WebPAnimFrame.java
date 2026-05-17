package gg.norisk.webp;

import java.awt.image.BufferedImage;

/**
 * A single frame from an animated WEBP, returned by
 * {@link WebPAnimDecoder#nextFrame()}.
 *
 * <p>{@link #durationMs()} is the time this frame should be displayed
 * before the next one. {@link #endTimestampMs()} is the absolute time
 * (from the start of the animation) at which this frame ends — useful
 * if you want to sync playback against an external clock.
 */
public final class WebPAnimFrame {

    private final BufferedImage image;
    private final int durationMs;
    private final int endTimestampMs;

    public WebPAnimFrame(BufferedImage image, int durationMs, int endTimestampMs) {
        this.image = image;
        this.durationMs = durationMs;
        this.endTimestampMs = endTimestampMs;
    }

    /** Decoded frame as {@link BufferedImage#TYPE_INT_ARGB}. */
    public BufferedImage image()       { return image; }

    /** How long this frame should be displayed before the next one. */
    public int durationMs()            { return durationMs; }

    /** Absolute end-timestamp of this frame, in ms from animation start. */
    public int endTimestampMs()        { return endTimestampMs; }
}
