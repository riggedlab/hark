package asr

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestDashScopeConfigAndStaticHealth(t *testing.T) {
	cfg := NormalizeConfig(Config{Provider: " DASHSCOPE ", APIKey: " key "})
	if cfg.BaseURL != DefaultDashScopeURL || cfg.Model != DefaultDashScopeModel || cfg.APIKey != "key" {
		t.Fatalf("unexpected defaults: %+v", cfg)
	}
	for _, base := range []string{"https://dashscope-intl.aliyuncs.com/", "https://workspace.cn-beijing.maas.aliyuncs.com"} {
		got := NormalizeConfig(Config{Provider: ProviderDashScope, BaseURL: base})
		if got.BaseURL != strings.TrimRight(base, "/")+dashScopePath {
			t.Errorf("unexpected endpoint: %s", got.BaseURL)
		}
	}
	if err := ValidateConfig(Config{Provider: ProviderDashScope}); err == nil {
		t.Fatal("missing key should fail validation")
	}
	if err := ValidateConfig(Config{Provider: ProviderDashScope, APIKey: "key", BaseURL: "://bad"}); err == nil {
		t.Fatal("invalid URL should fail validation")
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Error("CheckHealth must not call DashScope")
	}))
	defer srv.Close()
	cfg.BaseURL = srv.URL
	if ok, model := CheckHealth(context.Background(), cfg); !ok || model == nil || *model != DefaultDashScopeModel {
		t.Fatalf("static health failed: %v %v", ok, model)
	}
	for _, p := range Presets() {
		if p.ID == "dashscope" && (p.Provider != ProviderDashScope || p.BaseURL != DefaultDashScopeURL || p.DefaultMod != DefaultDashScopeModel) {
			t.Fatalf("broken DashScope preset: %+v", p)
		}
	}
}

func TestDashScopeMultiSpeaker(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" || r.URL.Path != dashScopePath || r.Header.Get("Authorization") != "Bearer test-key" ||
			r.Header.Get("Content-Type") != "application/json" || r.Header.Get("X-DashScope-SSE") != "disable" {
			t.Errorf("unexpected request: %s %s %v", r.Method, r.URL, r.Header)
		}
		var payload struct {
			Model string `json:"model"`
			Input struct {
				Messages []struct {
					Role    string `json:"role"`
					Content []struct {
						Type       string `json:"type"`
						InputAudio struct {
							Data string `json:"data"`
						} `json:"input_audio"`
					} `json:"content"`
				} `json:"messages"`
			} `json:"input"`
			Parameters map[string]any `json:"parameters"`
		}
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Error(err)
			w.WriteHeader(400)
			return
		}
		if payload.Model != DefaultDashScopeModel || len(payload.Input.Messages) != 1 || len(payload.Input.Messages[0].Content) != 1 {
			t.Errorf("unexpected payload: %+v", payload)
			w.WriteHeader(400)
			return
		}
		message := payload.Input.Messages[0]
		content := message.Content[0]
		encoded := strings.TrimPrefix(content.InputAudio.Data, "data:audio/ogg;base64,")
		audio, err := base64.StdEncoding.DecodeString(encoded)
		if message.Role != "user" || content.Type != "input_audio" || err != nil || !bytes.Equal(audio, probeAudio) ||
			payload.Parameters["format"] != "opus" || payload.Parameters["speaker_diarization_enabled"] != true {
			t.Errorf("incorrect audio or parameters: %+v, err=%v", payload.Parameters, err)
		}
		if _, ok := payload.Parameters["language_hints"]; ok {
			t.Error("language_hints must not accompany diarization")
		}
		if _, ok := payload.Parameters["keep_dialect"]; ok {
			t.Error("keep_dialect must not accompany diarization")
		}
		_, _ = w.Write([]byte(`{"output":{"text":"你好。收到。","sentence":{"text":"收到。","speaker_id":1},"sentences":[{"begin_time":120,"end_time":1680,"text":"你好。","speaker_id":0},{"begin_time":1800,"end_time":3200,"text":"收到。","speaker_id":1}]},"usage":{"duration":3.42}}`))
	}))
	defer srv.Close()
	res, err := Transcribe(context.Background(), Config{Provider: ProviderDashScope, BaseURL: srv.URL, APIKey: "test-key", Language: "zh"}, probeAudio, "test.opus")
	if err != nil {
		t.Fatal(err)
	}
	if res.Text != "你好。收到。" || res.DurationS != 3.42 || res.SpeechS != 2.96 || res.Engine != ProviderDashScope || res.Model != DefaultDashScopeModel || res.SyntheticSegments || len(res.Segments) != 2 {
		t.Fatalf("unexpected result: %+v", res)
	}
	if res.Segments[0].Speaker != "0" || res.Segments[1].Speaker != "1" || res.Segments[0].Start != 0.12 || res.Segments[1].End != 3.2 {
		t.Fatalf("incorrect timestamps/speakers: %+v", res.Segments)
	}
}

