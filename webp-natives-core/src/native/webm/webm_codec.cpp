#include "webm_codec.h"

#include <cstring>
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

static void argb_to_i420(const uint32_t* argb, int width, int height,
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

static void i420_to_argb(const uint8_t* y, const uint8_t* u, const uint8_t* v,
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

    info->set_duration(static_cast<double>(total_duration_ms) / 1000.0);

    if (!segment.Finalize()) {
        vpx_codec_destroy(&codec);
        return false;
    }

    vpx_codec_destroy(&codec);
    out = writer.data();
    return !out.empty();
}

bool decode_vp9(const uint8_t* data, size_t size,
                std::vector<WebMFrame>& out_frames,
                WebMInfo& out_info) {
    out_frames.clear();
    if (data == nullptr || size == 0) return false;

    MemoryReader reader(data, size);
    mkvparser::EBMLHeader header;
    long long ebml_pos = 0;
    if (header.Parse(&reader, ebml_pos) < 0) return false;

    long long pos = 0;
    mkvparser::Segment* segment = nullptr;
    if (mkvparser::Segment::CreateInstance(&reader, pos, segment) < 0 || segment == nullptr) {
        return false;
    }
    if (segment->Load() < 0) {
        delete segment;
        return false;
    }

    const mkvparser::Tracks* tracks = segment->GetTracks();
    if (tracks == nullptr) {
        delete segment;
        return false;
    }

    long video_track = -1;
    int width = 0;
    int height = 0;
    for (unsigned long i = 0; i < tracks->GetTracksCount(); ++i) {
        const mkvparser::Track* track = tracks->GetTrackByIndex(i);
        if (track != nullptr && track->GetType() == mkvparser::Track::kVideo) {
            const auto* video = static_cast<const mkvparser::VideoTrack*>(track);
            video_track = track->GetNumber();
            width = static_cast<int>(video->GetWidth());
            height = static_cast<int>(video->GetHeight());
            break;
        }
    }
    if (video_track < 0 || width <= 0 || height <= 0) {
        delete segment;
        return false;
    }

    vpx_codec_ctx_t codec;
    if (vpx_codec_dec_init(&codec, vpx_codec_vp9_dx(), nullptr, 0) != VPX_CODEC_OK) {
        delete segment;
        return false;
    }

    const mkvparser::Cluster* cluster = segment->GetFirst();
    int prev_ts_ms = -1;

    while (cluster != nullptr && !cluster->EOS()) {
        const mkvparser::BlockEntry* entry = nullptr;
        long status = cluster->GetFirst(entry);
        while (status == 0 && entry != nullptr && !entry->EOS()) {
            const mkvparser::Block* block = entry->GetBlock();
            if (block != nullptr && block->GetTrackNumber() == video_track) {
                const int frame_count = block->GetFrameCount();
                for (int f = 0; f < frame_count; ++f) {
                    const mkvparser::Block::Frame& frame = block->GetFrame(f);
                    std::vector<uint8_t> frame_data(static_cast<size_t>(frame.len));
                    if (frame.Read(segment->m_pReader, frame_data.data()) != 0) continue;

                    if (vpx_codec_decode(&codec, frame_data.data(), frame_data.size(), nullptr, 0) != VPX_CODEC_OK) {
                        continue;
                    }

                    vpx_codec_iter_t iter = nullptr;
                    vpx_image_t* img;
                    while ((img = vpx_codec_get_frame(&codec, &iter)) != nullptr) {
                        if (img->fmt != VPX_IMG_FMT_I420) continue;

                        WebMFrame out_frame;
                        out_frame.width = static_cast<int>(img->d_w);
                        out_frame.height = static_cast<int>(img->d_h);
                        out_frame.argb.resize(static_cast<size_t>(img->d_w) * img->d_h * 4);

                        const int ts_ms = static_cast<int>(block->GetTime(cluster) / 1000000LL);
                        out_frame.timestamp_ms = ts_ms;
                        out_frame.duration_ms = (prev_ts_ms < 0) ? 50 : (ts_ms - prev_ts_ms);
                        prev_ts_ms = ts_ms;

                        i420_to_argb(img->planes[VPX_PLANE_Y], img->planes[VPX_PLANE_U], img->planes[VPX_PLANE_V],
                                     img->stride[VPX_PLANE_Y], img->stride[VPX_PLANE_U],
                                     static_cast<int>(img->d_w), static_cast<int>(img->d_h),
                                     reinterpret_cast<uint32_t*>(out_frame.argb.data()));
                        out_frames.push_back(std::move(out_frame));
                    }
                }
            }

            const mkvparser::BlockEntry* next = nullptr;
            status = cluster->GetNext(entry, next);
            entry = next;
        }

        cluster = segment->GetNext(cluster);
    }

    vpx_codec_destroy(&codec);
    delete segment;

    if (out_frames.empty()) return false;

    out_info.width = width;
    out_info.height = height;
    out_info.frame_count = static_cast<int>(out_frames.size());
    out_info.duration_ms = out_frames.back().timestamp_ms
        + (out_frames.back().duration_ms > 0 ? out_frames.back().duration_ms : 50);
    return true;
}

}  // namespace webm_codec
