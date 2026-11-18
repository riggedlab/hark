package io.github.riggedlab.hark

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Immutable
data class SceneTag(
    val label: String,
    val score: Float,
)

@Immutable
data class Segment(
    val start: Double,
    val end: Double,
    val text: String,
    val speaker: String? = null,
    val db: Float? = null,
    val snrDb: Float? = null,
    val activeSec: Float? = null,
    val proximity: String? = null,
    val mood: String? = null,
    val moodScore: Float? = null,
    val vocalTag: String? = null,
)

@Immutable
data class Transcript(
    val name: String,
    val durationSec: Double,
    val text: String,
    val segments: List<Segment>,
    val sceneTags: List<SceneTag> = emptyList(),
)

/**
 * 转写文字：从信箱取回或本机直连转写后存在 files/texts/<录音名>.json。
 * 文字不跟着录音一起删除（录音 7 天后删，文字一直保留）。
 */
object Transcripts {
    private val mem = ConcurrentHashMap<String, Transcript>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val writeEpochs = ConcurrentHashMap<String, AtomicLong>()

    // 保留日常重复应答，超过阈值才折叠。
    private const val REPEAT_LIMIT = 32
    private val nonSpeechChars = Regex("[^\\p{L}\\p{N}]+")
    private val sentenceBreaks = Regex("[。！？.!?；;，,\\r\\n]+")

    private fun epochCounter(name: String): AtomicLong =
        writeEpochs.computeIfAbsent(name) { AtomicLong(0L) }

    fun dir(ctx: Context) = File(ctx.filesDir, "texts").apply { mkdirs() }

    /** 某段录音（文件名 xxx.opus）对应的文字，通过写纪元校验防止并发旧读回写覆盖新缓存 */
    fun get(ctx: Context, name: String): Transcript? {
        mem[name]?.let { return it }
        if (missing.contains(name)) return null
        val epochBefore = epochCounter(name).get()
        val file = File(dir(ctx), "$name.json")
        if (!file.exists()) {
            synchronized(this) {
                if ((epochBefore and 1L) == 0L && epochCounter(name).get() == epochBefore && !file.exists()) {
                    missing.add(name)
                }
            }
            return mem[name]
        }
        val parsed = runCatching { parse(JSONObject(file.readText())) }.getOrNull() ?: return mem[name]
        synchronized(this) {
            if ((epochBefore and 1L) == 0L && epochCounter(name).get() == epochBefore) {
                mem[name] = parsed
                missing.remove(name)
                return parsed
            }
        }
        return mem[name] ?: runCatching { parse(JSONObject(file.readText())) }.getOrNull()
    }

    fun allNames(ctx: Context): List<String> {
        val names = dir(ctx).list()?.filter { it.endsWith(".json") }?.map { it.removeSuffix(".json") } ?: emptyList()
        if (missing.isNotEmpty()) missing.removeAll(names.toSet())
        return names
    }

    fun save(ctx: Context, value: JSONObject) {
        val name = value.getString("name")
        require(Crypto.audioName.matches(name)) { "录音文件名格式错误" }
        val audio = File(RecorderService.chunkDir(ctx), name)
        val rejection = sanitizeAsr(value, Recordings.oggDurationSec(audio))
        if (rejection == null) saveSanitized(ctx, value)
        else Uploader(ctx).markRejectedCloud(name, rejection)
    }

    @Synchronized
    internal fun saveSanitized(ctx: Context, value: JSONObject) {
        val name = value.getString("name")
        require(Crypto.audioName.matches(name)) { "录音文件名格式错误" }
        val existingFile = File(dir(ctx), "$name.json")
        if (!value.has("scene_tags") && existingFile.exists()) {
            runCatching { JSONObject(existingFile.readText()).optJSONArray("scene_tags") }
                .getOrNull()
                ?.let { value.put("scene_tags", it) }
        }
        val serialized = value.toString()
        val ep = epochCounter(name)
        ep.incrementAndGet() // odd = 正在替换文件
        try {
            Sync.writeFile(existingFile, serialized)
            mem.remove(name)
            missing.remove(name)
        } finally {
            ep.incrementAndGet() // even = 替换完成
        }
    }

