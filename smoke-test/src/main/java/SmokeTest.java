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

        System.out.println("\nALL CHECKS PASSED.");
    }
}
