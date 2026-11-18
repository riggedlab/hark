package io.github.riggedlab.hark

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TranscriptsTest {
    @Test
    fun untimedTranscriptWithOnlyRepetitionIsRejected() {
        val segments = JSONArray()
        repeat(32) { i ->
            segments.put(JSONObject().put("text", if (i % 2 == 0) " 啊!" else "啊！").put("speaker", "other_$i"))
        }
        val root = JSONObject().put("duration_s", 300).put("text", "啊！".repeat(32)).put("segments", segments)

        assertNotNull(Transcripts.sanitizeAsr(root))
    }

    @Test
    fun foldsRepetitionAndPreservesTextBeforeAndAfterAcrossTimedSegments() {
        val parts = listOf("比赛开始。") + List(32) { if (it % 2 == 0) "加油！" else "加油!" } + "比赛结束。"
        val segments = JSONArray(parts.mapIndexed { i, text ->
            JSONObject().put("text", text).put("start", i).put("end", i + 1).put("speaker", "other_$i")
        })
        val root = JSONObject().put("duration_s", 34).put("text", parts.joinToString("")).put("segments", segments)

        assertNull(Transcripts.sanitizeAsr(root))
        assertEquals("比赛开始。加油！比赛结束。", root.getString("text"))
        val clean = root.getJSONArray("segments")
        assertEquals(3, clean.length())
        assertEquals("比赛开始。", clean.getJSONObject(0).getString("text"))
        assertEquals("加油！", clean.getJSONObject(1).getString("text"))
        assertEquals("比赛结束。", clean.getJSONObject(2).getString("text"))
        assertEquals(33, clean.getJSONObject(2).getInt("start"))
        assertEquals("other_33", clean.getJSONObject(2).getString("speaker"))
    }

    @Test
    fun foldsFullTextWhenOnlySegmentsExposeTheRepetition() {
        val parts = listOf("比赛开始", *Array(50) { "加油" }, "比赛结束")
        val segments = JSONArray(parts.map { JSONObject().put("text", it) })
        val root = JSONObject().put("duration_s", 2).put("text", parts.joinToString("")).put("segments", segments)

        assertNull(Transcripts.sanitizeAsr(root))
        assertEquals("比赛开始加油比赛结束", root.getString("text"))
        assertEquals(3, root.getJSONArray("segments").length())
    }

    @Test
    fun foldsInsideSingleSegmentAndKeepsNormalRepetition() {
        val before = "对，对，对，我们继续。"
        val after = "大家坐好。"
        val text = before + "加油！".repeat(100) + after
        val root = PhoneAsr.buildTranscriptJson("20261113-140000.opus", 2.0, text, "dashscope", "model", emptyList())

        assertNull(Transcripts.sanitizeAsr(root))
        assertEquals(before + "加油！" + after, root.getString("text"))
        assertEquals(root.getString("text"), root.getJSONArray("segments").getJSONObject(0).getString("text"))
    }

    @Test
    fun storageUsesActualOggDurationRecordsRejectionAndPreservesExistingTranscript() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val chunks = RecorderService.chunkDir(ctx).apply { mkdirs() }
        val name = "20261113-140000.opus"
        ctx.assets.open("probe.opus").use { input ->
            File(chunks, name).outputStream().use { input.copyTo(it) }
        }
        val text = (1..80).joinToString(" ") { "item$it" }
        val bad = JSONObject().put("name", name).put("duration_s", 120).put(
            "segments", JSONArray().put(JSONObject().put("text", text).put("start", 0).put("end", 120)),
        )
        assertNull(Transcripts.sanitizeAsr(bad))

        Transcripts.save(ctx, JSONObject(bad.toString()))
        assertNull(Transcripts.get(ctx, name))
        assertFalse(File(Transcripts.dir(ctx), "$name.json").exists())
        assertTrue(File(ctx.filesDir, "asr_no_retry/$name").readText().contains("文字密度异常"))

        val good = "继续讨论今天的安排。"
        Transcripts.save(ctx, JSONObject().put("name", name).put("text", good))
        Transcripts.save(ctx, bad)
        assertEquals(good, Transcripts.get(ctx, name)?.text)
    }
}
