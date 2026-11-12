package io.github.riggedlab.hark

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Process
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10
import kotlin.math.sqrt

/** 只分析已交给文件写入器的帧。录音线程只做非阻塞 offer，不持有解码器/VAD 的锁。 */
object LiveAudioMonitor {
    @Volatile private var active: Worker? = null

    fun start(context: Context) {
        if (active != null) return
        val worker = Worker(context.applicationContext)
        active = worker
        worker.start()
    }

    fun stop() {
        val worker = active
        active = null
        worker?.finish()
        clearStatus()
    }

    fun reset() {
        active?.reset()
        clearStatus()
    }

    fun offer(seq: Int, opus: ByteArray) { active?.offer(seq, opus) }

    private fun clearStatus() = AppState.update {
        it.copy(liveLevel = null, liveSpeech = false, liveUnavailable = false)
    }

    private data class Packet(val seq: Int, val opus: ByteArray, val epoch: Int, val at: Long)

    private class Worker(private val context: Context) : Thread("hark-live-audio") {
        // 5 × 20ms = 100ms；这是可丢弃的分析队列，录音写入完全不受容量限制。
        private val queue = ArrayBlockingQueue<Packet>(5)
        private val epoch = AtomicInteger()
        @Volatile private var running = true
        @Volatile private var failed = false

        fun offer(seq: Int, opus: ByteArray) {
            if (!running || failed) return
            val packet = Packet(seq, opus, epoch.get(), System.nanoTime())
            if (!queue.offer(packet)) {
                queue.poll()
                queue.offer(packet)
            }
        }

        fun reset() { epoch.incrementAndGet(); queue.clear(); interrupt() }
        fun finish() { running = false; queue.clear(); interrupt() }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            var decoder: OpusStreamDecoder? = null
            var vad: StreamingVad? = null
            var seenEpoch = -1
            var lastSeq = -1
            var lastSpeech = 0L
            var lastPcm = 0L
            var lastPublish = 0L
            var level = 0f
            var squares = 0.0
            var sampleCount = 0

            fun resetAudio() {
                decoder?.close()
                decoder = null
                vad?.reset()
                lastSeq = -1
                squares = 0.0
                sampleCount = 0
                level = 0f
                lastPcm = 0L
            }

            fun sample(value: Float) {
                if (!running || seenEpoch != epoch.get()) return
                squares += value * value
                sampleCount++
                if (sampleCount == 320) {
                    val db = 20.0 * log10(sqrt(squares / sampleCount).coerceAtLeast(0.000001))
                    level = ((db + 60.0) / 60.0).toFloat().coerceIn(0f, 1f)
                    squares = 0.0
                    sampleCount = 0
                    lastPcm = System.nanoTime()
                }
                if (vad?.accept(value) == true) lastSpeech = System.nanoTime()
            }

            try {
                while (running) {
                    val packet = try { queue.poll(50, TimeUnit.MILLISECONDS) }
                        catch (_: InterruptedException) { null }
                    if (!running) break
                    if (seenEpoch != epoch.get()) {
                        resetAudio()
                        seenEpoch = epoch.get()
                        lastSpeech = 0L
                    }
                    try {
                        if (!failed && packet != null && packet.epoch == seenEpoch &&
                            System.nanoTime() - packet.at <= 200_000_000L) {
                            if (packet.opus.isEmpty()) {
                                resetAudio() // 安静声明不需要解码补静音，清掉 Silero 的上下文即可。
                            } else {
                                if (lastSeq >= 0 && ((packet.seq - lastSeq) and 0xffff) != 1) resetAudio()
                                if (vad == null) vad = StreamingVad(context)
                                if (decoder == null) decoder = OpusStreamDecoder(::sample)
                                if (decoder!!.offer(packet.opus)) lastSeq = packet.seq
                            }
                        }
                        if (!failed) decoder?.drain()
                    } catch (_: Exception) {
                        // 任何分析故障都只降级显示；不抛回录音线程、不记录音频或分析结果。
                        failed = true
                        queue.clear()
                        resetAudio()
                        vad?.close()
                        vad = null
                    }
                    val now = System.nanoTime()
                    if (now - lastPublish >= 100_000_000L && active === this && seenEpoch == epoch.get()) {
                        lastPublish = now
                        AppState.update {
                            if (active !== this || seenEpoch != epoch.get()) it else it.copy(
                                liveLevel = if (failed) null else if (now - lastPcm < 500_000_000L) level else 0f,
                                liveSpeech = !failed && lastSpeech != 0L && now - lastSpeech < 1_000_000_000L,
                                liveUnavailable = failed,
                            )
                        }
                    }
                }
            } finally {
                decoder?.close()
                runCatching { vad?.close() }
                queue.clear()
            }
        }
    }
}

