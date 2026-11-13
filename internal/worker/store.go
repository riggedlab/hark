package worker

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/riggedlab/hark/internal/protocol"
	_ "modernc.org/sqlite"
)

type Store struct {
	mu     sync.Mutex
	db     *sql.DB
	dbPath string
}

type ArchiveFilter struct {
	Query     string `json:"query"`
	DateRange string `json:"date_range"` // "all" | "today" | "7d" | "30d" | "YYYY-MM-DD"
	SpeakerID string `json:"speaker_id"`
	Limit     int    `json:"limit"`
	Offset    int    `json:"offset"`
}

type SpeakerBadge struct {
	ID    string `json:"id"`
	Label string `json:"label"`
}

type ArchiveItem struct {
	Name      string         `json:"name"`
	StartedAt string         `json:"started_at"`
	DurationS float64        `json:"duration_s"`
	SpeechS   float64        `json:"speech_s"`
	Engine    string         `json:"engine"`
	Model     string         `json:"model"`
	Preview   string         `json:"preview"`
	Speakers  []SpeakerBadge `json:"speakers"`
}

type ArchivePage struct {
	Items    []ArchiveItem  `json:"items"`
	Total    int            `json:"total"`
	Speakers []SpeakerBadge `json:"speakers"`
}

type ResolvedSegment struct {
	Start        float64  `json:"start"`
	End          float64  `json:"end"`
	Text         string   `json:"text"`
	SpeakerID    string   `json:"speaker_id,omitempty"`
	SpeakerLabel string   `json:"speaker_label,omitempty"`
	DB           *float64 `json:"db,omitempty"`
	SnrDB        *float64 `json:"snr_db,omitempty"`
	ActiveS      *float64 `json:"act_s,omitempty"`
	Proximity    string   `json:"proximity,omitempty"`
	Mood         string   `json:"mood,omitempty"`
	MoodScore    *float64 `json:"mood_score,omitempty"`
	VocalTag     string   `json:"vocal_tag,omitempty"`
}

type TranscriptDetail struct {
	Name      string              `json:"name"`
	StartedAt string              `json:"started_at"`
	DurationS float64             `json:"duration_s"`
	SpeechS   float64             `json:"speech_s"`
	Engine    string              `json:"engine"`
	Model     string              `json:"model"`
	Text      string              `json:"text"`
	Segments  []ResolvedSegment   `json:"segments"`
	SceneTags []protocol.SceneTag `json:"scene_tags,omitempty"`
}

type ASRMetrics struct {
	WindowTotal   int     `json:"window_total"`
	SuccessCount  int     `json:"success_count"`
	SuccessRate   float64 `json:"success_rate"`
	AvgLatencyMs  int64   `json:"avg_latency_ms"`
	LastError     string  `json:"last_error,omitempty"`
	LastSuccessAt string  `json:"last_success_at,omitempty"`
}

type ArchiveStats struct {
	DBPath          string     `json:"db_path"`
	DBSizeBytes     int64      `json:"db_size_bytes"`
	TotalRecordings int        `json:"total_recordings"`
	TotalDurationS  float64    `json:"total_duration_s"`
	TotalSpeechS    float64    `json:"total_speech_s"`
	SpeakerCount    int        `json:"speaker_count"`
	ASR             ASRMetrics `json:"asr"`
}

func OpenStore(dataDir string) (*Store, error) {
	if dataDir == "" {
		dataDir = DefaultDataDir()
	}
	dataDir = expandHome(dataDir)
	// Keep the database path stable if the process later changes directory.
	dataDir, err := filepath.Abs(dataDir)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}

	harkDB := filepath.Join(dataDir, "hark.db")
	legacyDB := filepath.Join(dataDir, "diting.db")
	dbPath := harkDB
	if _, err := os.Stat(harkDB); os.IsNotExist(err) {
		if _, lerr := os.Stat(legacyDB); lerr == nil {
			dbPath = legacyDB
		}
	}

	dsn := fmt.Sprintf("file:%s?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)", dbPath)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)

	s := &Store{db: db, dbPath: dbPath}
	if err := s.migrate(); err != nil {
		_ = db.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.db == nil {
		return nil
	}
	return s.db.Close()
}

