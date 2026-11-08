package app.partitura

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

/** Exporta áudio compactado (AAC em .m4a) usando o codificador do próprio Android — sem internet e sem biblioteca extra. MP3 não é oferecido: o Android não traz codificador MP3. */
object AudioExport {
    fun m4a(pcm: ShortArray, rate: Int, cacheDir: File): ByteArray {
        val file = File.createTempFile("export", ".m4a", cacheDir)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var started = false
        val info = MediaCodec.BufferInfo()
        var inPos = 0; var inDone = false; var outDone = false
        try {
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        buf.clear()
                        val n = minOf(buf.capacity() / 2, pcm.size - inPos)
                        val pts = inPos * 1_000_000L / rate
                        if (n <= 0) { codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else {
                            buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, inPos, n)
                            codec.queueInputBuffer(ii, 0, n * 2, pts, 0)
                            inPos += n
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { if (started) muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }
        val bytes = file.readBytes(); file.delete()
        return bytes
    }
}
