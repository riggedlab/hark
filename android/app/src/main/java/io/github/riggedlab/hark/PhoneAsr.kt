package io.github.riggedlab.hark

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID
import kotlin.math.max
import kotlin.math.round

enum class AsrFailureKind { AUTH_OR_CONFIG, BAD_AUDIO, TRANSIENT }

class PhoneAsrException(val kind: AsrFailureKind, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

data class AsrProbeReport(
    val ok: Boolean,
    val latencyMs: Long,
    val model: String = "",
    val text: String = "",
    val speakerCount: Int = 0,
    val error: String? = null,
)

/**
 * 手机本机直连 ASR 客户端：支持豆包语音大模型极速版、OpenAI 兼容接口与自建 Hark HTTP。
 * 输出格式与云端回传的 texts/<name>.json 完全一致，单段说话人统一映射为局部 other_1 / other_2，
 * 切回云端信箱模式时与电脑端声纹名册互不污染。
 */
object PhoneAsr {

    fun probe(ctx: Context, rawCfg: AsrConfig): AsrProbeReport {
        val err = rawCfg.validationError()
        if (err != null) return AsrProbeReport(ok = false, latencyMs = 0, error = err)
        val opus = runCatching { ctx.assets.open("probe.opus").use { it.readBytes() } }.getOrElse {
            return AsrProbeReport(ok = false, latencyMs = 0, error = "读取测试音频失败：${it.message}")
        }
        return probeBytes(rawCfg, opus)
    }

    internal fun probeBytes(rawCfg: AsrConfig, opus: ByteArray): AsrProbeReport {
        val err = rawCfg.validationError()
        if (err != null) return AsrProbeReport(ok = false, latencyMs = 0, error = err)
        val t0 = System.nanoTime()
        return try {
            val res = transcribe(rawCfg.normalized(), "20260101-000000.opus", opus, timeoutMs = 30_000)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val segs = res.optJSONArray("segments") ?: JSONArray()
            val speakers = HashSet<String>()
            for (i in 0 until segs.length()) {
                val sp = segs.getJSONObject(i).optString("speaker")
                if (sp.isNotBlank()) speakers.add(sp)
            }
            AsrProbeReport(
                ok = true,
                latencyMs = ms,
                model = res.optString("model"),
                text = res.optString("text"),
                speakerCount = speakers.size,
            )
        } catch (e: Exception) {
            val ms = (System.nanoTime() - t0) / 1_000_000
            AsrProbeReport(ok = false, latencyMs = ms, error = e.message ?: "请求失败")
        }
    }

    fun transcribe(
        rawCfg: AsrConfig,
        name: String,
        opus: ByteArray,
        timeoutMs: Int = 180_000,
    ): JSONObject {
        val cfg = rawCfg.normalized()
        cfg.validationError()?.let { throw IllegalArgumentException(it) }
        return when (cfg.provider) {
            AsrProvider.VOLCENGINE -> transcribeVolcengine(cfg, name, opus, timeoutMs)
            AsrProvider.OPENAI -> transcribeOpenAI(cfg, name, opus, timeoutMs)
            AsrProvider.HARK_HTTP -> transcribeHarkHttp(cfg, name, opus, timeoutMs)
        }
    }

    internal data class RawSeg(val start: Double, val end: Double, val text: String, val speaker: String? = null)

    internal fun buildTranscriptJson(
        name: String,
        durationS: Double,
        fullText: String,
        engine: String,
        model: String,
        rawSegs: List<RawSeg>,
    ): JSONObject {
        val speakerMap = LinkedHashMap<String, String>()
        for (s in rawSegs) {
            val raw = s.speaker?.trim().orEmpty()
            if (raw.isNotEmpty() && s.text.isNotBlank() && raw !in speakerMap) {
                speakerMap[raw] = if (raw.startsWith("other_")) raw else "other_${speakerMap.size + 1}"
            }
        }
        val arr = JSONArray()
        for (s in rawSegs) {
            val txt = s.text.trim()
            if (txt.isEmpty()) continue
            val obj = JSONObject()
                .put("start", round2(s.start))
                .put("end", round2(max(s.start, s.end)))
                .put("text", txt)
            val mapped = s.speaker?.trim()?.let { speakerMap[it] }
            if (!mapped.isNullOrEmpty()) obj.put("speaker", mapped)
            arr.put(obj)
        }
        val finalText = fullText.trim().ifEmpty {
            buildString {
                for (i in 0 until arr.length()) append(arr.getJSONObject(i).optString("text"))
            }
        }
        var syntheticSegments = false
        if (arr.length() == 0 && finalText.isNotEmpty()) {
            syntheticSegments = true
            arr.put(JSONObject().put("start", 0.0).put("end", round2(durationS)).put("text", finalText))
        }
        return JSONObject()
            .put("name", name)
            .put("duration_s", round2(durationS))
            .put("text", finalText)
            .put("engine", engine)
            .put("model", model)
            .put("segments", arr)
            .apply { if (syntheticSegments) put("synthetic_segments", true) }
    }

    // ─────────────────── 1. 火山引擎 · 豆包语音大模型极速版 ───────────────────

    private fun transcribeVolcengine(cfg: AsrConfig, name: String, opus: ByteArray, timeoutMs: Int): JSONObject {
        val b64Audio = Base64.getEncoder().encodeToString(opus)
        val localDur = oggDurationSec(opus)

        fun sendOnce(enableSpeaker: Boolean): Triple<Int, Map<String, String>, String> {
            val reqObj = JSONObject()
                .put("model_name", "bigmodel")
                .put("enable_itn", true)
                .put("enable_punc", true)
                .put("enable_ddc", true)
                .put("show_utterances", true)
            if (enableSpeaker) reqObj.put("enable_speaker_info", true)
            val payload = JSONObject()
                .put("user", JSONObject().put("uid", cfg.appId.ifBlank { "hark" }))
                .put("audio", JSONObject().put("data", b64Audio).put("format", "ogg").put("codec", "opus"))
                .put("request", reqObj)
                .toString()
                .toByteArray(Charsets.UTF_8)

            val conn = (URL(cfg.baseUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = timeoutMs
                doOutput = true
                setFixedLengthStreamingMode(payload.size)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Api-Resource-Id", cfg.model)
                setRequestProperty("X-Api-Request-Id", UUID.randomUUID().toString())
                setRequestProperty("X-Api-Sequence", "-1")
                if (cfg.appId.isNotBlank()) {
                    setRequestProperty("X-Api-App-Key", cfg.appId)
                    setRequestProperty("X-Api-Access-Key", cfg.apiKey)
                } else {
                    setRequestProperty("X-Api-Key", cfg.apiKey)
                }
            }
            return try {
                conn.outputStream.use { it.write(payload) }
                val code = conn.responseCode
                val headers = conn.headerFields.entries
                    .filter { it.key != null && !it.value.isNullOrEmpty() }
                    .associate { it.key.lowercase() to it.value.first() }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                Triple(code, headers, body)
            } finally {
                conn.disconnect()
            }
        }

        var enableSpeaker = true
        val backoffs = longArrayOf(800L, 2_000L)
        var httpCode = 0
        var headers = emptyMap<String, String>()
        var raw = ""
        var lastErr: Exception? = null

        for (attempt in 0..backoffs.size) {
            try {
                val res = sendOnce(enableSpeaker)
                httpCode = res.first
                headers = res.second
                raw = res.third
                lastErr = null
                val apiCode = headers["x-api-status-code"].orEmpty().trim()
                if (apiCode == "45000001" && enableSpeaker) {
                    enableSpeaker = false
                    continue
                }
                val retryable = httpCode == 429 || httpCode >= 500 || apiCode == "55000031" || apiCode.startsWith("550")
                if (!retryable || attempt >= backoffs.size) break
            } catch (e: Exception) {
                lastErr = e
                if (attempt >= backoffs.size) throw e
            }
            Thread.sleep(backoffs[attempt])
        }
        lastErr?.let { throw it }

        val apiCode = headers["x-api-status-code"].orEmpty().trim()
        val apiMsg = headers["x-api-message"].orEmpty().trim()
        val logId = headers["x-tt-logid"].orEmpty().trim()

        if (httpCode == 401 || httpCode == 403 || httpCode == 404) {
            throw PhoneAsrException(
                AsrFailureKind.AUTH_OR_CONFIG,
                "豆包语音鉴权或地址失败 (HTTP $httpCode $apiMsg)：请确认填写的是「豆包语音控制台」API Key（与火山方舟 Key 不通用），且已开通 ${cfg.model}",
            )
        }
        if (httpCode !in 200..299) {
            val kind = when {
                httpCode in listOf(413, 415) -> AsrFailureKind.BAD_AUDIO
                httpCode in listOf(400, 422) -> classifyClientError(raw)
                else -> AsrFailureKind.TRANSIENT
            }
            throw PhoneAsrException(kind, "豆包语音返回 HTTP $httpCode ($apiCode $apiMsg): ${raw.take(140)}")
        }
        if (apiCode == "20000003") {
            return buildTranscriptJson(name, localDur, "", AsrProvider.VOLCENGINE.id, cfg.model, emptyList())
        }
        if (apiCode.isNotEmpty() && apiCode != "20000000") {
            val kind = when {
                apiCode in setOf("45000002", "45000006", "45000151") -> AsrFailureKind.BAD_AUDIO
                apiCode.startsWith("450") -> AsrFailureKind.AUTH_OR_CONFIG
                else -> AsrFailureKind.TRANSIENT
            }
            throw PhoneAsrException(kind, "豆包语音错误 $apiCode ($apiMsg, logid=$logId)")
        }

        val root = JSONObject(raw)
        val durMs = root.optJSONObject("audio_info")?.optLong("duration", 0L) ?: 0L
        val durS = if (durMs > 0) durMs / 1000.0 else localDur
        val resultObj = root.optJSONObject("result") ?: JSONObject()
        val utterances = resultObj.optJSONArray("utterances") ?: JSONArray()
        val segs = ArrayList<RawSeg>(utterances.length())
        for (i in 0 until utterances.length()) {
            val u = utterances.getJSONObject(i)
            if (u.has("definite") && !u.optBoolean("definite", true)) continue
            val txt = u.optString("text").trim()
            if (txt.isEmpty()) continue
            val spk = extractVolcSpeaker(u)
            segs.add(RawSeg(u.optLong("start_time", 0L) / 1000.0, u.optLong("end_time", 0L) / 1000.0, txt, spk))
        }
        return buildTranscriptJson(name, durS, resultObj.optString("text"), AsrProvider.VOLCENGINE.id, cfg.model, segs)
    }

    private fun extractVolcSpeaker(u: JSONObject): String? {
        for (k in listOf("speaker_id", "speaker")) {
            val v = u.opt(k)?.takeUnless { it == JSONObject.NULL }?.toString()?.trim()
            if (!v.isNullOrEmpty()) return v
        }
        val add = u.optJSONObject("additions") ?: return null
        for (k in listOf("speaker_id", "speaker")) {
            val v = add.opt(k)?.takeUnless { it == JSONObject.NULL }?.toString()?.trim()
            if (!v.isNullOrEmpty()) return v
        }
        return null
    }

    // ─────────────────── 2. OpenAI 兼容接口 (/v1/audio/transcriptions) ───────────────────

    private fun transcribeOpenAI(cfg: AsrConfig, name: String, opus: ByteArray, timeoutMs: Int): JSONObject {
        val endpoint = cfg.baseUrl.trimEnd('/') + "/audio/transcriptions"
        val localDur = oggDurationSec(opus)
        // OpenAI / Groq / Whisper 按扩展名校验容器，Ogg Opus 容器必须以 .ogg 后缀上传（与桌面端 internal/asr/asr.go 一致）
        val uploadName = name.removeSuffix(".opus") + ".ogg"

        fun postFormat(respFmt: String): Pair<Int, String> {
            val fields = linkedMapOf("model" to cfg.model, "response_format" to respFmt)
            val (body, contentType) = buildMultipart(fields, "file", uploadName, "audio/ogg", opus)
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = timeoutMs
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Content-Type", contentType)
                if (cfg.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            }
            return try {
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val s = if (code in 200..299) conn.inputStream else conn.errorStream
                code to (s?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
            } finally {
                conn.disconnect()
            }
        }

        var (code, raw) = postFormat("verbose_json")
        if (code == 400 && raw.lowercase().contains("response_format")) {
            val fallback = postFormat("json")
            code = fallback.first
            raw = fallback.second
        }
        if (code !in 200..299) {
            val kind = when {
                code == 401 || code == 403 || code == 404 -> AsrFailureKind.AUTH_OR_CONFIG
                code in listOf(413, 415) -> AsrFailureKind.BAD_AUDIO
                code in listOf(400, 422) -> classifyClientError(raw)
                else -> AsrFailureKind.TRANSIENT
            }
            throw PhoneAsrException(kind, "ASR 返回 HTTP $code: ${raw.take(160)}")
        }
        val obj = JSONObject(raw)
        val durS = obj.optDouble("duration", 0.0).takeIf { it > 0 } ?: localDur
        val arr = obj.optJSONArray("segments") ?: JSONArray()
        val segs = ArrayList<RawSeg>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val txt = s.optString("text").trim()
            if (txt.isEmpty()) continue
            val spk = s.opt("speaker")?.takeUnless { it == JSONObject.NULL }?.toString()?.trim()?.ifEmpty { null }
            segs.add(RawSeg(s.optDouble("start", 0.0), s.optDouble("end", 0.0), txt, spk))
        }
        return buildTranscriptJson(name, durS, obj.optString("text"), AsrProvider.OPENAI.id, cfg.model, segs)
    }

    // ─────────────────── 3. 自建 Hark HTTP (/transcribe) ───────────────────

    private fun transcribeHarkHttp(cfg: AsrConfig, name: String, opus: ByteArray, timeoutMs: Int): JSONObject {
        val endpoint = cfg.baseUrl.trimEnd('/') + "/transcribe"
        val localDur = oggDurationSec(opus)
        val fields = if (cfg.model.isNotBlank()) mapOf("model" to cfg.model) else emptyMap()
        val (body, contentType) = buildMultipart(fields, "file", name, "audio/ogg", opus)
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = timeoutMs
            doOutput = true
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("Content-Type", contentType)
            if (cfg.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
        }
        val (code, raw) = try {
            conn.outputStream.use { it.write(body) }
            val c = conn.responseCode
            val s = if (c in 200..299) conn.inputStream else conn.errorStream
            c to (s?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
        if (code !in 200..299) {
            val kind = when {
                code == 401 || code == 403 || code == 404 -> AsrFailureKind.AUTH_OR_CONFIG
                code in listOf(413, 415) -> AsrFailureKind.BAD_AUDIO
                code in listOf(400, 422) -> classifyClientError(raw)
                else -> AsrFailureKind.TRANSIENT
            }
            throw PhoneAsrException(kind, "自建 ASR 返回 HTTP $code: ${raw.take(160)}")
        }
        val obj = JSONObject(raw)
        val durS = obj.optDouble("duration_s", 0.0).takeIf { it > 0 } ?: localDur
        val arr = obj.optJSONArray("segments") ?: JSONArray()
        val segs = ArrayList<RawSeg>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val txt = s.optString("text").trim()
            if (txt.isEmpty()) continue
            val spk = s.opt("speaker")?.takeUnless { it == JSONObject.NULL }?.toString()?.trim()?.ifEmpty { null }
            segs.add(RawSeg(s.optDouble("start", 0.0), s.optDouble("end", 0.0), txt, spk))
        }
        val model = obj.optString("model").ifBlank { cfg.model.ifBlank { "hark-http" } }
        return buildTranscriptJson(name, durS, obj.optString("text"), AsrProvider.HARK_HTTP.id, model, segs)
    }

    private fun buildMultipart(
        fields: Map<String, String>,
        fileField: String,
        fileName: String,
        mimeType: String,
        fileBytes: ByteArray,
    ): Pair<ByteArray, String> {
        val boundary = "----HarkBoundary" + UUID.randomUUID().toString().replace("-", "")
        val out = ByteArrayOutputStream(fileBytes.size + 512)
        fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        for ((k, v) in fields) {
            writeAscii("--$boundary\r\n")
            writeAscii("Content-Disposition: form-data; name=\"$k\"\r\n\r\n")
            writeAscii("$v\r\n")
        }
        writeAscii("--$boundary\r\n")
        writeAscii("Content-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"\r\n")
        writeAscii("Content-Type: $mimeType\r\n\r\n")
        out.write(fileBytes)
        writeAscii("\r\n--$boundary--\r\n")
        return out.toByteArray() to "multipart/form-data; boundary=$boundary"
    }

    internal fun oggDurationSec(opus: ByteArray): Double {
        val start = maxOf(0, opus.size - 65536)
        for (i in opus.size - 27 downTo start) {
            if (opus[i] == 'O'.code.toByte() && opus[i + 1] == 'g'.code.toByte() &&
                opus[i + 2] == 'g'.code.toByte() && opus[i + 3] == 'S'.code.toByte()
            ) {
                var g = 0L
                for (k in 7 downTo 0) g = (g shl 8) or (opus[i + 6 + k].toLong() and 0xff)
                return max(0.0, (g - OggOpusWriter.PRE_SKIP) / 48000.0)
            }
        }
        return 0.0
    }

    /** 仅明确的音频损坏/空音频/解码失败才判为单文件 BAD_AUDIO；模型名填错等通用 400 归为 AUTH_OR_CONFIG 立即停批，不误伤正常录音。 */
    internal fun classifyClientError(body: String): AsrFailureKind {
        val s = body.lowercase()
        val isAudioProblem = s.contains("corrupt") ||
            s.contains("decode") ||
            s.contains("invalid file") ||
            s.contains("audio file is too short") ||
            s.contains("empty audio") ||
            s.contains("invalid audio") ||
            s.contains("bad ogg") ||
            s.contains("音频损坏") ||
            s.contains("解码失败")
        return if (isAudioProblem) AsrFailureKind.BAD_AUDIO else AsrFailureKind.AUTH_OR_CONFIG
    }

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}
