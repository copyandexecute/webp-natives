#include "webm_codec.h"

#include <algorithm>
#include <cstring>
#include <thread>
#include <utility>
#include <libyuv.h>
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

    std::vector<uint8_t>& data() { return data_; }

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

int auto_threads(int requested) {
    if (requested > 0) return std::min(requested, 16);
    const unsigned hw = std::thread::hardware_concurrency();
    return static_cast<int>(std::min(hw == 0 ? 1u : hw, 8u));
}

// VP9 only parallelizes across tile columns (plus row-mt within them); pick
// the widest tiling the resolution allows so g_threads can actually bite.
int tile_columns_log2(int width) {
    if (width >= 2560) return 3;
    if (width >= 1280) return 2;
    if (width >= 640) return 1;
    return 0;
}

}  // namespace

namespace webm_codec {

// ─────────────────────────────────────────────────────────────────
//  Streaming encoder
// ─────────────────────────────────────────────────────────────────

class Encoder::Writer {
public:
    MemoryWriter mem;
    mkvmuxer::Segment segment;
};

Encoder* Encoder::create(int width, int height, const EncoderOptions& opts) {
    if (width <= 0 || height <= 0) return nullptr;
    auto* e = new Encoder();
    if (!e->init(width, height, opts)) {
        delete e;
        return nullptr;
    }
    return e;
}

Encoder::~Encoder() {
    if (img_inited_) vpx_img_free(&img_);
    if (codec_inited_) vpx_codec_destroy(&codec_);
}

bool Encoder::init(int width, int height, const EncoderOptions& opts) {
    width_ = width;
    height_ = height;

    vpx_codec_enc_cfg_t cfg;
    if (vpx_codec_enc_config_default(vpx_codec_vp9_cx(), &cfg, 0) != VPX_CODEC_OK) {
        return false;
    }
    cfg.g_w = width;
    cfg.g_h = height;
    cfg.g_timebase.num = 1;
    cfg.g_timebase.den = 1000;
    cfg.g_lag_in_frames = 0;
    cfg.g_threads = auto_threads(opts.threads);
    cfg.rc_target_bitrate = opts.bitrate_kbps;
    cfg.g_error_resilient = VPX_ERROR_RESILIENT_DEFAULT;
    cfg.kf_mode = VPX_KF_AUTO;
    cfg.kf_max_dist = opts.kf_max_dist > 0 ? opts.kf_max_dist : 30;

    switch (opts.rc_mode) {
        case 1: cfg.rc_end_usage = VPX_VBR; break;
        case 2: cfg.rc_end_usage = VPX_CQ; break;
        default: cfg.rc_end_usage = VPX_CBR; break;
    }
    if (opts.min_quantizer >= 0) cfg.rc_min_quantizer = opts.min_quantizer;
    if (opts.max_quantizer >= 0) cfg.rc_max_quantizer = opts.max_quantizer;
    if (opts.rc_mode == 2) {
        // CQ level must sit inside [min_q, max_q] or libvpx ignores it.
        if (opts.min_quantizer < 0) cfg.rc_min_quantizer = 4;
        if (opts.max_quantizer < 0) cfg.rc_max_quantizer = 56;
    }

    if (vpx_codec_enc_init(&codec_, vpx_codec_vp9_cx(), &cfg, 0) != VPX_CODEC_OK) {
        return false;
    }
    codec_inited_ = true;

    vpx_codec_control(&codec_, VP8E_SET_CPUUSED, opts.cpu_used);
    vpx_codec_control(&codec_, VP9E_SET_TILE_COLUMNS, tile_columns_log2(width));
    vpx_codec_control(&codec_, VP9E_SET_ROW_MT, 1);
    vpx_codec_control(&codec_, VP9E_SET_FRAME_PARALLEL_DECODING, 1);
    if (opts.rc_mode == 2) {
        const int cq = std::max(0, std::min(63, opts.cq_level));
        vpx_codec_control(&codec_, VP8E_SET_CQ_LEVEL, cq);
    } else if (opts.cpu_used >= 5) {
        // Cyclic-refresh AQ: the standard realtime-CBR quality boost.
        vpx_codec_control(&codec_, VP9E_SET_AQ_MODE, 3);
    }

    writer_.reset(new Writer());
    if (!writer_->segment.Init(&writer_->mem)) return false;

    track_ = writer_->segment.AddVideoTrack(static_cast<int32_t>(width),
                                            static_cast<int32_t>(height), 1);
    mkvmuxer::Track* video = writer_->segment.GetTrackByNumber(track_);
    if (video == nullptr) return false;
    video->set_codec_id(mkvmuxer::Tracks::kVp9CodecId);
    mkvmuxer::SegmentInfo* info = writer_->segment.GetSegmentInfo();
    info->set_timecode_scale(1000000);  // ns per tick → 1 tick = 1ms
    info->set_writing_app("webp-natives");

    // Allocate the I420 source image once and reuse it per frame. libvpx sizes
    // the planes with the correct (ceil) chroma dimensions and its own aligned
    // strides, so odd widths/heights can't overflow. align=32 matches the
    // encoder's SIMD expectations.
    if (vpx_img_alloc(&img_, VPX_IMG_FMT_I420, width, height, 32) == nullptr) {
        return false;
    }
    img_inited_ = true;
    return true;
}

void Encoder::load_argb(const uint32_t* argb) {
    // Java TYPE_INT_ARGB words are B,G,R,A bytes on little-endian — exactly
    // libyuv's "ARGB" byte order.
    libyuv::ARGBToI420(reinterpret_cast<const uint8_t*>(argb), width_ * 4,
                       img_.planes[VPX_PLANE_Y], img_.stride[VPX_PLANE_Y],
                       img_.planes[VPX_PLANE_U], img_.stride[VPX_PLANE_U],
                       img_.planes[VPX_PLANE_V], img_.stride[VPX_PLANE_V],
                       width_, height_);
}

void Encoder::load_rgba(const uint8_t* rgba) {
    // R,G,B,A byte order (GL_RGBA readback) is libyuv's "ABGR" fourcc.
    libyuv::ABGRToI420(rgba, width_ * 4,
                       img_.planes[VPX_PLANE_Y], img_.stride[VPX_PLANE_Y],
                       img_.planes[VPX_PLANE_U], img_.stride[VPX_PLANE_U],
                       img_.planes[VPX_PLANE_V], img_.stride[VPX_PLANE_V],
                       width_, height_);
}

bool Encoder::encode_loaded(int duration_ms) {
    if (finished_) return false;
    return encode_current_image(duration_ms);
}

bool Encoder::add_frame(const uint32_t* argb, int duration_ms) {
    if (finished_ || argb == nullptr) return false;
    load_argb(argb);
    return encode_current_image(duration_ms);
}

bool Encoder::add_frame_rgba(const uint8_t* rgba, int duration_ms) {
    if (finished_ || rgba == nullptr) return false;
    load_rgba(rgba);
    return encode_current_image(duration_ms);
}

bool Encoder::encode_current_image(int duration_ms) {
    const int dur = duration_ms > 0 ? duration_ms : 1;
    if (vpx_codec_encode(&codec_, &img_, pts_ms_, dur, 0, VPX_DL_REALTIME) != VPX_CODEC_OK) {
        return false;
    }
    if (!drain_packets()) return false;
    pts_ms_ += dur;
    return true;
}

bool Encoder::drain_packets() {
    vpx_codec_iter_t iter = nullptr;
    const vpx_codec_cx_pkt_t* pkt;
    while ((pkt = vpx_codec_get_cx_data(&codec_, &iter)) != nullptr) {
        if (pkt->kind != VPX_CODEC_CX_FRAME_PKT) continue;
        const bool keyframe = (pkt->data.frame.flags & VPX_FRAME_IS_KEY) != 0;
        const uint64_t ts_ns = static_cast<uint64_t>(pkt->data.frame.pts) * 1000000ULL;
        if (!writer_->segment.AddFrame(static_cast<const uint8_t*>(pkt->data.frame.buf),
                                       pkt->data.frame.sz, track_, ts_ns, keyframe)) {
            return false;
        }
    }
    return true;
}

bool Encoder::finish(std::vector<uint8_t>& out) {
    if (finished_) return false;
    finished_ = true;

    // Flush any frames still buffered inside libvpx (none with lag=0, but the
    // flush contract requires draining until the codec reports empty).
    for (;;) {
        if (vpx_codec_encode(&codec_, nullptr, pts_ms_, 1, 0, VPX_DL_REALTIME) != VPX_CODEC_OK) {
            return false;
        }
        vpx_codec_iter_t iter = nullptr;
        const vpx_codec_cx_pkt_t* pkt = vpx_codec_get_cx_data(&codec_, &iter);
        if (pkt == nullptr) break;
        do {
            if (pkt->kind == VPX_CODEC_CX_FRAME_PKT) {
                const bool keyframe = (pkt->data.frame.flags & VPX_FRAME_IS_KEY) != 0;
                const uint64_t ts_ns = static_cast<uint64_t>(pkt->data.frame.pts) * 1000000ULL;
                if (!writer_->segment.AddFrame(static_cast<const uint8_t*>(pkt->data.frame.buf),
                                               pkt->data.frame.sz, track_, ts_ns, keyframe)) {
                    return false;
                }
            }
        } while ((pkt = vpx_codec_get_cx_data(&codec_, &iter)) != nullptr);
    }

    writer_->segment.GetSegmentInfo()->set_duration(static_cast<double>(pts_ms_));
    if (!writer_->segment.Finalize()) return false;
    out = std::move(writer_->mem.data());
    return !out.empty();
}

// ─────────────────────────────────────────────────────────────────
//  Batch encode — thin wrapper over the streaming encoder.
// ─────────────────────────────────────────────────────────────────

bool encode_vp9(const std::vector<std::vector<uint32_t>>& frames,
                int width, int height,
                const std::vector<int>& durations_ms,
                int cpu_used,
                int bitrate_kbps,
                std::vector<uint8_t>& out) {
    if (frames.empty() || frames.size() != durations_ms.size() || width <= 0 || height <= 0) {
        return false;
    }

    EncoderOptions opts;
    opts.cpu_used = cpu_used;
    opts.bitrate_kbps = bitrate_kbps;

    std::unique_ptr<Encoder> enc(Encoder::create(width, height, opts));
    if (!enc) return false;

    for (size_t fi = 0; fi < frames.size(); ++fi) {
        if (static_cast<int>(frames[fi].size()) < width * height) return false;
        if (!enc->add_frame(frames[fi].data(), durations_ms[fi])) return false;
    }
    return enc->finish(out);
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

    // Multi-threaded decode: VP9 frame-level threading kicks in when the
    // stream was encoded with frame-parallel mode / tiles (ours is).
    vpx_codec_dec_cfg_t dec_cfg;
    std::memset(&dec_cfg, 0, sizeof(dec_cfg));
    dec_cfg.threads = static_cast<unsigned int>(auto_threads(0));
    if (vpx_codec_dec_init(&codec_, vpx_codec_vp9_dx(), &dec_cfg, 0) != VPX_CODEC_OK) {
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
        // libyuv writes B,G,R,A bytes ("ARGB" fourcc) — a Java TYPE_INT_ARGB
        // int[] on little-endian, matching the historical scalar output.
        libyuv::I420ToARGB(img->planes[VPX_PLANE_Y], img->stride[VPX_PLANE_Y],
                           img->planes[VPX_PLANE_U], img->stride[VPX_PLANE_U],
                           img->planes[VPX_PLANE_V], img->stride[VPX_PLANE_V],
                           fr.argb.data(), fr.width * 4,
                           fr.width, fr.height);
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
