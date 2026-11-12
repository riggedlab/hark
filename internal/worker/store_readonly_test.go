package worker

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/riggedlab/hark/internal/e2ee"
	"github.com/riggedlab/hark/internal/protocol"
)

func TestResolveDataDirPrecedence(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("HARK_HOME", "")
	t.Setenv("DITING_HOME", "")
	conf := filepath.Join(t.TempDir(), "config.json")
	t.Setenv("HARK_CONF", conf)
	// Invalid credentials and unrelated fields must not affect archive queries.
	if err := os.WriteFile(conf, []byte(`{"data_dir":"~/archive","keychain_stored":true,"asr":123}`), 0o600); err != nil {
		t.Fatal(err)
	}
	check := func(flag, path, want string) {
		t.Helper()
		if got := ResolveDataDir(flag, path); got != want {
			t.Fatalf("ResolveDataDir(%q, %q) = %q; want %q", flag, path, got, want)
		}
	}
	check("", "", filepath.Join(home, "archive"))
	t.Setenv("DITING_HOME", "~/legacy")
	check("", conf, filepath.Join(home, "legacy"))
	t.Setenv("HARK_HOME", "~/current")
	check("", conf, filepath.Join(home, "current"))
	check("~/explicit", conf, filepath.Join(home, "explicit"))
	t.Setenv("HARK_HOME", "")
	t.Setenv("DITING_HOME", "")
	check("", conf+".missing", filepath.Join(home, "hark"))
	for _, content := range []string{`{`, `{"data_dir":42}`, `{}`, `{"data_dir":""}`} {
		if err := os.WriteFile(conf, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
		check("", conf, filepath.Join(home, "hark"))
	}
}

func TestReadOnlyMissingStoreNeverCreatesFiles(t *testing.T) {
	root := t.TempDir()
	for _, dir := range []string{root, filepath.Join(root, "not-created")} {
		s, exists, err := OpenReadOnlyStore(dir)
		if s != nil || exists || err != nil {
			t.Fatalf("unexpected missing store result: %v %v %v", s, exists, err)
		}
	}
	files, err := os.ReadDir(root)
	if err != nil || len(files) != 0 {
		t.Fatalf("read-only open created files: %v %v", files, err)
	}
}

func TestReadOnlyURIWindowsDrive(t *testing.T) {
	u, err := url.Parse(readOnlyURI("C:/archive #?/hark.db"))
	if err != nil {
		t.Fatal(err)
	}
	if u.Host != "" || u.Path != "/C:/archive #?/hark.db" || u.Query().Get("mode") != "ro" {
		t.Fatalf("drive letter must be a path, not a URI authority: host=%q path=%q", u.Host, u.Path)
	}
}

func TestReadOnlyLegacySpeakersNoMigrationAndEscapedURI(t *testing.T) {
	for _, displayName := range []bool{false, true} {
		t.Run(fmt.Sprint(displayName), func(t *testing.T) {
			root := t.TempDir()
			dir := filepath.Join(root, "initial")
			if err := os.Mkdir(dir, 0o700); err != nil {
				t.Fatal(err)
			}
			path := filepath.Join(dir, "diting.db")
			db, err := sql.Open("sqlite", path)
			if err != nil {
				t.Fatal(err)
			}
			extra := ""
			if displayName {
				extra = ", display_name TEXT DEFAULT '老王'"
			}
			for _, query := range []string{
				`CREATE TABLE speakers (id TEXT PRIMARY KEY, weight REAL, merged_into TEXT` + extra + `)`,
				`INSERT INTO speakers (id, weight) VALUES ('spk_001', 12)`,
				`CREATE TABLE transcripts (name TEXT PRIMARY KEY, started_at TEXT, duration_s REAL, speech_s REAL,
				engine TEXT, model TEXT, text TEXT, segments TEXT)`,
				`INSERT INTO transcripts VALUES ('20261112-143000.opus', '2026-11-12T14:30:00', 10, 8, '', '', '接口',
				'[ {"start":0,"end":8,"text":"接口","speaker":"spk_001"} ]')`,
			} {
				if _, err := db.Exec(query); err != nil {
					t.Fatal(err)
				}
			}
			if err := db.Close(); err != nil {
				t.Fatal(err)
			}
			escapedDir := filepath.Join(root, "存档 # ? & %")
			if err := os.Rename(dir, escapedDir); err != nil {
				t.Fatal(err)
			}
			path = filepath.Join(escapedDir, "diting.db")
			before, err := os.ReadFile(path)
			if err != nil {
				t.Fatal(err)
			}
			// A directory named hark.db is not a database file; try the legacy file.
			if err := os.Mkdir(filepath.Join(escapedDir, "hark.db"), 0o700); err != nil {
				t.Fatal(err)
			}
			ro, exists, err := OpenReadOnlyStore(escapedDir)
			if err != nil || !exists || ro.DBPath() != path {
				t.Fatalf("legacy open: %v %v", exists, err)
			}
			people, err := ro.ListSpeakers()
			label := "说话人 001"
			if displayName {
				label = "老王"
			}
			if err != nil || len(people) != 1 || people[0].Label != label || people[0].FirstSeen != "" {
				t.Fatalf("legacy speakers: %+v %v", people, err)
			}
			d, err := ro.GetDetail("20261112-143000.opus")
			if err != nil || len(d.Segments) != 1 || d.Segments[0].SpeakerLabel != label {
				t.Fatalf("legacy detail: %+v %v", d, err)
			}
			st, err := ro.ReadOnlyStats()
			if err != nil || !st.DBExists || st.TotalRecordings != 1 || st.SpeakerCount != 1 || st.DBSizeBytes == 0 {
				t.Fatalf("legacy stats: %+v %v", st, err)
			}
			if _, _, err := ro.Timeline(ArchiveFilter{SpeakerID: label}); err != nil {
				t.Fatal(err)
			}
			if _, err := ro.Search(ArchiveFilter{Query: "接口"}); err != nil {
				t.Fatal(err)
			}
			for _, query := range []string{`DELETE FROM transcripts`, `CREATE TABLE unwanted (id TEXT)`,
				`CREATE TEMP TABLE unwanted (id TEXT)`, `ALTER TABLE speakers ADD COLUMN unwanted TEXT`} {
				if _, err := ro.db.Exec(query); err == nil {
					t.Fatalf("write succeeded: %s", query)
				}
			}
			// mode=ro still protects the main database if query_only is disabled.
			if _, err := ro.db.Exec(`PRAGMA query_only=OFF`); err != nil {
				t.Fatal(err)
			}
			if err := ro.DeleteTranscript(d.Name); err == nil {
				t.Fatal("mode=ro accepted deletion")
			}
			if err := ro.Close(); err != nil {
				t.Fatal(err)
			}
			after, err := os.ReadFile(path)
			if err != nil || !bytes.Equal(before, after) {
				t.Fatalf("read-only open changed the database: %v", err)
			}
		})
	}
}

func newQueryFixture(t *testing.T) (*Store, *Store) {
	t.Helper()
	dir := t.TempDir()
	w, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = w.Close() })
	for _, q := range []string{
		`INSERT INTO speakers(id, weight, display_name, first_seen, sample, sample_name, sample_start)
		VALUES ('spk_001', 50, '老王 Wang', '2026-11-01T10:00:00', '接口上线', '20261101-100000.opus', 2.5)`,
		`INSERT INTO speakers(id, weight, merged_into) VALUES ('spk_002', 10, 'spk_001'), ('spk_003', 5, 'spk_002')`,
		`INSERT INTO speakers(id, weight, display_name) VALUES ('spk_004', 20, 'Alice'), ('spk_005', 20, 'Bob')`,
	} {
		if _, err := w.db.Exec(q); err != nil {
			t.Fatal(err)
		}
	}
	ids := []string{"spk_001", "spk_002", "spk_003", "spk_004"}
	for i, id := range ids {
		rec := protocol.TranscriptPayload{Name: fmt.Sprintf("20261112-%06d.opus", i), StartedAt: "2026-11-12T00:00:00",
			DurationS: 10, SpeechS: 8, Text: "开场白。讨论接口。结束。", Segments: []protocol.Segment{
				{Start: 0, End: 2, Text: "开场白", Speaker: &id},
				{Start: 2, End: 8, Text: "讨论接口", Speaker: &id},
			}}
		if err := w.SaveTranscript(rec); err != nil {
			t.Fatal(err)
		}
	}
	ro, exists, err := OpenReadOnlyStore(dir)
	if err != nil || !exists {
		t.Fatalf("open: %v %v", exists, err)
	}
	t.Cleanup(func() { _ = ro.Close() })
	return w, ro
}

