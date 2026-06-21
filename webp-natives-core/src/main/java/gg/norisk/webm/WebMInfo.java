package gg.norisk.webm;

public final class WebMInfo {

    private final int width;
    private final int height;
    private final int frameCount;
    private final int durationMs;

    public WebMInfo(int width, int height, int frameCount, int durationMs) {
        this.width = width;
        this.height = height;
        this.frameCount = frameCount;
        this.durationMs = durationMs;
    }

    public int width()       { return width; }
    public int height()      { return height; }
    public int frameCount()  { return frameCount; }
    public int durationMs()  { return durationMs; }

    @Override
    public String toString() {
        return "WebMInfo(" + width + "x" + height + ", frames=" + frameCount + ", duration=" + durationMs + "ms)";
    }
}
