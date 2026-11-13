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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 手机端本地离线声纹识别 + 逐句有效响度（dBFS）引擎：
 * 1. 采用阿里 3D-Speaker CAM++ 中文 16kHz 192 维 ONNX 模型提取逐句（>= 2.0s）声纹（阈值 0.68、无漂移原型、同段互斥认领）；
 * 2. 对每句话按 20ms 帧计算剔除句中停顿后的有效发声响度（dBFS）与有效发声秒数（act_s），供人与 AI 判断同一场景内说话人相对远近；
 * 3. 声纹向量 100% 留存手机本地 files/speakers_local.json，绝不上传第三方云端。
 */
internal object Voiceprint {
    private const val TAG = "Voiceprint"
    const val MODEL_ASSET = "campplus_zh_16k.onnx"
    const val MODEL_ID = "campplus_zh_16k"
    const val EMBED_DIM = 192
    const val SCHEMA_VERSION = 2

    // 宁可把同一人保守标为「对话人 N」，绝不把他人误标为「我」
    internal const val MATCH_THRESHOLD = 0.68f
    internal const val IN_FILE_MERGE_SIM = 0.68f
    private const val BOOTSTRAP_INLIER_SIM = 0.70f
    private const val MIN_SPEECH_S = 2.0
    private const val NEW_SPEAKER_MIN_S = 10.0
    private const val MAX_EXTRACT_S = 14.0
    private const val SHORT_INHERIT_MAX_DB_DIFF = 8.5f

    private const val MIN_FLOOR_ENERGY = 1e-10
    private const val ACTIVE_GATE_MIN_DB = -62.0
    private const val ACTIVE_SPAN_DB = 15.0
    private const val MIN_CLAMP_DB = -65.0

    private val FIRST_SEEN_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val env by lazy { OrtEnvironment.getEnvironment() }
    @Volatile private var session: OrtSession? = null
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bootstrapRunning = AtomicBoolean(false)
    @Volatile private var bootstrapDone = false

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

