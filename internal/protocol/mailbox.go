package protocol

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	MaxAudioBytes = 20 * 1024 * 1024
	MaxTextBytes  = 2 * 1024 * 1024
	MaxSlotBytes  = 1 * 1024 * 1024
)

var idRe = regexp.MustCompile(`^[0-9a-f]{32}$`)

type ServerConfig struct {
	DataDir      string
	PhoneToken   string
	WorkerToken  string
	AudioTTLDays float64
	TextTTLDays  float64
}

type persistedState struct {
	NextSeq  int            `json:"next_seq"`
	SlotVers map[string]int `json:"slot_vers"`
}

// MailboxServer 纯标准库实现的零知识盲信箱（不依赖 e2ee 包，不持有解密密钥）。
type MailboxServer struct {
	cfg      ServerConfig
	inboxDir string
	outDir   string
	slotDir  string

	mu       sync.Mutex
	notifyCh chan struct{}
	state    persistedState
	inboxVer int
	phoneVer int

	stopCleanup chan struct{}
	cleanupDone chan struct{}
	closeOnce   sync.Once
}

func NewMailboxServer(cfg ServerConfig) (*MailboxServer, error) {
	cfg.PhoneToken = strings.TrimSpace(cfg.PhoneToken)
	cfg.WorkerToken = strings.TrimSpace(cfg.WorkerToken)
	if len(cfg.PhoneToken) < 8 {
		return nil, errors.New("PHONE_TOKEN 不能为空且长度至少为 8 个字符")
	}
	if len(cfg.WorkerToken) < 8 {
		return nil, errors.New("WORKER_TOKEN 不能为空且长度至少为 8 个字符")
	}
	if subtle.ConstantTimeCompare([]byte(cfg.PhoneToken), []byte(cfg.WorkerToken)) == 1 {
		return nil, errors.New("PHONE_TOKEN 与 WORKER_TOKEN 必须使用不同的随机字符串")
	}
	if cfg.DataDir == "" {
		cfg.DataDir = "/data"
	}
	if cfg.AudioTTLDays <= 0 {
		cfg.AudioTTLDays = 7
	}
	if cfg.TextTTLDays <= 0 {
		cfg.TextTTLDays = 30
	}

	s := &MailboxServer{
		cfg:         cfg,
		inboxDir:    filepath.Join(cfg.DataDir, "inbox"),
		outDir:      filepath.Join(cfg.DataDir, "outbox"),
		slotDir:     filepath.Join(cfg.DataDir, "slots"),
		notifyCh:    make(chan struct{}),
		stopCleanup: make(chan struct{}),
		cleanupDone: make(chan struct{}),
		state: persistedState{
			NextSeq:  1,
			SlotVers: map[string]int{SlotWorkerStatus: 0, SlotSpeakersMac: 0, SlotSpeakersPhone: 0},
		},
	}
	for _, dir := range []string{s.inboxDir, s.outDir, s.slotDir} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return nil, fmt.Errorf("创建数据目录失败 %s: %w", dir, err)
		}
	}
	stateFile := filepath.Join(cfg.DataDir, "state.json")
	if raw, err := os.ReadFile(stateFile); err == nil {
		_ = json.Unmarshal(raw, &s.state)
		if s.state.NextSeq < 1 {
			s.state.NextSeq = 1
		}
		if s.state.SlotVers == nil {
			s.state.SlotVers = map[string]int{}
		}
	}
	s.mu.Lock()
	s.cleanupLocked()
	s.mu.Unlock()
	go s.runCleanup()
	return s, nil
}

func (s *MailboxServer) runCleanup() {
	defer close(s.cleanupDone)
	ticker := time.NewTicker(time.Hour)
	defer ticker.Stop()
	for {
		select {
		case <-s.stopCleanup:
			return
		case <-ticker.C:
			s.mu.Lock()
			s.cleanupLocked()
			s.mu.Unlock()
		}
	}
}

// Close stops periodic cleanup and waits for any ongoing cleanup to finish.
// It is safe to call more than once. HTTP handlers are shut down separately.
func (s *MailboxServer) Close() {
	s.closeOnce.Do(func() { close(s.stopCleanup) })
	<-s.cleanupDone
}

