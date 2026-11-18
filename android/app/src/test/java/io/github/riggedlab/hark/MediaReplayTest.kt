package io.github.riggedlab.hark

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class MediaReplayTest {
    @Test
    fun replay() {
        val manifestPath = System.getenv("HARK_MEDIA_REPLAY_MANIFEST")
        assumeTrue("Run tools/eval_media_proximity.py to supply a phone export", !manifestPath.isNullOrBlank())
        val manifest = JSONObject(File(requireNotNull(manifestPath)).readText())
        val texts = File(manifest.getString("texts"))

        fun read(name: String): JSONObject = JSONObject(File(texts, "$name.opus.json").readText())

        val result = buildString {
            for (group in listOf("media", "self")) {
                val names = manifest.getJSONArray(group)
                var total = 0
                var before = 0
                var after = 0
                var newlyMedia = 0
                for (i in 0 until names.length()) {
                    val name = names.getString(i)
                    val segments = read(name).getJSONArray("segments")
                    val turns = List(segments.length()) { idx ->
                        val segment = segments.getJSONObject(idx)
                        Proximity.Turn(
                            start = segment.getDouble("start"),
                            end = segment.getDouble("end"),
                            db = segment.optDouble("db").takeIf { it.isFinite() }?.toFloat(),
                            label = segment.optString("proximity").takeIf { it.isNotBlank() },
                        )
                    }
                    val updated = Proximity.refineMediaTurns(turns)
                    val clipBefore = turns.count { it.label == Proximity.MEDIA }
                    val clipAfter = updated.count { it == Proximity.MEDIA }
                    appendLine("$group $name: $clipBefore -> $clipAfter media / ${turns.size} sentences")
                    total += turns.size
                    before += clipBefore
                    after += clipAfter
                    newlyMedia += updated.indices.count { updated[it] == Proximity.MEDIA && turns[it].label != Proximity.MEDIA }
                }
                assertTrue("Expected sentences in $group", total > 0)
                if (group == "media") {
                    appendLine(String.format(Locale.ROOT, "Video recall: %d/%d (%.1f%%) -> %d/%d (%.1f%%)",
                        before, total, 100.0 * before / total, after, total, 100.0 * after / total))
                } else {
                    appendLine("Wearer mislabeled media: $before/$total -> $after/$total ($newlyMedia new)")
                }
            }

            val runaway = manifest.getString("runaway")
            if (runaway.isNotBlank()) {
                val transcript = read(runaway)
                val before = transcript.getJSONArray("segments").length()
                val discarded = Transcripts.sanitizeAsr(transcript) != null
                val after = if (discarded) 0 else transcript.getJSONArray("segments").length()
                appendLine("ASR $runaway: $before -> $after sentences (discarded=$discarded)")
            }
        }
        File(manifest.getString("result")).writeText(result)
    }
}
