/*
 * webp-natives — JNI bridge to libwebp.
 *
 * Java side:  gg.norisk.webp.internal.WebPNative
 * Symbols:    Java_gg_norisk_webp_internal_WebPNative_<method>
 *
 * Performance notes
 * -----------------
 * The "fast path" routines (decodeARGBInto / encodeARGB) skip the byte[]
 * intermediate: libwebp is configured for MODE_BGRA and writes directly
 * into / reads directly from a Java int[] that is also the BufferedImage's
 * DataBufferInt backing store. On little-endian systems (all targets we
 * care about: x86, x86_64, ARM64) the byte order BGRA in memory is
 * bit-identical to a Java int with format 0xAARRGGBB — i.e. exactly what
 * BufferedImage.TYPE_INT_ARGB stores.
 *
 * That gives us zero pixel-format conversion on top of zero allocation
 * inside the decoder. The old RGBA byte[] paths are kept around as
 * fallback for callers that supply images in unusual pixel formats.
 */

#include <jni.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "webp/decode.h"
#include "webp/encode.h"
#include "webp/demux.h"

/* Compile-time assertion that we're on a little-endian target. libwebp's
 * MODE_BGRA writes bytes in memory as [B][G][R][A]; a Java int read out of
 * that memory on LE gives 0xAARRGGBB which is exactly BufferedImage.TYPE_INT_ARGB.
 * On a hypothetical big-endian JVM the fast path is unsafe; refuse to build. */
#if defined(__BYTE_ORDER__) && (__BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__)
#  error "webp-natives fast path requires a little-endian target"
#endif

/* ──────────────────────────────────────────────────────────────────
 *  Header parse
 * ────────────────────────────────────────────────────────────────── */

JNIEXPORT jboolean JNICALL
Java_gg_norisk_webp_internal_WebPNative_isWebP(JNIEnv* env, jclass cls, jbyteArray data) {
    (void) cls;
    if (data == NULL) return JNI_FALSE;
    jsize len = (*env)->GetArrayLength(env, data);
    if (len < 12) return JNI_FALSE;

    jbyte* bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) return JNI_FALSE;

    const unsigned char* b = (const unsigned char*) bytes;
    jboolean ok = (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' &&
                   b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P')
                  ? JNI_TRUE : JNI_FALSE;

    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return ok;
}

JNIEXPORT jintArray JNICALL
Java_gg_norisk_webp_internal_WebPNative_getInfo(JNIEnv* env, jclass cls, jbyteArray data) {
    (void) cls;
    if (data == NULL) return NULL;
    jsize len = (*env)->GetArrayLength(env, data);
    if (len <= 0) return NULL;

    jbyte* bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) return NULL;

    int width = 0, height = 0;
    int ok = WebPGetInfo((const uint8_t*) bytes, (size_t) len, &width, &height);

    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    if (!ok) return NULL;

    jintArray result = (*env)->NewIntArray(env, 2);
    if (result == NULL) return NULL;
    jint dims[2] = { width, height };
    (*env)->SetIntArrayRegion(env, result, 0, 2, dims);
    return result;
}

/* ──────────────────────────────────────────────────────────────────
 *  Fast path: decode/encode directly to/from int[] (== BufferedImage
 *  TYPE_INT_ARGB backing buffer on little-endian).
 * ────────────────────────────────────────────────────────────────── */

/**
 * Decode a WEBP into a pre-allocated int[] buffer. Caller must size the
 * buffer to at least width*height ints (== width*height*4 bytes).
 *
 * Returns 0 on success, negative on error. On success, outDims is set to
 * {width, height}.
 */
