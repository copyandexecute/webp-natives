package gg.norisk.webp;

/**
 * Encoder speed/size tradeoff for {@link WebP#encode} / {@link WebP#encodeLossless}.
 *
 * <p>Comes in three flavours:
 *
 * <ul>
 *   <li><b>Built-in</b>: {@link #FAST}, {@link #BALANCED}, {@link #BEST}.
 *       Constants — picked for the most common use cases.</li>
 *   <li><b>Custom from named constants</b>:
 *       {@code EncodePreset.of(METHOD_FAST, LOSSLESS_DEFAULT)}.
 *       Use the {@code METHOD_*} / {@code LOSSLESS_*} fields so call sites
 *       stay readable.</li>
 *   <li><b>Custom from raw numbers</b>:
 *       {@code new EncodePreset(3, 50f)}. Drop down to libwebp's native
 *       integers when you want a tuning the named constants don't cover.</li>
 * </ul>
 *
 * <p>Two libwebp knobs are exposed:
 *
 * <table border="1" summary="Preset settings">
 *   <tr><th>field</th><th>range</th><th>meaning</th></tr>
 *   <tr><td>{@link #method()}</td>
 *       <td>0 .. 6 (see {@code METHOD_*} constants)</td>
 *       <td>Encoder search effort. {@link #METHOD_FASTEST} = no search,
 *           {@link #METHOD_SLOWEST} = exhaustive. Affects both lossy and
 *           lossless mode.</td></tr>
 *   <tr><td>{@link #losslessQuality()}</td>
 *       <td>0 .. 100 (see {@code LOSSLESS_*} constants)</td>
 *       <td>In <em>lossless</em> mode only: extra compression effort
 *           ({@link #LOSSLESS_FASTEST} = fastest/largest,
 *           {@link #LOSSLESS_BEST} = slowest/smallest). Ignored when
 *           encoding lossy — there the user-supplied {@code quality}
 *           controls visual quality directly.</td></tr>
 * </table>
 *
 * <p>Built-in presets (typical 4K photo-like image, lossless encode):
 *
 * <table border="1" summary="Built-in preset numbers">
 *   <tr><th></th><th>method</th><th>lossless quality</th><th>Relative speed</th><th>Relative size</th></tr>
 *   <tr><td>{@link #FAST}</td>    <td>{@link #METHOD_FASTEST}</td><td>{@link #LOSSLESS_FASTEST}</td>
 *       <td>~2-3× faster than BALANCED</td><td>~20-30% larger</td></tr>
 *   <tr><td>{@link #BALANCED}</td><td>{@link #METHOD_FAST}</td>   <td>{@link #LOSSLESS_DEFAULT}</td>
 *       <td>baseline</td><td>baseline</td></tr>
 *   <tr><td>{@link #BEST}</td>    <td>{@link #METHOD_SLOWEST}</td><td>{@link #LOSSLESS_BEST}</td>
 *       <td>~4-10× slower than BALANCED</td><td>~3-5% smaller</td></tr>
 * </table>
 */
public final class EncodePreset {

    // ─────────────────────────────────────────────────────────────────
    //  libwebp encoder method constants
    //  Use as the first arg to EncodePreset(int, float) / .of(int, float).
    //  Maps directly to libwebp WebPConfig.method (0..6).
    // ─────────────────────────────────────────────────────────────────

    /** {@code method=0} — no encoder-side search, fastest possible. */
    public static final int METHOD_FASTEST  = 0;
    /** {@code method=1} — minimal search; good speed at slightly larger output. */
    public static final int METHOD_FAST     = 1;
    /** {@code method=3} — balanced; lighter search than libwebp's default. */
    public static final int METHOD_BALANCED = 3;
    /** {@code method=4} — libwebp's own {@code WEBP_PRESET_DEFAULT} method. */
    public static final int METHOD_DEFAULT  = 4;
    /** {@code method=5} — extra search; slower, slightly smaller output. */
    public static final int METHOD_SLOW     = 5;
    /** {@code method=6} — exhaustive search; slowest, smallest output. */
    public static final int METHOD_SLOWEST  = 6;

    // ─────────────────────────────────────────────────────────────────
    //  libwebp lossless-quality constants
    //  Use as the second arg to EncodePreset(int, float) / .of(int, float).
    //  Controls extra compression effort in lossless mode only (ignored lossy).
    // ─────────────────────────────────────────────────────────────────

    /** {@code losslessQuality=0} — no extra compression effort, fastest. */
    public static final float LOSSLESS_FASTEST = 0.0f;
    /** {@code losslessQuality=75} — libwebp's recommended default. */
    public static final float LOSSLESS_DEFAULT = 75.0f;
    /** {@code losslessQuality=100} — maximum compression effort, slowest. */
    public static final float LOSSLESS_BEST    = 100.0f;

    // ─────────────────────────────────────────────────────────────────
    //  Built-in presets
    // ─────────────────────────────────────────────────────────────────

    /** Fastest encode; output is somewhat larger. Pick when latency matters more than bytes. */
    public static final EncodePreset FAST     = new EncodePreset(METHOD_FASTEST, LOSSLESS_FASTEST, "FAST");

    /** Default. Good speed/size tradeoff for general use. */
    public static final EncodePreset BALANCED = new EncodePreset(METHOD_FAST,    LOSSLESS_DEFAULT, "BALANCED");

    /** Smallest output; encode is much slower. Pick for upload / cold storage. */
    public static final EncodePreset BEST     = new EncodePreset(METHOD_SLOWEST, LOSSLESS_BEST,    "BEST");

    private final int    method;
    private final float  losslessQuality;
    private final String name;

    /**
     * Custom preset.
     *
     * @param method libwebp encoder method, {@code 0..6} (0 = fastest, 6 = slowest+smallest)
     * @param losslessQuality libwebp lossless compression effort, {@code 0..100}
     *                        (0 = fastest, 100 = smallest). Ignored when encoding lossy.
     * @throws IllegalArgumentException if either argument is out of range
     */
    public EncodePreset(int method, float losslessQuality) {
        this(method, losslessQuality, null);
    }

    private EncodePreset(int method, float losslessQuality, String name) {
        if (method < 0 || method > 6) {
            throw new IllegalArgumentException("method must be in 0..6, got " + method);
        }
        if (losslessQuality < 0f || losslessQuality > 100f || Float.isNaN(losslessQuality)) {
            throw new IllegalArgumentException("losslessQuality must be in 0..100, got " + losslessQuality);
        }
        this.method = method;
        this.losslessQuality = losslessQuality;
        this.name = (name != null) ? name : "custom(method=" + method + ",lossless=" + losslessQuality + ")";
    }

    /** Factory equivalent of {@link #EncodePreset(int, float)}. */
    public static EncodePreset of(int method, float losslessQuality) {
        return new EncodePreset(method, losslessQuality);
    }

    public int    method()          { return method; }
    public float  losslessQuality() { return losslessQuality; }
    /** Human-readable name; {@code "FAST"} / {@code "BALANCED"} / {@code "BEST"} for built-ins, descriptive for custom. */
    public String name()            { return name; }

    @Override
    public String toString() {
        return name;
    }
}
