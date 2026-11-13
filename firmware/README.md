# 固件（XIAO nRF54L15 Sense）

## 环境
- nRF Connect SDK v3.4.2，默认从 `/opt/nordic/ncs/v3.4.2` 读取（可通过环境变量 `NCS` / `NCS_VERSION` 覆盖；中国大陆可用 `nrfutil sdk-manager install v3.4.2 --region cn` 安装）
- 烧录：用板载的 CMSIS-DAP 加 pyocd（工具链自带）。openocd 没装，用不到
- 串口：`/dev/cu.usbmodem*`，115200

## 常用命令
```sh
./flash.sh mic-level                      # 编译并烧录某个项目
./env.sh python3 $PWD/serial_read.py 10   # 读 10 秒串口输出
./env.sh pyocd reset -t nrf54l            # 重启板子
```

## 项目
| 目录 | 作用 | 状态 |
|---|---|---|
| blinky | 绿灯快闪（0.1 秒切换一次） | ✅ 已验证 |
| mic-level | PDM 麦克风音量表，从串口输出 | ✅ 已验证（增益偏低，峰值约 -17 dBFS） |
| ble-test | BLE 心率示例，设备名 "Hark DT-1" | ✅ 已验证，nRF Connect 能连上并收到数据 |
| **recorder** | **录音固件**：PDM 16kHz → 80Hz 高通 + 固定 +12dB 增益（峰值限制 -1dBFS）→ Opus（定点、16kbps VBR、复杂度 1）→ BLE 通知 | ✅ 0 丢包，约 1.6KB/s，编码 4.7ms/帧 |

## 录音固件 BLE 协议
- 服务 `5a1d0001-7e3c-4b8a-9f00-524947000000`
- 音频特征 `5a1d0002-…`（notify）：每个包 = `[seq 低][seq 高][一帧 Opus 20ms]`
  - `seq` 是**帧序号**，每 20ms 加一，安静时没发的帧也算
  - **安静时不发送**：只有 2 字节 `[seq 低][seq 高]` 的包是"安静声明"，表示 seq 以及之前没收到的帧都是安静，手机用静音补齐。安静时每秒发一次
  - 有声音（最近 10 帧里有 3 帧比噪声底高 8dB）时开始发送，先补发之前缓存的 0.4 秒；声音停了以后再发 3 秒
  - 有内容的包前面如果缺帧，就是真的丢包
- 电池特征 `5a1d0003-…`（read + notify）：uint16 小端，单位 mV，每 30 秒更新一次
- 音频 CCC 和电池 CCC 的读写分别要求 `BT_GATT_PERM_READ_ENCRYPT` / `BT_GATT_PERM_WRITE_ENCRYPT`；电池值读取要求 `BT_GATT_PERM_READ_ENCRYPT`。未加密不能订阅、不能读电量，也不会开麦（恢复过的 CCC 同样受加密状态约束）
- 手机/电脑完成配对、加密并订阅后板子才开麦；需要 MTU ≥ ~125（macOS 会协商到 247）。音频包和安静声明格式不变
- Mac 测试接收：`uv run --script tools/ble_receiver.py 20 out.wav`（终端 App 要有蓝牙权限）

## 配对与重连
1. 在 App 开始录音，首次使用会提示「请按一下板子上的按键」。短按板子的用户按键（`sw0`，稳定 30ms 去抖），打开 60 秒配对窗口；绿灯每 0.25 秒切换一次。已有连接会断开，给新手机留出唯一连接位置。
2. 窗口广播包含 128-bit service data：服务 UUID `5a1d0001-7e3c-4b8a-9f00-524947000000` + 单字节 `01`。窗口外广播恢复普通服务 UUID，不含此标记；开关窗口都会重启广播。App 没有保存地址时只扫描此标记。
3. App 先调用系统 `createBond`，等待绑定成功才保存地址、协商 MTU、发现服务并订阅。系统若弹出配对提示需确认。窗口外 `pairing_accept` 拒绝新配对，但已配对设备可直接用保存的密钥恢复加密。
4. 只有新配对成功并持久化后才删除其它 bond；误按按键不会清掉原手机。`BT_MAX_PAIRED=2` 只是给替换过程预留一个位置，不自动覆盖最旧密钥。配对失败保留另一部手机的配对。
5. 窗口外未加密连接最多占用 15 秒，窗口内则到窗口结束。加密失败立即断开。窗口结束后绿灯恢复「开麦常亮、否则灭」。
6. 更换手机、系统忘记密钥或设备已与电脑配对时，在状态面板点「重新配对」，再按板子按键。App 会断开、清地址，并尝试移除系统 bond；系统不允许移除时可在蓝牙设置里手动忘记。电脑配对成功会顶掉手机配对。