/** 原始 Opus 帧直接送 MediaCodec，不创建临时文件。CSD 按 Android MediaCodec 的 Opus 约定。 */
internal class OpusStreamDecoder(private val sample: (Float) -> Unit) : AutoCloseable {
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var pts = 0L
    private var rate = 48000
    private var channels = 1
    private var encoding = AudioFormat.ENCODING_PCM_16BIT
    private var acc = 0.0
    private var accN = 0
    private var position = 0.0

    init {
        try {
            val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("OpusHead".toByteArray(Charsets.US_ASCII))
                put(1); put(1); putShort(OggOpusWriter.PRE_SKIP.toShort())
                putInt(16000); putShort(0); put(0)
            }
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 1).apply {
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                setByteBuffer("csd-0", ByteBuffer.wrap(head.array()))
                setByteBuffer("csd-1", nativeLong(OggOpusWriter.PRE_SKIP * 1_000_000_000L / 48000))
                setByteBuffer("csd-2", nativeLong(80_000_000L)) // Opus 规范的 seek pre-roll 80ms
            }
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    fun offer(packet: ByteArray): Boolean {
        drain()
        val index = codec.dequeueInputBuffer(0)
        if (index < 0) return false // 来不及就丢帧；下一帧的序号空缺会重置分析状态。
        codec.getInputBuffer(index)!!.apply { clear(); put(packet) }
        codec.queueInputBuffer(index, 0, packet.size, pts, 0)
        pts += 20_000
        drain()
        return true
    }

    fun drain() {
        repeat(8) {
            when (val index = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    require(rate >= 16000 && channels in 1..2)
                    require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT)
                }
                else -> {
                    if (index < 0) return
                    try {
                        val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder()).apply {
                            position(info.offset); limit(info.offset + info.size)
                        }
                        val bytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                        val step = rate / 16000.0
                        while (buffer.remaining() >= bytes * channels) {
                            var value = 0.0
                            repeat(channels) { value += if (bytes == 4) buffer.float.toDouble() else buffer.short / 32768.0 }
                            acc += value / channels
                            accN++
                            position++
                            if (position >= step) {
                                sample((acc / accN).toFloat())
                                acc = 0.0; accN = 0; position -= step
                            }
                        }
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
            }
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private fun nativeLong(value: Long): ByteBuffer = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply {
        putLong(value); flip()
    }
}

/** 独立 session、状态和上下文；不接触上传前的整段 Vad。所有 tensor/session 都在分析线程关闭。 */
internal class StreamingVad(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(1)
        options.setInterOpNumThreads(1)
        env.createSession(context.assets.open("silero_vad.onnx").use { it.readBytes() }, options)
    }
    private val sr = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16000)), longArrayOf())
    private val input = FloatArray(64 + 512)
    private var state = FloatArray(256)
    private var count = 0

    fun reset() { input.fill(0f); state.fill(0f); count = 0 }

    fun accept(value: Float): Boolean {
        input[64 + count++] = value
        if (count < 512) return false
        count = 0
        val speech = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 576)).use { inp ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { st ->
                session.run(mapOf("input" to inp, "state" to st, "sr" to sr)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val probability = (out[0].value as Array<FloatArray>)[0][0]
                    @Suppress("UNCHECKED_CAST")
                    val next = out[1].value as Array<Array<FloatArray>>
                    for (i in state.indices) state[i] = next[i / 128][0][i % 128]
                    probability >= 0.5f
                }
            }
        }
        input.copyInto(input, 0, 512, 576)
        return speech
    }

    override fun close() { sr.close(); session.close() }
}