JNIEXPORT jint JNICALL
Java_gg_norisk_webp_internal_WebPNative_decodeARGBInto(JNIEnv* env, jclass cls,
                                                        jbyteArray data,
                                                        jintArray outPixels,
                                                        jintArray outDims) {
    (void) cls;
    if (data == NULL || outPixels == NULL || outDims == NULL) return -1;
    jsize dataLen = (*env)->GetArrayLength(env, data);
    jsize pixelsLen = (*env)->GetArrayLength(env, outPixels);
    if (dataLen <= 0 || pixelsLen <= 0) return -1;
    if ((*env)->GetArrayLength(env, outDims) < 2) return -1;

    jbyte* dataBytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (dataBytes == NULL) return -1;

    /* Sniff dimensions to validate output buffer capacity before we touch it. */
    int width = 0, height = 0;
    if (!WebPGetInfo((const uint8_t*) dataBytes, (size_t) dataLen, &width, &height)) {
        (*env)->ReleaseByteArrayElements(env, data, dataBytes, JNI_ABORT);
        return -2;
    }
    if (width <= 0 || height <= 0 || (jsize) width * (jsize) height > pixelsLen) {
        (*env)->ReleaseByteArrayElements(env, data, dataBytes, JNI_ABORT);
        return -3;
    }

    /* GetPrimitiveArrayCritical pins the array's backing storage on HotSpot
     * (no copy). The critical region must not call back into the JVM —
     * libwebp's decode is pure C, so this is safe. The cost is that GC is
     * blocked during the call; for our largest workload (4K decode ~150ms)
     * this is a known tradeoff for the zero-copy gain. */
    jint* pixels = (jint*) (*env)->GetPrimitiveArrayCritical(env, outPixels, NULL);
    if (pixels == NULL) {
        (*env)->ReleaseByteArrayElements(env, data, dataBytes, JNI_ABORT);
        return -1;
    }

    WebPDecoderConfig config;
    WebPInitDecoderConfig(&config);
    config.options.use_threads     = 1;
    config.output.colorspace       = MODE_BGRA;
    config.output.u.RGBA.rgba      = (uint8_t*) pixels;
    config.output.u.RGBA.size      = (size_t) width * height * 4;
    config.output.u.RGBA.stride    = width * 4;
    config.output.is_external_memory = 1;

    VP8StatusCode status = WebPDecode((const uint8_t*) dataBytes, (size_t) dataLen, &config);

    /* Release the critical pin in both success and failure branches before
     * we touch any other JNI call (in particular SetIntArrayRegion below). */
    (*env)->ReleasePrimitiveArrayCritical(env, outPixels, pixels, status == VP8_STATUS_OK ? 0 : JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, data, dataBytes, JNI_ABORT);

    if (status != VP8_STATUS_OK) {
        return -10 - (jint) status;
    }

    jint dims[2] = { width, height };
    (*env)->SetIntArrayRegion(env, outDims, 0, 2, dims);
    return 0;
}

/**
 * Apply an {@code EncodePreset}'s knobs (method + losslessQuality) to a
 * libwebp config. method clamped to [0,6], losslessQuality to [0,100] —
 * matches the Java-side validation but defends in depth.
 */
static void applyPreset(WebPConfig* config, jboolean lossless, jfloat lossyQuality, jint method, jfloat losslessQuality) {
    if (method < 0) method = 0;
    if (method > 6) method = 6;
    if (losslessQuality < 0.0f) losslessQuality = 0.0f;
    if (losslessQuality > 100.0f) losslessQuality = 100.0f;

    if (lossless == JNI_TRUE) {
        config->lossless = 1;
        config->quality  = losslessQuality;
        config->method   = method;
    } else {
        /* WebPConfigPreset overwrites quality and method to its preset
         * defaults — call it first, then override method. */
        WebPConfigPreset(config, WEBP_PRESET_DEFAULT, lossyQuality);
        config->method   = method;
    }
}

/**
 * Encode an int[] holding ARGB pixels (BGRA bytes in memory on LE) into
 * a WEBP byte stream.
 *
 * Uses libwebp's low-level WebPEncode API instead of the convenience
 * WebPEncode{Lossless}BGRA wrappers so we can:
 *   - enable multi-threading (config.thread_level = 1)  → 2-3× on lossy
 *   - apply a caller-supplied tuning preset (method + losslessQuality)
 *   - feed the encoder ARGB data zero-copy via picture.argb = pinned int[]
 */
