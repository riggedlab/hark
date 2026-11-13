package io.github.riggedlab.hark

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class ChunkRecorderTest {
    private val audio = byteArrayOf(0x48, 1, 2, 3)

    /** 从生成的 Ogg Opus 文件里解出所有音频包（跳过前 2 页 OpusHead / OpusTags），用于帧级保序与无重叠校验 */
    private fun readOggPackets(file: File): List<ByteArray> {
        val data = file.readBytes()
        val out = ArrayList<ByteArray>()
        var pos = 0
        var pageIdx = 0
        while (pos + 27 <= data.size) {
            assertEquals("OggS", String(data, pos, 4, Charsets.US_ASCII))
            val segCount = data[pos + 26].toInt() and 0xff
            val lacingPos = pos + 27
            val bodyPos = lacingPos + segCount
            var bodyLen = 0
            for (i in 0 until segCount) bodyLen += data[lacingPos + i].toInt() and 0xff
            if (pageIdx >= 2) {
                var p = bodyPos
                var curLen = 0
                for (i in 0 until segCount) {
                    val l = data[lacingPos + i].toInt() and 0xff
                    curLen += l
                    if (l < 255) {
                        out.add(data.copyOfRange(p, p + curLen))
                        p += curLen
                        curLen = 0
                    }
                }
            }
            pageIdx++
            pos = bodyPos + bodyLen
        }
        return out
    }

    private fun taggedPacket(index: Int): ByteArray =
        ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN).put(0x48).putInt(index).array()

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
        try {
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
        } finally { dir.deleteRecursively() }
    }

    @Test fun silenceDeclarationWritesAndUpdatesHeartbeatImmediately() {
        val dir = Files.createTempDirectory("chunks-silence").toFile()
        try {
            val c = ChunkRecorder(dir)
            assertTrue(c.onPacket(29, ByteArray(0)))
            assertTrue(c.lastWriteNanos != 0L)
            assertTrue(dir.listFiles()!!.single().length() > 100)
            val written = c.lastWriteNanos
            assertFalse(c.onPacket(28, ByteArray(0)))
            assertEquals(written, c.lastWriteNanos)
            c.closeChunk()
            assertEquals(0, dir.listFiles()!!.size) // 仍维持整段安静就删除的行为
        } finally { dir.deleteRecursively() }
    }

    @Test fun fileWriteFailureNeverReportsWritten() {
        val notADirectory = Files.createTempFile("chunks-unwritable", ".tmp").toFile()
        try {
            val c = ChunkRecorder(notADirectory)
            assertThrows(java.io.IOException::class.java) { c.onPacket(0, audio) }
            assertEquals(0L, c.lastWriteNanos)
        } finally { notADirectory.delete() }
    }

    @Test fun silenceDeclarationsFillTimelineAndLossIsCountedSeparately() {
        val dir = Files.createTempDirectory("chunks").toFile()
        try {
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
        } finally { dir.deleteRecursively() }
    }

    @Test fun allSilentChunkIsDeleted() {
        val dir = Files.createTempDirectory("chunks").toFile()
        try {
            val c = ChunkRecorder(dir)
            for (k in 1..10) c.onPacket(k * 50, ByteArray(0))
            c.closeChunk()
            assertEquals(0, dir.listFiles()!!.count { it.name.endsWith(".opus") || it.name.endsWith(".part") })
        } finally { dir.deleteRecursively() }
    }

    @Test fun seqWrapAround() {
        val dir = Files.createTempDirectory("chunks").toFile()
        try {
            val c = ChunkRecorder(dir)
            c.onPacket(65530, audio)
            c.onPacket(20, ByteArray(0))            // 跨过 65535 回到 0
            c.onPacket(21, audio)
            assertEquals(26, c.silentFrames)        // 65531..65535 共 5 帧 + 0..20 共 21 帧
            assertEquals(0, c.lostPackets)
            c.closeChunk()
        } finally { dir.deleteRecursively() }
    }

    @Test fun shortMemoClosesAfter35sIdleWithout45sSpeechGate() {
        val dir = Files.createTempDirectory("chunks-short-memo").toFile()
        try {
            var fakeMs = 1_760_000_000_000L
            var ready = 0
            val c = ChunkRecorder(dir, onChunkReady = { ready++ }, nowEpochMs = { fakeMs })
            var seq = 0

            // 开口说一句 6 秒的口头备忘（无需攒满 45 秒）
            repeat(6 * 50) { i ->
                c.onPacket(seq++ and 0xffff, audio)
                if (i % 2 == 0) c.onSpeechDetected()
                fakeMs += 20
            }
            assertEquals(0, ready)

            // 中途思考停顿 20 秒，再补一句 2 秒的话：同一场话绝不断开
            repeat(20 * 50) {
                c.onPacket(seq++ and 0xffff, audio) // 哪怕固件仍在发敲键盘底噪包
                fakeMs += 20
            }
            assertEquals(0, ready)
            repeat(2 * 50) { i ->
                c.onPacket(seq++ and 0xffff, audio)
                if (i % 2 == 0) c.onSpeechDetected()
                fakeMs += 20
            }
            assertEquals(0, ready)

            // 说完后连续 34 秒无人声：尚未满 35 秒，不切
            repeat(34 * 50) {
                c.onPacket(seq++ and 0xffff, audio)
                fakeMs += 20
            }
            assertEquals(0, ready)

            // 满第 35 秒无人声：立即自动收尾封包上传！
            repeat(50) {
                c.onPacket(seq++ and 0xffff, audio)
                fakeMs += 20
            }
            assertEquals(1, ready)
            val opus = dir.listFiles { f -> f.name.endsWith(".opus") }!!.single()
            assertTrue(Crypto.audioName.matches(opus.name))
            assertEquals((6 + 20 + 2 + 35).toDouble(), Recordings.oggDurationSec(opus), 0.2)
        } finally { dir.deleteRecursively() }
    }

    @Test fun briefCoughOrNoiseStaysIn5MinAmbientChunk() {
        val dir = Files.createTempDirectory("chunks-cough").toFile()
        try {
            var fakeMs = 1_760_000_100_000L
            var ready = 0
            val c = ChunkRecorder(dir, onChunkReady = { ready++ }, nowEpochMs = { fakeMs })
            var seq = 0

            // 第 10 秒咳了一声（仅触发 6 个 32ms 窗口 < 14 窗口门槛）
            repeat(10 * 50) { c.onPacket(seq++ and 0xffff, audio); fakeMs += 20 }
            repeat(15) { i ->
                c.onPacket(seq++ and 0xffff, audio)
                if (i < 6) c.onSpeechDetected()
                fakeMs += 20
            }
            // 随后 60 秒敲键盘无人声：不能误当作对话结束切碎，必须继续留在当前环境音片段里
            repeat(60 * 50) { c.onPacket(seq++ and 0xffff, audio); fakeMs += 20 }
            assertEquals(0, ready)

            // 一直录满 5 分钟（15,000 帧），完整归档为 1 段 300 秒的环境音文件供 SceneAndMood 扫描
            val remain = ChunkRecorder.AMBIENT_CHUNK_PACKETS - (10 * 50 + 15 + 60 * 50)
            repeat(remain) {
                val prevWrite = c.lastWriteNanos
                c.onPacket(seq++ and 0xffff, audio)
                fakeMs += 20
                if (seq % 50 == 0) assertTrue("每秒都应持续刷盘维持心跳", c.lastWriteNanos >= prevWrite && c.lastWriteNanos != 0L)
            }
            assertEquals(1, ready)
            val opus = dir.listFiles { f -> f.name.endsWith(".opus") }!!.single()
            assertEquals(300.0, Recordings.oggDurationSec(opus), 0.2)
        } finally { dir.deleteRecursively() }
    }

    @Test fun ambientOver1MinSplitsLosslesslyWithPrerollWhenSpeechStarts() {
        val dir = Files.createTempDirectory("chunks-split").toFile()
        try {
            var fakeMs = 1_760_001_000_000L
            val readyFiles = ArrayList<String>()
            val c = ChunkRecorder(
                dir,
                onChunkReady = {
                    val names = dir.listFiles { f -> f.name.endsWith(".opus") }!!.map { it.name }.sorted()
                    readyFiles.clear()
                    readyFiles.addAll(names)
                },
                nowEpochMs = { fakeMs },
            )

            var pktIdx = 0
            // 1. 先录 120 秒（6,000 帧）纯敲键盘环境音，同时验证跨越 60s 蓄水期每秒写入心跳不中断
            var lastCheckNanos = 0L
            repeat(120 * 50) {
                c.onPacket(pktIdx and 0xffff, taggedPacket(pktIdx))
                pktIdx++
                fakeMs += 20
                if (pktIdx % 50 == 0) {
                    assertTrue("frame $pktIdx heartbeat must advance every second", c.lastWriteNanos > lastCheckNanos)
                    lastCheckNanos = c.lastWriteNanos
                }
            }
            assertTrue(readyFiles.isEmpty())

            // 2. 第 120.0 秒突然开口说话：在 0.6 秒（30 帧）内触发 14 个 VAD 窗口确认对话开始
            val speechStartIdx = pktIdx
            repeat(30) { i ->
                c.onPacket(pktIdx and 0xffff, taggedPacket(pktIdx))
                pktIdx++
                fakeMs += 20
                if (i % 2 == 0) c.onSpeechDetected() // 15 个窗口 >= 14，在第 27 帧触发 splitForConversation
            }
            // 前半段纯环境音应当已经独立封存！
            assertEquals(1, readyFiles.size)

            // 3. 继续说 5 秒话，然后冷场 35 秒自动收尾对话段
            repeat(5 * 50) { i ->
                c.onPacket(pktIdx and 0xffff, taggedPacket(pktIdx))
                pktIdx++
                fakeMs += 20
                if (i % 2 == 1) c.onSpeechDetected()
            }
            repeat(35 * 50) {
                c.onPacket(pktIdx and 0xffff, taggedPacket(pktIdx))
                pktIdx++
                fakeMs += 20
            }
            assertEquals(2, readyFiles.size)

            val files = dir.listFiles { f -> f.name.endsWith(".opus") }!!.sortedBy { it.name }
            assertEquals(2, files.size)
            files.forEach { assertTrue(Crypto.audioName.matches(it.name)) }

            val ambientPkts = readOggPackets(files[0])
            val convPkts = readOggPackets(files[1])

            // 核心不变量 1：前半段环境音在开口前 ~1.2 秒处干净收尾，绝不包含任何开口后的帧！
            assertTrue("环境音段不该混入说话起音", ambientPkts.size < speechStartIdx)
            val prerollBeforeSpeech = speechStartIdx - ambientPkts.size
            assertTrue("新对话段开头应保留至少 1 秒开口前铺垫 (实际 ${prerollBeforeSpeech * 20}ms)", prerollBeforeSpeech >= 50)

            // 核心不变量 2：两段拼起来与原始帧流严格 1:1 相等（0 丢帧、0 重叠、0 乱序）！
            val combined = ambientPkts + convPkts
            assertEquals(pktIdx, combined.size)
            for (i in 0 until pktIdx) {
                assertArrayEquals("frame $i mismatch", taggedPacket(i), combined[i])
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun pureSilenceBeforeLateSpeechDeletesEmptyPrefixChunk() {
        val dir = Files.createTempDirectory("chunks-silent-prefix").toFile()
        try {
            var fakeMs = 1_760_002_000_000L
            var ready = 0
            val c = ChunkRecorder(dir, onChunkReady = { ready++ }, nowEpochMs = { fakeMs })
            var seq = 49

            // 前 2 分钟固件全程只发每秒一次的安静声明（0 真实音频包）
            repeat(120) {
                c.onPacket(seq and 0xffff, ByteArray(0))
                seq += 50
                fakeMs += 1000
            }
            assertEquals(0, ready)

            // 突然开口说 3 秒话
            var pktSeq = seq - 49
            repeat(3 * 50) { i ->
                c.onPacket(pktSeq++ and 0xffff, audio)
                if (i % 2 == 0) c.onSpeechDetected()
                fakeMs += 20
            }
            // 前 2 分钟纯静音空壳应直接删除，不触发 ready、不留垃圾文件
            assertEquals(0, ready)
            assertEquals(0, dir.listFiles { f -> f.name.endsWith(".opus") }!!.size)

            // 冷场 35 秒后，只产出一个干净的对话 .opus
            repeat(35 * 50) {
                c.onPacket(pktSeq++ and 0xffff, audio)
                fakeMs += 20
            }
            assertEquals(1, ready)
            assertEquals(1, dir.listFiles { f -> f.name.endsWith(".opus") }!!.size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun longConversationSoftCutsOnPauseAfter4MinAndHardCapsAt6Min() {
        val dir = Files.createTempDirectory("chunks-long-talk").toFile()
        try {
            var fakeMs = 1_760_003_000_000L
            var ready = 0
            val c = ChunkRecorder(dir, onChunkReady = { ready++ }, nowEpochMs = { fakeMs })
            var seq = 0

            // 连续讲话 5 分 10 秒（中间停顿都 < 1 秒），跨过 4 分钟和 5 分钟时绝不能硬劈句子
            repeat(310 * 50) { i ->
                c.onPacket(seq++ and 0xffff, audio)
                if (i % 10 == 0) c.onSpeechDetected()
                fakeMs += 20
            }
            assertEquals(0, ready)

            // 出现 2.5 秒（125 帧）换气停顿，立即触发防劈句软切！
            repeat(ChunkRecorder.SOFT_CUT_PAUSE_PACKETS) {
                c.onPacket(seq++ and 0xffff, audio)
                fakeMs += 20
            }
            assertEquals(1, ready)

            // 下一段如果连讲满 6 分钟一秒都不停，在 6 分钟上限兜底硬切
            repeat(ChunkRecorder.HARD_MAX_PACKETS) { i ->
                c.onPacket(seq++ and 0xffff, audio)
                if (i % 5 == 0) c.onSpeechDetected()
                fakeMs += 20
            }
            assertEquals(2, ready)
            val files = dir.listFiles { f -> f.name.endsWith(".opus") }!!.sortedBy { it.name }
            assertEquals(2, files.size)
            assertEquals(312.5, Recordings.oggDurationSec(files[0]), 0.2)
            assertEquals(360.0, Recordings.oggDurationSec(files[1]), 0.2)
        } finally { dir.deleteRecursively() }
    }
}
