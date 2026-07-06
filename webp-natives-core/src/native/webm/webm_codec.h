#pragma once

#include <cstddef>
#include <cstdint>
#include <deque>
#include <memory>
#include <vector>

#include <vpx/vpx_decoder.h>
#include <vpx/vpx_encoder.h>
#include <mkvparser/mkvparser.h>

namespace webm_codec {

struct WebMFrame {
    std::vector<uint8_t> argb;
    int width = 0;
    int height = 0;
    int duration_ms = 0;
    int timestamp_ms = 0;
};

struct WebMInfo {
    int width = 0;
    int height = 0;
    int frame_count = 0;
    int duration_ms = 0;
};

/**
 * Encoder tuning. Defaults reproduce the historical batch-encode behaviour
 * (CBR, kf every 30 frames) except that encoding is now multi-threaded.
 */
struct EncoderOptions {
    int cpu_used = 8;          // VP9 -cpu-used: 0 = best quality, 8 = fastest (realtime)
    int bitrate_kbps = 4000;   // target (CBR/VBR) or ceiling (CQ)
    int threads = 0;           // 0 = auto (hw cores, capped at 8)
    int kf_max_dist = 30;      // max frames between keyframes
    int rc_mode = 0;           // 0 = CBR, 1 = VBR, 2 = CQ (constrained quality)
    int cq_level = 32;         // 0..63, only used when rc_mode == 2 (lower = better)
    int min_quantizer = -1;    // -1 = libvpx default
    int max_quantizer = -1;    // -1 = libvpx default
};

/**
 * Streaming VP9/WebM encoder. Frames are fed one at a time — encode cost is
 * paid per add_frame() call instead of one giant blocking batch, and only a
 * single I420 scratch image is alive at any moment.
 *
 * Lifetime: create() → add_frame()* → finish() (flushes + finalizes the
 * container, invalidates the encoder) → destroy. Not thread-safe
 * (single-producer, like a Java OutputStream).
 */
class Encoder {
public:
    static Encoder* create(int width, int height, const EncoderOptions& opts);
    ~Encoder();

    Encoder(const Encoder&) = delete;
    Encoder& operator=(const Encoder&) = delete;

    /** argb: width*height little-endian ARGB words (Java TYPE_INT_ARGB). */
    bool add_frame(const uint32_t* argb, int duration_ms);
    /** rgba: width*height*4 bytes in R,G,B,A order (GL_RGBA readback). */
    bool add_frame_rgba(const uint8_t* rgba, int duration_ms);

    // Two-phase variant for JNI: load_*() only converts into the I420 scratch
    // image (fast, safe under GetPrimitiveArrayCritical); encode_loaded() runs
    // the actual VP9 encode after the critical region is released.
    void load_argb(const uint32_t* argb);
    void load_rgba(const uint8_t* rgba);
    bool encode_loaded(int duration_ms);

    /** Flush the codec, finalize the WebM container. Call exactly once. */
    bool finish(std::vector<uint8_t>& out);

    int width() const { return width_; }
    int height() const { return height_; }

private:
    Encoder() = default;
    bool init(int width, int height, const EncoderOptions& opts);
    bool encode_current_image(int duration_ms);
    bool drain_packets();

    class Writer;
    std::unique_ptr<Writer> writer_;

    vpx_codec_ctx_t codec_{};
    bool codec_inited_ = false;
    vpx_image_t img_{};
    bool img_inited_ = false;

    int width_ = 0;
    int height_ = 0;
    uint64_t track_ = 0;
    int64_t pts_ms_ = 0;
    bool finished_ = false;
};

/** Encode VP9/WebM (no audio). durations_ms length must equal frames.size(). */
bool encode_vp9(const std::vector<std::vector<uint32_t>>& frames,
                int width, int height,
                const std::vector<int>& durations_ms,
                int cpu_used,
                int bitrate_kbps,
                std::vector<uint8_t>& out);

/**
 * Streaming VP9/WebM decoder. Frames are decoded lazily — one per next()
 * call — so a long clip never materializes every frame in memory at once.
 *
 * Lifetime: open() copies the input bytes; the returned Decoder owns the
 * copy, the libwebm parser and the libvpx decoder context until destroyed.
 * Not thread-safe (single-consumer, like a Java Iterator).
 */
class Decoder {
public:
    /**
     * Parse the container + init the VP9 decoder. Returns nullptr on failure.
     * When max_width/max_height are > 0 and the stream is larger, every frame
     * is downscaled (SIMD, on the YUV planes) to fit inside the bounds before
     * the ARGB conversion — info() and frames report the scaled size.
     */
    static Decoder* open(const uint8_t* data, size_t size,
                         int max_width = 0, int max_height = 0);
    ~Decoder();

    Decoder(const Decoder&) = delete;
    Decoder& operator=(const Decoder&) = delete;

    const WebMInfo& info() const { return info_; }

    /** True while next() can still produce a frame. */
    bool has_more() const { return !pending_.empty(); }

    /** Decode/return the next frame. Returns false once the stream is drained. */
    bool next(WebMFrame& out);

private:
    Decoder() = default;

    bool init(const uint8_t* data, size_t size, int max_width, int max_height);
    void scan_timestamps();
    bool read_next_coded_frame(std::vector<uint8_t>& buf);
    void decode_one_packet();
    void fill_pending();
    int frame_duration_ms(int index) const;

    std::vector<uint8_t> buffer_;             // owned copy of the input stream
    mkvparser::IMkvReader* reader_ = nullptr; // reads from buffer_
    mkvparser::Segment* segment_ = nullptr;
    vpx_codec_ctx_t codec_{};
    bool codec_inited_ = false;

    long video_track_ = -1;
    WebMInfo info_{};
    std::vector<int> frame_ts_ms_;            // per-frame timestamps from the header scan

    // Decode-time downscale target (0 = deliver native size).
    int target_w_ = 0;
    int target_h_ = 0;
    std::vector<uint8_t> scale_buf_;          // scaled I420 planes scratch

    // Decode cursor (resumable position in the cluster/block tree).
    const mkvparser::Cluster* cluster_ = nullptr;
    const mkvparser::BlockEntry* entry_ = nullptr;
    int frame_in_block_ = 0;
    bool cursor_done_ = false;

    std::deque<WebMFrame> pending_;           // decoded-but-not-yet-returned images
    int out_index_ = 0;                       // index of the next frame next() will return
};

}  // namespace webm_codec
