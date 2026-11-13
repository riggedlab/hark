#!/bin/sh
# 安装 hark 查询 CLI（只读查询本地语音存档，供 AI Agent 调用）。
#
#   curl -fsSL https://raw.githubusercontent.com/riggedlab/hark/main/tools/install-cli.sh | sh
#
# 可选环境变量：
#   HARK_VERSION      指定版本，如 0.1.2；默认最新 Release
#   HARK_INSTALL_DIR  安装目录，默认 ~/.local/bin
#   HARK_BASE_URL     仅测试用：替换 Release 下载地址
#
# 会校验 Release 里的 SHA256SUMS.txt，不需要 root。Windows 请手动下载 hark-cli-windows-amd64.exe。
set -eu

repo="riggedlab/hark"
install_dir="${HARK_INSTALL_DIR:-$HOME/.local/bin}"

fail() { echo "hark 安装失败：$*" >&2; exit 1; }

case "$(uname -s)" in
	Darwin) os=darwin ;;
	Linux) os=linux ;;
	*) fail "不支持的系统 $(uname -s)，请到 https://github.com/$repo/releases 手动下载" ;;
esac
case "$(uname -m)" in
	x86_64 | amd64) arch=amd64 ;;
	arm64 | aarch64) arch=arm64 ;;
	*) fail "不支持的架构 $(uname -m)" ;;
esac

if [ -n "${HARK_BASE_URL:-}" ]; then
	base="$HARK_BASE_URL"
elif [ -n "${HARK_VERSION:-}" ]; then
	base="https://github.com/$repo/releases/download/v${HARK_VERSION#v}"
else
	base="https://github.com/$repo/releases/latest/download"
fi
asset="hark-cli-$os-$arch"

if command -v sha256sum >/dev/null 2>&1; then
	sha() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum >/dev/null 2>&1; then
	sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
else
	fail "找不到 sha256sum 或 shasum，无法校验下载文件"
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT INT TERM

echo "下载 $asset ..."
curl -fsSL "$base/$asset" -o "$tmp/hark" || fail "下载 $base/$asset 失败"
curl -fsSL "$base/SHA256SUMS.txt" -o "$tmp/sums" || fail "下载校验文件失败"

want="$(awk -v f="$asset" '$2 == f || $2 == "*" f { print $1 }' "$tmp/sums")"
[ -n "$want" ] || fail "校验文件里没有 $asset"
[ "$(sha "$tmp/hark")" = "$want" ] || fail "SHA256 校验不一致，已放弃安装"

mkdir -p "$install_dir"
chmod +x "$tmp/hark"
mv "$tmp/hark" "$install_dir/hark"
echo "已安装：$install_dir/hark（$("$install_dir/hark" --version)）"

case ":$PATH:" in
	*":$install_dir:"*) ;;
	*) echo "提示：$install_dir 不在 PATH 里，请在 shell 配置中加入：export PATH=\"$install_dir:\$PATH\"" ;;
esac
