package io.github.riggedlab.hark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoodThresholdTest {
    @Test
    fun onlyConfidentMoodIsShown() {
        assertNull(shownMood(null))
        assertNull(shownMood(0.57f))   // 以前会显示「愉悦」，抽查对错参半
        assertNull(shownMood(-0.45f))  // 以前会显示「语气急促」，多是普通陈述
        assertEquals("positive", shownMood(0.6f))
        assertEquals("negative", shownMood(-0.72f))
    }
}
