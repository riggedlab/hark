package worker

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/riggedlab/hark/internal/protocol"
)

// ResolveDataDir reads only the data_dir setting; it never loads credentials or a keychain.
func ResolveDataDir(flagDir, confPath string) string {
	if flagDir != "" {
		return NormalizeDataDir(flagDir)
	}
	for _, key := range []string{"HARK_HOME", "DITING_HOME"} {
		if dir := os.Getenv(key); dir != "" {
			return NormalizeDataDir(dir)
		}
	}
	if confPath == "" {
		confPath = DefaultConfigPath()
	}
	var conf struct {
		DataDir string `json:"data_dir"`
	}
	if raw, err := os.ReadFile(expandHome(confPath)); err == nil {
		if json.Unmarshal(raw, &conf) == nil && strings.TrimSpace(conf.DataDir) != "" {
			return NormalizeDataDir(conf.DataDir)
		}
	}
	return DefaultDataDir()
}

// OpenReadOnlyStore never creates a database or migrates its schema. Do not use
// immutable=1 here: a live worker may have newer committed data in the WAL.
func OpenReadOnlyStore(dataDir string) (*Store, bool, error) {
	dir, err := filepath.Abs(NormalizeDataDir(dataDir))
	if err != nil {
		return nil, false, err
	}
	for _, name := range []string{"hark.db", "diting.db"} {
		path := filepath.Join(dir, name)
		info, err := os.Stat(path)
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			return nil, false, err
		}
		if !info.Mode().IsRegular() {
			continue
		}
		db, err := sql.Open("sqlite", readOnlyURI(path))
		if err != nil {
			return nil, true, err
		}
		db.SetMaxOpenConns(1)
		if err := db.Ping(); err != nil {
			_ = db.Close()
			return nil, true, err
		}
		return &Store{db: db, dbPath: path}, true, nil
	}
	return nil, false, nil
}

func readOnlyURI(path string) string {
	uriPath := filepath.ToSlash(path)
	// On Windows a drive letter belongs in /C:/..., never in the URI authority.
	if !strings.HasPrefix(uriPath, "/") {
		uriPath = "/" + uriPath
	}
	u := url.URL{Scheme: "file", Path: uriPath}
	u.RawQuery = "mode=ro&_pragma=busy_timeout(5000)&_pragma=query_only(ON)"
	return u.String()
}

type ReadOnlyStats struct {
	DBExists        bool    `json:"db_exists"`
	DBPath          string  `json:"db_path"`
	DBSizeBytes     int64   `json:"db_size_bytes"`
	TotalRecordings int     `json:"total_recordings"`
	TotalDurationS  float64 `json:"total_duration_s"`
	TotalSpeechS    float64 `json:"total_speech_s"`
	SpeakerCount    int     `json:"speaker_count"`
	LatestName      string  `json:"latest_name"`
	LatestStartedAt string  `json:"latest_started_at"`
}

func (s *Store) ReadOnlyStats() (ReadOnlyStats, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	st := ReadOnlyStats{DBExists: true, DBPath: s.dbPath}
	info, err := os.Stat(s.dbPath)
	if err != nil {
		return st, err
	}
	st.DBSizeBytes = info.Size()
	if err := s.db.QueryRow(`SELECT COUNT(*), COALESCE(SUM(duration_s), 0),
		COALESCE(SUM(speech_s), 0) FROM transcripts`).Scan(
		&st.TotalRecordings, &st.TotalDurationS, &st.TotalSpeechS); err != nil {
		return st, err
	}
	err = s.db.QueryRow(`SELECT name, COALESCE(started_at, '') FROM transcripts
		ORDER BY name DESC LIMIT 1`).Scan(&st.LatestName, &st.LatestStartedAt)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return st, err
	}
	people, err := s.listSpeakersLocked()
	st.SpeakerCount = len(people)
	return st, err
}

type SpeakerInfo struct {
	ID          string   `json:"id"`
	Label       string   `json:"label"`
	MergedIDs   []string `json:"merged_ids"`
	FirstSeen   string   `json:"first_seen"`
	Sample      string   `json:"sample"`
	SampleName  string   `json:"sample_name"`
	SampleStart float64  `json:"sample_start"`
	WeightS     float64  `json:"weight_s"`
}

type storedSpeaker struct {
	SpeakerInfo
	mergedInto string
}

