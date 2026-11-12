/*
 * 音频前处理：80Hz 高通滤波 + 自动增益控制（AGC）+ 峰值限制。
 *
 * - 高通：二阶巴特沃斯，去掉直流、走路震动、衣服摩擦、空调等低频轰鸣（人声基本在 100Hz 以上）。
 * - AGC：只在"像是有人在说话"的帧上调整增益（帧能量比噪声底高 12dB 以上），安静时保持不动，
 *        这样不会把底噪越放越大。变响时快速降增益，变轻时慢慢升（≤ 6dB/秒）。
 * - 峰值限制：放大后若峰值超过 -1dBFS，就立即把这一帧的增益压到刚好不削波；
 *        帧内从上一帧的增益线性过渡到新增益，避免咔哒声。
 */
#include <math.h>
#include <zephyr/sys/util.h>
#include "agc.h"
#include "app.h"

#define HPF_HZ          80.0f
#define TARGET_DBFS     (-28.0f)   /* 说话时希望达到的帧 RMS */
#define GAIN_MIN_DB     0.0f
#define GAIN_MAX_DB     20.0f
#define GAIN_INIT_DB    12.0f      /* 以前固定 ×4 */
#define RISE_DB_PER_FR  (6.0f * FRAME_MS / 1000.0f)   /* 升增益最快 6dB/秒 */
#define FALL_DB_PER_FR  (3.0f)                       /* 降增益很快 */
#define SPEECH_OVER_NF_DB 12.0f
#define PEAK_LIMIT      (0.89f * 32767.0f)          /* -1dBFS */
#define GATE_DB         8.0f       /* 比噪声底高这么多就当作"有声音"，比 AGC 的说话判断更宽松，宁可多发 */

static float b0, b1, b2, a1, a2;     /* 高通系数 */
static float hx1, hx2, hy1, hy2;     /* 高通状态 */
static float gain_db = GAIN_INIT_DB;
static float gain_lin_prev;
static float noise_db = -60.0f;
static uint32_t limited;
static float buf[FRAME_SAMPLES];
static bool voice;

void agc_init(void)
{
	/* RBJ cookbook 高通，Q = 1/sqrt(2) */
	float w0 = 2.0f * 3.14159265f * HPF_HZ / SAMPLE_RATE;
	float alpha = sinf(w0) / (2.0f * 0.70710678f);
	float c = cosf(w0), a0 = 1.0f + alpha;
	b0 = (1.0f + c) / 2.0f / a0;
	b1 = -(1.0f + c) / a0;
	b2 = (1.0f + c) / 2.0f / a0;
	a1 = -2.0f * c / a0;
	a2 = (1.0f - alpha) / a0;
	hx1 = hx2 = hy1 = hy2 = 0;
	gain_db = GAIN_INIT_DB;
	gain_lin_prev = powf(10.0f, gain_db / 20.0f);
}

static inline float db(float lin)
{
	return 20.0f * log10f(lin + 1e-9f);
}

void agc_process(int16_t *pcm, int n)
{
	n = MIN(n, FRAME_SAMPLES);

	/* 1. 高通，同时求能量和峰值 */
	float sum_sq = 0, peak = 0;
	for (int i = 0; i < n; i++) {
		float x = pcm[i];
		float y = b0 * x + b1 * hx1 + b2 * hx2 - a1 * hy1 - a2 * hy2;
		hx2 = hx1; hx1 = x; hy2 = hy1; hy1 = y;
		buf[i] = y;
		sum_sq += y * y;
		float a = fabsf(y);
		if (a > peak) {
			peak = a;
		}
	}
	float rms_db = db(sqrtf(sum_sq / n) / 32768.0f);

	/* 2. 噪声底：跟踪最小值，下降很快、上升很慢（约 1dB/秒） */
	if (rms_db < noise_db) {
		noise_db = rms_db;
	} else {
		noise_db += 1.0f * FRAME_MS / 1000.0f;
	}

	voice = rms_db > noise_db + GATE_DB && rms_db > -80.0f;

	/* 3. 只在说话帧上调整增益 */
	if (rms_db > noise_db + SPEECH_OVER_NF_DB && rms_db > -75.0f) {
		float want = CLAMP(TARGET_DBFS - rms_db, GAIN_MIN_DB, GAIN_MAX_DB);
		if (want > gain_db) {
			gain_db = MIN(want, gain_db + RISE_DB_PER_FR);
		} else {
			gain_db = MAX(want, gain_db - FALL_DB_PER_FR);
		}
	}

	/* 4. 峰值限制：这一帧放大后不能超过 -1dBFS */
	float g = powf(10.0f, gain_db / 20.0f);
	if (peak * g > PEAK_LIMIT) {
		g = PEAK_LIMIT / peak;
		gain_db = db(g);          /* 记住压低后的增益，之后再慢慢升回来 */
		limited++;
	}

	/* 5. 帧内从上一帧增益平滑过渡到这一帧增益 */
	float step = (g - gain_lin_prev) / n;
	float cur = gain_lin_prev;
	for (int i = 0; i < n; i++) {
		cur += step;
		float v = buf[i] * cur;
		pcm[i] = (int16_t)CLAMP(lrintf(v), -32768, 32767);
	}
	gain_lin_prev = g;
}

bool agc_voice(void) { return voice; }
float agc_gain_db(void) { return gain_db; }
float agc_noise_db(void) { return noise_db; }
uint32_t agc_limited_frames(void) { return limited; }
