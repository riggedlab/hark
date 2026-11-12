package io.github.riggedlab.hark

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把连续到来的 Opus 包按 5 分钟一段切成文件。
 * 文件先写成 .part，写完改名成 .opus，所以列表里看到的 .opus 都是完整的。
 */
class ChunkRecorder(private val dir: File, private val onChunkReady: (() -> Unit)? = null) {

    private var writer: OggOpusWriter? = null
    private var partFile: File? = null
    private var lastSeq = -1
    var lostPackets = 0L
        private set
    var totalPackets = 0L
        private set
    /** 设备安静时没有发送、由手机补静音的帧数 */
    var silentFrames = 0L
        private set
    var lastWriteNanos = 0L
        private set

    init {
        dir.mkdirs()
        // 上次异常退出留下的半截文件：Ogg 是流式格式，前面的部分仍然能播放，直接转正
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { finalize(it) }
    }

    val currentFileName: String? get() = partFile?.name?.removeSuffix(".part")
    val currentSeconds: Double get() = writer?.durationSeconds ?: 0.0

    /** 当前这段文件里有几个真正的音频包（不算补的静音） */
    private var realInChunk = 0

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
        repeat(fill) { write(OggOpusWriter.SILENT_PACKET) }
        if (silence) silentFrames += fill else lostPackets += fill
        if (!silence) {
            totalPackets++
            realInChunk++
            write(opus)
        } else {
            writer?.let { w ->
                val before = w.writtenPackets
                w.flushAudio()
                if (w.writtenPackets > before) lastWriteNanos = System.nanoTime()
            }
        }
        return true
    }

    private fun write(pkt: ByteArray) {
        ensureWriter()
        val w = writer!!
        val before = w.writtenPackets
        w.writePacket(pkt)
        if (w.writtenPackets > before) lastWriteNanos = System.nanoTime()
        if (w.packetCount >= CHUNK_PACKETS) closeChunk()
    }

    /** 断开连接时调用：收尾当前文件 */
    @Synchronized
    fun closeChunk() {
        writer?.let { w ->
            val before = w.writtenPackets
            w.close()
            if (w.writtenPackets > before) lastWriteNanos = System.nanoTime()
        }
        // 整段都是安静（设备一个音频包都没发）：没什么可听的，直接删掉
        partFile?.let { if (writer != null && realInChunk == 0) it.delete() else finalize(it) }
        realInChunk = 0
        writer = null
        partFile = null
    }

    @Synchronized
    fun resetSequence() {
        lastSeq = -1
    }

    private fun ensureWriter() {
        if (writer != null) return
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(dir, "$name.opus.part")
        partFile = f
        writer = OggOpusWriter(f)
    }

    private fun finalize(part: File) {
        val dest = File(part.parentFile, part.name.removeSuffix(".part"))
        if (part.renameTo(dest)) onChunkReady?.invoke()
    }

    companion object {
        const val CHUNK_PACKETS = 5 * 60 * 50       // 5 分钟 × 每秒 50 帧
        const val MAX_FILL_GAP = 500                // 10 秒以内的空缺用静音补（设备安静时每秒声明一次）
    }
}
