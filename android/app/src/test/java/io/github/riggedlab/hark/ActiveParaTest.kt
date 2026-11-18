package io.github.riggedlab.hark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

class ActiveParaTest {
    private val t0 = LocalDateTime.of(2026, 11, 18, 22, 0)
    private val rec = Recording(File("20261118-220000.opus"), t0, 60.0, hasAudio = true)

    private fun para(key: String, vararg spans: Pair<Double, Double>) = ParaUi(
        key = key,
        lines = spans.map { (s, e) -> LineUi(rec, Segment(s, e, "x"), t0, t0) },
        who = null, speakerId = null, isSelf = false, text = "x", textLower = "x", whoLower = null,
        startAt = t0, timeHm = "22:00", durationSec = 1, hasAnyAudio = true, avgDb = null,
        proximity = null, voiceCue = null, affectCue = null, isPositive = false, isTense = false,
        isBackgroundNoise = false,
    )

    @Test
    fun onlyOneBubbleActiveAcrossPaddingOverlapAndWholeFileFallback() {
        val paras = listOf(
            para("whole", 0.0 to 60.0),           // 没有逐句时间的整段兜底句
            para("a", 10.0 to 12.0),
            para("b", 12.1 to 15.0),              // 与 a 的余量区间重叠
            para("c", 13.0 to 14.0),              // 与 b 时间重叠（多人同时说）
        ).asSequence()
        assertEquals("b", activeParaKey(paras, rec.name, 12.1)) // 点 b：a 的尾余量也覆盖这里
        assertEquals("a", activeParaKey(paras, rec.name, 10.0))
        assertEquals("c", activeParaKey(paras, rec.name, 13.5))
        assertEquals("whole", activeParaKey(paras, rec.name, 30.0))
        assertNull(activeParaKey(paras, "other.opus", 10.0))
    }
}
