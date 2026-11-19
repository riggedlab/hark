package io.github.riggedlab.hark

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
class VoiceprintTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun vector(degrees: Double): FloatArray {
        val radians = Math.toRadians(degrees)
        return FloatArray(192) { when (it) { 0 -> cos(radians).toFloat(); 1 -> sin(radians).toFloat(); else -> 0f } }
    }
    private fun transcript(name: String, speaker: String = "spk_001", engine: String = "dashscope"): JSONObject =
        JSONObject().put("name", name).put("engine", engine).put("vp_ver", 2).put("segments", JSONArray()
            .put(JSONObject().put("start", 0.0).put("end", 2.0).put("text", "第一句").put("db", -35).put("speaker", speaker))
            .put(JSONObject().put("start", 2.0).put("end", 2.4).put("text", "好").put("db", -35).put("speaker", speaker)))

    @Test fun campplusMatchesSherpaOnnxForProbeAndShortSentence() {
        val loader = javaClass.classLoader!!
        val raw = loader.getResourceAsStream("voiceprint/probe.f32")!!.use { it.readBytes() }
        val pcm = FloatArray(raw.size / 4)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(pcm)
        val references = JSONObject(loader.getResourceAsStream("voiceprint/reference.json")!!.bufferedReader().use { it.readText() }).getJSONArray("references")
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            OrtEnvironment.getEnvironment().createSession(ctx.assets.open(Voiceprint.MODEL_ASSET).use { it.readBytes() }, options).use { session ->
                for (i in 0 until references.length()) {
                    val ref = references.getJSONObject(i)
                    val clip = pcm.copyOfRange(ref.getInt("start_sample"), ref.getInt("end_sample"))
                    val expected = FloatArray(192) { ref.getJSONArray("embedding").getDouble(it).toFloat() }
                    val actual = Voiceprint.extractEmbedding(session, clip)!!
                    val similarity = Voiceprint.cosine(actual, expected)
                    println("CAM++ reference[$i] cosine=$similarity")
                    assertTrue("reference $i cosine=$similarity", similarity >= 0.99f)
                }
            }
        }
        assertNull(Voiceprint.extractEmbedding(ctx, FloatArray(3999)))
    }

    @Test fun averageLinkageAndDurationWeightedGroupsMatchReference() {
        // A-C 距离 > 0.6，但合并 A-B 后与 C 的平均距离 < 0.6，必须合成一组（不能用完全连接）。
        val utterances = listOf(
            Voiceprint.Utterance(0, 1.0, vector(0.0)),
            Voiceprint.Utterance(1, 2.0, vector(40.0)),
            Voiceprint.Utterance(2, 3.0, vector(80.0)),
            Voiceprint.Utterance(3, 0.4, vector(170.0)),
        )
        val groups = Voiceprint.groupUtterances(utterances)
        assertEquals(1, groups.size)
        assertEquals(setOf(0, 1, 2, 3), groups[0].members.toSet())
        val expected = Voiceprint.l2Normalize(FloatArray(192) { utterances[0].embedding[it] + 2 * utterances[1].embedding[it] + 3 * utterances[2].embedding[it] })
        assertArrayEquals(expected, groups[0].embedding, 1e-6f)

        val separated = Voiceprint.groupUtterances(listOf(
            Voiceprint.Utterance(0, 0.8, vector(0.0)), Voiceprint.Utterance(1, 2.0, vector(100.0)),
            Voiceprint.Utterance(2, 0.79, vector(95.0)), Voiceprint.Utterance(3, 0.3, vector(5.0)),
        ))
        assertEquals(setOf(0, 3), separated[0].members.toSet())
        assertEquals(setOf(1, 2), separated[1].members.toSet())
        assertArrayEquals(vector(100.0), separated[1].embedding, 1e-6f)
        assertEquals(2, Voiceprint.groupUtterances(listOf(Voiceprint.Utterance(0, 0.4, vector(0.0)), Voiceprint.Utterance(1, 0.4, vector(0.0)))).size)
    }

    @Test fun scoresUseDurationLoudnessThresholdAndHighestIdentity() {
        val u = Voiceprint.Utterance(0, 0.4, vector(90.0), -35f)
        assertEquals(0.74f, Voiceprint.identityScore(u, vector(0.0), vector(0.0)), 1e-6f)
        assertEquals(0.2f, Voiceprint.identityScore(u.copy(duration = 8.0), vector(0.0), vector(0.0)), 1e-6f)
        assertEquals(0.59f, Voiceprint.identityScore(u.copy(db = -50f), vector(0.0), vector(0.0)), 1e-6f)
        val utterances = listOf(Voiceprint.Utterance(0, 2.0, vector(0.0), -35f), Voiceprint.Utterance(1, 2.0, vector(170.0), -35f))
        val centroids = mapOf("me" to vector(0.0), "friend" to vector(20.0))
        val assigned = Voiceprint.assignSpeakers(utterances, centroids)
        assertEquals("me", assigned[0])
        assertEquals("other_1", assigned[1])
        assertEquals("friend", Voiceprint.assignSpeakers(utterances, centroids, mapOf(0 to Voiceprint.NOT_SELF), setOf("me"))[0])
        assertEquals("friend", Voiceprint.assignSpeakers(utterances, centroids, mapOf(1 to "friend"))[1])
        assertEquals("other_1", Voiceprint.assignSpeakers(listOf(utterances[0].copy(db = -80f)), centroids)[0])
    }

    @Test fun groupThatResemblesSpeakerCarriesSentencesBelowSentenceThreshold() {
        // 两句长句同组；第二句单独打分不过 0.65，但组向量与本人质心足够近。
        val utterances = listOf(Voiceprint.Utterance(0, 2.0, vector(0.0), -35f), Voiceprint.Utterance(1, 2.0, vector(60.0), -50f))
        val centroids = mapOf("me" to vector(0.0))
        assertEquals(listOf("me", "me"), Voiceprint.assignSpeakers(utterances, centroids).let { listOf(it[0], it[1]) })
        assertEquals("other_1", Voiceprint.assignSpeakers(utterances, centroids, mapOf(1 to Voiceprint.NOT_SELF), setOf("me"))[1])
        assertEquals("other_1", Voiceprint.assignSpeakers(utterances, mapOf("me" to vector(90.0)))[1])
    }

    @Test fun cacheIgnoresAsrSpeakersAndWorksWithoutOriginalAudio() {
        Speakers.namedIdentity(ctx, "我", "spk_001")
        Voiceprint.addEnrollment(ctx, "spk_001", "seed.opus", 0.0, 2.0, vector(0.0), "seed")
        val name = "20261113-114000.opus"
        val cache = listOf(Voiceprint.CachedSegment(0.0, 2.0, vector(0.0)), Voiceprint.CachedSegment(2.0, 2.4, vector(170.0)))
        Voiceprint.writeCache(ctx, name, cache)
        for (engine in listOf("dashscope", "hark-http", "openai", "volcengine")) {
            val root = transcript(name, speaker = "other_5", engine = engine)
                .put("sm_audio_ver", SceneAndMood.AUDIO_SCHEMA_VERSION)
            val segments = root.getJSONArray("segments")
            for (i in 0 until segments.length()) segments.getJSONObject(i).put("proximity", Proximity.MEDIA)
            Voiceprint.enrichTranscript(ctx, File(ctx.filesDir, name), root)
            assertEquals(Voiceprint.ASSIGN_VERSION, root.getInt("vp_ver"))
            assertEquals("spk_001", segments.getJSONObject(0).getString("speaker"))
            assertEquals("other_1", segments.getJSONObject(1).getString("speaker"))
            assertEquals(Proximity.NEAR, segments.getJSONObject(0).getString("proximity"))
            assertEquals(Proximity.MEDIA, segments.getJSONObject(1).getString("proximity"))
            assertFalse(root.has("sm_audio_ver"))
        }
        assertArrayEquals(vector(170.0), Voiceprint.readCache(ctx, name)!![1].embedding, 1e-6f)
        assertFalse(File(ctx.filesDir, "speakers_local.json").readText().contains("pspk_"))
    }

    @Test fun unnamedLegacyIdsDoNotBecomeTheWearer() {
        File(ctx.filesDir, "speakers_mac.json").writeText(
            JSONObject().put("items", JSONObject().put("spk_001", JSONObject()).put("spk_01", JSONObject())).toString(),
        )
        assertTrue(Speakers.selfIds(Speakers.all(ctx)).isEmpty())
        assertEquals("self", Speakers.selfIdentity(ctx))
        val roster = Speakers.all(ctx)
        assertEquals(setOf("self"), Speakers.selfIds(roster))
        assertFalse(Proximity.isProtectedSpeakerId("spk_001", roster))
        assertFalse(Proximity.isProtectedSpeakerId("spk_01", roster))
        assertTrue(Proximity.isProtectedSpeakerId("self", roster))
    }

    @Test fun markingTheWearerAsNotSelfInvalidatesMediaContext() = runBlocking {
        val name = "20261113-115100.opus"
        val me = Speakers.selfIdentity(ctx)
        Voiceprint.addEnrollment(ctx, me, name, 0.0, 2.0, vector(0.0), "user")
        Voiceprint.writeCache(ctx, name, listOf(Voiceprint.CachedSegment(0.0, 2.0, vector(0.0))))
        val transcript = transcript(name, speaker = me)
            .put("vp_ver", Voiceprint.ASSIGN_VERSION)
            .put("sm_audio_ver", SceneAndMood.AUDIO_SCHEMA_VERSION)
            .put("sm_text_ver", 2)
        Transcripts.save(ctx, transcript)

        Voiceprint.labelSentence(ctx, name, 0.0, 2.0, Voiceprint.NOT_SELF)
        val root = JSONObject(File(Transcripts.dir(ctx), "$name.json").readText())
        val segment = root.getJSONArray("segments").getJSONObject(0)
        assertEquals(Voiceprint.NOT_SELF, segment.getString("vp_manual"))
        assertTrue(segment.getString("speaker").startsWith("other_"))
        assertFalse(root.has("sm_audio_ver"))
    }

    @Test fun explicitLabelsPersistAndEnrollmentEditsReuseCache() = runBlocking {
        val me = Speakers.selfIdentity(ctx)
        val friend = Speakers.namedIdentity(ctx, "李总")
        val name = "20261113-115000.opus"
        Transcripts.save(ctx, transcript(name))
        Voiceprint.writeCache(ctx, name, listOf(Voiceprint.CachedSegment(0.0, 2.0, vector(0.0)), Voiceprint.CachedSegment(2.0, 2.4, vector(20.0))))
        val otherDate = "20261112-115000.opus"
        Transcripts.save(ctx, transcript(otherDate).put("vp_ver", Voiceprint.ASSIGN_VERSION))
        Voiceprint.writeCache(ctx, otherDate, Voiceprint.readCache(ctx, name)!!)
        val cache = File(ctx.filesDir, "vp_cache/$name.bin")
        val before = cache.readBytes()
        Voiceprint.labelSentence(ctx, name, 0.0, 2.0, Voiceprint.SELF)
        assertEquals(me, Transcripts.get(ctx, name)!!.segments[0].speaker)
        assertEquals(1, JSONObject(File(ctx.filesDir, "voice_enroll.json").readText()).getJSONArray(me).length())
        withTimeout(5_000) { while (Transcripts.get(ctx, otherDate)!!.segments[0].speaker != me) delay(10) }
        Voiceprint.labelSentence(ctx, name, 0.0, 2.0, friend)
        val enroll = JSONObject(File(ctx.filesDir, "voice_enroll.json").readText())
        assertEquals(0, enroll.getJSONArray(me).length())
        assertEquals(1, enroll.getJSONArray(friend).length())
        assertEquals(friend, Transcripts.get(ctx, name)!!.segments[0].speaker)
        Voiceprint.labelSentence(ctx, name, 2.0, 2.4, Voiceprint.NOT_SELF)
        assertNotEquals(me, Transcripts.get(ctx, name)!!.segments[1].speaker)
        assertEquals(Voiceprint.NOT_SELF, JSONObject(File(Transcripts.dir(ctx), "$name.json").readText()).getJSONArray("segments").getJSONObject(1).getString("vp_manual"))
        assertArrayEquals(before, cache.readBytes())
    }

    @Test fun labelingCachedSentenceDoesNotRequireAudioOrOtherSentenceEmbeddings() = runBlocking {
        val name = "20261113-115500.opus"
        Transcripts.save(ctx, transcript(name))
        Voiceprint.writeCache(ctx, name, listOf(Voiceprint.CachedSegment(0.0, 2.0, vector(0.0))))
        Voiceprint.labelSentence(ctx, name, 0.0, 2.0, Voiceprint.SELF)
        assertEquals(Speakers.selfIdentity(ctx), Transcripts.get(ctx, name)!!.segments[0].speaker)
        assertEquals(1, Voiceprint.readCache(ctx, name)!!.size)
    }

    @Test fun scanSkipsCurrentTranscriptUntilForcedOrReplaced() {
        val name = "20261113-115601.opus"
        val root = JSONObject().put("name", name).put("vp_ver", Voiceprint.ASSIGN_VERSION)
        Transcripts.save(ctx, root)
        val file = File(Transcripts.dir(ctx), "$name.json")
        var parsed = 0
        fun scan(force: Boolean = false) = Voiceprint.rescoreTranscripts(ctx, force) { parsed++ }

        assertFalse(scan())
        assertFalse(scan())
        assertEquals(1, parsed)
        assertTrue(scan(force = true))
        assertFalse(scan())
        assertEquals(2, parsed)

        val modified = Files.getLastModifiedTime(file.toPath())
        val length = file.length()
        val older = JSONObject(file.readText()).put("vp_ver", Voiceprint.ASSIGN_VERSION - 1)
        Sync.writeFile(file, older.toString())
        Files.setLastModifiedTime(file.toPath(), modified)
        assertEquals(length, file.length())
        assertTrue(scan())
        assertEquals(Voiceprint.ASSIGN_VERSION, JSONObject(file.readText()).getInt("vp_ver"))
        assertFalse(scan())
        assertEquals(3, parsed)
    }

    @Test fun scanRetriesHistoricalTranscriptWhenEmbeddingsArrive() {
        val name = "20261113-115602.opus"
        Transcripts.save(ctx, transcript(name))
        var parsed = 0
        fun scan() = Voiceprint.rescoreTranscripts(ctx, force = false) { parsed++ }

        assertFalse(scan())
        assertFalse(scan())
        assertEquals(1, parsed)

        Voiceprint.writeCache(ctx, name, listOf(
            Voiceprint.CachedSegment(0.0, 2.0, vector(0.0)),
            Voiceprint.CachedSegment(2.0, 2.4, null),
        ))
        assertTrue(scan())
        assertFalse(scan())
        assertEquals(2, parsed)
    }

    @Test fun seedImportUsesNamesDeduplicatesAndRescoresVersionThreeTranscripts() {
        val name = "20261113-121000.opus"
        val friend = Speakers.namedIdentity(ctx, "朋友")
        val me = Speakers.selfIdentity(ctx)
        Voiceprint.writeCache(ctx, name, listOf(Voiceprint.CachedSegment(0.0, 2.0, vector(0.0)), Voiceprint.CachedSegment(2.0, 2.4, vector(170.0))))
        val tr = transcript(name).put("vp_ver", Voiceprint.ASSIGN_VERSION)
        Transcripts.save(ctx, tr)
        val seed = JSONObject().put("self", JSONArray().put(JSONObject().put("file", name).put("start", 0).put("end", 2)))
            .put("朋友", JSONArray().put(JSONObject().put("file", name).put("start", 2).put("end", 2.4)))
            .put("新朋友", JSONArray().put(JSONObject().put("file", name).put("start", 2).put("end", 2.4)))
        val file = File(ctx.filesDir, "voice_seed.json")
        repeat(2) { i ->
            file.writeText(seed.toString())
            assertEquals(i == 0, Voiceprint.bootstrapIfNeeded(ctx))
            assertFalse(file.exists())
        }
        val enroll = JSONObject(File(ctx.filesDir, "voice_enroll.json").readText())
        assertEquals(1, enroll.getJSONArray(me).length())
        assertEquals("seed", enroll.getJSONArray(friend).getJSONObject(0).getString("source"))
        assertTrue(Speakers.all(ctx).values.any { it.name == "新朋友" })
        assertEquals(me, Transcripts.get(ctx, name)!!.segments[0].speaker)
        // 按样本均值而非句长加权计算身份质心。
        Voiceprint.addEnrollment(ctx, me, name, 3.0, 30.0, vector(80.0), "user")
        assertArrayEquals(vector(40.0), Voiceprint.enrollmentCentroids(ctx).getValue(me), 1e-6f)
    }

    @Test fun partialSeedImportDoesNotRewriteHistoryAgain() {
        val name = "20261113-122000.opus"
        val expired = "20261110-122000.opus"
        Voiceprint.writeCache(ctx, name, listOf(
            Voiceprint.CachedSegment(0.0, 2.0, vector(0.0)),
            Voiceprint.CachedSegment(2.0, 2.4, null),
        ))
        Transcripts.save(ctx, transcript(name).put("vp_ver", Voiceprint.ASSIGN_VERSION))
        val seed = File(ctx.filesDir, "voice_seed.json").apply {
            writeText(JSONObject().put("self", JSONArray(listOf(name, expired).map {
                JSONObject().put("file", it).put("start", 0).put("end", 2)
            })).toString())
        }

        assertTrue(Voiceprint.bootstrapIfNeeded(ctx))
        assertTrue(seed.exists())
        val history = File(Transcripts.dir(ctx), "$name.json")
        assertTrue(history.setLastModified(1_000L))
        assertFalse(Voiceprint.bootstrapIfNeeded(ctx))
        assertEquals(1_000L, history.lastModified())
        assertEquals(Speakers.selfIdentity(ctx), Transcripts.get(ctx, name)?.segments?.first()?.speaker)
    }

    @Test fun newSeedCorrectsAutomaticV3Assignment() {
        val name = "20261113-122001.opus"
        val me = Speakers.selfIdentity(ctx)
        val friend = Speakers.namedIdentity(ctx, "朋友")
        Voiceprint.addEnrollment(ctx, me, "20261113-122002.opus", 0.0, 2.0, vector(0.0), "user")
        Voiceprint.writeCache(ctx, name, listOf(
            Voiceprint.CachedSegment(0.0, 2.0, vector(35.0)),
            Voiceprint.CachedSegment(2.0, 2.4, null),
        ))
        val root = Voiceprint.enrichTranscript(ctx, File(RecorderService.chunkDir(ctx), name), transcript(name))
        Transcripts.save(ctx, root)
        assertEquals(me, Transcripts.get(ctx, name)?.segments?.first()?.speaker)

        File(ctx.filesDir, "voice_seed.json").writeText(JSONObject().put("朋友", JSONArray()
            .put(JSONObject().put("file", name).put("start", 0).put("end", 2))).toString())

        Voiceprint.bootstrapIfNeeded(ctx)

        assertEquals(friend, Transcripts.get(ctx, name)?.segments?.first()?.speaker)
    }

    @Test fun rescoreKeepsNamedHistoryAndBothSidesOfAnUnnamedMerge() {
        val name = "20261113-122100.opus"
        val roster = JSONObject().put("items", JSONObject().put("named", JSONObject())
            .put("source", JSONObject()).put("target", JSONObject()).put("untouched", JSONObject()))
        File(ctx.filesDir, "speakers_mac.json").writeText(roster.toString())
        assertTrue(Speakers.edit(ctx, "named", name = "李总"))
        assertTrue(Speakers.edit(ctx, "source", mergeInto = "target"))
        File(RecorderService.chunkDir(ctx).apply { mkdirs() }, name).writeBytes(ByteArray(64))
        val ids = listOf("named", "source", "target", "untouched")
        val segments = JSONArray(ids.mapIndexed { i, id -> JSONObject().put("text", "第${i + 1}句")
            .put("speaker", id).put("start", i).put("end", i + 1) })
        Transcripts.save(ctx, JSONObject().put("name", name).put("vp_ver", 2).put("segments", segments))
        Voiceprint.writeCache(ctx, name, ids.indices.map { i ->
            Voiceprint.CachedSegment(i.toDouble(), i + 1.0, vector(0.0))
        })

        assertTrue(Voiceprint.bootstrapIfNeeded(ctx))

        val speakers = requireNotNull(Transcripts.get(ctx, name)).segments.map { it.speaker }
        assertEquals(ids.take(3), speakers.take(3))
        assertTrue(speakers.last()?.startsWith("other_") == true)
        assertEquals("target", Speakers.resolve(ctx, speakers[1])?.id)

        val friend = Speakers.namedIdentity(ctx, "朋友")
        File(ctx.filesDir, "voice_seed.json").writeText(JSONObject().put("朋友", JSONArray()
            .put(JSONObject().put("file", name).put("start", 3).put("end", 4))).toString())
        assertTrue(Voiceprint.bootstrapIfNeeded(ctx))
        assertEquals(ids.take(3) + friend, Transcripts.get(ctx, name)?.segments?.map { it.speaker })
    }

    @Test fun schemaMigrationInvalidatesAllOldPrototypesAndKeepsRosterMetadata() {
        val old = JSONObject().put("version", 2).put("next_seq", 31).put("items", JSONObject()
            .put("pspk_001", JSONObject().put("centroid", JSONArray(List(192) { 0.1 })).put("model", "old").put("sample", "历史样本").put("first_seen", "昨天"))
            .put("pspk_030", JSONObject().put("centroid", JSONArray(List(192) { 0.2 }))))
        Sync.writeFile(File(ctx.filesDir, "speakers_local.json"), old.toString())
        Speakers.edit(ctx, "pspk_001", name = "李总")
        Speakers.edit(ctx, "pspk_030", mergeInto = "pspk_001")
        val roster = Speakers.all(ctx)
        assertEquals("李总", roster["pspk_001"]!!.name)
        assertEquals("历史样本", roster["pspk_001"]!!.sample)
        assertEquals("pspk_001", Speakers.resolve(ctx, "pspk_030")!!.id)
        val migrated = JSONObject(File(ctx.filesDir, "speakers_local.json").readText())
        assertEquals(3, migrated.getInt("version"))
        assertFalse(migrated.toString().contains("centroid"))
        assertTrue(Voiceprint.enrollmentCentroids(ctx).isEmpty())
    }

    @Test fun syntheticWholeFileSegmentsSkipVoiceprints() {
        val root = PhoneAsr.buildTranscriptJson("20261113-123000.opus", 42.0, "整段文本", "openai", "model", emptyList())
        Voiceprint.enrichTranscript(ctx, File(ctx.filesDir, root.getString("name")), root)
        assertEquals(Voiceprint.ASSIGN_VERSION, root.getInt("vp_ver"))
        assertFalse(root.getJSONArray("segments").getJSONObject(0).has("speaker"))
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

}
