# 开发与发布

## 从源码构建

```sh
# 桌面端：开发运行；打包安装包用 bun run build
(cd desktop && bun install && bun run dev)

# 无界面 Worker
CGO_ENABLED=0 go build -o hark-worker ./cmd/hark-worker

# Android Debug APK（不内置任何私人配置，装好后在「设备状态 → 转写与同步设置」里填写）
(cd android && ./gradlew assembleDebug)

# 固件（需要 nRF Connect SDK v3.4.2，见 firmware/README.md）
./firmware/flash.sh recorder
```

盲信箱见 [server/README.md](../server/README.md)，`hark` 命令行见 [cli.md](cli.md)。

## 本地验证

在仓库根目录执行：

```sh
# 1. Go 单测，以及端到端加密全链路集成测试
CGO_ENABLED=0 go test ./...
go test -race ./internal/... ./server ./cmd/...

# CLI 实跑：用临时目录和合成数据，不碰个人存档
CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /tmp/hark ./cmd/hark
python3 cmd/hark/smoke_test.py /tmp/hark

# 2. 桌面端前端类型检查与打包
(cd desktop && bun run build)

# 3. Android 单元测试
(cd android && ./gradlew testDebugUnitTest)

# 4. 开源前的隐私与安全扫描
python3 tools/oss_audit.py --strict-git
```

## 版本发布（维护者）

推送 `v*` 标签后，GitHub Actions 会自动完成以下步骤：

- 并行打包 macOS、Windows、Linux 桌面安装包
- 签名 Android APK
- 交叉编译静态单文件
- 构建多架构 GHCR 镜像

全部校验通过后，会生成 `SHA256SUMS.txt` 并发布 Release。

```sh
./tools/release.sh 0.1.0
git add desktop/package.json android/app/build.gradle.kts
git commit -m "chore(release): v0.1.0"
git tag v0.1.0
git push origin main v0.1.0
```
