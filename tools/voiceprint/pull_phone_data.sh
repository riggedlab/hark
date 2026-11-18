#!/usr/bin/env bash
# 把手机端 hark 私有数据（录音 chunks、转写 texts、声纹库）拉到本地做声纹评测。
# 前提：手机装的是 debuggable 构建（assembleDebug + 同一 release 签名覆盖安装即可，数据不丢）。
# 用法：tools/voiceprint/pull_phone_data.sh <输出目录>
set -euo pipefail
OUT=${1:?usage: pull_phone_data.sh <out_dir>}
ADB=${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}
PKG=io.github.riggedlab.hark
mkdir -p "$OUT"
# run-as 下用 tar 打包整个 files 目录（排除模型等大文件缓存），一次性流式传回
"$ADB" exec-out run-as "$PKG" tar -cf - -C files chunks texts speakers_local.json speakers_phone.json speakers_mac.json 2>/dev/null \
  | tar -xf - -C "$OUT" || true
echo "chunks: $(ls "$OUT/chunks" 2>/dev/null | wc -l)  texts: $(ls "$OUT/texts" 2>/dev/null | wc -l)"
du -sh "$OUT"
