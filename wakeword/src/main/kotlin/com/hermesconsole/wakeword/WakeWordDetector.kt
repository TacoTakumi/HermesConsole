package com.hermesconsole.wakeword

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Detects "hey computer" fully on-device (REQ-01) with sherpa-onnx's KeywordSpotter.
 *
 * Nothing here can reach the network - there is no HTTP client, no socket, no URL: the model is
 * a bundled asset and the whole detection path is local. That is the proof REQ-01 asks for, and
 * it holds for every run rather than for one run with the radio switched off.
 *
 * Feed it the same AEC-processed capture stream the voice loop uses; it takes raw 16 kHz mono
 * PCM16 frames and knows nothing about where they came from, so this module depends on no other
 * module in the project.
 */
class WakeWordDetector(
    context: Context,
    private val phrase: String = WAKE_PHRASE,
    private val captureGain: Float = CAPTURE_GAIN,
    keywordsScore: Float = KEYWORDS_SCORE,
    keywordsThreshold: Float = KEYWORDS_THRESHOLD,
) : WakeWordSource {

    init {
        // sherpa-onnx's native asset loader calls exit(-1) on a missing file - it takes the whole
        // process down, uncatchably. Pre-flighting here turns a forgotten fetch script into an
        // ordinary exception instead of a silent death.
        for (asset in listOf(ENCODER, DECODER, JOINER, TOKENS, KEYWORDS)) {
            try {
                context.assets.open(asset).close()
            } catch (e: java.io.IOException) {
                throw IllegalStateException(
                    "KWS asset missing: $asset - run scripts/fetch-wakeword-assets.sh",
                    e,
                )
            }
        }
    }

    private val spotter = KeywordSpotter(
        assetManager = context.assets,
        config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = ENCODER,
                    decoder = DECODER,
                    joiner = JOINER,
                ),
                tokens = TOKENS,
                modelType = "zipformer2",
            ),
            keywordsFile = KEYWORDS,
            keywordsScore = keywordsScore,
            keywordsThreshold = keywordsThreshold,
        ),
    )

    private val stream: OnlineStream = spotter.createStream().also {
        // ptr == 0 means the keyword failed to encode against the model's vocabulary - usually
        // keywords.txt holding raw text instead of pre-tokenized BPE. Using the stream anyway
        // segfaults in native code, so fail loudly here instead.
        check(it.ptr != 0L) {
            "KWS stream creation failed - is keywords.txt pre-tokenized BPE for this model?"
        }
    }

    override fun detections(frames: Flow<ByteArray>): Flow<WakeWord> = flow {
        frames.collect { frame ->
            val samples = toFloatSamples(frame, captureGain)
            if (samples.isEmpty()) return@collect

            stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
            while (spotter.isReady(stream)) {
                spotter.decode(stream)
                val keyword = spotter.getResult(stream).keyword
                if (keyword.isNotBlank()) {
                    // Mandatory after every hit: without it the decoder keeps the hypothesis and
                    // re-fires on the following frames.
                    spotter.reset(stream)
                    Log.i(TAG, "wake word: $keyword")
                    emit(WakeWord(phrase = phrase, atMillis = System.currentTimeMillis()))
                }
            }
        }
    }

    override fun reset() = spotter.reset(stream)

    override fun close() {
        stream.release()
        spotter.release()
    }

    companion object {
        /**
         * Makeup gain on the capture stream, and the reason this detector works at 2 m.
         *
         * The voice-communication path delivers a voice at 2 m at around -25 dBFS; the KWS zipformer
         * was trained near full scale. Fed the stream as it arrives, the model heard "computer" 4
         * times in 10 - and no amount of threshold or model swapping fixed that, because the problem
         * was never sensitivity. Amplified, the same recording scored 9 in 10 (T-33).
         *
         * 6x sits in the middle of a broad plateau, not on a peak: every gain from 2x to 8x scored
         * 8-10 of 10 on the recorded session. That flatness is the point - a value that only worked
         * at exactly 6x would be a fit to one recording rather than a fix.
         */
        const val CAPTURE_GAIN: Float = 6.0f

        /**
         * Sensitivity. The score is sherpa-onnx's own default: raising it was measured and made
         * detection WORSE. The threshold is lowered a little from the library's 0.25, which bought
         * the last utterance or two without ever firing in an empty room during the sweep.
         *
         * Leaning further is tempting and wrong. False accepts are not free - the console must never
         * wake on its own TTS (REQ-06) - and past this point the extra hits came bundled with them.
         */
        const val KEYWORDS_SCORE: Float = 1.5f
        const val KEYWORDS_THRESHOLD: Float = 0.15f

        private const val TAG = "WakeWord"
        private const val SAMPLE_RATE = 16_000
        private const val ENCODER = "kws/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val DECODER = "kws/decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        private const val JOINER = "kws/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val TOKENS = "kws/tokens.txt"
        private const val KEYWORDS = "kws/keywords.txt"
    }
}

/**
 * 16-bit LE PCM bytes to the normalised float samples sherpa-onnx expects, with [gain] applied.
 *
 * The gain is not a volume control - it is what makes the model work at all in this room. The
 * voice-communication capture path hands us a voice at 2 m at roughly -25 dBFS, and the KWS
 * zipformer was trained on audio far closer to full scale; fed the quiet original it recognised
 * "computer" 4 times in 10, and fed the same recording amplified it recognised 9 (T-33). Clamped
 * rather than scaled, because a close talker must not wrap around into noise.
 */
fun toFloatSamples(pcm: ByteArray, gain: Float = 1.0f): FloatArray {
    val count = pcm.size / 2
    val samples = FloatArray(count)
    for (i in 0 until count) {
        val sample = ((pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 0xFF)).toShort()
        samples[i] = (sample / 32768.0f * gain).coerceIn(-1.0f, 1.0f)
    }
    return samples
}
