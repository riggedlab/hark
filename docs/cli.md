# `hark` 命令行

`hark` 是给本地 AI Agent（Claude Code、Pi、Codex、Hermes 等）用的只读查询工具，读的是电脑端的本地语音存档。它不需要服务器凭据，也不访问钥匙串。

## 安装

在仓库根目录构建，然后把二进制目录加到 PATH：

```sh
CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o ./bin/hark ./cmd/hark
export PATH="$PWD/bin:$PATH"
```

也可以直接从 [Releases](https://github.com/riggedlab/hark/releases) 下载 `hark-*` 单二进制。

## 常用命令

```sh
hark status                                   # 存档概况
hark speakers                                 # 人物 ID 与显示名称
hark timeline --date today                    # 今天的完整对话，按时间正序
hark search "接口" --date 30d --speaker "老王"  # 跨天检索，返回命中片段
hark get 20261112-143000 --json               # 某段录音的完整上下文
```

- `timeline` 默认取今天最近 200 条；`search` 默认取最近 30 天的 30 条。
- 两者都支持 `--date today|yesterday|7d|30d|all|YYYY-MM-DD`、`--speaker`（ID 或名称）、`--limit`、`--offset`。
- `get <name...>` 可一次给多个文件名，`.opus` 后缀可省略。
- 所有查询都支持 `--json`。flags 放在位置参数前后都可以。
- 还没有数据库时会提示「本地暂无录音存档」，不会创建空库。

## 数据目录

查找顺序：

1. `--data-dir`
2. 环境变量 `HARK_HOME`（旧名 `DITING_HOME` 仍可用）
3. 配置 JSON 里的 `data_dir`。配置文件默认 `~/.config/hark/config.json`，可用 `HARK_CONF` 或 `--config` 指定
4. `~/hark`

旧版的 `diting.db` 也能直接读。

## 维护命令（会写数据，不属于只读查询）

- `hark worker [flags]`：和 `hark-worker` 是同一个引擎，flags 也一样。
- `hark gen-key`：生成手机与电脑共用的密钥。

## 接入 Agent 技能

把仓库里的 `skills/hark` 软链到你所用 Agent 的技能目录。如果已经有同名技能，先核对一下，不要覆盖：

```sh
# Claude Code
mkdir -p ~/.claude/skills && ln -s "$PWD/skills/hark" ~/.claude/skills/hark
# Pi
mkdir -p ~/.pi/agent/skills && ln -s "$PWD/skills/hark" ~/.pi/agent/skills/hark
# Codex（以及其他读取 ~/.agents/skills 的 Agent）
mkdir -p ~/.agents/skills && ln -s "$PWD/skills/hark" ~/.agents/skills/hark
```

Hermes 等支持 `SKILL.md` 的 Agent，把同一个目录链接到各自的技能目录即可。