func (s *Store) DBPath() string { return s.dbPath }

func (s *Store) migrate() error {
	stmts := []string{
		`CREATE TABLE IF NOT EXISTS transcripts (
			name TEXT PRIMARY KEY,
			started_at TEXT,
			duration_s REAL,
			speech_s REAL,
			engine TEXT,
			model TEXT,
			text TEXT,
			segments TEXT,
			scene_tags_json TEXT DEFAULT '[]',
			created_at TEXT DEFAULT (datetime('now','localtime'))
		)`,
		`CREATE INDEX IF NOT EXISTS idx_transcripts_started ON transcripts(started_at DESC)`,
		`CREATE TABLE IF NOT EXISTS speakers (
			id TEXT PRIMARY KEY,
			centroid TEXT,
			weight REAL DEFAULT 0,
			merged_into TEXT,
			created_at TEXT DEFAULT (datetime('now','localtime')),
			first_seen TEXT,
			sample TEXT,
			sample_name TEXT,
			sample_start REAL,
			embedding_model TEXT DEFAULT '',
			display_name TEXT DEFAULT ''
		)`,
		`CREATE TABLE IF NOT EXISTS asr_runs (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			name TEXT,
			ok INTEGER NOT NULL,
			latency_ms INTEGER NOT NULL,
			duration_s REAL DEFAULT 0,
			engine TEXT,
			model TEXT,
			error TEXT,
			created_at TEXT DEFAULT (datetime('now','localtime'))
		)`,
	}
	for _, q := range stmts {
		if _, err := s.db.Exec(q); err != nil {
			return err
		}
	}

	// 兼容旧版 speakers 表缺列的情况
	existingCols := map[string]bool{}
	rows, err := s.db.Query(`PRAGMA table_info(speakers)`)
	if err == nil {
		for rows.Next() {
			var cid, notNull, pk int
			var name, ctype string
			var dflt sql.NullString
			if rows.Scan(&cid, &name, &ctype, &notNull, &dflt, &pk) == nil {
				existingCols[name] = true
			}
		}
		rows.Close()
	}
	for _, col := range [][2]string{
		{"first_seen", "TEXT"},
		{"sample", "TEXT"},
		{"sample_name", "TEXT"},
		{"sample_start", "REAL"},
		{"embedding_model", "TEXT DEFAULT ''"},
		{"display_name", "TEXT DEFAULT ''"},
	} {
		if !existingCols[col[0]] {
			_, _ = s.db.Exec(fmt.Sprintf(`ALTER TABLE speakers ADD COLUMN %s %s`, col[0], col[1]))
		}
	}

	trCols := map[string]bool{}
	if tRows, terr := s.db.Query(`PRAGMA table_info(transcripts)`); terr == nil {
		for tRows.Next() {
			var cid, notNull, pk int
			var name, ctype string
			var dflt sql.NullString
			if tRows.Scan(&cid, &name, &ctype, &notNull, &dflt, &pk) == nil {
				trCols[name] = true
			}
		}
		tRows.Close()
	}
	if !trCols["scene_tags_json"] {
		_, _ = s.db.Exec(`ALTER TABLE transcripts ADD COLUMN scene_tags_json TEXT DEFAULT '[]'`)
	}
	return nil
}