    /**
     * 毫秒级纯 JSON 结构升级（绝不解码音频，可安全在任何路径调用）：
     * - 清除旧版 v1 低阈值产生的质心向量，防止串人；
     * - 保留用户在 speakers_phone.json 中改名或合并过的历史 pspk_ 元数据（first_seen / sample）；
     * - 记录单调递增的 next_seq，严禁复用任何曾分配过的 pspk_ 编号。
     */
    internal fun migrateSchemaFast(ctx: Context): Boolean = synchronized(Speakers) {
        val f = localFile(ctx)
        if (!f.exists()) return false
        val oldRoot = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return false
        val oldVer = oldRoot.optInt("version", 1)
        if (oldVer >= SCHEMA_VERSION) return false

        val phoneFile = File(ctx.filesDir, "speakers_phone.json")
        val phoneItems = if (phoneFile.exists()) {
            runCatching { JSONObject(phoneFile.readText()).optJSONObject("items") }.getOrNull() ?: JSONObject()
        } else JSONObject()

        val oldItems = oldRoot.optJSONObject("items") ?: JSONObject()
        var maxPspkSeq = oldRoot.optInt("next_seq", 1) - 1
        fun scanSeq(keys: Iterator<String>) {
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.startsWith("pspk_")) {
                    val num = k.removePrefix("pspk_").toIntOrNull() ?: 0
                    if (num > maxPspkSeq) maxPspkSeq = num
                }
            }
        }
        scanSeq(oldItems.keys())
        scanSeq(phoneItems.keys())

        // 保留用户曾显式改名/合并的历史条目及其全部合并目标链元数据（仅剥离旧版 v1 centroid 向量）
        val keepIds = HashSet<String>()
        for (id in phoneItems.keys()) {
            keepIds.add(id)
            var target = phoneItems.optJSONObject(id)?.optString("merged_into")?.takeIf { it.isNotBlank() }
            val seen = HashSet<String>(4)
            while (target != null && seen.add(target)) {
                keepIds.add(target)
                target = phoneItems.optJSONObject(target)?.optString("merged_into")?.takeIf { it.isNotBlank() }
            }
        }
        val migratedItems = JSONObject()
        for (id in oldItems.keys()) {
            if (id !in keepIds) continue
            val oldObj = oldItems.optJSONObject(id) ?: continue
            val metaOnly = JSONObject()
            oldObj.optString("first_seen").takeIf { it.isNotBlank() }?.let { metaOnly.put("first_seen", it) }
            oldObj.optString("sample").takeIf { it.isNotBlank() }?.let { metaOnly.put("sample", it) }
            migratedItems.put(id, metaOnly)
        }

        val newRoot = JSONObject()
            .put("version", SCHEMA_VERSION)
            .put("next_seq", maxPspkSeq + 1)
            .put("items", migratedItems)
        Sync.writeFile(f, newRoot.toString())
        Log.i(TAG, "已升级声纹库至 v$SCHEMA_VERSION（清理旧质心，保留 ${migratedItems.length()} 条已编辑元数据，next_seq=${maxPspkSeq + 1}）")
        true
    }

    /** 触发非阻塞后台自举与近期直连录音修正（在 uploadLock 之外异步运行，绝不阻塞实时新录音转写） */
    fun triggerBackgroundBootstrap(ctx: Context) {
        if (bootstrapDone || !bootstrapRunning.compareAndSet(false, true)) return
        val appCtx = ctx.applicationContext
        bgScope.launch {
            try {
                if (bootstrapIfNeeded(appCtx)) {
                    AppState.update { it.copy(textVersion = it.textVersion + 1) }
                }
                bootstrapDone = true
            } finally {
                bootstrapRunning.set(false)
            }
        }
    }

    /** 后台从电脑端已标注录音中为熟人（如 spk_001「我」）提取高纯度 Medoid 声纹原型 */
    private fun bootstrapKnownSpeakersFromHistory(ctx: Context): Boolean {
        var changed = migrateSchemaFast(ctx)
        val chunkDir = RecorderService.chunkDir(ctx)
        val roster = Speakers.all(ctx)
        val existingPrototypes = loadPrototypes(ctx)

        val missingSpkIds = roster.values
            .filter { !it.id.startsWith("pspk_") && it.mergedInto == null && it.id !in existingPrototypes }
            .map { it.id }
            .toSet()

        if (missingSpkIds.isEmpty()) return changed

        data class CandidateUtt(val emb: FloatArray, val durSec: Double, val db: Float)
        val candidates = HashMap<String, MutableList<CandidateUtt>>()
        val allNames = Transcripts.allNames(ctx).sortedDescending()

        for (name in allNames) {
            if (missingSpkIds.all { (candidates[it]?.size ?: 0) >= 10 }) break
            val opus = File(chunkDir, name)
            if (!opus.exists()) continue
            val trFile = File(Transcripts.dir(ctx), "$name.json")
            val rawRoot = runCatching { JSONObject(trFile.readText()) }.getOrNull() ?: continue
            val engine = rawRoot.optString("engine")
            if (engine == "volcengine" || engine == "openai") continue
            val segArr = rawRoot.optJSONArray("segments") ?: continue

            var pcm: FloatArray? = null
            var energies: DoubleArray? = null
            for (i in 0 until segArr.length()) {
                val s = segArr.getJSONObject(i)
                val rawSpk = s.optString("speaker")
                if (rawSpk !in missingSpkIds) continue
                val bucket = candidates.getOrPut(rawSpk) { ArrayList() }
                if (bucket.size >= 10) continue
                val st = s.optDouble("start")
                val ed = s.optDouble("end")
                val dur = ed - st
                if (dur < 2.2 || s.optString("text").isBlank()) continue

                if (pcm == null) {
                    pcm = runCatching { Vad.decode16k(opus) }.getOrNull() ?: break
                    energies = computeFrameEnergies16k(pcm)
                }
                val (segDb, _) = computeActiveSpeechStats(energies!!, 0.02, st, ed) ?: continue
                if (segDb < -34.0f) continue
                val (clip, clipSec) = sliceSpeakerAudio(pcm, listOf(Span(st, ed, "")))
                val emb = extractEmbedding(ctx, clip) ?: continue
                bucket.add(CandidateUtt(emb, clipSec, segDb))
            }
        }

        for ((spkId, list) in candidates) {
            if (list.isEmpty()) continue
            val inliers = if (list.size == 1) {
                list
            } else {
                var bestIdx = 0
                var bestMeanSim = -1.0
                for (i in list.indices) {
                    var sumSim = 0.0
                    for (j in list.indices) if (i != j) sumSim += cosine(list[i].emb, list[j].emb)
                    val meanSim = sumSim / (list.size - 1)
                    if (meanSim > bestMeanSim) {
                        bestMeanSim = meanSim
                        bestIdx = i
                    }
                }
                val medoid = list[bestIdx].emb
                list.filter { cosine(it.emb, medoid) >= BOOTSTRAP_INLIER_SIM }
            }
            val totalSec = inliers.sumOf { it.durSec }
            if (inliers.isEmpty() || totalSec < MIN_SPEECH_S) continue

            val sumVec = FloatArray(EMBED_DIM)
            for (u in inliers) {
                val w = u.durSec.coerceIn(1.0, 6.0).toFloat()
                for (k in 0 until EMBED_DIM) sumVec[k] += u.emb[k] * w
            }
            val avgDb = inliers.map { it.db }.average()
            savePrototype(ctx, spkId, l2Normalize(sumVec), totalSec, null, null)
            Log.i(
                TAG,
                "已为 $spkId 建立 v$SCHEMA_VERSION 高精度声纹原型 (${inliers.size}/${list.size} 句内点, ${"%.1f".format(totalSec)}s, 均值 ${"%.1f".format(avgDb)}dB)",
            )
            changed = true
        }
        return changed
    }

    fun bootstrapIfNeeded(ctx: Context): Boolean {
        return runCatching {
            var changed = bootstrapKnownSpeakersFromHistory(ctx)
            val chunkDir = RecorderService.chunkDir(ctx)

            val recentNames = Transcripts.allNames(ctx).sortedDescending().take(18)
            for (name in recentNames) {
                val opus = File(chunkDir, name)
                if (!opus.exists()) continue
                val trFile = File(Transcripts.dir(ctx), "$name.json")
                if (!trFile.exists()) continue
                val rawJson = runCatching { JSONObject(trFile.readText()) }.getOrNull() ?: continue
                if (rawJson.optInt("vp_ver", 0) >= SCHEMA_VERSION) continue
                val segs = rawJson.optJSONArray("segments") ?: continue
                if (segs.length() == 0) continue

                val enriched = doEnrich(ctx, opus, rawJson)
                Transcripts.save(ctx, enriched)
                changed = true
            }
            changed
        }.onFailure { e ->
            Log.w(TAG, "bootstrap voiceprint skipped: ${e.message}")
        }.getOrDefault(false)
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

    internal data class Span(val startSec: Double, val endSec: Double, val text: String) {
        val durationSec: Double get() = (endSec - startSec).coerceAtLeast(0.0)
    }

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
            val trim = if (sp.durationSec >= 1.6) 0.10 else 0.0
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
     * 为云端同步到达的转写补齐本机原音频的逐句分贝 "db" 与有效发声时长 "act_s"（若远端尚未提供），不改动其已有说话人标签。
     */
    fun annotateLoudnessIfMissing(opusFile: File, root: JSONObject): JSONObject = runCatching {
        val segArr = root.optJSONArray("segments") ?: return root
        if (segArr.length() == 0) return root
        val needAny = (0 until segArr.length()).any { i ->
            val o = segArr.getJSONObject(i)
            (!o.has("db") || o.isNull("db")) && o.optDouble("end") > o.optDouble("start")
        }
        if (!needAny) return root
        val pcm = Vad.decode16k(opusFile)
        val energies = computeFrameEnergies16k(pcm)
        if (energies.isEmpty()) return root
        for (i in 0 until segArr.length()) {
            val o = segArr.getJSONObject(i)
            if (o.has("db") && !o.isNull("db")) continue
            val stats = computeActiveSpeechStats(energies, 0.02, o.optDouble("start"), o.optDouble("end")) ?: continue
            o.put("db", stats.first.toDouble())
            o.put("act_s", stats.second.toDouble())
        }
        root
    }.getOrDefault(root)

    /**
     * 实时转写路径：仅执行毫秒级 JSON 版本检查（绝不扫描历史音频，绝不长时间占用 uploadLock），
     * 直接使用已就绪声纹原型完成逐句分贝计算与高精度声纹归属。
     */
    fun enrichTranscript(ctx: Context, opusFile: File, transcriptJson: JSONObject): JSONObject {
        migrateSchemaFast(ctx)
        return runCatching {
            doEnrich(ctx, opusFile, transcriptJson)
        }.onFailure { e ->
            Log.w(TAG, "voiceprint enrich ${opusFile.name} skipped: ${e.message}")
        }.getOrDefault(transcriptJson)
    }

    private fun doEnrich(ctx: Context, opusFile: File, root: JSONObject): JSONObject {
        val segArr = root.optJSONArray("segments")
        if (root.optBoolean("synthetic_segments", false) || segArr == null || segArr.length() == 0) {
            root.put("vp_done", true)
            root.put("vp_ver", SCHEMA_VERSION)
            return root
        }

        val t0 = System.currentTimeMillis()
        val pcm = Vad.decode16k(opusFile)
        val energies = computeFrameEnergies16k(pcm)

        // 1. 为所有真实分句写入剔除停顿后的有效语音平均分贝 "db" 与有效发声时长 "act_s"
        val segDbs = arrayOfNulls<Float>(segArr.length())
        for (i in 0 until segArr.length()) {
            val o = segArr.getJSONObject(i)
            val st = o.optDouble("start")
            val ed = o.optDouble("end")
            val stats = computeActiveSpeechStats(energies, 0.02, st, ed)
            if (stats != null) {
                segDbs[i] = stats.first
                o.put("db", stats.first.toDouble())
                o.put("act_s", stats.second.toDouble())
            }
        }

        val engine = root.optString("engine")
        val isPhoneDirect = engine == "volcengine" || engine == "openai"

        if (!isPhoneDirect) {
            val hasMacGlobal = (0 until segArr.length()).any { i ->
                segArr.getJSONObject(i).optString("speaker").startsWith("spk_")
            }
            if (hasMacGlobal) {
                root.put("vp_done", true)
                root.put("vp_ver", SCHEMA_VERSION)
                return root
            }
        }

        if (pcm.size < (KaldiFbank.SAMPLE_RATE * MIN_SPEECH_S).toInt()) {
            root.put("vp_done", true)
            root.put("vp_ver", SCHEMA_VERSION)
            return root
        }

        val hasDiarizedLabels = (0 until segArr.length()).any { i ->
            segArr.getJSONObject(i).optString("speaker").isNotBlank()
        }

        // 2. 仅对单句时长 >= 2.0s 的句子独立提取声纹（绝不跨短句拼接，彻底阻断混人连带误认）
        val extractedUtts = ArrayList<LocalTurnGroup>()
        val rawLabels = arrayOfNulls<String>(segArr.length())

        for (i in 0 until segArr.length()) {
            val o = segArr.getJSONObject(i)
            val txt = o.optString("text").trim()
            if (txt.isEmpty()) continue
            val rawSpk = o.optString("speaker").takeIf { it.isNotBlank() }
            rawLabels[i] = rawSpk
            val st = o.optDouble("start")
            val ed = o.optDouble("end")
            val dur = ed - st
            if (!hasDiarizedLabels && dur > 25.0) continue
            if (dur < MIN_SPEECH_S) continue

            val (clip, clipSec) = sliceSpeakerAudio(pcm, listOf(Span(st, ed, txt)))
            if (clipSec < MIN_SPEECH_S || clip.isEmpty()) continue
            val emb = extractEmbedding(ctx, clip) ?: continue
            extractedUtts.add(
                LocalTurnGroup(
                    key = "u_$i",
                    speechSec = clipSec,
                    embedding = emb,
                    bestSample = txt.take(80),
                ),
            )
        }

        if (extractedUtts.isEmpty()) {
            if (isPhoneDirect) {
                var fIdx = 0
                val localMap = HashMap<String, String>()
                for (i in 0 until segArr.length()) {
                    val o = segArr.getJSONObject(i)
                    val cur = o.optString("speaker")
                    if (cur.startsWith("spk_") || cur.startsWith("pspk_")) {
                        o.put("speaker", localMap.getOrPut(cur) { fIdx++; "other_$fIdx" })
                    }
                }
            }
            root.put("vp_done", true)
            root.put("vp_ver", SCHEMA_VERSION)
            return root
        }

        val clusters = clusterInFile(extractedUtts, IN_FILE_MERGE_SIM)
        val firstSeenStr = Recordings.parseStart(opusFile)?.format(FIRST_SEEN_FMT)
        val keyToSpeaker = matchAndPersistClusters(
            ctx = ctx,
            clusters = clusters,
            firstSeen = firstSeenStr,
            allowPersistPrototypes = hasDiarizedLabels,
        )

        val speakerDbAcc = HashMap<String, MutableList<Float>>()
        val rawTagAssignedSet = HashMap<String, MutableSet<String>>()
        val segAssigned = arrayOfNulls<String>(segArr.length())

        for (i in 0 until segArr.length()) {
            val direct = keyToSpeaker["u_$i"]
            if (direct != null) {
                segAssigned[i] = direct
                segDbs[i]?.let { speakerDbAcc.getOrPut(direct) { ArrayList() }.add(it) }
                rawLabels[i]?.let { rawTagAssignedSet.getOrPut(it) { HashSet() }.add(direct) }
            }
        }

        var fallbackIdx = keyToSpeaker.values.count { it.startsWith("other_") }
        val shortFallback = HashMap<String, String>()

        for (i in 0 until segArr.length()) {
            val o = segArr.getJSONObject(i)
            if (o.optString("text").isBlank()) continue
            var finalSpk = segAssigned[i]
            if (finalSpk == null && hasDiarizedLabels) {
                val rawTag = rawLabels[i]
                val candidatesForTag = rawTag?.let { rawTagAssignedSet[it] }
                if (candidatesForTag != null && candidatesForTag.size == 1) {
                    val candSpk = candidatesForTag.first()
                    val refDbs = speakerDbAcc[candSpk]
                    val curDb = segDbs[i]
                    val dbConsistent = refDbs.isNullOrEmpty() || curDb == null ||
                        abs(curDb - refDbs.average().toFloat()) <= SHORT_INHERIT_MAX_DB_DIFF
                    if (dbConsistent) {
                        finalSpk = candSpk
                    }
                }
                if (finalSpk == null && rawTag != null) {
                    finalSpk = shortFallback.getOrPut(rawTag) {
                        fallbackIdx++
                        "other_$fallbackIdx"
                    }
                }
            }
            if (finalSpk != null) {
                o.put("speaker", finalSpk)
            } else if (isPhoneDirect) {
                o.remove("speaker")
            }
        }

        root.put("vp_done", true)
        root.put("vp_ver", SCHEMA_VERSION)
        Log.i(
            TAG,
            "${opusFile.name}: v$SCHEMA_VERSION 声纹+分贝完成（${clusters.size} 簇 -> ${keyToSpeaker.values.distinct()}），耗时 ${System.currentTimeMillis() - t0}ms",
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
        val root = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return emptyMap()
        if (root.optInt("version", 1) < SCHEMA_VERSION) return emptyMap()
        val items = root.optJSONObject("items") ?: return emptyMap()
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

    private fun readNextSeqLocked(ctx: Context, knownIds: Set<String>): Int {
        val f = localFile(ctx)
        var seq = if (f.exists()) {
            runCatching { JSONObject(f.readText()).optInt("next_seq", 1) }.getOrDefault(1)
        } else 1
        for (id in knownIds) {
            if (id.startsWith("pspk_")) {
                val n = id.removePrefix("pspk_").toIntOrNull() ?: 0
                if (n >= seq) seq = n + 1
            }
        }
        return seq.coerceAtLeast(1)
    }

    internal fun savePrototype(
        ctx: Context,
        id: String,
        centroid: FloatArray,
        weight: Double,
        firstSeen: String?,
        sample: String?,
        nextSeqOverride: Int? = null,
    ) = synchronized(Speakers) {
        val f = localFile(ctx)
        val root = if (f.exists()) {
            runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
                .let { if (it.optInt("version", 1) < SCHEMA_VERSION) JSONObject() else it }
        } else JSONObject()
        root.put("version", SCHEMA_VERSION)
        if (nextSeqOverride != null) {
            root.put("next_seq", max(root.optInt("next_seq", 1), nextSeqOverride))
        } else if (id.startsWith("pspk_")) {
            val num = id.removePrefix("pspk_").toIntOrNull() ?: 0
            root.put("next_seq", max(root.optInt("next_seq", 1), num + 1))
        }
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
        migrateSchemaFast(ctx)
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
        var nextSeq = readNextSeqLocked(ctx, allKnownIds)
        val claimedCanonicalIds = HashSet<String>()
        val mapping = HashMap<String, String>()
        var otherIdx = 0

        fun nextPhoneSpeakerId(): String {
            while (true) {
                val cand = "pspk_%03d".format(nextSeq++)
                if (cand !in allKnownIds) {
                    allKnownIds.add(cand)
                    return cand
                }
            }
        }

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
                    Log.d(TAG, "簇 (${"%.1f".format(cluster.speechSec)}s) 命中声纹 $bestId (cos=${"%.3f".format(bestSim)})")
                    bestId
                }
                allowPersistPrototypes && cluster.speechSec >= NEW_SPEAKER_MIN_S -> {
                    val newId = nextPhoneSpeakerId()
                    savePrototype(ctx, newId, cluster.embedding, cluster.speechSec, firstSeen, cluster.bestSample, nextSeq)
                    Log.i(TAG, "新建手机声纹人物 $newId (${"%.1f".format(cluster.speechSec)}s, bestOldCos=${"%.3f".format(bestSim)})")
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