// Missing metadata columns (or a missing early-version speakers table) have
// empty defaults. All identifiers below come from this fixed list, not input.
func (s *Store) readSpeakersLocked() ([]storedSpeaker, error) {
	rows, err := s.db.Query(`PRAGMA table_info(speakers)`)
	if err != nil {
		return nil, err
	}
	cols := map[string]bool{}
	for rows.Next() {
		var cid, notNull, pk int
		var name, kind string
		var def sql.NullString
		if err := rows.Scan(&cid, &name, &kind, &notNull, &def, &pk); err != nil {
			rows.Close()
			return nil, err
		}
		cols[name] = true
	}
	err = rows.Err()
	rows.Close()
	if err != nil || !cols["id"] {
		return []storedSpeaker{}, err
	}
	exprs := []string{"id"}
	for _, col := range []string{"display_name", "merged_into", "first_seen", "sample", "sample_name", "sample_start", "weight"} {
		fallback := "''"
		if col == "sample_start" || col == "weight" {
			fallback = "0"
		}
		expr := fallback
		if cols[col] {
			expr = "COALESCE(" + col + ", " + fallback + ")"
		}
		exprs = append(exprs, expr)
	}
	rows, err = s.db.Query(`SELECT ` + strings.Join(exprs, ", ") + ` FROM speakers`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []storedSpeaker{}
	for rows.Next() {
		var sp storedSpeaker
		if err := rows.Scan(&sp.ID, &sp.Label, &sp.mergedInto, &sp.FirstSeen, &sp.Sample,
			&sp.SampleName, &sp.SampleStart, &sp.WeightS); err != nil {
			return nil, err
		}
		sp.MergedIDs = []string{}
		if sp.Label == "" {
			sp.Label = formatDefaultSpeakerLabel(sp.ID)
		}
		out = append(out, sp)
	}
	return out, rows.Err()
}

func speakerMapFromRecords(records []storedSpeaker) map[string]SpeakerBadge {
	raw := make(map[string]storedSpeaker, len(records))
	for _, sp := range records {
		raw[sp.ID] = sp
	}
	out := make(map[string]SpeakerBadge, len(records))
	for _, sp := range records {
		id := sp.ID
		seen := map[string]bool{}
		for !seen[id] {
			seen[id] = true
			next := raw[id].mergedInto
			if next == "" {
				break
			}
			if _, exists := raw[next]; !exists {
				break
			}
			id = next
		}
		out[sp.ID] = SpeakerBadge{ID: id, Label: raw[id].Label}
	}
	return out
}

func (s *Store) listSpeakersLocked() ([]SpeakerInfo, error) {
	records, err := s.readSpeakersLocked()
	if err != nil {
		return nil, err
	}
	spkMap := speakerMapFromRecords(records)
	out := []SpeakerInfo{}
	for _, sp := range records {
		if sp.mergedInto != "" {
			continue
		}
		for _, source := range records {
			if source.ID != sp.ID && spkMap[source.ID].ID == sp.ID {
				sp.MergedIDs = append(sp.MergedIDs, source.ID)
			}
		}
		sort.Strings(sp.MergedIDs)
		out = append(out, sp.SpeakerInfo)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].WeightS == out[j].WeightS {
			return out[i].ID < out[j].ID
		}
		return out[i].WeightS > out[j].WeightS
	})
	return out, nil
}

func (s *Store) ListSpeakers() ([]SpeakerInfo, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.listSpeakersLocked()
}

const transcriptColumns = `name, COALESCE(started_at, ''), COALESCE(duration_s, 0),
	COALESCE(speech_s, 0), COALESCE(engine, ''), COALESCE(model, ''),
	COALESCE(text, ''), COALESCE(segments, '[]')`

func scanTranscript(row interface{ Scan(...any) error }, spkMap map[string]SpeakerBadge) (TranscriptDetail, error) {
	var d TranscriptDetail
	var raw string
	err := row.Scan(&d.Name, &d.StartedAt, &d.DurationS, &d.SpeechS, &d.Engine, &d.Model, &d.Text, &raw)
	if err != nil {
		return d, err
	}
	var segs []protocol.Segment
	if err := json.Unmarshal([]byte(raw), &segs); err != nil && raw != "" {
		return d, fmt.Errorf("录音 %s 的 segments 无法解析: %w", d.Name, err)
	}
	d.Segments = make([]ResolvedSegment, 0, len(segs))
	for _, seg := range segs {
		rs := ResolvedSegment{Start: seg.Start, End: seg.End, Text: seg.Text, DB: seg.DB, ActiveS: seg.ActiveS}
		if seg.Speaker != nil && *seg.Speaker != "" {
			badge, ok := spkMap[*seg.Speaker]
			if !ok {
				badge = SpeakerBadge{ID: *seg.Speaker, Label: formatDefaultSpeakerLabel(*seg.Speaker)}
			}
			rs.SpeakerID, rs.SpeakerLabel = badge.ID, badge.Label
		}
		d.Segments = append(d.Segments, rs)
	}
	return d, nil
}

