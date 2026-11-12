// Hark 无界面命令行拉取与转写端（适用于 NAS、Linux 服务器或容器环境）。
package main

import (
	"context"
	"os"
	"os/signal"
	"syscall"

	"github.com/riggedlab/hark/internal/worker"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	code := worker.RunCLI(ctx, os.Args[1:], os.Stdout, os.Stderr)
	stop()
	os.Exit(code)
}