func TestDashScopeFallbackAndTransientRetry(t *testing.T) {
	calls := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		var payload struct {
			Parameters map[string]any `json:"parameters"`
		}
		_ = json.NewDecoder(r.Body).Decode(&payload)
		if payload.Parameters["speaker_diarization_enabled"] != (calls <= 2) {
			t.Errorf("unexpected diarization flag at call %d", calls)
		}
		switch calls {
		case 1:
			w.WriteHeader(503)
			_, _ = w.Write([]byte(`{"code":"ServiceUnavailable"}`))
		case 2:
			w.WriteHeader(400)
			_, _ = w.Write([]byte(`{"code":"InvalidParameter","message":"speaker_diarization_enabled is not supported"}`))
		case 3:
			w.WriteHeader(429)
			_, _ = w.Write([]byte(`{"code":"Throttling"}`))
		default:
			_, _ = w.Write([]byte(`{"output":{"text":"你好。再见。","sentence":{"text":"再见。","begin_time":1000,"end_time":2000,"speaker_id":null}},"usage":{"duration":3}}`))
		}
	}))
	defer srv.Close()
	res, err := Transcribe(context.Background(), Config{Provider: ProviderDashScope, BaseURL: srv.URL, APIKey: "test-key"}, probeAudio, "test.opus")
	if err != nil {
		t.Fatal(err)
	}
	if calls != 4 || !res.SyntheticSegments || len(res.Segments) != 1 || res.Segments[0].Text != "你好。再见。" || res.Segments[0].End != 3 || res.Segments[0].Speaker != "" {
		t.Fatalf("fallback lost full text or retry budget: calls=%d result=%+v", calls, res)
	}
}

func TestDashScopeEmptyAndSentenceResults(t *testing.T) {
	for _, tc := range []struct {
		name, body, text string
		status           int
		synthetic        bool
	}{
		{"silent", `{"code":"Audio.AudioSilentError","message":"silent audio"}`, "", 400, false},
		{"empty", `{"output":{"text":"","sentences":[]},"usage":{"duration":3.4}}`, "", 200, false},
		{"null", `{"output":{"text":"","sentence":null}}`, "", 200, false},
		{"text only", `{"output":{"text":"全文"}}`, "全文", 200, true},
		{"sentence only", `{"output":{"sentence":{"text":"单句","begin_time":100,"end_time":2100,"speaker_id":null}}}`, "单句", 200, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls++
				w.WriteHeader(tc.status)
				_, _ = w.Write([]byte(tc.body))
			}))
			defer srv.Close()
			res, err := Transcribe(context.Background(), Config{Provider: ProviderDashScope, BaseURL: srv.URL, APIKey: "k"}, probeAudio, "test.opus")
			if err != nil {
				t.Fatal(err)
			}
			if calls != 1 || res.Text != tc.text || res.SyntheticSegments != tc.synthetic || res.DurationS <= 0 {
				t.Fatalf("unexpected result: %+v, calls=%d", res, calls)
			}
			if tc.text == "" && (len(res.Segments) != 0 || res.SpeechS != 0) {
				t.Fatalf("silence has speech: %+v", res)
			}
		})
	}
}

func TestDashScopeAuthAndParameterFailures(t *testing.T) {
	for _, tc := range []struct {
		name       string
		status     int
		body, want string
		calls      int
	}{
		{"unauthorized", 401, `{"code":"InvalidApiKey","message":"Invalid API-key"}`, "鉴权失败", 1},
		{"forbidden", 403, `{"code":"AccessDenied"}`, "鉴权失败", 1},
		{"api auth error", 200, `{"code":"InvalidApiKey"}`, "鉴权失败", 1},
		{"bad model", 400, `{"code":"InvalidParameter","message":"unknown model"}`, "InvalidParameter", 1},
		{"decoder error", 400, `{"code":"Audio.DecoderError","message":"invalid audio"}`, "Audio.DecoderError", 1},
		{"fallback rejected again", 400, `{"code":"InvalidParameter","message":"speaker_diarization_enabled unsupported"}`, "InvalidParameter", 2},
		{"malformed success", 200, `{`, "解析百炼响应失败", 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls++
				w.WriteHeader(tc.status)
				_, _ = w.Write([]byte(tc.body))
			}))
			defer srv.Close()
			_, err := Transcribe(context.Background(), Config{Provider: ProviderDashScope, BaseURL: srv.URL, APIKey: "k"}, probeAudio, "test.opus")
			if err == nil || !strings.Contains(err.Error(), tc.want) || calls != tc.calls {
				t.Fatalf("unexpected error/retries: %v, calls=%d", err, calls)
			}
		})
	}
}
