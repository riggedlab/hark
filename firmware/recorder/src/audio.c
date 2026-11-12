/* 音频：PDM 麦克风 16kHz → 高通 + 自动增益 → Opus 编码 → BLE 发送。
 * 只在手机订阅了音频时才开麦，省电。
 *
 * 安静时不编码、不发送（省设备和手机的电）：
 * - 每帧都过 AGC，顺便判断"有没有声音"；最近 10 帧里有 3 帧有声音就开始发送
 * - 开始发送时，把之前缓存的 0.4 秒也补发出去，免得丢掉第一个字
 * - 最后一次有声音后再继续发 3 秒，免得把句子中间的停顿切掉
 * - 不发送期间，每秒发一个只有帧序号的"安静声明"，手机据此用静音补齐，时间线不乱
 */
#include <zephyr/kernel.h>
#include <zephyr/audio/dmic.h>
#include <opus.h>
#include "app.h"
#include "agc.h"

#define BLOCK_BYTES  (FRAME_SAMPLES * 2)
#define BLOCK_COUNT  50                   /* 50 × 20ms = 1s 缓冲：开口时要一口气编码 0.4s 缓存，蓝牙拥塞时发送也会等；用完驱动就停 */
#define PREROLL      20                   /* 开口前补发 0.4 秒 */
#define HANG         150                  /* 声音停了以后再发 3 秒 */
#define ONSET_WIN    10                   /* 最近 10 帧里 */
#define ONSET_MIN    3                    /* 有 3 帧有声音就开始发 */
#define KEEPALIVE    50                   /* 安静时每 1 秒声明一次 */
#define STATS_EVERY  250                  /* 每 250 帧 (5 秒) 打印一次统计 */

K_MEM_SLAB_DEFINE_STATIC(pcm_slab, BLOCK_BYTES, BLOCK_COUNT, 4);

static const struct device *dmic = DEVICE_DT_GET(DT_NODELABEL(dmic_dev));

static uint8_t enc_mem[28 * 1024] __aligned(8);
static int16_t ring[PREROLL][FRAME_SAMPLES];   /* 最近 0.4 秒处理过的 PCM，按帧号取模存放 */
static OpusEncoder *enc;

static int mic_config(void)
{
	static struct pcm_stream_cfg stream = {
		.pcm_rate = SAMPLE_RATE, .pcm_width = 16,
		.block_size = BLOCK_BYTES, .mem_slab = &pcm_slab,
	};
	struct dmic_cfg cfg = {
		.io = { .min_pdm_clk_freq = 1000000, .max_pdm_clk_freq = 3500000,
			.min_pdm_clk_dc = 40, .max_pdm_clk_dc = 60 },
		.streams = &stream,
		.channel = { .req_num_streams = 1, .req_num_chan = 1,
			     .req_chan_map_lo = dmic_build_channel_map(0, 0, PDM_CHAN_LEFT) },
	};
	return dmic_configure(dmic, &cfg);
}

static int encoder_init(void)
{
	int size = opus_encoder_get_size(1);
	printk("opus encoder size: %d bytes\n", size);
	if (size > sizeof(enc_mem)) {
		return -ENOMEM;
	}
	enc = (OpusEncoder *)enc_mem;
	int err = opus_encoder_init(enc, SAMPLE_RATE, 1, OPUS_APPLICATION_VOIP);
	if (err != OPUS_OK) {
		return err;
	}
	opus_encoder_ctl(enc, OPUS_SET_BITRATE(OPUS_BITRATE));
	opus_encoder_ctl(enc, OPUS_SET_VBR(1));
	opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(1));
	opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
	opus_encoder_ctl(enc, OPUS_SET_BANDWIDTH(OPUS_BANDWIDTH_WIDEBAND));
	return 0;
}

static void drain_mic(void)
{
	void *buf;
	uint32_t size;

	while (dmic_read(dmic, 0, &buf, &size, 0) == 0) {
		k_mem_slab_free(&pcm_slab, buf);
	}
}

static int encode_send(uint32_t seq, uint8_t *out, uint32_t *bytes, uint32_t *enc_us_sum, uint32_t *enc_us_max)
{
	uint32_t t0 = k_cycle_get_32();
	int n = opus_encode(enc, ring[seq % PREROLL], FRAME_SAMPLES, out, OPUS_MAX_BYTES);
	uint32_t us = k_cyc_to_us_floor32(k_cycle_get_32() - t0);

	if (n < 0) {
		printk("opus error %d\n", n);
		return n;
	}
	*bytes += n;
	*enc_us_sum += us;
	*enc_us_max = MAX(*enc_us_max, us);
	return ble_send_audio((uint16_t)seq, out, n);
}

