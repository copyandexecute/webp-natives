/*
 * webp-natives — JNI bridge to libwebp.
 *
 * Java side:  gg.norisk.webp.internal.WebPNative
 * Symbols:    Java_gg_norisk_webp_internal_WebPNative_<method>
 */

#include <jni.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "webp/decode.h"
#include "webp/encode.h"

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

JNIEXPORT jbyteArray JNICALL
Java_gg_norisk_webp_internal_WebPNative_decodeRGBA(JNIEnv* env, jclass cls, jbyteArray data, jintArray outDims) {
    (void) cls;
    if (data == NULL || outDims == NULL) return NULL;
    jsize len = (*env)->GetArrayLength(env, data);
    if (len <= 0) return NULL;
    if ((*env)->GetArrayLength(env, outDims) < 2) return NULL;

    jbyte* bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) return NULL;

    int width = 0, height = 0;
    uint8_t* rgba = WebPDecodeRGBA((const uint8_t*) bytes, (size_t) len, &width, &height);

    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);

    if (rgba == NULL) return NULL;

    const jsize rgbaLen = (jsize) width * (jsize) height * 4;
    jbyteArray result = (*env)->NewByteArray(env, rgbaLen);
    if (result == NULL) {
        WebPFree(rgba);
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, result, 0, rgbaLen, (const jbyte*) rgba);
    WebPFree(rgba);

    jint dims[2] = { width, height };
    (*env)->SetIntArrayRegion(env, outDims, 0, 2, dims);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_gg_norisk_webp_internal_WebPNative_encodeRGBA(JNIEnv* env, jclass cls,
                                                   jbyteArray rgba,
                                                   jint width, jint height,
                                                   jfloat quality, jboolean lossless) {
    (void) cls;
    if (rgba == NULL || width <= 0 || height <= 0) return NULL;
    jsize len = (*env)->GetArrayLength(env, rgba);
    if (len < (jsize) width * (jsize) height * 4) return NULL;

    jbyte* pixels = (*env)->GetByteArrayElements(env, rgba, NULL);
    if (pixels == NULL) return NULL;

    uint8_t* output = NULL;
    size_t outLen;
    const int stride = width * 4;
    if (lossless == JNI_TRUE) {
        outLen = WebPEncodeLosslessRGBA((const uint8_t*) pixels, width, height, stride, &output);
    } else {
        outLen = WebPEncodeRGBA((const uint8_t*) pixels, width, height, stride, quality, &output);
    }

    (*env)->ReleaseByteArrayElements(env, rgba, pixels, JNI_ABORT);

    if (outLen == 0 || output == NULL) {
        if (output != NULL) WebPFree(output);
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, (jsize) outLen);
    if (result == NULL) {
        WebPFree(output);
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, result, 0, (jsize) outLen, (const jbyte*) output);
    WebPFree(output);
    return result;
}
