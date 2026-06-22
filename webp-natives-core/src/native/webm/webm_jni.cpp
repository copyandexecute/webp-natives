#include "webm_codec.h"

#include <jni.h>
#include <cstring>
#include <vector>

namespace {

std::vector<uint32_t> jni_argb_frame(JNIEnv* env, jintArray arr, int width, int height) {
    const jsize needed = static_cast<jsize>(width * height);
    if (env->GetArrayLength(arr) < needed) return {};
    jint* pixels = env->GetIntArrayElements(arr, nullptr);
    if (pixels == nullptr) return {};
    std::vector<uint32_t> out(static_cast<size_t>(needed));
    std::memcpy(out.data(), pixels, static_cast<size_t>(needed) * sizeof(uint32_t));
    env->ReleaseIntArrayElements(arr, pixels, JNI_ABORT);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_gg_norisk_webm_internal_WebMNative_encodeVp9(JNIEnv* env, jclass cls,
                                                   jint width, jint height,
                                                   jobjectArray frameArrays,
                                                   jintArray durationsMs,
                                                   jint cpuUsed, jint bitrateKbps) {
    (void) cls;
    if (frameArrays == nullptr || durationsMs == nullptr || width <= 0 || height <= 0) return nullptr;

    const jsize frameCount = env->GetArrayLength(frameArrays);
    if (frameCount <= 0 || env->GetArrayLength(durationsMs) != frameCount) return nullptr;

    jint* durs = env->GetIntArrayElements(durationsMs, nullptr);
    if (durs == nullptr) return nullptr;

    std::vector<std::vector<uint32_t>> frames(static_cast<size_t>(frameCount));
    std::vector<int> durations(static_cast<size_t>(frameCount));
    bool ok = true;

    for (jsize i = 0; i < frameCount && ok; ++i) {
        auto* frameArr = static_cast<jintArray>(env->GetObjectArrayElement(frameArrays, i));
        if (frameArr == nullptr) {
            ok = false;
            break;
        }
        frames[static_cast<size_t>(i)] = jni_argb_frame(env, frameArr, width, height);
        env->DeleteLocalRef(frameArr);
        if (frames[static_cast<size_t>(i)].empty()) {
            ok = false;
            break;
        }
        durations[static_cast<size_t>(i)] = static_cast<int>(durs[i]);
    }

    env->ReleaseIntArrayElements(durationsMs, durs, JNI_ABORT);
    if (!ok) return nullptr;

    std::vector<uint8_t> webm;
    if (!webm_codec::encode_vp9(frames, width, height, durations, cpuUsed, bitrateKbps, webm)) {
        return nullptr;
    }

    auto* result = env->NewByteArray(static_cast<jsize>(webm.size()));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(webm.size()),
                            reinterpret_cast<const jbyte*>(webm.data()));
    return result;
}

JNIEXPORT jlong JNICALL
Java_gg_norisk_webm_internal_WebMNative_decodeOpen(JNIEnv* env, jclass cls, jbyteArray data) {
    (void) cls;
    if (data == nullptr) return 0;
    const jsize len = env->GetArrayLength(data);
    if (len <= 0) return 0;

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return 0;

    webm_codec::Decoder* decoder = webm_codec::Decoder::open(
        reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    if (decoder == nullptr) return 0;
    return reinterpret_cast<jlong>(decoder);
}

JNIEXPORT jintArray JNICALL
Java_gg_norisk_webm_internal_WebMNative_decodeGetInfo(JNIEnv* env, jclass cls, jlong handlePtr) {
    (void) cls;
    if (handlePtr == 0) return nullptr;
    auto* decoder = reinterpret_cast<webm_codec::Decoder*>(handlePtr);
    const webm_codec::WebMInfo& info = decoder->info();
    jintArray arr = env->NewIntArray(4);
    if (arr == nullptr) return nullptr;
    jint vals[4] = {
        info.width,
        info.height,
        info.frame_count,
        info.duration_ms,
    };
    env->SetIntArrayRegion(arr, 0, 4, vals);
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_gg_norisk_webm_internal_WebMNative_decodeHasMoreFrames(JNIEnv* env, jclass cls, jlong handlePtr) {
    (void) env;
    (void) cls;
    if (handlePtr == 0) return JNI_FALSE;
    auto* decoder = reinterpret_cast<webm_codec::Decoder*>(handlePtr);
    return decoder->has_more() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_gg_norisk_webm_internal_WebMNative_decodeNextFrame(JNIEnv* env, jclass cls, jlong handlePtr) {
    (void) cls;
    if (handlePtr == 0) return nullptr;
    auto* decoder = reinterpret_cast<webm_codec::Decoder*>(handlePtr);

    webm_codec::WebMFrame frame;
    if (!decoder->next(frame)) return nullptr;  // stream drained

    const jsize pixelCount = static_cast<jsize>(frame.width * frame.height);
    jintArray pixels = env->NewIntArray(pixelCount + 2);
    if (pixels == nullptr) return nullptr;

    env->SetIntArrayRegion(pixels, 0, pixelCount,
                           reinterpret_cast<const jint*>(frame.argb.data()));
    jint meta[2] = { frame.duration_ms, frame.timestamp_ms };
    env->SetIntArrayRegion(pixels, pixelCount, 2, meta);
    return pixels;
}

JNIEXPORT void JNICALL
Java_gg_norisk_webm_internal_WebMNative_decodeClose(JNIEnv* env, jclass cls, jlong handlePtr) {
    (void) env;
    (void) cls;
    if (handlePtr == 0) return;
    delete reinterpret_cast<webm_codec::Decoder*>(handlePtr);
}

}  // extern "C"
