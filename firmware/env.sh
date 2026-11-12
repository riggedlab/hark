#!/bin/sh
# 用法: ./env.sh <命令>   在 nRF Connect SDK 工具链环境里运行命令
# west 需要在 SDK 工作区内运行，所以这里切到 SDK 目录，项目路径请用绝对路径（脚本会帮你转换）。
NCS_VERSION="${NCS_VERSION:-v3.4.2}"
NCS="${NCS:-/opt/nordic/ncs/$NCS_VERSION}"
HERE="$(cd "$(dirname "$0")" && pwd)"
export ZEPHYR_BASE="$NCS/zephyr"
cd "$NCS" && exec nrfutil sdk-manager toolchain launch --ncs-version "$NCS_VERSION" -- "$@"
