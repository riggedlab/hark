#!/usr/bin/env python3
"""开源发布前隐私与安全门禁脚本（确定性检查，严禁在输出中回显任何真实密钥或私人域名明文）。

检查项：
1. 若本机存在 .secrets/（当前目录或主仓库目录），在内存中提取所有非空配置值、域名与证书片段，
   扫描全部 git 跟踪文件，确保零命中（命中时只报文件路径、行号与来源文件名，绝不打印明文）。
2. 扫描跟踪文件中的本机绝对路径（/Users/...、/home/...、C:\\Users\\...）。
3. 扫描跟踪文件中的非本地 IPv4 字面量、私钥/证书头、旧个人仓库地址（OWRIG/diting-wear）。
4. 禁止将敏感或机器专属产物纳入 git 跟踪（.env、.opus、.wav、.db、.gcode、.3mf、.blend、.apk 等）。
5. 检查 android/app/build.gradle.kts 是否在未开启显式开关时默认将 .secrets/ 注入安装包。
6. （加 --strict-git 时）检查 git 历史提交邮箱是否仍含局域网主机名（@*.local）。
"""
from __future__ import annotations

import argparse
import ipaddress
import os
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlparse

SKIP_PREFIXES = ("firmware/lib/opus/", "android/gradle/wrapper/")
BINARY_EXTS = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".stl", ".onnx", ".jar", ".ico", ".opus"}
ALLOWED_SYNTHETIC_AUDIO = {"internal/asr/probe.opus"}
FORBIDDEN_TRACKED_EXTS = {
    ".env", ".opus", ".ogg", ".wav", ".m4a", ".mp3", ".flac",
    ".db", ".sqlite", ".sqlite3", ".gcode", ".3mf", ".blend",
    ".apk", ".aab", ".jks", ".keystore", ".p12", ".pfx", ".pem", ".key",
}

RE_LOCAL_PATH = re.compile(r"(/Users/[A-Za-z0-9._-]+|/home/[A-Za-z0-9._-]+|[A-Za-z]:\\Users\\[A-Za-z0-9._-]+)")
RE_PEM = re.compile(r"-----BEGIN [A-Z0-9 ]*(?:PRIVATE KEY|CERTIFICATE)-----")
RE_OLD_REPO = re.compile(r"github\.com/OWRIG/diting-wear", re.IGNORECASE)
# 只匹配前后不是点号或数字的独立 IPv4（避免把 1.2.3.4.5 或 OID 误判）
RE_IPV4 = re.compile(r"(?<![0-9.])((?:25[0-5]|2[0-4]\d|1?\d?\d)(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3})(?![0-9.])")


def git_tracked_files(repo: Path) -> list[Path]:
    out = subprocess.check_output(["git", "-C", str(repo), "ls-files"], text=True)
    return [repo / line for line in out.splitlines() if line.strip()]


def load_redacted_needles(secrets_dirs: list[Path]) -> list[tuple[str, str]]:
    """返回 [(needle, label)]，needle 只驻留内存，绝不打印。"""
    needles: list[tuple[str, str]] = []
    seen: set[str] = set()
    for sdir in secrets_dirs:
        if not sdir.is_dir():
            continue
        for f in sorted(sdir.iterdir()):
            if not f.is_file() or f.name.startswith(".") or f.suffix.lower() in {".jks", ".keystore", ".p12", ".pfx"}:
                continue
            txt = f.read_text("utf-8", "ignore")
            if "-----BEGIN" in txt:
                body = "".join(
                    line.strip() for line in txt.splitlines()
                    if line.strip() and not line.startswith("-----")
                )
                if len(body) >= 24 and body not in seen:
                    seen.add(body)
                    needles.append((body[:32], f".secrets/{f.name}(cert-body)"))
                continue
            for raw_line in txt.splitlines():
                line = raw_line.strip()
                if not line or line.startswith("#"):
                    continue
                val = line.split("=", 1)[1].strip().strip("\"'") if "=" in line else line
                if len(val) >= 6 and val not in ("127.0.0.1", "localhost") and val not in seen:
                    seen.add(val)
                    needles.append((val, f".secrets/{f.name}"))
                if "://" in val:
                    host = urlparse(val).hostname or ""
                    if host and "." in host and host not in ("127.0.0.1", "localhost"):
                        if host not in seen:
                            seen.add(host)
                            needles.append((host, f".secrets/{f.name}(host)"))
                        parts = host.split(".")
                        if len(parts) >= 3:
                            root_domain = ".".join(parts[-2:])
                            if len(root_domain) >= 6 and root_domain not in seen:
                                seen.add(root_domain)
                                needles.append((root_domain, f".secrets/{f.name}(domain)"))
    return needles