func (s *MailboxServer) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v2/health", s.handleHealth)
	mux.HandleFunc("PUT /v2/inbox/{item_id}", s.requireAuth(s.cfg.PhoneToken, s.handlePutAudio))
	mux.HandleFunc("GET /v2/inbox", s.requireAuth(s.cfg.WorkerToken, s.handleListAudio))
	mux.HandleFunc("GET /v2/inbox/{item_id}", s.requireAuth(s.cfg.WorkerToken, s.handleGetAudio))
	mux.HandleFunc("PUT /v2/outbox/{item_id}", s.requireAuth(s.cfg.WorkerToken, s.handlePutText))
	mux.HandleFunc("PUT /v2/slot/{slot}", s.handlePutSlot)
	mux.HandleFunc("GET /v2/slot/{slot}", s.requireEither(s.handleGetSlot))
	mux.HandleFunc("GET /v2/poll", s.requireAuth(s.cfg.PhoneToken, s.handlePoll))
	return mux
}

func extractBearer(r *http.Request) string {
	auth := r.Header.Get("Authorization")
	if strings.HasPrefix(auth, "Bearer ") {
		return strings.TrimSpace(strings.TrimPrefix(auth, "Bearer "))
	}
	return ""
}

func matchToken(got, expected string) bool {
	if len(got) != len(expected) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(got), []byte(expected)) == 1
}

func (s *MailboxServer) requireAuth(expected string, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !matchToken(extractBearer(r), expected) {
			writeJSONError(w, http.StatusUnauthorized, "bad token")
			return
		}
		next(w, r)
	}
}

func (s *MailboxServer) requireEither(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tok := extractBearer(r)
		if !matchToken(tok, s.cfg.PhoneToken) && !matchToken(tok, s.cfg.WorkerToken) {
			writeJSONError(w, http.StatusUnauthorized, "bad token")
			return
		}
		next(w, r)
	}
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeJSONError(w http.ResponseWriter, code int, detail string) {
	writeJSON(w, code, map[string]string{"detail": detail})
}

func validID(id string) bool {
	return idRe.MatchString(id)
}

func validSlot(slot string) bool {
	for _, s := range AllSlots {
		if s == slot {
			return true
		}
	}
	return false
}

func readBodyLimited(r *http.Request, limit int64) ([]byte, int) {
	defer r.Body.Close()
	buf, err := io.ReadAll(io.LimitReader(r.Body, limit+1))
	if err != nil {
		return nil, http.StatusBadRequest
	}
	if len(buf) == 0 || int64(len(buf)) > limit {
		return nil, http.StatusRequestEntityTooLarge
	}
	return buf, 0
}

