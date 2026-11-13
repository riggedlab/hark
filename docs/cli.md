# `hark` 命令行与 AI Agent 技能

让 Claude Code、Codex、Pi、Hermes 这类本地 AI Agent 能回答「我今天聊了什么」「上周和老王说的那件事」。

分两部分：
- **`hark` 命令行**：只读查询电脑端的本地语音存档（`~/hark/hark.db`）。它不联网，不需要服务器凭据，也不访问钥匙串。
- **`hark` 技能**（[`skills/hark/SKILL.md`](../skills/hark/SKILL.md)）：告诉 Agent 什么时候、怎么用这个命令行。

前提：电脑端（桌面端或 `hark-worker`）已经在运行，本机已经有转写存档。

## 快速开始（自己动手）

```sh
# 1. 安装命令行（macOS / Linux；会校验 SHA256，装到 ~/.local/bin）
curl -fsSL https://raw.githubusercontent.com/riggedlab/hark/main/tools/install-cli.sh | sh

# 2. 把技能装给你电脑上的所有 Agent（Claude Code、Codex 等）
npx skills add riggedlab/hark -g -y

# 3. 确认能读到存档
hark status
```

然后直接问 Agent：「我今天都聊了什么？」

## 快速开始（让 Agent 帮你装）

把下面这段话发给你的 Agent：

> 帮我安装 Hark 的命令行和技能：先运行 `curl -fsSL https://raw.githubusercontent.com/riggedlab/hark/main/tools/install-cli.sh | sh`，再运行 `npx skills add riggedlab/hark -g -y`，最后运行 `hark status`，把结果告诉我。

## 其他安装方式

- **Windows**：到 [Releases](https://github.com/riggedlab/hark/releases) 下载 `hark-cli-windows-amd64.exe`，改名为 `hark.exe`，放进 PATH 里的某个目录。
- **用 Go 安装**：`go install github.com/riggedlab/hark/cmd/hark@latest`。
- **从源码构建**：`CGO_ENABLED=0 go build -trimpath -o ./bin/hark ./cmd/hark`。
- **指定版本或目录**：安装脚本支持环境变量 `HARK_VERSION=0.1.2`、`HARK_INSTALL_DIR=/some/bin`。
- **只给某一个 Agent 装技能**：`npx skills add riggedlab/hark -g -a claude-code`。不想用 npx，也可以手动把仓库里的 `skills/hark` 目录软链到 Agent 的技能目录，例如 `~/.claude/skills/hark`。

## 更新

```sh
curl -fsSL https://raw.githubusercontent.com/riggedlab/hark/main/tools/install-cli.sh | sh   # 命令行
npx skills update hark -g                                                                     # 技能
```

查看当前版本：`hark --version`。

## 命令

| 命令 | 作用 |
|---|---|
| `hark status` | 存档概况：时间范围、条数 |
| `hark speakers` | 已建档的人物 ID 和显示名称 |
| `hark timeline --date today` | 按时间正序看完整对话，默认取今天最近 200 条 |
| `hark search "接口" --date 30d` | 跨天检索，返回命中片段，默认取最近 30 天的 30 条 |
| `hark get 20261112-143000` | 一段或多段录音的完整上下文，`.opus` 后缀可省略 |

通用参数：
- `--date today|yesterday|7d|30d|all|YYYY-MM-DD`
- `--speaker`：人物 ID 或名称
- `--limit`、`--offset`：翻页
- `--json`：结构化输出，适合 Agent 处理

每个命令的完整参数用 `hark <命令> --help` 查看。

还没有存档时，命令会提示「本地暂无录音存档」，不会创建空库。

## 数据目录

查找顺序：

1. `--data-dir`
2. 环境变量 `HARK_HOME`（旧名 `DITING_HOME` 仍可用）
3. 配置 JSON 里的 `data_dir`。配置文件默认 `~/.config/hark/config.json`，可用 `HARK_CONF` 或 `--config` 指定
4. `~/hark`

旧版的 `diting.db` 也能直接读。

## 维护命令（会写数据，Agent 技能不会调用）

- `hark worker [flags]`：和 `hark-worker` 是同一个引擎，flags 也一样。
- `hark gen-key`：生成手机与电脑共用的密钥。
