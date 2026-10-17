// webrtc-sdk/jni/webrtc_apm_jni.cc
//
// JNI bridge for the WebRTC AudioProcessing (AEC3) module - the native half of
// the SoftwareAec seam (REQ-07, REQ-09, REQ-10, D-02). Compiled into
// libwebrtc_apm.so by webrtc-build/build.sh: the file is injected into the
// pinned WebRTC checkout at build time so the checkout stays pristine.
//
// Kotlin contract (NativeSoftwareAec, T-06):
//   init(sampleRateHz)                          -> long handle
//   processCapture(handle, in, out)             -> void (10 ms frames)
//   processReference(handle, in)                -> void (10 ms frames)
//   setDelayMs(handle, ms)                      -> void
//   close(handle)                               -> void
//
// The APM is not thread-safe (REQ-06): this bridge serializes every call on a
// per-session mutex as a belt-and-braces backstop; the authoritative lock is
// SerializedSoftwareAec on the Kotlin side.
//
// Frame contract: the APM processes 10 ms frames (sampleRateHz/100 samples,
// e.g. 160 samples = 320 bytes at 16 kHz). processCapture/processReference
// accept any input length; incomplete trailing samples are buffered until the
// next call completes a frame. Capture input is expected to be whole 10 ms
// frames (MicPipeline emits 20 ms chunks); reference chunks may be arbitrary.
//
// API shape targets the pinned branch (refs/branch-heads/8009): this branch's
// AudioProcessing is created through BuiltinAudioProcessingBuilder + an
// Environment, configured via AudioProcessing::Config (no AudioProcessingConfig
// / AudioProcessingBuilder yet - those are later-branch APIs).

#include <jni.h>

#include <android/log.h>

// The JNI bridge uses pointer arithmetic on JNI byte arrays and buffered
// vectors (resize-guaranteed - the bounds come from the Java array length).
// The unsafe-buffers clang plugin flags this, but the arithmetic is safe in
// every site. Opt out for this file (docs/unsafe_buffers.md).
#pragma allow_unsafe_buffers

#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <vector>

#include "api/audio/audio_processing.h"
#include "api/audio/builtin_audio_processing_builder.h"
#include "api/environment/environment_factory.h"


#define TAG "webrtc_apm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct ApmSession {
  webrtc::scoped_refptr<webrtc::AudioProcessing> apm;
  int sample_rate_hz = 0;
  int frame_samples = 0;  // 10 ms frame: rate / 100
  int frame_bytes = 0;    // frame_samples * sizeof(int16_t)
  std::vector<int16_t> capture_pending;
  std::vector<int16_t> reference_pending;
  std::mutex mutex;
};

webrtc::scoped_refptr<webrtc::AudioProcessing> CreateApm(int sample_rate_hz) {
  webrtc::AudioProcessing::Config config;
  config.echo_canceller.enabled = true;  // AEC3, default (non-mobile) config
  // High-pass filter: enforced by the echo canceller config by default.
  // Noise suppression stays off: barge-in is double-talk, and the residual
  // suppression AEC3 bundles is enough until T-11 tunes the config on-device.

  webrtc::BuiltinAudioProcessingBuilder builder;
  builder.SetConfig(config);
  webrtc::scoped_refptr<webrtc::AudioProcessing> apm =
      builder.Build(webrtc::CreateEnvironment());
  if (!apm) {
    LOGE("BuiltinAudioProcessingBuilder().Build() failed");
    return nullptr;
  }

  const webrtc::StreamConfig stream(sample_rate_hz, 1);
  const webrtc::ProcessingConfig processing_config{stream, stream, stream,
                                                   stream};
  if (apm->Initialize(processing_config) != 0) {
    LOGE("AudioProcessing::Initialize failed");
    return nullptr;
  }
  return apm;
}

ApmSession* Session(jlong handle) {
  return reinterpret_cast<ApmSession*>(handle);
}

// Process all complete 10 ms frames buffered in |pending|, feeding each to
// |feed| (one APM call per frame), keeping the trailing partial samples.
template <typename Feed>
void DrainFrames(std::vector<int16_t>& pending, ApmSession& session,
                 Feed feed) {
  const size_t frame = static_cast<size_t>(session.frame_samples);
  while (pending.size() >= frame) {
    int16_t* block = pending.data();
    feed(block);
    pending.erase(pending.begin(),
                  pending.begin() + static_cast<ptrdiff_t>(frame));
  }
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
  (void)vm;
  (void)reserved;
  LOGI("libwebrtc_apm loaded");
  return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
  (void)vm;
  (void)reserved;
  LOGI("libwebrtc_apm unloaded");
}

