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
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class ProximityTest {

    @Test
    fun nearSoftVoiceBeatsFarLoudVoiceWhenDbIsInverted() {
        // 近处低声耳语 (-44.5 dB，比远处大声还轻 9.5 dB)，但底噪未抬高 (-60.5 dB -> SNR 16dB)，且 CED 检出近讲/耳语线索
        val cedNearSoft = FloatArray(527).apply {
            this[5] = 0.06f   // Narration
            this[15] = 0.16f  // Whispering
            this[500] = 0.02f // Clean silence gap
            this[70] = 0.01f  // Babble
        }
        val nearSoftAc = Proximity.SegmentAcoustic(
            activeDb = -44.5f,
            activeSec = 1.6f,
            localFloorDb = -60.5f,
            snrDb = 16.0f,
            hfLfDb = -18.5f,
        )

        // 远处高声说话 (-35.0 dB，表面分贝响了 9.5 dB)，但邻近停顿帧底噪被 AGC 抬到 -43.6 dB (SNR 仅 8.6dB)，且含公共空间混响特征
        val cedFarLoud = FloatArray(527).apply {
            this[5] = 0.002f
            this[15] = 0.001f
            this[70] = 0.08f  // Babble
            this[508] = 0.22f // Inside, public space
            this[511] = 0.12f // Reverberation
        }
        val farLoudAc = Proximity.SegmentAcoustic(
            activeDb = -35.0f,
            activeSec = 2.4f,
            localFloorDb = -43.6f,
            snrDb = 8.6f,
            hfLfDb = -2.0f,
        )

        val (medHf, p90Db) = Proximity.chunkReferenceStats(listOf(nearSoftAc, farLoudAc))
        val resNearSoft = Proximity.classifySegment(nearSoftAc, medHf, p90Db, cedNearSoft)
        val resFarLoud = Proximity.classifySegment(farLoudAc, medHf, p90Db, cedFarLoud)

        assertEquals(Proximity.NEAR, resNearSoft)
        assertEquals(Proximity.FAR, resFarLoud)
    }

    @Test
    fun mediaPlaybackDetectedWhileProtectedSpeakerExempted() {
        val cedVideo = FloatArray(527).apply {
            this[524] = 0.31f // Television
            this[137] = 0.18f // Music
        }
        val tinnySpeakerAc = Proximity.SegmentAcoustic(
            activeDb = -36.2f,
            activeSec = 3.1f,
            localFloorDb = -56.0f,
            snrDb = 19.8f,
            hfLfDb = 6.4f, // 手机/电脑小喇叭缺失 300Hz 以下低频
        )

        assertEquals(
            Proximity.MEDIA,
            Proximity.classifySegment(tinnySpeakerAc, -8.0f, -34.0f, cedVideo, isProtectedSpeaker = false),
        )
        assertEquals(
            Proximity.NEAR,
            Proximity.classifySegment(tinnySpeakerAc, -8.0f, -34.0f, cedVideo, isProtectedSpeaker = true),
        )
    }

    @Test
    fun localSnrCancelsFirmwareAgcBoostAndIgnoresDigitalSilenceFill() {
        val sr = 16000
        val rng = Random(42)
        // 构造 6 秒音频：[0..1s] 硬件静音补帧(0f) + [1..2.5s] 近场轻声(-36dB)伴真实底噪(-60dB) + [2.5..5.5s] 3s HANG 停顿底噪(-60dB)
        val nearPcm = FloatArray(sr * 6)
        for (i in sr until (sr * 5.5).toInt()) {
            val ambient = (rng.nextDouble(-1.0, 1.0) * 0.0014).toFloat() // ~ -61 dBFS
            val speech = if (i in (sr * 1.2).toInt() until (sr * 2.4).toInt()) {
                (0.022 * sin(2.0 * PI * 220.0 * i / sr)).toFloat() // ~ -36 dBFS
            } else 0f
            nearPcm[i] = speech + ambient
        }

        // 构造远场被旧版 AGC 放大 +16dB 的 6 秒音频：语音表面更响(-33dB)，但 HANG 底噪同步被抬到 -45dB
        val farBoostedPcm = FloatArray(sr * 6)
        for (i in sr until (sr * 5.5).toInt()) {
            val boostedAmbient = (rng.nextDouble(-1.0, 1.0) * 0.0095).toFloat() // ~ -45 dBFS
            val boostedSpeech = if (i in (sr * 1.2).toInt() until (sr * 2.4).toInt()) {
                (0.031 * sin(2.0 * PI * 220.0 * i / sr)).toFloat() // ~ -33 dBFS（表面分贝反而比近场高 3dB！）
            } else 0f
            farBoostedPcm[i] = boostedSpeech + boostedAmbient
        }

        val acNear = Proximity.buildProfile(nearPcm).measureSegment(1.2, 2.4)
        val acFar = Proximity.buildProfile(farBoostedPcm).measureSegment(1.2, 2.4)
        assertNotNull(acNear)
        assertNotNull(acFar)
        assertTrue("Expected far activeDb > near activeDb, got ${acFar!!.activeDb} vs ${acNear!!.activeDb}", acFar.activeDb > acNear.activeDb)
        assertTrue("Expected near snrDb >= 20dB despite digital zero prefix, got ${acNear.snrDb}", acNear.snrDb >= 20.0f)
        assertTrue("Expected far snrDb <= 13dB after local floor subtraction, got ${acFar.snrDb}", acFar.snrDb <= 13.0f)
    }

    @Test
    fun shortReplyInheritsAdjacentSameSpeakerProximity() {
        val starts = doubleArrayOf(1.0, 4.2)
        val ends = doubleArrayOf(3.5, 4.65) // 第二句仅 0.45s ("好的")
        val spks = arrayOf<String?>("spk_001", "spk_001")
        val acs = arrayOf<Proximity.SegmentAcoustic?>(
            Proximity.SegmentAcoustic(-41.0f, 2.1f, -60.0f, 19.0f, -14.0f),
            Proximity.SegmentAcoustic(-42.5f, 0.4f, -52.0f, 9.5f, -13.0f),
        )
        val proxs = arrayOf<String?>(Proximity.NEAR, Proximity.FAR)

        Proximity.smoothShortSegments(starts, ends, spks, acs, proxs)
        assertEquals(Proximity.NEAR, proxs[1])
    }

    @Test
    fun farAndMediaClustersDoNotCreateGlobalPspkWhileMatchingExistingSpeaker() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val knownVec = Voiceprint.l2Normalize(FloatArray(192) { if (it == 0) 1f else 0.01f })
        val farStrangerVec = Voiceprint.l2Normalize(FloatArray(192) { if (it == 5) 1f else -0.01f })
        Voiceprint.savePrototype(ctx, "spk_001", knownVec, 30.0, "2026-11-12 09:00", "我是佩戴者本人")

        val clusters = listOf(
            listOf("u_0") to Voiceprint.LocalTurnGroup("u_0", 15.0, knownVec, "桌面上本人说话"),
            listOf("u_1") to Voiceprint.LocalTurnGroup("u_1", 18.0, farStrangerVec, "隔壁工位聊了十八秒八卦"),
        )
        // 即使两簇都被标为非近场（eligiblePersistKeys 为空），已知 spk_001 仍正常命中，而远场陌生人绝不新建 pspk_
        val mapping = Voiceprint.matchAndPersistClusters(
            ctx = ctx,
            clusters = clusters,
            firstSeen = "2026-11-13 14:00",
            allowPersistPrototypes = true,
            eligiblePersistKeys = emptySet(),
        )
        assertEquals("spk_001", mapping["u_0"])
        assertEquals("other_1", mapping["u_1"])
    }

    @Test
    fun enrichWithPcmWritesProximityAndSnrDbRoundTrip() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "20261113-150000.opus"
        val sr = 16000
        val rng = Random(7)
        val pcm = FloatArray(sr * 5) { i ->
            val amb = (rng.nextDouble(-1.0, 1.0) * 0.001).toFloat()
            val sig = if (i in (sr * 0.5).toInt() until (sr * 2.5).toInt()) {
                (0.12 * sin(2.0 * PI * 240.0 * i / sr)).toFloat()
            } else 0f
            sig + amb
        }
        val root = JSONObject()
            .put("name", name)
            .put("duration_s", 5.0)
            .put("text", "近处清晰测试。")
            .put(
                "segments",
                JSONArray().put(
                    JSONObject().put("start", 0.5).put("end", 2.5).put("text", "近处清晰测试。").put("speaker", "spk_001"),
                ),
            )
        val enriched = SceneAndMood.enrichWithPcm(ctx, pcm, root)
        Transcripts.save(ctx, enriched)

        val parsed = Transcripts.get(ctx, name)
        assertNotNull(parsed)
        val seg = parsed!!.segments.single()
        assertEquals(Proximity.NEAR, seg.proximity)
        assertNotNull(seg.snrDb)
        assertTrue("Expected high SNR >= 20dB, got ${seg.snrDb}", seg.snrDb!! >= 20f)
    }
}
