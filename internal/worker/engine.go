package worker

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"math"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/riggedlab/hark/internal/asr"
	"github.com/riggedlab/hark/internal/e2ee"
	"github.com/riggedlab/hark/internal/protocol"
)

type RuntimeStatus struct {
	Configured    bool    `json:"configured"`
	ConfigHint    string  `json:"config_hint,omitempty"`
	Paused        bool    `json:"paused"`
	Phase         string  `json:"phase"` // "unconfigured" | "paused" | "idle" | "transcribing" | "asr_down" | "error"
	Current       string  `json:"current,omitempty"`
	ASROK         bool    `json:"asr_ok"`
	ASRProvider   string  `json:"asr_provider"`
	ASRModel      string  `json:"asr_model,omitempty"`
	LastDone      string  `json:"last_done,omitempty"`
	LastError     string  `json:"last_error,omitempty"`
	PhaseElapsedS float64 `json:"phase_elapsed_s"`
	RosterVer     int     `json:"roster_ver"`
}

type Engine struct {
	mu               sync.Mutex
	cfg              Config
	store            *Store
	phase            string
	phaseStarted     time.Time
	current          string
	asrOK            bool
	asrModel         string
	lastDone         string
	lastError        string
	paused           bool
	lastPhoneSlotVer int
	rosterVer        int
	pollCancel       context.CancelFunc

	onChange func()
	wakeCh   chan struct{}
	cancel   context.CancelFunc
	wg       sync.WaitGroup
}

func NewEngine(cfg Config, store *Store, onChange func()) *Engine {
	return &Engine{
		cfg:          cfg,
		store:        store,
		phase:        "idle",
		phaseStarted: time.Now(),
		onChange:     onChange,
		wakeCh:       make(chan struct{}, 1),
	}
}

func (e *Engine) notify() {
	select {
	case e.wakeCh <- struct{}{}:
	default:
	}
	if e.onChange != nil {
		go e.onChange()
	}
}

func (e *Engine) Start() {
	e.mu.Lock()
	if e.cancel != nil {
		e.mu.Unlock()
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	e.cancel = cancel
	e.mu.Unlock()

	e.wg.Add(2)
	go e.runHeartbeat(ctx)
	go e.runLoop(ctx)
}

func (e *Engine) Stop() {
	e.mu.Lock()
	cancel := e.cancel
	e.cancel = nil
	e.mu.Unlock()
	if cancel != nil {
		cancel()
		e.wg.Wait()
	}
}

func (e *Engine) SetPaused(paused bool) {
	e.mu.Lock()
	e.paused = paused
	e.phaseStarted = time.Now()
	if paused {
		e.phase = "paused"
		e.current = ""
		if e.pollCancel != nil {
			e.pollCancel()
		}
	} else {
		e.phase = "idle"
	}
	e.mu.Unlock()
	e.notify()
}

func (e *Engine) isPaused() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.paused
}

func (e *Engine) UpdateConfig(cfg Config) {
	wasRunning := false
	e.mu.Lock()
	wasRunning = e.cancel != nil
	e.mu.Unlock()
	if wasRunning {
		e.Stop()
	}
	e.mu.Lock()
	e.cfg = cfg
	e.lastError = ""
	e.lastPhoneSlotVer = 0
	e.phase = "idle"
	e.phaseStarted = time.Now()
	e.mu.Unlock()
	if wasRunning {
		e.Start()
	}
	e.notify()
}

func (e *Engine) Status() RuntimeStatus {
	e.mu.Lock()
	defer e.mu.Unlock()

	_, cfgErr := e.cfg.ValidateReady()
	st := RuntimeStatus{
		Configured:    cfgErr == nil,
		Paused:        e.paused,
		Phase:         e.phase,
		Current:       e.current,
		ASROK:         e.asrOK,
		ASRProvider:   e.cfg.ASR.Provider,
		ASRModel:      e.asrModel,
		LastDone:      e.lastDone,
		LastError:     e.lastError,
		PhaseElapsedS: math.Round(time.Since(e.phaseStarted).Seconds()*10) / 10,
		RosterVer:     e.rosterVer,
	}
	if e.store == nil {
		st.Phase = "error"
		if st.LastError == "" {
			st.LastError = "本地存档目录无法打开"
		}
		return st
	}
	if cfgErr != nil {
		st.Phase = "unconfigured"
		st.ConfigHint = cfgErr.Error()
	} else if e.paused {
		st.Phase = "paused"
	}
	return st
}

