#pragma once

#include <cstdint>
#include <vector>

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

/** Decode all VP9/WebM video frames to ARGB. */
bool decode_vp9(const uint8_t* data, size_t size,
                std::vector<WebMFrame>& out_frames,
                WebMInfo& out_info);

}  // namespace webm_codec
