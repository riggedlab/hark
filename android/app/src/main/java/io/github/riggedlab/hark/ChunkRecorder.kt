package io.github.riggedlab.hark

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 把连续到来的 Opus 包按自然对话流与环境音切分成文件：
 * 1. 没人说话时（纯环境音 / 敲键盘 / 咳嗽清嗓）：每 5 分钟归档一段 .opus 留给本机环境音识别；
 * 2. 纯环境音已录满 1 分钟后突然开口说话：先把前段环境音无重叠归档，并带 ~1.8 秒预录滑窗新开对话段（不吞首字）；
 * 3. 进入对话后：连续 35 秒无人声即自动收尾触发上传/转写（无需傻等 5 分钟）；
 * 4. 持续长对话：满 4 分钟后趁 ≥ 2.5 秒换气停顿软切防劈句，满 6 分钟硬上限兜底。
 *
 * 文件先写成 .part，写完改名成 .opus，所以列表里看到的 .opus 都是完整的。
 */
class ChunkRecorder(
    private val dir: File,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val onChunkReady: (() -> Unit)? = null,
) {

    private class HeldFrame(val pkt: ByteArray, val isReal: Boolean)

    private var writer: OggOpusWriter? = null
    private var partFile: File? = null
    private var lastSeq = -1
    private var lastStartEpochSec = -1L

    var lostPackets = 0L
        private set
    var totalPackets = 0L
        private set
    /** 设备安静时没有发送、由手机补静音的帧数 */
    var silentFrames = 0L
        private set
    var lastWriteNanos = 0L
        private set

    /** 当前已真正写进 writer 的有效音频包数（不算补的静音，也不算仍在 holdQueue 里的包） */
    private var writtenRealInChunk = 0

    /** 纯环境音满 1 分钟后维持的 ~1.8s FIFO 滑窗，确保突然开口时能把首字起音完整划入新对话段且前段零重叠 */
    private val holdQueue = ArrayDeque<HeldFrame>(AMBIENT_HOLD_PACKETS + 4)
    private var holdIntakeToggle = 0
    private var holdUnflushedWrites = 0

    /** 单调帧时钟（每 20ms 音频帧 +1），驱动对话开口滑窗与冷场计时，不依赖系统墙钟 */
    private var audioFrameClock = 0L
    private var framesSinceLastSpeech = 0L
    private var speechWindowsInChunk = 0
    private var inConversation = false
    private val recentSpeechFrames = ArrayDeque<Long>(32)

    init {
        dir.mkdirs()
        // 上次异常退出留下的半截文件：Ogg 是流式格式，前面的部分仍然能播放，直接转正
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { finalize(it) }
    }

    val currentFileName: String? get() = partFile?.name?.removeSuffix(".part")
    val currentSeconds: Double
        get() = writer?.let { it.durationSeconds + holdQueue.size.toDouble() / FRAMES_PER_SEC } ?: 0.0

    /**
     * 收到一个包。opus 为空表示设备的"安静声明"：seq 以及之前没收到的帧都是安静，用静音补齐。
     * 有内容的包前面如果缺帧，就是真的丢包（设备会把安静的帧都声明掉），同样用静音补齐但计入丢包。
     */
    @Synchronized
    fun onPacket(seq: Int, opus: ByteArray): Boolean {
        val silence = opus.isEmpty()
        var fill = 0
        if (lastSeq >= 0) {
            val gap = (seq - lastSeq - 1) and 0xFFFF
            if (gap > 0xFF00) return false           // 序号比已收到的还旧（重复声明），忽略
            if (gap in 1..MAX_FILL_GAP) fill = gap
            else if (gap > MAX_FILL_GAP) closeChunk()   // 断得太久（或者设备重启了），另起一段
        }
        lastSeq = seq
        if (silence) fill += 1                        // 声明的这一帧本身也是安静
        repeat(fill) { emitFrame(OggOpusWriter.SILENT_PACKET, isReal = false) }
        if (silence) silentFrames += fill else lostPackets += fill
        if (!silence) {
            totalPackets++
            emitFrame(opus, isReal = true)
        } else {
            flushWriterAudio()
        }
        return true
    }

    /**
     * 后台流式 Silero VAD 每检出一个 32ms 人声窗口时调用（在录音 io 线程串行执行）。
     * 若流式 VAD 未启用或设备解码器降级，此方法不会被调用，自动退化为每 5 分钟定长切段。
     */
    @Synchronized
    fun onSpeechDetected() {
        if (writer == null && holdQueue.isEmpty()) return

        framesSinceLastSpeech = 0L
        speechWindowsInChunk++

        recentSpeechFrames.addLast(audioFrameClock)
        while (recentSpeechFrames.isNotEmpty() && audioFrameClock - recentSpeechFrames.first() > ONSET_WINDOW_FRAMES) {
            recentSpeechFrames.removeFirst()
        }

        if (!inConversation &&
            (recentSpeechFrames.size >= ONSET_SPEECH_WINS || speechWindowsInChunk >= TOTAL_SPEECH_WINS_FOR_CONV)
        ) {
            if (holdQueue.size >= AMBIENT_HOLD_PACKETS) {
                splitForConversation()
            } else {
                inConversation = true
                drainHoldQueueToWriter()
            }
        }
    }

    private fun totalFramesInChunk(): Long = (writer?.packetCount ?: 0L) + holdQueue.size

    private fun emitFrame(pkt: ByteArray, isReal: Boolean) {
        audioFrameClock++
        framesSinceLastSpeech++

        val shouldHoldAmbient = !inConversation && totalFramesInChunk() >= AMBIENT_SPLIT_MIN_PACKETS
        if (shouldHoldAmbient) {
            holdQueue.addLast(HeldFrame(pkt, isReal))
            if (holdQueue.size <= AMBIENT_HOLD_PACKETS) {
                // 半速 FIFO 蓄水：严格保持帧先后顺序，同时每秒照常落盘维持 UI 写入心跳
                holdIntakeToggle++
                if (holdIntakeToggle % 2 == 0) {
                    val f = holdQueue.removeFirst()
                    writeDirect(f.pkt, f.isReal)
                    holdUnflushedWrites++
                    if (holdUnflushedWrites >= FRAMES_PER_SEC / 2) {
                        flushWriterAudio()
                        holdUnflushedWrites = 0
                    }
                }
            } else {
                val f = holdQueue.removeFirst()
                writeDirect(f.pkt, f.isReal)
                if (holdUnflushedWrites > 0) {
                    holdUnflushedWrites++
                    if (holdUnflushedWrites >= FRAMES_PER_SEC / 2) {
                        flushWriterAudio()
                        holdUnflushedWrites = 0
                    }
                }
            }
            if (totalFramesInChunk() >= AMBIENT_CHUNK_PACKETS) {
                closeChunk()
            }
            return
        }

        if (holdQueue.isNotEmpty()) {
            drainHoldQueueToWriter()
        }
        writeDirect(pkt, isReal)

        val total = totalFramesInChunk()
        if (inConversation) {
            when {
                framesSinceLastSpeech >= CONVERSATION_IDLE_PACKETS -> closeChunk()
                total >= SOFT_CUT_MIN_PACKETS && framesSinceLastSpeech >= SOFT_CUT_PAUSE_PACKETS -> closeChunk()
                total >= HARD_MAX_PACKETS -> closeChunk()
            }
        } else if (total >= AMBIENT_CHUNK_PACKETS) {
            closeChunk()
        }
    }

    private fun splitForConversation() {
        val preroll = ArrayList<HeldFrame>(holdQueue)
        holdQueue.clear()
        holdIntakeToggle = 0
        holdUnflushedWrites = 0
        val carryWins = recentSpeechFrames.size.coerceAtLeast(1)

        // 先无损收尾前一段纯环境音（不含 preroll 里的说话起音，物理零重叠）
        closeWriterAndFinalize()

        // 用 preroll（含开口前 ~1s 环境铺垫 + 刚开口起音）开启新对话段
        ensureWriter(backdateFrames = preroll.size)
        inConversation = true
        framesSinceLastSpeech = 0L
        speechWindowsInChunk = carryWins
        for (f in preroll) {
            writeDirect(f.pkt, f.isReal)
        }
        flushWriterAudio()
    }

    private fun drainHoldQueueToWriter() {
        while (holdQueue.isNotEmpty()) {
            val f = holdQueue.removeFirst()
            writeDirect(f.pkt, f.isReal)
        }
        holdIntakeToggle = 0
        holdUnflushedWrites = 0
    }

    private fun writeDirect(pkt: ByteArray, isReal: Boolean) {
        ensureWriter()
        val w = writer!!
        if (isReal) writtenRealInChunk++
        val before = w.writtenPackets
        w.writePacket(pkt)
        if (w.writtenPackets > before) lastWriteNanos = System.nanoTime()
    }

    private fun flushWriterAudio() {
        writer?.let { w ->
            val before = w.writtenPackets
            w.flushAudio()
            if (w.writtenPackets > before) lastWriteNanos = System.nanoTime()
        }
    }

    /** 断开连接或满足切段条件时调用：收尾当前文件 */
    @Synchronized
    fun closeChunk() {
        if (holdQueue.isNotEmpty()) {
            drainHoldQueueToWriter()
        }
        closeWriterAndFinalize()
    }

    private fun closeWriterAndFinalize() {
        val w = writer
        val f = partFile
        val realCount = writtenRealInChunk
        writtenRealInChunk = 0
        writer = null
        partFile = null
        holdQueue.clear()
        holdIntakeToggle = 0
        holdUnflushedWrites = 0
        framesSinceLastSpeech = 0L
        speechWindowsInChunk = 0
        inConversation = false
        recentSpeechFrames.clear()

        w?.let {
            val before = it.writtenPackets
            it.close()
            if (it.writtenPackets > before) lastWriteNanos = System.nanoTime()
        }
        // 整段都是安静（设备一个音频包都没发）：没什么可听的，直接删掉
        f?.let { if (w != null && realCount == 0) it.delete() else finalize(it) }
    }

    @Synchronized
    fun resetSequence() {
        lastSeq = -1
    }

    private fun ensureWriter(backdateFrames: Int = 0) {
        if (writer != null) return
        val (f, sec) = nextPartFile(backdateFrames)
        writer = OggOpusWriter(f)
        partFile = f
        lastStartEpochSec = sec
    }

    private fun nextPartFile(backdateFrames: Int): Pair<File, Long> {
        val backdateSec = (backdateFrames / FRAMES_PER_SEC).toLong()
        var sec = (nowEpochMs() / 1000L) - backdateSec
        if (sec <= lastStartEpochSec) sec = lastStartEpochSec + 1L
        while (true) {
            val name = Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()).format(NAME_FMT)
            val finalFile = File(dir, "$name.opus")
            val part = File(dir, "$name.opus.part")
            if (!finalFile.exists() && !part.exists()) {
                return part to sec
            }
            sec++
        }
    }

    private fun finalize(part: File) {
        val dest = File(part.parentFile, part.name.removeSuffix(".part"))
        if (part.renameTo(dest)) onChunkReady?.invoke()
    }

    companion object {
        private val NAME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)

        const val FRAMES_PER_SEC = 50
        const val CHUNK_PACKETS = 5 * 60 * FRAMES_PER_SEC               // 5 分钟：无人说话时，每 5 分钟归档一段环境音
        const val AMBIENT_CHUNK_PACKETS = CHUNK_PACKETS
        const val AMBIENT_SPLIT_MIN_PACKETS = 60 * FRAMES_PER_SEC       // 1 分钟：纯环境音满 1 分钟后突然开口，前段先归档
        const val AMBIENT_HOLD_PACKETS = 90                             // 1.8 秒（90 帧）：开口预录缓冲，保证首字前留足铺垫
        const val CONVERSATION_IDLE_PACKETS = 35 * FRAMES_PER_SEC       // 35 秒：对话结束后连续 35 秒无人声即封包上传
        const val SOFT_CUT_MIN_PACKETS = 4 * 60 * FRAMES_PER_SEC        // 4 分钟：长对话满 4 分钟后趁停顿软切防劈句
        const val SOFT_CUT_PAUSE_PACKETS = 125                          // 2.5 秒（125 帧）：长对话软切所需的换气停顿
        const val HARD_MAX_PACKETS = 6 * 60 * FRAMES_PER_SEC            // 6 分钟：持续不停嘴时的硬切上限
        const val MAX_FILL_GAP = 500                                    // 10 秒以内的空缺用静音补（设备安静时每秒声明一次）

        // Silero VAD 窗口 32ms：1.5s 音频跨度（75 帧）内命中 ≥ 14 个窗口（~0.45s 人声），或本段累计 ≥ 38 个窗口（~1.2s）即确认进入对话
        const val ONSET_WINDOW_FRAMES = 75L
        const val ONSET_SPEECH_WINS = 14
        const val TOTAL_SPEECH_WINS_FOR_CONV = 38
    }
}
