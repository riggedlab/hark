package io.github.riggedlab.hark

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 录音的音量波形：每 0.1 秒一个值（0..1，按分贝映射，安静≈0，说话≈0.4~0.8）。
 * 第一次需要时解码一遍算出来，缓存在 files/waves/<录音名>.wave。
 */
object Waveforms {
    const val BUCKET_MS = 100
    private const val DB_FLOOR = -65.0          // 低于这个当作完全安静
    private val mem = ConcurrentHashMap<String, FloatArray>()
    private val lock = Mutex()                   // 一次只解码一个文件

    private fun cacheFile(rec: File) = File(rec.parentFile!!.parentFile, "waves/${rec.nameWithoutExtension}.wave")

    fun cached(rec: File): FloatArray? = mem[rec.name] ?: cacheFile(rec).takeIf { it.exists() }?.let { f ->
        f.readBytes().map { (it.toInt() and 0xff) / 255f }.toFloatArray().also { mem[rec.name] = it }
    }

    suspend fun get(rec: File): FloatArray? = cached(rec) ?: withContext(Dispatchers.Default) {
        lock.withLock {
            cached(rec) ?: runCatching { compute(rec) }.onFailure { Log.w("Waveforms", "decode failed", it) }
                .getOrNull()?.also { w ->
                    val cf = cacheFile(rec)
                    cf.parentFile?.mkdirs()
                    cf.writeBytes(ByteArray(w.size) { (w[it] * 255).toInt().coerceIn(0, 255).toByte() })
                    mem[rec.name] = w
                }
        }
    }

    /** 删除已经没有对应录音的波形缓存 */
    fun cleanup(chunkDir: File) {
        val alive = chunkDir.listFiles()?.map { it.nameWithoutExtension.removeSuffix(".opus") }?.toSet() ?: return
        File(chunkDir.parentFile, "waves").listFiles()?.forEach { if (it.nameWithoutExtension !in alive) it.delete() }
    }

    private fun compute(f: File): FloatArray {
        val ex = MediaExtractor()
        ex.setDataSource(f.absolutePath)
        ex.selectTrack(0)
        val fmt = ex.getTrackFormat(0)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var bucketSize = rate * BUCKET_MS / 1000
        val out = ArrayList<Float>()
        var sumSq = 0.0
        var n = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val size = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                bucketSize = rate * BUCKET_MS / 1000
            } else if (o >= 0) {
                val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val sb = buf.asShortBuffer()
                while (sb.hasRemaining()) {
                    val v = sb.get().toDouble()
                    sumSq += v * v
                    if (++n >= bucketSize) {
                        out.add(level(sumSq / n)); sumSq = 0.0; n = 0
                    }
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }
        if (n > 0) out.add(level(sumSq / n))
        codec.stop(); codec.release(); ex.release()
        return out.toFloatArray()
    }

    private fun level(meanSq: Double): Float {
        val db = 20 * log10((sqrt(meanSq) + 1) / 32768.0)
        return ((db - DB_FLOOR) / -DB_FLOOR).coerceIn(0.0, 1.0).toFloat()
    }
}
