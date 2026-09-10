package com.n9nik.audiocutter.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/** Selection range in milliseconds, coerced into a valid [0, duration] window. */
data class TrimRange(val startMs: Long, val endMs: Long)

/** Result of a trim: the output file plus its extension/mime for MediaStore. */
data class TrimResult(val file: File, val extension: String, val mimeType: String)

/**
 * Outcome of a trim. Success carries the output file; failure carries a
 * plain-English reason safe to show in a toast. Never null — the UI must
 * always get an answer so the "Cutting..." spinner can never spin forever.
 */
sealed interface TrimOutcome {
    data class Ok(val result: TrimResult) : TrimOutcome
    data class Failed(val reason: String) : TrimOutcome
}

/**
 * Offline trim engine.
 *
 * - AAC (audio/mp4a-latm): lossless remux to .m4a via MediaExtractor/MediaMuxer.
 * - MP3 (audio/mpeg): lossless frame-level cut to .mp3 (no re-encode).
 * - Fade in/out requested, or any other codec: decode -> PCM fade -> AAC encode -> .m4a.
 */
object AudioTrimmer {

    /** Coerces a requested range into a valid non-empty selection inside [0, durationMs]. */
    fun coerceRange(requestedStartMs: Long, requestedEndMs: Long, durationMs: Long): TrimRange? {
        if (durationMs <= 0) return null
        val start = requestedStartMs.coerceIn(0, durationMs)
        val end = requestedEndMs.coerceIn(0, durationMs)
        if (end - start < 1000) return null // ignore sub-second slivers
        return TrimRange(start, end)
    }