func (s *Store) SaveTranscript(p protocol.TranscriptPayload) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	rawSegs, err := json.Marshal(p.Segments)
	if err != nil {
		return err
	}
	sceneSlice := p.SceneTags
	if sceneSlice == nil {
		sceneSlice = []protocol.SceneTag{}
	}
	rawScenes, err := json.Marshal(sceneSlice)
	if err != nil {
		return err
	}
	_, err = s.db.Exec(`
		INSERT OR REPLACE INTO transcripts(name, started_at, duration_s, speech_s, engine, model, text, segments, scene_tags_json)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		p.Name, p.StartedAt, p.DurationS, p.SpeechS, p.Engine, p.Model, p.Text, string(rawSegs), string(rawScenes),
	)
	return err
}

func (s *Store) RecordASRRun(name string, ok bool, latencyMs int64, durationS float64, engine, model, errMsg string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	okInt := 0
	if ok {
		okInt = 1
	}
	if len(errMsg) > 200 {
		errMsg = errMsg[:200]
	}
	_, _ = s.db.Exec(`
		INSERT INTO asr_runs(name, ok, latency_ms, duration_s, engine, model, error)
		VALUES (?, ?, ?, ?, ?, ?, ?)`,
		name, okInt, latencyMs, durationS, engine, model, errMsg,
	)
	// 只保留最近 500 条运行诊断，不无限膨胀
	_, _ = s.db.Exec(`DELETE FROM asr_runs WHERE id NOT IN (SELECT id FROM asr_runs ORDER BY id DESC LIMIT 500)`)
}

// speakerMapLocked 返回 speakerID -> (canonicalID, displayLabel)。
func (s *Store) speakerMapLocked() map[string]SpeakerBadge {
	records, _ := s.readSpeakersLocked()
	return speakerMapFromRecords(records)
}

func formatDefaultSpeakerLabel(sid string) string {
	if strings.HasPrefix(sid, "spk_") {
		return "说话人 " + strings.TrimPrefix(sid, "spk_")
	}
	if strings.HasPrefix(sid, "other_") {
		return "对话人 " + strings.TrimPrefix(sid, "other_")
	}
	return sid
}

// matchingSpeakerIDs expands a source ID or display name to the entire merged group.
func matchingSpeakerIDs(who string, spkMap map[string]SpeakerBadge) map[string]bool {
	who = strings.ToLower(strings.TrimSpace(who))
	canonical := map[string]bool{}
	for rawID, badge := range spkMap {
		if strings.ToLower(rawID) == who || strings.ToLower(badge.ID) == who {
			canonical[badge.ID] = true
		}
	}
	if len(canonical) == 0 && who != "" {
		for _, badge := range spkMap {
			if strings.Contains(strings.ToLower(badge.Label), who) {
				canonical[badge.ID] = true
			}
		}
	}
	out := map[string]bool{}
	for rawID, badge := range spkMap {
		if canonical[badge.ID] {
			out[rawID], out[badge.ID] = true, true
		}
	}
	if len(out) == 0 && who != "" {
		out[who] = true // Includes recording-local other_N speakers.
	}
	return out
}

const segmentRows = `json_each(CASE WHEN json_valid(segments) THEN segments ELSE '[]' END)`

func speakerCondition(ids map[string]bool) (string, []any) {
	keys := make([]string, 0, len(ids))
	for id := range ids {
		keys = append(keys, id)
	}
	sort.Strings(keys)
	args := make([]any, len(keys))
	for i, id := range keys {
		args[i] = id
	}
	return `EXISTS (SELECT 1 FROM ` + segmentRows + ` AS seg
		WHERE json_extract(seg.value, '$.speaker') IN (` + strings.TrimSuffix(strings.Repeat("?,", len(keys)), ",") + `))`, args
}

func buildWhereClause(f ArchiveFilter, spkMap map[string]SpeakerBadge) (string, []any) {
	var conds []string
	var args []any

	now := time.Now()
	date := strings.TrimSpace(f.DateRange)
	switch date {
	case "", "all":
	case "today":
		conds = append(conds, "started_at >= ?")
		args = append(args, now.Format("2006-01-02")+"T00:00:00")
	case "yesterday":
		day := now.AddDate(0, 0, -1).Format("2006-01-02")
		conds = append(conds, "started_at >= ? AND started_at < ?")
		args = append(args, day+"T00:00:00", now.Format("2006-01-02")+"T00:00:00")
	case "7d", "30d":
		days := -6
		if date == "30d" {
			days = -29
		}
		conds = append(conds, "started_at >= ?")
		args = append(args, now.AddDate(0, 0, days).Format("2006-01-02")+"T00:00:00")
	default:
		if day, err := time.Parse("2006-01-02", date); err == nil {
			conds = append(conds, "started_at >= ? AND started_at < ?")
			args = append(args, date+"T00:00:00", day.AddDate(0, 0, 1).Format("2006-01-02")+"T00:00:00")
		}
	}

	if q := strings.TrimSpace(f.Query); q != "" {
		// Query strings are literal substrings, including SQL LIKE metacharacters.
		like := "%" + strings.NewReplacer(`\`, `\\`, "%", `\%`, "_", `\_`).Replace(q) + "%"
		sub := []string{`text LIKE ? ESCAPE '\'`, `name LIKE ? ESCAPE '\'`,
			`EXISTS (SELECT 1 FROM ` + segmentRows + ` AS seg WHERE json_extract(seg.value, '$.text') LIKE ? ESCAPE '\')`}
		args = append(args, like, like, like)
		matched := map[string]bool{}
		for rawID, badge := range spkMap {
			if strings.Contains(strings.ToLower(badge.Label), strings.ToLower(q)) ||
				strings.Contains(strings.ToLower(badge.ID), strings.ToLower(q)) {
				matched[rawID] = true
			}
		}
		if len(matched) > 0 {
			condition, values := speakerCondition(matched)
			sub = append(sub, condition)
			args = append(args, values...)
		}
		conds = append(conds, "("+strings.Join(sub, " OR ")+")")
	}

	if spk := strings.TrimSpace(f.SpeakerID); spk != "" {
		condition, values := speakerCondition(matchingSpeakerIDs(spk, spkMap))
		conds = append(conds, condition)
		args = append(args, values...)
	}
	if len(conds) == 0 {
		return "", nil
	}
	return " WHERE " + strings.Join(conds, " AND "), args
}

