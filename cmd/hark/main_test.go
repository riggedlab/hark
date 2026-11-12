package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/riggedlab/hark/internal/asr"
	"github.com/riggedlab/hark/internal/e2ee"
	"github.com/riggedlab/hark/internal/protocol"
	"github.com/riggedlab/hark/internal/worker"
)

func invoke(args ...string) (int, string, string) {
	var stdout, stderr bytes.Buffer
	code := run(args, &stdout, &stderr)
	return code, stdout.String(), stderr.String()
}

func cliFixture(t *testing.T) (string, []string) {
	t.Helper()
	dir := t.TempDir()
	store, err := worker.OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	_, err = store.AssignSpeakers(asr.Result{EmbeddingModel: "test", Segments: []asr.Segment{{Text: "接口", Speaker: "s0"}},
		Speakers: []asr.SpeakerEmbedding{{Speaker: "s0", SpeechS: 12, Embedding: []float64{1, 0}}}}, 0.60)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.ApplyPhoneRoster(map[string]protocol.PhoneSpeakerItem{"spk_001": {Name: "老王"}}); err != nil {
		t.Fatal(err)
	}
	names := []string{}
	for _, ago := range []int{0, 1, 40} {
		day := time.Now().AddDate(0, 0, -ago)
		name := day.Format("20060102") + "-143000.opus"
		id := "spk_001"
		if err := store.SaveTranscript(protocol.TranscriptPayload{Name: name, StartedAt: day.Format("2006-01-02") + "T14:30:00",
			DurationS: 30, SpeechS: 20, Text: "开场白。接口明天上线。结束。", Segments: []protocol.Segment{
				{Start: 0, End: 3, Text: "开场白", Speaker: &id},
				{Start: 3, End: 20, Text: "接口明天上线", Speaker: &id},
			}}); err != nil {
			t.Fatal(err)
		}
		names = append(names, name)
	}
	return dir, names
}

func TestEmptyArchiveCommands(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "missing")
	for _, command := range [][]string{{"status"}, {"speakers"}, {"timeline"}, {"search", "接口"}} {
		args := append(append([]string{}, command...), "--data-dir", dir)
		code, stdout, stderr := invoke(args...)
		if code != 0 || stderr != "" || !strings.Contains(stdout, "本地暂无录音存档") {
			t.Fatalf("empty text: %v code=%d stdout=%s stderr=%s", args, code, stdout, stderr)
		}
		code, stdout, stderr = invoke(append(args, "--json")...)
		var out struct {
			DBExists bool `json:"db_exists"`
		}
		if code != 0 || stderr != "" || json.Unmarshal([]byte(stdout), &out) != nil || out.DBExists || !strings.Contains(stdout, `"db_exists": false`) {
			t.Fatalf("empty JSON: %v code=%d stdout=%s stderr=%s", args, code, stdout, stderr)
		}
	}
	code, stdout, stderr := invoke("get", "missing", "--data-dir", dir, "--json")
	if code != 1 || stdout != "" || !strings.Contains(stderr, "本地暂无录音存档") {
		t.Fatalf("empty get: code=%d stdout=%s stderr=%s", code, stdout, stderr)
	}
	if _, err := os.Stat(dir); !os.IsNotExist(err) {
		t.Fatalf("queries created the data directory: %v", err)
	}
}