// readArchiveLocked fetches complete transcripts in one paged SELECT, rather
// than issuing a GetDetail query for every recording.
func (s *Store) readArchiveLocked(f ArchiveFilter) ([]TranscriptDetail, int, map[string]SpeakerBadge, error) {
	records, err := s.readSpeakersLocked()
	if err != nil {
		return nil, 0, nil, err
	}
	spkMap := speakerMapFromRecords(records)
	where, args := buildWhereClause(f, spkMap)
	var total int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM transcripts`+where, args...).Scan(&total); err != nil {
		return nil, 0, nil, err
	}
	if f.Limit <= 0 {
		f.Limit = 200
	}
	if f.Offset < 0 {
		f.Offset = 0
	}
	rows, err := s.db.Query(`SELECT `+transcriptColumns+` FROM transcripts`+where+
		` ORDER BY name DESC LIMIT ? OFFSET ?`, append(args, f.Limit, f.Offset)...)
	if err != nil {
		return nil, 0, nil, err
	}
	defer rows.Close()
	items := []TranscriptDetail{}
	for rows.Next() {
		d, err := scanTranscript(rows, spkMap)
		if err != nil {
			return nil, 0, nil, err
		}
		items = append(items, d)
	}
	return items, total, spkMap, rows.Err()
}

func (s *Store) Timeline(f ArchiveFilter) ([]TranscriptDetail, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	items, total, _, err := s.readArchiveLocked(f)
	for i, j := 0, len(items)-1; i < j; i, j = i+1, j-1 {
		items[i], items[j] = items[j], items[i]
	}
	return items, total, err
}

type SearchItem struct {
	ArchiveItem
	MatchedSegments []ResolvedSegment `json:"matched_segments"`
}

type SearchPage struct {
	Items []SearchItem `json:"items"`
	Total int          `json:"total"`
}

func (s *Store) Search(f ArchiveFilter) (SearchPage, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if f.Limit <= 0 {
		f.Limit = 30
	}
	details, total, spkMap, err := s.readArchiveLocked(f)
	if err != nil {
		return SearchPage{}, err
	}
	page := SearchPage{Items: []SearchItem{}, Total: total}
	q := strings.ToLower(strings.TrimSpace(f.Query))
	who := matchingSpeakerIDs(f.SpeakerID, spkMap)
	for _, d := range details {
		it := SearchItem{ArchiveItem: ArchiveItem{
			Name: d.Name, StartedAt: d.StartedAt, DurationS: d.DurationS, SpeechS: d.SpeechS,
			Engine: d.Engine, Model: d.Model, Speakers: []SpeakerBadge{},
		}, MatchedSegments: []ResolvedSegment{}}
		seen := map[string]bool{}
		for _, seg := range d.Segments {
			if seg.SpeakerID != "" && !seen[seg.SpeakerID] {
				seen[seg.SpeakerID] = true
				it.Speakers = append(it.Speakers, SpeakerBadge{ID: seg.SpeakerID, Label: seg.SpeakerLabel})
			}
			if strings.TrimSpace(f.SpeakerID) != "" && !who[seg.SpeakerID] {
				continue
			}
			if q == "" || strings.Contains(strings.ToLower(seg.Text), q) ||
				strings.Contains(strings.ToLower(seg.SpeakerLabel), q) || strings.Contains(strings.ToLower(seg.SpeakerID), q) {
				it.MatchedSegments = append(it.MatchedSegments, seg)
			}
		}
		if len(it.MatchedSegments) == 0 && q != "" {
			for _, seg := range d.Segments {
				if strings.Contains(strings.ToLower(seg.Text), q) ||
					strings.Contains(strings.ToLower(seg.SpeakerLabel), q) || strings.Contains(strings.ToLower(seg.SpeakerID), q) {
					it.MatchedSegments = append(it.MatchedSegments, seg)
				}
			}
		}
		if len(it.MatchedSegments) == 0 {
			if d.Text != "" {
				it.MatchedSegments = append(it.MatchedSegments, ResolvedSegment{
					Start: 0, End: d.DurationS, Text: textPreview(d.Text, q),
				})
			} else if len(d.Segments) > 0 {
				it.MatchedSegments = append(it.MatchedSegments, d.Segments[0])
			}
		}
		if len(it.MatchedSegments) > 0 {
			it.Preview = textPreview(it.MatchedSegments[0].Text, q)
		}
		page.Items = append(page.Items, it)
	}
	return page, nil
}

// Keep previews short, but center them on the match rather than truncating it away.
func textPreview(text, query string) string {
	runes := []rune(strings.TrimSpace(text))
	start := 0
	if query != "" {
		lower := strings.ToLower(string(runes))
		if idx := strings.Index(lower, query); idx >= 0 {
			start = len([]rune(lower[:idx])) - 32
			if start < 0 {
				start = 0
			}
		}
	}
	end := start + 160
	if end > len(runes) {
		end = len(runes)
	}
	out := string(runes[start:end])
	if start > 0 {
		out = "…" + out
	}
	if end < len(runes) {
		out += "…"
	}
	return out
}
