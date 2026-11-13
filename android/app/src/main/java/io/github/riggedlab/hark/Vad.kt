package io.github.riggedlab.hark

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * 判断一段录音里有没有人声（Silero VAD，和家里 Mac 转写前用的是同一个模型）。
 * 没有人声的录音不上传：家里的 Mac 转出来也是 0 个字，上传只是浪费流量。
 *
 * 门槛是用真实录音标定的：没人说话的 6 段，人声时长 0–0.6 秒；有人说话的 6 段，1.1–44 秒。
 * 取 0.25 秒，宁可多传，不要漏掉。
 */
object Vad {
    private const val TAG = "Vad"
    const val MIN_SPEECH_S = 0.25
    private const val PROB = 0.5f
    private const val WIN = 512          // 16kHz 下 32ms
    private const val CTX = 64           // Silero v5 每个窗口前要带上一窗口末尾 64 个采样

    @Volatile private var session: OrtSession? = null
    private val env by lazy { OrtEnvironment.getEnvironment() }

    private fun session(ctx: Context): OrtSession = session ?: synchronized(this) {
        session ?: env.createSession(ctx.assets.open("silero_vad.onnx").use { it.readBytes() },
            OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }).also { session = it }
    }

    private fun cacheFile(ctx: Context, rec: File) = File(ctx.filesDir, "vad/${rec.name}.txt")
    private fun gateCacheFile(ctx: Context, rec: File) = File(ctx.filesDir, "vad_gate/${rec.name}.txt")

    /** 缓存的人声时长（秒），还没算过返回 null */
    fun cached(ctx: Context, rec: File): Double? =
        cacheFile(ctx, rec).takeIf { it.exists() }?.readText()?.trim()?.toDoubleOrNull()

    /** 这段录音里有多少秒人声。算不出来（比如解码失败）返回 null，调用方应当照常上传 */
    fun speechSeconds(ctx: Context, rec: File): Double? {
        cached(ctx, rec)?.let { return it }
        val t0 = System.currentTimeMillis()
        val starts = runCatching { runSpeechWindows(ctx, decode16k(rec)) }
            .onFailure { Log.w(TAG, "vad ${rec.name} failed", it) }
            .getOrNull() ?: return null
        val r = starts.size * WIN / 16000.0
        cacheFile(ctx, rec).apply { parentFile?.mkdirs() }.writeText("%.2f".format(r))
        Log.i(TAG, "${rec.name}: 人声 %.1fs，用时 %dms".format(r, System.currentTimeMillis() - t0))
        return r
    }

    /**
     * 单次解码完成「人声检测 + 整段远场/外放背景粗筛」，结果持久化缓存避免断网重试时重复解码。
     * 解码或推理异常时保守返回 KEEP，交由后续链路照常处理。
     */
    internal fun screenChunk(ctx: Context, rec: File): Proximity.ChunkGate {
        gateCacheFile(ctx, rec).takeIf { it.exists() }?.readText()?.trim()?.let { tag ->
            when (tag) {
                "novoice" -> return Proximity.ChunkGate.NO_VOICE
                "far" -> return Proximity.ChunkGate.FAR_OR_MEDIA
                "keep" -> return Proximity.ChunkGate.KEEP
            }
        }
        // 若此前已缓存过 0 人声，无需再次解码音频
        cached(ctx, rec)?.let { sec ->
            if (sec < MIN_SPEECH_S) {
                writeGateCache(ctx, rec, Proximity.ChunkGate.NO_VOICE)
                return Proximity.ChunkGate.NO_VOICE
            }
        }
        val t0 = System.currentTimeMillis()
        val gate = runCatching {
            val pcm = decode16k(rec)
            val starts = runSpeechWindows(ctx, pcm)
            val sec = starts.size * WIN / 16000.0
            cacheFile(ctx, rec).apply { parentFile?.mkdirs() }.writeText("%.2f".format(sec))
            val g = Proximity.screenChunkPcm(ctx, pcm, starts)
            Log.i(TAG, "${rec.name}: 人声 %.1fs -> %s，用时 %dms".format(sec, g.name, System.currentTimeMillis() - t0))
            g
        }.onFailure {
            Log.w(TAG, "screenChunk ${rec.name} failed", it)
        }.getOrNull() ?: return Proximity.ChunkGate.KEEP

        writeGateCache(ctx, rec, gate)
        return gate
    }

    private fun writeGateCache(ctx: Context, rec: File, gate: Proximity.ChunkGate) {
        val code = when (gate) {
            Proximity.ChunkGate.NO_VOICE -> "novoice"
            Proximity.ChunkGate.FAR_OR_MEDIA -> "far"
            Proximity.ChunkGate.KEEP -> "keep"
        }
        runCatching {
            gateCacheFile(ctx, rec).apply { parentFile?.mkdirs() }.writeText(code)
        }
    }

    private fun runSpeechWindows(ctx: Context, x: FloatArray): IntArray {
        val s = session(ctx)
        var state = FloatArray(2 * 128)
        val sr = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16000)), longArrayOf())
        val buf = FloatArray(CTX + WIN)
        val winStarts = ArrayList<Int>()
        var i = 0
        sr.use {
            while (i + WIN <= x.size) {
                // 前 64 个是上一窗口的末尾（第一个窗口是 0）
                if (i >= CTX) System.arraycopy(x, i - CTX, buf, 0, CTX) else buf.fill(0f, 0, CTX)
                System.arraycopy(x, i, buf, CTX, WIN)
                OnnxTensor.createTensor(env, FloatBuffer.wrap(buf), longArrayOf(1, (CTX + WIN).toLong())).use { inp ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { st ->
                        s.run(mapOf("input" to inp, "state" to st, "sr" to sr)).use { out ->
                            @Suppress("UNCHECKED_CAST")
                            val p = (out[0].value as Array<FloatArray>)[0][0]
                            @Suppress("UNCHECKED_CAST")
                            val ns = out[1].value as Array<Array<FloatArray>>
                            state = FloatArray(256) { k -> ns[k / 128][0][k % 128] }
                            if (p > PROB) winStarts.add(i)
                        }
                    }
                }
                i += WIN
            }
        }
        return winStarts.toIntArray()
    }

    /** 解码成 16kHz 单声道 float（[-1, 1]）。Android 的 Opus 解码器一般输出 48kHz，每 3 个采样取平均 */
    internal fun decode16k(f: File): FloatArray {
        if (!f.exists() || f.length() < 200) return FloatArray(0)
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(f.absolutePath)
            ex.selectTrack(0)
            val fmt = ex.getTrackFormat(0)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val out = ArrayList<Float>(16000 * 60)
            var acc = 0.0; var accN = 0; var pos = 0.0
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val size = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (size < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { c.queueInputBuffer(i, 0, size, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = c.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = c.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val sb = c.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).apply {
                        position(info.offset); limit(info.offset + info.size) }.asShortBuffer()
                    val step = rate / 16000.0
                    while (sb.remaining() >= channels) {
                        var v = 0.0
                        repeat(channels) { v += sb.get() }
                        acc += v / channels; accN++
                        pos += 1.0
                        if (pos >= step) { out.add((acc / accN / 32768.0).toFloat()); acc = 0.0; accN = 0; pos -= step }
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            return out.toFloatArray()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }
}
