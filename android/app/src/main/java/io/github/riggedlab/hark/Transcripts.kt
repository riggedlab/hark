package io.github.riggedlab.hark

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class Segment(val start: Double, val end: Double, val text: String, val speaker: String? = null)
data class Transcript(val name: String, val durationSec: Double, val text: String, val segments: List<Segment>)

/**
 * 转写文字：从信箱取回后存在 files/texts/<录音名>.json。
 * 文字不跟着录音一起删除（录音 7 天后删，文字一直保留）。
 */
object Transcripts {
    private val mem = ConcurrentHashMap<String, Transcript>()

    fun dir(ctx: Context) = File(ctx.filesDir, "texts").apply { mkdirs() }

    /** 某段录音（文件名 xxx.opus）对应的文字，没有就返回 null */
    fun get(ctx: Context, name: String): Transcript? = mem[name] ?: File(dir(ctx), "$name.json")
        .takeIf { it.exists() }?.let { runCatching { parse(JSONObject(it.readText())) }.getOrNull() }
        ?.also { mem[name] = it }

    fun allNames(ctx: Context): List<String> =
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }?.map { it.name.removeSuffix(".json") } ?: emptyList()

    fun save(ctx: Context, value: JSONObject) {
        val name = value.getString("name")
        require(Crypto.audioName.matches(name)) { "录音文件名格式错误" }
        Sync.writeFile(File(dir(ctx), "$name.json"), value.toString())
        mem.remove(name)
    }

    private fun parse(o: JSONObject): Transcript {
        val segs = o.optJSONArray("segments")
        val list = (0 until (segs?.length() ?: 0)).map {
            val s = segs!!.getJSONObject(it)
            Segment(s.optDouble("start"), s.optDouble("end"), s.optString("text"),
                s.optString("speaker").takeIf { it.isNotBlank() })
        }
        return Transcript(o.getString("name"), o.optDouble("duration_s", 0.0), o.optString("text"), list)
    }
}