JNIEXPORT jbyteArray JNICALL
Java_gg_norisk_webp_internal_WebPNative_encodeARGB(JNIEnv* env, jclass cls,
                                                    jintArray argb,
                                                    jint width, jint height,
                                                    jfloat quality, jboolean lossless,
                                                    jint method, jfloat losslessQuality) {
    (void) cls;
    if (argb == NULL || width <= 0 || height <= 0) return NULL;
    jsize len = (*env)->GetArrayLength(env, argb);
    if (len < (jsize) width * (jsize) height) return NULL;

    WebPConfig config;
    if (!WebPConfigInit(&config)) return NULL;
    applyPreset(&config, lossless, quality, method, losslessQuality);
    config.thread_level = 1;

    WebPPicture picture;
    if (!WebPPictureInit(&picture)) return NULL;
    picture.use_argb     = 1;
    picture.width        = width;
    picture.height       = height;
    picture.argb_stride  = width;

    WebPMemoryWriter writer;
    WebPMemoryWriterInit(&writer);
    picture.writer       = WebPMemoryWrite;
    picture.custom_ptr   = &writer;

    /* Pin only for the duration of WebPEncode. Outside the critical region
     * we may safely allocate/grow the output writer (which calls malloc). */
    jint* pixels = (jint*) (*env)->GetPrimitiveArrayCritical(env, argb, NULL);
    if (pixels == NULL) {
        WebPMemoryWriterClear(&writer);
        return NULL;
    }
    picture.argb = (uint32_t*) pixels;

    int ok = WebPEncode(&config, &picture);

    /* Picture's argb pointer was externally owned; WebPPictureFree won't
     * touch it. Release the pin first so any post-encode malloc inside
     * picture cleanup doesn't run under critical-section restrictions. */
    (*env)->ReleasePrimitiveArrayCritical(env, argb, pixels, JNI_ABORT);
    picture.argb = NULL;
    WebPPictureFree(&picture);

    if (!ok || writer.size == 0) {
        WebPMemoryWriterClear(&writer);
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, (jsize) writer.size);
    if (result == NULL) {
        WebPMemoryWriterClear(&writer);
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, result, 0, (jsize) writer.size, (const jbyte*) writer.mem);
    WebPMemoryWriterClear(&writer);
    return result;
}

/* ──────────────────────────────────────────────────────────────────
 *  Fallback path: byte[] RGBA. Used when the caller can't or won't
 *  supply a TYPE_INT_ARGB int[] (e.g. legacy unit tests, exotic
 *  BufferedImage subtypes). Same threading flag, just one more copy.
 * ────────────────────────────────────────────────────────────────── */