func writeAtomic(path string, body []byte) error {
	var suffix [4]byte
	_, _ = rand.Read(suffix[:])
	tmp := filepath.Join(filepath.Dir(path), fmt.Sprintf(".%s.%s.tmp", filepath.Base(path), hex.EncodeToString(suffix[:])))
	defer os.Remove(tmp)
	if err := os.WriteFile(tmp, body, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func (s *MailboxServer) broadcastLocked() {
	close(s.notifyCh)
	s.notifyCh = make(chan struct{})
}

func (s *MailboxServer) saveStateLocked() error {
	raw, err := json.Marshal(s.state)
	if err != nil {
		return err
	}
	return writeAtomic(filepath.Join(s.cfg.DataDir, "state.json"), raw)
}

func (s *MailboxServer) cleanupLocked() {
	// 清理历史遗留 v1 明文文件
	_ = os.Remove(filepath.Join(s.cfg.DataDir, "speakers.json"))
	cleanExt := func(dir, ext string) {
		entries, _ := os.ReadDir(dir)
		for _, e := range entries {
			if !e.IsDir() && strings.HasSuffix(e.Name(), ext) {
				_ = os.Remove(filepath.Join(dir, e.Name()))
			}
		}
	}
	cleanExt(s.inboxDir, ".opus")
	cleanExt(s.outDir, ".json")

	now := time.Now()
	cleanDir := func(dir string, ttlDays float64, isInbox bool) {
		entries, _ := os.ReadDir(dir)
		maxAge := time.Duration(ttlDays * float64(24*time.Hour))
		for _, e := range entries {
			if e.IsDir() {
				continue
			}
			isTemp := strings.HasSuffix(e.Name(), ".tmp")
			ageLimit := maxAge
			if isTemp {
				ageLimit = 24 * time.Hour
			} else if maxAge <= 0 {
				continue
			}
			info, err := e.Info()
			if err != nil {
				continue
			}
			if now.Sub(info.ModTime()) > ageLimit {
				if err := os.Remove(filepath.Join(dir, e.Name())); err != nil || isTemp {
					continue
				}
				s.phoneVer++
				if isInbox {
					s.inboxVer++
				}
				s.broadcastLocked()
			}
		}
	}
	cleanDir(s.inboxDir, s.cfg.AudioTTLDays, true)
	cleanDir(s.outDir, s.cfg.TextTTLDays, false)
	// Slots and state have no TTL; only reclaim their orphaned temporary files.
	cleanDir(s.slotDir, 0, false)
	cleanDir(s.cfg.DataDir, 0, false)
}

type outboxEntry struct {
	seq  int
	id   string
	path string
}

func (s *MailboxServer) listTextsLocked(afterSeq int) []outboxEntry {
	entries, _ := os.ReadDir(s.outDir)
	var out []outboxEntry
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".bin") {
			continue
		}
		stem := strings.TrimSuffix(e.Name(), ".bin")
		parts := strings.SplitN(stem, "_", 2)
		if len(parts) != 2 {
			continue
		}
		seq, err := strconv.Atoi(parts[0])
		if err != nil || seq <= afterSeq {
			continue
		}
		out = append(out, outboxEntry{seq: seq, id: parts[1], path: filepath.Join(s.outDir, e.Name())})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].seq < out[j].seq })
	return out
}

func (s *MailboxServer) findExistingOutboxLocked(itemID string) (int, bool) {
	suffix := "_" + itemID + ".bin"
	entries, _ := os.ReadDir(s.outDir)
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), suffix) {
			parts := strings.SplitN(e.Name(), "_", 2)
			if seq, err := strconv.Atoi(parts[0]); err == nil {
				return seq, true
			}
		}
	}
	return 0, false
}

func (s *MailboxServer) listInboxLocked() []InboxItem {
	type fileWithTime struct {
		item  InboxItem
		mtime time.Time
	}
	entries, _ := os.ReadDir(s.inboxDir)
	var list []fileWithTime
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".bin") {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		list = append(list, fileWithTime{
			item:  InboxItem{ID: strings.TrimSuffix(e.Name(), ".bin"), Size: info.Size()},
			mtime: info.ModTime(),
		})
	}
	sort.Slice(list, func(i, j int) bool { return list[i].mtime.Before(list[j].mtime) })
	out := make([]InboxItem, len(list))
	for i, f := range list {
		out[i] = f.item
	}
	return out
}

func (s *MailboxServer) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, HealthResp{OK: true})
}

