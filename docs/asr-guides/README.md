# ASR 语音转写接入与本地部署指南

Hark 桌面端（`desktop/`）与无界面 Worker（`cmd/hark-worker/`）内置了 **2 种标准语音转写协议**，不在主仓库捆绑特定 Python 环境或大模型权重。你可以按自己的硬件条件二选一：

---

## 方式一：OpenAI 兼容接口（零运维，填 Key 即用）

支持所有兼容 `POST {BaseURL}/v1/audio/transcriptions` 的云端或本地服务。Hark 会自动把 `.opus` 封装重命名为 `.ogg` 上传，并在模型不支持 `verbose_json` 时自动回退为 `json`、从本地 Ogg 页尾计算精确时长。

| 服务商 | Base URL | 推荐模型名 | 特点 |
|---|---|---|---|
| **硅基流动 SiliconFlow** | `https://api.siliconflow.cn/v1` | `FunAudioLLM/SenseVoiceSmall` | 中文口语速度快、成本低 |
| **Groq** | `https://api.groq.com/openai/v1` | `whisper-large-v3-turbo` | 响应极快，支持分段时间戳 |
| **OpenAI** | `https://api.openai.com/v1` | `whisper-1` | 通用多语种与分段时间戳稳定 |
| **阿里云百炼** | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `paraformer-realtime-v2` | 中文专有名词表现好 |
| **本地 faster-whisper** | `http://127.0.0.1:8000/v1` | `Systran/faster-whisper-large-v3` | 本地离线 Docker 一键部署 |

> **能力自适应说明**：三方 OpenAI 兼容接口通常只返回文字和分段时间戳、不返回声纹向量。Hark 检测到响应无声纹时，**自动跳过跨录音认人**，仅按时间线存档分段文字，不会报错或卡住同步。

---

## 方式二：Hark 本地增强接口（带声纹跨录音认人）

如果你希望在 Mac / 本地工作站离线转写，并自动区分「谁说了哪句话」、在手机端给人物改名与合并，可以自建一个最小 HTTP 服务，在 Hark 桌面端选择 **「Hark 增强接口 (声纹)」** 并填入地址（如 `http://127.0.0.1:8765`）。

### 1. 健康检查 `GET /health`

```json
{
  "ok": true,
  "model": "Qwen3-ASR-1.7B"
}
```

### 2. 转写与声纹接口 `POST /transcribe`

请求：`multipart/form-data`，文件字段名 `file`（16kHz 单声道 Ogg Opus）。

响应 JSON（`speaker` 支持整数如 `0, 1` 或字符串如 `"s0"`）：

```json
{
  "text": "你好，收到。",
  "duration_s": 3.45,
  "speech_s": 3.10,
  "engine": "mlx-qwen3",
  "model": "Qwen3-ASR-1.7B",
  "embedding_model": "3dspeaker-cam++",
  "segments": [
    {"start": 0.0, "end": 1.65, "text": "你好，", "speaker": "s0"},
    {"start": 1.85, "end": 3.40, "text": "收到。", "speaker": "s1"}
  ],
  "speakers": [
    {"speaker": "s0", "speech_s": 1.65, "embedding": [0.012, -0.045, "... 向量数组 ..."]},
    {"speaker": "s1", "speech_s": 1.55, "embedding": [-0.031, 0.088, "... 向量数组 ..."]}
  ]
}
```

- **`speakers[].embedding`**：只存入电脑本机 SQLite（`~/hark/hark.db`），**绝不上传盲信箱**。
- **`embedding_model`**（推荐填写）：标记声纹模型名称（如 `"3dspeaker-cam++"`）。Hark 按模型名称隔离声纹比对空间，日后更换声纹模型不会与历史声纹串味。

---

## 实战调优经验

- **[Apple Silicon 本地 Qwen3-ASR + 3D-Speaker 实战笔记](apple-silicon-mlx.md)**：包含全天随身录音场景下踩出来的 **VAD 25 秒合并防碎段**、**防复读幻觉熔断回退**、**工位远场与随身近场音量统一线性抬升**、**必须从原始波形提声纹** 4 条核心经验。
