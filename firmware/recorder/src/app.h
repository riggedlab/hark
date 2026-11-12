#pragma once
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

/* 采样参数 */
#define SAMPLE_RATE    16000
#define FRAME_MS       20
#define FRAME_SAMPLES  (SAMPLE_RATE * FRAME_MS / 1000)   /* 320 */
#define OPUS_BITRATE   16000
#define OPUS_MAX_BYTES 120

/* ble.c */
int ble_init(void);
bool ble_streaming(void);              /* 已加密且手机订阅了音频 */
void ble_recording(bool active);       /* 绿灯：窗口闪烁优先于录音常亮 */
/* 发一帧音频，seq 是帧序号（每 20ms 加一，安静没发的帧也算）。
 * len == 0 表示"安静声明"：seq 以及之前没收到的帧都是安静，手机用静音补上 */
int ble_send_audio(uint16_t seq, const uint8_t *data, size_t len);

/* ble.c 电池 */
int ble_send_battery(uint16_t mv);

/* battery.c */
uint16_t battery_mv(void);

/* audio.c */
void audio_start(void);
