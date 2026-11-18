#!/usr/bin/env bash
# 用 release 签名打 debuggable 包覆盖安装到手机（数据保留），可选推送声纹种子，然后拉起 App。
# 用法: tools/voiceprint/install_phone.sh [voice_seed.json]
# 签名信息从 SIGNING_ENV（默认 ../record/.secrets/hark-android-signing.env）读取，不落盘不回显。
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ADB=${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}
PKG=io.github.riggedlab.hark
SIGNING_ENV=${SIGNING_ENV:-$HOME/personal/record/.secrets/hark-android-signing.env}
set -a; source "$SIGNING_ENV"; set +a
export ANDROID_KEYSTORE_PATH=${ANDROID_KEYSTORE_PATH:-$(dirname "$SIGNING_ENV")/hark-release.jks}
"$ADB" get-state >/dev/null 2>&1 || { echo "手机未连接（adb get-state 失败）"; exit 1; }
(cd "$ROOT/android" && ./gradlew -q :app:assembleDebug 2>&1 | grep -v "SDK XML\|SDK processing" || true)
"$ADB" install -r "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
if [ -n "${1:-}" ]; then
  "$ADB" push "$1" /data/local/tmp/voice_seed.json >/dev/null
  "$ADB" shell "run-as $PKG sh -c 'cat /data/local/tmp/voice_seed.json > files/voice_seed.json'"
  "$ADB" shell rm /data/local/tmp/voice_seed.json
  echo "种子已推送：$("$ADB" shell run-as $PKG ls -la files/voice_seed.json)"
fi
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
"$ADB" shell dumpsys package "$PKG" | grep -m1 lastUpdateTime
