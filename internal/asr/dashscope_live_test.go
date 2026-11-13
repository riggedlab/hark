package asr

import (
	"context"
	"os"
	"testing"
)

// 真实接口冒烟测试：DASHSCOPE_API_KEY=sk-xxx go test -run TestDashScopeLive -v ./internal/asr
func TestDashScopeLive(t *testing.T) {
	key := os.Getenv("DASHSCOPE_API_KEY")
	if key == "" {
		t.Skip("未设置 DASHSCOPE_API_KEY")
	}
	rep := Probe(context.Background(), Config{
		Provider: ProviderDashScope,
		APIKey:   key,
		BaseURL:  os.Getenv("DASHSCOPE_BASE_URL"),
		Model:    os.Getenv("DASHSCOPE_MODEL"),
	})
	t.Logf("%+v", rep)
	if !rep.OK {
		t.Fatalf("探测失败: %s", rep.Error)
	}
}
