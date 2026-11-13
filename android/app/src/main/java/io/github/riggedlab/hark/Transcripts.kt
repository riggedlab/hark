package io.github.riggedlab.hark

import android.content.Context
import androidx.compose.runtime.Immutable
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
    fun save(ctx: Context, value: JSONObject) {
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
            val proxVal = s.optString("proximity").takeIf { it == "near" || it == "far" || it == "media" }
            val moodVal = s.optString("mood").takeIf { it.isNotBlank() }
            val moodScoreVal = if (s.has("mood_score") && !s.isNull("mood_score")) {
                s.optDouble("mood_score", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
            } else null
            val vocalVal = s.optString("vocal_tag").takeIf { it.isNotBlank() }
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
            val lb = tagObj.optString("label").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val sc = tagObj.optDouble("score", Double.NaN).takeIf { !it.isNaN() }?.toFloat() ?: 0.8f
            SceneTag(lb, sc)
        }
        return Transcript(o.getString("name"), o.optDouble("duration_s", 0.0), o.optString("text"), list, scenes)
    }
}
