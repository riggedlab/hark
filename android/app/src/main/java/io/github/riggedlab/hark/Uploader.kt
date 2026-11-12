package io.github.riggedlab.hark

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 把录好的 .opus 上传到信箱。上传成功后在 files/uploaded/ 里放一个同名标记文件。
 * 失败了没关系，下一轮会再试（服务器端同名上传是幂等的）。
 */
class Uploader(private val ctx: Context) {
    private val chunkDir = RecorderService.chunkDir(ctx)
    private val markDir = File(ctx.filesDir, "uploaded").apply { mkdirs() }

    private val silentDir = File(ctx.filesDir, "novoice").apply { mkdirs() }

    fun isUploaded(f: File) = File(markDir, f.name).exists()
    /** 这段录音没有人声，没有上传（录音本身照常在手机上保留 7 天） */
    fun isSilent(f: File) = File(silentDir, f.name).exists()

    /** 上传所有还没上传的录音，返回 (成功数, 剩余数) */
    fun uploadPending(): Pair<Int, Int> = synchronized(uploadLock) {
        val cfg = Config.load(ctx) ?: return 0 to pending().size
        var ok = 0
        for (f in pending()) {
            val speech = Vad.speechSeconds(ctx, f)
            if (speech != null && speech < Vad.MIN_SPEECH_S) {
                File(silentDir, f.name).createNewFile()
                Log.i(TAG, "${f.name} 没有人声，不上传")
                continue
            }
            if (upload(cfg, f)) {
                File(markDir, f.name).createNewFile()
                ok++
            } else break                     // 网络不通就别一个个试了，等下一轮
        }
        markDir.listFiles()?.forEach { if (!File(chunkDir, it.name).exists()) it.delete() }
        silentDir.listFiles()?.forEach { if (!File(chunkDir, it.name).exists()) it.delete() }
        return ok to pending().size
    }

    fun hasPending() = pending().isNotEmpty()

    private fun pending() =
        (chunkDir.listFiles { f -> f.name.endsWith(".opus") } ?: emptyArray())
            .filter { !isUploaded(it) && !isSilent(it) }.sortedBy { it.name }

    private fun upload(cfg: Config, f: File): Boolean = runCatching {
        val packet = Crypto.packAudio(cfg.e2eeKey, f.name, f.readBytes())
        val conn = Mailbox.open("${cfg.serverUrl}/v2/inbox/${Crypto.itemId(cfg.e2eeKey, f.name)}")
        try {
            conn.requestMethod = "PUT"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(packet.size)
            conn.setRequestProperty("Authorization", "Bearer ${cfg.token}")
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.outputStream.use { it.write(packet) }
            val code = conn.responseCode
            if (code != 200) Log.w(TAG, "upload ${f.name} -> HTTP $code")
            code == 200
        } finally { conn.disconnect() }
    }.onFailure { Log.w(TAG, "upload ${f.name} failed: ${it.message}") }.getOrDefault(false)

    companion object {
        private const val TAG = "Uploader"
        // 前台、录音服务和收尾线程共用，避免同时运行 VAD 和重复上传。
        private val uploadLock = Any()
    }
}
