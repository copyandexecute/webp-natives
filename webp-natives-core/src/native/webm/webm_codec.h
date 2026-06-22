#pragma once

#include <cstddef>
#include <cstdint>
#include <deque>
#include <vector>

#include <vpx/vpx_decoder.h>
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
    /** Parse the container + init the VP9 decoder. Returns nullptr on failure. */
    static Decoder* open(const uint8_t* data, size_t size);
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

    bool init(const uint8_t* data, size_t size);
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

    // Decode cursor (resumable position in the cluster/block tree).
    const mkvparser::Cluster* cluster_ = nullptr;
    const mkvparser::BlockEntry* entry_ = nullptr;
    int frame_in_block_ = 0;
    bool cursor_done_ = false;

    std::deque<WebMFrame> pending_;           // decoded-but-not-yet-returned images
    int out_index_ = 0;                       // index of the next frame next() will return
};

}  // namespace webm_codec
