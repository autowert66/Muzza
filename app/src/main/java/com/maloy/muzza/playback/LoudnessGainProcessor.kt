package com.maloy.muzza.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Applies a constant gain to 16-bit PCM, in dB, as an [AudioProcessor] rather than an
 * `AudioEffect`.
 *
 * An offloaded `AudioTrack` renders on a different thread than the audio effect chain, so
 * audioflinger refuses to host a `LoudnessEnhancer` on it: it logs "effect Loudness Enhancer does
 * not support offload flags", migrates the effect chain, and tears the output stream down and
 * recreates it — an audible dropout (measured 444ms). An `AudioProcessor` runs before offload
 * encoding, so it works in both modes with no effect, no control-session broadcast and no flush.
 *
 * Sits first in the chain (see `createRenderersFactory`) so it sees the decoder's native 16-bit
 * PCM rather than SonicAudioProcessor's float output. Any other encoding is reported as
 * unhandled, which makes media3 treat this processor as inactive and pass audio through
 * untouched.
 */
class LoudnessGainProcessor : BaseAudioProcessor() {

    private companion object {
        const val MIN_GAIN_DB = -800f
        const val MAX_GAIN_DB = 800f
    }

    /** Linear amplitude currently in use, derived from [targetGainDb]. */
    private var amplitude = 1f

    /**
     * Target gain in dB, applied to the next buffer processed. Clamped to the same range the
     * platform `LoudnessEnhancer` was driven with.
     */
    @Volatile
    var targetGainDb: Float = 0f
        set(value) {
            val clamped = value.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
            if (clamped != field) {
                field = clamped
                // dBFS -> linear amplitude. Recomputed once per change, not per sample.
                amplitude = 10.0.pow((clamped / 20.0).toDouble()).toFloat()
            }
        }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat
        else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val data = inputBuffer.order(ByteOrder.nativeOrder())
        val shorts = data.asShortBuffer()
        val count = shorts.remaining()

        val out = replaceOutputBuffer(count * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until count) {
            val scaled = (shorts.get(i) * amplitude).toInt()
            // Saturate rather than wrap: a hot gain must not invert the waveform.
            out.putShort(scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        out.flip()
    }

    /** Zeroes the buffered output; nothing extra to do for a stateless per-sample gain. */
    override fun onFlush() = Unit
}