func (s *MailboxServer) handlePutAudio(w http.ResponseWriter, r *http.Request) {
	itemID := r.PathValue("item_id")
	if !validID(itemID) {
		writeJSONError(w, http.StatusBadRequest, "bad id")
		return
	}
	body, code := readBodyLimited(r, MaxAudioBytes)
	if code != 0 {
		writeJSONError(w, code, "empty or too large")
		return
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	s.cleanupLocked()
	if _, exists := s.findExistingOutboxLocked(itemID); exists {
		writeJSON(w, http.StatusOK, PutAudioResp{OK: true, Status: "already_done"})
		return
	}
	if err := writeAtomic(filepath.Join(s.inboxDir, itemID+".bin"), body); err != nil {
		writeJSONError(w, http.StatusInternalServerError, err.Error())
		return
	}
	s.inboxVer++
	s.phoneVer++
	s.broadcastLocked()
	writeJSON(w, http.StatusOK, PutAudioResp{OK: true, Size: len(body)})
}

func parseIntQuery(r *http.Request, key string, def int) int {
	raw := r.URL.Query().Get(key)
	if raw == "" {
		return def
	}
	v, err := strconv.Atoi(raw)
	if err != nil {
		return def
	}
	return v
}

func parseFloatQuery(r *http.Request, key string, def float64) float64 {
	raw := r.URL.Query().Get(key)
	if raw == "" {
		return def
	}
	v, err := strconv.ParseFloat(raw, 64)
	if err != nil || v < 0 {
		return def
	}
	return v
}

func (s *MailboxServer) handleListAudio(w http.ResponseWriter, r *http.Request) {
	ver := parseIntQuery(r, "ver", -1)
	waitS := math.Min(parseFloatQuery(r, "wait", 0), 30)

	s.mu.Lock()
	s.cleanupLocked()
	items := s.listInboxLocked()
	if len(items) == 0 && ver == s.inboxVer && waitS > 0 {
		deadline := time.NewTimer(time.Duration(waitS * float64(time.Second)))
		defer deadline.Stop()
		for len(items) == 0 && ver == s.inboxVer {
			ch := s.notifyCh
			s.mu.Unlock()
			select {
			case <-ch:
			case <-deadline.C:
				s.mu.Lock()
				goto done
			case <-r.Context().Done():
				return
			}
			s.mu.Lock()
			items = s.listInboxLocked()
		}
	}
done:
	curVer := s.inboxVer
	if items == nil {
		items = []InboxItem{}
	}
	s.mu.Unlock()
	writeJSON(w, http.StatusOK, InboxListResp{Ver: curVer, Items: items})
}

func (s *MailboxServer) handleGetAudio(w http.ResponseWriter, r *http.Request) {
	itemID := r.PathValue("item_id")
	if !validID(itemID) {
		writeJSONError(w, http.StatusBadRequest, "bad id")
		return
	}
	p := filepath.Join(s.inboxDir, itemID+".bin")
	if _, err := os.Stat(p); err != nil {
		writeJSONError(w, http.StatusNotFound, "not found")
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	http.ServeFile(w, r, p)
}

func (s *MailboxServer) handlePutText(w http.ResponseWriter, r *http.Request) {
	itemID := r.PathValue("item_id")
	if !validID(itemID) {
		writeJSONError(w, http.StatusBadRequest, "bad id")
		return
	}
	body, code := readBodyLimited(r, MaxTextBytes)
	if code != 0 {
		writeJSONError(w, code, "empty or too large")
		return
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	seq, exists := s.findExistingOutboxLocked(itemID)
	if !exists {
		seq = s.state.NextSeq
		s.state.NextSeq++
		if err := s.saveStateLocked(); err != nil {
			writeJSONError(w, http.StatusInternalServerError, err.Error())
			return
		}
		outFile := filepath.Join(s.outDir, fmt.Sprintf("%010d_%s.bin", seq, itemID))
		if err := writeAtomic(outFile, body); err != nil {
			writeJSONError(w, http.StatusInternalServerError, err.Error())
			return
		}
	}
	audioPath := filepath.Join(s.inboxDir, itemID+".bin")
	if err := os.Remove(audioPath); err == nil {
		s.inboxVer++
	}
	s.phoneVer++
	s.broadcastLocked()
	writeJSON(w, http.StatusOK, PutTextResp{OK: true, Seq: seq})
}

func (s *MailboxServer) handlePutSlot(w http.ResponseWriter, r *http.Request) {
	slot := r.PathValue("slot")
	if !validSlot(slot) {
		writeJSONError(w, http.StatusBadRequest, "bad slot")
		return
	}
	expected := s.cfg.WorkerToken
	if slot == SlotSpeakersPhone {
		expected = s.cfg.PhoneToken
	}
	if !matchToken(extractBearer(r), expected) {
		writeJSONError(w, http.StatusUnauthorized, "bad token")
		return
	}
	body, code := readBodyLimited(r, MaxSlotBytes)
	if code != 0 {
		writeJSONError(w, code, "empty or too large")
		return
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	if err := writeAtomic(filepath.Join(s.slotDir, slot+".bin"), body); err != nil {
		writeJSONError(w, http.StatusInternalServerError, err.Error())
		return
	}
	ver := s.state.SlotVers[slot] + 1
	s.state.SlotVers[slot] = ver
	if err := s.saveStateLocked(); err != nil {
		writeJSONError(w, http.StatusInternalServerError, err.Error())
		return
	}
	s.phoneVer++
	s.broadcastLocked()
	writeJSON(w, http.StatusOK, PutSlotResp{OK: true, Ver: ver})
}

func (s *MailboxServer) handleGetSlot(w http.ResponseWriter, r *http.Request) {
	slot := r.PathValue("slot")
	if !validSlot(slot) {
		writeJSONError(w, http.StatusBadRequest, "bad slot")
		return
	}
	p := filepath.Join(s.slotDir, slot+".bin")
	s.mu.Lock()
	info, err := os.Stat(p)
	if err != nil {
		s.mu.Unlock()
		w.WriteHeader(http.StatusNoContent)
		return
	}
	raw, err := os.ReadFile(p)
	ver := s.state.SlotVers[slot]
	s.mu.Unlock()
	if err != nil {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	age := math.Max(0, time.Since(info.ModTime()).Seconds())
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("X-Slot-Ver", strconv.Itoa(ver))
	w.Header().Set("X-Slot-Age", fmt.Sprintf("%.3f", age))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(raw)
}

func (s *MailboxServer) handlePoll(w http.ResponseWriter, r *http.Request) {
	ver := parseIntQuery(r, "ver", -1)
	afterSeq := parseIntQuery(r, "after_seq", 0)
	wsVer := parseIntQuery(r, "ws_ver", 0)
	smVer := parseIntQuery(r, "sm_ver", 0)
	spVer := parseIntQuery(r, "sp_ver", 0)
	waitS := math.Min(parseFloatQuery(r, "wait", 0), 25)

	s.mu.Lock()
	s.cleanupLocked()
	// 若客户端游标大于等于服务端 next_seq（如服务端迁移到新卷重置序号），通知客户端回拨游标
	cursorReset := false
	if afterSeq >= s.state.NextSeq {
		afterSeq = 0
		cursorReset = true
	}
	texts := s.listTextsLocked(afterSeq)
	if ver == s.phoneVer && len(texts) == 0 && waitS > 0 {
		deadline := time.NewTimer(time.Duration(waitS * float64(time.Second)))
		defer deadline.Stop()
		for ver == s.phoneVer && len(texts) == 0 {
			ch := s.notifyCh
			s.mu.Unlock()
			select {
			case <-ch:
			case <-deadline.C:
				s.mu.Lock()
				goto done
			case <-r.Context().Done():
				return
			}
			s.mu.Lock()
			texts = s.listTextsLocked(afterSeq)
		}
	}
done:
	clientVers := map[string]int{
		SlotWorkerStatus:  wsVer,
		SlotSpeakersMac:   smVer,
		SlotSpeakersPhone: spVer,
	}
	slots := make(map[string]SlotMeta, len(AllSlots))
	for _, slot := range AllSlots {
		p := filepath.Join(s.slotDir, slot+".bin")
		info, err := os.Stat(p)
		if err != nil {
			continue
		}
		v := s.state.SlotVers[slot]
		age := math.Round(math.Max(0, time.Since(info.ModTime()).Seconds())*10) / 10
		meta := SlotMeta{Ver: v, AgeS: age}
		if v != clientVers[slot] {
			if raw, err := os.ReadFile(p); err == nil {
				meta.DataB64 = base64.StdEncoding.EncodeToString(raw)
			}
		}
		slots[slot] = meta
	}

	inboxCount := len(s.listInboxLocked())
	hasMore := len(texts) > 20
	limit := len(texts)
	if limit > 20 {
		limit = 20
	}
	newTexts := make([]PollTextItem, 0, limit)
	for _, t := range texts[:limit] {
		raw, err := os.ReadFile(t.path)
		if err != nil {
			continue
		}
		newTexts = append(newTexts, PollTextItem{
			Seq:     t.seq,
			ID:      t.id,
			DataB64: base64.StdEncoding.EncodeToString(raw),
		})
	}
	curVer := s.phoneVer
	s.mu.Unlock()

	writeJSON(w, http.StatusOK, PollResp{
		Ver:         curVer,
		CursorReset: cursorReset,
		InboxCount:  inboxCount,
		HasMore:     hasMore,
		NewTexts:    newTexts,
		Slots:       slots,
	})
}
