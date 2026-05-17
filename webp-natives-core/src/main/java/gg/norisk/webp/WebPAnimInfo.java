package gg.norisk.webp;

/**
 * Metadata for an animated WEBP, returned by {@link WebPAnimDecoder#info()}.
 *
 * <p>For a static WEBP, the info reports {@code frameCount == 1} and
 * {@code loopCount == 0} — there's no real "loop" semantics, but iteration
 * still works (yields the one frame and stops).
 */
public final class WebPAnimInfo {

    private final int canvasWidth;
    private final int canvasHeight;
    /** 0 means "loop forever"; otherwise loop this many times. Static WEBPs report 0. */
    private final int loopCount;
    /** Canvas background colour, packed as 0xAARRGGBB. */
    private final int backgroundColor;
    private final int frameCount;

    public WebPAnimInfo(int canvasWidth, int canvasHeight, int loopCount, int backgroundColor, int frameCount) {
        this.canvasWidth = canvasWidth;
        this.canvasHeight = canvasHeight;
        this.loopCount = loopCount;
        this.backgroundColor = backgroundColor;
        this.frameCount = frameCount;
    }

    public int canvasWidth()     { return canvasWidth; }
    public int canvasHeight()    { return canvasHeight; }
    public int loopCount()       { return loopCount; }
    public int backgroundColor() { return backgroundColor; }
    public int frameCount()      { return frameCount; }

    @Override
    public String toString() {
        return "WebPAnimInfo(" + canvasWidth + "x" + canvasHeight
            + ", frames=" + frameCount + ", loop=" + loopCount + ")";
    }
}
