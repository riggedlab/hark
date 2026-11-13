package io.github.riggedlab.hark

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
class VoiceprintTest {

    @Test
    fun kaldiFbankMatchesSherpaOnnxGoldenReference() {
        val n = 16000
        val wave = FloatArray(n) { i ->
            (0.35 * sin(2.0 * PI * 210.0 * i / 16000.0) +
                0.20 * cos(2.0 * PI * 530.0 * i / 16000.0) +
                0.10 * sin(2.0 * PI * 1240.0 * i / 16000.0)).toFloat()
        }
        val (numFrames, feats) = KaldiFbank.extractGlobalMeanNormalized(wave)
        assertEquals(100, numFrames)
        assertEquals(100 * 80, feats.size)

        // kaldi-native-fbank / sherpa-onnx 黄金基准值（high_freq=-400, snip_edges=false, povey, global-mean）
        val expectedRow10 = floatArrayOf(-1.87964f, -0.27234f, 0.58110f, 0.01270f, -0.01882f, -0.01236f, 0.00540f, 0.01398f)
        for (b in expectedRow10.indices) {
            assertEquals("row10 bin $b", expectedRow10[b], feats[10 * 80 + b], 2e-3f)
        }
        val expectedRow50 = floatArrayOf(-0.00300f, 0.00120f, -0.00038f, -0.00030f, -0.00242f, -0.01145f, -0.03121f, 0.03898f)
        for (k in expectedRow50.indices) {
            assertEquals("row50 bin ${15 + k}", expectedRow50[k], feats[50 * 80 + 15 + k], 2e-3f)
        }
    }

    @Test
    fun completeLinkagePreventsTransitiveChaining() {
        // A 与 B 相似 (0.72)，B 与 C 相似 (0.72)，但 A 与 C 不相似 (0.04 < 0.62) -> 绝不能链式合并成同一人
        val vecA = Voiceprint.l2Normalize(FloatArray(192) { if (it == 0) 1f else 0f })
        val vecB = Voiceprint.l2Normalize(FloatArray(192) { if (it == 0 || it == 1) 0.7071f else 0f })
        val vecC = Voiceprint.l2Normalize(FloatArray(192) { if (it == 1) 1f else 0f })

        val groups = listOf(
            Voiceprint.LocalTurnGroup("s1", 6.0, vecA, "第一句"),
            Voiceprint.LocalTurnGroup("s2", 5.0, vecB, "第二句"),
            Voiceprint.LocalTurnGroup("s3", 4.0, vecC, "第三句"),
        )
        val clusters = Voiceprint.clusterInFile(groups, mergeThreshold = 0.62f)
        assertEquals(2, clusters.size)
        assertEquals(listOf("s1", "s2"), clusters[0].first)
        assertEquals(listOf("s3"), clusters[1].first)
    }

    @Test
    fun localPhoneSpeakersIntegrateWithMacRosterAndMerge() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Speakers.receive(
            ctx,
            "speakers_mac",
            1,
            JSONObject().put("items", JSONObject().put("spk_001", JSONObject().put("sample", "旧样本"))),
        )
        Speakers.edit(ctx, "spk_001", name = "我")

        val localJson = JSONObject()
            .put("version", Voiceprint.SCHEMA_VERSION)
            .put(
                "items",
                JSONObject().put(
                    "pspk_001",
                    JSONObject()
                        .put("centroid", JSONArray(FloatArray(192) { 0.05f }.map { it.toDouble() }))
                        .put("weight", 12.0)
                        .put("model", Voiceprint.MODEL_ID)
                        .put("first_seen", "2026-11-13 10:00")
                        .put("sample", "手机端新识出的人"),
                ),
            )
        Sync.writeFile(File(ctx.filesDir, "speakers_local.json"), localJson.toString())

        val all = Speakers.all(ctx)
        assertTrue("spk_001" in all)
        assertTrue("pspk_001" in all)
        assertEquals("新说话人 #1", all["pspk_001"]?.defaultName)

