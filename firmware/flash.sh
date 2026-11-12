#!/bin/sh
# 用法: ./flash.sh <项目目录名>      例: ./flash.sh mic-level
# 编译并烧录到 XIAO nRF54L15（板子用 USB 插在 Mac 上）
set -e
F="$(cd "$(dirname "$0")" && pwd)"
APP="$1"
[ -d "$F/$APP" ] || { echo "找不到项目目录: $APP"; exit 1; }
"$F/env.sh" west build -b xiao_nrf54l15/nrf54l15/cpuapp -d "$F/build-$APP" "$F/$APP"
"$F/env.sh" pyocd flash -t nrf54l "$F/build-$APP/$APP/zephyr/zephyr.hex"