func TestTimelineSpeakerMergesAndSearchPreviews(t *testing.T) {
	w, ro := newQueryFixture(t)
	for _, who := range []string{"spk_001", "spk_002", "spk_003", "SPK_001", "老王", "wang", "WANG"} {
		items, total, err := ro.Timeline(ArchiveFilter{SpeakerID: who, Limit: 2, Offset: 1})
		if err != nil || total != 3 || len(items) != 2 || items[0].Name != "20261112-000000.opus" || items[1].Name != "20261112-000001.opus" {
			t.Fatalf("timeline %q: %+v total=%d err=%v", who, items, total, err)
		}
		if len(items[1].Segments) != 2 || items[1].Segments[1].SpeakerID != "spk_001" || items[1].Segments[1].SpeakerLabel != "老王 Wang" {
			t.Fatalf("unresolved merged speaker: %+v", items[1])
		}
	}
	people, err := ro.ListSpeakers()
	if err != nil || len(people) != 3 || people[0].ID != "spk_001" || people[1].ID != "spk_004" || people[2].ID != "spk_005" ||
		!reflect.DeepEqual(people[0].MergedIDs, []string{"spk_002", "spk_003"}) || people[0].SampleStart != 2.5 {
		t.Fatalf("speakers: %+v %v", people, err)
	}
	page, err := ro.Search(ArchiveFilter{Query: "接口", SpeakerID: "老王", Limit: 2})
	if err != nil || page.Total != 3 || len(page.Items) != 2 || len(page.Items[0].MatchedSegments) != 1 || page.Items[0].MatchedSegments[0].Start != 2 {
		t.Fatalf("matched segments: %+v %v", page, err)
	}
	for _, filter := range []ArchiveFilter{{SpeakerID: "老王"}, {Query: "老王"}, {Query: "20261112"}, {}} {
		page, err := ro.Search(filter)
		if err != nil || len(page.Items) == 0 || len(page.Items[0].MatchedSegments) == 0 {
			t.Fatalf("preview fallback: %+v %+v %v", filter, page, err)
		}
	}
	if err := w.SaveTranscript(protocol.TranscriptPayload{Name: "20261112-010000.opus", DurationS: 12,
		Text: strings.Repeat("很长的前文", 80) + "唯一关键词与后文 100% _值"}); err != nil {
		t.Fatal(err)
	}
	for _, q := range []string{"唯一关键词", "100%", "_值"} {
		page, err := ro.Search(ArchiveFilter{Query: q})
		if err != nil || page.Total != 1 || len(page.Items[0].MatchedSegments) != 1 || !strings.Contains(page.Items[0].Preview, q) {
			t.Fatalf("text-only preview %q: %+v %v", q, page, err)
		}
	}
	// A speaker ID in ordinary text must not count as participation.
	if err := w.SaveTranscript(protocol.TranscriptPayload{Name: "20261112-020000.opus", Segments: []protocol.Segment{{Text: "spk_001"}}}); err != nil {
		t.Fatal(err)
	}
	if _, total, err := ro.Timeline(ArchiveFilter{SpeakerID: "spk_001"}); err != nil || total != 3 {
		t.Fatalf("speaker matched transcript text: total=%d err=%v", total, err)
	}
	st, err := ro.ReadOnlyStats()
	if err != nil || st.TotalRecordings != 6 || st.TotalDurationS != 52 || st.TotalSpeechS != 32 || st.SpeakerCount != 3 || st.LatestName != "20261112-020000.opus" {
		t.Fatalf("stats: %+v %v", st, err)
	}
}

