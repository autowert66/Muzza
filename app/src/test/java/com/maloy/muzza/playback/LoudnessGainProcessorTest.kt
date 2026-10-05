package com.maloy.muzza.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

class LoudnessGainProcessorTest {

    private val pcm16 = AudioFormat(
        /* sampleRate= */ 44100,
        /* channelCount= */ 2,
        /* encoding= */ C.ENCODING_PCM_16BIT,
    )

    private fun process(
        processor: LoudnessGainProcessor,
        samples: ShortArray,
        configure: Boolean = true,
    ): ShortArray {
        if (configure) {
            val out = processor.configure(pcm16)
            assertTrue("processor should accept 16-bit PCM", out != AudioFormat.NOT_SET)
        }
        val input = ByteBuffer.allocateDirect(samples.size * 2)
            .order(ByteOrder.nativeOrder())
        samples.forEach { input.putShort(it) }
        input.flip()
        processor.queueInput(input)
        // The AudioProcessor contract requires the input position to advance by the bytes
        // consumed. DefaultAudioSink re-queues the same buffer while hasRemaining() is true, so
        // failing to consume it spins the audio thread forever.
        assertFalse(
            "queueInput must consume the whole input buffer",
            input.hasRemaining(),
        )
        val result = processor.getOutput()
        val shorts = ShortArray(result.remaining() / 2)
        for (i in shorts.indices) shorts[i] = result.getShort()
        return shorts
    }

    @Test
    fun `declines formats other than 16-bit PCM so media3 passes audio through`() {
        val processor = LoudnessGainProcessor()
        val floatFormat = AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT)
        assertEquals(AudioFormat.NOT_SET, processor.configure(floatFormat))
        assertFalse(processor.isActive)
    }

    @Test
    fun `zero gain leaves samples untouched`() {
        val processor = LoudnessGainProcessor()
        processor.targetGainDb = 0f
        val input = shortArrayOf(0, 1000, -1000, Short.MAX_VALUE, Short.MIN_VALUE)
        assertTrue(input.contentEquals(process(processor, input)))
    }

    @Test
    fun `negative gain attenuates by the expected ratio`() {
        val processor = LoudnessGainProcessor()
        processor.targetGainDb = -6f
        // -6 dB is a linear factor of 10^(-6/20) ~= 0.5012.
        val amplitude = 10.0.pow((-6f / 20f).toDouble()).toFloat()
        val input = shortArrayOf(1000, -1000, 20000)
        val out = process(processor, input)
        for (i in input.indices) {
            val expected = (input[i] * amplitude).toInt()
            assertTrue(
                "sample $i: expected ~$expected, got ${out[i]}",
                kotlin.math.abs(out[i] - expected) <= 1,
            )
        }
    }

    @Test
    fun `positive gain saturates instead of wrapping`() {
        val processor = LoudnessGainProcessor()
        processor.targetGainDb = 24f
        val input = shortArrayOf(10000, -10000, Short.MAX_VALUE, Short.MIN_VALUE)
        val out = process(processor, input)
        // Wrapping would turn a large positive sample negative; saturating clamps to MAX/MIN.
        assertEquals(Short.MAX_VALUE.toInt(), out[0].toInt())
        assertEquals(Short.MIN_VALUE.toInt(), out[1].toInt())
        assertEquals(Short.MAX_VALUE.toInt(), out[2].toInt())
        assertEquals(Short.MIN_VALUE.toInt(), out[3].toInt())
    }

    @Test
    fun `gain is clamped to the supported range`() {
        val processor = LoudnessGainProcessor()
        processor.targetGainDb = 100_000f
        assertEquals(800f, processor.targetGainDb, 0.001f)
        processor.targetGainDb = -100_000f
        assertEquals(-800f, processor.targetGainDb, 0.001f)
    }

    @Test
    fun `sample count is preserved`() {
        val processor = LoudnessGainProcessor()
        processor.targetGainDb = 3f
        val input = ShortArray(1024) { (it * 7 - 3000).toShort() }
        assertEquals(input.size, process(processor, input).size)
    }

    @Test
    fun `empty input at unity gain does not copy a buffer onto itself`() {
        val processor = LoudnessGainProcessor()
        processor.targetGainDb = 0f
        assertTrue(
            "processor should accept 16-bit PCM",
            processor.configure(pcm16) != AudioFormat.NOT_SET,
        )

        // AudioProcessingPipeline queues the shared static AudioProcessor.EMPTY_BUFFER on its
        // first getOutput(), before any real audio arrives. replaceOutputBuffer(0) hands back
        // that same instance, so an unguarded out.put(data) throws "The source buffer is this
        // buffer" and fails playback. queueInput must survive it and emit nothing.
        val empty = AudioProcessor.EMPTY_BUFFER
        processor.queueInput(empty)
        assertFalse(empty.hasRemaining())
        assertFalse("empty input must produce no output", processor.getOutput().hasRemaining())
    }
}