// CheckMailbox 测试信箱服务器 /v2/health 与 WORKER_TOKEN 鉴权是否通过。
func CheckMailbox(ctx context.Context, cfg Config) error {
	if strings.TrimSpace(cfg.ServerURL) == "" {
		return errors.New("未填写信箱服务器地址")
	}
	if strings.TrimSpace(cfg.WorkerToken) == "" {
		return errors.New("未填写 WORKER_TOKEN")
	}
	client, err := newHTTPClient(cfg, 8*time.Second)
	if err != nil {
		return err
	}
	reqCtx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()

	base := strings.TrimRight(strings.TrimSpace(cfg.ServerURL), "/")
	hReq, err := http.NewRequestWithContext(reqCtx, http.MethodGet, base+"/v2/health", nil)
	if err != nil {
		return err
	}
	hResp, err := client.Do(hReq)
	if err != nil {
		return fmt.Errorf("无法连接信箱服务器: %w", err)
	}
	hResp.Body.Close()
	if hResp.StatusCode != http.StatusOK {
		return fmt.Errorf("信箱健康检查返回 HTTP %d", hResp.StatusCode)
	}

	aReq, err := http.NewRequestWithContext(reqCtx, http.MethodGet, base+"/v2/inbox?ver=-1&wait=0", nil)
	if err != nil {
		return err
	}
	aReq.Header.Set("Authorization", "Bearer "+strings.TrimSpace(cfg.WorkerToken))
	aResp, err := client.Do(aReq)
	if err != nil {
		return err
	}
	aResp.Body.Close()
	if aResp.StatusCode == http.StatusUnauthorized {
		return errors.New("WORKER_TOKEN 鉴权失败（401），请核对与信箱 .env 是否一致")
	}
	if aResp.StatusCode != http.StatusOK {
		return fmt.Errorf("信箱队列接口返回 HTTP %d", aResp.StatusCode)
	}
	return nil
}

// RunOnce 单次排空当前信箱队列（供 CLI --once 或全链路自动化验收使用）。
func (e *Engine) RunOnce(ctx context.Context) error {
	e.mu.Lock()
	cfg := e.cfg
	e.mu.Unlock()

	key, err := cfg.ValidateReady()
	if err != nil {
		return err
	}
	client, err := newHTTPClient(cfg, 40*time.Second)
	if err != nil {
		return err
	}
	e.publishHeartbeatOnce(ctx, cfg, key, client)
	defer e.publishHeartbeatOnce(context.Background(), cfg, key, client)

	if err := e.syncPhoneSpeakers(ctx, cfg, key, client); err != nil {
		return err
	}
	if err := e.pushMacRoster(ctx, cfg, key, client); err != nil {
		return err
	}
	_, failed, err := e.pollAndProcessBatch(ctx, cfg, key, client, -1, 0)
	if err != nil {
		return err
	}
	if failed {
		return errors.New("部分录音转写失败")
	}
	return nil
}

func (e *Engine) runHeartbeat(ctx context.Context) {
	defer e.wg.Done()
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()

	publish := func() {
		e.mu.Lock()
		cfg := e.cfg
		hasStore := e.store != nil
		e.mu.Unlock()
		if !hasStore {
			return
		}
		key, err := cfg.ValidateReady()
		if err != nil {
			return
		}
		client, err := newHTTPClient(cfg, 10*time.Second)
		if err != nil {
			return
		}
		e.publishHeartbeatOnce(ctx, cfg, key, client)
	}

	publish()
	for {
		select {
		case <-ctx.Done():
			return
		case <-e.wakeCh:
			publish()
		case <-ticker.C:
			publish()
		}
	}
}

func (e *Engine) publishHeartbeatOnce(ctx context.Context, cfg Config, key []byte, client *http.Client) {
	ok, model := asr.CheckHealth(ctx, cfg.ASR)

	e.mu.Lock()
	e.asrOK = ok
	if model != nil {
		e.asrModel = *model
	} else {
		e.asrModel = ""
	}
	if e.phase == "idle" || e.phase == "asr_down" {
		nextPhase := "idle"
		if !ok {
			nextPhase = "asr_down"
		}
		if e.phase != nextPhase {
			e.phase = nextPhase
			e.phaseStarted = time.Now()
		}
	}
	phase := e.phase
	if e.paused {
		phase = "paused"
	} else if !ok && phase != "error" {
		phase = "asr_down"
	}
	var curPtr, modPtr, donePtr, errPtr *string
	if e.current != "" {
		v := e.current
		curPtr = &v
	}
	if model != nil && *model != "" {
		v := *model
		modPtr = &v
	}
	if e.lastDone != "" {
		v := e.lastDone
		donePtr = &v
	}
	if e.lastError != "" {
		v := e.lastError
		errPtr = &v
	}
	snap := protocol.WorkerStatus{
		Phase:         phase,
		Current:       curPtr,
		ASROK:         ok,
		ASRModel:      modPtr,
		LastDone:      donePtr,
		LastError:     errPtr,
		PhaseElapsedS: math.Round(time.Since(e.phaseStarted).Seconds()*10) / 10,
	}
	e.mu.Unlock()

	if e.onChange != nil {
		go e.onChange()
	}
	_ = putSlotEncrypted(ctx, cfg, key, client, protocol.SlotWorkerStatus, snap)
}

