package io.github.riggedlab.hark

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 处理录好的 .opus 片段：
 * - 云端信箱模式 (CLOUD)：加密上传到信箱 /v2/inbox，成功后写 files/uploaded/<name>。
 * - 手机直连模式 (DIRECT_ASR)：本机直调三方 ASR 生成 files/texts/<name>.json（不写 uploaded/，
 *   7 天内切回云端模式时会自动把未上传录音加密补传回电脑端 hark.db）。
 */
class Uploader(private val ctx: Context) {
    private val chunkDir = RecorderService.chunkDir(ctx)
    private val markDir = File(ctx.filesDir, "uploaded").apply { mkdirs() }
    private val silentDir = File(ctx.filesDir, "novoice").apply { mkdirs() }
    private val farDir = File(ctx.filesDir, "farvoice").apply { mkdirs() }
    private val asrNoRetryDir = File(ctx.filesDir, "asr_no_retry").apply { mkdirs() }

    fun isUploaded(f: File) = File(markDir, f.name).exists()
    /** 这段录音没有人声，不上传也不调云端 ASR（录音本身照常在手机上保留 7 天） */
    fun isSilent(f: File) = File(silentDir, f.name).exists()
    /** 整段录音仅有远场背景杂谈或电子外放且不匹配本机核心声纹，跳过转写/上传（本地照常保留 7 天） */
    fun isFarSkipped(f: File) = File(farDir, f.name).exists()
    fun isSkippedBadAudio(f: File) = File(asrNoRetryDir, f.name).takeIf { it.exists() }?.readText() == AsrFailureKind.BAD_AUDIO.name

    /** 后台一次性枚举标记集合，避免列表渲染时逐卡片调用 File.exists() */
    data class MarkSnapshot(
        val uploaded: Set<String>,
        val silent: Set<String>,
        val farSkipped: Set<String>,
        val badAudio: Set<String>,
        val asrTimedOut: Set<String>,
        val unuploadedCount: Int,
    )

    fun snapshotMarks(): MarkSnapshot {
        val up = (markDir.list() ?: emptyArray()).toHashSet()
        val sil = (silentDir.list() ?: emptyArray()).toHashSet()
        val far = (farDir.list() ?: emptyArray()).toHashSet()
        val failures = asrNoRetryDir.listFiles().orEmpty().associate { it.name to it.readText() }
        val bad = failures.filterValues { it == AsrFailureKind.BAD_AUDIO.name }.keys
        val timedOut = failures.filterValues { it == AsrFailureKind.READ_TIMEOUT.name }.keys
        val unuploaded = (chunkDir.list() ?: emptyArray()).count { name ->
            name.endsWith(".opus") && name !in up && name !in sil && name !in far && name !in failures
        }
        return MarkSnapshot(uploaded = up, silent = sil, farSkipped = far, badAudio = bad, asrTimedOut = timedOut, unuploadedCount = unuploaded)
    }

    /** 按当前工作模式处理待办片段，并异步触发与 ASR 解耦的本地环境音/心情扫描 */
    fun processPending(): Pair<Int, Int> {
        SceneAndMood.scheduleBackgroundScan(ctx)
        val res = when (Config.mode(ctx)) {
            WorkMode.CLOUD -> uploadPending()
            WorkMode.DIRECT_ASR -> transcribePendingLocally()
        }
        SceneAndMood.scheduleBackgroundScan(ctx)
        return res
    }

    fun hasPendingWork(): Boolean = when (Config.mode(ctx)) {
        WorkMode.CLOUD -> pendingCloud().isNotEmpty()
        WorkMode.DIRECT_ASR -> pendingLocalAsr().isNotEmpty()
    }

    /** 尚未加密上传到云端信箱的有声/待检片段数（供手机直连模式提示切回云端可补传的数量） */
    fun unuploadedCount(): Int = pendingCloud().size

    /** 上传所有还没上传的录音，返回 (成功数, 剩余数) */
    fun uploadPending(): Pair<Int, Int> = synchronized(uploadLock) {
        if (Config.mode(ctx) != WorkMode.CLOUD) return 0 to pendingLocalAsr().size
        val cfg = Config.load(ctx) ?: return 0 to pendingCloud().size
        var ok = 0
        for (f in pendingCloud()) {
            if (Config.mode(ctx) != WorkMode.CLOUD) break
            // 若已在手机直连模式下出过字，优先尊重已有文稿，避免切回云端补传时被重复拦截
            if (Transcripts.get(ctx, f.name) == null) {
                when (Vad.screenChunk(ctx, f)) {
                    Proximity.ChunkGate.NO_VOICE -> {
                        File(silentDir, f.name).createNewFile()
                        Log.i(TAG, "${f.name} 没有人声，不上传")
                        continue
                    }
                    Proximity.ChunkGate.FAR_OR_MEDIA -> {
                        File(farDir, f.name).createNewFile()
                        Log.i(TAG, "${f.name} 整段为远场/外放背景声，不上传")
                        continue
                    }
                    Proximity.ChunkGate.KEEP -> Unit
                }
            }
            if (upload(cfg, f)) {
                File(markDir, f.name).createNewFile()
                ok++
            } else break                     // 网络不通就别一个个试了，等下一轮
        }
        cleanupMarkers()
        return ok to pendingCloud().size
    }

