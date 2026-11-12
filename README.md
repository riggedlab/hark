# Hark · 谛听

> **Hark (Model `DT-1`)** is an open-source, all-day wearable voice sensor by **RiggedLab** that captures spoken life and turns it into private, structured context for AI models — featuring BLE encrypted audio streaming, a pure-Go zero-knowledge E2EE mailbox (`/v2`), and a native Go + MyGo desktop archive & ASR console.  
> *Note: Documentation and UI are Chinese-first during the current open alpha test. Android is supported now; the native iOS client is planned and will be added in an upcoming release.*

---

> 谛听，伏地而听，辨万物之声。

一个开源的、可全天佩戴或置于桌面的轻量语音采集器（硬件代号 **Hark DT-1**）：把日常交谈整理成按「事件 · 时间 · 地点 · 人物」结构化的个人上下文，供你自己的模型使用。

## 系统架构

```text
Hark DT-1 佩戴端 ──BLE 加密/Opus──▶ 手机端 (Android / iOS 待开发) ──AES-256-GCM (/v2)──▶ 盲信箱 (Go) ◀──长轮询── 桌面管理端 (MyGo) / CLI Worker ──▶ 可插拔 ASR
```

```text
hark/
├── firmware/            # 随身设备固件 (C / Zephyr，蓝牙广播名 Hark DT-1)
├── enclosure/           # 3D 打印外壳模型（开发板验证版 STL）
├── android/             # 安卓原生 App (Kotlin / Compose，包名 io.github.riggedlab.hark)
├── ios/                 # (待开发) iOS 原生 App，后续补入
├── server/              # 云端盲信箱 (纯 Go 标准库，零外部依赖，<15MB 镜像)
├── desktop/             # 电脑管理端 (Go + MyGo + Bun：菜单栏常驻 + 语音存档检索导出 + ASR 接入测活)
├── cmd/hark-worker/     # 无界面命令行版 (单二进制，给 NAS / Linux 服务器常驻运行)
├── internal/
│   ├── protocol/        # 盲信箱 /v2 接口数据结构与服务端实现
│   ├── e2ee/            # AES-256-GCM 封包与文件名 HMAC（与安卓端字节级兼容）
│   ├── asr/             # ASR 接入适配层（OpenAI 兼容接口无声纹自动降级 + Hark 本地声纹增强接口）
│   └── worker/          # 拉信箱 → 内存解密 → 调 ASR → 声纹认人 → 本地 SQLite 存档与导出
├── docs/
│   ├── asr-guides/      # ASR 接入契约、常见服务商预设与 Mac Apple Silicon 本地 MLX + 声纹实战经验
│   └── hardware-pcb-notes.md
└── go.mod
```

## 隐私与数据留存边界

| 环节 | 存什么 | 留存规则 |
|---|---|---|
| **Hark DT-1 设备** | 仅内存缓冲（0.4s 预录环）与蓝牙配对密钥 | 不落盘录音；未完成加密订阅前不开麦 |
| **安卓手机** | 最近 7 天本地 `.opus` 缓存、转写文字、地点记录 | 已处理录音满 7 天自动清理（未上传成功的不误删）；**定位记录只留手机本机，不上传 Hark 信箱**（地名解析走系统定位服务） |
| **盲信箱（`server/`）** | AES-256-GCM 密文与加密状态槽 | 不配置、不持有 `E2EE_KEY`；音频密文被电脑端回传文字后**立即删除**（未取走最长留 7 天，文字密文最长留 30 天） |
| **电脑端（`desktop/` / `hark-worker`）** | 本地 SQLite（`~/hark/hark.db`：文字存档与可选声纹） | 音频在**内存中直接解密转写，不在磁盘生成临时明文录音**；**声纹向量只留本机 SQLite，绝不上传** |

## 直接下载安装（GitHub Releases）

