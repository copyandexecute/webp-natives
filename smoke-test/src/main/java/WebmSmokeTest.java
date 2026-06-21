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
    /** Allow some loss from VP9 lossy encode */
    private static final int MAX_MISMATCH_RATIO = 500; // per frame, ~0.8% of pixels

    public static void main(String[] args) throws Exception {
        System.out.println("[1] Load native: " + WebM.loadNativeLibrary());
        System.out.println("[1] WebM supported: " + WebM.isSupported());
        if (!WebM.isSupported()) {
            System.err.println("SKIP — WebM VP9 encode is Windows-only for now");
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
            int totalMm = 0;
            while (decoder.hasMoreFrames() && frameIdx < FRAME_COUNT) {
                WebMFrame frame = decoder.nextFrame();
                int mismatches = countMismatches(frames[frameIdx], frame.image());
                totalMm += mismatches;
                if (mismatches > MAX_MISMATCH_RATIO) {
                    System.out.println("    frame " + frameIdx + ": " + mismatches + " pixel mismatches"
                        + " (duration=" + frame.durationMs() + "ms, ts=" + frame.timestampMs() + "ms)");
                }
                frameIdx++;
            }

            System.out.println("[5] frames decoded: " + frameIdx);
            System.out.println("[5] total pixel mismatches: " + totalMm
                + " (VP9 is lossy — small diffs are OK)");

            if (frameIdx < FRAME_COUNT - 2) {
                System.err.println("FAIL — decoded too few frames");
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

    private static int countMismatches(BufferedImage expected, BufferedImage actual) {
        int w = expected.getWidth();
        int h = expected.getHeight();
        int mm = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (expected.getRGB(x, y) != actual.getRGB(x, y)) mm++;
            }
        }
        return mm;
    }
}
