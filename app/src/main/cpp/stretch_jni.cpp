// JNI bridge for org.dancesonic.player.playback.stretch.NativeTimeStretcher.
// Audio is passed in direct ByteBuffers of native-order 16-bit PCM, so nothing is copied across JNI.

#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <new>
#include <vector>

#include "stream_stretcher.h"

using dancesonic::StreamStretcher;

namespace {
StreamStretcher *fromHandle(jlong handle) { return reinterpret_cast<StreamStretcher *>(handle); }

int16_t *bufferAt(JNIEnv *env, jobject buffer, jint byteOffset) {
    auto *base = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
    return base == nullptr ? nullptr : reinterpret_cast<int16_t *>(base + byteOffset);
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_dancesonic_player_playback_stretch_NativeTimeStretcher_nativeCreate(
    JNIEnv *, jobject, jint channels, jint sampleRate) {
    return reinterpret_cast<jlong>(new (std::nothrow) StreamStretcher(channels, sampleRate));
}

JNIEXPORT void JNICALL
Java_org_dancesonic_player_playback_stretch_NativeTimeStretcher_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    delete fromHandle(handle);
}

JNIEXPORT void JNICALL
Java_org_dancesonic_player_playback_stretch_NativeTimeStretcher_nativeSetPlan(
    JNIEnv *env, jobject, jlong handle, jint blockSamples, jint intervalSamples, jlongArray breakIn,
    jdoubleArray breakOut) {
    jsize count = std::min(env->GetArrayLength(breakIn), env->GetArrayLength(breakOut));
    std::vector<int64_t> in(static_cast<size_t>(count));
    std::vector<double> out(static_cast<size_t>(count));
    if (count > 0) {
        env->GetLongArrayRegion(breakIn, 0, count, reinterpret_cast<jlong *>(in.data()));
        env->GetDoubleArrayRegion(breakOut, 0, count, out.data());
    }
    fromHandle(handle)->setPlan(blockSamples, intervalSamples, in.data(), out.data(), int(count));
}

JNIEXPORT void JNICALL
Java_org_dancesonic_player_playback_stretch_NativeTimeStretcher_nativeStart(
    JNIEnv *, jobject, jlong handle, jfloat speed, jfloat pitch) {
    fromHandle(handle)->start(speed, pitch);
}

JNIEXPORT jboolean JNICALL
Java_org_dancesonic_player_playback_stretch_NativeTimeStretcher_nativeQueueInput(
    JNIEnv *env, jobject, jlong handle, jobject buffer, jint byteOffset, jint frames) {
    int16_t *samples = bufferAt(env, buffer, byteOffset);
    if (samples == nullptr) return JNI_FALSE;
    fromHandle(handle)->queueInput(samples, frames);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_dancesonic_player_playback_stretch_NativeTimeStretcher_nativeQueueEndOfStream(
    JNIEnv *, jobject, jlong handle) {
    fromHandle(handle)->queueEndOfStream();
}

JNIEXPORT jint JNICALL
Java_org_dancesonic_player_playback_stretch_NativeTimeStretcher_nativeAvailableFrames(
    JNIEnv *, jobject, jlong handle) {
    return fromHandle(handle)->availableFrames();
}

JNIEXPORT jint JNICALL
Java_org_dancesonic_player_playback_stretch_NativeTimeStretcher_nativeReadOutput(
    JNIEnv *env, jobject, jlong handle, jobject buffer, jint byteOffset, jint maxFrames) {
    int16_t *samples = bufferAt(env, buffer, byteOffset);
    if (samples == nullptr) return -1;
    return fromHandle(handle)->readOutput(samples, maxFrames);
}

JNIEXPORT jboolean JNICALL
Java_org_dancesonic_player_playback_stretch_NativeTimeStretcher_nativeIsEnded(JNIEnv *, jobject, jlong handle) {
    return fromHandle(handle)->isEnded() ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