无需在本地配编译环境，直接前往 **[Releases](https://github.com/riggedlab/hark/releases)** 下载最新构建产物：

- **macOS 桌面管理端（内置自动更新）**：`hark-*.dmg`（同时支持 Apple Silicon 与 Intel）
- **Windows 桌面管理端（内置自动更新）**：`hark-setup-*-amd64.exe`
- **Linux 桌面管理端（内置自动更新）**：`hark_*_amd64.deb` / `hark_*_arm64.deb` / `.tar.gz`
- **Android 手机端（内置版本检查，支持覆盖升级）**：`hark-*-android-arm64.apk`
- **iOS 手机端**：待开发，后续版本补齐
- **盲信箱服务端**：Docker 镜像 `ghcr.io/riggedlab/hark-mailbox:latest` 或 `hark-mailbox-linux-*` 单二进制
- **无界面 Worker（NAS / 服务器）**：`hark-worker-*` 单二进制

## 从源码快速跑通

### 1. 部署盲信箱（纯 Go 标准库，Docker 或单文件直跑）

```sh
(
  cd server
  cp .env.example .env
  # 编辑 .env，设置两个不同的随机字符串 PHONE_TOKEN 与 WORKER_TOKEN
  docker compose up -d --build
)
```

### 2. 启动电脑管理端（或无界面 CLI）

**桌面管理端（macOS / Windows / Linux，菜单栏常驻 + 存档导出 + 一键测连通）：**

```sh
(
  cd desktop
  bun install
  bun run dev     # 开发运行；打包安装包执行 bun run build
)
```

打开后在「信箱与存储」点「生成新密钥」并保存（同时复制给手机 App「服务器设置」），在「ASR 接入」选择服务商（如硅基流动、Groq、OpenAI 或自建本地声纹服务），点一下 **「测试连接」** 发送内置双人测试语音即可验真连通性、耗时、时间戳与声纹能力。

**无界面命令行版（适合 NAS / Linux 服务器）：**

```sh
CGO_ENABLED=0 go build -o hark-worker ./cmd/hark-worker
./hark-worker -gen-key   # 生成共享密钥
./hark-worker            # 常驻拉取（加 -once 单次排空队列后退出）
```

### 3. 编译安卓 App 与烧录固件

```sh
# 编译公开版 Debug APK（默认不内置任何私人配置，在「设备状态 → 服务器设置」填入信箱与共享密钥）
(cd android && ./gradlew assembleDebug)

# 烧录 XIAO nRF54L15 Sense 录音固件（需先安装 nRF Connect SDK v3.4.2）
./firmware/flash.sh recorder
```

## 本地自动化验证

在仓库根目录执行：

```sh
# 1. Go 全量单测与全链路 E2EE 集成测试
CGO_ENABLED=0 go test ./...
go test -race ./internal/... ./server

# 2. 桌面端前端类型检查与原生应用打包
(cd desktop && bun run build)

# 3. Android 单元测试
(cd android && ./gradlew testDebugUnitTest)

# 4. 开源隐私与安全门禁扫描
python3 tools/oss_audit.py --strict-git
```

## 版本发布（维护者）

推送 `v*` 标签会自动触发 GitHub Actions 并行打包 macOS / Windows / Linux 桌面安装包、签名 Android APK、交叉编译静态单文件与多架构 GHCR 镜像，校验无误后自动生成 `SHA256SUMS.txt` 并发布 Release：

```sh
./tools/release.sh 0.1.0
git add desktop/package.json android/app/build.gradle.kts
git commit -m "chore(release): v0.1.0"
git tag v0.1.0
git push origin main v0.1.0
```

## 当前进度与后续计划

- [x] 随身固件 v1：16kHz PDM → 80Hz 高通 + 自动增益 → Opus → BLE 加密传输与静音跳帧
- [x] Android 客户端：蓝牙配对重连、端侧 Silero VAD、波形回放、事件/人物/地点时间线
- [x] 纯 Go `/v2` 端到端加密盲信箱 + MyGo 跨平台桌面存档管理端 + 双协议可插拔 ASR 测活
- [ ] **iOS 原生客户端**（待开发：BLE 后台长连、端侧 VAD 与 `/v2` 端到端加密同步，复用同一套硬件与信箱协议）
- [ ] 蓝牙断连时设备端 Flash 离线补录缓存
- [ ] 自制超薄 PCB v1 与正式版外壳

## 第三方代码与许可证

- 本项目主体采用 [MIT License](LICENSE)（Copyright (c) 2026 OWRIG）。
- `firmware/lib/opus`：[libopus](https://opus-codec.org/) v1.5.2（BSD License，见 `firmware/lib/opus/COPYING`）。
- `android/app/src/main/assets/silero_vad.onnx`：[Silero VAD](https://github.com/snakers4/silero-vad)（MIT License）。
- `firmware/blinky`、`firmware/ble-test` 改写自 Zephyr 示例（Apache-2.0 License）。
