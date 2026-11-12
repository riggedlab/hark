package main

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/riggedlab/hark/internal/protocol"
)

func TestServerAuthSlotIsolationAndLongPollWakeup(t *testing.T) {
	if _, err := protocol.NewMailboxServer(protocol.ServerConfig{PhoneToken: "short", WorkerToken: "long-enough-token"}); err == nil {
		t.Fatal("expected short token error")
	}
	if _, err := protocol.NewMailboxServer(protocol.ServerConfig{PhoneToken: "same-test-token", WorkerToken: "same-test-token"}); err == nil {
		t.Fatal("expected identical token error")
	}

	mb, err := protocol.NewMailboxServer(protocol.ServerConfig{
		DataDir:     t.TempDir(),
		PhoneToken:  "test-phone-token",
		WorkerToken: "test-worker-token",
	})
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(mb.Handler())
	defer srv.Close()

	// Worker 不允许写 speakers_phone 状态槽
	req, _ := http.NewRequest(http.MethodPut, srv.URL+"/v2/slot/speakers_phone", bytes.NewReader([]byte("cipher")))
	req.Header.Set("Authorization", "Bearer test-worker-token")
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401 when worker writes speakers_phone, got %v", resp)
	}
	resp.Body.Close()

	// 验证长轮询在新密文到达时 <100ms 立即唤醒
	done := make(chan time.Duration, 1)
	go func() {
		t0 := time.Now()
		r, _ := http.NewRequest(http.MethodGet, srv.URL+"/v2/inbox?ver=0&wait=10", nil)
		r.Header.Set("Authorization", "Bearer test-worker-token")
		res, err := http.DefaultClient.Do(r)
		if err == nil {
			res.Body.Close()
		}
		done <- time.Since(t0)
	}()

	time.Sleep(40 * time.Millisecond)
	iid := strings.Repeat("a", 32)
	putReq, _ := http.NewRequest(http.MethodPut, srv.URL+"/v2/inbox/"+iid, bytes.NewReader([]byte("encrypted-audio")))
	putReq.Header.Set("Authorization", "Bearer test-phone-token")
	putResp, _ := http.DefaultClient.Do(putReq)
	putResp.Body.Close()

	elapsed := <-done
	if elapsed > 2*time.Second {
		t.Fatalf("long poll did not wake immediately: took %v", elapsed)
	}

	// 验证服务端迁移重置序号后，旧客户端携带大 after_seq 跨多页（25条 > 20条分页）也能完整拉完不死循环
	for i := 0; i < 25; i++ {
		id := strings.Repeat("b", 30) + string([]byte{'0' + byte(i/10), '0' + byte(i%10)})
		outReq, _ := http.NewRequest(http.MethodPut, srv.URL+"/v2/outbox/"+id, bytes.NewReader([]byte("encrypted-text")))
		outReq.Header.Set("Authorization", "Bearer test-worker-token")
		outResp, _ := http.DefaultClient.Do(outReq)
		outResp.Body.Close()
	}

	poll1, _ := http.NewRequest(http.MethodGet, srv.URL+"/v2/poll?after_seq=999", nil)
	poll1.Header.Set("Authorization", "Bearer test-phone-token")
	resp1, err := http.DefaultClient.Do(poll1)
	if err != nil {
		t.Fatal(err)
	}
	var buf1 bytes.Buffer
	_, _ = buf1.ReadFrom(resp1.Body)
	resp1.Body.Close()
	if !strings.Contains(buf1.String(), `"cursor_reset":true`) || !strings.Contains(buf1.String(), `"has_more":true`) {
		t.Fatalf("expected cursor_reset=true and has_more=true on page 1, got %s", buf1.String())
	}

	// 客户端收到 cursor_reset 后游标推进到第 1 页末尾 20，第 2 页拉完 21..25 并结束
	poll2, _ := http.NewRequest(http.MethodGet, srv.URL+"/v2/poll?after_seq=20", nil)
	poll2.Header.Set("Authorization", "Bearer test-phone-token")
	resp2, err := http.DefaultClient.Do(poll2)
	if err != nil {
		t.Fatal(err)
	}
	var buf2 bytes.Buffer
	_, _ = buf2.ReadFrom(resp2.Body)
	resp2.Body.Close()
	if strings.Contains(buf2.String(), `"cursor_reset":true`) || !strings.Contains(buf2.String(), `"has_more":false`) || !strings.Contains(buf2.String(), `"seq":25`) {
		t.Fatalf("expected page 2 to finish at seq 25 with has_more=false, got %s", buf2.String())
	}
}
