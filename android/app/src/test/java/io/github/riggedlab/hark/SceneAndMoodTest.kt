package io.github.riggedlab.hark

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
class SceneAndMoodTest {

    @Test
    fun cedMelAndOnnxMatchSherpaReferenceAndGuaranteeNonEmptySceneTags() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sr = 16000
        val wav = FloatArray(sr * 3) { i ->
            val t = i.toDouble() / sr
            (0.30 * sin(2.0 * PI * 440.0 * t) + 0.15 * sin(2.0 * PI * 880.0 * t)).toFloat()
        }
        val probs = SceneAndMood.inferCedProbs(ctx, wav)
        assertNotNull(probs)
        assertEquals(527, probs!!.size)
        // AudioSet index 501 = Sine wave，验证 Kotlin 64 维梅尔谱前端与 sherpa-onnx 输出完全对齐 (>0.88)
        assertTrue("Expected Sine wave prob > 0.88, got ${probs[501]}", probs[501] > 0.88f)

        // 当不存在白名单外源噪声时，必须兜底输出「室内安静」而非空列表
        val tags = SceneAndMood.detectSceneTags(ctx, wav)
        assertTrue(tags.isNotEmpty())
        assertEquals("室内安静", tags.first().label)
        assertTrue(tags.first().score in 0.65f..0.98f)
    }

    @Test
    fun bgeSemanticMoodDistinguishesPositiveNeutralNegativeWithConsistentScores() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        val pos = SceneAndMood.fuseUtteranceMood(
            SceneAndMood.predictSemanticMoodProbs(ctx, "谢谢你帮我盯这个事，最后效果超出预期，省了我好大劲！"),
        )
        assertEquals("positive", pos.mood)
        assertTrue("Expected positive score >= 0.18, got ${pos.score}", pos.score >= SceneAndMood.NEUTRAL_BAND)

        val neu = SceneAndMood.fuseUtteranceMood(
            SceneAndMood.predictSemanticMoodProbs(ctx, "我们下午两点半在会议室过一下下周的排期和接口。"),
        )
        assertEquals("neutral", neu.mood)
        assertTrue("Expected neutral score in (-0.18, 0.18), got ${neu.score}", neu.score > -SceneAndMood.NEUTRAL_BAND && neu.score < SceneAndMood.NEUTRAL_BAND)

        val neg = SceneAndMood.fuseUtteranceMood(
            SceneAndMood.predictSemanticMoodProbs(ctx, "唉，白折腾了一整晚还是报错，根本没法用，太让人窝火了。"),
        )
        assertEquals("negative", neg.mood)
        assertTrue("Expected negative score <= -0.18, got ${neg.score}", neg.score <= -SceneAndMood.NEUTRAL_BAND)
    }

    @Test
    fun acousticParalinguisticLaughterAndSighShiftMoodAndEmitVocalTag() {
        val baseNeutral = floatArrayOf(0.20f, 0.55f, 0.25f)

        val withLaugh = SceneAndMood.fuseUtteranceMood(baseNeutral, laughProb = 0.72f)
        assertEquals("positive", withLaugh.mood)
        assertEquals("笑声", withLaugh.vocalTag)
        assertTrue(withLaugh.score >= SceneAndMood.NEUTRAL_BAND)

        val withSigh = SceneAndMood.fuseUtteranceMood(floatArrayOf(0.25f, 0.55f, 0.20f), sighProb = 0.75f)
        assertEquals("negative", withSigh.mood)
        assertEquals("叹气", withSigh.vocalTag)
        assertTrue(withSigh.score <= -SceneAndMood.NEUTRAL_BAND)
    }

    @Test
    fun enrichWithPcmPersistsSceneTagsAndPerSegmentMoodRoundTrip() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "20261113-130000.opus"
        val pcm = FloatArray(16000 * 4) { i -> (0.08 * sin(2.0 * PI * 300.0 * i / 16000.0)).toFloat() }
        val root = JSONObject()
            .put("name", name)
            .put("duration_s", 4.0)
            .put("text", "今天下午三点开会。唉这个崩溃问题太烦人了。")
            .put(
                "segments",
                JSONArray()
                    .put(JSONObject().put("start", 0.0).put("end", 1.8).put("text", "今天下午三点开会。").put("speaker", "spk_01").put("db", -20.0).put("act_s", 1.5))
                    .put(JSONObject().put("start", 2.0).put("end", 4.0).put("text", "唉这个崩溃问题卡了一天，太烦人了。").put("speaker", "spk_02").put("db", -28.5).put("act_s", 1.8)),
            )

        val enriched = SceneAndMood.enrichWithPcm(ctx, pcm, root)
        Transcripts.save(ctx, enriched)

        val parsed = Transcripts.get(ctx, name)
        assertNotNull(parsed)
        assertTrue(parsed!!.sceneTags.isNotEmpty())
        assertEquals("neutral", parsed.segments[0].mood)
        assertEquals("negative", parsed.segments[1].mood)
        assertNotNull(parsed.segments[0].moodScore)
        assertNotNull(parsed.segments[1].moodScore)
    }

    @Test
    fun sceneSidecarWorksIndependentlyWithoutAnyAsrTranscript() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val noAsrName = "20261113-130500.opus"
        SceneAndMood.saveSceneSidecar(
            ctx,
            noAsrName,
            listOf(SceneTag("敲键盘", 0.84f), SceneTag("室内安静", 0.76f)),
        )
        val tags = SceneAndMood.getSceneTags(ctx, noAsrName)
        assertEquals(2, tags.size)
        assertEquals("敲键盘", tags[0].label)
    }

    @Test
    fun computeAndCommitRunsOutsideLockAndRetriesOnConcurrentSaveWithoutStaleCache() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "20261113-131000.opus"
        Transcripts.save(
            ctx,
            JSONObject()
                .put("name", name)
                .put("duration_s", 5.0)
                .put("text", "第一版")
                .put("segments", JSONArray().put(JSONObject().put("start", 0.0).put("end", 2.0).put("text", "第一版"))),
        )
        assertEquals("第一版", Transcripts.get(ctx, name)?.text)

        var crossThreadSaveTriggered = false
        val ok = Transcripts.computeAndCommit(ctx, name) { working ->
            if (!crossThreadSaveTriggered) {
                crossThreadSaveTriggered = true
                val doneLatch = java.util.concurrent.CountDownLatch(1)
                Thread {
                    // 独立线程调用 save：若 computeAndCommit 持锁阻塞，此处将死锁超时失败！
                    Transcripts.save(
                        ctx,
                        JSONObject()
                            .put("name", name)
                            .put("duration_s", 6.0)
                            .put("text", "并发新版")
                            .put("segments", JSONArray().put(JSONObject().put("start", 0.0).put("end", 3.0).put("text", "并发新版，省事多了！"))),
                    )
                    doneLatch.countDown()
                }.start()
                assertTrue("跨线程 save 在重型推理期间必须零阻塞完成", doneLatch.await(2, java.util.concurrent.TimeUnit.SECONDS))
            }
            SceneAndMood.enrichTextOnly(ctx, working)
            true
        }
        assertTrue(ok)
        val latest = Transcripts.get(ctx, name)
        assertNotNull(latest)
        assertEquals("并发新版", latest!!.text)
        assertNotNull("重试后应在新版文本上补齐心情", latest.segments.first().mood)
    }
}
