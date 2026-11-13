package io.github.riggedlab.hark

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 手机唯一的 v2 拉取入口，串行提交游标；前台长轮询可随生命周期立即断开。 */
object Sync {
    private val polling = Mutex()
    private val pushing = Mutex()
    @Volatile private var pollFailures = 0

    fun writeFile(file: File, text: String) {
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(text)
        check(tmp.renameTo(file)) { "保存失败，请检查手机存储空间" }
    }

    private suspend fun request(cfg: Config, path: String, waitSec: Int = 0, body: ByteArray? = null): String =
        suspendCancellableCoroutine { continuation ->
            val conn = Mailbox.open("${cfg.serverUrl}$path")
            conn.connectTimeout = 15_000
            conn.readTimeout = (waitSec + 15) * 1000
            conn.setRequestProperty("Authorization", "Bearer ${cfg.token}")
            continuation.invokeOnCancellation { conn.disconnect() }
            Dispatchers.IO.asExecutor().execute {
                try {
                    if (!continuation.isActive) return@execute
                    if (body != null) {
                        conn.requestMethod = "PUT"
                        conn.doOutput = true
                        conn.setFixedLengthStreamingMode(body.size)
                        conn.setRequestProperty("Content-Type", "application/octet-stream")
                        conn.outputStream.use { it.write(body) }
                    }
                    check(conn.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${conn.responseCode}" }
                    val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    continuation.resume(text)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                } finally { conn.disconnect() }
            }
        }

    suspend fun pushSpeakerEdits(ctx: Context): Boolean = pushing.withLock {
        if (Config.mode(ctx) != WorkMode.CLOUD) return@withLock true
        val cfg = Config.load(ctx) ?: return@withLock false
        val (revision, body) = Speakers.pending(ctx) ?: return@withLock true
        try {
            val packet = Crypto.encrypt(cfg.e2eeKey, body.toByteArray(Charsets.UTF_8), "slot:speakers_phone".toByteArray())
            val result = JSONObject(request(cfg, "/v2/slot/speakers_phone", body = packet))
            Speakers.acknowledge(ctx, revision, result.getInt("ver"))
            true
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            AppState.updateRemote { it.copy(mailboxReachable = false, mailboxError = e.message?.take(120)) }
            Log.w("Sync", "说话人修改待重试：${e.message}")
            false
        }
    }

    private fun decode(cfg: Config, value: JSONObject, aad: String): JSONObject = JSONObject(
        Crypto.decrypt(cfg.e2eeKey, Base64.getDecoder().decode(value.getString("data_b64")),
            aad.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8))

    suspend fun pollOnce(ctx: Context, waitSec: Int = 0): Boolean = polling.withLock {
        if (Config.mode(ctx) != WorkMode.CLOUD) return@withLock false
        val cfg = Config.load(ctx) ?: return@withLock false
        try {
            if (!pushSpeakerEdits(ctx)) return@withLock false
            val p = ctx.getSharedPreferences("sync_state", Context.MODE_PRIVATE)
            val recovering = pollFailures > 0 || AppState.status.value.remote.mailboxReachable == false
            var wait = if (recovering) 0 else waitSec.coerceIn(0, 20)
            do {
                val query = "ver=${p.getInt("ver", -1)}&after_seq=${p.getLong("after_seq", 0)}" +
                    "&ws_ver=${p.getInt("ws_ver", 0)}&sm_ver=${p.getInt("sm_ver", 0)}&sp_ver=${p.getInt("sp_ver", 0)}&wait=$wait"
                val result = JSONObject(request(cfg, "/v2/poll?$query", wait))
                val texts = result.getJSONArray("new_texts")
                var after = if (result.optBoolean("cursor_reset", false)) 0L else p.getLong("after_seq", 0)
                // 一批全部认证并落盘后才推进游标；先快速保存文字，环境音与心情交由独立后台协程异步补齐，绝不阻塞同步批次
                for (i in 0 until texts.length()) {
                    val item = texts.getJSONObject(i)
                    val text = decode(cfg, item, "text:${item.getString("id")}")
                    val name = text.getString("name")
                    require(Crypto.audioName.matches(name) && Crypto.itemId(cfg.e2eeKey, name) == item.getString("id")) {
                        "转写文件名或条目 ID 不匹配"
                    }
                    Transcripts.save(ctx, text)
                    after = maxOf(after, item.getLong("seq"))
                }
                if (texts.length() > 0) {
                    SceneAndMood.scheduleBackgroundScan(ctx)
                }
                val slots = result.getJSONObject("slots")
                for (slot in listOf("speakers_mac", "speakers_phone")) {
                    val meta = slots.optJSONObject(slot) ?: continue
                    if (meta.has("data_b64")) Speakers.receive(ctx, slot, meta.getInt("ver"), decode(cfg, meta, "slot:$slot"))
                }
                val ws = slots.optJSONObject("worker_status")
                val cached = File(ctx.filesDir, "worker_status.json")
                if (ws?.has("data_b64") == true) {
                    writeFile(cached, decode(cfg, ws, "slot:worker_status").toString())
                    p.edit().putInt("ws_ver", ws.getInt("ver")).commit()
                }
                val worker = if (ws != null && cached.exists()) JSONObject(cached.readText()) else null
                val age = ws?.getDouble("age_s")
                AppState.updateRemote { RemoteStatus(
                    mailboxReachable = true, inboxCount = result.getInt("inbox_count"),
                    workerOnline = worker != null && age != null && age <= 45,
                    workerAgeSec = age, workerPhase = worker?.optString("phase"),
                    workerCurrent = worker?.optString("current")?.takeUnless { worker.isNull("current") },
                    workerPhaseElapsedSec = (worker?.optDouble("phase_elapsed_s", 0.0) ?: 0.0) + (age ?: 0.0),
                    asrOk = worker?.optBoolean("asr_ok"),
                    asrModel = worker?.optString("asr_model")?.takeUnless { worker.isNull("asr_model") },
                    workerError = worker?.optString("last_error")?.takeUnless { worker.isNull("last_error") },
                ) }
                p.edit().putLong("after_seq", after).putInt("ver", result.getInt("ver")).commit()
                AppState.update { it.copy(textVersion = it.textVersion + texts.length()) }
                wait = 0
            } while (result.getBoolean("has_more"))
            pollFailures = 0
            true
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            val msg = e.message?.take(120) ?: ""
            val hard = msg.contains("HTTP 401") || msg.contains("HTTP 403") || msg.contains("HTTP 404") || e is javax.crypto.AEADBadTagException
            if (hard || ++pollFailures >= 3) {
                AppState.updateRemote { it.copy(mailboxReachable = false, mailboxError = msg.ifBlank { null }) }
            }
            Log.w("Sync", "同步失败 ($pollFailures)：$msg")
            false
        }
    }
}
