package asr

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestOpenAIAdapterAndFallback(t *testing.T) {
	if dur := OggOpusDuration(probeAudio); dur < 2.5 || dur > 5.0 {
		t.Fatalf("unexpected probe.opus duration: %.2fs", dur)
	}

	var calls []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseMultipartForm(4 << 20); err != nil {
			t.Fatal(err)
		}
		fh := r.MultipartForm.File["file"][0]
		if !strings.HasSuffix(fh.Filename, ".ogg") {
			t.Fatalf("expected .ogg upload filename, got %s", fh.Filename)
		}
		rf := r.FormValue("response_format")
		calls = append(calls, rf)
		if rf == "verbose_json" {
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"error":"verbose_json unsupported"}`))
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"text": "你好，收到声音很清楚"})
	}))
	defer srv.Close()

	rep := Probe(context.Background(), Config{
		Provider: ProviderOpenAI,
		BaseURL:  srv.URL + "/v1",
		APIKey:   "sk-test",
		Model:    "SenseVoiceSmall",
		Language: "zh",
	})
	if !rep.OK || rep.Text != "你好，收到声音很清楚" || rep.HasSpeakers {
		t.Fatalf("unexpected ProbeReport: %+v", rep)
	}
	if len(calls) != 2 || calls[0] != "verbose_json" || calls[1] != "json" {
		t.Fatalf("expected verbose_json -> json fallback, got %v", calls)
	}
}

func TestHarkHTTPAdapterWithSpeakers(t *testing.T) {
	emb1 := make([]float64, 192)
	emb2 := make([]float64, 192)
	emb1[0] = 1
	emb2[1] = 1

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/health":
			_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "model": "Qwen3-ASR-1.7B"})
		case "/transcribe":
			if err := r.ParseMultipartForm(4 << 20); err != nil || len(r.MultipartForm.File["file"]) != 1 {
				t.Fatalf("expected multipart 'file' field, err=%v", err)
			}
			// 验证同时兼容整数编号 speaker: 0 / 1 与字符串编号
			_, _ = w.Write([]byte(`{
				"text": "你好收到",
				"duration_s": 3.44,
				"speech_s": 3.1,
				"engine": "mlx-qwen3",
				"model": "Qwen3-ASR-1.7B",
				"embedding_model": "3dspeaker-cam++",
				"segments": [
					{"start": 0.0, "end": 1.6, "text": "你好", "speaker": 0},
					{"start": 1.8, "end": 3.4, "text": "收到", "speaker": 1}
				],
				"speakers": [
					{"speaker": 0, "speech_s": 1.6, "embedding": ` + mustJSON(emb1) + `},
					{"speaker": 1, "speech_s": 1.6, "embedding": ` + mustJSON(emb2) + `}
				]
			}`))
		}
	}))
	defer srv.Close()

	cfg := Config{Provider: ProviderHarkHTTP, BaseURL: srv.URL}
	ok, model := CheckHealth(context.Background(), cfg)
	if !ok || model == nil || *model != "Qwen3-ASR-1.7B" {
		t.Fatalf("CheckHealth failed: ok=%v model=%v", ok, model)
	}
	rep := Probe(context.Background(), cfg)
	if !rep.OK || !rep.HasTimestamps || !rep.HasSpeakers || rep.SpeakerCount != 2 || rep.EmbeddingDim != 192 {
		t.Fatalf("unexpected HarkHTTP ProbeReport: %+v", rep)
	}
}

func mustJSON(v any) string {
	b, _ := json.Marshal(v)
	return string(b)
}

func TestVolcengineFlashAdapterDiarizationFallbackAndSilence(t *testing.T) {
	calls := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.Header.Get("X-Api-Key") != "volc-test-key" {
			t.Fatalf("expected X-Api-Key header, got %q", r.Header.Get("X-Api-Key"))
		}
		if r.Header.Get("X-Api-Resource-Id") != DefaultVolcResourceID {
			t.Fatalf("unexpected resource id: %q", r.Header.Get("X-Api-Resource-Id"))
		}
		var body struct {
			Audio   map[string]any `json:"audio"`
			Request map[string]any `json:"request"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		if body.Audio["format"] != "ogg" || body.Audio["codec"] != "opus" {
			t.Fatalf("unexpected audio meta: %+v", body.Audio)
		}

		// 第 1 次模拟返回带双说话人标签的完整 utterances
		if calls == 1 {
			w.Header().Set("X-Api-Status-Code", "20000000")
			w.Header().Set("X-Api-Message", "OK")
			_, _ = w.Write([]byte(`{
				"audio_info": {"duration": 3450},
				"result": {
					"text": "你好，收到。",
					"utterances": [
						{"start_time": 0, "end_time": 1650, "text": "你好，", "additions": {"speaker_id": 1}},
						{"start_time": 1850, "end_time": 3400, "text": "收到。", "additions": {"speaker_id": 2}}
					]
				}
			}`))
			return
		}
		// 第 2 次模拟静音音频 20000003
		w.Header().Set("X-Api-Status-Code", "20000003")
		w.Header().Set("X-Api-Message", "Silent audio")
		_, _ = w.Write([]byte(`{}`))
	}))
	defer srv.Close()

	cfg := Config{
		Provider: ProviderVolcengine,
		BaseURL:  srv.URL,
		APIKey:   "volc-test-key",
	}
	rep := Probe(context.Background(), cfg)
	if !rep.OK || !rep.HasTimestamps || !rep.HasSpeakers || !rep.HasDiarizationOnly || rep.SpeakerCount != 2 {
		t.Fatalf("unexpected Volcengine ProbeReport: %+v", rep)
	}

	silentRes, err := Transcribe(context.Background(), cfg, probeAudio, "20261112-120000.opus")
	if err != nil || silentRes.Text != "" || len(silentRes.Segments) != 0 {
		t.Fatalf("expected silent 20000003 to return empty result without error, got %+v err=%v", silentRes, err)
	}
}
