package com.n9nik.audiocutter.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Waveform peaks (0..1) plus duration, decoded off the UI thread. */
data class Waveform(val peaks: FloatArray, val durationMs: Long)

/**
 * Decodes audio to PCM and computes per-bucket peak amplitudes.
 * Streaming: never holds the whole file in memory.
 */
object WaveformExtractor {

    fun extract(context: Context, uri: Uri, buckets: Int = 160): Waveform? {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var mime: String? = null
            var sampleRate = 44100
            var channels = 2
            var durationUs = 0L
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME)
                if (m != null && m.startsWith("audio/")) {
                    trackIndex = i
                    mime = m
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    if (f.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = f.getLong(MediaFormat.KEY_DURATION)
                    }
                    break
                }
            }
            if (trackIndex < 0 || mime == null) return null

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(extractor.getTrackFormat(trackIndex), null, null, 0)
            decoder.start()
            extractor.selectTrack(trackIndex)

            val bucketPeaks = FloatArray(buckets)
            val bucketCounts = IntArray(buckets)
            var totalFrames = 0L
            // Estimate total frames for bucket mapping; fall back to growing estimate.
            var estTotalFrames = if (durationUs > 0) durationUs * sampleRate / 1_000_000 else 0L

            val info = MediaCodec.BufferInfo()
            var decoderDone = false
            while (!decoderDone) {
                val inIdx = decoder.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = decoder.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(inBuf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        decoderDone = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                var outIdx = decoder.dequeueOutputBuffer(info, 10_000)
                while (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)!!
                    if (info.size > 0) {
                        val dup = outBuf.duplicate().order(ByteOrder.nativeOrder())
                        val shorts = info.size / 2
                        var peak = 0
                        var i = 0
                        while (i < shorts) {
                            val s = abs(dup.short.toInt())
                            if (s > peak) peak = s
                            i += channels // sample one channel per frame; plenty for a waveform
                        }
                        val frames = shorts / channels
                        // Assign this chunk's peak across the buckets it spans.
                        val startFrame = totalFrames
                        totalFrames += frames
                        if (estTotalFrames <= 0) estTotalFrames = totalFrames * 2
                        val b0 = (startFrame * buckets / estTotalFrames.coerceAtLeast(1))
                            .coerceIn(0L, (buckets - 1).toLong()).toInt()
                        val b1 = ((startFrame + frames) * buckets / estTotalFrames.coerceAtLeast(1))
                            .coerceIn(0L, (buckets - 1).toLong()).toInt()
                        val norm = peak / 32768f
                        for (b in b0..b1) {
                            if (norm > bucketPeaks[b]) bucketPeaks[b] = norm
                            bucketCounts[b]++
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        decoderDone = true
                    }
                    outIdx = decoder.dequeueOutputBuffer(info, 0)
                }
            }

            // Normalize against the global max for a full-height waveform.
            val max = bucketPeaks.maxOrNull() ?: 0f
            val normalized = if (max > 0.01f) {
                FloatArray(buckets) { (bucketPeaks[it] / max).coerceIn(0.02f, 1f) }
            } else {
                FloatArray(buckets) { 0.02f }
            }
            val durationMs = if (durationUs > 0) durationUs / 1000
            else totalFrames * 1000 / sampleRate.coerceAtLeast(1)
            return Waveform(normalized, durationMs)
        } catch (_: Exception) {
            return null
        } finally {
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }
}
