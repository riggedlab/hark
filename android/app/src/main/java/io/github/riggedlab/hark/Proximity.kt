package io.github.riggedlab.hark

import android.content.Context
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 单麦语音远近场与电子外放判别引擎（100% 手机本机运行，零新增模型体积）：
 *
 * 物理依据（基于真实佩戴/桌面历史 .opus 录音实测标定）：
 * 1. 局部信噪比 (snr_db = 有效语音分贝 - 前后停顿传输帧 p15 底噪)：
 *    固件旧版 AGC 在停顿及句尾 3 秒 (HANG) 冻结增益，放大远场语音的同时等量抬高邻近底噪；
 *    相减后增益项完全抵消，对旧版动态增益录音与新版固定 +12dB 增益录音具备同一物理尺度。
 * 2. CED-Tiny 声学空间与发声方式线索（与整体分贝无关，专解「近处低声 vs 远处高声」倒挂）：
 *    - 近场干声/耳语线索：Narration(5) + Whispering(15) + 词间干净静音(500)
 *    - 远场混响/嘈杂线索：Chatter/Crowd/Babble(68,69,70) + 大空间/公共场所/混响(507,508,509,511,512,300)
 *    - 电子外放线索：Television(524) + Radio(525) + Music/Singing(137,27) + 合成音(7)
 * 3. 中高频能量比 (hf_lf_db = 1k~4kHz / 150~800Hz)：
 *    手机/电脑小喇叭物理缺失 300Hz 以下低频（hf_lf_db 显著偏正）；同段内扯嗓子远讲的中高频比高于近讲低语。
 */
internal object Proximity {
    const val NEAR = "near"
    const val FAR = "far"
    const val MEDIA = "media"

    private const val SAMPLE_RATE = 16000
    private const val FRAME_LEN = 320 // 20ms @ 16kHz
    private const val FFT_N = 512
    private const val HALF_FFT = 256
    private const val MIN_FLOOR_ENERGY = 1e-10
    private const val ACTIVE_GATE_MIN_DB = -62.0f
    private const val ACTIVE_SPAN_DB = 15.0f
    private const val MIN_CLAMP_DB = -65.0f

    // 硬件静音补帧 (OggOpusWriter.SILENT_PACKET) 解码后低于 -78dBFS，真实蓝牙传输帧高于此门限
    private const val TX_FLOOR_DB = -76.0f
    private const val TX_PEAK_MIN = 1.5e-4f

