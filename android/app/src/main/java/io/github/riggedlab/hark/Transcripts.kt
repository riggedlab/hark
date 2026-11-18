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

    // Whisper 和项目的 MLX 指南都用复读信号识别解码失控。这里是丢弃阈值，留足正常应答余量。
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

    @Synchronized
    fun save(ctx: Context, value: JSONObject, alreadyRejected: Boolean = false) {
        val name = value.getString("name")
        require(Crypto.audioName.matches(name)) { "录音文件名格式错误" }
        val audio = File(RecorderService.chunkDir(ctx), name)
        val existingFile = File(dir(ctx), "$name.json")
        val rejected = alreadyRejected || rejectRunawayAsr(value, Recordings.oggDurationSec(audio))
        if (rejected && existingFile.exists()) return
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

    /** 拒收失控文字，保留空转写和音频元数据，让同步游标或手机转写队列正常前进。 */
    internal fun rejectRunawayAsr(value: JSONObject, audioDurationSec: Double = 0.0): Boolean {
        val text = value.opt("text") as? String ?: ""
        val segments = value.optJSONArray("segments") ?: JSONArray()
        val sentences = (0 until segments.length()).asSequence().mapNotNull {
            segments.optJSONObject(it)?.opt("text") as? String
        }
        val repeated = hasRunawayRepetition(sentences) || hasRunawayRepetition(sentenceBreaks.splitToSequence(text))
        val duration = if (audioDurationSec.isFinite() && audioDurationSec > 0.0) {
            audioDurationSec
        } else {
            value.optDouble("duration_s", 0.0)
        }
        val chars = maxOf(spokenChars(text), sentences.sumOf(::spokenChars))
        // 普通话快语速约 6.4 音节/秒。40 字符/秒给中英混说和格式化数字留余量，
        // 80 字符的下限吸收极短录音的时间误差。使用整段时长，不信任服务返回的逐句时间。
        val tooDense = duration.isFinite() && duration > 0.0 && chars > maxOf(80.0, duration * 40.0)
        if (!repeated && !tooDense) return false

        Log.w(
            "Transcripts",
            "discarded ASR ${value.optString("name")}: repeated=$repeated, tooDense=$tooDense, " +
                "segments=${segments.length()}, chars=$chars, durationSec=$duration",
        )
        value.put("text", "").put("segments", JSONArray()).remove("synthetic_segments")
        return true
    }

    private fun spokenChars(text: String): Long = text.codePoints().filter { Character.isLetterOrDigit(it) }.count()

    private fun hasRunawayRepetition(sentences: Sequence<String>): Boolean {
        val counts = HashMap<String, Int>()
        var previous = ""
        var consecutive = 0
        var total = 0
        var mostRepeated = 0
        for (sentence in sentences) {
            val key = sentence.replace(nonSpeechChars, "").lowercase()
            if (key.isEmpty()) continue
            consecutive = if (key == previous) consecutive + 1 else 1
            if (consecutive >= REPEAT_LIMIT) return true
            previous = key
            total++
            val count = (counts[key] ?: 0) + 1
            counts[key] = count
            mostRepeated = maxOf(mostRepeated, count)
        }
        return mostRepeated >= REPEAT_LIMIT && mostRepeated.toDouble() / total >= 0.8
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