func TestTimelineYesterdayLocalBoundaries(t *testing.T) {
	w, ro := newQueryFixture(t)
	if _, err := w.db.Exec(`DELETE FROM transcripts`); err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	yesterday := now.AddDate(0, 0, -1).Format("2006-01-02")
	for i, started := range []string{
		now.AddDate(0, 0, -2).Format("2006-01-02") + "T23:59:59",
		yesterday + "T00:00:00", yesterday + "T23:59:59.999",
		now.Format("2006-01-02") + "T00:00:00",
	} {
		if err := w.SaveTranscript(protocol.TranscriptPayload{Name: fmt.Sprintf("rec-%d.opus", i), StartedAt: started}); err != nil {
			t.Fatal(err)
		}
	}
	for _, date := range []string{"yesterday", yesterday} {
		items, total, err := ro.Timeline(ArchiveFilter{DateRange: date})
		if err != nil || total != 2 || len(items) != 2 || items[0].Name != "rec-1.opus" || items[1].Name != "rec-2.opus" {
			t.Fatalf("date %s: %+v %d %v", date, items, total, err)
		}
	}
}

func TestReadOnlyWALReadsDuringUncommittedWriteAndAfterCommit(t *testing.T) {
	w, ro := newQueryFixture(t)
	var mode string
	if err := w.db.QueryRow(`PRAGMA journal_mode`).Scan(&mode); err != nil || mode != "wal" {
		t.Fatalf("not using WAL: %s %v", mode, err)
	}
	tx, err := w.db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`INSERT INTO transcripts(name, text) VALUES ('pending.opus', '提交后可见')`); err != nil {
		t.Fatal(err)
	}
	read := make(chan error, 1)
	go func() {
		st, err := ro.ReadOnlyStats()
		if err == nil && st.TotalRecordings != 4 {
			err = fmt.Errorf("saw uncommitted row: %+v", st)
		}
		read <- err
	}()
	select {
	case err := <-read:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("reader blocked behind a WAL writer")
	}
	if err := tx.Commit(); err != nil {
		t.Fatal(err)
	}
	if st, err := ro.ReadOnlyStats(); err != nil || st.TotalRecordings != 5 || st.LatestName != "pending.opus" {
		t.Fatalf("did not see committed WAL data: %+v %v", st, err)
	}
	done := make(chan error, 1)
	go func() {
		for i := 0; i < 30; i++ {
			if err := w.SaveTranscript(protocol.TranscriptPayload{Name: fmt.Sprintf("live-%02d.opus", i), Text: "并发写入"}); err != nil {
				done <- err
				return
			}
		}
		done <- nil
	}()
	for i := 0; i < 30; i++ {
		if _, _, err := ro.Timeline(ArchiveFilter{Limit: 5}); err != nil {
			t.Fatal(err)
		}
		if _, err := ro.Search(ArchiveFilter{Query: "并发"}); err != nil {
			t.Fatal(err)
		}
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if st, err := ro.ReadOnlyStats(); err != nil || st.TotalRecordings != 35 {
		t.Fatalf("lost concurrent writes: %+v %v", st, err)
	}
}