static void stream_session(void)
{
	uint8_t out[OPUS_MAX_BYTES];
	uint32_t frames = 0, sent = 0, sent_period = 0, bytes = 0, send_err = 0, enc_us_sum = 0, enc_us_max = 0;
	uint32_t seq = 0;          /* 下一帧的帧号（32 位，发出去时取低 16 位） */
	uint32_t next_open = 0;    /* 第一个既没发送、也没声明为安静的帧 */
	uint32_t last_ka = 0;
	uint16_t hist = 0;         /* 最近 ONSET_WIN 帧有没有声音，一位一帧 */
	int hang = 0;              /* > 0 表示正在发送 */

	if (dmic_trigger(dmic, DMIC_TRIGGER_START) < 0) {
		printk("ERROR: mic start failed\n");
		k_msleep(1000);
		return;
	}
	agc_init();
	opus_encoder_ctl(enc, OPUS_RESET_STATE);
	ble_recording(true);
	printk("mic ON\n");

	while (ble_streaming()) {
		void *buf;
		uint32_t size;

		if (dmic_read(dmic, 0, &buf, &size, 500) < 0) {
			/* 缓冲一旦用完，nrfx PDM 驱动会自己停掉且不再恢复，只能重启麦克风。
			 * 卡住的这段按时长跳过帧号，之后的安静声明让手机用静音补齐，时间线不缩短。 */
			printk("mic read timeout, restarting mic\n");
			seq += 500 / FRAME_MS;
			dmic_trigger(dmic, DMIC_TRIGGER_STOP);
			drain_mic();
			if (dmic_trigger(dmic, DMIC_TRIGGER_START) < 0) {
				printk("ERROR: mic restart failed\n");
				k_msleep(1000);
			}
			continue;
		}
		agc_process(buf, size / 2);
		bool v = agc_voice();
		uint32_t cur = seq++;
		memcpy(ring[cur % PREROLL], buf, FRAME_SAMPLES * 2);
		k_mem_slab_free(&pcm_slab, buf);
		frames++;
		hist = ((hist << 1) | v) & ((1u << ONSET_WIN) - 1);

		if (hang > 0) {
			/* 正在发送 */
			if (encode_send(cur, out, &bytes, &enc_us_sum, &enc_us_max) != 0) {
				send_err++;
			}
			sent++;
			sent_period++;
			next_open = cur + 1;
			hang = v ? HANG : hang - 1;
		} else if (__builtin_popcount(hist) >= ONSET_MIN) {
			/* 开口了：先声明缓存之前的帧是安静，再把缓存的帧和这一帧发出去 */
			uint32_t start = cur + 1 > PREROLL ? cur + 1 - PREROLL : 0;
			start = MAX(start, next_open);
			if (start > next_open) {
				ble_send_audio((uint16_t)(start - 1), NULL, 0);
			}
			opus_encoder_ctl(enc, OPUS_RESET_STATE);
			for (uint32_t s = start; s <= cur; s++) {
				if (encode_send(s, out, &bytes, &enc_us_sum, &enc_us_max) != 0) {
					send_err++;
				}
				sent++;
				sent_period++;
			}
			next_open = cur + 1;
			hang = HANG;
		} else if (cur - last_ka >= KEEPALIVE && cur + 1 > PREROLL) {
			/* 安静：声明到缓存之前为止都是安静（缓存里的帧可能还要补发） */
			uint32_t upto = cur + 1 - PREROLL - 1;
			if (upto + 1 > next_open) {
				ble_send_audio((uint16_t)upto, NULL, 0);
				next_open = upto + 1;
			}
			last_ka = cur;
		}

		if (frames % STATS_EVERY == 0) {
			printk("[stats] %u frames, sent %u%%, %u B/s, encode avg %u us max %u us, send errors %u, "
			       "gain %d dB, noise %d dBFS, limited %u\n",
			       frames, sent * 100 / frames, bytes * 1000 / (STATS_EVERY * FRAME_MS),
			       enc_us_sum / MAX(1, sent_period), enc_us_max, send_err,
			       (int)agc_gain_db(), (int)agc_noise_db(), agc_limited_frames());
			bytes = enc_us_sum = enc_us_max = sent_period = 0;
		}
	}

	dmic_trigger(dmic, DMIC_TRIGGER_STOP);
	drain_mic();
	ble_recording(false);
	printk("mic OFF\n");
}

static void audio_thread(void *a, void *b, void *c)
{
	if (!device_is_ready(dmic) || mic_config() < 0) {
		printk("ERROR: mic init failed\n");
		return;
	}
	if (encoder_init() != 0) {
		printk("ERROR: opus init failed\n");
		return;
	}
	printk("audio ready, waiting for phone to subscribe\n");

	for (;;) {
		if (ble_streaming()) {
			stream_session();
		} else {
			k_msleep(100);
		}
	}
}

/* Opus 用变长数组放在栈上，需要较大的栈 */
K_THREAD_STACK_DEFINE(audio_stack, 32 * 1024);
static struct k_thread audio_tid;

void audio_start(void)
{
	k_thread_create(&audio_tid, audio_stack, K_THREAD_STACK_SIZEOF(audio_stack),
			audio_thread, NULL, NULL, NULL, 5, K_FP_REGS, K_NO_WAIT);
}
