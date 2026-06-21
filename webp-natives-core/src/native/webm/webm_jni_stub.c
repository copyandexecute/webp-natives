#include <jni.h>


JNIEXPORT jbyteArray JNICALL
Java_gg_norisk_webm_internal_WebMNative_encodeVp9(JNIEnv* env, jclass cls,
                                                   jint width, jint height,
                                                   jobjectArray frameArrays,
                                                   jintArray durationsMs,
                                                   jint cpuUsed, jint bitrateKbps) {
    (void) env; (void) cls; (void) width; (void) height;
    (void) frameArrays; (void) durationsMs; (void) cpuUsed; (void) bitrateKbps;
    return NULL;
}

JNIEXPORT jlong JNICALL
Java_gg_norisk_webm_internal_WebMNative_decodeOpen(JNIEnv* env, jclass cls, jbyteArray data) {
    (void) env; (void) cls; (void) data;
    return 0;
}

JNIEXPORT jintArray JNICALL
Java_gg_norisk_webm_internal_WebMNative_decodeGetInfo(JNIEnv* env, jclass cls, jlong handlePtr) {
    (void) env; (void) cls; (void) handlePtr;
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_gg_norisk_webm_internal_WebMNative_decodeHasMoreFrames(JNIEnv* env, jclass cls, jlong handlePtr) {
    (void) env; (void) cls; (void) handlePtr;
    return JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_gg_norisk_webm_internal_WebMNative_decodeNextFrame(JNIEnv* env, jclass cls, jlong handlePtr) {
    (void) env; (void) cls; (void) handlePtr;
    return NULL;
}

JNIEXPORT void JNICALL
Java_gg_norisk_webm_internal_WebMNative_decodeClose(JNIEnv* env, jclass cls, jlong handlePtr) {
    (void) env; (void) cls; (void) handlePtr;
}
