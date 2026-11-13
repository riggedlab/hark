package io.github.riggedlab.hark

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 手机端本地离线声纹识别引擎（阿里 3D-Speaker CAM++ 中文 16kHz 192 维 ONNX）：
 * - 在「手机直连转写」（豆包语音 / OpenAI 兼容）拿到分句时间轴后，直接从本机 .opus 原音频切出说话片段，
 *   离线提取 192 维声纹向量，与本机声纹名册（含从历史已命名录音自举提取的熟人原型）做余弦匹配；
 * - 声纹向量 100% 留存在手机 files/speakers_local.json，绝不上传任何第三方云服务。
 */
internal object Voiceprint {
    private const val TAG = "Voiceprint"
    const val MODEL_ASSET = "campplus_zh_16k.onnx"
    const val MODEL_ID = "campplus_zh_16k"
    const val EMBED_DIM = 192

    internal const val MATCH_THRESHOLD = 0.58f
    internal const val IN_FILE_MERGE_SIM = 0.62f
    internal const val ADAPT_MIN_SIM = 0.68f
    private const val MIN_SPEECH_S = 1.4
    private const val NEW_SPEAKER_MIN_S = 2.8
    private const val MAX_EXTRACT_S = 16.0

    private val FIRST_SEEN_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val env by lazy { OrtEnvironment.getEnvironment() }
    @Volatile private var session: OrtSession? = null
    private val bootstrapped = AtomicBoolean(false)

    private fun localFile(ctx: Context) = File(ctx.filesDir, "speakers_local.json")

