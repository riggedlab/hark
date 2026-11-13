package io.github.riggedlab.hark

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 100% 本机离线、与第三方云端 ASR 完全解耦的「环境音识别 + 讲话人心情评分」引擎：
 * 1. 环境音与人声副语言：基于 CED-Tiny (AudioSet 527 类 INT8 ONNX, 5.85MB) + 16 类高置信生活场景白名单，
 *    无显著外源噪声时显式标注「室内安静」，确保每一段录音都有准确环境音标签；
 * 2. 讲话人心情与评分：基于 BGE-Small-ZH-v1.5 (INT8 ONNX, 22.9MB) + 口语情绪校准头 (mood_head_zh.json)，
 *    融合同句原声中的笑声/叹气副语言及语速、相对响度突变，输出严格自洽的 mood (positive/neutral/negative) 与 mood_score ([-1, +1])。
 */
internal object SceneAndMood {
    private const val TAG = "SceneAndMood"
    const val SCHEMA_VERSION = 2
    private const val SCENE_SIDECAR_VERSION = 1

    const val CED_ASSET = "ced_tiny_audio_tagging_int8.onnx"
    const val BGE_ASSET = "bge_small_zh_v15_q8.onnx"
    const val VOCAB_ASSET = "bge_small_zh_vocab.txt"
    const val HEAD_ASSET = "mood_head_zh.json"

    private const val SAMPLE_RATE = 16000
    private const val CED_N_FFT = 512
    private const val CED_HALF_FFT = 256
    private const val CED_HOP = 160
    private const val CED_MELS = 64
    private const val CED_TOP_DB = 120.0f
    private const val BGE_DIM = 512
    private const val MAX_SEQ_LEN = 80
    const val NEUTRAL_BAND = 0.18f

    private val env by lazy { OrtEnvironment.getEnvironment() }
    @Volatile private var cedSession: OrtSession? = null
    @Volatile private var bgeSession: OrtSession? = null
    @Volatile private var vocabMap: Map<String, Int>? = null
    @Volatile private var moodHead: MoodHead? = null

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workerRunning = AtomicBoolean(false)
    private val rescanPending = AtomicBoolean(false)
    private val sceneMem = ConcurrentHashMap<String, List<SceneTag>>()

    fun sceneDir(ctx: Context) = File(ctx.filesDir, "scenes").apply { mkdirs() }

