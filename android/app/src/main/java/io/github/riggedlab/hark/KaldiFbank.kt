package io.github.riggedlab.hark

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 与 sherpa-onnx / kaldi-native-fbank 严格对齐的 16kHz 80 维 Fbank 特征提取器（适配 3D-Speaker CAM++）：
 * - sample_rate = 16000, normalize_samples = true ([-1, 1])
 * - frame_length = 400 (25ms), frame_shift = 160 (10ms), fft_size = 512
 * - snip_edges = false（边界镜像反射填充）
 * - remove_dc_offset = true, preemph_coeff = 0.97, window = povey
 * - num_mel_bins = 80, low_freq = 20 Hz, high_freq = 7600 Hz (Nyquist - 400)
 * - energy floor = FLT_EPSILON (1.1920929e-7f)
 * - feature_normalize_type = global-mean（按列减去整段均值）
 */
internal object KaldiFbank {
    const val SAMPLE_RATE = 16000
    const val NUM_BINS = 80
    private const val FRAME_LEN = 400
    private const val FRAME_SHIFT = 160
    private const val FFT_N = 512
    private const val HALF_FFT = FFT_N / 2
    private const val PREEMPH = 0.97f
    private const val FLT_EPSILON = 1.1920929e-7f

    private val poveyWindow = FloatArray(FRAME_LEN) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (FRAME_LEN - 1))).pow(0.85).toFloat()
    }

    // 预计算 512 点基 2 FFT 位反转表与旋转因子
    private val bitRev = IntArray(FFT_N) { i ->
        var x = i
        var y = 0
        repeat(9) {
            y = (y shl 1) or (x and 1)
            x = x ushr 1
        }
        y
    }
    private val cosTable = FloatArray(HALF_FFT) { k -> cos(-2.0 * PI * k / FFT_N).toFloat() }
    private val sinTable = FloatArray(HALF_FFT) { k -> sin(-2.0 * PI * k / FFT_N).toFloat() }

    // 稀疏化存储每个 Mel 通道的有效起止 FFT bin 与权重
    private class MelBin(val offset: Int, val weights: FloatArray)

    private val melBins: Array<MelBin> = run {
        fun melScale(freq: Float): Float = (1127.0 * ln(1.0 + freq / 700.0)).toFloat()
        val melLow = melScale(20.0f)
        val melHigh = melScale(7600.0f) // 16000 / 2 - 400
        val melDelta = (melHigh - melLow) / (NUM_BINS + 1)
        val binWidth = SAMPLE_RATE.toFloat() / FFT_N
        val fftMels = FloatArray(HALF_FFT) { k -> melScale(k * binWidth) }

        Array(NUM_BINS) { b ->
            val left = melLow + b * melDelta
            val center = melLow + (b + 1) * melDelta
            val right = melLow + (b + 2) * melDelta
            var first = -1
            var last = -1
            val full = FloatArray(HALF_FFT)
            for (k in 0 until HALF_FFT) {
                val m = fftMels[k]
                val up = (m - left) / (center - left)
                val down = (right - m) / (right - center)
                val w = max(0f, min(up, down))
                if (w > 0f) {
                    if (first < 0) first = k
                    last = k
                    full[k] = w
                }
            }
            if (first < 0) MelBin(0, FloatArray(0))
            else MelBin(first, full.copyOfRange(first, last + 1))
        }
    }

    /**
     * 输入 16kHz 单声道归一化波形（[-1, 1]），输出行优先扁平化 [numFrames * 80] 已减全局均值的 Fbank 特征，
     * 返回 Pair(numFrames, features)。样本不足时返回 (0, FloatArray(0))。
     */
    fun extractGlobalMeanNormalized(samples: FloatArray): Pair<Int, FloatArray> {
        val n = samples.size
        if (n < FRAME_LEN) return 0 to FloatArray(0)
        val numFrames = (n + FRAME_SHIFT / 2) / FRAME_SHIFT
        if (numFrames <= 0) return 0 to FloatArray(0)

        val out = FloatArray(numFrames * NUM_BINS)
        val colSums = DoubleArray(NUM_BINS)
        val frame = FloatArray(FRAME_LEN)
        val re = FloatArray(FFT_N)
        val im = FloatArray(FFT_N)
        val power = FloatArray(HALF_FFT)

        for (m in 0 until numFrames) {
            val start = m * FRAME_SHIFT + FRAME_SHIFT / 2 - FRAME_LEN / 2
            var sum = 0.0
            for (i in 0 until FRAME_LEN) {
                var p = start + i
                while (p < 0 || p >= n) {
                    p = if (p < 0) -p - 1 else 2 * n - 1 - p
                }
                val v = samples[p]
                frame[i] = v
                sum += v
            }

            // 1. 去直流分量
            val mean = (sum / FRAME_LEN).toFloat()
            for (i in 0 until FRAME_LEN) frame[i] -= mean

            // 2. 预加重与 Povey 加窗
            for (i in FRAME_LEN - 1 downTo 1) {
                frame[i] = (frame[i] - PREEMPH * frame[i - 1]) * poveyWindow[i]
            }
            frame[0] = frame[0] * (1f - PREEMPH) * poveyWindow[0]

            // 3. 512 点原位基 2 FFT
            re.fill(0f)
            im.fill(0f)
            for (i in 0 until FRAME_LEN) {
                re[bitRev[i]] = frame[i]
            }
            var len = 2
            while (len <= FFT_N) {
                val half = len ushr 1
                val step = FFT_N / len
                var base = 0
                while (base < FFT_N) {
                    var k = 0
                    var tw = 0
                    while (k < half) {
                        val i1 = base + k
                        val i2 = i1 + half
                        val wr = cosTable[tw]
                        val wi = sinTable[tw]
                        val tr = wr * re[i2] - wi * im[i2]
                        val ti = wr * im[i2] + wi * re[i2]
                        re[i2] = re[i1] - tr
                        im[i2] = im[i1] - ti
                        re[i1] += tr
                        im[i1] += ti
                        k++
                        tw += step
                    }
                    base += len
                }
                len = len shl 1
            }

            for (k in 0 until HALF_FFT) {
                val r = re[k]
                val ii = im[k]
                power[k] = r * r + ii * ii
            }

            // 4. 80 维对数梅尔能量
            val rowOffset = m * NUM_BINS
            for (b in 0 until NUM_BINS) {
                val mb = melBins[b]
                val w = mb.weights
                val off = mb.offset
                var e = 0f
                for (j in w.indices) {
                    e += w[j] * power[off + j]
                }
                val logE = ln(max(e, FLT_EPSILON))
                out[rowOffset + b] = logE
                colSums[b] += logE.toDouble()
            }
        }

        // 5. global-mean CMN：每维减去整段均值
        val colMeans = FloatArray(NUM_BINS) { b -> (colSums[b] / numFrames).toFloat() }
        for (m in 0 until numFrames) {
            val rowOffset = m * NUM_BINS
            for (b in 0 until NUM_BINS) {
                out[rowOffset + b] -= colMeans[b]
            }
        }
        return numFrames to out
    }
}
