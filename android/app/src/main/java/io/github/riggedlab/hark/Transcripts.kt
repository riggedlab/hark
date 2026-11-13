package io.github.riggedlab.hark

import android.content.Context
import androidx.compose.runtime.Immutable
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class Segment(
    val start: Double,
    val end: Double,
    val text: String,
    val speaker: String? = null,
    val db: Float? = null,
    val activeSec: Float? = null,
)

@Immutable
data class Transcript(val name: String, val durationSec: Double, val text: String, val segments: List<Segment>)

/**
 * 转写文字：从信箱取回后存在 files/texts/<录音名>.json。
 * 文字不跟着录音一起删除（录音 7 天后删，文字一直保留）。
 */
object Transcripts {
    private val mem = ConcurrentHashMap<String, Transcript>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    fun dir(ctx: Context) = File(ctx.filesDir, "texts").apply { mkdirs() }

    /** 某段录音（文件名 xxx.opus）对应的文字，正负结果均缓存于内存，避免滚动时重复触发磁盘 syscall */
    fun get(ctx: Context, name: String): Transcript? {
        mem[name]?.let { return it }
        if (missing.contains(name)) return null
        val file = File(dir(ctx), "$name.json")
        if (!file.exists()) {
            missing.add(name)
            return null
        }
        val parsed = runCatching { parse(JSONObject(file.readText())) }.getOrNull()
        if (parsed != null) mem[name] = parsed
        return parsed
    }

    fun allNames(ctx: Context): List<String> {
        val names = dir(ctx).list()?.filter { it.endsWith(".json") }?.map { it.removeSuffix(".json") } ?: emptyList()
        if (missing.isNotEmpty()) missing.removeAll(names.toSet())
        return names
    }

    fun save(ctx: Context, value: JSONObject) {
        val name = value.getString("name")
        require(Crypto.audioName.matches(name)) { "录音文件名格式错误" }
        Sync.writeFile(File(dir(ctx), "$name.json"), value.toString())
        mem.remove(name)
        missing.remove(name)
    }

    private fun parse(o: JSONObject): Transcript {
        val segs = o.optJSONArray("segments")
        val list = (0 until (segs?.length() ?: 0)).map {
            val s = segs!!.getJSONObject(it)
            val dbVal = if (s.has("db") && !s.isNull("db")) {
                s.optDouble("db", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
            } else null
            val actVal = if (s.has("act_s") && !s.isNull("act_s")) {
                s.optDouble("act_s", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }?.toFloat()
            } else null
            Segment(
                start = s.optDouble("start"),
                end = s.optDouble("end"),
                text = s.optString("text"),
                speaker = s.optString("speaker").takeIf { it.isNotBlank() },
                db = dbVal,
                activeSec = actVal,
            )
        }
        return Transcript(o.getString("name"), o.optDouble("duration_s", 0.0), o.optString("text"), list)
    }
}
