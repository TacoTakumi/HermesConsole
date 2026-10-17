package com.hermesconsole.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext

/**
 * The capture path (REQ-02): AudioRecord on VOICE_COMMUNICATION - the only source that routes
 * through the platform's echo-cancelled voice pipeline - with an AcousticEchoCanceler attached to
 * the record session.
 *
 * The raw-mic source and the native low-latency capture APIs are deliberately absent: both bypass
 * the hardware echo canceller that barge-in depends on (D-03). A source scan enforces this.
 */
class MicPipeline(
    private val audioManager: AudioManager,
    override val config: AudioConfig = AudioConfig(),
) : AudioCapture {

    private var record: AudioRecord? = null
    private var canceler: AcousticEchoCanceler? = null
    private var suppressor: NoiseSuppressor? = null

    /** The audio mode in force before we switched to communication mode, restored on [close]. */
    private var priorAudioMode: Int? = null

    /** Capture devices currently attached, in the module's own vocabulary. */
    fun availableDevices(): List<AudioDeviceDescriptor> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.toDescriptor() }

    /**
     * Open the record session on the configured device and report the AEC verdict for it.
     * Called at startup, and again whenever the configured route changes (REQ-03).
     */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is held by the shell before the loop starts.
    fun start(): AecStatus {
        close()

        // Communication mode hands the hardware AEC a downlink reference for our playback (REQ-05,
        // docs/aec-spike.md "The tenth"). Set before the record session opens so the canceller binds
        // in the right mode. The prior mode is restored in [close].
        if (config.useCommunicationAudioMode) {
            priorAudioMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            Log.i(TAG, "audio mode -> MODE_IN_COMMUNICATION (was $priorAudioMode)")
        }

        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val selected = selectInputDevice(config.inputSource, devices.map { it.toDescriptor() })

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            config.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes(),
        )
        record = audioRecord

        // Pin capture only when the operator asked for a specific external device.
        //
        // Measured on the SM-X210 (T-04 diagnostic): setPreferredDevice on the built-in mic
        // BREAKS the voice-communication path - the stream is starved to a third of real time
        // and ~30 dB quieter, because that path does its own echo-cancelling routing (to a
        // different built-in mic than the first one enumerated) and forcing a device overrides it.
        // The default is therefore to let the platform route the voice pipeline itself.
        if (shouldPinDevice(config.inputSource)) {
            selected?.let { descriptor ->
                devices.firstOrNull { it.id == descriptor.id }
                    ?.let { audioRecord.setPreferredDevice(it) }
            }
        }

        val status = attachCanceler(audioRecord.audioSessionId, selected)
        applyNoiseSuppression(audioRecord.audioSessionId)
        audioRecord.startRecording()
        Log.i(TAG, status.describe())
        return status
    }

    /**
     * Take the platform's noise suppressor off the session when the config says so.
     *
     * The handle has to be created to be switched off - the effect is already running on this
     * session whether or not we asked for it, and creating it is how we get a say. Kept alive for
     * the life of the recorder: releasing it hands the session back to the platform's default.
     */
    private fun applyNoiseSuppression(sessionId: Int) {
        if (config.noiseSuppression || !NoiseSuppressor.isAvailable()) return
        val ns = runCatching { NoiseSuppressor.create(sessionId) }.getOrNull() ?: return
        suppressor = ns
        ns.setEnabled(false)
        Log.i(TAG, "noise suppression disabled (enabled=${ns.enabled})")
    }

    /**
     * Bind an AcousticEchoCanceler to this capture session and enable it. The returned status is
     * the ground truth for whether barge-in is safe on this device - it is reported, never assumed.
     */
    private fun attachCanceler(sessionId: Int, device: AudioDeviceDescriptor?): AecStatus {
        if (!AcousticEchoCanceler.isAvailable()) {
            return AecStatus(device = device, available = false, enabled = false)
        }
        val aec = runCatching { AcousticEchoCanceler.create(sessionId) }.getOrNull()
            ?: return AecStatus(device = device, available = false, enabled = false)

        canceler = aec
        aec.setEnabled(true)
        return AecStatus(device = device, available = true, enabled = aec.enabled)
    }

    override fun frames(): Flow<ByteArray> = flow {
        val audioRecord = requireNotNull(record) { "start() before collecting frames()" }
        val buffer = ByteArray(config.chunkBytes)
        while (currentCoroutineContext().isActive) {
            val read = audioRecord.read(buffer, 0, buffer.size)
            if (read <= 0) break
            emit(buffer.copyOf(read))
        }
    }

    override fun close() {
        canceler?.release()
        canceler = null
        suppressor?.release()
        suppressor = null
        record?.run {
            if (state == AudioRecord.STATE_INITIALIZED) stop()
            release()
        }
        record = null
        priorAudioMode?.let { audioManager.mode = it; priorAudioMode = null }
    }

    private fun bufferBytes(): Int {
        val min = AudioRecord.getMinBufferSize(
            config.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Four chunks of headroom so a slow collector doesn't drop frames.
        return maxOf(min, config.chunkBytes * 4)
    }

    private companion object {
        const val TAG = "MicPipeline"
    }
}

internal fun AudioDeviceInfo.toDescriptor(): AudioDeviceDescriptor = AudioDeviceDescriptor(
    id = id,
    type = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> DeviceType.BUILTIN_MIC
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        -> DeviceType.BUILTIN_SPEAKER
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        -> DeviceType.USB
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        -> DeviceType.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        -> DeviceType.WIRED_HEADSET
        else -> DeviceType.OTHER
    },
    name = productName.toString(),
)