def is_allowed_ip(ip_str: str) -> bool:
    try:
        ip = ipaddress.ip_address(ip_str)
    except ValueError:
        return True
    return ip.is_loopback or ip.is_unspecified or ip_str in {"255.255.255.255", "192.0.2.1", "198.51.100.1", "203.0.113.1"}


def main() -> int:
    ap = argparse.ArgumentParser(description="Open-source privacy & security audit gate")
    ap.add_argument("--repo", default=".", help="Git repository path")
    ap.add_argument("--secrets-dir", action="append", default=[], help="Extra .secrets dir to cross-check")
    ap.add_argument("--strict-git", action="store_true", help="Also verify git history & git user.email")
    args = ap.parse_args()

    repo = Path(args.repo).resolve()
    default_secrets = [
        repo / ".secrets",
        repo.parent / "record" / ".secrets",
        repo.parent.parent / "record" / ".secrets",
    ] + [Path(p).expanduser().resolve() for p in args.secrets_dir]
    needles = load_redacted_needles(default_secrets)

    findings: list[str] = []
    files = git_tracked_files(repo)

    for path in files:
        rel = path.relative_to(repo).as_posix()
        if rel.startswith(SKIP_PREFIXES) or not path.exists():
            continue

        ext = path.suffix.lower()
        is_env_variant = path.name == ".env" or (
            path.name.startswith(".env.") and not path.name.endswith(".example")
        )
        if is_env_variant or (
            ext in FORBIDDEN_TRACKED_EXTS
            and not rel.endswith(".env.example")
            and rel not in ALLOWED_SYNTHETIC_AUDIO
        ):
            findings.append(f"[FORBIDDEN_FILE] {rel}: 敏感或本机专属文件不应纳入 git 跟踪")
            continue
        if path.name == "slicer.log":
            findings.append(f"[FORBIDDEN_FILE] {rel}: 切片日志包含本机路径，不应纳入 git 跟踪")
            continue
        if ext in BINARY_EXTS:
            continue

        # 审计脚本自身包含规则正则字面量，跳过自身模式匹配（但仍做 .secrets 交叉扫描）
        is_self = rel == "tools/oss_audit.py"
        text = path.read_text("utf-8", "ignore")

        for lineno, line in enumerate(text.splitlines(), 1):
            for needle, label in needles:
                if needle in line:
                    findings.append(f"[SECRET_LEAK] {rel}:{lineno}: 命中本地私密信息来源 <{label}>（内容已脱敏隐藏）")

            if is_self:
                continue

            if RE_LOCAL_PATH.search(line):
                findings.append(f"[LOCAL_PATH] {rel}:{lineno}: 含本机用户目录绝对路径")
            if RE_PEM.search(line):
                findings.append(f"[PEM_BLOCK] {rel}:{lineno}: 含证书或私钥块头")
            if RE_OLD_REPO.search(line):
                findings.append(f"[OLD_REPO_URL] {rel}:{lineno}: 仍引用旧个人仓库地址")
            for m in RE_IPV4.finditer(line):
                ip_s = m.group(1)
                # 排除类似 1.31.1 或紧邻字母的版本号误报，仅检出真实 IP 语境
                if not is_allowed_ip(ip_s):
                    findings.append(f"[NON_LOCAL_IP] {rel}:{lineno}: 含非回环 IPv4 地址（已脱敏）")

    gradle = repo / "android" / "app" / "build.gradle.kts"
    if gradle.exists():
        gtxt = gradle.read_text("utf-8", "ignore")
        if ".secrets/" in gtxt and "devSecrets" not in gtxt:
            findings.append(
                "[APK_SECRET_INJECTION] android/app/build.gradle.kts: 默认无条件读取 .secrets/ 注入 BuildConfig，公开发布包会泄露私人服务器与 Token"
            )

    if args.strict_git:
        authors = subprocess.check_output(
            ["git", "-C", str(repo), "log", "--all", "--format=%ae %ce"], text=True
        )
        if ".local" in authors:
            findings.append("[GIT_HISTORY_EMAIL] git 历史提交中包含局域网主机名邮箱 (@*.local)，请使用干净首提发版")
        if os.environ.get("CI") != "true":
            cfg_email = subprocess.run(
                ["git", "-C", str(repo), "config", "user.email"], capture_output=True, text=True
            ).stdout.strip()
            if not cfg_email or ".local" in cfg_email:
                findings.append("[GIT_CONFIG_EMAIL] 当前仓库 git user.email 为空或仍为 @*.local，提交前请显式配置公开邮箱")

    if findings:
        print(f"OSS_AUDIT_FAILED ({len(findings)} 项待处理):")
        for item in findings:
            print(" -", item)
        return 1

    print(f"OSS_AUDIT_OK (已扫描 {len(files)} 个跟踪文件，交叉比对 {len(needles)} 项本地私密指纹，0 泄露)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
