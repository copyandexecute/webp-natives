import gg.norisk.webm.WebM;
import gg.norisk.webm.WebMDecoder;
import gg.norisk.webm.WebMFrame;
import gg.norisk.webm.WebMInfo;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.nio.file.Files;

public class WebmSmokeTest {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 180;
    /** McReal target: 5s @ 20fps */
    private static final int DURATION_MS = 5_000;
    private static final int FPS = 20;
    private static final int FRAME_MS = 1_000 / FPS;
    private static final int FRAME_COUNT = DURATION_MS / FRAME_MS;

    // VP9 is lossy and round-trips through I420, so an exact match is impossible.
    // We assert on perceptual closeness instead: a pixel is "bad" only if a
    // channel drifts past PIXEL_TOLERANCE, and we cap both the bad-pixel ratio
    // and the mean per-channel error. Gross corruption (wrong colours, frame
    // shear, decode failure) blows past these; honest lossy noise stays under.
    private static final int PIXEL_TOLERANCE = 24;        // per-channel |Δ| for a pixel to count as bad
    private static final double MAX_BAD_PIXEL_RATIO = 0.10;
    private static final double MAX_MEAN_ABS_ERROR = 12.0; // per channel, across the whole clip

    public static void main(String[] args) throws Exception {
        System.out.println("[1] Load native: " + WebM.loadNativeLibrary());
        System.out.println("[1] WebM supported: " + WebM.isSupported());
        if (!WebM.isSupported()) {
            System.err.println("SKIP — WebM native not available for this OS/arch");
            System.exit(0);
        }

        System.out.println("[1] Clip: " + FRAME_COUNT + " frames @ " + FPS + "fps = "
            + (FRAME_COUNT * FRAME_MS / 1000f) + "s");

        BufferedImage[] frames = new BufferedImage[FRAME_COUNT];
        int[] durationsMs = new int[FRAME_COUNT];
        for (int i = 0; i < FRAME_COUNT; i++) {
            frames[i] = makeFrame(i);
            durationsMs[i] = FRAME_MS;
        }

        long t0 = System.nanoTime();
        byte[] webm = WebM.encodeFast(frames, durationsMs);
        long encodeMs = (System.nanoTime() - t0) / 1_000_000L;

        System.out.println("[2] encoded WebM VP9: " + webm.length + " bytes in " + encodeMs + " ms");

        File outDir = new File("build");
        outDir.mkdirs();
        File outFile = new File(outDir, "webm-smoke-test.webm");
        Files.write(outFile.toPath(), webm);
        System.out.println("[3] wrote preview file: " + outFile.getAbsolutePath());
        System.out.println("    → open in Chrome/Firefox to watch the video");

        // Decoding is streaming: WebMDecoder pulls one frame per nextFrame()
        // call, so this loop never holds the whole clip in memory at once.
        try (WebMDecoder decoder = WebM.decode(webm)) {
            WebMInfo info = decoder.info();
            System.out.println("[4] decoded info: " + info);

            if (info.frameCount() < FRAME_COUNT - 2) {
                System.err.println("FAIL — expected ~" + FRAME_COUNT + " frames, got " + info.frameCount());
                System.exit(1);
            }
            if (info.width() != WIDTH || info.height() != HEIGHT) {
                System.err.println("FAIL — unexpected video size");
                System.exit(1);
            }

            int frameIdx = 0;
            long totalBadPixels = 0;
            long totalAbsError = 0;       // summed per-channel |Δ|
            long totalChannels = 0;       // pixels * 3
            while (decoder.hasMoreFrames() && frameIdx < FRAME_COUNT) {
                WebMFrame frame = decoder.nextFrame();
                FrameDiff d = diff(frames[frameIdx], frame.image());
                totalBadPixels += d.badPixels;
                totalAbsError += d.absError;
                totalChannels += (long) WIDTH * HEIGHT * 3;
                frameIdx++;
            }

            double badRatio = totalChannels == 0 ? 1.0
                : (double) totalBadPixels / ((double) totalChannels / 3.0);
            double meanAbsError = totalChannels == 0 ? Double.MAX_VALUE
                : (double) totalAbsError / (double) totalChannels;

            System.out.println("[5] frames decoded: " + frameIdx);
            System.out.printf("[5] fidelity: bad-pixel ratio=%.4f (max %.2f), mean abs err=%.3f/channel (max %.1f)%n",
                badRatio, MAX_BAD_PIXEL_RATIO, meanAbsError, MAX_MEAN_ABS_ERROR);

            if (frameIdx < FRAME_COUNT - 2) {
                System.err.println("FAIL — decoded too few frames");
                System.exit(1);
            }
            if (badRatio > MAX_BAD_PIXEL_RATIO) {
                System.err.println("FAIL — too many off-tolerance pixels: " + badRatio);
                System.exit(1);
            }
            if (meanAbsError > MAX_MEAN_ABS_ERROR) {
                System.err.println("FAIL — mean per-channel error too high: " + meanAbsError);
                System.exit(1);
            }
        }

        System.out.println("\nWEBM SMOKE TEST PASSED.");
    }

    private static BufferedImage makeFrame(int index) {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        float phase = index / (float) FRAME_COUNT;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int r = (int) (128 + 127 * Math.sin(phase * Math.PI * 2 + x * 0.05));
                int g = (int) (128 + 127 * Math.sin(phase * Math.PI * 2 + y * 0.05 + 1));
                int b = (int) (128 + 127 * Math.cos(phase * Math.PI * 2 + (x + y) * 0.03));
                pixels[y * WIDTH + x] = 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            }
        }
        return img;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** Per-frame difference: summed per-channel abs error + count of off-tolerance pixels. */
    private static final class FrameDiff {
        long absError;
        long badPixels;
    }

    private static FrameDiff diff(BufferedImage expected, BufferedImage actual) {
        int w = expected.getWidth();
        int h = expected.getHeight();
        FrameDiff d = new FrameDiff();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int e = expected.getRGB(x, y);
                int a = actual.getRGB(x, y);
                int dr = Math.abs(((e >> 16) & 0xFF) - ((a >> 16) & 0xFF));
                int dg = Math.abs(((e >> 8) & 0xFF) - ((a >> 8) & 0xFF));
                int db = Math.abs((e & 0xFF) - (a & 0xFF));
                d.absError += dr + dg + db;
                if (dr > PIXEL_TOLERANCE || dg > PIXEL_TOLERANCE || db > PIXEL_TOLERANCE) {
                    d.badPixels++;
                }
            }
        }
        return d;
    }
}
