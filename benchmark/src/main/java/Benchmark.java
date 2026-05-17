import gg.norisk.webp.EncodePreset;
import gg.norisk.webp.WebP;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;

/**
 * Micro-benchmark for webp-natives. Measures decode + encode latency + throughput
 * across three realistic image sizes:
 *   - 256x256    typical Minecraft cosmetic texture
 *   - 1280x720   HD screenshot
 *   - 3840x2160  4K screenshot
 *
 * For each scenario: 5 warmup iterations + 20 measured iterations.
 * Reports median (robust against GC spikes) + min + max.
 *
 * Output is markdown-table-friendly so we can paste before/after diffs.
 */
public class Benchmark {

    private static final int WARMUP = 10;
    private static final int ITERS  = 40;

    public static void main(String[] args) throws Exception {
        if (!WebP.loadNativeLibrary()) {
            System.err.println("FATAL: native library failed to load");
            System.exit(1);
        }
        System.out.println("webp-natives benchmark");
        System.out.println("JVM:  " + System.getProperty("java.version") + " " + System.getProperty("java.vm.name"));
        System.out.println("OS:   " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println("Heap: max=" + (Runtime.getRuntime().maxMemory() >> 20) + "M");
        System.out.println();

        int[][] sizes = {
            { 256, 256 },
            { 1280, 720 },
            { 3840, 2160 }
        };

        System.out.println("| Size | Pixels | Op | Median (ms) | Min (ms) | Max (ms) | MP/s |");
        System.out.println("|---|---|---|---|---|---|---|");

        for (int[] s : sizes) {
            int w = s[0], h = s[1];
            BufferedImage img = makePhotoLike(w, h);

            // Pre-encode once so we have bytes to feed the decode benchmark
            byte[] webpLossless = WebP.encodeLossless(img);
            byte[] webpLossy    = WebP.encode(img, 0.85f);
            byte[] png          = encodeImageIO(img, "png");
            byte[] jpg          = encodeImageIO(img, "jpg");

            // Sanity check: WEBP lossless roundtrip should preserve pixels
            BufferedImage rt = WebP.decode(webpLossless);
            int mm = pixelMismatches(img, rt);
            if (mm != 0) {
                System.err.println("BUG: lossless roundtrip produced " + mm + " pixel mismatches on " + w + "x" + h);
                System.exit(1);
            }

            run(w, h, "WEBP decode lossless", () -> WebP.decode(webpLossless));
            run(w, h, "WEBP decode lossy",    () -> WebP.decode(webpLossy));
            run(w, h, "PNG  decode (ImageIO)", () -> decodeImageIO(png));
            run(w, h, "JPG  decode (ImageIO)", () -> decodeImageIO(jpg));

            run(w, h, "WEBP encode lossy",    () -> WebP.encode(img, 0.85f));
            run(w, h, "WEBP encode lossless", () -> WebP.encodeLossless(img));
            run(w, h, "PNG  encode (ImageIO)", () -> encodeImageIO(img, "png"));
            run(w, h, "JPG  encode (ImageIO)", () -> encodeImageIO(img, "jpg"));
        }

        System.out.println();
        System.out.println("Encoded size reference:");
        System.out.println("| Size | WEBP lossless | WEBP lossy q=0.85 | PNG | JPG (default q) |");
        System.out.println("|---|---|---|---|---|");
        for (int[] s : sizes) {
            BufferedImage img = makePhotoLike(s[0], s[1]);
            byte[] webpL  = WebP.encodeLossless(img);
            byte[] webpQ  = WebP.encode(img, 0.85f);
            byte[] png    = encodeImageIO(img, "png");
            byte[] jpg    = encodeImageIO(img, "jpg");
            System.out.printf("| %dx%d | %.1f KB | %.1f KB | %.1f KB | %.1f KB |%n",
                s[0], s[1],
                webpL.length / 1024.0,
                webpQ.length / 1024.0,
                png.length   / 1024.0,
                jpg.length   / 1024.0);
        }

        // ─────────────────────────────────────────────────────────────────
        //  Preset comparison: speed vs. size tradeoff per preset.
        //  BEST preset is intentionally slow (~10× BALANCED) — cap iters
        //  for it so the bench doesn't take 20+ minutes on 4K.
        // ─────────────────────────────────────────────────────────────────
        EncodePreset[] presets = { EncodePreset.FAST, EncodePreset.BALANCED, EncodePreset.BEST };

        System.out.println();
        System.out.println("Preset comparison (encode lossless, HD 1280x720):");
        System.out.println("| Preset | Median (ms) | MP/s | Output (KB) | vs PNG size | vs BALANCED time | vs BALANCED size |");
        System.out.println("|---|---|---|---|---|---|---|");
        {
            int w = 1280, h = 720;
            BufferedImage img = makePhotoLike(w, h);
            double pngKb      = encodeImageIO(img, "png").length / 1024.0;
            double balKb      = WebP.encodeLossless(img, EncodePreset.BALANCED).length / 1024.0;
            long   balNanos   = medianTime(WARMUP, ITERS, () -> WebP.encodeLossless(img, EncodePreset.BALANCED));
            for (EncodePreset p : presets) {
                // BEST gets fewer iters to keep total runtime reasonable.
                int iters = (p == EncodePreset.BEST) ? 8 : ITERS;
                int warmup = (p == EncodePreset.BEST) ? 2 : WARMUP;
                long t = medianTime(warmup, iters, () -> WebP.encodeLossless(img, p));
                byte[] out = WebP.encodeLossless(img, p);
                double ms = t / 1e6;
                double mp = (w * h) / (t / 1e9) / 1e6;
                double sizeKb = out.length / 1024.0;
                System.out.printf("| %s | %.2f | %.1f | %.1f | %.0f%% | %.2f× | %.2f× |%n",
                    p.name(), ms, mp, sizeKb,
                    sizeKb / pngKb * 100,
                    (double) t / balNanos,
                    sizeKb / balKb);
            }
        }
    }

    /** Median wallclock of {@code op} over warmup+iters runs, in nanoseconds. */
    private static long medianTime(int warmup, int iters, Op op) throws Exception {
        for (int i = 0; i < warmup; i++) op.run();
        long[] t = new long[iters];
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            op.run();
            t[i] = System.nanoTime() - t0;
        }
        Arrays.sort(t);
        return t[iters / 2];
    }