func (e *Engine) runLoop(ctx context.Context) {
	defer e.wg.Done()
	ver := -1
	initialized := false

	for {
		if ctx.Err() != nil {
			return
		}
		e.mu.Lock()
		cfg := e.cfg
		paused := e.paused
		hasStore := e.store != nil
		e.mu.Unlock()

		key, cfgErr := cfg.ValidateReady()
		if paused || !hasStore || cfgErr != nil {
			select {
			case <-ctx.Done():
				return
			case <-time.After(1 * time.Second):
				continue
			}
		}

		client, err := newHTTPClient(cfg, 40*time.Second)
		if err != nil {
			e.setError(err.Error())
			sleepCtx(ctx, 5*time.Second)
			continue
		}

		if !initialized {
			if err := e.syncPhoneSpeakers(ctx, cfg, key, client); err != nil {
				e.setError(err.Error())
				sleepCtx(ctx, 5*time.Second)
				continue
			}
			if err := e.pushMacRoster(ctx, cfg, key, client); err != nil {
				e.setError(err.Error())
				sleepCtx(ctx, 5*time.Second)
				continue
			}
			initialized = true
		}

		nextVer, batchFailed, err := e.pollAndProcessBatch(ctx, cfg, key, client, ver, 25)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			e.setError(err.Error())
			sleepCtx(ctx, 5*time.Second)
			continue
		}
		ver = nextVer
		if !batchFailed {
			e.recoverFromError()
		} else {
			sleepCtx(ctx, 5*time.Second)
		}
	}
}

func (e *Engine) setError(msg string) {
	if len(msg) > 120 {
		msg = msg[:120]
	}
	e.mu.Lock()
	e.phase = "error"
	e.current = ""
	e.lastError = msg
	e.phaseStarted = time.Now()
	e.mu.Unlock()
	e.notify()
}

func (e *Engine) recoverFromError() {
	e.mu.Lock()
	if e.phase != "error" {
		e.mu.Unlock()
		return
	}
	e.phase = "idle"
	e.current = ""
	e.lastError = ""
	e.phaseStarted = time.Now()
	e.mu.Unlock()
	e.notify()
}

func (e *Engine) pollAndProcessBatch(ctx context.Context, cfg Config, key []byte, client *http.Client, ver int, waitS int) (int, bool, error) {
	pollCtx, cancelPoll := context.WithCancel(ctx)
	e.mu.Lock()
	e.pollCancel = cancelPoll
	e.mu.Unlock()
	defer func() {
		cancelPoll()
		e.mu.Lock()
		e.pollCancel = nil
		e.mu.Unlock()
	}()

	base := strings.TrimRight(cfg.ServerURL, "/")
	u := fmt.Sprintf("%s/v2/inbox?ver=%d&wait=%d", base, ver, waitS)
	req, err := http.NewRequestWithContext(pollCtx, http.MethodGet, u, nil)
	if err != nil {
		return ver, false, err
	}
	req.Header.Set("Authorization", "Bearer "+cfg.WorkerToken)
	resp, err := client.Do(req)
	if err != nil {
		if e.isPaused() && ctx.Err() == nil {
			return ver, false, nil
		}
		return ver, false, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return ver, false, errors.New("信箱返回 HTTP 404（云端仍是旧版 v1 容器，请升级为 v2 hark-mailbox）")
	}
	if resp.StatusCode != http.StatusOK {
		return ver, false, fmt.Errorf("拉取队列返回 HTTP %d", resp.StatusCode)
	}
	var list protocol.InboxListResp
	if err := json.NewDecoder(resp.Body).Decode(&list); err != nil {
		return ver, false, err
	}

	// 空队列轮询结束时也检查手机端是否更新了人物改名与合并
	_ = e.syncPhoneSpeakers(ctx, cfg, key, client)
	if len(list.Items) == 0 {
		return list.Ver, false, nil
	}

	failed := false
	for _, it := range list.Items {
		if ctx.Err() != nil || e.isPaused() {
			return list.Ver, failed, ctx.Err()
		}
		if err := e.processOne(ctx, cfg, key, client, it.ID); err != nil {
			failed = true
			e.setError(err.Error())
			log.Printf("[hark-worker] %s 处理失败: %v", it.ID, err)
		}
	}
	return list.Ver, failed, nil
}

