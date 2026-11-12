#pragma once
#include <stdint.h>
#include <stdbool.h>

/* 80Hz 高通 + 自动增益（带峰值限制），原地处理一帧 16-bit PCM */
void agc_init(void);
void agc_process(int16_t *pcm, int n);

/* 上一帧像不像有声音（比噪声底高 GATE_DB 以上）。用来决定安静时不发送 */
bool agc_voice(void);

/* 统计（给串口打印用） */
float agc_gain_db(void);
float agc_noise_db(void);
uint32_t agc_limited_frames(void);   /* 因为峰值而被压低增益的帧数 */
