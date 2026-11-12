// Hark 无界面命令行拉取与转写端（适用于 NAS、Linux 服务器或容器环境）。
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os/signal"
	"syscall"

	"github.com/riggedlab/hark/internal/e2ee"
	"github.com/riggedlab/hark/internal/worker"
)

func main() {
	var (
		confPath = flag.String("config", "", "配置文件路径（默认 ~/.config/hark/config.json 或兼容 worker.env）")
		dataDir  = flag.String("data-dir", "", "本地 SQLite 存档目录（默认 ~/hark）")
		once     = flag.Bool("once", false, "处理完当前信箱队列后立即退出")
		genKey   = flag.Bool("gen-key", false, "生成一把新的 32 字节端到端加密密钥并退出")
	)
	flag.Parse()

	if *genKey {
		k, err := e2ee.GenerateKey()
		if err != nil {
			log.Fatal(err)
		}
		fmt.Println(k)
		return
	}

	cfg, err := worker.LoadConfig(*confPath, nil)
	if err != nil {
		log.Fatalf("读取配置失败: %v", err)
	}
	if *dataDir != "" {
		cfg.DataDir = *dataDir
	}
	if _, err := cfg.ValidateReady(); err != nil {
		log.Fatalf("配置不完整: %v（配置文件：%s）", err, worker.DefaultConfigPath())
	}

	store, err := worker.OpenStore(cfg.DataDir)
	if err != nil {
		log.Fatalf("打开本地数据库失败: %v", err)
	}
	defer store.Close()

	eng := worker.NewEngine(cfg, store, nil)
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	if *once {
		if err := eng.RunOnce(ctx); err != nil {
			log.Fatalf("单次运行失败: %v", err)
		}
		return
	}

	log.Printf("hark-worker 启动（ASR=%s，数据库=%s）", cfg.ASR.Provider, store.DBPath())
	eng.Start()
	<-ctx.Done()
	log.Printf("正在平滑退出...")
	eng.Stop()
}
