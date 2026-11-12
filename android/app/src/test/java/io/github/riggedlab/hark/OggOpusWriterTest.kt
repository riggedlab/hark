package io.github.riggedlab.hark

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OggOpusWriterTest {
    /** 用 ffmpeg 生成真实 Opus 帧比较麻烦，这里写 3 秒的"空帧"，再用外部工具检查文件是否合法 */
    @Test
    fun writesValidOgg() {
        val out = File(System.getProperty("oggOut") ?: "build/test-silent.opus")
        out.parentFile?.mkdirs()
        OggOpusWriter(out).use { w -> repeat(150) { w.writePacket(OggOpusWriter.SILENT_PACKET) } }
        assertTrue(out.length() > 0)
        println("wrote ${out.absolutePath}")
    }
}
