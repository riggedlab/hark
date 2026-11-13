# Hark · 谛听

> An open-source wearable voice recorder (`DT-1`) that turns your spoken day into private, structured context for your own AI. Docs and UI are Chinese-first during the alpha.

> 谛听，伏地而听，辨万物之声。

Hark 是一个开源的随身语音采集器，硬件代号 **DT-1**，可以全天佩戴，也可以放在桌上。它把日常交谈转成文字，按「事件 · 时间 · 地点 · 人物」整理成个人上下文，供你自己的 AI 使用。

目前处于公开 alpha 阶段，手机端只支持 Android。

## 它怎么工作

```text
DT-1 ──蓝牙加密──▶ 手机 App ──端到端加密──▶ 盲信箱 ◀──拉取── 电脑端 ──▶ 语音转文字（ASR）
                                                              │
                                                              ▼
                                                     本地存档 ──▶ 你的 AI Agent
```

- **DT-1**：一块带麦克风的开发板，录音压缩成 Opus 后通过蓝牙加密发给手机。
- **手机 App**：负责接收、切段，然后加密上传。也可以不上传，直接在手机上转文字（见下文）。
- **盲信箱**（`server/`）：一个只转存密文的中转服务器。它没有密钥，自己解不开任何内容。
- **电脑端**：从信箱取回录音，在内存里解密，然后调用你配置的 ASR 服务转成文字，存进本机 SQLite。ASR 指语音转文字服务。
- **AI Agent**：通过 `hark` 命令行只读查询本地存档。

## 隐私与数据留存

| 环节 | 存什么 | 留存规则 |
|---|---|---|
| DT-1 设备 | 只有内存里 0.4 秒的预录缓冲，以及蓝牙配对密钥 | 不保存录音；加密连接建立之前不开麦 |
| 手机 | 最近 7 天的录音缓存、转写文字、地点记录 | 已处理的录音满 7 天自动清理，没上传成功的不会删。定位记录只留在手机上，不上传 |
| 盲信箱 | AES-256-GCM 密文 | 服务器不持有密钥。电脑端取回文字后，音频密文立即删除；没取走的音频最长留 7 天，文字密文最长留 30 天 |
| 电脑 | 本地 SQLite（`~/hark/hark.db`），保存文字存档和可选的声纹 | 音频只在内存里解密转写，磁盘上不出现明文录音；声纹只留在本机 |

> ⚠️ **录音前请征得在场其他人的同意**，并遵守当地关于录音的法律。

## 快速开始

### 1. 做一台 DT-1

需要一块 Seeed XIAO nRF54L15 Sense 和一块 402020 锂电池，焊两根线，装进 3D 打印外壳就行。外壳有两款，外形都是 23.7 × 32.8 × 15 mm：

| [简易款](enclosure/dt1-plain/) | [谛听款](enclosure/dt1-carved/) |
|:---:|:---:|
| <img src="enclosure/dt1-plain/previews/assembly-front.png" width="260" alt="DT-1 简易款外壳渲染图"> | <img src="enclosure/dt1-carved/previews/assembly-front.png" width="260" alt="DT-1 谛听款外壳渲染图"> |
| 平面小牌，推荐黑色普通 PLA | 面盖阴刻谛听兽面纹，推荐木粉 PLA |

<sub>以上是渲染图，不是实物照片。两款都还没按最终文件重新打印验证。</sub>

- 物料、接线和装配：[docs/hardware.md](docs/hardware.md)
- 购物清单：[docs/shopping-list.md](docs/shopping-list.md)
- 外壳：[enclosure/](enclosure/)

