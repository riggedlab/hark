package io.github.riggedlab.hark

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class FinalChunkUploadTest {
    @Test fun stoppingClosesThenEncryptsLastChunkWithoutWaitingForNetwork() {
        val dir = Files.createTempDirectory("last-chunk").toFile()
        val io = Executors.newSingleThreadExecutor()
        val allowClose = CountDownLatch(1)
        val uploaded = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        try {
            val chunks = ChunkRecorder(dir)
            chunks.onPacket(0, byteArrayOf(0x48, 1, 2, 3))
            io.execute { allowClose.await() }
            finishRecording(io, chunks::closeChunk) {
                try {
                    assertTrue(Thread.currentThread().isDaemon)
                    val file = dir.listFiles { f -> f.name.endsWith(".opus") }!!.single()
                    assertFalse(dir.listFiles()!!.any { it.name.endsWith(".part") })
                    val key = ByteArray(32) { it.toByte() }
                    val packet = Crypto.packAudio(key, file.name, file.readBytes())
                    assertArrayEquals(file.readBytes(), Crypto.unpackAudio(key, Crypto.itemId(key, file.name), packet).second)
                } catch (t: Throwable) { error.set(t) }
                finally { uploaded.countDown() }
            }
            assertEquals(1L, uploaded.count) // 封包尚被队列挡住，销毁调用已返回
            io.shutdown()
            allowClose.countDown()
            assertTrue(uploaded.await(5, TimeUnit.SECONDS))
            error.get()?.let { throw it }
        } finally {
            allowClose.countDown()
            io.shutdownNow()
            io.awaitTermination(5, TimeUnit.SECONDS)
            dir.deleteRecursively()
        }
    }
}