func (e *Engine) processOne(ctx context.Context, cfg Config, key []byte, client *http.Client, iid string) error {
	base := strings.TrimRight(cfg.ServerURL, "/")
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base+"/v2/inbox/"+url.PathEscape(iid), nil)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+cfg.WorkerToken)
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	packet, err := io.ReadAll(io.LimitReader(resp.Body, protocol.MaxAudioBytes+4096))
	resp.Body.Close()
	if err != nil {
		return err
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("下载音频密文返回 HTTP %d", resp.StatusCode)
	}

	name, opus, err := e2ee.UnpackAudio(key, iid, packet)
	if err != nil {
		return fmt.Errorf("解密音频失败（请确认手机与电脑 E2EE_KEY 一致）: %w", err)
	}

	e.mu.Lock()
	e.phase = "transcribing"
	e.current = name
	e.lastError = ""
	e.phaseStarted = time.Now()
	e.mu.Unlock()
	e.notify()

	t0 := time.Now()
	res, err := asr.Transcribe(ctx, cfg.ASR, opus, name)
	latMs := time.Since(t0).Milliseconds()
	if err != nil {
		e.store.RecordASRRun(name, false, latMs, asr.OggOpusDuration(opus), cfg.ASR.Provider, cfg.ASR.Model, err.Error())
		return err
	}
	e.store.RecordASRRun(name, true, latMs, res.DurationS, res.Engine, res.Model, "")
	log.Printf("[hark-worker] %s: %.1fs 音频，转写耗时 %dms，%d 字", name, res.DurationS, latMs, len([]rune(res.Text)))

	mapping, err := e.store.AssignSpeakers(res, cfg.SpeakerThreshold)
	if err != nil {
		return err
	}

	startedAt := parseStartedAt(name)
	outSegs := make([]protocol.Segment, 0, len(res.Segments))
	seenGlobal := map[string]bool{}
	for _, seg := range res.Segments {
		var spkPtr *string
		if mapped, ok := mapping[string(seg.Speaker)]; ok && mapped != "" {
			v := mapped
			spkPtr = &v
			if strings.HasPrefix(mapped, "spk_") {
				seenGlobal[mapped] = true
			}
		}
		outSegs = append(outSegs, protocol.Segment{
			Start:   seg.Start,
			End:     seg.End,
			Text:    seg.Text,
			Speaker: spkPtr,
		})
	}

	// 更新新出现或已有全局说话人的首次出现时间与最长代表句
	baseTime, baseErr := time.Parse("2006-01-02T15:04:05", startedAt)
	for sid := range seenGlobal {
		var firstStart float64 = -1
		var longestText string
		var longestStart float64
		for _, seg := range outSegs {
			if seg.Speaker == nil || *seg.Speaker != sid || strings.TrimSpace(seg.Text) == "" {
				continue
			}
			if firstStart < 0 || seg.Start < firstStart {
				firstStart = seg.Start
			}
			if len([]rune(seg.Text)) > len([]rune(longestText)) {
				longestText = seg.Text
				longestStart = seg.Start
			}
		}
		firstSeen := startedAt
		if baseErr == nil && firstStart >= 0 {
			firstSeen = baseTime.Add(time.Duration(firstStart * float64(time.Second))).Format("2006-01-02T15:04:05")
		}
		_ = e.store.UpdateSpeakerMeta(sid, firstSeen, longestText, name, longestStart)
	}

	if err := e.pushMacRoster(ctx, cfg, key, client); err != nil {
		return err
	}

	payload := protocol.TranscriptPayload{
		Name:      name,
		StartedAt: startedAt,
		DurationS: res.DurationS,
		SpeechS:   res.SpeechS,
		Engine:    res.Engine,
		Model:     res.Model,
		Text:      res.Text,
		Segments:  outSegs,
	}
	if err := e.store.SaveTranscript(payload); err != nil {
		return err
	}

	rawJSON, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	outPacket, err := e2ee.Encrypt(key, rawJSON, []byte("text:"+iid), nil)
	if err != nil {
		return err
	}
	putReq, err := http.NewRequestWithContext(ctx, http.MethodPut, base+"/v2/outbox/"+url.PathEscape(iid), bytes.NewReader(outPacket))
	if err != nil {
		return err
	}
	putReq.Header.Set("Authorization", "Bearer "+cfg.WorkerToken)
	putResp, err := client.Do(putReq)
	if err != nil {
		return err
	}
	putResp.Body.Close()
	if putResp.StatusCode != http.StatusOK {
		return fmt.Errorf("回传转写密文返回 HTTP %d", putResp.StatusCode)
	}

	e.mu.Lock()
	e.phase = "idle"
	e.current = ""
	e.lastDone = name
	e.lastError = ""
	e.phaseStarted = time.Now()
	e.mu.Unlock()
	e.notify()
	return nil
}

