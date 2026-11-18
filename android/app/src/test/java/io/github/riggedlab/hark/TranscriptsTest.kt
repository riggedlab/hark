package io.github.riggedlab.hark

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TranscriptsTest {
    @Test
    fun discardsStuckSentenceRunEvenWithDifferentSpeakersAndPunctuation() {
        val segments = JSONArray()
        repeat(32) { i ->
            segments.put(JSONObject().put("text", if (i % 2 == 0) " 啊!" else "啊！").put("speaker", "other_$i"))
        }
        val root = JSONObject().put("duration_s", 300).put("text", "啊！").put("segments", segments)

        assertTrue(Transcripts.rejectRunawayAsr(root))
        assertEquals("", root.getString("text"))
        assertEquals(0, root.getJSONArray("segments").length())
        assertEquals(300, root.getInt("duration_s"))
    }

    @Test
    fun checksTextOnlyResponsesAndKeepsNormalRepetition() {
        val runaway = JSONObject().put("duration_s", 120).put("text", "我说完了。".repeat(32))
        assertTrue(Transcripts.rejectRunawayAsr(runaway))

        val normal = JSONObject().put("duration_s", 1).put("text", "对，对，对，我们继续。")
        assertFalse(Transcripts.rejectRunawayAsr(normal))
        assertEquals("对，对，对，我们继续。", normal.getString("text"))
    }

    @Test
    fun storageUsesActualOggDurationAndPreservesAnExistingTranscript() {
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
        assertFalse(Transcripts.rejectRunawayAsr(bad))

        Transcripts.save(ctx, JSONObject(bad.toString()))
        val empty = requireNotNull(Transcripts.get(ctx, name))
        assertEquals("", empty.text)
        assertTrue(empty.segments.isEmpty())

        val good = "继续讨论今天的安排。"
        Transcripts.save(ctx, JSONObject().put("name", name).put("text", good))
        Transcripts.save(ctx, bad)
        assertEquals(good, Transcripts.get(ctx, name)?.text)
        Transcripts.save(ctx, bad, alreadyRejected = true)
        assertEquals(good, Transcripts.get(ctx, name)?.text)
    }
}