        // 支持把手机端识出的 pspk_001 改名，或合并进电脑同步来的 spk_001（我）
        assertTrue(Speakers.edit(ctx, "pspk_001", mergeInto = "spk_001"))
        val resolved = Speakers.resolve(ctx, "pspk_001")
        assertNotNull(resolved)
        assertEquals("spk_001", resolved?.id)
        assertEquals("我", resolved?.name)
    }

    @Test
    fun distinctInFileClustersRemainSeparatedAndPreserveRawMergedId() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        File(ctx.filesDir, "speakers_local.json").delete()
        File(ctx.filesDir, "speakers_phone.json").delete()
        File(ctx.filesDir, "speakers_mac.json").delete()

        // 1. 两个在文件内未被合并的独立簇（均 >= 10s 建档门槛），绝不能在同轮新建原型时串成同一个 pspk_001
        val v1 = Voiceprint.l2Normalize(FloatArray(192) { if (it == 0) 1f else 0f })
        val v2 = Voiceprint.l2Normalize(FloatArray(192) { if (it == 0) 0.60f else if (it == 1) 0.80f else 0f })
        val clusters = listOf(
            listOf("s1") to Voiceprint.LocalTurnGroup("s1", 12.5, v1, "说话人一"),
            listOf("s2") to Voiceprint.LocalTurnGroup("s2", 11.0, v2, "说话人二"),
        )
        val firstAssign = Voiceprint.matchAndPersistClusters(ctx, clusters, "2026-11-13 10:00", allowPersistPrototypes = true)
        assertEquals("pspk_001", firstAssign["s1"])
        assertEquals("pspk_002", firstAssign["s2"])

        // 1b. 同性别陌生人（余弦 0.61 < 0.68 精确门槛，且不足 10s 建档门槛）绝不能误匹配成「我」(pspk_001)
        val vBorderlineStranger = Voiceprint.l2Normalize(
            FloatArray(192) { if (it == 0) 0.61f else if (it == 2) 0.7924f else 0f },
        )
        val strangerAssign = Voiceprint.matchAndPersistClusters(
            ctx,
            listOf(listOf("stranger") to Voiceprint.LocalTurnGroup("stranger", 5.0, vBorderlineStranger, "旁边路人说话")),
            "2026-11-13 10:02",
            allowPersistPrototypes = true,
        )
        assertEquals("other_1", strangerAssign["stranger"])

        // 2. 将 pspk_002 合并入 pspk_001 后，新录音匹配到 pspk_002 原型时仍记录原始 pspk_002，拆开后可无损恢复
        assertTrue(Speakers.edit(ctx, "pspk_001", name = "我"))
        assertTrue(Speakers.edit(ctx, "pspk_002", mergeInto = "pspk_001"))
        val nextAssign = Voiceprint.matchAndPersistClusters(
            ctx,
            listOf(listOf("turnA") to Voiceprint.LocalTurnGroup("turnA", 4.5, v2, "合并期间的新话")),
            "2026-11-13 10:05",
            allowPersistPrototypes = true,
        )
        assertEquals("pspk_002", nextAssign["turnA"])
        assertEquals("我", Speakers.resolve(ctx, nextAssign["turnA"])?.name)

        // 拆出 pspk_002 后，该条录音立即恢复为独立的 pspk_002，未被永久改写成 pspk_001
        assertTrue(Speakers.edit(ctx, "pspk_002", unmerge = true))
        assertEquals("pspk_002", Speakers.resolve(ctx, nextAssign["turnA"])?.id)

        // 3. 无分离标签（allowPersistPrototypes = false）时，新声音只给局部 other_1，不污染全局声纹库
        val vUnknown = Voiceprint.l2Normalize(FloatArray(192) { if (it == 5) 1f else 0f })
        val roAssign = Voiceprint.matchAndPersistClusters(
            ctx,
            listOf(listOf("__seg_0") to Voiceprint.LocalTurnGroup("__seg_0", 5.0, vUnknown, "未知单句")),
            "2026-11-13 10:10",
            allowPersistPrototypes = false,
        )
        assertEquals("other_1", roAssign["__seg_0"])
    }

    @Test
    fun syntheticWholeFileSegmentFromPlainTextAsrSkipsSpeakerAssignment() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val probeOpus = File("../../internal/asr/probe.opus")
        val targetOpus = File(ctx.cacheDir, "20261113-103000.opus")
        if (probeOpus.exists()) probeOpus.copyTo(targetOpus, overwrite = true) else targetOpus.writeBytes(ByteArray(256))

        // 模拟 OpenAI json 纯文本回退（服务端未返回 segments，由 PhoneAsr 包装成整段单句并标记 synthetic_segments）
        val built = PhoneAsr.buildTranscriptJson(
            name = targetOpus.name,
            durationS = 42.0,
            fullText = "甲说了前半段，乙接着说了后半段。",
            engine = "openai",
            model = "SenseVoiceSmall",
            rawSegs = emptyList(),
        )
        assertTrue(built.optBoolean("synthetic_segments", false))

        val enriched = Voiceprint.enrichTranscript(ctx, targetOpus, built)
        assertTrue(enriched.optBoolean("vp_done", false))
        val seg0 = enriched.getJSONArray("segments").getJSONObject(0)
        assertFalse("无分句纯文本不应被强行赋予说话人标签", seg0.has("speaker"))
    }

    @Test
    fun activeSpeechDbIgnoresInternalPausesAndRoundTripsInTranscripts() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // 1.0 秒 -20 dBFS 人声 + 2.0 秒 -70 dBFS 停顿静音 -> 有效发声响度仍保持 -20.0 dB
        val energies = DoubleArray(150) { idx -> if (idx < 50) 0.01 else 1e-7 }
        val db = Voiceprint.computeActiveSpeechDb(energies, 0.02, 0.0, 3.0)
        assertNotNull(db)
        assertEquals(-20.0f, db!!, 0.3f)

        val name = "20261113-120000.opus"
        Transcripts.save(
            ctx,
            JSONObject()
                .put("name", name)
                .put("duration_s", 12.0)
                .put("text", "近处说话。远处回应。")
                .put(
                    "segments",
                    JSONArray()
                        .put(JSONObject().put("start", 0.0).put("end", 4.0).put("text", "近处说话。").put("speaker", "spk_001").put("db", -19.4))
                        .put(JSONObject().put("start", 5.0).put("end", 9.0).put("text", "远处回应。").put("speaker", "other_1").put("db", -32.6)),
                ),
        )
        val tr = Transcripts.get(ctx, name)
        assertNotNull(tr)
        assertEquals(-19.4f, tr!!.segments[0].db!!, 0.05f)
        assertEquals(-32.6f, tr.segments[1].db!!, 0.05f)
    }

    @Test
    fun schemaV1MigrationNeverReusesOldPhoneSpeakerIdsAndPreservesNamedMetadata() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        File(ctx.filesDir, "speakers_local.json").delete()
        File(ctx.filesDir, "speakers_phone.json").delete()
        File(ctx.filesDir, "speakers_mac.json").delete()

        // 模拟旧版 v1 曾创建 pspk_001..pspk_005，且用户在 v1 期间曾把 pspk_002 改名为「李总」存入 speakers_phone.json
        val v1Local = JSONObject().put(
            "items",
            JSONObject()
                .put("pspk_001", JSONObject().put("centroid", JSONArray(List(192) { 0.3 })).put("first_seen", "2026-11-12 08:30").put("sample", "未改名合并目标样本"))
                .put("pspk_002", JSONObject().put("centroid", JSONArray(List(192) { 0.1 })).put("first_seen", "2026-11-12 09:00").put("sample", "旧样本句"))
                .put("pspk_004", JSONObject().put("centroid", JSONArray(List(192) { 0.4 })))
                .put("pspk_005", JSONObject().put("centroid", JSONArray(List(192) { 0.2 }))),
        )
        val v1Phone = JSONObject().put(
            "items",
            JSONObject()
                .put("pspk_002", JSONObject().put("name", "李总"))
                .put("pspk_004", JSONObject().put("merged_into", "pspk_001")),
        )
        Sync.writeFile(File(ctx.filesDir, "speakers_local.json"), v1Local.toString())
        Sync.writeFile(File(ctx.filesDir, "speakers_phone.json"), v1Phone.toString())

        // 首次调用 Speakers.all 即自动完成 v1 -> v2 平滑迁移，且未改名的合并目标 pspk_001 也被完整保留
        val roster = Speakers.all(ctx)
        assertEquals("李总", roster["pspk_002"]?.name)
        assertEquals("旧样本句", roster["pspk_002"]?.sample)
        assertEquals("pspk_001", Speakers.resolve(ctx, "pspk_004")?.id)
        assertEquals("未改名合并目标样本", roster["pspk_001"]?.sample)

        // 2. 新建说话人必须从 pspk_006 起分配，绝不复用历史 pspk_001..pspk_005 造成串人
        val vNew = Voiceprint.l2Normalize(FloatArray(192) { if (it == 7) 1f else 0f })
        val assigned = Voiceprint.matchAndPersistClusters(
            ctx,
            listOf(listOf("u_0") to Voiceprint.LocalTurnGroup("u_0", 12.0, vNew, "全新说话人满十秒")),
            "2026-11-13 12:00",
            allowPersistPrototypes = true,
        )
        assertEquals("pspk_006", assigned["u_0"])
    }
}
