// JNI bridge to RNNoise: removes background noise from 48 kHz mono 16-bit speech.
#include <jni.h>
#include <stdlib.h>
#include "rnnoise.h"

#define FRAME 480 /* 10 ms at 48 kHz */

JNIEXPORT jlong JNICALL
Java_com_backdropcam_VoiceIsolation_nativeCreate(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return (jlong) (intptr_t) rnnoise_create(NULL);
}

JNIEXPORT void JNICALL
Java_com_backdropcam_VoiceIsolation_nativeDestroy(JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    if (handle) rnnoise_destroy((DenoiseState *) (intptr_t) handle);
}

/* Cleans `frames` whole 480-sample frames of `pcm` in place. */
JNIEXPORT void JNICALL
Java_com_backdropcam_VoiceIsolation_nativeProcess(JNIEnv *env, jclass cls, jlong handle,
                                                  jshortArray pcm, jint frames) {
    (void) cls;
    if (!handle || frames <= 0) return;
    DenoiseState *st = (DenoiseState *) (intptr_t) handle;
    jshort *s = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (!s) return;
    float buf[FRAME];
    for (int f = 0; f < frames; f++) {
        jshort *p = s + f * FRAME;
        for (int i = 0; i < FRAME; i++) buf[i] = (float) p[i];
        rnnoise_process_frame(st, buf, buf);
        for (int i = 0; i < FRAME; i++) {
            float v = buf[i];
            p[i] = (jshort) (v > 32767.f ? 32767 : (v < -32768.f ? -32768 : (int) v));
        }
    }
    (*env)->ReleaseShortArrayElements(env, pcm, s, 0);
}
