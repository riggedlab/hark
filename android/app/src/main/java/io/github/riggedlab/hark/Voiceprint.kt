package io.github.riggedlab.hark

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** CAM++ 中英增强版：逐句声纹留在手机，仅从用户注册/种子样本识别身份，不使用 ASR 说话人标签。 */
internal object Voiceprint {
    private const val TAG = "Voiceprint"
    const val MODEL_ASSET = "campplus_zh_en_adv_16k.onnx"
    const val MODEL_ID = "campplus_zh_en_adv_16k"
    const val EMBED_DIM = 192
    const val SCHEMA_VERSION = 3
    const val SELF = "__self__"
    const val NOT_SELF = "__not_self__"

    // 2026-11-13 用户标注 391 条校准；与 tools/voiceprint/identify_me.py 的聚类规则一致，参数以任务说明为准。
    internal const val MATCH_THRESHOLD = 0.65f
    internal const val CLUSTER_DISTANCE = 0.6f
    internal const val LONG_SENTENCE_S = 0.8
    private const val MIN_SENTENCE_S = 0.3
    private const val MIN_AUDIO_S = 0.25
    private const val DB_WEIGHT = 0.01f
    private const val DB_REFERENCE = -35f
    private const val MISSING_DB = -50f
    private const val MIN_FLOOR_ENERGY = 1e-10
    private const val ACTIVE_GATE_MIN_DB = -62.0
    private const val ACTIVE_SPAN_DB = 15.0
    private const val MIN_CLAMP_DB = -65.0

    private val env by lazy { OrtEnvironment.getEnvironment() }
    @Volatile private var session: OrtSession? = null
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backgroundLock = Mutex()
    private val labelLock = Mutex()
    private val scanRunning = AtomicBoolean(false)
    private val cacheLock = Any()

    private fun localFile(ctx: Context) = File(ctx.filesDir, "speakers_local.json")
    private fun enrollFile(ctx: Context) = File(ctx.filesDir, "voice_enroll.json")
    private fun labelsFile(ctx: Context) = File(ctx.filesDir, "voice_labels.json")
    private fun cacheFile(ctx: Context, name: String) =
        File(File(ctx.filesDir, "vp_cache").apply { mkdirs() }, "$name.bin")

    private fun session(ctx: Context): OrtSession = session ?: synchronized(this) {
        session ?: OrtSession.SessionOptions().use { opts ->
            opts.setIntraOpNumThreads(2)
            opts.setInterOpNumThreads(1)
            env.createSession(ctx.assets.open(MODEL_ASSET).use { it.readBytes() }, opts).also { session = it }
        }
    }

    /** 只清理旧模型的向量；完整保留旧 pspk 名册及用户改名/合并，供历史显示。 */
    internal fun migrateSchemaFast(ctx: Context): Boolean = synchronized(Speakers) {
        val file = localFile(ctx)
        if (!file.exists()) return false
        val root = JSONObject(file.readText())
        if (root.optInt("version", 1) >= SCHEMA_VERSION) return false
        val items = root.optJSONObject("items") ?: JSONObject()
        for (id in items.keys()) {
            val item = items.getJSONObject(id)
            item.remove("centroid")
            item.remove("weight")
            item.remove("model")
        }
        root.put("version", SCHEMA_VERSION)
        Sync.writeFile(file, root.toString())
        true
    }

