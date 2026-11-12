/* 全天语音记录器 —— 录音 + Opus 编码 + BLE 实时发送 */
#include <zephyr/kernel.h>
#include "app.h"

int main(void)
{
	printk("\n=== Hark DT-1 recorder start ===\n");
	if (ble_init() != 0) {
		printk("ERROR: BLE init failed\n");
		return 0;
	}
	audio_start();
	return 0;
}