func (e *Engine) syncPhoneSpeakers(ctx context.Context, cfg Config, key []byte, client *http.Client) error {
	base := strings.TrimRight(cfg.ServerURL, "/")
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base+"/v2/slot/"+protocol.SlotSpeakersPhone, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+cfg.WorkerToken)
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNoContent {
		return nil
	}
	if resp.StatusCode == http.StatusNotFound {
		return errors.New("信箱返回 HTTP 404（云端仍是旧版 v1 容器，请升级为 v2 hark-mailbox）")
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("读取 speakers_phone 返回 HTTP %d", resp.StatusCode)
	}
	slotVer := ParseVerHeader(resp.Header)
	e.mu.Lock()
	sameVer := slotVer > 0 && slotVer == e.lastPhoneSlotVer
	e.mu.Unlock()
	if sameVer {
		return nil
	}

	pkt, err := io.ReadAll(io.LimitReader(resp.Body, protocol.MaxSlotBytes+1024))
	if err != nil {
		return err
	}
	plain, err := e2ee.Decrypt(key, pkt, []byte("slot:"+protocol.SlotSpeakersPhone))
	if err != nil {
		return err
	}
	var roster protocol.PhoneSpeakerRoster
	if err := json.Unmarshal(plain, &roster); err != nil {
		return err
	}
	if err := e.store.ApplyPhoneRoster(roster.Items); err != nil {
		return err
	}
	e.mu.Lock()
	e.lastPhoneSlotVer = slotVer
	e.rosterVer++
	e.mu.Unlock()
	e.notify()
	return nil
}

func (e *Engine) pushMacRoster(ctx context.Context, cfg Config, key []byte, client *http.Client) error {
	roster, err := e.store.ExportMacRoster()
	if err != nil {
		return err
	}
	return putSlotEncrypted(ctx, cfg, key, client, protocol.SlotSpeakersMac, roster)
}

func putSlotEncrypted(ctx context.Context, cfg Config, key []byte, client *http.Client, slot string, val any) error {
	raw, err := json.Marshal(val)
	if err != nil {
		return err
	}
	pkt, err := e2ee.Encrypt(key, raw, []byte("slot:"+slot), nil)
	if err != nil {
		return err
	}
	base := strings.TrimRight(cfg.ServerURL, "/")
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, base+"/v2/slot/"+slot, bytes.NewReader(pkt))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+cfg.WorkerToken)
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("写入状态槽 %s 返回 HTTP %d", slot, resp.StatusCode)
	}
	return nil
}

func parseStartedAt(name string) string {
	stem := strings.TrimSuffix(name, ".opus")
	if t, err := time.Parse("20060102-150405", stem); err == nil {
		return t.Format("2006-01-02T15:04:05")
	}
	return stem
}

func newHTTPClient(cfg Config, timeout time.Duration) (*http.Client, error) {
	tr := &http.Transport{Proxy: nil} // 不走系统 HTTP 代理，与原 trust_env=False 保持一致
	if caPath := strings.TrimSpace(cfg.ServerCA); caPath != "" {
		pemBytes, err := os.ReadFile(expandHome(caPath))
		if err != nil {
			return nil, fmt.Errorf("读取 SERVER_CA 失败: %w", err)
		}
		pool := x509.NewCertPool()
		if !pool.AppendCertsFromPEM(pemBytes) {
			return nil, errors.New("解析 SERVER_CA PEM 证书失败")
		}
		tr.TLSClientConfig = &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS12}
	}
	return &http.Client{Timeout: timeout, Transport: tr}, nil
}

func sleepCtx(ctx context.Context, d time.Duration) {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
	case <-t.C:
	}
}

func ParseVerHeader(h http.Header) int {
	v, _ := strconv.Atoi(h.Get("X-Slot-Ver"))
	return v
}