采用 `CONFIG_BT_SMP=y`、`CONFIG_BT_SMP_SC_PAIR_ONLY=y`、`CONFIG_BT_SMP_APP_PAIRING_ACCEPT=y`、`CONFIG_BT_BONDABLE=y`：仅 LE Secure Connections Just Works。没有显示屏/口令输入，不启用强制认证 Level 4 的 `CONFIG_BT_SMP_SC_ONLY`；Just Works 提供链路加密，不提供 MITM 身份校验。`CONFIG_BT_SMP_ALLOW_UNAUTH_OVERWRITE=y` 让同一手机忘记密钥后也能重新配对，所有新配对仍受按键窗口限制。

### 密钥存储与程序边界
NCS v3.4.2 此板已通过 `nrf54l15_cpuapp_partition.dtsi` 提供 `storage_partition`。recorder overlay 保留其地址 `0x174000..0x17cfff`（36 KiB），并把未使用的 MCUboot/双槽布局替换成地址 `0x000000..0x173fff` 的裸机程序分区，仍从 0 启动。`CONFIG_USE_DT_CODE_PARTITION=y` 约束链接边界，程序不能增长到 settings 区。

使用 `CONFIG_BT_SETTINGS=y`、`CONFIG_SETTINGS=y`、`CONFIG_FLASH=y`、`CONFIG_FLASH_MAP=y`、`CONFIG_FLASH_PAGE_LAYOUT=y`、`CONFIG_ZMS=y`、`CONFIG_SETTINGS_ZMS=y`，在 RRAM 上用 ZMS 保存密钥；`bt_enable()` 后、广播前调用 `settings_load()`。断电重启会恢复配对。HEX 不包含 settings 区；工具链自带 pyOCD 0.42.0 的 `chip_erase` 默认是 `sector`，现有 `flash.sh` 默认只擦写程序涉及的扇区。也可以显式指定：

```sh
# 仅在需要烧录时执行；整片擦除/recover 仍会清空配对。
firmware/env.sh pyocd flash -t nrf54l -e sector "$PWD/firmware/build-recorder/recorder/zephyr/zephyr.hex"
```

构建后可运行只读检查：`python3 firmware/recorder/verify_storage.py firmware/build-recorder/recorder/zephyr`，核对链接边界和 HEX 确实不含配对分区。

## App 前台实时状态
「正在录上」来自 `ChunkRecorder` 的成功文件写入时间：音频每 50 帧成页并 flush，安静声明立即 flush；只收到包、重复旧包、写入异常或尚在内存中凑页都不能刷新写入时间。最近 2 秒没写入就显示「没有收到数据」。整段安静删除、5 分钟分段和上传前整段 VAD 的规则保持不变。

Activity 可见时另开低优先级线程，用流式 `MediaCodec` 解 Opus；仅内存中的 5 帧分析队列，积压丢旧帧，超过 200ms 的分析帧丢弃，绝不等待分析才写文件。解码后的电平最多约 10Hz 刷新；独立 Silero session 使用同一 assets 模型，16kHz、512 + 64 采样、门限 0.5，最近 1 秒有过人声窗口才显示「检测到人声」。切后台释放解码器和该 session；分析不可用时仅保留写入状态，不影响录音。不保存、不记录实时分析音频和结果，也不实时转文字。

## libopus
- `lib/opus` 是 v1.5.2 源码，`lib/opus.cmake` 定义了编译清单和宏（FIXED_POINT、OPUS_ARM_INLINE_EDSP）
- 打开 EDSP 后编码耗时从 11ms 降到 4.7ms，复杂度 3 降到 1 后音质听不出区别

## 电池
- 402020 电池焊在背面的 BAT 焊盘上：USB 口朝上时，**左边是负极（黑线）**，**右边是正极（红线）**。右下角的 N1/N2 是 NFC 焊盘，不要焊错
- 电压：ADC 通道 7 (P1.14) 经过 1/2 分压，`vbat_pwr`（P1.15）只在测量时接通
- 接上电池后，插 USB 充电时红灯会闪；不接电池时红灯常亮