    fun triggerBackgroundBootstrap(ctx: Context) {
        SceneAndMood.scheduleBackgroundScan(ctx)
        if (!scanRunning.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        bgScope.launch {
            try {
                backgroundLock.withLock {
                    if (bootstrapIfNeeded(app)) {
                        refreshUi()
                        SceneAndMood.scheduleBackgroundScan(app)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "声纹后台处理失败: ${e.message}")
            } finally {
                scanRunning.set(false)
            }
        }
    }

    private fun refreshUi() = AppState.update { it.copy(textVersion = it.textVersion + 1) }

    fun bootstrapIfNeeded(ctx: Context): Boolean {
        var changed = migrateSchemaFast(ctx)
        val imported = importSeeds(ctx)
        changed = rescoreTranscripts(ctx, force = imported) || changed
        return changed
    }

    /** 注册改变了全局质心，所以所有留有声纹缓存的日期均受影响；非我标注只影响当天。 */
    private fun rescoreTranscripts(ctx: Context, force: Boolean, day: String? = null): Boolean {
        var changed = false
        for (name in Transcripts.allNames(ctx).sortedDescending()) {
            if (day != null && !name.startsWith(day)) continue
            try {
                val updated = Transcripts.computeAndCommit(ctx, name) { root ->
                    if (!force && root.optInt("vp_ver", 0) >= SCHEMA_VERSION) false
                    else doEnrich(ctx, File(RecorderService.chunkDir(ctx), name), root)
                }
                if (updated) {
                    changed = true
                    refreshUi()
                }
            } catch (e: Exception) {
                Log.w(TAG, "$name 声纹处理失败: ${e.message}")
            }
        }
        return changed
    }

    /** 输入与 sherpa-onnx 相同的 16kHz、[-1,1] 单声道采样；不裁边、不截断、不拼接句子。 */
    fun extractEmbedding(ctx: Context, pcm16k: FloatArray): FloatArray? =
        if (pcm16k.size < (KaldiFbank.SAMPLE_RATE * MIN_AUDIO_S).toInt()) null
        else extractEmbedding(session(ctx), pcm16k)

    internal fun extractEmbedding(ort: OrtSession, pcm16k: FloatArray): FloatArray? {
        if (pcm16k.size < (KaldiFbank.SAMPLE_RATE * MIN_AUDIO_S).toInt()) return null
        val (frames, features) = KaldiFbank.extractGlobalMeanNormalized(pcm16k)
        if (frames == 0) return null
        val raw = OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, frames.toLong(), KaldiFbank.NUM_BINS.toLong())).use { input ->
            ort.run(mapOf("x" to input)).use { output ->
                @Suppress("UNCHECKED_CAST")
                (output[0].value as Array<FloatArray>)[0]
            }
        }
        return l2Normalize(raw)
    }
    internal fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x.toDouble()
        val norm = sqrt(sum).toFloat()
        if (norm < 1e-8f) return v.copyOf()
        return FloatArray(v.size) { i -> v[i] / norm }
    }

    internal fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return -1f
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i].toDouble()
        return dot.toFloat()
    }

    /** 将 16kHz 归一化单声道波形切分为 20ms（320 采样点）帧均方能量序列 */
    internal fun computeFrameEnergies16k(pcm16k: FloatArray): DoubleArray {
        val frameLen = 320 // 20ms @ 16kHz
        if (pcm16k.isEmpty()) return DoubleArray(0)
        val fullFrames = pcm16k.size / frameLen
        val rem = pcm16k.size % frameLen
        val total = fullFrames + if (rem >= 80) 1 else 0
        val out = DoubleArray(total)
        var pos = 0
        for (f in 0 until fullFrames) {
            var sumSq = 0.0
            for (k in 0 until frameLen) {
                val v = pcm16k[pos++].toDouble()
                sumSq += v * v
            }
            out[f] = sumSq / frameLen
        }
        if (rem >= 80) {
            var sumSq = 0.0
            for (k in 0 until rem) {
                val v = pcm16k[pos++].toDouble()
                sumSq += v * v
            }
            out[fullFrames] = sumSq / rem
        }
        return out
    }

    /**
     * 计算区间 [startSec, endSec] 内有效发声帧的平均响度（dBFS，1 位小数）与有效发声时长（秒，2 位小数）。
     */
    internal fun computeActiveSpeechStats(
        frameEnergies: DoubleArray,
        frameDurSec: Double,
        startSec: Double,
        endSec: Double,
    ): Pair<Float, Float>? {
        if (frameEnergies.isEmpty() || endSec <= startSec || frameDurSec <= 0.0) return null
        val sIdx = floor(startSec / frameDurSec).toInt().coerceAtLeast(0)
        val eIdx = ceil(endSec / frameDurSec).toInt().coerceAtMost(frameEnergies.size)
        if (eIdx <= sIdx) return null

        val count = eIdx - sIdx
        val dbs = DoubleArray(count)
        for (i in 0 until count) {
            dbs[i] = 10.0 * log10(max(frameEnergies[sIdx + i], MIN_FLOOR_ENERGY))
        }
        val sorted = dbs.copyOf().apply { sort() }
        val p90 = sorted[min(count - 1, (count * 0.90).toInt())]
        val gate = max(p90 - ACTIVE_SPAN_DB, ACTIVE_GATE_MIN_DB)

        var activeSum = 0.0
        var activeCnt = 0
        var allSum = 0.0
        for (i in 0 until count) {
            val e = frameEnergies[sIdx + i]
            allSum += e
            if (dbs[i] >= gate) {
                activeSum += e
                activeCnt++
            }
        }
        val meanE = if (activeCnt > 0) activeSum / activeCnt else allSum / count
        val rawDb = 10.0 * log10(max(meanE, MIN_FLOOR_ENERGY))
        val clampedDb = rawDb.coerceIn(MIN_CLAMP_DB, 0.0)
        val dbOut = ((clampedDb * 10.0).roundToInt() / 10.0).toFloat()
        val effFrames = if (activeCnt > 0) activeCnt else count
        val actSecOut = ((effFrames * frameDurSec * 100.0).roundToInt() / 100.0).coerceAtLeast(0.02).toFloat()
        return dbOut to actSecOut
    }

    internal fun computeActiveSpeechDb(
        frameEnergies: DoubleArray,
        frameDurSec: Double,
        startSec: Double,
        endSec: Double,
    ): Float? = computeActiveSpeechStats(frameEnergies, frameDurSec, startSec, endSec)?.first

    internal data class Span(val startSec: Double, val endSec: Double, val text: String)
    internal data class Utterance(val index: Int, val duration: Double, val embedding: FloatArray, val db: Float = MISSING_DB)
    internal data class Group(val members: MutableList<Int>, val embedding: FloatArray)

    /** 平均连接层次聚类：簇间距离为所有句对余弦距离的平均值，短句不参与合并和组向量计算。 */
    internal fun groupUtterances(utterances: List<Utterance>): List<Group> {
        val long = utterances.filter { it.duration >= LONG_SENTENCE_S }
        val clusters = Array<MutableList<Utterance>?>(long.size) { mutableListOf(long[it]) }
        val distances = Array(long.size) { i -> FloatArray(long.size) { j -> 1f - cosine(long[i].embedding, long[j].embedding) } }
        while (true) {
            var a = -1
            var b = -1
            var best = CLUSTER_DISTANCE
            for (i in clusters.indices) {
                if (clusters[i] == null) continue
                for (j in i + 1 until clusters.size) {
                    if (clusters[j] != null && distances[i][j] < best) {
                        best = distances[i][j]; a = i; b = j
                    }
                }
            }
            if (a < 0) break
            val left = clusters[a]!!
            val right = clusters[b]!!
            for (k in clusters.indices) {
                if (k == a || k == b || clusters[k] == null) continue
                val d = (distances[a][k] * left.size + distances[b][k] * right.size) / (left.size + right.size)
                distances[a][k] = d
                distances[k][a] = d
            }
            left.addAll(right)
            clusters[b] = null
        }
        val groups = clusters.filterNotNull().map { members ->
            val sum = FloatArray(EMBED_DIM)
            for (u in members) for (k in sum.indices) sum[k] += u.embedding[k] * u.duration.toFloat()
            Group(members.map { it.index }.toMutableList(), l2Normalize(sum))
        }.toMutableList()
        val longGroups = groups.toList()
        for (u in utterances.filter { it.duration < LONG_SENTENCE_S }) {
            val group = longGroups.maxByOrNull { cosine(u.embedding, it.embedding) }
            if (group != null) group.members.add(u.index)
            else groups.add(Group(mutableListOf(u.index), u.embedding))
        }
        return groups.sortedBy { it.members.min() }
    }

    internal fun identityScore(u: Utterance, group: FloatArray, centroid: FloatArray): Float {
        // 2026-11-13 用户标注 391 条校准：随句长降低组权重，响度缺失按 -50 dBFS。
        val wg = (0.8 - 0.6 * u.duration / 4).coerceIn(0.2, 0.8).toFloat()
        return (1 - wg) * cosine(u.embedding, centroid) + wg * cosine(group, centroid) + DB_WEIGHT * (u.db - DB_REFERENCE)
    }

    internal fun assignSpeakers(
        utterances: List<Utterance>,
        centroids: Map<String, FloatArray>,
        manual: Map<Int, String> = emptyMap(),
        selfIds: Set<String> = emptySet(),
    ): Map<Int, String> {
        val assigned = LinkedHashMap<Int, String>()
        val byIndex = utterances.associateBy { it.index }
        var otherIndex = 0
        for (group in groupUtterances(utterances)) {
            var otherId: String? = null
            for (index in group.members) {
                val override = manual[index]
                if (override != null && override != NOT_SELF) {
                    assigned[index] = override
                    continue
                }
                val u = byIndex.getValue(index)
                val best = centroids.entries.asSequence()
                    .filter { override != NOT_SELF || it.key !in selfIds }
                    .map { it.key to identityScore(u, group.embedding, it.value) }
                    .maxByOrNull { it.second }
                assigned[index] = if (best != null && best.second >= MATCH_THRESHOLD) best.first else {
                    otherId ?: "other_${++otherIndex}".also { otherId = it }
                }
            }
        }
        // 无声纹（过短、音频缺失）的句子仍遵从人工指定，不借用 ASR 标签。
        for ((i, id) in manual) if (id != NOT_SELF) assigned[i] = id
        return assigned
    }

    internal data class CachedSegment(val start: Double, val end: Double, val embedding: FloatArray?)

    internal fun readCache(ctx: Context, name: String): List<CachedSegment>? = synchronized(cacheLock) {
        val file = cacheFile(ctx, name)
        if (!file.exists()) return null
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != SCHEMA_VERSION || input.readUTF() != MODEL_ID) return null
                List(input.readInt()) {
                    CachedSegment(input.readDouble(), input.readDouble(), if (input.readBoolean()) FloatArray(EMBED_DIM) { input.readFloat() } else null)
                }
            }
        }.onFailure { Log.w(TAG, "声纹缓存 ${file.name} 无效: ${it.message}") }.getOrNull()
    }

    internal fun writeCache(ctx: Context, name: String, entries: List<CachedSegment>) = synchronized(cacheLock) {
        val file = cacheFile(ctx, name)
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { output ->
            output.writeInt(SCHEMA_VERSION)
            output.writeUTF(MODEL_ID)
            output.writeInt(entries.size)
            for (s in entries) {
                output.writeDouble(s.start); output.writeDouble(s.end)
                output.writeBoolean(s.embedding != null)
                s.embedding?.forEach { output.writeFloat(it) }
            }
        }
        check(tmp.renameTo(file)) { "保存声纹缓存失败" }
    }

    private fun slice(pcm: FloatArray, start: Double, end: Double): FloatArray {
        val s = (start * KaldiFbank.SAMPLE_RATE).toInt().coerceIn(0, pcm.size)
        val e = (end * KaldiFbank.SAMPLE_RATE).toInt().coerceIn(s, pcm.size)
        return pcm.copyOfRange(s, e)
    }

    private fun cachedSegments(ctx: Context, opus: File, segments: JSONArray): List<CachedSegment>? {
        val cache = readCache(ctx, opus.name).orEmpty()
        val matching = (0 until segments.length()).map { i ->
            val s = segments.getJSONObject(i)
            cache.firstOrNull { it.start == s.optDouble("start") && it.end == s.optDouble("end") }
        }
        if (matching.all { it != null }) return matching.filterNotNull()
        if (!opus.exists()) return null
        val pcm = Vad.decode16k(opus)
        val energies = computeFrameEnergies16k(pcm)
        val entries = (0 until segments.length()).map { i ->
            val s = segments.getJSONObject(i)
            val start = s.optDouble("start")
            val end = s.optDouble("end")
            if (!s.has("db") || s.isNull("db")) {
                computeActiveSpeechStats(energies, 0.02, start, end)?.let { (db, active) ->
                    s.put("db", db.toDouble()).put("act_s", active.toDouble())
                }
            }
            matching[i] ?: CachedSegment(start, end, if (end - start >= MIN_SENTENCE_S) extractEmbedding(ctx, slice(pcm, start, end)) else null)
        }
        writeCache(ctx, opus.name, entries)
        return entries
    }

    private fun readObject(file: File): JSONObject = if (file.exists()) JSONObject(file.readText()) else JSONObject()
    private fun spanKey(start: Double, end: Double) = "$start:$end"

    private fun manualAssignments(ctx: Context, name: String, segments: JSONArray): Map<Int, String> = synchronized(Speakers) {
        val labels = readObject(labelsFile(ctx)).optJSONObject(name) ?: return emptyMap()
        (0 until segments.length()).mapNotNull { i ->
            val s = segments.getJSONObject(i)
            labels.optString(spanKey(s.optDouble("start"), s.optDouble("end"))).takeIf { it.isNotEmpty() }?.let { i to it }
        }.toMap()
    }

    internal fun enrollmentCentroids(ctx: Context): Map<String, FloatArray> = synchronized(Speakers) {
        val root = readObject(enrollFile(ctx))
        root.keys().asSequence().mapNotNull { id ->
            val samples = root.getJSONArray(id)
            if (samples.length() == 0) return@mapNotNull null
            val sum = FloatArray(EMBED_DIM)
            for (i in 0 until samples.length()) {
                val emb = samples.getJSONObject(i).getJSONArray("emb")
                for (k in sum.indices) sum[k] += emb.getDouble(k).toFloat()
            }
            id to l2Normalize(sum)
        }.toMap()
    }

    internal fun addEnrollment(ctx: Context, id: String, name: String, start: Double, end: Double, embedding: FloatArray, source: String) = synchronized(Speakers) {
        val file = enrollFile(ctx)
        val root = readObject(file)
        val samples = root.optJSONArray(id) ?: JSONArray().also { root.put(id, it) }
        val sample = JSONObject().put("file", name).put("start", start).put("end", end)
            .put("emb", JSONArray(l2Normalize(embedding).map { it.toDouble() })).put("source", source)
        for (i in 0 until samples.length()) {
            val old = samples.getJSONObject(i)
            if (old.optString("file") == name && old.optDouble("start") == start && old.optDouble("end") == end) {
                // 重启重导入不增重，人工样本的来源不被 seed 覆盖。
                if (old.optString("source") == "user" && source == "seed") return
                samples.put(i, sample)
                Sync.writeFile(file, root.toString())
                return
            }
        }
        samples.put(sample)
        Sync.writeFile(file, root.toString())
    }

    /** 人工改判时撤回同一句以前的注册样本，避免旧判断继续影响质心。 */
    private fun removeUserEnrollment(ctx: Context, name: String, start: Double, end: Double): Boolean = synchronized(Speakers) {
        val file = enrollFile(ctx)
        val root = readObject(file)
        var removed = false
        for (id in root.keys()) {
            val samples = root.getJSONArray(id)
            for (i in samples.length() - 1 downTo 0) {
                val s = samples.getJSONObject(i)
                if (s.optString("source") == "user" && s.optString("file") == name && s.optDouble("start") == start && s.optDouble("end") == end) {
                    samples.remove(i)
                    removed = true
                }
            }
        }
        if (removed) Sync.writeFile(file, root.toString())
        removed
    }

    suspend fun labelSentence(ctx: Context, name: String, start: Double, end: Double, identity: String) = labelLock.withLock {
        val id = if (identity == SELF) Speakers.selfIdentity(ctx) else identity
        val emb = if (id == NOT_SELF) null else {
            require(end - start >= MIN_SENTENCE_S) { "这句不足 0.3 秒，无法注册声纹" }
            val entries = readCache(ctx, name).orEmpty()
            entries.firstOrNull { it.start == start && it.end == end }?.embedding ?: run {
                val opus = File(RecorderService.chunkDir(ctx), name)
                check(opus.exists()) { "这句没有可用声纹，且原录音已过期" }
                val extracted = extractEmbedding(ctx, slice(Vad.decode16k(opus), start, end))
                    ?: error("这句音频不足 0.25 秒，无法注册声纹")
                writeCache(ctx, name, entries.filterNot { it.start == start && it.end == end } + CachedSegment(start, end, extracted))
                extracted
            }
        }
        val removed = removeUserEnrollment(ctx, name, start, end)
        if (emb != null) addEnrollment(ctx, id, name, start, end, emb, "user")
        synchronized(Speakers) {
            val file = labelsFile(ctx)
            val root = readObject(file)
            val labels = root.optJSONObject(name) ?: JSONObject().also { root.put(name, it) }
            labels.put(spanKey(start, end), id)
            Sync.writeFile(file, root.toString())
        }
        // 先让人工指定立刻生效，即使原录音已过期，随后再后台重打分。
        Transcripts.computeAndCommit(ctx, name) { root -> applyAssignments(ctx, name, root, readCache(ctx, name).orEmpty()); true }
        refreshUi()
        SceneAndMood.scheduleBackgroundScan(ctx)
        bgScope.launch {
            backgroundLock.withLock {
                if (rescoreTranscripts(ctx, force = true, day = if (emb != null || removed) null else name.take(8))) {
                    SceneAndMood.scheduleBackgroundScan(ctx)
                }
            }
        }
    }

    /** 文件全部成功导入才删除；部分失败保留，已成功样本通过 file/start/end 去重。 */
    internal fun importSeeds(ctx: Context): Boolean {
        val file = File(ctx.filesDir, "voice_seed.json")
        if (!file.exists()) return false
        val seed = JSONObject(file.readText())
        var changed = false
        var complete = true
        for (person in seed.keys()) {
            val id = if (person == "self") Speakers.selfIdentity(ctx) else Speakers.namedIdentity(ctx, person)
            val samples = seed.getJSONArray(person)
            for (i in 0 until samples.length()) {
                val s = samples.getJSONObject(i)
                val name = s.getString("file")
                val start = s.getDouble("start")
                val end = s.getDouble("end")
                try {
                    require(Crypto.audioName.matches(name)) { "种子录音文件名格式错误" }
                    require(end - start >= MIN_SENTENCE_S) { "种子句子不足 0.3 秒" }
                    val cached = readCache(ctx, name)?.firstOrNull { it.start == start && it.end == end }?.embedding
                    val emb = cached ?: extractEmbedding(ctx, slice(Vad.decode16k(File(RecorderService.chunkDir(ctx), name)), start, end))
                        ?: error("种子音频不足 0.25 秒")
                    if (cached == null) {
                        val entries = readCache(ctx, name).orEmpty().filterNot { it.start == start && it.end == end }
                        writeCache(ctx, name, entries + CachedSegment(start, end, emb))
                    }
                    addEnrollment(ctx, id, name, start, end, emb, "seed")
                    changed = true
                } catch (e: Exception) {
                    complete = false
                    Log.w(TAG, "$name 种子导入失败: ${e.message}")
                }
            }
        }
        if (complete) check(file.delete()) { "删除已导入种子文件失败" }
        return changed
    }

    private fun applyAssignments(ctx: Context, name: String, root: JSONObject, cache: List<CachedSegment>) {
        val segments = root.optJSONArray("segments") ?: return
        val manual = manualAssignments(ctx, name, segments)
        val utterances = (0 until segments.length()).mapNotNull { i ->
            val s = segments.getJSONObject(i)
            val start = s.optDouble("start")
            val end = s.optDouble("end")
            val emb = cache.firstOrNull { it.start == start && it.end == end }?.embedding ?: return@mapNotNull null
            Utterance(i, end - start, emb, s.optDouble("db", MISSING_DB.toDouble()).toFloat())
        }
        val roster = Speakers.all(ctx)
        val selfRoots = Speakers.selfIds(roster)
        val selfIds = roster.keys.filter { Speakers.resolve(roster, it)?.id in selfRoots }.toSet()
        val assigned = assignSpeakers(utterances, enrollmentCentroids(ctx), manual, selfIds)
        var other = assigned.values.mapNotNull { it.removePrefix("other_").toIntOrNull() }.maxOrNull() ?: 0
        for (i in 0 until segments.length()) {
            val s = segments.getJSONObject(i)
            val previousSpeaker = s.optString("speaker")
            val speaker = assigned[i] ?: "other_${++other}"
            val isProtected = Proximity.isProtectedSpeakerId(speaker, roster)
            s.put("speaker", speaker)
            if (manual.containsKey(i)) s.put("vp_manual", manual.getValue(i)) else s.remove("vp_manual")
            // 身份保护改变了连续段边界，仍有录音时需要重新计算整段。
            if (previousSpeaker != speaker && isProtected != Proximity.isProtectedSpeakerId(previousSpeaker, roster)) {
                SceneAndMood.invalidateAudioEnrichment(root)
            }
            if (s.optString("proximity") == Proximity.MEDIA && isProtected) {
                s.put("proximity", Proximity.NEAR)
            }
        }
    }

    fun enrichTranscript(ctx: Context, opusFile: File, transcriptJson: JSONObject): JSONObject {
        migrateSchemaFast(ctx)
        try { doEnrich(ctx, opusFile, transcriptJson) }
        catch (e: Exception) { Log.w(TAG, "${opusFile.name} 声纹失败: ${e.message}") }
        return transcriptJson
    }

    private fun doEnrich(ctx: Context, opusFile: File, root: JSONObject): Boolean {
        val segments = root.optJSONArray("segments")
        if (segments == null || segments.length() == 0 || root.optBoolean("synthetic_segments", false)) {
            root.put("vp_done", true).put("vp_ver", SCHEMA_VERSION)
            return true
        }
        val cache = cachedSegments(ctx, opusFile, segments) ?: return false
        applyAssignments(ctx, opusFile.name, root, cache)
        root.put("vp_done", true).put("vp_ver", SCHEMA_VERSION)
        return true
    }

    /** 录音粗筛仍可用显式注册身份保护佩戴者；绝不从历史 ASR 标签自举。 */
    internal fun matchesProtectedSpeaker(ctx: Context, pcm16k: FloatArray, spans: List<Span>, minSim: Float = 0.58f): Boolean {
        val roster = Speakers.all(ctx)
        val centroids = enrollmentCentroids(ctx).filterKeys { Proximity.isProtectedSpeakerId(it, roster) }
        if (centroids.isEmpty()) return false
        return spans.any { span ->
            val emb = extractEmbedding(ctx, slice(pcm16k, span.startSec, span.endSec))
            emb != null && centroids.values.any { cosine(emb, it) >= minSim }
        }
    }
}
