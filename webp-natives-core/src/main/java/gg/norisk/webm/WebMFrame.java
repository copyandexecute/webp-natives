package gg.norisk.webm;

import java.awt.image.BufferedImage;

public final class WebMFrame {

    private final BufferedImage image;
    private final int durationMs;
    private final int timestampMs;

    public WebMFrame(BufferedImage image, int durationMs, int timestampMs) {
        this.image = image;
        this.durationMs = durationMs;
        this.timestampMs = timestampMs;
    }

    public BufferedImage image()       { return image; }
    public int durationMs()            { return durationMs; }
    public int timestampMs()           { return timestampMs; }
}
