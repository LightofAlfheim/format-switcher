package com.formatswitcher.app.processing

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.formatswitcher.app.UserFacingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Decodes the sound track of any audio or video file the phone can play
 * (MP3, AAC/M4A, WAV, FLAC, OGG, OPUS, AMR, MP4, MKV, WEBM…) and writes it as
 * M4A (AAC, with a chosen bitrate) or WAV (16-bit PCM). Uses only Android's
 * built-in codecs – nothing leaves the phone.
 */
class AudioConverter(private val context: Context) {

    enum class Output { AAC, WAV }

    suspend fun convert(
        uri: Uri,
        outFile: File,
        output: Output,
        bitrate: Int,
        forceMono: Boolean,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var sink: PcmSink? = null
        var success = false
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                throw UserFacingException("Couldn't open this file.")
            }
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UserFacingException("This file has no sound in it to convert.")

            extractor.selectTrack(trackIndex)
            val inFormat = extractor.getTrackFormat(trackIndex)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs =
                if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            inFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

            val dec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw UserFacingException("Your phone can't decode this audio type ($mime).")
            }
            decoder = dec
            dec.configure(inFormat, null, null, 0)
            dec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var mixer: ChannelMixer? = null
            var resampler: LinearResampler? = null
            var pcmFloat = false
            var lastProgress = -1f

            while (!outputDone) {
                ensureActive()

                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        if (sink == null) {
                            val f = dec.outputFormat
                            val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            val outCh = if (forceMono || ch == 1) 1 else 2
                            mixer = ChannelMixer(ch, outCh)
                            val outRate = if (output == Output.AAC) aacSampleRate(rate) else rate
                            resampler = if (outRate != rate) LinearResampler(rate, outRate, outCh) else null
                            sink = when (output) {
                                Output.AAC -> AacSink(outFile, outRate, outCh, bitrate)
                                Output.WAV -> WavSink(outFile, outRate, outCh)
                            }
                        }
                        val buf = dec.getOutputBuffer(outIdx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val samples = readPcm(buf.slice().order(ByteOrder.nativeOrder()), pcmFloat)
                        var frames = mixer!!.mix(samples)
                        val rs = resampler
                        if (rs != null) frames = rs.process(frames)
                        if (frames.isNotEmpty()) sink!!.write(frames)
                    }
                    if (durationUs > 0) {
                        val p = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                        if (p - lastProgress >= 0.01f) {
                            lastProgress = p
                            onProgress(p)
                        }
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }

            val finalSink = sink ?: throw UserFacingException("Couldn't read any sound from this file.")
            finalSink.finish()
            onProgress(1f)
            success = true
        } finally {
            try {
                decoder?.stop()
            } catch (_: Exception) {
            }
            try {
                decoder?.release()
            } catch (_: Exception) {
            }
            extractor.release()
            if (!success) {
                sink?.abort()
                outFile.delete()
            }
        }
    }

    private fun readPcm(buf: ByteBuffer, isFloat: Boolean): ShortArray {
        return if (isFloat) {
            val fb = buf.asFloatBuffer()
            ShortArray(fb.remaining()) { (fb.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
        } else {
            val sb = buf.asShortBuffer()
            ShortArray(sb.remaining()).also { sb.get(it) }
        }
    }

    private fun aacSampleRate(rate: Int): Int = when {
        rate in AAC_RATES -> rate
        rate > 48_000 -> 48_000
        else -> 44_100
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        private val AAC_RATES = setOf(8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000)
    }
}

// ---------------------------------------------------------------------------------------------

private interface PcmSink {
    fun write(pcm: ShortArray)
    fun finish()
    fun abort()
}

/** Turns mono/stereo/surround into 1 or 2 channels. */
private class ChannelMixer(private val inCh: Int, private val outCh: Int) {
    fun mix(s: ShortArray): ShortArray {
        if (inCh == outCh) return s
        val frames = s.size / inCh
        val out = ShortArray(frames * outCh)
        for (f in 0 until frames) {
            val base = f * inCh
            if (outCh == 1) {
                var sum = 0
                for (c in 0 until inCh) sum += s[base + c]
                out[f] = (sum / inCh).toShort()
            } else {
                // Surround (e.g. 5.1: FL, FR, C, LFE, BL, BR) -> stereo, folding the centre in.
                val centre = if (inCh >= 3) s[base + 2] * 0.707f else 0f
                out[f * 2] = clip(s[base] + centre)
                out[f * 2 + 1] = clip(s[base + 1] + centre)
            }
        }
        return out
    }

    private fun clip(v: Float): Short = v.coerceIn(-32768f, 32767f).toInt().toShort()
}

/** Simple streaming linear-interpolation resampler (used for rates AAC can't encode, e.g. 96 kHz). */
private class LinearResampler(inRate: Int, outRate: Int, private val ch: Int) {
    private val step = inRate.toDouble() / outRate
    private var pos = 0.0
    private var prev: ShortArray? = null

    fun process(input: ShortArray): ShortArray {
        if (input.size < ch) return ShortArray(0)
        val p = prev
        val combined = if (p != null) p + input else input
        val total = combined.size / ch
        val out = ShortArray(((total / step).toInt() + 2) * ch)
        var n = 0
        while (pos + 1 < total && n + ch <= out.size) {
            val i = pos.toInt()
            val frac = (pos - i).toFloat()
            for (c in 0 until ch) {
                val a = combined[i * ch + c]
                val b = combined[(i + 1) * ch + c]
                out[n++] = (a + (b - a) * frac).toInt().toShort()
            }
            pos += step
        }
        pos -= (total - 1)
        prev = combined.copyOfRange((total - 1) * ch, total * ch)
        return out.copyOf(n)
    }
}

/** Writes 16-bit PCM WAV. */
private class WavSink(private val file: File, private val rate: Int, private val ch: Int) : PcmSink {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private var dataBytes = 0L

    init {
        out.write(ByteArray(44)) // header placeholder, filled in at the end
    }

    override fun write(pcm: ShortArray) {
        val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(pcm)
        out.write(bb.array())
        dataBytes += pcm.size * 2L
    }

    override fun finish() {
        out.flush()
        out.close()
        if (dataBytes > 0xFFFFFFFFL - 36) {
            throw UserFacingException("This is too long for a WAV file (over 4 GB). Try M4A instead.")
        }
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header())
        }
    }

    override fun abort() {
        try {
            out.close()
        } catch (_: Exception) {
        }
    }

    private fun header(): ByteArray {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt((36 + dataBytes).toInt())
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1) // PCM
        bb.putShort(ch.toShort())
        bb.putInt(rate)
        bb.putInt(rate * ch * 2)
        bb.putShort((ch * 2).toShort())
        bb.putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataBytes.toInt())
        return bb.array()
    }
}

