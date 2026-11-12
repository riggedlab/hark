# Hark 盲信箱（`server/`）

纯 Go 标准库编写的端到端加密（`/v2`）零知识中转站：
- **零外部依赖**：只用 Go 标准库，静态编译单二进制（可直接裸机跑，也可 Docker 跑）。
- **零知识中转**：不配置、不持有 `E2EE_KEY`，只存取 AES-256-GCM 密文与不透明条目 ID；音频密文在电脑端回传转写结果后**立即删除**。

## 部署方式 A：Docker Compose（推荐）

```sh
(
  cd server
  cp .env.example .env
  # 编辑 .env，填入两个不同的随机字符串 PHONE_TOKEN 与 WORKER_TOKEN
  docker compose up -d --build
)
curl http://127.0.0.1:8000/v2/health   # 返回 {"ok":true}
```

用 Caddy 提供自动 HTTPS（五分钟配好）：

```caddyfile
hark.example.com {
    reverse_proxy 127.0.0.1:8000
}
```

## 部署方式 B：单二进制直接跑（无需 Docker）

```sh
# 在开发机交叉编译 Linux 静态单文件
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o hark-mailbox ./server

# 拷到服务器直接运行
DATA_DIR=/var/lib/hark-mailbox PHONE_TOKEN=... WORKER_TOKEN=... ./hark-mailbox
```