func TestQueryCommandsAndInterspersedFlags(t *testing.T) {
	dir, names := cliFixture(t)
	for _, tc := range []struct {
		args  []string
		count int
	}{
		{[]string{"get", strings.TrimSuffix(names[0], ".opus"), "--json"}, 1},
		{[]string{"get", "--json", names[0], names[1]}, 2},
		{[]string{"get", names[0], "--json=true", names[1]}, 2},
		{[]string{"search", "接口", "--date", "7d", "--speaker", "老王", "--json"}, 2},
		{[]string{"search", "--date=7d", "--json", "接口", "--limit", "1"}, 1},
		{[]string{"search", "接口", "--json"}, 2},
		{[]string{"search", "", "--speaker", "老王", "--json"}, 2},
		{[]string{"timeline", "--json"}, 1},
		{[]string{"timeline", "--date", "yesterday", "--json"}, 1},
		{[]string{"timeline", "--date", "all", "--limit", "2", "--offset", "1", "--json"}, 2},
		{[]string{"speakers", "--json"}, 1},
	} {
		args := append(append([]string{}, tc.args...), "--data-dir", dir)
		code, stdout, stderr := invoke(args...)
		var out struct {
			DBExists bool              `json:"db_exists"`
			Items    []json.RawMessage `json:"items"`
		}
		if code != 0 || stderr != "" || json.Unmarshal([]byte(stdout), &out) != nil || !out.DBExists || len(out.Items) != tc.count || !strings.Contains(stdout, "\n  ") {
			t.Fatalf("%v: code=%d stdout=%s stderr=%s", args, code, stdout, stderr)
		}
	}
	code, stdout, stderr := invoke("search", "--data-dir", dir, "--json", "--", "--json")
	if code != 0 || stderr != "" || !strings.Contains(stdout, `"total": 0`) {
		t.Fatalf("-- boundary: code=%d stdout=%s stderr=%s", code, stdout, stderr)
	}
	code, stdout, stderr = invoke("status", "--data-dir", dir, "--json")
	var st worker.ReadOnlyStats
	if code != 0 || stderr != "" || json.Unmarshal([]byte(stdout), &st) != nil || st.TotalRecordings != 3 || st.LatestName != names[0] || st.SpeakerCount != 1 {
		t.Fatalf("status: code=%d stdout=%s stderr=%s", code, stdout, stderr)
	}
	code, stdout, stderr = invoke("timeline", "--data-dir", dir, "--date", "all", "--limit", "2")
	if code != 0 || stderr != "" || !strings.Contains(stdout, "共 3 条，当前展示最近 2 条") || !strings.Contains(stdout, "14:30:00 →") ||
		!strings.Contains(stdout, "老王 (spk_001)") || !strings.Contains(stdout, names[0]) || strings.Index(stdout, names[1]) > strings.Index(stdout, names[0]) {
		t.Fatalf("timeline text: code=%d stdout=%s stderr=%s", code, stdout, stderr)
	}
	code, stdout, stderr = invoke("search", "接口", "--data-dir", dir)
	if code != 0 || stderr != "" || !strings.Contains(stdout, names[0]) || !strings.Contains(stdout, "接口明天上线") || strings.Contains(stdout, "开场白") {
		t.Fatalf("search text: code=%d stdout=%s stderr=%s", code, stdout, stderr)
	}
	code, stdout, stderr = invoke("get", names[0], "absent", "--data-dir", dir, "--json")
	if code != 1 || stdout != "" || !strings.Contains(stderr, "录音不存在: absent.opus") {
		t.Fatalf("get must fail without partial output: code=%d stdout=%s stderr=%s", code, stdout, stderr)
	}
}

func TestConfigAndInvalidArguments(t *testing.T) {
	dir, _ := cliFixture(t)
	t.Setenv("HARK_HOME", "")
	t.Setenv("DITING_HOME", "")
	conf := filepath.Join(t.TempDir(), "config.json")
	raw, err := json.Marshal(map[string]any{"data_dir": dir, "asr": 123, "keychain_stored": true})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(conf, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	if code, stdout, stderr := invoke("status", "--config", conf, "--json"); code != 0 || stderr != "" || !strings.Contains(stdout, `"total_recordings": 3`) {
		t.Fatalf("config-only data dir: %d %s %s", code, stdout, stderr)
	}
	for _, args := range [][]string{
		{"unknown"}, {"get"}, {"search"}, {"search", "one", "two"}, {"status", "extra"},
		{"timeline", "--date", "2026-02-30"}, {"timeline", "--limit", "0"}, {"search", "接口", "--offset", "-1"},
		{"search", "接口", "--unknown"}, {"get", "name", "--json=bad"}, {"search", "接口", "--date"},
	} {
		if code, stdout, stderr := invoke(args...); code != 2 || stdout != "" || stderr == "" {
			t.Fatalf("invalid args %v: code=%d stdout=%s stderr=%s", args, code, stdout, stderr)
		}
	}
}

func TestHelpAndKeyGeneration(t *testing.T) {
	for _, args := range [][]string{nil, {"--help"}, {"get", "--help"}, {"search", "接口", "--help"}, {"worker", "--help"}} {
		if code, stdout, stderr := invoke(args...); code != 0 || stdout+stderr == "" {
			t.Fatalf("help %v: %d %s %s", args, code, stdout, stderr)
		}
	}
	for _, args := range [][]string{{"gen-key"}, {"worker", "-gen-key"}, {"worker", "--gen-key"}} {
		code, stdout, stderr := invoke(args...)
		if _, err := e2ee.DecodeKey(strings.TrimSpace(stdout)); code != 0 || stderr != "" || err != nil {
			t.Fatalf("key generation %v: code=%d decode=%v", args, code, err)
		}
	}
}