/** Encodes PCM to AAC-LC and stores it in an .m4a (MP4) container. */
private class AacSink(file: File, private val rate: Int, private val ch: Int, bitrate: Int) : PcmSink {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var framesQueued = 0L
    private var released = false

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch)
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    private fun ptsUs(): Long = framesQueued * 1_000_000L / rate

    override fun write(pcm: ShortArray) {
        var offset = 0
        while (offset < pcm.size) {
            val idx = codec.dequeueInputBuffer(TIMEOUT_US)
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx)!!
                buf.clear()
                val maxShorts = (buf.remaining() / 2 / ch) * ch
                val n = min(maxShorts, pcm.size - offset)
                buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, offset, n)
                codec.queueInputBuffer(idx, 0, n * 2, ptsUs(), 0)
                framesQueued += n / ch
                offset += n
            }
            drain(false)
        }
    }

    private fun drain(endOfStream: Boolean) {
        var idle = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0L)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    idle++
                    if (idle > 300) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                idx >= 0 -> {
                    val ob = codec.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> if (!endOfStream) return
            }
        }
    }

    override fun finish() {
        var queued = false
        var tries = 0
        while (!queued) {
            val idx = codec.dequeueInputBuffer(TIMEOUT_US)
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                queued = true
            } else {
                drain(false)
                tries++
                if (tries > 500) break
            }
        }
        drain(true)
        val ok = muxerStarted
        release()
        if (!ok) throw UserFacingException("Couldn't encode the audio.")
    }

    override fun abort() = release()

    private fun release() {
        if (released) return
        released = true
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        try {
            codec.release()
        } catch (_: Exception) {
        }
        try {
            if (muxerStarted) muxer.stop()
        } catch (_: Exception) {
        }
        try {
            muxer.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
    }
}