    private fun session(ctx: Context): OrtSession = session ?: synchronized(this) {
        session ?: run {
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
            }
            val bytes = ctx.assets.open(MODEL_ASSET).use { it.readBytes() }
            env.createSession(bytes, opts).also { session = it }
        }
    }

    /** 输入 16kHz 单声道归一化波形（[-1, 1]），输出 L2 归一化后的 192 维声纹向量 */
    fun extractEmbedding(ctx: Context, pcm16k: FloatArray): FloatArray? {
        if (pcm16k.size < (KaldiFbank.SAMPLE_RATE * MIN_SPEECH_S).toInt()) return null
        val (numFrames, feats) = KaldiFbank.extractGlobalMeanNormalized(pcm16k)
        if (numFrames < 40) return null
        val ort = session(ctx)
        val raw = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(feats),
            longArrayOf(1L, numFrames.toLong(), KaldiFbank.NUM_BINS.toLong()),
        ).use { input ->
            ort.run(mapOf("x" to input)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val arr = out[0].value as Array<FloatArray>
                arr[0]
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

    internal data class Span(val startSec: Double, val endSec: Double, val text: String) {
        val durationSec: Double get() = (endSec - startSec).coerceAtLeast(0.0)
    }

    /** 从整段 16kHz 音频中按最长优先截取某说话人的干净语音（单次最多取 16 秒，兼顾准确度与 <100ms 耗时） */
    internal fun sliceSpeakerAudio(pcm16k: FloatArray, spans: List<Span>): Pair<FloatArray, Double> {
        val sr = KaldiFbank.SAMPLE_RATE
        val sorted = spans.filter { it.durationSec >= 0.45 }.sortedByDescending { it.durationSec }
        val maxSamples = (MAX_EXTRACT_S * sr).toInt()
        val picked = ArrayList<IntRange>()
        var totalSamples = 0
        var totalSpeechSec = 0.0
        for (sp in spans) {
            if (sp.durationSec > 0.1) totalSpeechSec += sp.durationSec
        }
        for (sp in sorted) {
            if (totalSamples >= maxSamples) break
            // 长句掐去首尾 80ms 边界，减少换人交界串音
            val trim = if (sp.durationSec >= 1.4) 0.08 else 0.0
            val sIdx = ((sp.startSec + trim) * sr).toInt().coerceIn(0, pcm16k.size)
            val eIdx = ((sp.endSec - trim) * sr).toInt().coerceIn(sIdx, pcm16k.size)
            val len = eIdx - sIdx
            if (len < (0.35 * sr).toInt()) continue
            val take = min(len, maxSamples - totalSamples)
            picked.add(sIdx until (sIdx + take))
            totalSamples += take
        }
        if (totalSamples == 0) return FloatArray(0) to totalSpeechSec
        val out = FloatArray(totalSamples)
        var pos = 0
        for (r in picked) {
            val len = r.last - r.first + 1
            System.arraycopy(pcm16k, r.first, out, pos, len)
            pos += len
        }
        return out to totalSpeechSec
    }

    internal data class LocalTurnGroup(
        val key: String,
        val speechSec: Double,
        val embedding: FloatArray,
        val bestSample: String,
    )

    /** 文件内全连接聚类：合并云端 ASR 偶发切碎的同一说话人，同时防止 A-B-C 链式串味 */
    internal fun clusterInFile(
        groups: List<LocalTurnGroup>,
        mergeThreshold: Float = IN_FILE_MERGE_SIM,
    ): List<Pair<List<String>, LocalTurnGroup>> {
        val ordered = groups.sortedByDescending { it.speechSec }
        val clusters = ArrayList<MutableList<LocalTurnGroup>>()
        for (g in ordered) {
            var targetCluster: MutableList<LocalTurnGroup>? = null
            for (c in clusters) {
                if (c.all { m -> cosine(g.embedding, m.embedding) >= mergeThreshold }) {
                    targetCluster = c
                    break
                }
            }
            if (targetCluster != null) targetCluster.add(g) else clusters.add(mutableListOf(g))
        }
        return clusters.map { members ->
            val dim = members.first().embedding.size
            val sum = FloatArray(dim)
            var totalS = 0.0
            var bestQuote = ""
            val keys = ArrayList<String>(members.size)
            for (m in members) {
                keys.add(m.key)
                val w = m.speechSec.toFloat().coerceAtLeast(0.5f)
                totalS += m.speechSec
                for (i in 0 until dim) sum[i] += m.embedding[i] * w
                if (m.bestSample.length > bestQuote.length) bestQuote = m.bestSample
            }
            keys to LocalTurnGroup(
                key = keys.first(),
                speechSec = totalS,
                embedding = l2Normalize(sum),
                bestSample = bestQuote,
            )
        }
    }

    /**
     * 后台一次性自举：
     * 1. 若手机已有从电脑同步来的熟人名册（如 spk_001「我」）和本机未过期的 .opus 录音，
     *    自动从这些熟人说过的片段提取 192 维声纹原型存入 speakers_local.json，实现零配置无缝衔接；
     * 2. 若近期已有直连转写留下的 other_N 片段且原音频仍在，顺带补做声纹认人。
     */
    fun bootstrapIfNeeded(ctx: Context): Boolean {
        if (!bootstrapped.compareAndSet(false, true)) return false
        return runCatching {
            var changed = false
            val chunkDir = RecorderService.chunkDir(ctx)
            val roster = Speakers.all(ctx)
            val existingPrototypes = loadPrototypes(ctx)

            // 1. 为尚未建立本机声纹原型的已有说话人（如电脑同步来的「我」）从本机历史录音提取声纹
            val missingSpkIds = roster.values
                .filter { it.mergedInto == null && it.id !in existingPrototypes }
                .map { it.id }
                .toSet()

            if (missingSpkIds.isNotEmpty()) {
                val allNames = Transcripts.allNames(ctx).sortedDescending()
                val collected = HashMap<String, MutableList<Pair<File, Span>>>()
                val collectedSec = HashMap<String, Double>()

                for (name in allNames) {
                    if (missingSpkIds.all { (collectedSec[it] ?: 0.0) >= 12.0 }) break
                    val opus = File(chunkDir, name)
                    if (!opus.exists()) continue
                    val tr = Transcripts.get(ctx, name) ?: continue
                    for (seg in tr.segments) {
                        val dur = seg.end - seg.start
                        if (dur < 1.5 || seg.text.isBlank() || seg.speaker == null) continue
                        val canonical = Speakers.resolve(ctx, seg.speaker)?.id ?: continue
                        if (canonical !in missingSpkIds) continue
                        val curS = collectedSec[canonical] ?: 0.0
                        if (curS >= 14.0) continue
                        collected.getOrPut(canonical) { ArrayList() }.add(opus to Span(seg.start, seg.end, seg.text))
                        collectedSec[canonical] = curS + dur
                    }
                }

                for ((spkId, items) in collected) {
                    val byFile = items.groupBy({ it.first }, { it.second })
                    val sumVec = FloatArray(EMBED_DIM)
                    var totalW = 0.0
                    for ((file, spans) in byFile) {
                        val pcm = runCatching { Vad.decode16k(file) }.getOrNull() ?: continue
                        if (pcm.isEmpty()) continue
                        val (clip, sec) = sliceSpeakerAudio(pcm, spans)
                        val emb = extractEmbedding(ctx, clip) ?: continue
                        val w = sec.coerceAtLeast(1.0)
                        for (i in 0 until EMBED_DIM) sumVec[i] += emb[i] * w.toFloat()
                        totalW += w
                    }
                    if (totalW >= MIN_SPEECH_S) {
                        savePrototype(ctx, spkId, l2Normalize(sumVec), totalW, null, null)
                        Log.i(TAG, "已从本机历史录音为 $spkId 建立声纹原型 (${String.format("%.1f", totalW)}s)")
                        changed = true
                    }
                }
            }

            // 2. 为最近尚未赋予全局声纹（仅含 other_N 或空说话人）且原音频仍在的直连转写补标声纹
            val recentNames = Transcripts.allNames(ctx).sortedDescending().take(25)
            for (name in recentNames) {
                val opus = File(chunkDir, name)
                if (!opus.exists()) continue
                val trFile = File(Transcripts.dir(ctx), "$name.json")
                if (!trFile.exists()) continue
                val rawJson = runCatching { JSONObject(trFile.readText()) }.getOrNull() ?: continue
                if (rawJson.optBoolean("vp_done", false)) continue
                val segs = rawJson.optJSONArray("segments") ?: continue
                if (segs.length() == 0) continue
                val alreadyGlobal = (0 until segs.length()).any { i ->
                    val sp = segs.getJSONObject(i).optString("speaker")
                    sp.startsWith("spk_") || sp.startsWith("pspk_")
                }
                if (alreadyGlobal) continue
                val enriched = enrichTranscript(ctx, opus, rawJson)
                Transcripts.save(ctx, enriched)
                changed = true
            }
            changed
        }.onFailure { e ->
            Log.w(TAG, "bootstrap voiceprint skipped: ${e.message}")
        }.getOrDefault(false)
    }

    /**
     * 对手机直连 ASR 返回的转写 JSON 做本地声纹提取、同段聚类与全局熟人认人，返回增强后的 JSON。
     * 任何异常均安全降级保留原转写结果，绝不阻塞出字。
     */
    fun enrichTranscript(ctx: Context, opusFile: File, transcriptJson: JSONObject): JSONObject {
        bootstrapIfNeeded(ctx)
        return runCatching {
            doEnrich(ctx, opusFile, transcriptJson)
        }.onFailure { e ->
            Log.w(TAG, "voiceprint enrich ${opusFile.name} skipped: ${e.message}")
        }.getOrDefault(transcriptJson)
    }

    private fun doEnrich(ctx: Context, opusFile: File, root: JSONObject): JSONObject {
        val segArr = root.optJSONArray("segments")
        val fullText = root.optString("text").trim()
        val durationSec = root.optDouble("duration_s", 0.0)
        if ((segArr == null || segArr.length() == 0) && fullText.isEmpty()) return root

        // 若服务端未提供真实分句（如 OpenAI json 回退生成的整段 synthetic_segments）或无 segments：
        // 在解码原音频前直接标记跳过，既节省解码开销，又防止整段多人对话被误归给某一人
        if (root.optBoolean("synthetic_segments", false) || segArr == null || segArr.length() == 0) {
            root.put("vp_done", true)
            return root
        }
        val workingSegs = segArr

        // 若自建服务已返回全局 spk_ 标识则直接保留
        val hasGlobal = (0 until workingSegs.length()).any { i ->
            val s = workingSegs.getJSONObject(i).optString("speaker")
            s.startsWith("spk_") || s.startsWith("pspk_")
        }
        if (hasGlobal) return root

        val t0 = System.currentTimeMillis()
        val pcm = Vad.decode16k(opusFile)
        if (pcm.size < (KaldiFbank.SAMPLE_RATE * MIN_SPEECH_S).toInt()) {
            root.put("vp_done", true)
            return root
        }

        val hasDiarizedLabels = (0 until workingSegs.length()).any { i ->
            workingSegs.getJSONObject(i).optString("speaker").isNotBlank()
        }

        // 为每个 segment 分配局部说话组 key：
        // - 有声学分离标签（如豆包语音）：按说话人标签分组，允许建档与自适应更新；
        // - 无说话人标签（如通用 Whisper）：仅逐单句独立提取，只读匹配已知熟人，绝不跨句拼接或污染声纹库。
        val segKeys = arrayOfNulls<String>(workingSegs.length())
        val groupedSpans = LinkedHashMap<String, MutableList<Span>>()

        if (hasDiarizedLabels) {
            for (i in 0 until workingSegs.length()) {
                val o = workingSegs.getJSONObject(i)
                val txt = o.optString("text").trim()
                val spk = o.optString("speaker").takeIf { it.isNotBlank() } ?: continue
                if (txt.isEmpty()) continue
                segKeys[i] = spk
                groupedSpans.getOrPut(spk) { ArrayList() }
                    .add(Span(o.optDouble("start"), o.optDouble("end"), txt))
            }
        } else {
            for (i in 0 until workingSegs.length()) {
                val o = workingSegs.getJSONObject(i)
                val txt = o.optString("text").trim()
                if (txt.isEmpty()) continue
                val st = o.optDouble("start")
                val ed = o.optDouble("end")
                // 单句超长且无分离标签时可能跨越多人话轮，保守跳过声纹归属
                if (ed - st > 25.0) continue
                val key = "__seg_$i"
                segKeys[i] = key
                groupedSpans[key] = mutableListOf(Span(st, ed, txt))
            }
        }

        val extractedGroups = ArrayList<LocalTurnGroup>()
        for ((key, spans) in groupedSpans) {
            val (clip, speechSec) = sliceSpeakerAudio(pcm, spans)
            if (speechSec < MIN_SPEECH_S || clip.isEmpty()) continue
            val emb = extractEmbedding(ctx, clip) ?: continue
            val sample = spans.maxByOrNull { it.text.length }?.text?.take(80).orEmpty()
            extractedGroups.add(LocalTurnGroup(key = key, speechSec = speechSec, embedding = emb, bestSample = sample))
        }

        if (extractedGroups.isEmpty()) {
            root.put("vp_done", true)
            return root
        }

        val mergeThresh = if (hasDiarizedLabels) IN_FILE_MERGE_SIM else MATCH_THRESHOLD
        val clusters = clusterInFile(extractedGroups, mergeThresh)
        val firstSeenStr = Recordings.parseStart(opusFile)?.format(FIRST_SEEN_FMT)

        val keyToFinalSpeaker = matchAndPersistClusters(
            ctx = ctx,
            clusters = clusters,
            firstSeen = firstSeenStr,
            allowPersistPrototypes = hasDiarizedLabels,
        )

        // 未达提取时长的极短组：映射为不冲突的局部 other_N
        var fallbackIdx = keyToFinalSpeaker.values.count { it.startsWith("other_") }
        val shortFallback = HashMap<String, String>()
        for (i in 0 until workingSegs.length()) {
            val k = segKeys[i] ?: continue
            val assigned = keyToFinalSpeaker[k] ?: shortFallback.getOrPut(k) {
                fallbackIdx++
                "other_$fallbackIdx"
            }
            workingSegs.getJSONObject(i).put("speaker", assigned)
        }
        root.put("vp_done", true)
        Log.i(
            TAG,
            "${opusFile.name}: 端侧声纹完成（${clusters.size} 人 -> ${keyToFinalSpeaker.values.distinct()}），耗时 ${System.currentTimeMillis() - t0}ms",
        )
        return root
    }

    private data class Prototype(
        val id: String,
        val centroid: FloatArray,
        val weight: Double,
        val model: String,
    )

    private fun loadPrototypes(ctx: Context): Map<String, Prototype> = synchronized(Speakers) {
        val f = localFile(ctx)
        if (!f.exists()) return emptyMap()
        val items = runCatching { JSONObject(f.readText()).optJSONObject("items") }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Prototype>()
        for (id in items.keys()) {
            val obj = items.optJSONObject(id) ?: continue
            val arr = obj.optJSONArray("centroid") ?: continue
            if (arr.length() != EMBED_DIM) continue
            val vec = FloatArray(EMBED_DIM) { i -> arr.optDouble(i, 0.0).toFloat() }
            out[id] = Prototype(
                id = id,
                centroid = l2Normalize(vec),
                weight = obj.optDouble("weight", 1.0),
                model = obj.optString("model", MODEL_ID),
            )
        }
        out
    }

    private fun savePrototype(
        ctx: Context,
        id: String,
        centroid: FloatArray,
        weight: Double,
        firstSeen: String?,
        sample: String?,
    ) = synchronized(Speakers) {
        val f = localFile(ctx)
        val root = if (f.exists()) runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject()) else JSONObject()
        val items = root.optJSONObject("items") ?: JSONObject().also { root.put("items", it) }
        val obj = items.optJSONObject(id) ?: JSONObject()
        val cArr = JSONArray()
        for (v in centroid) cArr.put(v.toDouble())
        obj.put("centroid", cArr)
        obj.put("weight", weight)
        obj.put("model", MODEL_ID)
        if (!firstSeen.isNullOrBlank() && obj.optString("first_seen").isBlank()) {
            obj.put("first_seen", firstSeen)
        }
        if (!sample.isNullOrBlank() && obj.optString("sample").isBlank()) {
            obj.put("sample", sample.take(80))
        }
        items.put(id, obj)
        Sync.writeFile(f, root.toString())
    }

    internal fun matchAndPersistClusters(
        ctx: Context,
        clusters: List<Pair<List<String>, LocalTurnGroup>>,
        firstSeen: String?,
        allowPersistPrototypes: Boolean = true,
    ): Map<String, String> = synchronized(Speakers) {
        val initialPrototypes = loadPrototypes(ctx)
        val roster = Speakers.all(ctx)
        fun canonicalOf(id: String): String {
            var cur = roster[id] ?: return id
            val seen = HashSet<String>(4)
            while (cur.mergedInto != null && seen.add(cur.id)) {
                cur = roster[cur.mergedInto] ?: break
            }
            return cur.id
        }
        val allKnownIds = (roster.keys + initialPrototypes.keys).toMutableSet()
        val claimedCanonicalIds = HashSet<String>()
        val mapping = HashMap<String, String>()
        var otherIdx = 0

        fun nextPhoneSpeakerId(): String {
            var n = 1
            while (true) {
                val cand = "pspk_%03d".format(n)
                if (cand !in allKnownIds) {
                    allKnownIds.add(cand)
                    return cand
                }
                n++
            }
        }

        // 按说话时长降序优先认领历史声纹原型
        val orderedClusters = clusters.sortedByDescending { it.second.speechSec }
        for ((memberKeys, cluster) in orderedClusters) {
            var bestSim = -1f
            var bestId = ""
            for ((id, proto) in initialPrototypes) {
                if (canonicalOf(id) in claimedCanonicalIds) continue
                if (proto.model != MODEL_ID || proto.centroid.size != cluster.embedding.size) continue
                val sim = cosine(cluster.embedding, proto.centroid)
                if (sim > bestSim) {
                    bestSim = sim
                    bestId = id
                }
            }

            val targetId = when {
                bestSim >= MATCH_THRESHOLD -> {
                    claimedCanonicalIds.add(canonicalOf(bestId))
                    val proto = initialPrototypes[bestId]!!
                    if (allowPersistPrototypes && bestSim >= ADAPT_MIN_SIM) {
                        val oldW = proto.weight.coerceIn(1.0, 30.0)
                        val addW = cluster.speechSec.coerceIn(1.0, 8.0)
                        val updated = FloatArray(EMBED_DIM) { i ->
                            (proto.centroid[i] * oldW + cluster.embedding[i] * addW).toFloat()
                        }
                        val normUpdated = l2Normalize(updated)
                        savePrototype(ctx, bestId, normUpdated, oldW + addW, firstSeen, cluster.bestSample)
                    }
                    // 直接落盘原始原型 ID（而非 resolve 后的目标 ID），保证后续「拆出」时历史录音无损恢复归属
                    bestId
                }
                allowPersistPrototypes && cluster.speechSec >= NEW_SPEAKER_MIN_S -> {
                    val newId = nextPhoneSpeakerId()
                    savePrototype(ctx, newId, cluster.embedding, cluster.speechSec, firstSeen, cluster.bestSample)
                    newId
                }
                else -> {
                    otherIdx++
                    "other_$otherIdx"
                }
            }

            for (k in memberKeys) mapping[k] = targetId
        }
        return mapping
    }
}