    /** Java built-in ImageIO encode — pure Java, no native acceleration. */
    private static byte[] encodeImageIO(BufferedImage img, String fmt) throws Exception {
        // JPEG doesn't support alpha — flatten ARGB to RGB. Done outside the
        // measurement loop by re-using the same input image for png/jpg.
        BufferedImage src = img;
        if (fmt.equals("jpg") || fmt.equals("jpeg")) {
            if (src.getType() != BufferedImage.TYPE_INT_RGB) {
                BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
                rgb.createGraphics().drawImage(src, 0, 0, null);
                src = rgb;
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(src, fmt, out)) {
            throw new RuntimeException("ImageIO has no writer for format: " + fmt);
        }
        return out.toByteArray();
    }

    /** Java built-in ImageIO decode. */
    private static BufferedImage decodeImageIO(byte[] data) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
        if (img == null) throw new RuntimeException("ImageIO returned null for input");
        return img;
    }

    interface Op {
        void run() throws Exception;
    }

    private static void run(int w, int h, String name, Op op) throws Exception {
        // Warmup — JIT compilation, native lib paging
        for (int i = 0; i < WARMUP; i++) op.run();

        long[] times = new long[ITERS];
        for (int i = 0; i < ITERS; i++) {
            long t0 = System.nanoTime();
            op.run();
            times[i] = System.nanoTime() - t0;
        }

        Arrays.sort(times);
        long median = times[ITERS / 2];
        long min = times[0];
        long max = times[ITERS - 1];

        double medianMs = median / 1e6;
        double minMs = min / 1e6;
        double maxMs = max / 1e6;
        double megapixelsPerSec = (w * h) / (median / 1e9) / 1e6;

        System.out.printf("| %dx%d | %.2f MP | %s | %.2f | %.2f | %.2f | %.1f |%n",
            w, h, (w * h) / 1e6, name, medianMs, minMs, maxMs, megapixelsPerSec);
    }

    /**
     * Generates a photo-like image: low-frequency gradient + medium-frequency sinusoidal
     * pattern + high-frequency noise. Compresses similarly to a real screenshot —
     * roughly 80-90% of pure-noise size for lossless WEBP.
     */
    private static BufferedImage makePhotoLike(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Random rng = new Random(0xC0FFEE);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // base gradient
                double bx = (double) x / w;
                double by = (double) y / h;
                // sinusoidal pattern (medium frequency)
                double sine = 0.5 + 0.5 * Math.sin(bx * 8) * Math.cos(by * 6);
                // noise
                double noise = (rng.nextInt(64) - 32) / 255.0;

                int r = clamp((int) ((bx * 0.4 + sine * 0.4 + noise) * 255));
                int g = clamp((int) ((by * 0.4 + sine * 0.3 + noise) * 255));
                int b = clamp((int) (((1 - bx) * 0.3 + sine * 0.3 + noise) * 255));
                int a = 255;
                img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static int pixelMismatches(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return -1;
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) n++;
            }
        }
        return n;
    }
}
