package io.github.riggedlab.hark

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * 把一帧帧 Opus 数据封装成标准的 Ogg Opus 文件（RFC 7845），
 * 这样电脑和手机上的播放器、ffmpeg 都能直接打开。
 */
class OggOpusWriter(file: File, inputSampleRate: Int = 16000) : AutoCloseable {

    private val out = BufferedOutputStream(FileOutputStream(file), 16 * 1024)
    private val serial = Random.nextInt()
    private var pageSeq = 0
    private var granule = 0L                    // 48kHz 采样数（含 pre-skip）
    private val pending = ArrayList<ByteArray>()
    var packetCount = 0L
        private set
    /** 已成功写到文件的音频包数，不包括头和仍在内存里等待成页的包。 */
    var writtenPackets = 0L
        private set

    init {
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray())
            put(1)                              // 版本
            put(1)                              // 声道数
            putShort(PRE_SKIP.toShort())
            putInt(inputSampleRate)
            putShort(0)                         // 输出增益
            put(0)                              // 声道映射族
        }.array()
        writePage(listOf(head), granulePos = 0, headerType = 0x02)   // BOS

        val vendor = "hark-dt1".toByteArray()
        val tags = ByteBuffer.allocate(8 + 4 + vendor.size + 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusTags".toByteArray())
            putInt(vendor.size)
            put(vendor)
            putInt(0)                           // 没有用户注释
        }.array()
        writePage(listOf(tags), granulePos = 0, headerType = 0)
    }

    /** 写入一帧 Opus。samples48k = 这一帧对应的 48kHz 采样数（20ms = 960）。 */
    fun writePacket(packet: ByteArray, samples48k: Int = 960) {
        pending.add(packet)
        granule += samples48k
        packetCount++
        if (pending.size >= PACKETS_PER_PAGE) flush(eos = false)
    }

    /** 时长（秒），不含 pre-skip */
    val durationSeconds: Double get() = (granule).toDouble() / 48000.0

    private fun flush(eos: Boolean) {
        if (pending.isEmpty() && !eos) return
        writePage(pending.toList(), granule, if (eos) 0x04 else 0)
        out.flush()
        writtenPackets += pending.size
        pending.clear()
    }

    /** 安静声明通常每秒一次，及时写出不足一页的静音。 */
    fun flushAudio() = flush(eos = false)

    private fun writePage(packets: List<ByteArray>, granulePos: Long, headerType: Int) {
        val lacing = ArrayList<Byte>()
        for (p in packets) {
            var n = p.size
            while (n >= 255) { lacing.add(255.toByte()); n -= 255 }
            lacing.add(n.toByte())
        }
        require(lacing.size <= 255) { "too many segments" }
        val bodyLen = packets.sumOf { it.size }
        val page = ByteBuffer.allocate(27 + lacing.size + bodyLen).order(ByteOrder.LITTLE_ENDIAN)
        page.put("OggS".toByteArray())
        page.put(0)                               // 版本
        page.put(headerType.toByte())
        page.putLong(granulePos)
        page.putInt(serial)
        page.putInt(pageSeq++)
        page.putInt(0)                            // CRC 占位
        page.put(lacing.size.toByte())
        lacing.forEach { page.put(it) }
        packets.forEach { page.put(it) }
        val bytes = page.array()
        val crc = crc32(bytes)
        bytes[22] = (crc and 0xff).toByte()
        bytes[23] = ((crc ushr 8) and 0xff).toByte()
        bytes[24] = ((crc ushr 16) and 0xff).toByte()
        bytes[25] = ((crc ushr 24) and 0xff).toByte()
        out.write(bytes)
    }

    override fun close() {
        flush(eos = true)
        out.flush()
        out.close()
    }

    companion object {
        const val PRE_SKIP = 312                  // 6.5ms 编码器前瞻
        const val PACKETS_PER_PAGE = 50           // 每页约 1 秒

        /** 一帧"空"的 SILK 宽带 20ms 包（只有 TOC 字节），解码器会当作丢包补偿 */
        val SILENT_PACKET = byteArrayOf(0x48)

        private val CRC_TABLE = IntArray(256) { i ->
            var r = i shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            r
        }

        private fun crc32(data: ByteArray): Int {
            var crc = 0
            for (b in data) {
                crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (b.toInt() and 0xff)) and 0xff]
            }
            return crc
        }
    }
}
