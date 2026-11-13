package io.github.riggedlab.hark

import androidx.compose.runtime.Immutable
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** 一段录音：文件名就是开始时间（yyyyMMdd-HHmmss.opus）。音频可能已经过期删除，只剩文字。 */
@Immutable
data class Recording(
    val file: File,
    val start: LocalDateTime,
    val durationSec: Double,
    val hasAudio: Boolean = file.exists(),
) {
    val name: String = file.name
    val end: LocalDateTime = start.plusSeconds(durationSec.toLong())
}

@Immutable
data class RecordingStub(
    val name: String,
    val file: File,
    val start: LocalDateTime,
    val hasAudio: Boolean,
)

object Recordings {
    const val RETENTION_DAYS = 7L
    private val NAME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val durationCache = ConcurrentHashMap<String, Double>()

    fun parseStart(f: File): LocalDateTime? = parseStartName(f.name)

    fun parseStartName(name: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(name.removeSuffix(".opus"), NAME_FMT) }.getOrNull()

    /**
     * 零磁盘文件头读取的轻量级索引：仅扫描目录文件名并按时间倒序（从近到远）返回，
     * 供首页记忆流按页懒加载，避免冷启动全量读取数以百计的 Ogg 页尾与转写 JSON。
     */
    fun listStubsNewestFirst(ctx: android.content.Context): List<RecordingStub> {
        val dir = RecorderService.chunkDir(ctx)
        val audioNames = dir.list()?.filter { it.endsWith(".opus") }?.toHashSet() ?: emptySet()
        val textNames = Transcripts.allNames(ctx)
        val allNames = HashSet<String>(audioNames.size + textNames.size).apply {
            addAll(audioNames)
            addAll(textNames)
        }
        return allNames.mapNotNull { name ->
            val start = parseStartName(name) ?: return@mapNotNull null
            RecordingStub(
                name = name,
                file = File(dir, name),
                start = start,
                hasAudio = name in audioNames,
            )
        }.sortedByDescending { it.start }
    }

    fun resolveRecording(ctx: android.content.Context, stub: RecordingStub): Recording {
        val dur = if (stub.hasAudio) {
            durationCache.getOrPut("${stub.name}:${stub.file.length()}") { oggDurationSec(stub.file) }
        } else {
            Transcripts.get(ctx, stub.name)?.durationSec ?: 0.0
        }
        return Recording(stub.file, stub.start, dur, hasAudio = stub.hasAudio)
    }

    /** 列出所有录音（含只剩文字的），新的在前；文件存在性与时长均在后台线程一次性缓存 */
    fun list(ctx: android.content.Context): List<Recording> =
        listStubsNewestFirst(ctx).map { resolveRecording(ctx, it) }

    /** 删除 RETENTION_DAYS 天之前的录音（按自然日：今天 + 前 6 天保留） */
    fun cleanup(dir: File, mode: WorkMode = WorkMode.CLOUD, today: LocalDate = LocalDate.now()) {
        val keepFrom = today.minusDays(RETENTION_DAYS - 1).atStartOfDay()
        // 还没处理完的不删：断网或欠费时宁可多占空间，也不能丢录音
        val done = listOf("uploaded", "novoice", "farvoice").map { File(dir.parentFile, it) }
        val textsDir = File(dir.parentFile, "texts")
        dir.listFiles { f -> f.name.endsWith(".opus") }?.forEach { f ->
            val start = parseStart(f)
            val localTranscribed = mode == WorkMode.DIRECT_ASR && File(textsDir, "${f.name}.json").exists()
            val handled = done.any { File(it, f.name).exists() } || localTranscribed
            if (start != null && start.isBefore(keepFrom) && handled) f.delete()
        }
        Waveforms.cleanup(dir)
    }

    /** 读 Ogg 文件最后一页的 granule position 得到时长（48kHz，去掉 pre-skip） */
    fun oggDurationSec(f: File): Double = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val tail = minOf(len, 65536L).toInt()
            val buf = ByteArray(tail)
            raf.seek(len - tail)
            raf.readFully(buf)
            for (i in tail - 27 downTo 0) {
                if (buf[i] == 'O'.code.toByte() && buf[i + 1] == 'g'.code.toByte() &&
                    buf[i + 2] == 'g'.code.toByte() && buf[i + 3] == 'S'.code.toByte()) {
                    var g = 0L
                    for (k in 7 downTo 0) g = (g shl 8) or (buf[i + 6 + k].toLong() and 0xff)
                    return@use maxOf(0.0, (g - OggOpusWriter.PRE_SKIP) / 48000.0)
                }
            }
            0.0
        }
    }.getOrDefault(0.0)
}