func (s *Store) QueryArchive(f ArchiveFilter) (ArchivePage, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	unlimited := f.Limit < 0
	if !unlimited && (f.Limit == 0 || f.Limit > 500) {
		f.Limit = 150
	}
	if f.Offset < 0 {
		f.Offset = 0
	}
	spkMap := s.speakerMapLocked()
	where, args := buildWhereClause(f, spkMap)

	var total int
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM transcripts`+where, args...).Scan(&total)

	var querySQL string
	var qArgs []any
	if unlimited {
		querySQL = `
			SELECT name, COALESCE(started_at, ''), COALESCE(duration_s, 0), COALESCE(speech_s, 0),
			       COALESCE(engine, ''), COALESCE(model, ''), COALESCE(text, ''), COALESCE(segments, '[]')
			FROM transcripts` + where + ` ORDER BY name DESC`
		qArgs = args
	} else {
		querySQL = `
			SELECT name, COALESCE(started_at, ''), COALESCE(duration_s, 0), COALESCE(speech_s, 0),
			       COALESCE(engine, ''), COALESCE(model, ''), COALESCE(text, ''), COALESCE(segments, '[]')
			FROM transcripts` + where + ` ORDER BY name DESC LIMIT ? OFFSET ?`
		qArgs = append(append([]any{}, args...), f.Limit, f.Offset)
	}
	rows, err := s.db.Query(querySQL, qArgs...)
	if err != nil {
		return ArchivePage{}, err
	}
	defer rows.Close()

	items := []ArchiveItem{}
	for rows.Next() {
		var it ArchiveItem
		var fullText, rawSegs string
		if err := rows.Scan(&it.Name, &it.StartedAt, &it.DurationS, &it.SpeechS, &it.Engine, &it.Model, &fullText, &rawSegs); err != nil {
			continue
		}
		runes := []rune(strings.TrimSpace(fullText))
		if len(runes) > 96 {
			it.Preview = string(runes[:96]) + "…"
		} else {
			it.Preview = string(runes)
		}
		it.Speakers = extractSpeakerBadges(rawSegs, spkMap)
		items = append(items, it)
	}

	// 汇总所有已建档人物供筛选下拉使用
	seenSpk := map[string]bool{}
	var allSpeakers []SpeakerBadge
	for _, b := range spkMap {
		if strings.HasPrefix(b.ID, "spk_") && !seenSpk[b.ID] {
			seenSpk[b.ID] = true
			allSpeakers = append(allSpeakers, b)
		}
	}
	return ArchivePage{Items: items, Total: total, Speakers: allSpeakers}, nil
}

func extractSpeakerBadges(rawSegs string, spkMap map[string]SpeakerBadge) []SpeakerBadge {
	var segs []protocol.Segment
	if err := json.Unmarshal([]byte(rawSegs), &segs); err != nil {
		return []SpeakerBadge{}
	}
	seen := map[string]bool{}
	out := []SpeakerBadge{}
	for _, seg := range segs {
		if seg.Speaker == nil || *seg.Speaker == "" {
			continue
		}
		rawID := *seg.Speaker
		badge, ok := spkMap[rawID]
		if !ok {
			badge = SpeakerBadge{ID: rawID, Label: formatDefaultSpeakerLabel(rawID)}
		}
		if !seen[badge.ID] {
			seen[badge.ID] = true
			out = append(out, badge)
		}
	}
	return out
}

func (s *Store) GetDetail(name string) (TranscriptDetail, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	records, err := s.readSpeakersLocked()
	if err != nil {
		return TranscriptDetail{}, err
	}
	return scanTranscript(s.db.QueryRow(`SELECT `+s.transcriptColumnsLocked()+` FROM transcripts WHERE name = ?`, name),
		speakerMapFromRecords(records))
}

func (s *Store) DeleteTranscript(name string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.db.Exec(`DELETE FROM transcripts WHERE name = ?`, name)
	return err
}

func (s *Store) ExportMarkdown(f ArchiveFilter) (string, error) {
	f.Limit = -1
	f.Offset = 0
	page, err := s.QueryArchive(f)
	if err != nil {
		return "", err
	}
	var sb strings.Builder
	sb.WriteString("# Hark 语音上下文存档\n\n")
	if len(page.Items) == 0 {
		sb.WriteString("（当前筛选条件下无记录）\n")
		return sb.String(), nil
	}

	// 按时间正序导出，便于模型按因果阅读
	for i := len(page.Items) - 1; i >= 0; i-- {
		detail, err := s.GetDetail(page.Items[i].Name)
		if err != nil {
			continue
		}
		headerTime := detail.StartedAt
		if len(headerTime) >= 16 {
			headerTime = strings.Replace(headerTime[:16], "T", " ", 1)
		}
		sb.WriteString(fmt.Sprintf("## %s（%.0f 秒）\n\n", headerTime, detail.DurationS))
		if len(detail.Segments) > 0 {
			for _, seg := range detail.Segments {
				mm := int(seg.Start) / 60
				ss := int(seg.Start) % 60
				if seg.SpeakerLabel != "" {
					sb.WriteString(fmt.Sprintf("- `%02d:%02d` **%s**：%s\n", mm, ss, seg.SpeakerLabel, seg.Text))
				} else {
					sb.WriteString(fmt.Sprintf("- `%02d:%02d` %s\n", mm, ss, seg.Text))
				}
			}
		} else if detail.Text != "" {
			sb.WriteString(detail.Text + "\n")
		}
		sb.WriteString("\n")
	}
	return sb.String(), nil
}

func (s *Store) ExportJSON(f ArchiveFilter) (string, error) {
	f.Limit = -1
	f.Offset = 0
	page, err := s.QueryArchive(f)
	if err != nil {
		return "", err
	}
	details := make([]TranscriptDetail, 0, len(page.Items))
	for i := len(page.Items) - 1; i >= 0; i-- {
		if d, err := s.GetDetail(page.Items[i].Name); err == nil {
			details = append(details, d)
		}
	}
	raw, err := json.MarshalIndent(details, "", "  ")
	return string(raw), err
}

func (s *Store) BackupTo(destPath string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	destPath, err := filepath.Abs(destPath)
	if err != nil {
		return fmt.Errorf("解析备份目标失败: %w", err)
	}
	dbPath, err := filepath.Abs(s.dbPath)
	if err != nil {
		return fmt.Errorf("解析当前数据库路径失败: %w", err)
	}
	realDBPath, err := filepath.EvalSymlinks(dbPath)
	if err != nil {
		return fmt.Errorf("解析当前数据库路径失败: %w", err)
	}
	destDir, err := filepath.EvalSymlinks(filepath.Dir(destPath))
	if err != nil {
		return fmt.Errorf("解析备份目录失败: %w", err)
	}
	realDestPath := filepath.Join(destDir, filepath.Base(destPath))
	destInfo, err := os.Stat(destPath)
	if err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("检查备份目标失败: %w", err)
	}
	// Compare paths even when WAL/SHM files do not exist, and file identities
	// for symlink and hard-link aliases of any active database file.
	for _, path := range []string{dbPath, realDBPath} {
		for _, suffix := range []string{"", "-wal", "-shm"} {
			activePath := path + suffix
			if destPath == activePath || realDestPath == activePath {
				return fmt.Errorf("备份目标不能是当前数据库或其 WAL/SHM 文件: %s", destPath)
			}
			info, err := os.Stat(activePath)
			if err != nil && !os.IsNotExist(err) {
				return fmt.Errorf("检查当前数据库文件失败: %w", err)
			}
			if destInfo != nil && info != nil && os.SameFile(destInfo, info) {
				return fmt.Errorf("备份目标不能是当前数据库或其 WAL/SHM 文件: %s", destPath)
			}
		}
	}

	// VACUUM INTO accepts an empty existing file. Reserve a private name in the
	// destination directory so the final rename stays on the same filesystem.
	tmp, err := os.CreateTemp(destDir, ".hark-backup-*.tmp")
	if err != nil {
		return fmt.Errorf("创建临时备份失败: %w", err)
	}
	tmpPath := tmp.Name()
	defer os.Remove(tmpPath)
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("关闭临时备份失败: %w", err)
	}
	if _, err := s.db.Exec(`VACUUM INTO ?`, tmpPath); err != nil {
		return fmt.Errorf("备份数据库失败: %w", err)
	}
	if err := os.Rename(tmpPath, destPath); err != nil {
		return fmt.Errorf("替换备份失败: %w", err)
	}
	return nil
}

func (s *Store) Stats() (ArchiveStats, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	st := ArchiveStats{DBPath: s.dbPath}
	if info, err := os.Stat(s.dbPath); err == nil {
		st.DBSizeBytes = info.Size()
	}
	_ = s.db.QueryRow(`
		SELECT COUNT(*), COALESCE(SUM(duration_s), 0), COALESCE(SUM(speech_s), 0)
		FROM transcripts`).Scan(&st.TotalRecordings, &st.TotalDurationS, &st.TotalSpeechS)
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM speakers WHERE COALESCE(merged_into, '') = ''`).Scan(&st.SpeakerCount)

	// 最近 50 次转写指标
	rows, err := s.db.Query(`SELECT ok, latency_ms, COALESCE(error, ''), COALESCE(created_at, '') FROM asr_runs ORDER BY id DESC LIMIT 50`)
	if err == nil {
		defer rows.Close()
		var latSum int64
		for rows.Next() {
			var ok int
			var lat int64
			var errStr, createdAt string
			if rows.Scan(&ok, &lat, &errStr, &createdAt) != nil {
				continue
			}
			st.ASR.WindowTotal++
			if ok == 1 {
				st.ASR.SuccessCount++
				latSum += lat
				if st.ASR.LastSuccessAt == "" {
					st.ASR.LastSuccessAt = createdAt
				}
			} else if st.ASR.LastError == "" && errStr != "" {
				st.ASR.LastError = errStr
			}
		}
		if st.ASR.WindowTotal > 0 {
			st.ASR.SuccessRate = float64(st.ASR.SuccessCount) * 100.0 / float64(st.ASR.WindowTotal)
		}
		if st.ASR.SuccessCount > 0 {
			st.ASR.AvgLatencyMs = latSum / int64(st.ASR.SuccessCount)
		}
	}
	return st, nil
}