func TestWorkerCLILegacyFlagsAndOnce(t *testing.T) {
	mb, err := protocol.NewMailboxServer(protocol.ServerConfig{DataDir: t.TempDir(), PhoneToken: "phone-test", WorkerToken: "worker-test"})
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(mb.Handler())
	defer srv.Close()
	key, err := e2ee.GenerateKey()
	if err != nil {
		t.Fatal(err)
	}
	cfg := DefaultConfig()
	cfg.ServerURL, cfg.WorkerToken, cfg.E2EEKey = srv.URL, "worker-test", key
	cfg.DataDir = filepath.Join(t.TempDir(), "must-not-be-used")
	conf := filepath.Join(t.TempDir(), "worker.json")
	raw, err := json.Marshal(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(conf, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	for _, prefix := range []string{"-", "--"} {
		var stderr bytes.Buffer
		dir := t.TempDir()
		code := RunCLI(context.Background(), []string{prefix + "config", conf, prefix + "data-dir", dir, prefix + "once"}, io.Discard, &stderr)
		if code != 0 {
			t.Fatalf("legacy flags: code=%d stderr=%s", code, &stderr)
		}
		if _, err := os.Stat(filepath.Join(dir, "hark.db")); err != nil {
			t.Fatal(err)
		}
		var stdout bytes.Buffer
		if code := RunCLI(context.Background(), []string{prefix + "gen-key", prefix + "config", conf + ".missing"}, &stdout, &stderr); code != 0 {
			t.Fatalf("gen-key: code=%d stderr=%s", code, &stderr)
		}
		if _, err := e2ee.DecodeKey(strings.TrimSpace(stdout.String())); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := os.Stat(cfg.DataDir); !os.IsNotExist(err) {
		t.Fatalf("data-dir override ignored: %v", err)
	}
}