    /** 折叠重复语句。仅在无法保留可信内容时返回拒收原因。 */
    internal fun sanitizeAsr(value: JSONObject, audioDurationSec: Double = 0.0): String? {
        val text = value.opt("text") as? String ?: ""
        val array = value.optJSONArray("segments") ?: JSONArray()
        val segments = (0 until array.length()).map { array.getJSONObject(it) }
        val segmentTexts = segments.map { it.optString("text") }
        val textFold = foldRepetitions(listOf(text))
        val segmentFold = foldRepetitions(segmentTexts)
        // 部分服务把逐句结果无标点拼成全文，两处的复读边界可能只看得出一处。
        val sameWords = (textFold != null || segmentFold != null) && text.isNotBlank() &&
            speechKey(text) == speechKey(segmentTexts.joinToString(""))
        val cleanText = textFold?.texts?.single()
            ?: if (sameWords && segmentFold != null) segmentFold.texts.joinToString("") else text
        val cleanSegments = segmentFold?.texts
            ?: if (sameWords && segments.size == 1) listOf(cleanText) else segmentTexts
        val duration = audioDurationSec.takeIf { it.isFinite() && it > 0.0 } ?: value.optDouble("duration_s", 0.0)
        val chars = maxOf(spokenChars(cleanText), cleanSegments.sumOf(::spokenChars))
        val onlyUntimedRepetitions = segmentFold?.onlyRepeated == true &&
            (spokenChars(text) == 0L || textFold?.onlyRepeated == true) &&
            segments.none { it.optDouble("end") > it.optDouble("start") }
        // 使用整段真实时长检验折叠后的内容，避免一段长复读连带丢掉正常句。
        val reason = when {
            onlyUntimedRepetitions -> "转写内容只有无有效时间的复读"
            duration.isFinite() && duration > 0.0 && chars > maxOf(80.0, duration * 40.0) ->
                "转写文字密度异常（${chars} 字符 / ${duration} 秒）"
            else -> null
        }
        if (reason != null) {
            Log.w("Transcripts", "discarded ASR ${value.optString("name")}: $reason")
            return reason
        }
        if (cleanText != text) value.put("text", cleanText)
        if (cleanSegments != segmentTexts) {
            val filtered = JSONArray()
            for ((i, segment) in segments.withIndex()) {
                val clean = cleanSegments[i]
                if (clean.isNotBlank()) filtered.put(segment.put("text", clean))
            }
            value.put("segments", filtered)
        }
        return null
    }

    private fun spokenChars(text: String): Long = text.codePoints().filter { Character.isLetterOrDigit(it) }.count()

    private fun speechKey(text: String): String = text.replace(nonSpeechChars, "").lowercase()

    private data class Phrase(val source: Int, val text: String, val key: String)
    private data class Fold(val texts: List<String>, val onlyRepeated: Boolean)

    private fun foldRepetitions(texts: List<String>): Fold? {
        val phrases = ArrayList<Phrase>()
        for ((source, text) in texts.withIndex()) {
            var start = 0
            for (boundary in sentenceBreaks.findAll(text)) {
                val end = boundary.range.last + 1
                val phrase = text.substring(start, end)
                phrases.add(Phrase(source, phrase, speechKey(phrase)))
                start = end
            }
            if (start < text.length) {
                val phrase = text.substring(start)
                phrases.add(Phrase(source, phrase, speechKey(phrase)))
            }
        }
        val spoken = phrases.indices.filter { phrases[it].key.isNotEmpty() }
        val repeatedKeys = HashSet<String>()
        val drop = BooleanArray(phrases.size)
        var first = 0
        while (first < spoken.size) {
            val key = phrases[spoken[first]].key
            var end = first + 1
            while (end < spoken.size && phrases[spoken[end]].key == key) end++
            if (end - first >= REPEAT_LIMIT) {
                repeatedKeys.add(key)
                for (i in first + 1 until end) drop[spoken[i]] = true
            }
            first = end
        }
        val counts = spoken.groupingBy { phrases[it].key }.eachCount()
        val dominant = counts.entries.firstOrNull { it.value >= REPEAT_LIMIT && it.value >= spoken.size * 0.8 }
        if (dominant != null && repeatedKeys.add(dominant.key)) {
            val occurrences = spoken.filter { phrases[it].key == dominant.key }
            for (i in occurrences.drop(1)) drop[i] = true
        }
        if (repeatedKeys.isEmpty()) return null
        val kept = List(texts.size) { StringBuilder() }
        for ((i, phrase) in phrases.withIndex()) {
            if (!drop[i]) kept[phrase.source].append(phrase.text)
        }
        return Fold(kept.map { it.toString() }, spoken.all { phrases[it].key in repeatedKeys })
    }