    /** 手机本机直调三方 ASR 转写尚未出字的录音，返回 (成功数, 剩余数) */
    fun transcribePendingLocally(): Pair<Int, Int> {
        if (Config.mode(ctx) == WorkMode.DIRECT_ASR) {
            Voiceprint.triggerBackgroundBootstrap(ctx)
        }
        return synchronized(uploadLock) {
            if (Config.mode(ctx) != WorkMode.DIRECT_ASR) return@synchronized 0 to pendingCloud().size
            val cfg = AsrConfig.load(ctx) ?: return@synchronized 0 to pendingLocalAsr().size
            var ok = 0
            for (f in pendingLocalAsr()) {
                if (Config.mode(ctx) != WorkMode.DIRECT_ASR) break
                when (Vad.screenChunk(ctx, f)) {
                    Proximity.ChunkGate.NO_VOICE -> {
                        File(silentDir, f.name).createNewFile()
                        Log.i(TAG, "${f.name} 没有人声，跳过手机转写")
                        continue
                    }
                    Proximity.ChunkGate.FAR_OR_MEDIA -> {
                        File(farDir, f.name).createNewFile()
                        Log.i(TAG, "${f.name} 整段为远场/外放背景声，跳过手机转写")
                        continue
                    }
                    Proximity.ChunkGate.KEEP -> Unit
                }
                AppState.update { it.copy(localAsrRunning = f.name) }
                var abortBatch = false
                runCatching {
                    val rawJson = PhoneAsr.transcribe(cfg, f.name, f.readBytes())
                    val enriched = Voiceprint.enrichTranscript(ctx, f, rawJson)
                    Transcripts.save(ctx, enriched)
                    SceneAndMood.scheduleBackgroundScan(ctx)
                }.onSuccess {
                ok++
                AppState.update {
                    it.copy(
                        localAsrRunning = null,
                        localAsrError = null,
                        textVersion = it.textVersion + 1,
                    )
                }
            }.onFailure { e ->
                Log.w(TAG, "local asr ${f.name} failed: ${e.message}")
                val kind = (e as? PhoneAsrException)?.kind ?: AsrFailureKind.TRANSIENT
                if (kind == AsrFailureKind.BAD_AUDIO || kind == AsrFailureKind.READ_TIMEOUT) {
                    // 坏音频无法重试修复；读超时可能已计费。跨批次、重启也跳过，继续处理后续录音。
                    markAsrNoRetry(f.name, kind)
                } else {
                    abortBatch = true
                }
                AppState.update {
                    it.copy(
                        localAsrRunning = null,
                        localAsrError = e.message?.take(120) ?: "转写失败",
                    )
                }
            }
            if (abortBatch) break
        }
        cleanupMarkers()
        ok to pendingLocalAsr().size
        }
    }

    private fun cleanupMarkers() {
        markDir.listFiles()?.forEach { if (!File(chunkDir, it.name).exists()) it.delete() }
        silentDir.listFiles()?.forEach { if (!File(chunkDir, it.name).exists()) it.delete() }
        farDir.listFiles()?.forEach { if (!File(chunkDir, it.name).exists()) it.delete() }
        File(ctx.filesDir, "vad_gate").listFiles()?.forEach {
            val base = it.name.removeSuffix(".txt")
            if (!File(chunkDir, base).exists()) it.delete()
        }
        asrNoRetryDir.listFiles()?.forEach { if (!File(chunkDir, it.name).exists()) it.delete() }
    }

    private fun pendingCloud() =
        (chunkDir.listFiles { f -> f.name.endsWith(".opus") } ?: emptyArray())
            .filter { !isUploaded(it) && !isSilent(it) && !isFarSkipped(it) && !File(asrNoRetryDir, it.name).exists() }
            .sortedBy { it.name }

    private fun pendingLocalAsr() =
        (chunkDir.listFiles { f -> f.name.endsWith(".opus") } ?: emptyArray())
            .filter { !isSilent(it) && !isFarSkipped(it) && !File(asrNoRetryDir, it.name).exists() && Transcripts.get(ctx, it.name) == null }
            .sortedBy { it.name }

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

    internal fun markAsrNoRetry(name: String, kind: AsrFailureKind) {
        Sync.writeFile(File(asrNoRetryDir, name), kind.name)
    }

    companion object {
        private const val TAG = "Uploader"
        // 前台、录音服务和收尾线程共用，避免同时运行 VAD、重复上传或重复调 ASR。
        private val uploadLock = Any()
        fun clearLocalAsrFailures(ctx: Context) {
            // 修改配置可重新尝试坏音频；已可能计费的超时不能因此被自动重发。
            File(ctx.filesDir, "asr_no_retry").listFiles()?.forEach {
                if (it.readText() == AsrFailureKind.BAD_AUDIO.name) it.delete()
            }
        }
    }
}
