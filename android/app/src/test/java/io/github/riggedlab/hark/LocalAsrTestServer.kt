package io.github.riggedlab.hark

import org.json.JSONObject
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket

internal data class HttpReq(val requestLine: String, val headers: Map<String, String>, val body: ByteArray)
internal data class HttpResp(val status: Int = 200, val headers: Map<String, String> = emptyMap(), val body: String = "{}")

internal fun withTestServer(handler: (HttpReq) -> HttpResp, block: (String) -> Unit) {
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
