import gg.norisk.webm.WebM;
import gg.norisk.webm.WebMDecoder;
import gg.norisk.webm.WebMEncoder;
import gg.norisk.webm.WebMFrame;
import gg.norisk.webm.WebMInfo;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.nio.file.Files;

public class WebmSmokeTest {

    private static final int FPS = 20;
    private static final int FRAME_MS = 1_000 / FPS;

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

        fidelityScenario();   // small clip, strict pixel-fidelity gate
        realisticScenario();  // 720p clip, throughput + proves decode stays lazy
        oddDimensionScenario(); // odd w/h — regression guard for the chroma-overflow crash
        streamingScenario();  // WebMEncoder: per-frame feed, int[] + direct-RGBA paths
        scaledDecodeScenario(); // decode-time YUV downscale

        System.out.println("\nWEBM SMOKE TEST PASSED.");
    }

    // ─────────────────────────────────────────────────────────────────
    //  Scenario 1 — correctness: encode→decode a small clip and check the
    //  pixels survive the lossy round-trip within tolerance.
    // ─────────────────────────────────────────────────────────────────
    private static void fidelityScenario() throws Exception {
        final int W = 320, H = 180, N = 100;   // 5s @ 20fps
        System.out.println("\n=== fidelity scenario: " + W + "x" + H + ", " + N + " frames ===");

        BufferedImage[] frames = new BufferedImage[N];
        int[] durationsMs = new int[N];
        for (int i = 0; i < N; i++) {
            frames[i] = makeFrame(W, H, i, N);
            durationsMs[i] = FRAME_MS;
        }

        byte[] webm = WebM.encodeFast(frames, durationsMs);
        System.out.println("[fidelity] encoded " + webm.length + " bytes");
        writeFile("webm-smoke-test.webm", webm);

        try (WebMDecoder decoder = WebM.decode(webm)) {
            WebMInfo info = decoder.info();
            System.out.println("[fidelity] decoded info: " + info);
            if (info.frameCount() < N - 2) fail("expected ~" + N + " frames, got " + info.frameCount());
            if (info.width() != W || info.height() != H) fail("unexpected video size");

            int frameIdx = 0;
            long totalBadPixels = 0, totalAbsError = 0, totalChannels = 0;
            while (decoder.hasMoreFrames() && frameIdx < N) {
                WebMFrame frame = decoder.nextFrame();
                long[] d = diff(frames[frameIdx], frame.image());
                totalBadPixels += d[0];
                totalAbsError += d[1];
                totalChannels += (long) W * H * 3;
                frameIdx++;
            }

            double badRatio = (double) totalBadPixels / ((double) totalChannels / 3.0);
            double meanAbsError = (double) totalAbsError / (double) totalChannels;
            System.out.printf("[fidelity] bad-pixel ratio=%.4f (max %.2f), mean abs err=%.3f/ch (max %.1f)%n",
                badRatio, MAX_BAD_PIXEL_RATIO, meanAbsError, MAX_MEAN_ABS_ERROR);

            if (frameIdx < N - 2) fail("decoded too few frames");
            if (badRatio > MAX_BAD_PIXEL_RATIO) fail("too many off-tolerance pixels: " + badRatio);
            if (meanAbsError > MAX_MEAN_ABS_ERROR) fail("mean per-channel error too high: " + meanAbsError);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Scenario 2 — realistic resolution + proof that decode is lazy.
    //
    //  720p is the order of magnitude McReal actually captures at. We
    //  measure encode/decode throughput, and assert decode stays streaming:
    //  with a lazy decoder open() only parses headers + the first frame, so
    //  the bulk of the time is in the per-frame loop. A regression to eager
    //  decode (decode-all-at-open) would invert that — open() would dominate
    //  and the loop would be near-instant — which this assertion catches.
    //  We also bound the Java-side heap growth during the loop.
    // ─────────────────────────────────────────────────────────────────
    private static void realisticScenario() throws Exception {
        final int W = 1280, H = 720, N = 100;  // 5s @ 20fps at real capture resolution
        System.out.println("\n=== realistic scenario: " + W + "x" + H + ", " + N + " frames ===");

        BufferedImage[] frames = new BufferedImage[N];
        int[] durationsMs = new int[N];
        for (int i = 0; i < N; i++) {
            frames[i] = makeFrame(W, H, i, N);
            durationsMs[i] = FRAME_MS;
        }

        long t0 = System.nanoTime();
        byte[] webm = WebM.encodeFast(frames, durationsMs);
        long encodeMs = (System.nanoTime() - t0) / 1_000_000L;
        System.out.printf("[realistic] encoded %d bytes in %d ms (%.1f fps)%n",
            webm.length, encodeMs, encodeMs == 0 ? 0.0 : N * 1000.0 / encodeMs);
        writeFile("webm-smoke-test-720p.webm", webm);

        // Free the encode inputs so the heap measurement below reflects the
        // decoder alone, and so a full clip's worth of decoded frames being
        // retained would be unmistakable.
        frames = null;
        System.gc();
        long heapBaseline = usedHeap();

        long tOpen0 = System.nanoTime();
        try (WebMDecoder decoder = WebM.decode(webm)) {
            long openNs = System.nanoTime() - tOpen0;

            WebMInfo info = decoder.info();
            System.out.println("[realistic] decoded info: " + info);
            if (info.frameCount() < N - 2) fail("expected ~" + N + " frames, got " + info.frameCount());
            if (info.width() != W || info.height() != H) fail("unexpected video size");

            int frameIdx = 0;
            long peakHeapDelta = 0, totalBad = 0, totalAbsErr = 0, spotChannels = 0;
            long tLoop0 = System.nanoTime();
            while (decoder.hasMoreFrames() && frameIdx < N) {
                WebMFrame frame = decoder.nextFrame();
                // Spot-check fidelity on a few frames without retaining the
                // whole clip: makeFrame is deterministic, so regenerate.
                if (frameIdx % 25 == 0) {
                    long[] d = diff(makeFrame(W, H, frameIdx, N), frame.image());
                    totalBad += d[0];
                    totalAbsErr += d[1];
                    spotChannels += (long) W * H * 3;
                }
                peakHeapDelta = Math.max(peakHeapDelta, usedHeap() - heapBaseline);
                frameIdx++;
            }
            long loopNs = System.nanoTime() - tLoop0;

            double decodeMs = loopNs / 1_000_000.0;
            System.out.printf("[realistic] open=%.1f ms, decode-loop=%.1f ms (%.1f fps), peak heap Δ=%.1f MB%n",
                openNs / 1_000_000.0, decodeMs, decodeMs == 0 ? 0.0 : frameIdx * 1000.0 / decodeMs,
                peakHeapDelta / (1024.0 * 1024.0));

            if (frameIdx < N - 2) fail("decoded too few frames");

            double badRatio = spotChannels == 0 ? 0 : (double) totalBad / ((double) spotChannels / 3.0);
            double meanErr = spotChannels == 0 ? 0 : (double) totalAbsErr / (double) spotChannels;
            System.out.printf("[realistic] spot fidelity: bad-pixel ratio=%.4f, mean abs err=%.3f/ch%n", badRatio, meanErr);
            if (badRatio > MAX_BAD_PIXEL_RATIO) fail("720p: too many off-tolerance pixels: " + badRatio);
            if (meanErr > MAX_MEAN_ABS_ERROR) fail("720p: mean per-channel error too high: " + meanErr);

            // Laziness gate: the per-frame loop must dominate over open().
            // Eager decode (decode-all-at-open) would push the work into
            // open() and make the loop near-instant, failing this. This is the
            // robust cross-platform signal — the old eager decoder buffered
            // frames in *native* memory, which a Java-heap check can't see, so
            // peakHeapDelta below is only a diagnostic (it also counts not-yet
            // collected garbage, so it isn't a reliable hard bound).
            if (loopNs <= openNs) {
                fail("decode does not look lazy — open() (" + (openNs / 1_000_000L)
                    + "ms) was not cheaper than the per-frame loop (" + (loopNs / 1_000_000L) + "ms). "
                    + "Did the decoder regress to decoding every frame at open()?");
            }
            // peakHeapDelta already printed above (diagnostic only).
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Scenario 3 — odd dimensions. 4:2:0 chroma planes are
    //  ceil(w/2) x ceil(h/2); a tight hand-packed I420 buffer used to size
    //  them with floor and overflow on odd width/height, corrupting the heap
    //  (STATUS_HEAP_CORRUPTION 0xC0000374) during encode. McReal captures
    //  arbitrary window sizes, so odd dimensions are real input. This must
    //  encode + decode cleanly.
    // ─────────────────────────────────────────────────────────────────
    private static void oddDimensionScenario() throws Exception {
        final int W = 641, H = 361, N = 12;
        System.out.println("\n=== odd-dimension scenario: " + W + "x" + H + ", " + N + " frames ===");

        BufferedImage[] frames = new BufferedImage[N];
        int[] durationsMs = new int[N];
        for (int i = 0; i < N; i++) {
            frames[i] = makeFrame(W, H, i, N);
            durationsMs[i] = FRAME_MS;
        }

        // Pre-fix this call overran the chroma planes and crashed the JVM.
        byte[] webm = WebM.encodeFast(frames, durationsMs);
        System.out.println("[odd] encoded " + webm.length + " bytes (no crash)");

        try (WebMDecoder decoder = WebM.decode(webm)) {
            WebMInfo info = decoder.info();
            System.out.println("[odd] decoded info: " + info);
            if (info.width() != W || info.height() != H) fail("odd-dim: unexpected size " + info);
            int n = 0;
            while (decoder.hasMoreFrames() && n < N) {
                decoder.nextFrame();
                n++;
            }
            if (n < N - 2) fail("odd-dim: decoded too few frames (" + n + ")");
        }
        System.out.println("[odd] roundtrip ok");
    }

    // ─────────────────────────────────────────────────────────────────
    //  Scenario 4 — streaming encoder. Feeds frames one at a time through
    //  WebMEncoder (the capture-while-encoding path), via both the int[]
    //  ARGB and the direct-ByteBuffer RGBA entry points, and checks the
    //  results decode with the same fidelity as the batch API.
    // ─────────────────────────────────────────────────────────────────
    private static void streamingScenario() throws Exception {
        final int W = 1280, H = 720, N = 80;  // 4s @ 20fps — the McReal capture shape
        System.out.println("\n=== streaming scenario: " + W + "x" + H + ", " + N + " frames ===");

        // int[] ARGB path.
        long maxAddNs = 0, totalAddNs = 0;
        byte[] webm;
        try (WebMEncoder enc = WebMEncoder.create(W, H, WebMEncoder.Options.realtime())) {
            for (int i = 0; i < N; i++) {
                int[] argb = ((DataBufferInt) makeFrame(W, H, i, N).getRaster().getDataBuffer()).getData();
                long t0 = System.nanoTime();
                enc.addFrame(argb, FRAME_MS);
                long dt = System.nanoTime() - t0;
                maxAddNs = Math.max(maxAddNs, dt);
                totalAddNs += dt;
            }
            webm = enc.finish();
        }
        System.out.printf("[streaming] argb: %d bytes, addFrame avg=%.2f ms, max=%.2f ms%n",
            webm.length, totalAddNs / (N * 1_000_000.0), maxAddNs / 1_000_000.0);
        writeFile("webm-smoke-test-streaming.webm", webm);
        assertStreamFidelity(webm, W, H, N, "argb");

        // Direct-ByteBuffer RGBA path (the GL-readback shape: R,G,B,A bytes).
        java.nio.ByteBuffer rgba = java.nio.ByteBuffer.allocateDirect(W * H * 4);
        try (WebMEncoder enc = WebMEncoder.create(W, H, WebMEncoder.Options.realtime())) {
            for (int i = 0; i < N; i++) {
                int[] argb = ((DataBufferInt) makeFrame(W, H, i, N).getRaster().getDataBuffer()).getData();
                rgba.clear();
                for (int p = 0; p < W * H; p++) {
                    int v = argb[p];
                    rgba.put((byte) (v >> 16)).put((byte) (v >> 8)).put((byte) v).put((byte) (v >> 24));
                }
                enc.addFrameRgba(rgba, FRAME_MS);
            }
            webm = enc.finish();
        }
        System.out.println("[streaming] rgba: " + webm.length + " bytes");
        assertStreamFidelity(webm, W, H, N, "rgba");
    }

    private static void assertStreamFidelity(byte[] webm, int w, int h, int n, String tag) throws Exception {
        try (WebMDecoder decoder = WebM.decode(webm)) {
            WebMInfo info = decoder.info();
            if (info.frameCount() < n - 2) fail(tag + ": expected ~" + n + " frames, got " + info.frameCount());
            if (info.width() != w || info.height() != h) fail(tag + ": unexpected video size " + info);
            int frameIdx = 0;
            long totalBad = 0, totalAbsErr = 0, spotChannels = 0;
            while (decoder.hasMoreFrames() && frameIdx < n) {
                WebMFrame frame = decoder.nextFrame();
                if (frameIdx % 20 == 0) {
                    long[] d = diff(makeFrame(w, h, frameIdx, n), frame.image());
                    totalBad += d[0];
                    totalAbsErr += d[1];
                    spotChannels += (long) w * h * 3;
                }
                frameIdx++;
            }
            if (frameIdx < n - 2) fail(tag + ": decoded too few frames (" + frameIdx + ")");
            double badRatio = (double) totalBad / ((double) spotChannels / 3.0);
            double meanErr = (double) totalAbsErr / (double) spotChannels;
            System.out.printf("[streaming] %s fidelity: bad-pixel ratio=%.4f, mean abs err=%.3f/ch%n",
                tag, badRatio, meanErr);
            if (badRatio > MAX_BAD_PIXEL_RATIO) fail(tag + ": too many off-tolerance pixels: " + badRatio);
            if (meanErr > MAX_MEAN_ABS_ERROR) fail(tag + ": mean per-channel error too high: " + meanErr);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Scenario 5 — decode-time downscale. A 1280x720 clip opened with a
    //  640x360 bound must report and deliver scaled frames, and the scaled
    //  pixels must still resemble the source (checked against a reference
    //  frame sampled at the scaled grid, with a loose tolerance to allow
    //  for the bilinear YUV scale).
    // ─────────────────────────────────────────────────────────────────
    private static void scaledDecodeScenario() throws Exception {
        final int W = 1280, H = 720, N = 24, TW = 640, TH = 360;
        System.out.println("\n=== scaled-decode scenario: " + W + "x" + H + " -> max " + TW + "x" + TH + " ===");

        BufferedImage[] frames = new BufferedImage[N];
        int[] durationsMs = new int[N];
        for (int i = 0; i < N; i++) {
            frames[i] = makeFrame(W, H, i, N);
            durationsMs[i] = FRAME_MS;
        }
        byte[] webm = WebM.encodeFast(frames, durationsMs);

        try (WebMDecoder decoder = WebM.decode(webm, TW, TH)) {
            WebMInfo info = decoder.info();
            System.out.println("[scaled] decoded info: " + info);
            if (info.width() != TW || info.height() != TH) fail("scaled: expected " + TW + "x" + TH + ", got " + info);

            int n = 0;
            long absErr = 0, samples = 0;
            while (decoder.hasMoreFrames() && n < N) {
                WebMFrame frame = decoder.nextFrame();
                BufferedImage img = frame.image();
                if (img.getWidth() != TW || img.getHeight() != TH) fail("scaled: frame size " + img.getWidth() + "x" + img.getHeight());
                if (n % 8 == 0) {
                    BufferedImage ref = makeFrame(W, H, n, N);
                    for (int y = 4; y < TH - 4; y += 24) {
                        for (int x = 4; x < TW - 4; x += 24) {
                            int a = img.getRGB(x, y);
                            int e = ref.getRGB(x * 2, y * 2);
                            absErr += Math.abs(((e >> 16) & 0xFF) - ((a >> 16) & 0xFF))
                                + Math.abs(((e >> 8) & 0xFF) - ((a >> 8) & 0xFF))
                                + Math.abs((e & 0xFF) - (a & 0xFF));
                            samples += 3;
                        }
                    }
                }
                n++;
            }
            if (n < N - 2) fail("scaled: decoded too few frames (" + n + ")");
            double meanErr = (double) absErr / samples;
            System.out.printf("[scaled] frames=%d, spot mean abs err=%.2f/ch%n", n, meanErr);
            if (meanErr > 26.0) fail("scaled: pixels drifted too far from source: " + meanErr);
        }
    }

    private static BufferedImage makeFrame(int w, int h, int index, int total) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        float phase = index / (float) total;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = (int) (128 + 127 * Math.sin(phase * Math.PI * 2 + x * 0.05));
                int g = (int) (128 + 127 * Math.sin(phase * Math.PI * 2 + y * 0.05 + 1));
                int b = (int) (128 + 127 * Math.cos(phase * Math.PI * 2 + (x + y) * 0.03));
                pixels[y * w + x] = 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            }
        }
        return img;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** Returns {badPixels, summedPerChannelAbsError}. */
    private static long[] diff(BufferedImage expected, BufferedImage actual) {
        int w = expected.getWidth(), h = expected.getHeight();
        long absError = 0, badPixels = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int e = expected.getRGB(x, y);
                int a = actual.getRGB(x, y);
                int dr = Math.abs(((e >> 16) & 0xFF) - ((a >> 16) & 0xFF));
                int dg = Math.abs(((e >> 8) & 0xFF) - ((a >> 8) & 0xFF));
                int db = Math.abs((e & 0xFF) - (a & 0xFF));
                absError += dr + dg + db;
                if (dr > PIXEL_TOLERANCE || dg > PIXEL_TOLERANCE || db > PIXEL_TOLERANCE) badPixels++;
            }
        }
        return new long[] { badPixels, absError };
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static void writeFile(String name, byte[] data) throws Exception {
        File outDir = new File("build");
        outDir.mkdirs();
        File outFile = new File(outDir, name);
        Files.write(outFile.toPath(), data);
        System.out.println("    wrote " + outFile.getAbsolutePath() + " (open in Chrome/Firefox to watch)");
    }

    private static void fail(String msg) {
        System.err.println("FAIL — " + msg);
        System.exit(1);
    }
}