// init(sampleRateHz) -> handle (0 on failure).
JNIEXPORT jlong JNICALL
Java_com_hermesconsole_audio_NativeSoftwareAec_init(JNIEnv* env, jclass clazz,
                                                    jint sample_rate_hz) {
  (void)env;
  (void)clazz;
  if (sample_rate_hz != 16000 && sample_rate_hz != 32000 &&
      sample_rate_hz != 48000 && sample_rate_hz != 8000) {
    LOGE("unsupported sample rate: %d (APM int16 rates are 8/16/32/48 kHz)",
         sample_rate_hz);
    return 0;
  }
  auto session = std::make_unique<ApmSession>();
  session->apm = CreateApm(sample_rate_hz);
  if (!session->apm) {
    return 0;
  }
  session->sample_rate_hz = sample_rate_hz;
  session->frame_samples = sample_rate_hz / 100;  // 10 ms
  session->frame_bytes =
      session->frame_samples * static_cast<int>(sizeof(int16_t));
  session->capture_pending.reserve(
      2 * static_cast<size_t>(session->frame_samples));
  session->reference_pending.reserve(
      2 * static_cast<size_t>(session->frame_samples));
  LOGI("APM initialized: %d Hz, %d samples per 10 ms frame", sample_rate_hz,
       session->frame_samples);
  return reinterpret_cast<jlong>(session.release());
}

// processCapture(handle, in, out): 16 kHz int16 mono capture through
// ProcessStream; out must be at least as long as in. Only whole 10 ms frames
// are processed; a trailing partial stays buffered for the next call.
JNIEXPORT void JNICALL
Java_com_hermesconsole_audio_NativeSoftwareAec_processCapture(
    JNIEnv* env, jclass clazz, jlong handle, jbyteArray in, jbyteArray out) {
  (void)clazz;
  ApmSession* session = Session(handle);
  if (!session) {
    return;
  }
  std::lock_guard<std::mutex> lock(session->mutex);

  const jsize in_len = env->GetArrayLength(in);
  jbyte* in_ptr = env->GetByteArrayElements(in, nullptr);
  if (in_ptr == nullptr) {
    return;
  }
  jbyte* out_ptr = env->GetByteArrayElements(out, nullptr);
  if (out_ptr == nullptr) {
    env->ReleaseByteArrayElements(in, in_ptr, JNI_ABORT);
    return;
  }

  const size_t prev = session->capture_pending.size();
  session->capture_pending.resize(prev + static_cast<size_t>(in_len / 2));
  std::memcpy(session->capture_pending.data() + prev, in_ptr,
              static_cast<size_t>(in_len));

  const webrtc::StreamConfig config(session->sample_rate_hz, 1);
  size_t out_pos = 0;
  DrainFrames(session->capture_pending, *session, [&](int16_t* block) {
    session->apm->ProcessStream(block, config, config, block);
    std::memcpy(out_ptr + out_pos, block,
                static_cast<size_t>(session->frame_bytes));
    out_pos += static_cast<size_t>(session->frame_bytes);
  });

  env->ReleaseByteArrayElements(out, out_ptr, 0);
  env->ReleaseByteArrayElements(in, in_ptr, JNI_ABORT);
}

// processReference(handle, in): the far-end reference (the exact PCM written to
// the sink, resampled to 16 kHz) through ProcessReverseStream.
JNIEXPORT void JNICALL
Java_com_hermesconsole_audio_NativeSoftwareAec_processReference(
    JNIEnv* env, jclass clazz, jlong handle, jbyteArray in) {
  (void)clazz;
  ApmSession* session = Session(handle);
  if (!session) {
    return;
  }
  std::lock_guard<std::mutex> lock(session->mutex);

  const jsize in_len = env->GetArrayLength(in);
  jbyte* in_ptr = env->GetByteArrayElements(in, nullptr);
  if (in_ptr == nullptr) {
    return;
  }

  const size_t prev = session->reference_pending.size();
  session->reference_pending.resize(prev + static_cast<size_t>(in_len / 2));
  std::memcpy(session->reference_pending.data() + prev, in_ptr,
              static_cast<size_t>(in_len));

  const webrtc::StreamConfig config(session->sample_rate_hz, 1);
  DrainFrames(session->reference_pending, *session, [&](int16_t* block) {
    session->apm->ProcessReverseStream(block, config, config, block);
  });

  env->ReleaseByteArrayElements(in, in_ptr, JNI_ABORT);
}

// setDelayMs(handle, ms): seed the APM delay controller with the measured
// output-path latency (REQ-05). Must be called before ProcessStream.
JNIEXPORT void JNICALL
Java_com_hermesconsole_audio_NativeSoftwareAec_setDelayMs(JNIEnv* env,
                                                          jclass clazz,
                                                          jlong handle,
                                                          jint delay_ms) {
  (void)env;
  (void)clazz;
  ApmSession* session = Session(handle);
  if (!session) {
    return;
  }
  std::lock_guard<std::mutex> lock(session->mutex);
  session->apm->set_stream_delay_ms(delay_ms);
}

// close(handle): destroy the session.
JNIEXPORT void JNICALL Java_com_hermesconsole_audio_NativeSoftwareAec_close(
    JNIEnv* env, jclass clazz, jlong handle) {
  (void)env;
  (void)clazz;
  delete Session(handle);
}

}  // extern "C"
