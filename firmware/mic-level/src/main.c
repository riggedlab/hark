/* 麦克风音量表：16kHz 单声道，每 100ms 打印一次音量条。 */
#include <zephyr/kernel.h>
#include <zephyr/audio/dmic.h>
#include <zephyr/drivers/gpio.h>
#include <math.h>
#include <stdlib.h>

#define RATE        16000
#define BLOCK_BYTES (2 * RATE / 10)          /* 100ms, 16-bit */
#define BLOCK_COUNT 4
K_MEM_SLAB_DEFINE_STATIC(slab, BLOCK_BYTES, BLOCK_COUNT, 4);

static const struct gpio_dt_spec led = GPIO_DT_SPEC_GET(DT_ALIAS(led0), gpios);

int main(void)
{
	const struct device *dmic = DEVICE_DT_GET(DT_NODELABEL(dmic_dev));

	gpio_pin_configure_dt(&led, GPIO_OUTPUT_INACTIVE);
	k_msleep(1000);
	printk("\n=== mic-level start ===\n");

	if (!device_is_ready(dmic)) {
		printk("ERROR: dmic not ready\n");
		return 0;
	}

	struct pcm_stream_cfg stream = {
		.pcm_rate = RATE, .pcm_width = 16,
		.block_size = BLOCK_BYTES, .mem_slab = &slab,
	};
	struct dmic_cfg cfg = {
		.io = { .min_pdm_clk_freq = 1000000, .max_pdm_clk_freq = 3500000,
			.min_pdm_clk_dc = 40, .max_pdm_clk_dc = 60 },
		.streams = &stream,
		.channel = { .req_num_streams = 1, .req_num_chan = 1,
			     .req_chan_map_lo = dmic_build_channel_map(0, 0, PDM_CHAN_LEFT) },
	};

	int ret = dmic_configure(dmic, &cfg);
	if (ret == 0) {
		ret = dmic_trigger(dmic, DMIC_TRIGGER_START);
	}
	if (ret < 0) {
		printk("ERROR: dmic start failed: %d\n", ret);
		return 0;
	}

	for (uint32_t n = 0;; n++) {
		void *buf;
		uint32_t size;

		ret = dmic_read(dmic, 0, &buf, &size, 1000);
		if (ret < 0) {
			printk("ERROR: read failed: %d\n", ret);
			continue;
		}

		int16_t *s = buf;
		int cnt = size / 2;
		int64_t sum = 0, sq = 0;
		int peak = 0;

		for (int i = 0; i < cnt; i++) {
			sum += s[i];
		}
		int mean = sum / cnt;          /* 去掉直流偏置 */
		for (int i = 0; i < cnt; i++) {
			int v = s[i] - mean;
			sq += (int64_t)v * v;
			if (abs(v) > peak) {
				peak = abs(v);
			}
		}
		k_mem_slab_free(&slab, buf);

		int rms = (int)sqrt((double)sq / cnt);
		/* 约 20dB 每 10 格：dBFS 从 -70 到 0 映射到 0..50 格 */
		double db = 20.0 * log10((rms + 1) / 32768.0);
		int bars = (int)((db + 70.0) * 50.0 / 70.0);
		bars = CLAMP(bars, 0, 50);

		char bar[51];
		for (int i = 0; i < 50; i++) {
			bar[i] = i < bars ? '#' : '.';
		}
		bar[50] = '\0';
		printk("%5d dB  rms=%5d peak=%5d |%s|\n", (int)db, rms, peak, bar);

		gpio_pin_set_dt(&led, bars > 25);   /* 声音大时灯亮 */
	}
}