    /** 读取某段录音的环境音标签（优先独立 sidecar，兼容转写内嵌字段，不依赖 ASR 成功与否） */
    fun getSceneTags(ctx: Context, name: String): List<SceneTag> {
        sceneMem[name]?.let { return it }
        val sf = File(sceneDir(ctx), "$name.json")
        if (sf.exists()) {
            val parsed = runCatching {
                val root = JSONObject(sf.readText())
                val arr = root.optJSONArray("scene_tags") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val lb = o.optString("label").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val sc = o.optDouble("score", Double.NaN).takeIf { !it.isNaN() }?.toFloat() ?: 0.8f
                    SceneTag(lb, sc)
                }
            }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) {
                sceneMem[name] = parsed
                return parsed
            }
        }
        return Transcripts.get(ctx, name)?.sceneTags ?: emptyList()
    }

    internal fun saveSceneSidecar(ctx: Context, name: String, tags: List<SceneTag>) {
        if (!Crypto.audioName.matches(name) || tags.isEmpty()) return
        val arr = JSONArray()
        for (t in tags) arr.put(JSONObject().put("label", t.label).put("score", t.score.toDouble()))
        val root = JSONObject().put("ver", SCENE_SIDECAR_VERSION).put("scene_tags", arr)
        Sync.writeFile(File(sceneDir(ctx), "$name.json"), root.toString())
        sceneMem[name] = tags
    }

    fun sceneSignature(ctx: Context): String {
        val files = sceneDir(ctx).listFiles() ?: return "0:0"
        var cnt = 0
        var newest = 0L
        for (f in files) {
            if (f.name.endsWith(".json")) {
                cnt++
                if (f.lastModified() > newest) newest = f.lastModified()
            }
        }
        return "$cnt:$newest"
    }

    /**
     * 异步非阻塞调度全量环境音与讲话人心情扫描（在 uploadLock / Sync.lock 之外独立运行）：
     * 1. 所有本地 .opus 录音（即使无人声跳过、断网待传、ASR 失败）均独立生成环境音 sidecar；
     * 2. 所有带本地 .opus 的转写完成完整声学+语义心情与有效分贝补齐（标记 sm_audio_ver）；
     * 3. 仅当原音频已过期清理（!opus.exists()）时走纯文本心情兜底（标记 sm_text_ver），绝不阻塞或永久漏掉有音频记录。
     */
    fun scheduleBackgroundScan(ctx: Context) {
        val appCtx = ctx.applicationContext
        rescanPending.set(true)
        if (!workerRunning.compareAndSet(false, true)) return
        bgScope.launch {
            try {
                while (rescanPending.compareAndSet(true, false)) {
                    runBackgroundScanOnce(appCtx)
                }
            } finally {
                workerRunning.set(false)
                if (rescanPending.get()) {
                    scheduleBackgroundScan(appCtx)
                }
            }
        }
    }

    private suspend fun runBackgroundScanOnce(ctx: Context) {
        val chunkDir = RecorderService.chunkDir(ctx)
        var dirtyBatch = 0

        fun flushUiIfNeeded(force: Boolean = false) {
            if (dirtyBatch >= 4 || (force && dirtyBatch > 0)) {
                dirtyBatch = 0
                AppState.update { it.copy(textVersion = it.textVersion + 1) }
            }
        }

        // Pass 1: 优先处理已有转写的对话录音（让「文字」页秒级出标），再后台补齐其余无人声/待转写 .opus 片段
        val trDir = Transcripts.dir(ctx)
        val opusFiles = chunkDir.listFiles()
            ?.filter { Crypto.audioName.matches(it.name) && it.length() > 0 }
            ?.sortedWith(
                compareByDescending<File> { File(trDir, "${it.name}.json").exists() }
                    .thenByDescending { it.name },
            )
            ?: emptyList()

        for (opus in opusFiles) {
            val name = opus.name
            val sidecarFile = File(sceneDir(ctx), "$name.json")
            val hasSidecar = sidecarFile.exists() &&
                runCatching { JSONObject(sidecarFile.readText()).optInt("ver", 0) >= SCENE_SIDECAR_VERSION }.getOrDefault(false)

            val trFile = File(Transcripts.dir(ctx), "$name.json")
            val trNeedsAudio = trFile.exists() && runCatching {
                val r = JSONObject(trFile.readText())
                r.optInt("sm_audio_ver", 0) < SCHEMA_VERSION ||
                    (r.optJSONArray("segments")?.let { segs ->
                        (0 until segs.length()).any { idx ->
                            val s = segs.getJSONObject(idx)
                            !s.has("db") || !s.has("proximity")
                        }
                    } == true)
            }.getOrDefault(false)

            if (hasSidecar && !trNeedsAudio) continue

            val pcm = runCatching { Vad.decode16k(opus) }.getOrNull()
            if (pcm == null || pcm.isEmpty()) continue

            val tags = if (hasSidecar) getSceneTags(ctx, name) else {
                detectSceneTags(ctx, pcm).also { saveSceneSidecar(ctx, name, it) }
            }

            if (trFile.exists()) {
                val updated = Transcripts.computeAndCommit(ctx, name) { root ->
                    enrichWithPcmAndTags(ctx, pcm, tags, root)
                    true
                }
                if (updated) dirtyBatch++
            } else {
                dirtyBatch++
            }
            flushUiIfNeeded()
            delay(8)
        }

        // Pass 2: 对原音频已过期的历史转写，执行轻量纯文本心情回填（仅写 sm_text_ver，绝不占用 sm_audio_ver）
        val allTrNames = Transcripts.allNames(ctx).sortedDescending()
        for (name in allTrNames) {
            if (File(chunkDir, name).exists()) continue
            val trFile = File(Transcripts.dir(ctx), "$name.json")
            val needsText = runCatching {
                val r = JSONObject(trFile.readText())
                r.optInt("sm_audio_ver", 0) < SCHEMA_VERSION && r.optInt("sm_text_ver", 0) < SCHEMA_VERSION
            }.getOrDefault(false)
            if (!needsText) continue

            val updated = Transcripts.computeAndCommit(ctx, name) { root ->
                enrichTextOnly(ctx, root)
                true
            }
            if (updated) {
                dirtyBatch++
                flushUiIfNeeded()
                delay(4)
            }
        }

        // 清理原音频和文字都已不存在的孤立场景缓存
        val validNames = (opusFiles.map { it.name } + allTrNames).toHashSet()
        sceneDir(ctx).listFiles()?.forEach { f ->
            val base = f.name.removeSuffix(".json")
            if (base !in validNames) {
                f.delete()
                sceneMem.remove(base)
            }
        }

        flushUiIfNeeded(force = true)
    }

    private class MoodHead(
        val hidden: Int,
        val temperature: Float,
        val w1: Array<FloatArray>, // [hidden][512]
        val b1: FloatArray,        // [hidden]
        val w2: Array<FloatArray>, // [3][hidden]
        val b2: FloatArray,        // [3]
    )

    internal data class CategoryRule(
        val label: String,
        val indices: IntArray,
        val threshold: Float,
        val isVocalAction: Boolean = false,
    )

    // 收敛后的 16 类高价值日常环境音与副语言白名单（彻底屏蔽 AudioSet 冷门长尾误报）
    internal val SCENE_RULES = listOf(
        CategoryRule("敲键盘", intArrayOf(384, 386), 0.18f),
        CategoryRule("背景音乐", intArrayOf(137, 267, 27), 0.24f),
        CategoryRule("车流交通", intArrayOf(300, 306, 307, 308, 314, 321, 327), 0.20f),
        CategoryRule("地铁列车", intArrayOf(329, 332, 333, 334), 0.22f),
        CategoryRule("人声嘈杂", intArrayOf(68, 69, 70), 0.22f),
        CategoryRule("餐具用餐", intArrayOf(364, 365, 54), 0.20f),
        CategoryRule("风声", intArrayOf(283, 285), 0.22f),
        CategoryRule("雨声流水", intArrayOf(288, 289, 290, 291, 292, 370), 0.22f),
        CategoryRule("开关门", intArrayOf(354, 355, 357, 358, 359), 0.22f),
        CategoryRule("宠物叫声", intArrayOf(74, 75, 80, 81, 83), 0.24f),
        CategoryRule("鸟鸣", intArrayOf(111, 112), 0.24f),
        CategoryRule("铃声提示", intArrayOf(388, 390, 391, 395, 396), 0.24f),
        CategoryRule("笑声", intArrayOf(16, 17, 18, 19, 20, 21), 0.20f, isVocalAction = true),
        CategoryRule("叹气", intArrayOf(26, 38, 22), 0.20f, isVocalAction = true),
        CategoryRule("掌声欢呼", intArrayOf(63, 66, 67), 0.22f, isVocalAction = true),
        CategoryRule("咳嗽清嗓", intArrayOf(47, 48, 49), 0.22f, isVocalAction = true),
    )

    private val LAUGH_INDICES = intArrayOf(16, 17, 18, 19, 20, 21)
    private val SIGH_INDICES = intArrayOf(26, 38, 22)
    private val APPLAUSE_INDICES = intArrayOf(63, 66, 67)
    private val COUGH_INDICES = intArrayOf(47, 48, 49)

    // 预计算 CED 周期 Hann 窗与 64 通道 HTK 梅尔滤波器组 (0..8000 Hz)
    private val hannPeriodic = FloatArray(CED_N_FFT) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / CED_N_FFT)).toFloat()
    }

    private class SparseMelFilter(val offset: Int, val weights: FloatArray)

    private val cedMelFilters: Array<SparseMelFilter> = run {
        fun hzToMel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
        fun melToHz(m: Double) = 700.0 * (10.0.pow(m / 2595.0) - 1.0)
        val mMin = hzToMel(0.0)
        val mMax = hzToMel(8000.0)
        val mPts = DoubleArray(CED_MELS + 2) { i -> mMin + i * (mMax - mMin) / (CED_MELS + 1) }
        val fPts = DoubleArray(CED_MELS + 2) { i -> melToHz(mPts[i]) }
        val binHz = SAMPLE_RATE.toDouble() / CED_N_FFT

        Array(CED_MELS) { m ->
            val fL = fPts[m]
            val fC = fPts[m + 1]
            val fR = fPts[m + 2]
            var first = -1
            var last = -1
            val full = FloatArray(CED_HALF_FFT + 1)
            for (k in 0..CED_HALF_FFT) {
                val f = k * binHz
                val up = (f - fL) / (fC - fL)
                val dn = (fR - f) / (fR - fC)
                val w = max(0.0, min(up, dn)).toFloat()
                if (w > 0f) {
                    if (first < 0) first = k
                    last = k
                    full[k] = w
                }
            }
            if (first < 0) SparseMelFilter(0, FloatArray(0))
            else SparseMelFilter(first, full.copyOfRange(first, last + 1))
        }
    }

    private fun ced(ctx: Context): OrtSession = cedSession ?: synchronized(this) {
        cedSession ?: run {
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
            }
            val bytes = ctx.assets.open(CED_ASSET).use { it.readBytes() }
            env.createSession(bytes, opts).also { cedSession = it }
        }
    }

    private fun bge(ctx: Context): OrtSession = bgeSession ?: synchronized(this) {
        bgeSession ?: run {
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
            }
            val bytes = ctx.assets.open(BGE_ASSET).use { it.readBytes() }
            env.createSession(bytes, opts).also { bgeSession = it }
        }
    }

    private fun vocab(ctx: Context): Map<String, Int> = vocabMap ?: synchronized(this) {
        vocabMap ?: run {
            val map = HashMap<String, Int>(24000)
            ctx.assets.open(VOCAB_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
                var idx = 0
                for (line in lines) {
                    map[line] = idx++
                }
            }
            map.also { vocabMap = it }
        }
    }

    private fun head(ctx: Context): MoodHead = moodHead ?: synchronized(this) {
        moodHead ?: run {
            val root = JSONObject(ctx.assets.open(HEAD_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })
            val hidden = root.getInt("hidden")
            val temp = root.optDouble("temperature", 1.25).toFloat()
            val w1Json = root.getJSONArray("w1")
            val w1 = Array(hidden) { r ->
                val row = w1Json.getJSONArray(r)
                FloatArray(BGE_DIM) { c -> row.getDouble(c).toFloat() }
            }
            val b1Json = root.getJSONArray("b1")
            val b1 = FloatArray(hidden) { i -> b1Json.getDouble(i).toFloat() }
            val w2Json = root.getJSONArray("w2")
            val w2 = Array(3) { r ->
                val row = w2Json.getJSONArray(r)
                FloatArray(hidden) { c -> row.getDouble(c).toFloat() }
            }
            val b2Json = root.getJSONArray("b2")
            val b2 = FloatArray(3) { i -> b2Json.getDouble(i).toFloat() }
            MoodHead(hidden, temp, w1, b1, w2, b2).also { moodHead = it }
        }
    }

    /** 提取与 CedFeatureExtractor 对齐的 [64 * numFrames] 频带优先对数功率梅尔谱 */
    internal fun extractCedMelBandMajor(
        samples: FloatArray,
        startIdx: Int = 0,
        endIdx: Int = samples.size,
    ): Pair<Int, FloatArray> {
        val len = (endIdx - startIdx).coerceAtLeast(0)
        if (len < CED_N_FFT) return 0 to FloatArray(0)
        val pad = CED_N_FFT / 2
        val numFrames = 1 + len / CED_HOP
        val out = FloatArray(CED_MELS * numFrames)

        val windowed = FloatArray(CED_N_FFT)
        val re = FloatArray(CED_N_FFT)
        val im = FloatArray(CED_N_FFT)
        val power = FloatArray(CED_HALF_FFT + 1)
        var maxDb = -1e9f

        for (t in 0 until numFrames) {
            val base = t * CED_HOP - pad
            for (i in 0 until CED_N_FFT) {
                var rel = base + i
                while (rel < 0 || rel >= len) {
                    rel = if (rel < 0) -rel else 2 * len - 2 - rel
                }
                windowed[i] = samples[startIdx + rel] * hannPeriodic[i]
            }
            KaldiFbank.fftPowerSpectrum257(windowed, re, im, power)
            for (m in 0 until CED_MELS) {
                val mf = cedMelFilters[m]
                val w = mf.weights
                val off = mf.offset
                var e = 0f
                for (j in w.indices) {
                    e += w[j] * power[off + j]
                }
                val db = (10.0 * log10(max(e.toDouble(), 1e-10))).toFloat()
                out[m * numFrames + t] = db
                if (db > maxDb) maxDb = db
            }
        }
        val floorDb = maxDb - CED_TOP_DB
        for (i in out.indices) {
            if (out[i] < floorDb) out[i] = floorDb
        }
        return numFrames to out
    }

    /** 对任意音频片段（>= 0.5s）运行 CED-Tiny 推理，返回 527 维 AudioSet 概率向量 */
    internal fun inferCedProbs(
        ctx: Context,
        pcm16k: FloatArray,
        startIdx: Int = 0,
        endIdx: Int = pcm16k.size,
    ): FloatArray? {
        val len = endIdx - startIdx
        if (len < SAMPLE_RATE / 2) return null
        val (numFrames, feats) = extractCedMelBandMajor(pcm16k, startIdx, endIdx)
        if (numFrames <= 0) return null
        val ort = ced(ctx)
        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(feats),
            longArrayOf(1L, CED_MELS.toLong(), numFrames.toLong()),
        ).use { input ->
            ort.run(mapOf("feats" to input)).use { out ->
                @Suppress("UNCHECKED_CAST")
                (out[0].value as Array<FloatArray>)[0]
            }
        }
    }

    private fun maxProbOf(probs: FloatArray, indices: IntArray): Float {
        var best = 0f
        for (idx in indices) {
            if (idx in probs.indices && probs[idx] > best) best = probs[idx]
        }
        return best
    }

    /** 从整段 16kHz 音频提取环境音标签（保证每段录音都有至少 1 个准确环境标签） */
    fun detectSceneTags(ctx: Context, pcm16k: FloatArray): List<SceneTag> {
        if (pcm16k.size < SAMPLE_RATE / 2) {
            return listOf(SceneTag("安静无声", 0.95f))
        }
        val winLen = min(pcm16k.size, SAMPLE_RATE * 5) // 5.0s 窗口
        val maxWindows = 12
        val starts = if (pcm16k.size <= winLen) {
            listOf(0)
        } else {
            val rawCount = ((pcm16k.size - winLen) / (SAMPLE_RATE * 4) + 1).coerceIn(1, maxWindows)
            if (rawCount == 1) listOf(0)
            else {
                val span = pcm16k.size - winLen
                List(rawCount) { i -> (i.toLong() * span / (rawCount - 1)).toInt() }
            }
        }

        val activeProbs = ArrayList<FloatArray>(starts.size)
        for (st in starts) {
            val ed = (st + winLen).coerceAtMost(pcm16k.size)
            var sumSq = 0.0
            var k = st
            while (k < ed) {
                val v = pcm16k[k].toDouble()
                sumSq += v * v
                k += 4
            }
            val rms = sqrt(sumSq / max(1, (ed - st) / 4))
            if (rms < 1.2e-4) continue // 跳过硬件静音补帧区间
            inferCedProbs(ctx, pcm16k, st, ed)?.let { activeProbs.add(it) }
        }

        if (activeProbs.isEmpty()) {
            return listOf(SceneTag("安静无声", 0.95f))
        }

        val detected = ArrayList<SceneTag>()
        var maxExternalNoise = 0f

        for (rule in SCENE_RULES) {
            val winScores = FloatArray(activeProbs.size) { w -> maxProbOf(activeProbs[w], rule.indices) }
            winScores.sortDescending()
            val peak = winScores[0]
            val agg = if (winScores.size >= 2) 0.65f * winScores[0] + 0.35f * winScores[1] else peak
            if (!rule.isVocalAction && agg > maxExternalNoise) maxExternalNoise = agg
            val eff = if (rule.isVocalAction) peak else agg
            if (eff >= rule.threshold) {
                val rounded = ((eff.coerceIn(0.01f, 0.99f) * 100f).roundToInt() / 100f)
                detected.add(SceneTag(rule.label, rounded))
            }
        }

        val quietScore = ((1.0f - maxExternalNoise).coerceIn(0.68f, 0.96f) * 100f).roundToInt() / 100f
        if (detected.isEmpty()) {
            return listOf(SceneTag("室内安静", quietScore))
        }
        val hasExternalScene = detected.any { tag ->
            SCENE_RULES.firstOrNull { it.label == tag.label }?.isVocalAction == false
        }
        if (!hasExternalScene && maxExternalNoise < 0.16f) {
            detected.add(SceneTag("室内安静", quietScore))
        }
        return detected.sortedByDescending { it.score }.take(3)
    }

    /** 与训练端 100% 对齐的纯 Kotlin 中文/英文混合 WordPiece 分词器 */
    internal fun tokenizeZh(ctx: Context, text: String, maxLen: Int = MAX_SEQ_LEN): IntArray {
        val v = vocab(ctx)
        val unk = v["[UNK]"] ?: 100
        val cls = v["[CLS]"] ?: 101
        val sep = v["[SEP]"] ?: 102

        val ids = ArrayList<Int>(min(maxLen, text.length + 4))
        ids.add(cls)
        val asciiBuf = StringBuilder()

        fun flushAscii() {
            if (asciiBuf.isEmpty()) return
            val w = asciiBuf.toString().lowercase()
            asciiBuf.setLength(0)
            var start = 0
            val sub = ArrayList<Int>(4)
            while (start < w.length) {
                var end = w.length
                var matched: Int? = null
                while (start < end) {
                    val piece = if (start == 0) w.substring(start, end) else "##${w.substring(start, end)}"
                    val id = v[piece]
                    if (id != null) {
                        matched = id
                        break
                    }
                    end--
                }
                if (matched == null) {
                    sub.clear()
                    sub.add(unk)
                    break
                }
                sub.add(matched)
                start = end
            }
            for (id in sub) {
                if (ids.size >= maxLen - 1) break
                ids.add(id)
            }
        }

        for (ch in text) {
            if (ids.size >= maxLen - 1) break
            val cp = ch.code
            val isCjkOrPunct = (cp in 0x4E00..0x9FFF) ||
                (cp in 0x3400..0x4DBF) ||
                (cp in 0x3000..0x303F) ||
                (cp in 0xFF00..0xFFEF) ||
                !ch.isLetterOrDigit()
            when {
                ch.isWhitespace() -> flushAscii()
                isCjkOrPunct -> {
                    flushAscii()
                    if (ids.size < maxLen - 1) {
                        ids.add(v[ch.toString()] ?: unk)
                    }
                }
                else -> asciiBuf.append(ch)
            }
        }
        flushAscii()
        ids.add(sep)
        return ids.toIntArray()
    }

    /** 提取中文句子的 BGE-Small-ZH 512 维归一化语义向量并输出校准后的 (pNeg, pNeu, pPos) */
    internal fun predictSemanticMoodProbs(ctx: Context, text: String): FloatArray {
        val clean = text.trim()
        if (clean.isEmpty()) return floatArrayOf(0.05f, 0.90f, 0.05f)
        val tokenIds = tokenizeZh(ctx, clean)
        val len = tokenIds.size
        val idsLong = LongArray(len) { i -> tokenIds[i].toLong() }
        val maskLong = LongArray(len) { 1L }
        val typeLong = LongArray(len) { 0L }
        val shape = longArrayOf(1L, len.toLong())

        val clsVec = OnnxTensor.createTensor(env, LongBuffer.wrap(idsLong), shape).use { tIds ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(maskLong), shape).use { tMask ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(typeLong), shape).use { tType ->
                    bge(ctx).run(
                        mapOf(
                            "input_ids" to tIds,
                            "attention_mask" to tMask,
                            "token_type_ids" to tType,
                        ),
                    ).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        val seqOut = (out[0].value as Array<Array<FloatArray>>)[0]
                        Voiceprint.l2Normalize(seqOut[0])
                    }
                }
            }
        }

        val h = head(ctx)
        val hiddenAct = FloatArray(h.hidden)
        for (r in 0 until h.hidden) {
            var sum = h.b1[r]
            val row = h.w1[r]
            for (c in 0 until BGE_DIM) sum += row[c] * clsVec[c]
            hiddenAct[r] = if (sum > 0f) sum else 0f
        }
        val logits = FloatArray(3)
        var maxLogit = -1e9f
        for (k in 0 until 3) {
            var sum = h.b2[k]
            val row = h.w2[k]
            for (c in 0 until h.hidden) sum += row[c] * hiddenAct[c]
            val scaled = sum / h.temperature
            logits[k] = scaled
            if (scaled > maxLogit) maxLogit = scaled
        }
        var expSum = 0.0
        val exps = DoubleArray(3)
        for (k in 0 until 3) {
            val e = exp((logits[k] - maxLogit).toDouble())
            exps[k] = e
            expSum += e
        }
        return FloatArray(3) { k -> (exps[k] / expSum).toFloat() }
    }

    internal data class UtteranceMoodResult(
        val mood: String,       // "positive" | "neutral" | "negative"
        val score: Float,       // [-0.99, +0.99]
        val vocalTag: String?,  // "笑声" | "叹气" | "掌声" | "咳嗽" | null
    )

    /** 融合文本语义概率与同句声学线索（笑声/叹气、语速、相对响度突变），保证标签与分值 100% 自洽 */
    internal fun fuseUtteranceMood(
        semProbs: FloatArray,
        laughProb: Float = 0f,
        sighProb: Float = 0f,
        applauseProb: Float = 0f,
        coughProb: Float = 0f,
        charsPerActiveSec: Float? = null,
        deltaDbFromSpeakerMean: Float? = null,
    ): UtteranceMoodResult {
        val pNeg = semProbs[0]
        val pNeu = semProbs[1]
        val pPos = semProbs[2]

        var acousticShift = 0f
        if (laughProb >= 0.20f) acousticShift += 0.34f * laughProb
        if (applauseProb >= 0.22f) acousticShift += 0.24f * applauseProb
        if (sighProb >= 0.20f) acousticShift -= 0.34f * sighProb

        val vocalTag = listOf(
            "笑声" to laughProb,
            "叹气" to sighProb,
            "掌声" to applauseProb,
            "咳嗽" to coughProb,
        ).filter { it.second >= 0.24f }.maxByOrNull { it.second }?.first

        var arousalScale = 1.0f
        if (charsPerActiveSec != null && deltaDbFromSpeakerMean != null) {
            if (charsPerActiveSec >= 5.8f && deltaDbFromSpeakerMean >= 3.0f) {
                arousalScale = 1.15f
            } else if (charsPerActiveSec <= 2.6f && deltaDbFromSpeakerMean <= -3.5f && (pNeg - pPos) > 0.10f) {
                acousticShift -= 0.08f
            }
        }

        val rawValence = (((pPos - pNeg) + acousticShift) * arousalScale).coerceIn(-0.99f, 0.99f)
        val (mood, finalScore) = when {
            pNeu >= 0.58f && abs(acousticShift) < 0.08f -> {
                "neutral" to (rawValence * 0.45f).coerceIn(-0.17f, 0.17f)
            }
            rawValence >= NEUTRAL_BAND -> {
                "positive" to rawValence.coerceIn(NEUTRAL_BAND, 0.99f)
            }
            rawValence <= -NEUTRAL_BAND -> {
                "negative" to rawValence.coerceIn(-0.99f, -NEUTRAL_BAND)
            }
            else -> {
                "neutral" to rawValence.coerceIn(-0.17f, 0.17f)
            }
        }
        val rounded = ((finalScore * 100f).roundToInt() / 100f)
        return UtteranceMoodResult(mood = mood, score = rounded, vocalTag = vocalTag)
    }

    /**
     * 使用已解码的 16kHz PCM 一次性补齐整段录音的 scene_tags 与每句 mood / mood_score / vocal_tag（零额外解码）。
     */
    fun enrichWithPcm(ctx: Context, pcm16k: FloatArray, root: JSONObject): JSONObject = runCatching {
        val tags = detectSceneTags(ctx, pcm16k)
        val name = root.optString("name")
        if (Crypto.audioName.matches(name)) saveSceneSidecar(ctx, name, tags)
        enrichWithPcmAndTags(ctx, pcm16k, tags, root)
    }.onFailure { e ->
        Log.w(TAG, "enrichWithPcm skipped: ${e.message}")
    }.getOrDefault(root)

    private fun enrichWithPcmAndTags(
        ctx: Context,
        pcm16k: FloatArray,
        sceneTags: List<SceneTag>,
        root: JSONObject,
    ): JSONObject {
        val t0 = System.currentTimeMillis()
        val sceneArr = JSONArray()
        for (tag in sceneTags) {
            sceneArr.put(JSONObject().put("label", tag.label).put("score", tag.score.toDouble()))
        }
        root.put("scene_tags", sceneArr)
        enrichSegmentsMoodInternal(ctx, pcm16k, root)
        root.put("sm_audio_ver", SCHEMA_VERSION)
        root.put("sm_text_ver", SCHEMA_VERSION)
        Log.i(
            TAG,
            "${root.optString("name")}: 环境音=${sceneTags.map { "${it.label}(${it.score})" }} + 心情完成，耗时 ${System.currentTimeMillis() - t0}ms",
        )
        return root
    }

    /**
     * 针对 7 天前原音频已过期清理的历史转写：仅基于文本补齐逐句 mood 与 mood_score（仅写 sm_text_ver，不冒充音频已完成）。
     */
    fun enrichTextOnly(ctx: Context, root: JSONObject): JSONObject = runCatching {
        enrichSegmentsMoodInternal(ctx, null, root)
        root.put("sm_text_ver", SCHEMA_VERSION)
        root
    }.onFailure { e ->
        Log.w(TAG, "enrichTextOnly skipped: ${e.message}")
    }.getOrDefault(root)

    private fun enrichSegmentsMoodInternal(ctx: Context, pcm16k: FloatArray?, root: JSONObject) {
        val segArr = root.optJSONArray("segments") ?: return
        val nSegs = segArr.length()
        if (nSegs == 0) return

        val proxProfile = if (pcm16k != null && pcm16k.isNotEmpty()) Proximity.buildProfile(pcm16k) else null
        val roster = if (proxProfile != null) runCatching { Speakers.all(ctx) }.getOrDefault(emptyMap()) else emptyMap()
        val acoustics = arrayOfNulls<Proximity.SegmentAcoustic>(nSegs)
        val starts = DoubleArray(nSegs)
        val ends = DoubleArray(nSegs)
        val spks = arrayOfNulls<String>(nSegs)

        for (i in 0 until nSegs) {
            val o = segArr.getJSONObject(i)
            val st = o.optDouble("start", 0.0)
            val ed = o.optDouble("end", 0.0)
            starts[i] = st
            ends[i] = ed
            spks[i] = o.optString("speaker").takeIf { it.isNotBlank() }
            if (proxProfile != null) {
                val ac = proxProfile.measureSegment(st, ed)
                if (ac != null) {
                    acoustics[i] = ac
                    o.put("db", (ac.activeDb * 10f).roundToInt() / 10.0)
                    o.put("act_s", (ac.activeSec * 100f).roundToInt() / 100.0)
                    o.put("snr_db", (ac.snrDb * 10f).roundToInt() / 10.0)
                }
            }
        }
        val (chunkMedHf, chunkP90Db) = Proximity.chunkReferenceStats(acoustics.toList())
        val proximities = arrayOfNulls<String>(nSegs)

        // 统计各说话人本段内的平均分贝，用于判断单句相对音量突增/突降
        val spkDbLists = HashMap<String, MutableList<Float>>()
        for (i in 0 until nSegs) {
            val o = segArr.getJSONObject(i)
            val spk = o.optString("speaker").ifBlank { "_anon" }
            if (o.has("db") && !o.isNull("db")) {
                val db = o.optDouble("db", Double.NaN)
                if (!db.isNaN()) spkDbLists.getOrPut(spk) { ArrayList() }.add(db.toFloat())
            }
        }
        val spkMeanDb = spkDbLists.mapValues { (_, v) -> v.average().toFloat() }

        for (i in 0 until nSegs) {
            val o = segArr.getJSONObject(i)
            val txt = o.optString("text").trim()
            if (txt.isEmpty()) continue

            val semProbs = predictSemanticMoodProbs(ctx, txt)

            var laughP = 0f
            var sighP = 0f
            var applauseP = 0f
            var coughP = 0f
            var cProbs: FloatArray? = null

            val st = starts[i]
            val ed = ends[i]
            if (pcm16k != null && ed - st >= 0.7) {
                val padS = ((st - 0.15).coerceAtLeast(0.0) * SAMPLE_RATE).toInt().coerceIn(0, pcm16k.size)
                val maxClipEnd = min(ed + 0.25, st + 5.0)
                val padE = (maxClipEnd * SAMPLE_RATE).toInt().coerceIn(padS, pcm16k.size)
                if (padE - padS >= SAMPLE_RATE / 2) {
                    cProbs = inferCedProbs(ctx, pcm16k, padS, padE)
                    if (cProbs != null) {
                        laughP = maxProbOf(cProbs, LAUGH_INDICES)
                        sighP = maxProbOf(cProbs, SIGH_INDICES)
                        applauseP = maxProbOf(cProbs, APPLAUSE_INDICES)
                        coughP = maxProbOf(cProbs, COUGH_INDICES)
                    }
                }
            }

            acoustics[i]?.let { ac ->
                proximities[i] = Proximity.classifySegment(
                    acoustic = ac,
                    chunkMedianHfLfDb = chunkMedHf,
                    chunkP90Db = chunkP90Db,
                    cedProbs = cProbs,
                    isProtectedSpeaker = Proximity.isProtectedSpeakerId(spks[i], roster),
                )
            }

            val actS = o.optDouble("act_s", ed - st).takeIf { it > 0.05 }?.toFloat()
            val cjkLen = txt.count { it.isLetterOrDigit() || it.code >= 0x4E00 }
            val charsPerSec = if (actS != null && cjkLen >= 3) cjkLen / actS else null
            val spkKey = o.optString("speaker").ifBlank { "_anon" }
            val curDb = if (o.has("db") && !o.isNull("db")) o.optDouble("db", Double.NaN).takeIf { !it.isNaN() }?.toFloat() else null
            val refDb = spkMeanDb[spkKey]
            val deltaDb = if (curDb != null && refDb != null && (spkDbLists[spkKey]?.size ?: 0) >= 2) curDb - refDb else null

            val res = fuseUtteranceMood(
                semProbs = semProbs,
                laughProb = laughP,
                sighProb = sighP,
                applauseProb = applauseP,
                coughProb = coughP,
                charsPerActiveSec = charsPerSec,
                deltaDbFromSpeakerMean = deltaDb,
            )
            o.put("mood", res.mood)
            o.put("mood_score", res.score.toDouble())
            if (res.vocalTag != null) {
                o.put("vocal_tag", res.vocalTag)
            } else {
                o.remove("vocal_tag")
            }
        }

        if (proxProfile != null) {
            Proximity.smoothShortSegments(starts, ends, spks, acoustics, proximities)
            for (i in 0 until nSegs) {
                proximities[i]?.let { segArr.getJSONObject(i).put("proximity", it) }
            }
        }
    }
}
