#include "webm_codec.h"

#include <cstring>
#include <utility>
#include <mkvmuxer/mkvmuxer.h>
#include <mkvparser/mkvparser.h>
#include <vpx/vp8cx.h>
#include <vpx/vp8dx.h>
#include <vpx/vpx_codec.h>
#include <vpx/vpx_encoder.h>
#include <vpx/vpx_decoder.h>

namespace {

class MemoryWriter : public mkvmuxer::IMkvWriter {
public:
    mkvmuxer::int32 Write(const void* buf, mkvmuxer::uint32 len) override {
        const auto* bytes = static_cast<const uint8_t*>(buf);
        if (pos_ + static_cast<mkvmuxer::int64>(len) > static_cast<mkvmuxer::int64>(data_.size())) {
            data_.resize(static_cast<size_t>(pos_ + len), 0);
        }
        std::memcpy(data_.data() + pos_, bytes, len);
        pos_ += len;
        return 0;
    }

    mkvmuxer::int64 Position() const override { return pos_; }

    mkvmuxer::int32 Position(mkvmuxer::int64 position) override {
        if (position < 0) return -1;
        if (static_cast<size_t>(position) > data_.size()) {
            data_.resize(static_cast<size_t>(position), 0);
        }
        pos_ = position;
        return 0;
    }

    bool Seekable() const override { return true; }

    void ElementStartNotify(mkvmuxer::uint64 /*element_id*/, mkvmuxer::int64 /*position*/) override {}

    const std::vector<uint8_t>& data() const { return data_; }

private:
    std::vector<uint8_t> data_;
    mkvmuxer::int64 pos_ = 0;
};

// Reads from an in-memory WebM buffer for the parser. Lives in the anonymous
// namespace; the Decoder holds it via an IMkvReader* and deletes the concrete
// type in its destructor (IMkvReader's own destructor is protected).
class MemoryReader : public mkvparser::IMkvReader {
public:
    MemoryReader(const uint8_t* data, size_t size) : data_(data), size_(size) {}

    int Read(long long pos, long len, unsigned char* buf) override {
        if (data_ == nullptr || buf == nullptr || pos < 0 || len < 0) return -1;
        if (static_cast<size_t>(pos + len) > size_) return -1;
        std::memcpy(buf, data_ + pos, static_cast<size_t>(len));
        return 0;
    }

    int Length(long long* total, long long* available) override {
        const long long sz = static_cast<long long>(size_);
        if (total != nullptr) *total = sz;
        if (available != nullptr) *available = sz;
        return 0;
    }

private:
    const uint8_t* data_;
    size_t size_;
};

void argb_to_i420(const uint32_t* argb, int width, int height,
                  uint8_t* y, uint8_t* u, uint8_t* v) {
    for (int j = 0; j < height; ++j) {
        for (int i = 0; i < width; ++i) {
            const uint32_t p = argb[j * width + i];
            const int b = p & 0xFF;
            const int g = (p >> 8) & 0xFF;
            const int r = (p >> 16) & 0xFF;
            const int y_val = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
            y[j * width + i] = static_cast<uint8_t>(y_val < 0 ? 0 : (y_val > 255 ? 255 : y_val));
            if ((j & 1) == 0 && (i & 1) == 0) {
                const int u_val = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                const int v_val = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                const int uv_idx = (j / 2) * (width / 2) + (i / 2);
                u[uv_idx] = static_cast<uint8_t>(u_val < 0 ? 0 : (u_val > 255 ? 255 : u_val));
                v[uv_idx] = static_cast<uint8_t>(v_val < 0 ? 0 : (v_val > 255 ? 255 : v_val));
            }
        }
    }
}

void i420_to_argb(const uint8_t* y, const uint8_t* u, const uint8_t* v,
                  int y_stride, int uv_stride, int width, int height, uint32_t* argb) {
    for (int j = 0; j < height; ++j) {
        for (int i = 0; i < width; ++i) {
            const int y_val = y[j * y_stride + i];
            const int uv_idx = (j / 2) * uv_stride + (i / 2);
            const int u_val = u[uv_idx] - 128;
            const int v_val = v[uv_idx] - 128;
            int r = y_val + ((91881 * v_val) >> 16);
            int g = y_val - ((22554 * u_val + 46802 * v_val) >> 16);
            int b = y_val + ((116130 * u_val) >> 16);
            r = r < 0 ? 0 : (r > 255 ? 255 : r);
            g = g < 0 ? 0 : (g > 255 ? 255 : g);
            b = b < 0 ? 0 : (b > 255 ? 255 : b);
            argb[j * width + i] = 0xFF000000u | (static_cast<uint32_t>(r) << 16)
                | (static_cast<uint32_t>(g) << 8) | static_cast<uint32_t>(b);
        }
    }
}

}  // namespace