    private val hann320 = FloatArray(FRAME_LEN) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / FRAME_LEN)).toFloat()
    }

    enum class ChunkGate { KEEP, NO_VOICE, FAR_OR_MEDIA }

    data class SegmentAcoustic(
        val activeDb: Float,
        val activeSec: Float,
        val localFloorDb: Float,
        val snrDb: Float,
        val hfLfDb: Float,
    )

    class AudioProfile(
        val pcm16k: FloatArray,
        val energies: DoubleArray,
        val frameDb: FloatArray,
        val transmitted: BooleanArray,
        val chunkFloorDb: Float,
    ) {
        private val fftBuf = FloatArray(FFT_N)
        private val fftRe = FloatArray(FFT_N)
        private val fftIm = FloatArray(FFT_N)
        private val fftPow = FloatArray(HALF_FFT + 1)

        fun measureSegment(startSec: Double, endSec: Double): SegmentAcoustic? {
            if (energies.isEmpty() || endSec <= startSec) return null
            val sIdx = floor(startSec / 0.02).toInt().coerceAtLeast(0)
            val eIdx = ceil(endSec / 0.02).toInt().coerceAtMost(energies.size)
            if (eIdx <= sIdx) return null

            val count = eIdx - sIdx
            val segDbs = FloatArray(count) { i -> frameDb[sIdx + i] }
            val sorted = segDbs.copyOf().apply { sort() }
            val p90 = sorted[min(count - 1, (count * 0.90).toInt())]
            val gate = max(p90 - ACTIVE_SPAN_DB, ACTIVE_GATE_MIN_DB)

            var activeSum = 0.0
            val activeIndices = IntArray(count)
            var activeCnt = 0
            var allSum = 0.0
            for (i in 0 until count) {
                val fIdx = sIdx + i
                val e = energies[fIdx]
                allSum += e
                if (segDbs[i] >= gate) {
                    activeSum += e
                    activeIndices[activeCnt++] = fIdx
                }
            }
            val meanE = if (activeCnt > 0) activeSum / activeCnt else allSum / count
            val rawDb = (10.0 * log10(max(meanE, MIN_FLOOR_ENERGY))).toFloat().coerceIn(MIN_CLAMP_DB, 0f)
            val activeDb = (rawDb * 10f).roundToInt() / 10f
            val effFrames = if (activeCnt > 0) activeCnt else count
            val activeSec = ((effFrames * 0.02 * 100.0).roundToInt() / 100.0).coerceAtLeast(0.02).toFloat()

            // 取紧邻窗口 [-0.6s, +2.5s] 与宽窗口 [-3.0s, +4.0s] 的传输帧 p15 底噪，兼顾 AGC 跟随速度与连续快语速鲁棒性
            val shortFloor = percentileTransmittedDb(max(0, sIdx - 30), min(energies.size, eIdx + 125), 0.15)
            val wideFloor = percentileTransmittedDb(max(0, sIdx - 150), min(energies.size, eIdx + 200), 0.15)
            val rawFloor = when {
                shortFloor != null && wideFloor != null -> min(shortFloor, wideFloor + 2.0f)
                shortFloor != null -> shortFloor
                wideFloor != null -> wideFloor
                else -> chunkFloorDb
            }.coerceAtMost(activeDb - 1.0f)
            val snrDb = (((activeDb - rawFloor).coerceIn(0f, 55f)) * 10f).roundToInt() / 10f

            val hfLfDb = computeHfLfDb(activeIndices, activeCnt, sIdx, eIdx)
            return SegmentAcoustic(
                activeDb = activeDb,
                activeSec = activeSec,
                localFloorDb = (rawFloor * 10f).roundToInt() / 10f,
                snrDb = snrDb,
                hfLfDb = hfLfDb,
            )
        }

        private fun percentileTransmittedDb(fromIdx: Int, toIdx: Int, q: Double): Float? {
            if (toIdx <= fromIdx) return null
            var n = 0
            for (i in fromIdx until toIdx) if (transmitted[i]) n++
            if (n < 6) return null
            val tmp = FloatArray(n)
            var p = 0
            for (i in fromIdx until toIdx) if (transmitted[i]) tmp[p++] = frameDb[i]
            tmp.sort()
            return tmp[min(n - 1, (n * q).toInt())]
        }

        /** 计算有效语音帧 1000~4000Hz 与 150~800Hz 能量比（dB），最多均匀抽样 36 帧（耗时 <0.3ms） */
        private fun computeHfLfDb(activeIndices: IntArray, activeCnt: Int, fallbackStart: Int, fallbackEnd: Int): Float {
            val total = if (activeCnt > 0) activeCnt else (fallbackEnd - fallbackStart)
            if (total <= 0) return -10f
            val maxFrames = min(total, 36)
            var sumLf = 0.0
            var sumHf = 0.0
            var used = 0
            for (k in 0 until maxFrames) {
                val idx = if (activeCnt > 0) {
                    activeIndices[(k * activeCnt) / maxFrames]
                } else {
                    fallbackStart + (k * total) / maxFrames
                }
                val base = idx * FRAME_LEN
                if (base + FRAME_LEN > pcm16k.size) break
                fftBuf.fill(0f)
                for (i in 0 until FRAME_LEN) {
                    fftBuf[i] = pcm16k[base + i] * hann320[i]
                }
                KaldiFbank.fftPowerSpectrum257(fftBuf, fftRe, fftIm, fftPow)
                // 512 点 FFT @ 16kHz -> 31.25 Hz/bin: 150..800Hz = bins 5..25, 1000..4000Hz = bins 32..128
                var lf = 0.0
                for (b in 5..25) lf += fftPow[b].toDouble()
                var hf = 0.0
                for (b in 32..128) hf += fftPow[b].toDouble()
                sumLf += lf
                sumHf += hf
                used++
            }
            if (used == 0) return -10f
            val ratio = (sumHf / used + 1e-12) / (sumLf / used + 1e-12)
            return ((10.0 * log10(ratio)).toFloat().coerceIn(-35f, 25f) * 10f).roundToInt() / 10f
        }
    }

    fun buildProfile(pcm16k: FloatArray): AudioProfile {
        val energies = Voiceprint.computeFrameEnergies16k(pcm16k)
        val n = energies.size
        val frameDb = FloatArray(n)
        val transmitted = BooleanArray(n)
        var txCount = 0
        for (f in 0 until n) {
            val db = (10.0 * log10(max(energies[f], MIN_FLOOR_ENERGY))).toFloat()
            frameDb[f] = db
            val base = f * FRAME_LEN
            val end = min(pcm16k.size, base + FRAME_LEN)
            var peak = 0f
            for (i in base until end) {
                val a = abs(pcm16k[i])
                if (a > peak) peak = a
            }
            val tx = db > TX_FLOOR_DB && peak > TX_PEAK_MIN
            transmitted[f] = tx
            if (tx) txCount++
        }
        val chunkFloorDb = if (txCount >= 6) {
            val txDbs = FloatArray(txCount)
            var p = 0
            for (f in 0 until n) if (transmitted[f]) txDbs[p++] = frameDb[f]
            txDbs.sort()
            txDbs[min(txCount - 1, (txCount * 0.15).toInt())]
        } else {
            -62.0f
        }
        return AudioProfile(pcm16k, energies, frameDb, transmitted, chunkFloorDb)
    }

    fun isProtectedSpeakerId(rawSpk: String?, roster: Map<String, Speaker>): Boolean {
        if (rawSpk.isNullOrBlank() || rawSpk.startsWith("other_")) return false
        var cur = roster[rawSpk] ?: return false
        val seen = HashSet<String>(4)
        while (cur.mergedInto != null && seen.add(cur.id)) {
            cur = roster[cur.mergedInto] ?: break
        }
        if (!cur.name.isNullOrBlank()) return true
        return roster.values.any { it.mergedInto == cur.id }
    }

    /**
     * 综合局部信噪比、CED-Tiny 空间/发声概率与中高频占比，输出 "near" | "far" | "media"。
     * nearMargin 用作整段预筛时的保守余量（默认 0f；整段拦截传 -0.18f 宁可保留临界低语）。
     */
    fun classifySegment(
        acoustic: SegmentAcoustic,
        chunkMedianHfLfDb: Float = acoustic.hfLfDb,
        chunkP90Db: Float = acoustic.activeDb,
        cedProbs: FloatArray? = null,
        isProtectedSpeaker: Boolean = false,
        nearMargin: Float = 0f,
    ): String {
        val hasCed = cedProbs != null && cedProbs.size >= 526
        var cedNear = 0f
        var cedFar = 0f
        var cedMedia = 0f
        if (hasCed) {
            val p = cedProbs!!
            // 5=Narration/近麦独白, 15=Whispering/耳语, 500=词间干净静音
            cedNear = p[5] + p[15] + p[500]
            // 68..70=Chatter/Crowd/Babble, 507..509=大空间/公共场所/户外底噪, 511..512=混响/回声, 300=远处交通底噪
            cedFar = maxOf(
                p[68],
                maxOf(p[69], p[70]),
                maxOf(p[507], p[508]),
                maxOf(p[509], p[511]),
                maxOf(p[512], p[300]),
            )
            // 524=Television, 525=Radio, 137=Music, 27=Singing, 7=Speech synthesizer
            cedMedia = maxOf(p[524], maxOf(p[525], p[137]), maxOf(p[27], p[7]))
        }

        if (!isProtectedSpeaker) {
            val tinnyMedia = acoustic.hfLfDb >= 3.5f && cedMedia >= 0.10f
            val strongMedia = cedMedia >= 0.40f && acoustic.hfLfDb >= -2.5f && cedNear < 0.08f
            val distantSmallSpeaker = acoustic.hfLfDb >= 6.8f && acoustic.activeDb < -37.0f
            if (tinnyMedia || strongMedia || distantSmallSpeaker) {
                return MEDIA
            }
        }

        val snrTerm = ((acoustic.snrDb - 14.5f) / 6.0f).coerceIn(-2.2f, 2.2f)
        val cedTerm = if (hasCed) {
            0.85f * log10(((cedNear + 0.002f) / (cedFar + 0.002f)).toDouble()).toFloat()
        } else 0f

        val relLoudnessTerm = when {
            acoustic.activeDb >= -26.0f && acoustic.snrDb >= 18.0f -> 0.80f
            chunkP90Db - acoustic.activeDb >= 12.0f && acoustic.snrDb < 14.5f && (!hasCed || cedNear < 0.08f) -> -0.75f
            else -> 0f
        }
        val effortPenalty = if (
            acoustic.hfLfDb - chunkMedianHfLfDb >= 7.5f &&
            acoustic.snrDb < 18.5f &&
            (!hasCed || cedFar >= cedNear)
        ) -0.60f else 0f

        val nearScore = snrTerm + cedTerm + relLoudnessTerm + effortPenalty
        return if (nearScore >= nearMargin) NEAR else FAR
    }

    /** 计算段落组统计基准（中位高频比与 p90 有效分贝），消除衣物遮挡对高频比的全局偏移 */
    fun chunkReferenceStats(acoustics: List<SegmentAcoustic?>): Pair<Float, Float> {
        val valid = acoustics.filterNotNull()
        if (valid.isEmpty()) return -10f to -34f
        val hfSorted = FloatArray(valid.size) { valid[it].hfLfDb }.apply { sort() }
        val dbSorted = FloatArray(valid.size) { valid[it].activeDb }.apply { sort() }
        val medHf = hfSorted[valid.size / 2]
        val p90Db = dbSorted[min(valid.size - 1, (valid.size * 0.90).toInt())]
        return medHf to p90Db
    }

    /**
     * 对短促应答句（<0.7s，未单独跑 CED-Tiny）做同说话人邻近轮次平滑：
     * 若前后 2.5 秒内同一说话人有音量相近（<=4.5dB）的长句，直接继承其远近标签，防止句尾「嗯/好的」闪烁。
     */
    fun smoothShortSegments(
        starts: DoubleArray,
        ends: DoubleArray,
        speakers: Array<String?>,
        acoustics: Array<SegmentAcoustic?>,
        proximities: Array<String?>,
    ) {
        for (i in proximities.indices) {
            if (ends[i] - starts[i] >= 0.7) continue
            val spk = speakers[i]?.takeIf { it.isNotBlank() } ?: continue
            val curDb = acoustics[i]?.activeDb ?: continue
            for (j in proximities.indices) {
                if (i == j || ends[j] - starts[j] < 0.7) continue
                if (speakers[j] != spk) continue
                val gap = when {
                    starts[j] >= ends[i] -> starts[j] - ends[i]
                    starts[i] >= ends[j] -> starts[i] - ends[j]
                    else -> 0.0
                }
                if (gap > 2.5) continue
                val refDb = acoustics[j]?.activeDb ?: continue
                if (abs(curDb - refDb) <= 4.5f && proximities[j] != null) {
                    proximities[i] = proximities[j]
                    break
                }
            }
        }
    }

    /**
     * 上传/直连转写前的整段远近粗筛（复用 Vad 已解码的 pcm16k 与 Silero 人声窗口索引，零二次解码）：
     * - 只要存在任意一段近场人声，或命中本机已保护用户名册声纹（如把设备放桌面上说话的本人），立即放行 KEEP；
     * - 仅当整段 100% 为远场背景杂谈或电子外放，且声纹不匹配已知核心人物时，才返回 FAR_OR_MEDIA。
     */
    fun screenChunkPcm(ctx: Context, pcm16k: FloatArray, speechWinStarts: IntArray): ChunkGate {
        val speechSec = speechWinStarts.size * 512.0 / SAMPLE_RATE
        if (speechSec < Vad.MIN_SPEECH_S) return ChunkGate.NO_VOICE
        if (pcm16k.isEmpty()) return ChunkGate.KEEP

        // 将相邻 <=0.45s 的 32ms Silero 人声窗口合并为连续发声段
        val bursts = ArrayList<Pair<Double, Double>>()
        var curS = speechWinStarts[0]
        var curE = curS + 512
        for (k in 1 until speechWinStarts.size) {
            val st = speechWinStarts[k]
            if (st - curE <= (0.45 * SAMPLE_RATE).toInt()) {
                curE = st + 512
            } else {
                bursts.add(curS.toDouble() / SAMPLE_RATE to curE.toDouble() / SAMPLE_RATE)
                curS = st
                curE = st + 512
            }
        }
        bursts.add(curS.toDouble() / SAMPLE_RATE to curE.toDouble() / SAMPLE_RATE)

        val profile = buildProfile(pcm16k)
        val measured = bursts.mapNotNull { (st, ed) ->
            val padSt = (st - 0.06).coerceAtLeast(0.0)
            val padEd = (ed + 0.06).coerceAtMost(pcm16k.size.toDouble() / SAMPLE_RATE)
            profile.measureSegment(padSt, padEd)?.let { Triple(st, ed, it) }
        }
        if (measured.isEmpty()) return ChunkGate.KEEP

        // 快速放行通道：只要有任何一段明显高信噪比且非小喇叭高频特征的人声，无需跑 ONNX 立即放行
        if (measured.any { (_, _, ac) -> ac.snrDb >= 15.5f && ac.hfLfDb < 3.5f }) {
            return ChunkGate.KEEP
        }

        val (medHf, p90Db) = chunkReferenceStats(measured.map { it.third })
        val candidates = measured
            .sortedByDescending { (_, _, ac) -> ac.activeDb + 0.5f * ac.snrDb }
            .take(4)

        for ((st, ed, ac) in candidates) {
            val clipS = ((st - 0.15).coerceAtLeast(0.0) * SAMPLE_RATE).toInt().coerceIn(0, pcm16k.size)
            val clipE = (min(ed + 0.25, st + 5.0) * SAMPLE_RATE).toInt().coerceIn(clipS, pcm16k.size)
            val cProbs = if (clipE - clipS >= SAMPLE_RATE / 2) {
                runCatching { SceneAndMood.inferCedProbs(ctx, pcm16k, clipS, clipE) }.getOrNull()
            } else null
            val label = classifySegment(
                acoustic = ac,
                chunkMedianHfLfDb = medHf,
                chunkP90Db = p90Db,
                cedProbs = cProbs,
                isProtectedSpeaker = false,
                nearMargin = -0.18f, // 整段拦截留保守余量，临界低语一律放行
            )
            if (label == NEAR) return ChunkGate.KEEP
        }

        // 声纹兜底保护：用户把设备放在桌面上远讲时，声学呈远场，但命中本人/已命名人物声纹则无条件放行
        if (speechSec >= 2.0) {
            val spans = bursts.map { (st, ed) -> Voiceprint.Span(st, ed, "") }
            val matchedKnown = runCatching {
                Voiceprint.matchesProtectedSpeaker(ctx, pcm16k, spans, minSim = 0.58f)
            }.getOrDefault(false)
            if (matchedKnown) return ChunkGate.KEEP
        }

        return ChunkGate.FAR_OR_MEDIA
    }
}