JNIEXPORT jbyteArray JNICALL
Java_gg_norisk_webp_internal_WebPNative_decodeRGBA(JNIEnv* env, jclass cls,
                                                    jbyteArray data, jintArray outDims) {
    (void) cls;
    if (data == NULL || outDims == NULL) return NULL;
    jsize len = (*env)->GetArrayLength(env, data);
    if (len <= 0) return NULL;
    if ((*env)->GetArrayLength(env, outDims) < 2) return NULL;

    jbyte* bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) return NULL;

    int width = 0, height = 0;
    if (!WebPGetInfo((const uint8_t*) bytes, (size_t) len, &width, &height)) {
        (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
        return NULL;
    }

    const jsize rgbaLen = (jsize) width * (jsize) height * 4;
    jbyteArray result = (*env)->NewByteArray(env, rgbaLen);
    if (result == NULL) {
        (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
        return NULL;
    }

    jbyte* outBytes = (*env)->GetByteArrayElements(env, result, NULL);
    if (outBytes == NULL) {
        (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
        return NULL;
    }

    WebPDecoderConfig config;
    WebPInitDecoderConfig(&config);
    config.options.use_threads     = 1;
    config.output.colorspace       = MODE_RGBA;
    config.output.u.RGBA.rgba      = (uint8_t*) outBytes;
    config.output.u.RGBA.size      = (size_t) rgbaLen;
    config.output.u.RGBA.stride    = width * 4;
    config.output.is_external_memory = 1;

    VP8StatusCode status = WebPDecode((const uint8_t*) bytes, (size_t) len, &config);

    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);

    if (status != VP8_STATUS_OK) {
        (*env)->ReleaseByteArrayElements(env, result, outBytes, JNI_ABORT);
        return NULL;
    }
    (*env)->ReleaseByteArrayElements(env, result, outBytes, 0);

    jint dims[2] = { width, height };
    (*env)->SetIntArrayRegion(env, outDims, 0, 2, dims);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_gg_norisk_webp_internal_WebPNative_encodeRGBA(JNIEnv* env, jclass cls,
                                                    jbyteArray rgba,
                                                    jint width, jint height,
                                                    jfloat quality, jboolean lossless,
                                                    jint method, jfloat losslessQuality) {
    (void) cls;
    if (rgba == NULL || width <= 0 || height <= 0) return NULL;
    jsize len = (*env)->GetArrayLength(env, rgba);
    if (len < (jsize) width * (jsize) height * 4) return NULL;

    WebPConfig config;
    if (!WebPConfigInit(&config)) return NULL;
    applyPreset(&config, lossless, quality, method, losslessQuality);
    config.thread_level = 1;

    WebPPicture picture;
    if (!WebPPictureInit(&picture)) return NULL;
    picture.use_argb = 1;
    picture.width    = width;
    picture.height   = height;

    WebPMemoryWriter writer;
    WebPMemoryWriterInit(&writer);
    picture.writer     = WebPMemoryWrite;
    picture.custom_ptr = &writer;

    jbyte* pixels = (*env)->GetByteArrayElements(env, rgba, NULL);
    if (pixels == NULL) {
        WebPMemoryWriterClear(&writer);
        return NULL;
    }

    /* Import RGBA into the picture (libwebp allocates internal ARGB plane,
     * converts byte order). Fallback path so a tiny extra copy is acceptable. */
    int imported = WebPPictureImportRGBA(&picture, (const uint8_t*) pixels, width * 4);
    (*env)->ReleaseByteArrayElements(env, rgba, pixels, JNI_ABORT);
    if (!imported) {
        WebPPictureFree(&picture);
        WebPMemoryWriterClear(&writer);
        return NULL;
    }

    int ok = WebPEncode(&config, &picture);
    WebPPictureFree(&picture);

    if (!ok || writer.size == 0) {
        WebPMemoryWriterClear(&writer);
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, (jsize) writer.size);
    if (result == NULL) {
        WebPMemoryWriterClear(&writer);
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, result, 0, (jsize) writer.size, (const jbyte*) writer.mem);
    WebPMemoryWriterClear(&writer);
    return result;
}

/* ──────────────────────────────────────────────────────────────────
 *  Animated WEBP decode (libwebpdemux)
 *
 *  The native handle wraps both the libwebp decoder AND a malloc'd copy
 *  of the input bytes — WebPAnimDecoder reads from its `WebPData` lazily
 *  during GetNext, so the underlying buffer must outlive the decoder.
 *
 *  Lifecycle: animDecoderOpen → animDecoderGetInfo → animDecoderHasMoreFrames
 *  / animDecoderNextFrame loop → animDecoderClose. animDecoderReset can be
 *  called at any time to restart iteration from frame 0 (e.g. for looped
 *  playback).
 * ────────────────────────────────────────────────────────────────── */

typedef struct {
    WebPAnimDecoder* decoder;
    uint8_t*         data;     /* owned: malloc'd copy of jbyteArray contents */
    size_t           dataLen;
} AnimDecoderHandle;

JNIEXPORT jlong JNICALL
Java_gg_norisk_webp_internal_WebPNative_animDecoderOpen(JNIEnv* env, jclass cls, jbyteArray data) {
    (void) cls;
    if (data == NULL) return 0;
    jsize len = (*env)->GetArrayLength(env, data);
    if (len <= 0) return 0;

    AnimDecoderHandle* handle = (AnimDecoderHandle*) malloc(sizeof(AnimDecoderHandle));
    if (handle == NULL) return 0;
    handle->decoder = NULL;
    handle->data    = (uint8_t*) malloc((size_t) len);
    handle->dataLen = (size_t) len;
    if (handle->data == NULL) {
        free(handle);
        return 0;
    }

    /* Copy bytes once, decoder reads from this buffer over its lifetime. */
    (*env)->GetByteArrayRegion(env, data, 0, len, (jbyte*) handle->data);

    WebPAnimDecoderOptions opts;
    if (!WebPAnimDecoderOptionsInit(&opts)) {
        free(handle->data);
        free(handle);
        return 0;
    }
    opts.color_mode  = MODE_BGRA;  /* matches BufferedImage.TYPE_INT_ARGB on LE */
    opts.use_threads = 1;

    WebPData webpData;
    webpData.bytes = handle->data;
    webpData.size  = handle->dataLen;

    handle->decoder = WebPAnimDecoderNew(&webpData, &opts);
    if (handle->decoder == NULL) {
        free(handle->data);
        free(handle);
        return 0;
    }
    return (jlong) (intptr_t) handle;
}

JNIEXPORT jintArray JNICALL
Java_gg_norisk_webp_internal_WebPNative_animDecoderGetInfo(JNIEnv* env, jclass cls, jlong handlePtr) {
    (void) cls;
    if (handlePtr == 0) return NULL;
    AnimDecoderHandle* handle = (AnimDecoderHandle*) (intptr_t) handlePtr;
    if (handle->decoder == NULL) return NULL;

    WebPAnimInfo info;
    if (!WebPAnimDecoderGetInfo(handle->decoder, &info)) return NULL;

    jintArray result = (*env)->NewIntArray(env, 5);
    if (result == NULL) return NULL;
    /* canvas_width, canvas_height, loop_count, bgcolor, frame_count */
    jint vals[5] = {
        (jint) info.canvas_width,
        (jint) info.canvas_height,
        (jint) info.loop_count,
        (jint) info.bgcolor,
        (jint) info.frame_count,
    };
    (*env)->SetIntArrayRegion(env, result, 0, 5, vals);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_gg_norisk_webp_internal_WebPNative_animDecoderHasMoreFrames(JNIEnv* env, jclass cls, jlong handlePtr) {
    (void) cls;
    if (handlePtr == 0) return JNI_FALSE;
    AnimDecoderHandle* handle = (AnimDecoderHandle*) (intptr_t) handlePtr;
    if (handle->decoder == NULL) return JNI_FALSE;
    return WebPAnimDecoderHasMoreFrames(handle->decoder) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Decode the next frame into the caller-supplied int[] (TYPE_INT_ARGB
 * backing — see decodeARGBInto for the BGRA-on-LE trick).
 *
 * @return end-timestamp of this frame in milliseconds (caller computes
 *         duration as ts_n - ts_(n-1)), or -1 on failure.
 */
JNIEXPORT jint JNICALL
Java_gg_norisk_webp_internal_WebPNative_animDecoderNextFrame(JNIEnv* env, jclass cls,
                                                              jlong handlePtr,
                                                              jintArray outPixels) {
    (void) cls;
    if (handlePtr == 0 || outPixels == NULL) return -1;
    AnimDecoderHandle* handle = (AnimDecoderHandle*) (intptr_t) handlePtr;
    if (handle->decoder == NULL) return -1;

    WebPAnimInfo info;
    if (!WebPAnimDecoderGetInfo(handle->decoder, &info)) return -1;
    const jsize needed = (jsize) info.canvas_width * (jsize) info.canvas_height;
    if ((*env)->GetArrayLength(env, outPixels) < needed) return -1;

    uint8_t* frameBuf = NULL;
    int timestamp = 0;
    if (!WebPAnimDecoderGetNext(handle->decoder, &frameBuf, &timestamp)) return -1;
    if (frameBuf == NULL) return -1;

    /* The decoder owns frameBuf and reuses it across GetNext calls, so
     * we must copy out before any subsequent call. On LE the BGRA bytes
     * coincide with ARGB int values for BufferedImage.TYPE_INT_ARGB. */
    jint* pixels = (jint*) (*env)->GetPrimitiveArrayCritical(env, outPixels, NULL);
    if (pixels == NULL) return -1;
    memcpy(pixels, frameBuf, (size_t) needed * 4);
    (*env)->ReleasePrimitiveArrayCritical(env, outPixels, pixels, 0);

    return (jint) timestamp;
}

JNIEXPORT void JNICALL
Java_gg_norisk_webp_internal_WebPNative_animDecoderReset(JNIEnv* env, jclass cls, jlong handlePtr) {
    (void) env;
    (void) cls;
    if (handlePtr == 0) return;
    AnimDecoderHandle* handle = (AnimDecoderHandle*) (intptr_t) handlePtr;
    if (handle->decoder == NULL) return;
    WebPAnimDecoderReset(handle->decoder);
}

JNIEXPORT void JNICALL
Java_gg_norisk_webp_internal_WebPNative_animDecoderClose(JNIEnv* env, jclass cls, jlong handlePtr) {
    (void) env;
    (void) cls;
    if (handlePtr == 0) return;
    AnimDecoderHandle* handle = (AnimDecoderHandle*) (intptr_t) handlePtr;
    if (handle->decoder != NULL) {
        WebPAnimDecoderDelete(handle->decoder);
        handle->decoder = NULL;
    }
    if (handle->data != NULL) {
        free(handle->data);
        handle->data = NULL;
    }
    free(handle);
}
