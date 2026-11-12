#!/usr/bin/env bash
# 一键对齐桌面端与安卓端版本号并运行开源安全审计门禁。
# 用法：./tools/release.sh 0.2.0
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "用法: $0 <版本号，不带 v，例如 0.1.0 或 0.2.0-alpha.1>" >&2
  exit 1
fi

VER="${1#v}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python3 - "$VER" <<'PY'
import json, pathlib, re, sys

ver = sys.argv[1]
if not re.fullmatch(r"\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?", ver):
    sys.exit(f"版本号格式不合法: {ver}")

pkg_path = pathlib.Path("desktop/package.json")
pkg = json.loads(pkg_path.read_text("utf-8"))
pkg["version"] = ver
pkg_path.write_text(json.dumps(pkg, indent=2, ensure_ascii=False) + "\n", "utf-8")

gradle_path = pathlib.Path("android/app/build.gradle.kts")
txt = gradle_path.read_text("utf-8")
m = re.search(r"versionCode\s*=\s*(\d+)", txt)
cur_name = re.search(r'versionName\s*=\s*"([^"]+)"', txt)
if not m or not cur_name:
    sys.exit("未找到 android/app/build.gradle.kts 中的 versionCode / versionName")
next_code = int(m.group(1)) if cur_name.group(1) == ver else int(m.group(1)) + 1
txt = re.sub(r"versionCode\s*=\s*\d+", f"versionCode = {next_code}", txt, count=1)
txt = re.sub(r'versionName\s*=\s*"[^"]+"', f'versionName = "{ver}"', txt, count=1)
gradle_path.write_text(txt, "utf-8")
print(f"已同步版本: desktop={ver}, android={ver} (versionCode={next_code})")
PY

python3 tools/oss_audit.py

echo ""
echo "检查通过。执行以下命令提交并触发 GitHub Release 自动构建："
echo "  git add desktop/package.json android/app/build.gradle.kts"
echo "  git commit -m \"chore(release): v${VER}\""
echo "  git tag v${VER}"
echo "  git push origin main v${VER}"
