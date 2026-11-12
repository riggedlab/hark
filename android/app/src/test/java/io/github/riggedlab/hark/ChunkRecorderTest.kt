package io.github.riggedlab.hark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class ChunkRecorderTest {
    private val audio = byteArrayOf(0x48, 1, 2, 3)

    @Test fun chunkReadyRunsOnlyAfterCompletedAudioIsVisible() {
        val dir = Files.createTempDirectory("chunks-ready").toFile()
        try {
            var ready = 0
            val c = ChunkRecorder(dir) {
                assertEquals(0, dir.listFiles { f -> f.name.endsWith(".part") }!!.size)
                assertTrue(dir.listFiles { f -> f.name.endsWith(".opus") }!!.single().length() > 0)
                ready++
            }
            c.onPacket(0, audio)
            assertEquals(0, ready)
            c.closeChunk()
            c.closeChunk()
            assertEquals(1, ready)
            dir.listFiles()!!.forEach { it.delete() }
            c.onPacket(1, ByteArray(0))
            c.closeChunk()
            assertEquals(1, ready) // 整段静音直接删除，不触发上传
        } finally { dir.deleteRecursively() }
    }

    @Test fun writeHeartbeatRequiresAudioPageToReachFile() {
        val dir = Files.createTempDirectory("chunks-written").toFile()
        val c = ChunkRecorder(dir)
        repeat(49) { assertTrue(c.onPacket(it, audio)) }
        assertEquals(0L, c.lastWriteNanos) // 内存中排队、只收到蓝牙帧都不算录上
        assertTrue(c.onPacket(49, audio))
        assertTrue(c.lastWriteNanos != 0L)
        assertTrue(dir.listFiles()!!.single().length() > 100)
        val written = c.lastWriteNanos
        assertFalse(c.onPacket(49, audio)) // 重复/过期的数据不能续命
        assertEquals(written, c.lastWriteNanos)
        c.closeChunk()
    }

    @Test fun silenceDeclarationWritesAndUpdatesHeartbeatImmediately() {
        val dir = Files.createTempDirectory("chunks-silence").toFile()
        val c = ChunkRecorder(dir)
        assertTrue(c.onPacket(29, ByteArray(0)))
        assertTrue(c.lastWriteNanos != 0L)
        assertTrue(dir.listFiles()!!.single().length() > 100)
        val written = c.lastWriteNanos
        assertFalse(c.onPacket(28, ByteArray(0)))
        assertEquals(written, c.lastWriteNanos)
        c.closeChunk()
        assertEquals(0, dir.listFiles()!!.size) // 仍维持整段安静就删除的行为
    }

    @Test fun fileWriteFailureNeverReportsWritten() {
        val notADirectory = Files.createTempFile("chunks-unwritable", ".tmp").toFile()
        val c = ChunkRecorder(notADirectory)
        assertThrows(java.io.IOException::class.java) { c.onPacket(0, audio) }
        assertEquals(0L, c.lastWriteNanos)
    }

    @Test fun silenceDeclarationsFillTimelineAndLossIsCountedSeparately() {
        val dir = Files.createTempDirectory("chunks").toFile()
        val c = ChunkRecorder(dir)
        c.onPacket(29, ByteArray(0))            // 第一次声明：这一帧是安静
        c.onPacket(79, ByteArray(0))            // 1 秒后：30..79 都是安静
        for (s in 80..99) c.onPacket(s, audio)  // 开口（含补发的缓存）
        c.onPacket(101, audio)                  // 100 丢了
        c.onPacket(90, ByteArray(0))            // 旧序号的声明，忽略
        c.closeChunk()
        assertEquals(51, c.silentFrames)
        assertEquals(1, c.lostPackets)
        assertEquals(21, c.totalPackets)
        assertEquals(1, dir.listFiles { f -> f.name.endsWith(".opus") }!!.size)
    }

    @Test fun allSilentChunkIsDeleted() {
        val dir = Files.createTempDirectory("chunks").toFile()
        val c = ChunkRecorder(dir)
        for (k in 1..10) c.onPacket(k * 50, ByteArray(0))
        c.closeChunk()
        assertEquals(0, dir.listFiles()!!.count { it.name.endsWith(".opus") || it.name.endsWith(".part") })
    }

    @Test fun seqWrapAround() {
        val dir = Files.createTempDirectory("chunks").toFile()
        val c = ChunkRecorder(dir)
        c.onPacket(65530, audio)
        c.onPacket(20, ByteArray(0))            // 跨过 65535 回到 0
        c.onPacket(21, audio)
        assertEquals(26, c.silentFrames)    // 65531..65535 共 5 帧 + 0..20 共 21 帧
        assertEquals(0, c.lostPackets)
    }
}