> ⚠️ **动手前先读[安全须知](docs/hardware.md#安全须知)。** 自己焊锂电池有起火风险。

烧录固件前，需要先安装 nRF Connect SDK v3.4.2，详见 [firmware/README.md](firmware/README.md)：

```sh
./firmware/flash.sh recorder
```

### 2. 安装软件

[Releases](https://github.com/riggedlab/hark/releases) 页面提供以下构建产物：

| 组件 | 文件 |
|---|---|
| 桌面端 macOS（Apple Silicon / Intel），自动更新 | `hark-*.dmg` |
| 桌面端 Windows，自动更新 | `hark-setup-*-amd64.exe` |
| 桌面端 Linux，自动更新 | `hark_*_amd64.deb` / `hark_*_arm64.deb` / `.tar.gz` |
| Android，可覆盖升级 | `hark-*-android-arm64.apk` |
| 盲信箱 | 镜像 `ghcr.io/riggedlab/hark-mailbox:latest` 或单文件 `hark-mailbox-linux-*` |
| 无界面 Worker（NAS / 服务器用） | `hark-worker-*` |
| AI Agent 查询命令行 | `hark-*` |

### 3. 部署盲信箱

用 Docker 部署，或者直接运行单文件。更多部署方式见 [server/README.md](server/README.md)。

```sh
cd server
cp .env.example .env   # 填入两个不同的随机字符串：PHONE_TOKEN、WORKER_TOKEN
docker compose up -d --build
```

### 4. 配置电脑端

打开桌面端，按顺序做三件事：

1. 在「信箱与存储」里点「生成新密钥」并保存。
2. 把密钥填到手机 App 的「服务器设置」。
3. 在「ASR 接入」里选一个服务商，比如硅基流动、Groq、OpenAI，或者自建的本地声纹服务。然后点「测试连接」，桌面端会发送一段内置的双人测试语音，验证连通性、耗时、时间戳和声纹能力。

ASR 服务的接入说明，以及在 Mac 上本地跑的方法，见 [docs/asr-guides/](docs/asr-guides/)。

如果不想开桌面端，也可以用无界面 Worker，适合放在 NAS 或 Linux 服务器上常驻：

```sh
./hark-worker -gen-key   # 生成共享密钥
./hark-worker            # 常驻拉取；加 -once 则处理完当前队列就退出
```

### 5. 选择手机工作模式

在 App 的「设备状态 → 转写与同步设置」里可以随时切换两种模式，两套凭据各自保留：

- **云端信箱同步**：录音加密上传到盲信箱，由电脑端统一转写和存档。
- **手机直连转写**：不需要信箱，也不需要电脑。手机直接调用 ASR 服务出字，支持火山引擎豆包、OpenAI 兼容接口或自建 HTTP 服务。7 天内切回云端模式时，还没上传的录音会自动补传。

### 6. 接入你的 AI Agent

```sh
hark timeline --date today
hark search "接口" --date 30d --speaker "老王"
```

把 `skills/hark` 软链到 Claude Code、Codex、Pi 等 Agent 的技能目录后，就可以直接问它「我今天聊了什么」。完整用法见 [docs/cli.md](docs/cli.md)。

## 进度与计划

- [x] 固件 v1：16kHz 录音 → 80Hz 高通与固定 +12dB 增益 → Opus 压缩 → 蓝牙加密传输，静音时跳帧
- [x] Android：蓝牙配对重连、端侧人声检测、波形回放、事件/人物/地点时间线
- [x] 端到端加密盲信箱、跨平台桌面端、可插拔 ASR
- [x] DT-1 开发板版硬件方案与两款 3D 打印外壳（实测续航待补）
- [ ] iOS 原生客户端（复用同一套硬件和信箱协议）
- [ ] 蓝牙断连时，设备端先存到 Flash，之后补传
- [ ] 自制超薄 PCB v1

## 仓库结构

```text
firmware/      设备固件（C / Zephyr）
enclosure/     3D 打印外壳
android/       Android App（Kotlin / Compose）
server/        盲信箱（纯 Go 标准库）
desktop/       桌面端（Go + MyGo + Bun）
cmd/           hark 查询命令行、hark-worker
internal/      加密、信箱协议、ASR 适配、Worker 引擎
skills/hark/   本地 AI Agent 查询技能
docs/          硬件、ASR 接入、命令行、开发文档
```

## 参与开发

从源码构建、运行测试和发布版本的方法，见 [docs/development.md](docs/development.md)。

## 许可证

- 本项目主体采用 [MIT License](LICENSE)（Copyright (c) 2026 OWRIG）。
- `firmware/lib/opus`：[libopus](https://opus-codec.org/) v1.5.2，BSD License，见 `firmware/lib/opus/COPYING`。
- `android/app/src/main/assets/silero_vad.onnx`：[Silero VAD](https://github.com/snakers4/silero-vad)，MIT License。
- `firmware/blinky`、`firmware/ble-test` 改写自 Zephyr 示例，Apache-2.0 License。
