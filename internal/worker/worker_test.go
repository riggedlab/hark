package worker

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/riggedlab/hark/internal/asr"
	"github.com/riggedlab/hark/internal/e2ee"
	"github.com/riggedlab/hark/internal/protocol"
)

func TestFullE2EEPipelineSpeakerSeparationAndArchive(t *testing.T) {
	tmp := t.TempDir()
	const phoneTok = "test-phone-token"
	const workerTok = "test-worker-token"

	mb, err := protocol.NewMailboxServer(protocol.ServerConfig{
		DataDir:     filepath.Join(tmp, "mailbox"),
		PhoneToken:  phoneTok,
		WorkerToken: workerTok,
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(mb.Close)
	mbSrv := httptest.NewServer(mb.Handler())
	defer mbSrv.Close()

	// 模拟带 192 维声纹的本地 ASR 服务
	embA := make([]float64, 192)
	embA[0] = 1
	asrSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/health" {
			_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "model": "Qwen3-ASR-1.7B"})
			return
		}
		_ = json.NewEncoder(w).Encode(asr.Result{
			Text:           "今天下午把架构迁到Go语言",
			DurationS:      15.0,
			SpeechS:        12.5,
			Engine:         "mlx-qwen3",
			Model:          "Qwen3-ASR-1.7B",
			EmbeddingModel: "3dspeaker-cam++",
			Segments: []asr.Segment{
				{Start: 0.5, End: 13.0, Text: "今天下午把架构迁到Go语言", Speaker: "s0"},
			},
			Speakers: []asr.SpeakerEmbedding{
				{Speaker: "s0", SpeechS: 12.5, Embedding: embA},
			},
		})
	}))
	defer asrSrv.Close()

	keyStr, err := e2ee.GenerateKey()
	if err != nil {
		t.Fatal(err)
	}
	key, _ := e2ee.DecodeKey(keyStr)

	// 1. 手机端加密上传一段音频
	const recName = "20261112-143000.opus"
	iid, _ := e2ee.ItemID(key, recName)
	audioPkt, err := e2ee.PackAudio(key, recName, []byte("OggS-fake-opus-stream"))
	if err != nil {
		t.Fatal(err)
	}
	putReq, _ := http.NewRequest(http.MethodPut, mbSrv.URL+"/v2/inbox/"+iid, bytes.NewReader(audioPkt))
	putReq.Header.Set("Authorization", "Bearer "+phoneTok)
	resp, err := http.DefaultClient.Do(putReq)
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("phone put audio failed: err=%v status=%v", err, resp)
	}
	resp.Body.Close()

	// 2. Worker 执行单次拉取、转写、声纹建档、回传
	store, err := OpenStore(filepath.Join(tmp, "hark-data"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()

	cfg := Config{
		ServerURL:        mbSrv.URL,
		WorkerToken:      workerTok,
		E2EEKey:          keyStr,
		SpeakerThreshold: 0.60,
		DataDir:          filepath.Join(tmp, "hark-data"),
		ASR: asr.Config{
			Provider: asr.ProviderHarkHTTP,
			BaseURL:  asrSrv.URL,
		},
	}
	if err := CheckMailbox(context.Background(), cfg); err != nil {
		t.Fatalf("CheckMailbox failed: %v", err)
	}

	eng := NewEngine(cfg, store, nil)
	if err := eng.RunOnce(context.Background()); err != nil {
		t.Fatalf("RunOnce failed: %v", err)
	}

	// 3. 手机端轮询 /v2/poll 验证密文回传且 inbox 已立即清空
	pollReq, _ := http.NewRequest(http.MethodGet, mbSrv.URL+"/v2/poll?after_seq=0", nil)
	pollReq.Header.Set("Authorization", "Bearer "+phoneTok)
	pResp, err := http.DefaultClient.Do(pollReq)
	if err != nil {
		t.Fatal(err)
	}
	var poll protocol.PollResp
	_ = json.NewDecoder(pResp.Body).Decode(&poll)
	pResp.Body.Close()
	if poll.InboxCount != 0 || len(poll.NewTexts) != 1 {
		t.Fatalf("unexpected poll response: %+v", poll)
	}
	rawPkt, _ := base64.StdEncoding.DecodeString(poll.NewTexts[0].DataB64)
	plainJSON, err := e2ee.Decrypt(key, rawPkt, []byte("text:"+iid))
	if err != nil {
		t.Fatalf("phone decrypt outbox failed: %v", err)
	}
	var tr protocol.TranscriptPayload
	_ = json.Unmarshal(plainJSON, &tr)
	if tr.Name != recName || len(tr.Segments) != 1 || tr.Segments[0].Speaker == nil || *tr.Segments[0].Speaker != "spk_001" {
		t.Fatalf("unexpected transcript payload: %+v", tr)
	}

	// 4. 手机端把 spk_001 改名为「老王」，验证电脑端存档检索与导出自动展示「老王」
	phoneRoster := protocol.PhoneSpeakerRoster{
		Items: map[string]protocol.PhoneSpeakerItem{
			"spk_001": {Name: "老王"},
		},
	}
	rBytes, _ := json.Marshal(phoneRoster)
	rPkt, _ := e2ee.Encrypt(key, rBytes, []byte("slot:"+protocol.SlotSpeakersPhone), nil)
	sReq, _ := http.NewRequest(http.MethodPut, mbSrv.URL+"/v2/slot/"+protocol.SlotSpeakersPhone, bytes.NewReader(rPkt))
	sReq.Header.Set("Authorization", "Bearer "+phoneTok)
	sResp, _ := http.DefaultClient.Do(sReq)
	sResp.Body.Close()

	if err := eng.RunOnce(context.Background()); err != nil {
		t.Fatal(err)
	}

	// 按人名「老王」全文搜索存档
	page, err := store.QueryArchive(ArchiveFilter{Query: "老王"})
	if err != nil || page.Total != 1 || page.Items[0].Speakers[0].Label != "老王" {
		t.Fatalf("Archive search by synced speaker name failed: %+v err=%v", page, err)
	}

	md, err := store.ExportMarkdown(ArchiveFilter{})
	if err != nil || !strings.Contains(md, "**老王**：今天下午把架构迁到Go语言") {
		t.Fatalf("ExportMarkdown missing speaker label or text:\n%s", md)
	}

	// 5. 验证不同 embedding_model 隔离：换一个不同声纹模型但同为 192 维相同向量，不会串入 spk_001
	mapping, err := store.AssignSpeakers(asr.Result{
		EmbeddingModel: "other-voiceprint-v2",
		Segments:       []asr.Segment{{Start: 0, End: 12, Text: "换了声纹模型", Speaker: "s0"}},
		Speakers:       []asr.SpeakerEmbedding{{Speaker: "s0", SpeechS: 12, Embedding: embA}},
	}, 0.60)
	if err != nil || mapping["s0"] != "spk_002" {
		t.Fatalf("expected new speaker spk_002 for different embedding_model, got %v (err=%v)", mapping, err)
	}

	// 6. 在线备份与统计检查
	backupFile := filepath.Join(tmp, "backup.db")
	if err := store.BackupTo(backupFile); err != nil {
		t.Fatalf("BackupTo failed: %v", err)
	}
	stats, err := store.Stats()
	if err != nil || stats.TotalRecordings != 1 || stats.ASR.SuccessRate != 100 {
		t.Fatalf("unexpected stats: %+v err=%v", stats, err)
	}
}

type failingSecretStore struct {
	data    map[string]string
	failKey string
}

func (f *failingSecretStore) Get(k string) (string, error) { return f.data[k], nil }
func (f *failingSecretStore) Delete(k string) error        { delete(f.data, k); return nil }
func (f *failingSecretStore) Set(k, v string) error {
	if k == f.failKey {
		return http.ErrNotSupported
	}
	f.data[k] = v
	return nil
}

func TestKeychainFallbackNeverUsesStaleSecretAndUnlimitedExport(t *testing.T) {
	tmp := t.TempDir()
	confFile := filepath.Join(tmp, "config.json")
	sec := &failingSecretStore{
		data:    map[string]string{"e2ee_key": "stale-key"},
		failKey: "asr_api_key",
	}
	want := DefaultConfig()
	want.WorkerToken = "new-worker-tok"
	want.E2EEKey = "new-e2ee-key"
	want.ASR.APIKey = "new-asr-key"

	if err := SaveConfig(confFile, want, sec); err != nil {
		t.Fatal(err)
	}
	got, err := LoadConfig(confFile, sec)
	if err != nil {
		t.Fatal(err)
	}
	if got.E2EEKey != "new-e2ee-key" || got.ASR.APIKey != "new-asr-key" || got.KeychainStored {
		t.Fatalf("stale keychain leaked into config: %+v", got)
	}

	// 验证超过 500 条记录时导出不截断
	store, err := OpenStore(filepath.Join(tmp, "db"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	for i := 0; i < 520; i++ {
		name := "20261112-" + strings.ReplaceAll(strings.TrimPrefix(http.StatusText(200), "OK"), "", "")
		_ = name
		rec := protocol.TranscriptPayload{
			Name:      "20261112-" + pad6(i) + ".opus",
			StartedAt: "2026-11-12T00:00:00",
			DurationS: 10,
			Text:      "条目",
		}
		if err := store.SaveTranscript(rec); err != nil {
			t.Fatal(err)
		}
	}
	md, err := store.ExportMarkdown(ArchiveFilter{})
	if err != nil || strings.Count(md, "## ") != 520 {
		t.Fatalf("expected all 520 items exported, got %d (err=%v)", strings.Count(md, "## "), err)
	}
}

func TestSceneTagsAndMoodPersistAndQueryRoundTrip(t *testing.T) {
	dir := t.TempDir()
	store, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()

	mood := "positive"
	score := 0.68
	vocal := "笑声"
	prox := "near"
	snr := 22.4
	spk := "spk_001"
	payload := protocol.TranscriptPayload{
		Name:      "20261113-140000.opus",
		StartedAt: "2026-11-13T14:00:00",
		DurationS: 12.0,
		SpeechS:   8.5,
		Engine:    "volcengine",
		Model:     "auc_turbo",
		Text:      "这个改动体验特别顺畅！",
		SceneTags: []protocol.SceneTag{{Label: "敲键盘", Score: 0.82}, {Label: "笑声", Score: 0.64}},
		Segments: []protocol.Segment{
			{
				Start:     0.5,
				End:       9.0,
				Text:      "这个改动体验特别顺畅！",
				Speaker:   &spk,
				SnrDB:     &snr,
				Proximity: &prox,
				Mood:      &mood,
				MoodScore: &score,
				VocalTag:  &vocal,
			},
		},
	}
	if err := store.SaveTranscript(payload); err != nil {
		t.Fatal(err)
	}

	ro, exists, err := OpenReadOnlyStore(dir)
	if err != nil || !exists {
		t.Fatalf("OpenReadOnlyStore failed: exists=%v err=%v", exists, err)
	}
	defer ro.Close()

	detail, err := ro.GetDetail(payload.Name)
	if err != nil {
		t.Fatal(err)
	}
	if len(detail.SceneTags) != 2 || detail.SceneTags[0].Label != "敲键盘" {
		t.Fatalf("unexpected SceneTags: %+v", detail.SceneTags)
	}
	if len(detail.Segments) != 1 || detail.Segments[0].Mood != "positive" || detail.Segments[0].VocalTag != "笑声" || detail.Segments[0].Proximity != "near" || detail.Segments[0].SnrDB == nil || *detail.Segments[0].SnrDB != 22.4 {
		t.Fatalf("unexpected Segment meta: %+v", detail.Segments)
	}
}

func pad6(n int) string {
	s := "000000" + string([]byte{
		byte('0' + (n/100000)%10),
		byte('0' + (n/10000)%10),
		byte('0' + (n/1000)%10),
		byte('0' + (n/100)%10),
		byte('0' + (n/10)%10),
		byte('0' + n%10),
	})
	return s[len(s)-6:]
}

// 错误被传到上层后，下一批和重建 Engine 也不能再次发送同一份可能已计费的音频。
func TestDashScopePermanentFailuresAreSkippedAcrossWorkerPolls(t *testing.T) {
	old := http.DefaultTransport
	defer func() { http.DefaultTransport = old }()
	for _, readTimeout := range []bool{false, true} {
		calls := 0
		http.DefaultTransport = workerASRTransport(func(r *http.Request) (*http.Response, error) {
			if r.URL.Host != "asr.test" {
				return old.RoundTrip(r)
			}
			calls++
			if readTimeout {
				return nil, &net.OpError{Op: "read", Net: "tcp", Err: os.ErrDeadlineExceeded}
			}
			return &http.Response{StatusCode: 400, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(`{"code":"InvalidFile.AudioLengthError"}`))}, nil
		})
		key := bytes.Repeat([]byte{1}, 32)
		const name = "20261113-160000.opus"
		iid, _ := e2ee.ItemID(key, name)
		packet, err := e2ee.PackAudio(key, name, []byte("OggS-fake-opus"))
		if err != nil {
			t.Fatal(err)
		}
		downloads := 0
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { downloads++; _, _ = w.Write(packet) }))
		store, err := OpenStore(t.TempDir())
		if err != nil {
			t.Fatal(err)
		}
		cfg := Config{ServerURL: srv.URL, ASR: asr.Config{Provider: asr.ProviderDashScope, BaseURL: "http://asr.test", APIKey: "k"}}
		client := &http.Client{Transport: old}
		eng := NewEngine(cfg, store, nil)
		err = eng.processOne(context.Background(), cfg, key, client, iid)
		want := asr.ErrBadAudio
		if readTimeout {
			want = asr.ErrReadTimeout
		}
		if !errors.Is(err, want) {
			t.Fatalf("readTimeout=%v error=%v", readTimeout, err)
		}
		if err := NewEngine(cfg, store, nil).processOne(context.Background(), cfg, key, client, iid); !errors.Is(err, errASRSkipped) {
			t.Fatalf("expected persistent skip, got %v", err)
		}
		if calls != 1 || downloads != 1 {
			t.Fatalf("readTimeout=%v calls=%d downloads=%d", readTimeout, calls, downloads)
		}
		store.Close()
		srv.Close()
	}
}

