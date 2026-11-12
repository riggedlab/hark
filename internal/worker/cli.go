package worker

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"

	"github.com/riggedlab/hark/internal/e2ee"
)

// RunCLI is shared by hark-worker and hark worker. Keep the legacy flags and
// their defaults here so the two entry points cannot drift.
func RunCLI(ctx context.Context, args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("hark-worker", flag.ContinueOnError)
	fs.SetOutput(stderr)
	confPath := fs.String("config", "", "配置文件路径（默认 ~/.config/hark/config.json 或兼容 worker.env）")
	dataDir := fs.String("data-dir", "", "本地 SQLite 存档目录（默认 ~/hark）")
	once := fs.Bool("once", false, "处理完当前信箱队列后立即退出")
	genKey := fs.Bool("gen-key", false, "生成一把新的 32 字节端到端加密密钥并退出")
	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return 0
		}
		return 2
	}
	logger := log.New(stderr, "", log.LstdFlags)
	fail := func(format string, args ...any) int {
		logger.Printf(format, args...)
		return 1
	}
	if *genKey {
		key, err := e2ee.GenerateKey()
		if err != nil {
			return fail("%v", err)
		}
		if _, err := fmt.Fprintln(stdout, key); err != nil {
			return fail("%v", err)
		}
		return 0
	}
	cfg, err := LoadConfig(*confPath, nil)
	if err != nil {
		return fail("读取配置失败: %v", err)
	}
	if *dataDir != "" {
		cfg.DataDir = *dataDir
	}
	if _, err := cfg.ValidateReady(); err != nil {
		return fail("配置不完整: %v（配置文件：%s）", err, DefaultConfigPath())
	}
	store, err := OpenStore(cfg.DataDir)
	if err != nil {
		return fail("打开本地数据库失败: %v", err)
	}
	defer store.Close()
	eng := NewEngine(cfg, store, nil)
	if *once {
		if err := eng.RunOnce(ctx); err != nil {
			return fail("单次运行失败: %v", err)
		}
		return 0
	}
	logger.Printf("hark-worker 启动（ASR=%s，数据库=%s）", cfg.ASR.Provider, store.DBPath())
	eng.Start()
	<-ctx.Done()
	logger.Printf("正在平滑退出...")
	eng.Stop()
	return 0
}
