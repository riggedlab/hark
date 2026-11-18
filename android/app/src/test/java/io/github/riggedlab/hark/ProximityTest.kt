package io.github.riggedlab.hark

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import java.io.File
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
    fun farSpeechIsKeptBeforeWearerEnrollmentAndLegacyFarMarksAreRetried() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val far = Proximity.SegmentAcoustic(-39f, 1.3f, -47f, 8f, -3f)
        assertEquals(Proximity.FAR, Proximity.classifySegment(far, nearMargin = -0.18f))
        // A named wearer in the old roster is not a new voiceprint enrollment.
        Speakers.selfIdentity(ctx)
        val pcm = FloatArray(16_000) { (0.006 * sin(2 * PI * 220 * it / 16_000)).toFloat() }
        val speech = IntArray(27) { it * 512 }
        assertFalse(Voiceprint.hasSelfEnrollment(ctx))
        assertEquals(Proximity.ChunkGate.KEEP, Proximity.screenChunkPcm(ctx, pcm, speech))
        assertEquals(Proximity.ChunkGate.NO_VOICE, Proximity.screenChunkPcm(ctx, pcm, intArrayOf()))

        val date = java.time.LocalDate.of(2026, 11, 18)
        val name = "20261110-120000.opus"
        val chunks = RecorderService.chunkDir(ctx).apply { mkdirs() }
        val audio = File(chunks, name).apply { writeBytes(ByteArray(64)) }
        File(ctx.filesDir, "farvoice/$name").apply { parentFile?.mkdirs(); createNewFile() }
        File(ctx.filesDir, "vad_gate/$name.txt").apply { parentFile?.mkdirs(); writeText("far") }
        assertFalse(Uploader(ctx).isFarSkipped(audio))
        assertTrue(Uploader(ctx).hasPendingWork())
        // The dummy file decodes to silence. The stale far cache must not override that result.
        assertEquals(Proximity.ChunkGate.NO_VOICE, Vad.screenChunk(ctx, audio))
        Recordings.cleanup(chunks, today = date)
        assertTrue(audio.exists())

        File(ctx.filesDir, "${Uploader.FAR_MARK_DIR}/$name").createNewFile()
        assertTrue(Uploader(ctx).isFarSkipped(audio))
        Recordings.cleanup(chunks, today = date)
        assertFalse(audio.exists())
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
    fun mediaMajorityOnlyPromotesSimilarTurnsInTheSameContinuousRun() {
        val turns = listOf(
            Proximity.Turn(0.0, 2.0, -49f, Proximity.MEDIA),
            Proximity.Turn(2.1, 3.0, -50f, Proximity.NEAR),
            Proximity.Turn(3.1, 4.0, -49f, Proximity.MEDIA),
            Proximity.Turn(4.1, 6.0, -40f, Proximity.NEAR),
            Proximity.Turn(6.1, 7.0, -48f, Proximity.MEDIA),
            Proximity.Turn(10.0, 11.0, -49f, Proximity.FAR),
        )
        assertEquals(
            listOf(Proximity.MEDIA, Proximity.MEDIA, Proximity.MEDIA, Proximity.NEAR, Proximity.MEDIA, Proximity.FAR),
            Proximity.refineMediaTurns(turns),
        )
    }

    @Test
    fun protectedTurnBreaksMediaContextAndAMinorityDoesNotPromote() {
        val turns = listOf(
            Proximity.Turn(0.0, 1.0, -49f, Proximity.MEDIA),
            Proximity.Turn(1.1, 2.0, -50f, Proximity.NEAR),
            Proximity.Turn(2.1, 3.0, -49f, Proximity.NEAR, protectedSpeaker = true),
            Proximity.Turn(3.1, 4.0, -48f, Proximity.MEDIA),
            Proximity.Turn(4.1, 5.0, -49f, Proximity.FAR),
        )
        assertEquals(turns.map { it.label }, Proximity.refineMediaTurns(turns))
    }

    @Test
    fun registeredSpeakersMatchAndStrangersRemainLocal() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val known = Voiceprint.l2Normalize(FloatArray(192) { if (it == 0) 1f else 0.01f })
        val stranger = Voiceprint.l2Normalize(FloatArray(192) { if (it == 5) 1f else -0.01f })
        Voiceprint.addEnrollment(ctx, "spk_001", "20261113-140000.opus", 0.0, 2.0, known, "user")
        val assigned = Voiceprint.assignSpeakers(
            listOf(Voiceprint.Utterance(0, 15.0, known), Voiceprint.Utterance(1, 18.0, stranger)),
            Voiceprint.enrollmentCentroids(ctx),
        )
        assertEquals("spk_001", assigned[0])
        assertEquals("other_1", assigned[1])
        assertFalse(File(ctx.filesDir, "speakers_local.json").exists())
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
