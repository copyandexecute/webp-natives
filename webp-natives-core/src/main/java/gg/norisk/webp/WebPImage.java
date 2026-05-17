package gg.norisk.webp;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Decoded WEBP wrapped around a <em>direct</em> {@link ByteBuffer} — off-heap
 * memory that can be passed straight to OpenGL/Vulkan/LWJGL without an extra
 * Java→native copy.
 *
 * <p>Returned by {@link WebP#decodeToBuffer(byte[])}. The pixel data is laid
 * out as BGRA bytes in memory (matches {@link BufferedImage#TYPE_INT_ARGB}
 * on little-endian; for OpenGL upload, use {@code GL_BGRA} as the format
 * argument).
 *
 * <p>The underlying buffer is owned by the JVM's NIO subsystem — the JDK's
 * own cleaner releases the off-heap memory when this object is garbage
 * collected. You don't have to free it manually, but you <em>can</em> nudge
 * the GC if you're allocating many large buffers in a tight loop.
 *
 * <p>If you need a {@link BufferedImage} for Java2D drawing or
 * {@link javax.imageio.ImageIO} writing, call {@link #toBufferedImage()} —
 * but at that point you're paying for the heap roundtrip we just saved,
 * so prefer {@link WebP#decode(byte[])} directly in that case.
 */
public final class WebPImage {

    private final ByteBuffer pixels;
    private final int width;
    private final int height;

    /** Package-private; instances come from {@link WebP#decodeToBuffer}. */
    WebPImage(ByteBuffer pixels, int width, int height) {
        this.pixels = pixels;
        this.width  = width;
        this.height = height;
    }

    /**
     * Direct ByteBuffer holding {@code width * height * 4} bytes in BGRA
     * order. Position is 0, limit is {@code width*height*4}, ready to feed
     * into {@code glTexImage2D}.
     */
    public ByteBuffer pixels() {
        return pixels;
    }

    public int width()  { return width; }
    public int height() { return height; }

    /** Bytes per row — always {@code width * 4} since we decode tightly packed. */
    public int stride() { return width * 4; }

    /**
     * Copy this image into a fresh {@link BufferedImage#TYPE_INT_ARGB}.
     *
     * <p>Convenient when you have an existing {@code BufferedImage}-based
     * pipeline; on little-endian the BGRA byte layout in our direct buffer
     * is bit-identical to a Java int read in ARGB order, so this is one
     * {@code IntBuffer.get(int[])} bulk copy.
     */
    public BufferedImage toBufferedImage() {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] argb = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        IntBuffer view = pixels.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        view.get(argb);
        return img;
    }

    @Override
    public String toString() {
        return "WebPImage(" + width + "x" + height + ", " + pixels.capacity() + " bytes direct)";
    }
}