func TestSkippedOnlyBatchBacksOffAndHonorsCancellation(t *testing.T) {
	key := bytes.Repeat([]byte{1}, 32)
	iid, _ := e2ee.ItemID(key, "20261113-160000.opus")
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { store.Close() })
	skipDir := filepath.Join(filepath.Dir(store.dbPath), "asr_no_retry")
	if err := os.Mkdir(skipDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(skipDir, iid), nil, 0o600); err != nil {
		t.Fatal(err)
	}
	listed := make(chan struct{}, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/v2/inbox":
			_ = json.NewEncoder(w).Encode(protocol.InboxListResp{Ver: 1, Items: []protocol.InboxItem{{ID: iid}}})
			listed <- struct{}{}
		case "/v2/inbox/" + iid:
			t.Error("skipped audio was fetched again")
			w.WriteHeader(http.StatusInternalServerError)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()
	cfg := Config{ServerURL: srv.URL}
	eng := NewEngine(cfg, store, nil)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() {
		ver, failed, err := eng.pollAndProcessBatch(ctx, cfg, key, srv.Client(), 1, 20)
		if err == nil && (ver != 1 || failed) {
			t.Errorf("skipped item counted as failed: ver=%d failed=%v", ver, failed)
		}
		done <- err
	}()
	select {
	case <-listed:
	case <-time.After(2 * time.Second):
		t.Fatal("inbox was not listed")
	}
	select {
	case err := <-done:
		t.Fatalf("batch returned immediately with only skipped items: %v", err)
	case <-time.After(100 * time.Millisecond):
	}
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("backoff ignored cancellation")
	}
}

type workerASRTransport func(*http.Request) (*http.Response, error)

func (f workerASRTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