    /**
     * 乐观无锁重计算 + 双阶段 SeqLock 纪元提交：
     * 重型推理（ONNX/音频解码）100% 在锁外执行，绝不阻塞跨线程实时 save()；若期间发生并发覆盖则基于最新文本重试一次。
     */
    fun computeAndCommit(ctx: Context, name: String, computeOutsideLock: (JSONObject) -> Boolean): Boolean {
        val file = File(dir(ctx), "$name.json")
        repeat(2) {
            val epochBefore: Long
            val rawText: String
            synchronized(this) {
                if (!file.exists()) return false
                epochBefore = epochCounter(name).get()
                rawText = runCatching { file.readText() }.getOrNull() ?: return false
            }
            val workingCopy = runCatching { JSONObject(rawText) }.getOrNull() ?: return false
            if (!computeOutsideLock(workingCopy)) return false
            val serialized = workingCopy.toString()
            synchronized(this) {
                val ep = epochCounter(name)
                if (ep.get() == epochBefore) {
                    if (serialized == rawText) return false
                    ep.incrementAndGet() // odd = 正在替换文件
                    try {
                        Sync.writeFile(file, serialized)
                        mem.remove(name)
                        missing.remove(name)
                    } finally {
                        ep.incrementAndGet() // even = 替换完成
                    }
                    return true
                }
            }
        }
        return false
    }

    private fun parse(o: JSONObject): Transcript {
        val segs = o.optJSONArray("segments")
        val list = (0 until (segs?.length() ?: 0)).map {
            val s = segs!!.getJSONObject(it)
            val dbVal = if (s.has("db") && !s.isNull("db")) {
                s.optDouble("db", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
            } else null
            val snrVal = if (s.has("snr_db") && !s.isNull("snr_db")) {
                s.optDouble("snr_db", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
            } else null
            val actVal = if (s.has("act_s") && !s.isNull("act_s")) {
                s.optDouble("act_s", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }?.toFloat()
            } else null
            val proxVal = s.optString("proximity").takeIf { it == Proximity.NEAR || Proximity.isBackground(it) }
            val hasMood = !Proximity.isBackground(proxVal)
            val moodVal = (s.opt("mood") as? String)?.takeIf { hasMood && it.isNotBlank() }
            val moodScoreVal = if (hasMood && s.has("mood_score") && !s.isNull("mood_score")) {
                s.optDouble("mood_score", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
            } else null
            val vocalVal = (s.opt("vocal_tag") as? String)?.takeIf { hasMood && it.isNotBlank() }
            Segment(
                start = s.optDouble("start"),
                end = s.optDouble("end"),
                text = s.optString("text"),
                speaker = s.optString("speaker").takeIf { it.isNotBlank() },
                db = dbVal,
                snrDb = snrVal,
                activeSec = actVal,
                proximity = proxVal,
                mood = moodVal,
                moodScore = moodScoreVal,
                vocalTag = vocalVal,
            )
        }
        val stArr = o.optJSONArray("scene_tags")
        val scenes = (0 until (stArr?.length() ?: 0)).mapNotNull { idx ->
            val tagObj = stArr!!.optJSONObject(idx) ?: return@mapNotNull null
            val lb = tagObj.optString("label").takeIf { it.isNotBlank() && SceneAndMood.isVisibleSceneLabel(it) }
                ?: return@mapNotNull null
            val sc = tagObj.optDouble("score", Double.NaN).takeIf { !it.isNaN() }?.toFloat() ?: 0.8f
            SceneTag(lb, sc)
        }
        return Transcript(o.getString("name"), o.optDouble("duration_s", 0.0), o.optString("text"), list, scenes)
    }
}
