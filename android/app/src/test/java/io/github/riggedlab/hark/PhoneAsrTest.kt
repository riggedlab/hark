package io.github.riggedlab.hark

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

class PhoneAsrTest {

    private val sampleOpus: ByteArray by lazy {
        val f = File("../../internal/asr/probe.opus")
        if (f.exists()) f.readBytes() else ByteArray(64) { it.toByte() }
    }

    private data class HttpReq(val requestLine: String, val headers: Map<String, String>, val body: ByteArray)
    private data class HttpResp(val status: Int = 200, val headers: Map<String, String> = emptyMap(), val body: String = "{}")

    private fun withTestServer(handler: (HttpReq) -> HttpResp, block: (String) -> Unit) {
        val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val t = Thread {
            while (!ss.isClosed) {
                val sock = runCatching { ss.accept() }.getOrNull() ?: break
                try {
                    val ins = sock.getInputStream()
                    val reqLine = readAsciiLine(ins) ?: continue
                    val hdrs = LinkedHashMap<String, String>()
                    while (true) {
                        val line = readAsciiLine(ins) ?: break
                        if (line.isEmpty()) break
                        val idx = line.indexOf(':')
                        if (idx > 0) hdrs[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                    }
                    val len = hdrs["content-length"]?.toIntOrNull() ?: 0
                    val body = ByteArray(len)
                    var read = 0
                    while (read < len) {
                        val n = ins.read(body, read, len - read)
                        if (n <= 0) break
                        read += n
                    }
                    val resp = try {
                        handler(HttpReq(reqLine, hdrs, body))
                    } catch (err: Throwable) {
                        HttpResp(status = 500, body = JSONObject().put("test_assertion_error", err.toString()).toString())
                    }
                    val outBytes = resp.body.toByteArray(Charsets.UTF_8)
                    val head = buildString {
                        append("HTTP/1.1 ${resp.status} OK\r\n")
                        append("Content-Type: application/json; charset=utf-8\r\n")
                        append("Content-Length: ${outBytes.size}\r\n")
                        append("Connection: close\r\n")
                        for ((k, v) in resp.headers) append("$k: $v\r\n")
                        append("\r\n")
                    }.toByteArray(Charsets.UTF_8)
                    sock.getOutputStream().use {
                        it.write(head)
                        it.write(outBytes)
                        it.flush()
                    }
                } finally {
                    runCatching { sock.close() }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            block("http://127.0.0.1:${ss.localPort}")
        } finally {
            runCatching { ss.close() }
            t.join(1_000)
        }
    }

    private fun readAsciiLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    @Test
    fun volcengineFlashWithSpeakerMappingAndFallback() {
        val calls = AtomicInteger(0)
        var rejectSpeakerOnce = true
        withTestServer({ req ->
            calls.incrementAndGet()
            assertTrue(req.requestLine.startsWith("POST /api/v3/auc/bigmodel/recognize/flash "))
            assertEquals("test-volc-key", req.headers["x-api-key"])
            assertEquals("volc.bigasr.auc_turbo", req.headers["x-api-resource-id"])
            val json = JSONObject(req.body.toString(Charsets.UTF_8))
            assertEquals("ogg", json.getJSONObject("audio").getString("format"))
            assertEquals("opus", json.getJSONObject("audio").getString("codec"))

            if (rejectSpeakerOnce && json.getJSONObject("request").optBoolean("enable_speaker_info", false)) {
                rejectSpeakerOnce = false
                HttpResp(
                    status = 200,
                    headers = mapOf("X-Api-Status-Code" to "45000001", "X-Api-Message" to "speaker info unsupported"),
                    body = "{}",
                )
            } else {
                HttpResp(
                    status = 200,
                    headers = mapOf("X-Api-Status-Code" to "20000000"),
                    body = """
                        {
                          "audio_info": {"duration": 3420},
                          "result": {
                            "text": "你好，今天三点开会。好的没问题。",
                            "utterances": [
                              {"start_time": 120, "end_time": 1680, "text": "你好，今天三点开会。", "additions": {"speaker": "A"}},
                              {"start_time": 1800, "end_time": 3200, "text": "好的没问题。", "additions": {"speaker": "B"}}
                            ]
                          }
                        }
                    """.trimIndent(),
                )
            }
        }) { baseUrl ->
            val cfg = AsrConfig(
                provider = AsrProvider.VOLCENGINE,
                baseUrl = baseUrl,
                apiKey = "test-volc-key",
            )
            val rep = PhoneAsr.probeBytes(cfg, sampleOpus)
            assertTrue(rep.error, rep.ok)
            assertEquals(2, calls.get())
            assertEquals(2, rep.speakerCount)
            assertEquals("你好，今天三点开会。好的没问题。", rep.text)

            val json = PhoneAsr.transcribe(cfg, "20261112-150000.opus", sampleOpus)
            assertEquals(3, calls.get())
            assertEquals("20261112-150000.opus", json.getString("name"))
            assertEquals(3.42, json.getDouble("duration_s"), 0.01)
            val segs = json.getJSONArray("segments")
            assertEquals(2, segs.length())
            assertEquals("other_1", segs.getJSONObject(0).getString("speaker"))
            assertEquals("other_2", segs.getJSONObject(1).getString("speaker"))
        }
    }

    @Test
    fun volcengineSilenceReturnsEmptyTranscriptWithoutError() {
        withTestServer({
            HttpResp(status = 200, headers = mapOf("X-Api-Status-Code" to "20000003"), body = "{}")
        }) { baseUrl ->
            val cfg = AsrConfig(
                provider = AsrProvider.VOLCENGINE,
                baseUrl = baseUrl,
                apiKey = "test-key",
            )
            val json = PhoneAsr.transcribe(cfg, "20261112-150500.opus", sampleOpus)
            assertEquals("", json.getString("text"))
            assertEquals(0, json.getJSONArray("segments").length())
        }
    }

    @Test
    fun openAiFallsBackFromVerboseJsonToJson() {
        val calls = AtomicInteger(0)
        withTestServer({ req ->
            val n = calls.incrementAndGet()
            assertTrue(req.requestLine.startsWith("POST /v1/audio/transcriptions "))
            assertEquals("Bearer sk-test", req.headers["authorization"])
            val bodyStr = req.body.toString(Charsets.ISO_8859_1)
            assertTrue(bodyStr.contains("filename=\"20261112-160000.ogg\""))
            if (n == 1) {
                assertTrue(bodyStr.contains("verbose_json"))
                HttpResp(status = 400, body = """{"error":{"message":"response_format 'verbose_json' is not supported"}}""")
            } else {
                assertTrue(bodyStr.contains("\r\n\r\njson\r\n"))
                HttpResp(status = 200, body = """{"text":"手机直连 OpenAI 测试成功"}""")
            }
        }) { baseUrl ->
            val cfg = AsrConfig(
                provider = AsrProvider.OPENAI,
                baseUrl = "$baseUrl/v1",
                apiKey = "sk-test",
                model = "gpt-4o-mini-transcribe",
            )
            val json = PhoneAsr.transcribe(cfg, "20261112-160000.opus", sampleOpus)
            assertEquals(2, calls.get())
            assertEquals("手机直连 OpenAI 测试成功", json.getString("text"))
            assertEquals(1, json.getJSONArray("segments").length())
        }
    }

    @Test
    fun cleanupPreservesUnuploadedInCloudModeAndCleansTranscribedInDirectMode() {
        val root = Files.createTempDirectory("hark-cleanup-test").toFile()
        try {
            val chunks = File(root, "chunks").apply { mkdirs() }
            val texts = File(root, "texts").apply { mkdirs() }
            File(root, "uploaded").mkdirs()
            File(root, "novoice").mkdirs()

            val oldTranscribed = File(chunks, "20261101-100000.opus").apply { writeBytes(ByteArray(16)) }
            val oldUntranscribed = File(chunks, "20261101-100500.opus").apply { writeBytes(ByteArray(16)) }
            File(texts, "20261101-100000.opus.json").writeText("""{"name":"20261101-100000.opus","text":"ok","segments":[]}""")

            val today = LocalDate.of(2026, 11, 12)
            Recordings.cleanup(chunks, WorkMode.CLOUD, today)
            assertTrue(oldTranscribed.exists())
            assertTrue(oldUntranscribed.exists())

            Recordings.cleanup(chunks, WorkMode.DIRECT_ASR, today)
            assertFalse(oldTranscribed.exists())
            assertTrue(oldUntranscribed.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun errorClassificationSeparatesConfigErrorFromCorruptAudio() {
        // 1. 火山 45000001（关闭说话人后仍报参数错误）必须判为 AUTH_OR_CONFIG，不能误标正常录音为坏文件
        withTestServer({
            HttpResp(status = 200, headers = mapOf("X-Api-Status-Code" to "45000001", "X-Api-Message" to "invalid resource"), body = "{}")
        }) { baseUrl ->
            val cfg = AsrConfig(provider = AsrProvider.VOLCENGINE, baseUrl = baseUrl, apiKey = "k")
            val ex = assertThrows(PhoneAsrException::class.java) {
                PhoneAsr.transcribe(cfg, "20261112-170000.opus", sampleOpus)
            }
            assertEquals(AsrFailureKind.AUTH_OR_CONFIG, ex.kind)
        }

        // 2. 火山 45000151（音频格式/解码异常）判为 BAD_AUDIO，允许队列跳过单文件继续处理新录音
        withTestServer({
            HttpResp(status = 200, headers = mapOf("X-Api-Status-Code" to "45000151", "X-Api-Message" to "audio decode failed"), body = "{}")
        }) { baseUrl ->
            val cfg = AsrConfig(provider = AsrProvider.VOLCENGINE, baseUrl = baseUrl, apiKey = "k")
            val ex = assertThrows(PhoneAsrException::class.java) {
                PhoneAsr.transcribe(cfg, "20261112-170500.opus", sampleOpus)
            }
            assertEquals(AsrFailureKind.BAD_AUDIO, ex.kind)
        }

        // 3. OpenAI 模型填错 400 -> AUTH_OR_CONFIG；音频损坏 400 -> BAD_AUDIO
        assertEquals(
            AsrFailureKind.AUTH_OR_CONFIG,
            PhoneAsr.classifyClientError("""{"error":{"message":"The model 'bad-model' does not exist"}}"""),
        )
        assertEquals(
            AsrFailureKind.BAD_AUDIO,
            PhoneAsr.classifyClientError("""{"error":{"message":"Audio file might be corrupted or unsupported"}}"""),
        )
    }
}
