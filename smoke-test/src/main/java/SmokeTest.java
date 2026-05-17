import gg.norisk.webp.WebP;
import gg.norisk.webp.WebPException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

public class SmokeTest {
    public static void main(String[] args) throws Exception {
        System.out.println("[1] Load native lib: " + WebP.loadNativeLibrary());
        System.out.println("[1] isAvailable():   " + WebP.isAvailable());

        // Make a 64x64 gradient image
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int r = (x * 4) & 0xFF;
                int gr = (y * 4) & 0xFF;
                int b = 128;
                int a = 255;
                img.setRGB(x, y, (a << 24) | (r << 16) | (gr << 8) | b);
            }
        }
        g.dispose();

        // Encode lossless
        byte[] lossless = WebP.encodeLossless(img);
        System.out.println("[2] lossless encode: " + lossless.length + " bytes");
        System.out.println("[2] isWebP check:    " + WebP.isWebP(lossless));
        int[] info = WebP.getInfo(lossless);
        System.out.println("[2] getInfo:         " + (info == null ? "null" : info[0] + "x" + info[1]));

        // Encode lossy
        byte[] lossy = WebP.encode(img, 0.8f);
        System.out.println("[3] lossy q=0.8:     " + lossy.length + " bytes");

        // Decode back
        BufferedImage decoded = WebP.decode(lossless);
        System.out.println("[4] decoded image:   " + decoded.getWidth() + "x" + decoded.getHeight());

        // Verify pixel equality (lossless should be exact)
        int mismatches = 0;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                if (img.getRGB(x, y) != decoded.getRGB(x, y)) mismatches++;
            }
        }
        System.out.println("[5] pixel mismatches (lossless roundtrip): " + mismatches + " / 4096");

        if (mismatches != 0) {
            System.err.println("FAIL — lossless roundtrip produced different pixels");
            System.exit(1);
        }

        // Roundtrip on a non-trivial image: random noise to stress the encoder
        BufferedImage noise = new BufferedImage(128, 96, BufferedImage.TYPE_INT_ARGB);
        java.util.Random rng = new java.util.Random(42);
        for (int y = 0; y < 96; y++) {
            for (int x = 0; x < 128; x++) {
                noise.setRGB(x, y, rng.nextInt() | 0xFF000000);
            }
        }
        byte[] noiseEnc = WebP.encodeLossless(noise);
        BufferedImage noiseDec = WebP.decode(noiseEnc);
        int noiseMismatches = 0;
        for (int y = 0; y < 96; y++) {
            for (int x = 0; x < 128; x++) {
                if (noise.getRGB(x, y) != noiseDec.getRGB(x, y)) noiseMismatches++;
            }
        }
        System.out.println("[6] noise roundtrip mismatches: " + noiseMismatches + " / " + (128 * 96));
        if (noiseMismatches != 0) {
            System.err.println("FAIL — noise lossless roundtrip mismatched");
            System.exit(1);
        }

        // Verify the preset API: built-in + custom from constants + custom from raw ints
        byte[] viaFast       = gg.norisk.webp.WebP.encodeLossless(img, gg.norisk.webp.EncodePreset.FAST);
        byte[] viaNamed      = gg.norisk.webp.WebP.encodeLossless(img,
            gg.norisk.webp.EncodePreset.of(
                gg.norisk.webp.EncodePreset.METHOD_BALANCED,
                gg.norisk.webp.EncodePreset.LOSSLESS_DEFAULT));
        byte[] viaCustom     = gg.norisk.webp.WebP.encodeLossless(img, gg.norisk.webp.EncodePreset.of(3, 50f));
        BufferedImage rtFast   = gg.norisk.webp.WebP.decode(viaFast);
        BufferedImage rtNamed  = gg.norisk.webp.WebP.decode(viaNamed);
        BufferedImage rtCustom = gg.norisk.webp.WebP.decode(viaCustom);
        int fastMm   = 0;
        int namedMm  = 0;
        int customMm = 0;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                if (img.getRGB(x, y) != rtFast.getRGB(x, y))   fastMm++;
                if (img.getRGB(x, y) != rtNamed.getRGB(x, y))  namedMm++;
                if (img.getRGB(x, y) != rtCustom.getRGB(x, y)) customMm++;
            }
        }
        System.out.println("[7] FAST preset lossless roundtrip mismatches:                      " + fastMm   + " / 4096");
        System.out.println("[7] of(METHOD_BALANCED, LOSSLESS_DEFAULT) roundtrip mismatches:    " + namedMm  + " / 4096");
        System.out.println("[7] custom(3, 50f) lossless roundtrip mismatches:                  " + customMm + " / 4096");
        if (fastMm != 0 || namedMm != 0 || customMm != 0) {
            System.err.println("FAIL — preset roundtrip mismatched");
            System.exit(1);
        }

        // Verify validation: out-of-range preset values must throw
        try {
            new gg.norisk.webp.EncodePreset(7, 50f);
            System.err.println("FAIL — EncodePreset(method=7) should have thrown");
            System.exit(1);
        } catch (IllegalArgumentException ok) {
            System.out.println("[8] validation: rejected method=7 as expected");
        }
        try {
            new gg.norisk.webp.EncodePreset(3, 101f);
            System.err.println("FAIL — EncodePreset(losslessQuality=101) should have thrown");
            System.exit(1);
        } catch (IllegalArgumentException ok) {
            System.out.println("[8] validation: rejected losslessQuality=101 as expected");
        }

        // Animated decoder smoke-check: open static WEBP as a 1-frame anim,
        // verify info + iterator yields exactly one frame with matching pixels.
        try (gg.norisk.webp.WebPAnimDecoder anim = gg.norisk.webp.WebP.decodeAnimated(lossless)) {
            gg.norisk.webp.WebPAnimInfo animInfo = anim.info();
            System.out.println("[9] anim info: " + animInfo);
            if (animInfo.canvasWidth() != 64 || animInfo.canvasHeight() != 64) {
                System.err.println("FAIL — anim dims " + animInfo.canvasWidth() + "x" + animInfo.canvasHeight() + " ≠ 64x64");
                System.exit(1);
            }
            if (animInfo.frameCount() != 1) {
                System.err.println("FAIL — static WEBP should report frameCount=1, got " + animInfo.frameCount());
                System.exit(1);
            }

            int frameSeen = 0;
            int animMm = 0;
            for (gg.norisk.webp.WebPAnimFrame frame : anim) {
                frameSeen++;
                BufferedImage f = frame.image();
                for (int y = 0; y < 64; y++) {
                    for (int x = 0; x < 64; x++) {
                        if (img.getRGB(x, y) != f.getRGB(x, y)) animMm++;
                    }
                }
            }
            System.out.println("[9] anim frames iterated:      " + frameSeen);
            System.out.println("[9] anim pixel mismatches:     " + animMm + " / 4096");
            if (frameSeen != 1 || animMm != 0) {
                System.err.println("FAIL — anim decode didn't roundtrip");
                System.exit(1);
            }

            // reset() should let us read the same frame again
            anim.reset();
            if (!anim.hasMoreFrames()) {
                System.err.println("FAIL — reset() should restore frame availability");
                System.exit(1);
            }
            System.out.println("[9] reset() then hasMoreFrames(): true");
        }

        // Direct ByteBuffer decode: round-trip pixels via off-heap memory
        gg.norisk.webp.WebPImage direct = gg.norisk.webp.WebP.decodeToBuffer(lossless);
        if (direct.width() != 64 || direct.height() != 64) {
            System.err.println("FAIL — decodeToBuffer dims " + direct.width() + "x" + direct.height());
            System.exit(1);
        }
        if (!direct.pixels().isDirect()) {
            System.err.println("FAIL — decodeToBuffer didn't return a direct buffer");
            System.exit(1);
        }
        BufferedImage fromDirect = direct.toBufferedImage();
        int directMm = 0;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                if (img.getRGB(x, y) != fromDirect.getRGB(x, y)) directMm++;
            }
        }
        System.out.println("[10] decodeToBuffer:                " + direct);
        System.out.println("[10] is direct buffer:              " + direct.pixels().isDirect());
        System.out.println("[10] toBufferedImage() mismatches:  " + directMm + " / 4096");
        if (directMm != 0) {
            System.err.println("FAIL — direct buffer roundtrip mismatched");
            System.exit(1);
        }

        // File + Stream API round-trip
        java.io.File tmp = java.io.File.createTempFile("webp-natives-smoke", ".webp");
        tmp.deleteOnExit();
        gg.norisk.webp.WebP.encodeLossless(img, tmp);
        BufferedImage fromFile = gg.norisk.webp.WebP.decode(tmp);
        int fileMm = 0;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                if (img.getRGB(x, y) != fromFile.getRGB(x, y)) fileMm++;
            }
        }
        System.out.println("[11] file round-trip ("
            + tmp.length() + " bytes) mismatches: " + fileMm + " / 4096");
        if (fileMm != 0) {
            System.err.println("FAIL — file roundtrip mismatched");
            System.exit(1);
        }

        // Stream round-trip
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        gg.norisk.webp.WebP.encodeLossless(img, baos);
        BufferedImage fromStream = gg.norisk.webp.WebP.decode(
            new java.io.ByteArrayInputStream(baos.toByteArray()));
        int streamMm = 0;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                if (img.getRGB(x, y) != fromStream.getRGB(x, y)) streamMm++;
            }
        }
        System.out.println("[11] stream round-trip ("
            + baos.size() + " bytes) mismatches: " + streamMm + " / 4096");
        if (streamMm != 0) {
            System.err.println("FAIL — stream roundtrip mismatched");
            System.exit(1);
        }

        System.out.println("\nALL CHECKS PASSED.");
    }
}
