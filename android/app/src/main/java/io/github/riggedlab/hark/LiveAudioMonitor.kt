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
    private const val HISTORY_SIZE = 72
    private const val BAR_INTERVAL_NS = 95_000_000L // ~10.5 根声柱/秒，配合 UI 端 120Hz 亚像素连续平移

    @Volatile private var active: Worker? = null
    /** 50Hz 无锁实时声压（0f..1f），供 120Hz Canvas 直接读取，避免每秒 10 次触发全局 Compose 重组 */
    @Volatile var realtimeLevel: Float = 0f
        private set
    @Volatile var lastPcmNanos: Long = 0L
        private set

    private val ringLock = Any()
    private val historyRing = FloatArray(HISTORY_SIZE)
    private var ringHead = 0
    private var ringFilled = 0
    private var lastBarNanos = 0L

    fun currentLevel(nowNanos: Long, fallback: Float? = null): Float {
        val pcmAt = lastPcmNanos
        if (pcmAt != 0L && nowNanos - pcmAt < 450_000_000L) return realtimeLevel
        return fallback ?: 0f
    }

    /** 读取最近声柱快照与当前柱内亚像素进度（0f..1f），锁屏解锁或从设置页返回立刻满屏连续显示 */
    fun readSpectrum(nowNanos: Long, outNewestFirst: FloatArray, fallback: Float? = null): Pair<Int, Float> {
        synchronized(ringLock) {
            if (ringFilled == 0 && fallback != null && fallback > 0.02f) {
                val n = minOf(outNewestFirst.size, 28)
                for (i in 0 until n) {
                    val wave = (0.55f + 0.45f * kotlin.math.abs(kotlin.math.sin(i * 0.62f)))
                    outNewestFirst[i] = (fallback * wave).coerceIn(0f, 1f)
                }
                return n to 0f
            }
            val count = minOf(ringFilled, outNewestFirst.size)
            for (i in 0 until count) {
                val idx = (ringHead - 1 - i + HISTORY_SIZE * 2) % HISTORY_SIZE
                outNewestFirst[i] = historyRing[idx]
            }
            val frac = if (lastBarNanos == 0L) 0f
            else ((nowNanos - lastBarNanos).toFloat() / BAR_INTERVAL_NS).coerceIn(0f, 1f)
            return count to frac
        }
    }

    private fun pushBarSample(level: Float, nowNanos: Long) {
        synchronized(ringLock) {
            if (lastBarNanos == 0L) {
                lastBarNanos = nowNanos
                historyRing[ringHead] = level
                ringHead = (ringHead + 1) % HISTORY_SIZE
                if (ringFilled < HISTORY_SIZE) ringFilled++
                return
            }
            var steps = ((nowNanos - lastBarNanos) / BAR_INTERVAL_NS).toInt()
            if (steps <= 0) {
                // 同一柱周期内保留峰值，说话短促音节不漏帧
                val prevIdx = (ringHead - 1 + HISTORY_SIZE) % HISTORY_SIZE
                if (ringFilled > 0 && level > historyRing[prevIdx]) {
                    historyRing[prevIdx] = level
                }
                return
            }
            if (steps > 6) {
                steps = 1
                lastBarNanos = nowNanos
            } else {
                lastBarNanos += steps * BAR_INTERVAL_NS
            }
            repeat(steps) { step ->
                val v = if (step == steps - 1) level else 0f
                historyRing[ringHead] = v
                ringHead = (ringHead + 1) % HISTORY_SIZE
                if (ringFilled < HISTORY_SIZE) ringFilled++
            }
        }
    }

    private fun resetRing() {
        synchronized(ringLock) {
            historyRing.fill(0f)
            ringHead = 0
            ringFilled = 0
            lastBarNanos = 0L
        }
    }

    @Volatile private var speechListener: (() -> Unit)? = null

    fun start(context: Context, onSpeechDetected: (() -> Unit)? = null) {
        if (onSpeechDetected != null) speechListener = onSpeechDetected
        if (active != null) return
        val worker = Worker(context.applicationContext)
        active = worker
        worker.start()
    }

    fun stop() {
        speechListener = null
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

    private fun clearStatus() {
        realtimeLevel = 0f
        lastPcmNanos = 0L
        resetRing()
        AppState.update {
            if (it.liveLevel == null && !it.liveSpeech && !it.liveUnavailable) it
            else it.copy(liveLevel = null, liveSpeech = false, liveUnavailable = false)
        }
    }

    private data class Packet(val seq: Int, val opus: ByteArray, val epoch: Int, val at: Long)

    private class Worker(private val context: Context) : Thread("hark-live-audio") {
        // 128 × 20ms ≈ 2.56s；非阻塞分析队列，兼顾后台省电调度抖动与录音写入零阻塞。
        private val queue = ArrayBlockingQueue<Packet>(128)
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
                realtimeLevel = 0f
                lastPcmNanos = 0L
            }

            fun sample(value: Float) {
                if (!running || seenEpoch != epoch.get()) return
                squares += value * value
                sampleCount++
                if (sampleCount == 320) {
                    val db = 20.0 * log10(sqrt(squares / sampleCount).coerceAtLeast(0.000001))
                    level = ((db + 56.0) / 52.0).toFloat().coerceIn(0f, 1f)
                    squares = 0.0
                    sampleCount = 0
                    val t = System.nanoTime()
                    lastPcm = t
                    realtimeLevel = level
                    lastPcmNanos = t
                    pushBarSample(level, t)
                }
                if (vad?.accept(value) == true) {
                    lastSpeech = System.nanoTime()
                    runCatching { speechListener?.invoke() }
                }
            }

            try {
                var idleTicks = 0
                while (running) {
                    val needPeriodicSweep = decoder != null || idleTicks < 80
                    val packet = try {
                        if (needPeriodicSweep) queue.poll(100, TimeUnit.MILLISECONDS) else queue.take()
                    } catch (_: InterruptedException) { null }
                    if (!running) break
                    if (packet != null) idleTicks = 0 else idleTicks++
                    if (seenEpoch != epoch.get()) {
                        resetAudio()
                        seenEpoch = epoch.get()
                        lastSpeech = 0L
                    }
                    try {
                        if (!failed && packet != null && packet.epoch == seenEpoch &&
                            System.nanoTime() - packet.at <= 1_500_000_000L) {
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
                    if (now - lastPcm >= BAR_INTERVAL_NS && active === this && seenEpoch == epoch.get() && !failed) {
                        pushBarSample(0f, now)
                    }
                    if (now - lastPublish >= 250_000_000L && active === this && seenEpoch == epoch.get()) {
                        lastPublish = now
                        val speechNow = !failed && lastSpeech != 0L && now - lastSpeech < 1_000_000_000L
                        val cur = AppState.status.value
                        if (cur.liveSpeech != speechNow || cur.liveUnavailable != failed) {
                            AppState.update {
                                if (active !== this || seenEpoch != epoch.get()) it else it.copy(
                                    liveSpeech = speechNow,
                                    liveUnavailable = failed,
                                )
                            }
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
