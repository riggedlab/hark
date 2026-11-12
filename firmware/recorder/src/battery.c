/* 电池电压测量：每 30 秒测一次，打印到串口，并通过 BLE 通知。 */
#include <zephyr/kernel.h>
#include <zephyr/drivers/adc.h>
#include <zephyr/drivers/regulator.h>
#include "app.h"

#define MEASURE_PERIOD_S 30

static const struct adc_dt_spec vbat_adc = ADC_DT_SPEC_GET_BY_IDX(DT_PATH(zephyr_user), 0);
static const struct device *vbat_sw = DEVICE_DT_GET(DT_NODELABEL(vbat_pwr));
static volatile uint16_t last_mv;

static int measure_mv(void)
{
	int16_t raw;
	int32_t mv;
	struct adc_sequence seq = { .buffer = &raw, .buffer_size = sizeof(raw) };

	regulator_enable(vbat_sw);          /* 接通分压电路 */
	k_msleep(10);
	adc_sequence_init_dt(&vbat_adc, &seq);
	int err = adc_read_dt(&vbat_adc, &seq);
	regulator_disable(vbat_sw);         /* 测完断开，省电 */
	if (err) {
		return err;
	}
	mv = raw;
	adc_raw_to_millivolts_dt(&vbat_adc, &mv);
	return mv * 2;                      /* 1/2 分压 */
}

uint16_t battery_mv(void)
{
	return last_mv;
}

static void battery_thread(void *a, void *b, void *c)
{
	if (!adc_is_ready_dt(&vbat_adc) || adc_channel_setup_dt(&vbat_adc) < 0) {
		printk("ERROR: battery ADC init failed\n");
		return;
	}
	for (;;) {
		int mv = measure_mv();
		if (mv > 0) {
			last_mv = mv;
			printk("[battery] %d mV, uptime %llds\n", mv, k_uptime_get() / 1000);
			ble_send_battery(mv);
		}
		k_sleep(K_SECONDS(MEASURE_PERIOD_S));
	}
}

K_THREAD_DEFINE(battery_tid, 1024, battery_thread, NULL, NULL, NULL, 7, 0, 2000);