    /** Returns the audio mime type of the first audio track, or null. */
    fun audioMime(context: Context, uri: Uri): String? {
        return try {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    if (mime != null && mime.startsWith("audio/")) return mime
                }
                null
            } finally {
                extractor.release()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Trims [range] of [uri].
     * @param fadeInMs fade-in length, 0 = off. @param fadeOutMs fade-out length, 0 = off.
     * Always returns an outcome — never null, never silent.
     */
    fun trim(
        context: Context,
        uri: Uri,
        range: TrimRange,
        fadeInMs: Long = 0,
        fadeOutMs: Long = 0
    ): TrimOutcome {
        return try {
            // Fade always needs the transcode path.
            if (fadeInMs > 0 || fadeOutMs > 0) {
                return transcodeWithFade(context, uri, range, fadeInMs, fadeOutMs)
            }
            // Lossless fast paths, chosen by sniffing the actual bytes (more
            // reliable than the container mime for raw MP3/ADTS files).
            when (sniffKind(context, uri)) {
                Kind.MP3 -> {
                    cutMp3(context, uri, range)?.let {
                        return TrimOutcome.Ok(TrimResult(it, "mp3", "audio/mpeg"))
                    }
                }
                Kind.ADTS -> {
                    cutAdts(context, uri, range)?.let {
                        return TrimOutcome.Ok(TrimResult(it, "aac", "audio/aac"))
                    }
                }
                Kind.MP4 -> {
                    if (audioMime(context, uri) == "audio/mp4a-latm") {
                        remuxAac(context, uri, range)?.let {
                            return TrimOutcome.Ok(TrimResult(it, "m4a", "audio/mp4"))
                        }
                    }
                }
                Kind.UNKNOWN -> {
                    // Mime-based last attempt before the transcode fallback.
                    when (audioMime(context, uri)) {
                        "audio/mpeg" -> cutMp3(context, uri, range)?.let {
                            return TrimOutcome.Ok(TrimResult(it, "mp3", "audio/mpeg"))
                        }
                        "audio/mp4a-latm" -> remuxAac(context, uri, range)?.let {
                            return TrimOutcome.Ok(TrimResult(it, "m4a", "audio/mp4"))
                        }
                    }
                }
            }
            // Lossless path didn't apply: decode -> AAC -> mux (capped, loud on failure).
            transcodeWithFade(context, uri, range, 0, 0)
        } catch (_: Exception) {
            TrimOutcome.Failed("Couldn't cut this file")
        }
    }

    /** Raw container kind, sniffed from the first bytes — no codec involved. */
    private enum class Kind { MP3, ADTS, MP4, UNKNOWN }

    private fun sniffKind(context: Context, uri: Uri): Kind {
        return try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val h = ByteArray(12)
                var read = 0
                while (read < h.size) {
                    val n = ins.read(h, read, h.size - read)
                    if (n <= 0) break
                    read += n
                }
                // ID3v2 tag -> MP3.
                if (read >= 3 && h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte()
                    && h[2] == '3'.code.toByte()
                ) return Kind.MP3
                if (read >= 2 && h[0] == 0xFF.toByte() && (h[1].toInt() and 0xE0) == 0xE0) {
                    // ADTS sync is 0xFFF + layer bits 00: (b1 & 0xF6) == 0xF0.
                    // A valid MP3 Layer III header can never match that (layer
                    // bits 00 are reserved), so this disambiguation is safe.
                    return if ((h[1].toInt() and 0xF6) == 0xF0) Kind.ADTS else Kind.MP3
                }
                // "ftyp" box -> MP4/M4A container.
                if (read >= 8 && h[4] == 'f'.code.toByte() && h[5] == 't'.code.toByte()
                    && h[6] == 'y'.code.toByte() && h[7] == 'p'.code.toByte()
                ) return Kind.MP4
                Kind.UNKNOWN
            } ?: Kind.UNKNOWN
        } catch (_: Exception) {
            Kind.UNKNOWN
        }
    }

    // ------------------------------------------------------------------
    // AAC lossless remux (proven path from TinyVoice)
    // ------------------------------------------------------------------

    private fun remuxAac(context: Context, uri: Uri, range: TrimRange): File? {
        return try {
            val outFile = File(context.cacheDir, "trim_${System.currentTimeMillis()}.m4a")
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                val trackIndex = findAudioTrack(extractor) ?: return null
                val format = extractor.getTrackFormat(trackIndex)
                val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    val outTrack = muxer.addTrack(format)
                    muxer.start()
                    extractor.selectTrack(trackIndex)
                    extractor.seekTo(range.startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val buffer = ByteBuffer.allocate(512 * 1024)
                    val info = MediaCodec.BufferInfo()
                    while (true) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs >= range.endMs * 1000) break
                        val ptsUs = (sampleTimeUs - range.startMs * 1000).coerceAtLeast(0)
                        info.set(0, size, ptsUs, extractor.sampleFlags)
                        muxer.writeSampleData(outTrack, buffer, info)
                        if (!extractor.advance()) break
                    }
                    muxer.stop()
                } finally {
                    muxer.release()
                }
            } finally {
                extractor.release()
            }
            outFile.takeIf { it.exists() && it.length() > 0 }
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // MP3 lossless frame-level cut
    // ------------------------------------------------------------------

    // Bitrates (kbps) for Layer III: index -> [MPEG1, MPEG2/2.5]
    private val BITRATES = arrayOf(
        intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320),
        intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    )
    private val SAMPLE_RATES = arrayOf(
        intArrayOf(44100, 48000, 32000), // MPEG1
        intArrayOf(22050, 24000, 16000), // MPEG2
        intArrayOf(11025, 12000, 8000)   // MPEG2.5
    )

    private fun cutMp3(context: Context, uri: Uri, range: TrimRange): File? {
        return try {
            val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            if (data.size < 128) return null
            val out = mutableListOf<Byte>()
            var pos = 0
            // Preserve ID3v2 tag if present.
            if (data.size > 10 && data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte()
                && data[2] == '3'.code.toByte()
            ) {
                val tagSize = synchsafeInt(data, 6)
                val end = (10 + tagSize).coerceAtMost(data.size)
                for (i in 0 until end) out.add(data[i])
                pos = end
            }
            var timeMs = 0.0
            var wroteAny = false
            while (pos + 4 < data.size) {
                // Find frame sync.
                if (data[pos] != 0xFF.toByte() || (data[pos + 1].toInt() and 0xE0) != 0xE0) {
                    pos++
                    continue
                }
                val b1 = data[pos + 1].toInt() and 0xFF
                val b2 = data[pos + 2].toInt() and 0xFF
                val b3 = data[pos + 3].toInt() and 0xFF
                val versionBits = (b1 shr 3) and 0x03
                val layerBits = (b1 shr 1) and 0x03
                val bitrateIdx = (b2 shr 4) and 0x0F
                val sampleRateIdx = (b2 shr 2) and 0x03
                val padding = (b2 shr 1) and 0x01
                // Must be Layer III with sane indices. Anything else (false sync
                // in binary data, free-format, corrupt frame) is skipped, not
                // fatal: bailing here used to throw the whole file onto the
                // slow transcode path over one bad byte.
                if (layerBits != 0x01 || bitrateIdx == 0 || bitrateIdx == 15
                    || sampleRateIdx == 3 || versionBits == 0x01
                ) {
                    pos++
                    continue
                }
                val version = when (versionBits) {
                    0x03 -> 0 // MPEG1
                    0x02 -> 1 // MPEG2
                    else -> 2 // MPEG2.5
                }
                val bitrate = BITRATES[if (version == 0) 0 else 1][bitrateIdx] * 1000
                val sampleRate = SAMPLE_RATES[version][sampleRateIdx]
                val frameLen = ((if (version == 0) 144 else 72) * bitrate / sampleRate + padding)
                val samplesPerFrame = if (version == 0) 1152 else 576
                if (frameLen <= 4 || pos + frameLen > data.size) {
                    pos++
                    continue
                }
                val frameDurMs = samplesPerFrame * 1000.0 / sampleRate
                if (timeMs >= range.startMs && timeMs < range.endMs) {
                    for (i in pos until pos + frameLen) out.add(data[i])
                    wroteAny = true
                }
                timeMs += frameDurMs
                if (timeMs >= range.endMs) break
                pos += frameLen
            }
            if (!wroteAny) return null
            val outFile = File(context.cacheDir, "trim_${System.currentTimeMillis()}.mp3")
            outFile.outputStream().use { fos ->
                val arr = ByteArray(out.size) { out[it] }
                fos.write(arr)
            }
            outFile.takeIf { it.exists() && it.length() > 0 }
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // AAC (ADTS) lossless frame-level cut — no decode/re-encode.
    // ------------------------------------------------------------------

    private val ADTS_SAMPLE_RATES =
        intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000)

    private fun cutAdts(context: Context, uri: Uri, range: TrimRange): File? {
        return try {
            val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            if (data.size < 64) return null
            val out = ArrayList<Byte>(data.size / 4)
            var pos = 0
            // Some .aac files carry an ID3v2 tag up front; skip it.
            if (data.size > 10 && data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte()
                && data[2] == '3'.code.toByte()
            ) {
                pos = (10 + synchsafeInt(data, 6)).coerceAtMost(data.size)
            }
            var timeMs = 0.0
            var wroteAny = false
            while (pos + 7 < data.size) {
                // ADTS sync: 0xFFF, then ID bit, layer bits 00, protection bit.
                if (data[pos] != 0xFF.toByte() || (data[pos + 1].toInt() and 0xF6) != 0xF0) {
                    pos++
                    continue
                }
                val b2 = data[pos + 2].toInt() and 0xFF
                val b3 = data[pos + 3].toInt() and 0xFF
                val b4 = data[pos + 4].toInt() and 0xFF
                val b5 = data[pos + 5].toInt() and 0xFF
                val b6 = data[pos + 6].toInt() and 0xFF
                val profile = (b2 shr 6) and 0x03
                val sfIdx = (b2 shr 2) and 0x0F
                val channelCfg = ((b2 and 0x01) shl 2) or ((b3 shr 6) and 0x03)
                val frameLen = ((b3 and 0x03) shl 11) or (b4 shl 3) or ((b5 shr 5) and 0x07)
                // Sanity: known profile, known sample rate, real channels,
                // single raw block per frame, plausible length.
                if (profile == 0x03 || sfIdx >= ADTS_SAMPLE_RATES.size || channelCfg == 0
                    || (b6 and 0x03) != 0 || frameLen < 7 || pos + frameLen > data.size
                ) {
                    pos++
                    continue
                }
                val frameDurMs = 1024 * 1000.0 / ADTS_SAMPLE_RATES[sfIdx]
                if (timeMs >= range.startMs && timeMs < range.endMs) {
                    for (i in pos until pos + frameLen) out.add(data[i])
                    wroteAny = true
                }
                timeMs += frameDurMs
                if (timeMs >= range.endMs) break
                pos += frameLen
            }
            if (!wroteAny) return null
            val outFile = File(context.cacheDir, "trim_${System.currentTimeMillis()}.aac")
            outFile.outputStream().use { fos -> fos.write(out.toByteArray()) }
            outFile.takeIf { it.exists() && it.length() > 0 }
        } catch (_: Exception) {
            null
        }
    }

    private fun synchsafeInt(data: ByteArray, off: Int): Int {
        return ((data[off].toInt() and 0x7F) shl 21) or
            ((data[off + 1].toInt() and 0x7F) shl 14) or
            ((data[off + 2].toInt() and 0x7F) shl 7) or
            (data[off + 3].toInt() and 0x7F)
    }

    // ------------------------------------------------------------------
    // Fade path: decode -> apply PCM fade -> AAC encode -> mux to .m4a
    // ------------------------------------------------------------------

    private fun transcodeWithFade(
        context: Context,
        uri: Uri,
        range: TrimRange,
        fadeInMs: Long,
        fadeOutMs: Long
    ): TrimOutcome {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            val trackIndex = findAudioTrack(extractor)
                ?: return TrimOutcome.Failed("Couldn't read the audio in this file")
            val trackFormat = extractor.getTrackFormat(trackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: return TrimOutcome.Failed("Couldn't read the audio in this file")
            val sampleRate = if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channels = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()

            val encFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val outFile = File(context.cacheDir, "trim_${System.currentTimeMillis()}.m4a")
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            extractor.selectTrack(trackIndex)
            val startUs = range.startMs * 1000
            val endUs = range.endMs * 1000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val totalSamples = (range.endMs - range.startMs) * sampleRate / 1000
            val fadeInSamples = fadeInMs * sampleRate / 1000
            val fadeOutSamples = fadeOutMs * sampleRate / 1000
            var samplesOut = 0L

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var outTrack = -1
            var muxerStarted = false
            var decoderDone = false
            var decoderSawEos = false // decoder output fully drained (EOS seen)
            var encoderDone = false
            var encoderEosSent = false
            val pendingPcm = ArrayDeque<ByteArray>()

            fun applyFade(pcm: ByteArray, sampleOffset: Long): ByteArray {
                if (fadeInSamples <= 0 && fadeOutSamples <= 0) return pcm
                val shorts = pcm.size / 2
                val buf = ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val outBuf = ByteBuffer.allocate(pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until shorts) {
                    val s = buf.short
                    val pos = sampleOffset + i / channels
                    var gain = 1.0
                    if (fadeInSamples > 0 && pos < fadeInSamples) {
                        gain = (pos + 1).toDouble() / fadeInSamples
                    }
                    if (fadeOutSamples > 0) {
                        val remaining = totalSamples - pos
                        if (remaining < fadeOutSamples) {
                            gain = minOf(gain, remaining.coerceAtLeast(0).toDouble() / fadeOutSamples)
                        }
                    }
                    outBuf.putShort((s * gain).toInt().coerceIn(-32768, 32767).toShort())
                }
                return outBuf.array()
            }

            // Watchdog: a transcode must finish or fail loudly. The budget is
            // capped — the old max(120s, 5x clip) could stretch to 20 minutes
            // for a full song, which no user will ever wait out. This
            // guarantees the UI's "Cutting your audio..." spinner always
            // resolves to a result or a plain-English failure.
            val clipMs = (range.endMs - range.startMs).coerceAtLeast(1)
            val budgetMs = minOf(180_000L, maxOf(45_000L, clipMs * 3))
            val deadlineMs = android.os.SystemClock.elapsedRealtime() + budgetMs

            // Main pump loop.
            while (!encoderDone) {
                if (android.os.SystemClock.elapsedRealtime() > deadlineMs) {
                    throw java.util.concurrent.TimeoutException("transcode exceeded budget")
                }
                // Feed decoder input.
                if (!decoderDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decoderDone = true
                        } else {
                            val pts = extractor.sampleTime
                            if (pts >= endUs) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                decoderDone = true
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, size, pts, 0)
                            }
                            extractor.advance()
                        }
                    }
                }
                // Drain decoder -> fade -> encoder input.
                var outIdx = decoder.dequeueOutputBuffer(decInfo, 10_000)
                while (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)!!
                    if (decInfo.size > 0 && decInfo.presentationTimeUs >= startUs) {
                        val chunk = ByteArray(decInfo.size)
                        outBuf.get(chunk)
                        pendingPcm.add(applyFade(chunk, samplesOut))
                        samplesOut += chunk.size / 2 / channels
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        // Decoder is fully drained. Encoder EOS is queued below in the
                        // main loop (retried each pass) so a backed-up codec can never
                        // wedge us in an unbounded inner feed loop.
                        decoderSawEos = true
                    }
                    outIdx = decoder.dequeueOutputBuffer(decInfo, 0)
                }
                // Move pending PCM into encoder. Bounded: if the encoder has no free
                // input buffer we break and retry next pass after draining its output.
                while (pendingPcm.isNotEmpty()) {
                    val inIdx = encoder.dequeueInputBuffer(0)
                    if (inIdx < 0) break
                    val chunk = pendingPcm.removeFirst()
                    val inBuf = encoder.getInputBuffer(inIdx)!!
                    inBuf.clear()
                    inBuf.put(chunk)
                    encoder.queueInputBuffer(inIdx, 0, chunk.size, 0, 0)
                }
                // Queue EOS to the encoder exactly once, only after all decoded PCM
                // has been fed. Retried every pass until it succeeds, so a single
                // TRY_AGAIN_LATER can never strand the loop without an EOS.
                if (decoderSawEos && pendingPcm.isEmpty() && !encoderEosSent) {
                    val inIdx = encoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        encoder.queueInputBuffer(
                            inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        encoderEosSent = true
                    }
                }
                // Drain encoder -> muxer.
                var eIdx = encoder.dequeueOutputBuffer(encInfo, 10_000)
                while (eIdx >= 0) {
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // Codec config is handled via INFO_OUTPUT_FORMAT_CHANGED.
                    } else if (encInfo.size > 0) {
                        if (!muxerStarted) {
                            // Should have started on format change; guard anyway.
                        } else {
                            val eBuf = encoder.getOutputBuffer(eIdx)!!
                            muxer.writeSampleData(outTrack, eBuf, encInfo)
                        }
                    }
                    encoder.releaseOutputBuffer(eIdx, false)
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encoderDone = true
                    }
                    eIdx = encoder.dequeueOutputBuffer(encInfo, 0)
                }
                if (eIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outTrack = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
            }

            muxer.stop()
            return if (outFile.exists() && outFile.length() > 0) {
                TrimOutcome.Ok(TrimResult(outFile, "m4a", "audio/mp4"))
            } else {
                TrimOutcome.Failed("Couldn't cut this file")
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            return TrimOutcome.Failed("Couldn't cut this file (it took too long)")
        } catch (_: Exception) {
            return TrimOutcome.Failed("Couldn't cut this file")
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }
}
