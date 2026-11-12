// Hark 盲信箱服务端（纯 Go 标准库，零外部依赖，静态编译单文件）。
package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/riggedlab/hark/internal/protocol"
)

func envFloat(key string, def float64) float64 {
	if s := os.Getenv(key); s != "" {
		if v, err := strconv.ParseFloat(s, 64); err == nil && v > 0 {
			return v
		}
	}
	return def
}

func main() {
	addr := os.Getenv("LISTEN_ADDR")
	if addr == "" {
		addr = ":8000"
	}
	mb, err := protocol.NewMailboxServer(protocol.ServerConfig{
		DataDir:      os.Getenv("DATA_DIR"),
		PhoneToken:   os.Getenv("PHONE_TOKEN"),
		WorkerToken:  os.Getenv("WORKER_TOKEN"),
		AudioTTLDays: envFloat("AUDIO_TTL_DAYS", 7),
		TextTTLDays:  envFloat("TEXT_TTL_DAYS", 30),
	})
	if err != nil {
		log.Fatalf("hark-mailbox 配置错误: %v", err)
	}

	srv := &http.Server{
		Addr:              addr,
		Handler:           mb.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      45 * time.Second,
		IdleTimeout:       90 * time.Second,
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		shutCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutCtx)
	}()

	log.Printf("hark-mailbox listening on %s", addr)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}