namespace webm_codec {

bool encode_vp9(const std::vector<std::vector<uint32_t>>& frames,
                int width, int height,
                const std::vector<int>& durations_ms,
                int cpu_used,
                int bitrate_kbps,
                std::vector<uint8_t>& out) {
    if (frames.empty() || frames.size() != durations_ms.size() || width <= 0 || height <= 0) {
        return false;
    }

    vpx_codec_ctx_t codec;
    vpx_codec_enc_cfg_t cfg;
    if (vpx_codec_enc_config_default(vpx_codec_vp9_cx(), &cfg, 0) != VPX_CODEC_OK) {
        return false;
    }
    cfg.g_w = width;
    cfg.g_h = height;
    cfg.g_timebase.num = 1;
    cfg.g_timebase.den = 1000;
    cfg.g_lag_in_frames = 0;
    cfg.rc_target_bitrate = bitrate_kbps;
    cfg.rc_end_usage = VPX_CBR;
    cfg.g_error_resilient = VPX_ERROR_RESILIENT_DEFAULT;
    cfg.kf_mode = VPX_KF_AUTO;
    cfg.kf_max_dist = 30;

    if (vpx_codec_enc_init(&codec, vpx_codec_vp9_cx(), &cfg, 0) != VPX_CODEC_OK) {
        return false;
    }
    vpx_codec_control(&codec, VP8E_SET_CPUUSED, cpu_used);

    MemoryWriter writer;
    mkvmuxer::Segment segment;
    if (!segment.Init(&writer)) {
        vpx_codec_destroy(&codec);
        return false;
    }

    const uint64_t track = segment.AddVideoTrack(static_cast<int32_t>(width),
                                                 static_cast<int32_t>(height), 1);
    mkvmuxer::Track* video = segment.GetTrackByNumber(track);
    if (video == nullptr) {
        vpx_codec_destroy(&codec);
        return false;
    }
    video->set_codec_id(mkvmuxer::Tracks::kVp9CodecId);
    mkvmuxer::SegmentInfo* info = segment.GetSegmentInfo();
    info->set_timecode_scale(1'000'000);
    info->set_writing_app("webp-natives");

    const size_t y_size = static_cast<size_t>(width) * height;
    const size_t uv_size = y_size / 4;
    std::vector<uint8_t> y_plane(y_size);
    std::vector<uint8_t> u_plane(uv_size);
    std::vector<uint8_t> v_plane(uv_size);
    std::vector<uint8_t> i420(y_size + uv_size * 2);

    int64_t pts_ms = 0;
    int total_duration_ms = 0;

    for (size_t fi = 0; fi < frames.size(); ++fi) {
        if (static_cast<int>(frames[fi].size()) < width * height) {
            vpx_codec_destroy(&codec);
            return false;
        }

        argb_to_i420(frames[fi].data(), width, height,
                     y_plane.data(), u_plane.data(), v_plane.data());

        uint8_t* dst_y = i420.data();
        uint8_t* dst_u = dst_y + y_size;
        uint8_t* dst_v = dst_u + uv_size;
        std::memcpy(dst_y, y_plane.data(), y_size);
        std::memcpy(dst_u, u_plane.data(), uv_size);
        std::memcpy(dst_v, v_plane.data(), uv_size);

        vpx_image_t img;
        vpx_img_wrap(&img, VPX_IMG_FMT_I420, width, height, 1, i420.data());

        const vpx_codec_err_t enc_err = vpx_codec_encode(
            &codec, &img, pts_ms, durations_ms[fi], 0, VPX_DL_REALTIME);
        if (enc_err != VPX_CODEC_OK) {
            vpx_codec_destroy(&codec);
            return false;
        }

        vpx_codec_iter_t iter = nullptr;
        const vpx_codec_cx_pkt_t* pkt;
        while ((pkt = vpx_codec_get_cx_data(&codec, &iter)) != nullptr) {
            if (pkt->kind == VPX_CODEC_CX_FRAME_PKT) {
                const bool keyframe = (pkt->data.frame.flags & VPX_FRAME_IS_KEY) != 0;
                const uint64_t ts_ns = static_cast<uint64_t>(pts_ms) * 1'000'000ULL;
                if (!segment.AddFrame(static_cast<const uint8_t*>(pkt->data.frame.buf),
                                      pkt->data.frame.sz, track, ts_ns, keyframe)) {
                    vpx_codec_destroy(&codec);
                    return false;
                }
            }
        }

        pts_ms += durations_ms[fi];
        total_duration_ms += durations_ms[fi];
    }

    info->set_duration(static_cast<double>(total_duration_ms));

    if (!segment.Finalize()) {
        vpx_codec_destroy(&codec);
        return false;
    }

    vpx_codec_destroy(&codec);
    out = writer.data();
    return !out.empty();
}

// ─────────────────────────────────────────────────────────────────
//  Streaming decoder
// ─────────────────────────────────────────────────────────────────

Decoder* Decoder::open(const uint8_t* data, size_t size) {
    if (data == nullptr || size == 0) return nullptr;
    auto* d = new Decoder();
    if (!d->init(data, size)) {
        delete d;
        return nullptr;
    }
    return d;
}

Decoder::~Decoder() {
    if (codec_inited_) vpx_codec_destroy(&codec_);
    delete segment_;
    // reader_ is an IMkvReader* whose destructor is protected; delete via the
    // concrete type (its compiler-generated destructor is accessible).
    delete static_cast<MemoryReader*>(reader_);
}

bool Decoder::init(const uint8_t* data, size_t size) {
    buffer_.assign(data, data + size);
    reader_ = new MemoryReader(buffer_.data(), buffer_.size());

    mkvparser::EBMLHeader header;
    long long ebml_pos = 0;
    if (header.Parse(reader_, ebml_pos) < 0) return false;

    long long pos = 0;
    if (mkvparser::Segment::CreateInstance(reader_, pos, segment_) < 0 || segment_ == nullptr) {
        return false;
    }
    if (segment_->Load() < 0) return false;

    const mkvparser::Tracks* tracks = segment_->GetTracks();
    if (tracks == nullptr) return false;

    int width = 0;
    int height = 0;
    for (unsigned long i = 0; i < tracks->GetTracksCount(); ++i) {
        const mkvparser::Track* track = tracks->GetTrackByIndex(i);
        if (track != nullptr && track->GetType() == mkvparser::Track::kVideo) {
            const auto* video = static_cast<const mkvparser::VideoTrack*>(track);
            video_track_ = track->GetNumber();
            width = static_cast<int>(video->GetWidth());
            height = static_cast<int>(video->GetHeight());
            break;
        }
    }
    if (video_track_ < 0 || width <= 0 || height <= 0) return false;

    if (vpx_codec_dec_init(&codec_, vpx_codec_vp9_dx(), nullptr, 0) != VPX_CODEC_OK) {
        return false;
    }
    codec_inited_ = true;

    info_.width = width;
    info_.height = height;
    scan_timestamps();  // header-only: frame_count + per-frame timestamps + duration

    // Position the decode cursor at the first cluster, then prime one frame.
    cluster_ = segment_->GetFirst();
    entry_ = nullptr;
    frame_in_block_ = 0;
    cursor_done_ = (cluster_ == nullptr || cluster_->EOS());

    fill_pending();
    return true;
}

void Decoder::scan_timestamps() {
    frame_ts_ms_.clear();
    const mkvparser::Cluster* cluster = segment_->GetFirst();
    while (cluster != nullptr && !cluster->EOS()) {
        const mkvparser::BlockEntry* entry = nullptr;
        long status = cluster->GetFirst(entry);
        while (status == 0 && entry != nullptr && !entry->EOS()) {
            const mkvparser::Block* block = entry->GetBlock();
            if (block != nullptr && block->GetTrackNumber() == video_track_) {
                const int fc = block->GetFrameCount();
                const int ts = static_cast<int>(block->GetTime(cluster) / 1000000LL);
                for (int f = 0; f < fc; ++f) frame_ts_ms_.push_back(ts);
            }
            const mkvparser::BlockEntry* next = nullptr;
            status = cluster->GetNext(entry, next);
            entry = next;
        }
        cluster = segment_->GetNext(cluster);
    }
    info_.frame_count = static_cast<int>(frame_ts_ms_.size());
    if (!frame_ts_ms_.empty()) {
        const int last = info_.frame_count - 1;
        info_.duration_ms = frame_ts_ms_[last] + frame_duration_ms(last);
    } else {
        info_.duration_ms = 0;
    }
}

int Decoder::frame_duration_ms(int index) const {
    const int n = static_cast<int>(frame_ts_ms_.size());
    if (index < 0 || index >= n) return 0;
    if (index + 1 < n) return frame_ts_ms_[index + 1] - frame_ts_ms_[index];
    if (n >= 2) return frame_ts_ms_[n - 1] - frame_ts_ms_[n - 2];  // last frame: reuse prior interval
    return 0;
}

bool Decoder::read_next_coded_frame(std::vector<uint8_t>& buf) {
    while (cluster_ != nullptr && !cluster_->EOS()) {
        if (entry_ == nullptr) {
            long status = cluster_->GetFirst(entry_);
            if (status < 0 || entry_ == nullptr) {
                cluster_ = segment_->GetNext(cluster_);
                frame_in_block_ = 0;
                continue;
            }
        }
        while (entry_ != nullptr && !entry_->EOS()) {
            const mkvparser::Block* block = entry_->GetBlock();
            if (block != nullptr && block->GetTrackNumber() == video_track_
                && frame_in_block_ < block->GetFrameCount()) {
                const mkvparser::Block::Frame& frame = block->GetFrame(frame_in_block_++);
                buf.resize(static_cast<size_t>(frame.len));
                if (frame.Read(reader_, buf.data()) != 0) buf.clear();
                return true;
            }
            // advance to the next block entry
            frame_in_block_ = 0;
            const mkvparser::BlockEntry* next = nullptr;
            long status = cluster_->GetNext(entry_, next);
            entry_ = (status == 0) ? next : nullptr;
        }
        // exhausted this cluster
        cluster_ = segment_->GetNext(cluster_);
        entry_ = nullptr;
        frame_in_block_ = 0;
    }
    return false;
}

void Decoder::decode_one_packet() {
    std::vector<uint8_t> buf;
    if (!read_next_coded_frame(buf)) {
        cursor_done_ = true;
        return;
    }
    if (buf.empty()) return;  // read failure on this packet — skip, keep going
    if (vpx_codec_decode(&codec_, buf.data(), static_cast<unsigned int>(buf.size()),
                         nullptr, 0) != VPX_CODEC_OK) {
        return;
    }

    vpx_codec_iter_t iter = nullptr;
    vpx_image_t* img;
    while ((img = vpx_codec_get_frame(&codec_, &iter)) != nullptr) {
        if (img->fmt != VPX_IMG_FMT_I420) continue;
        WebMFrame fr;
        fr.width = static_cast<int>(img->d_w);
        fr.height = static_cast<int>(img->d_h);
        fr.argb.resize(static_cast<size_t>(img->d_w) * img->d_h * 4);
        i420_to_argb(img->planes[VPX_PLANE_Y], img->planes[VPX_PLANE_U], img->planes[VPX_PLANE_V],
                     img->stride[VPX_PLANE_Y], img->stride[VPX_PLANE_U],
                     fr.width, fr.height, reinterpret_cast<uint32_t*>(fr.argb.data()));
        pending_.push_back(std::move(fr));
    }
}

void Decoder::fill_pending() {
    while (pending_.empty() && !cursor_done_) decode_one_packet();
}

bool Decoder::next(WebMFrame& out) {
    if (pending_.empty()) return false;
    out = std::move(pending_.front());
    pending_.pop_front();
    if (out_index_ < static_cast<int>(frame_ts_ms_.size())) {
        out.timestamp_ms = frame_ts_ms_[out_index_];
        out.duration_ms = frame_duration_ms(out_index_);
    }
    ++out_index_;
    fill_pending();  // keep one frame buffered so has_more() stays accurate
    return true;
}

}  // namespace webm_codec
