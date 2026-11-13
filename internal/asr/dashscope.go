package asr

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const (
	dashScopePath         = "/api/v1/services/aigc/multimodal-generation/generation"
	DefaultDashScopeURL   = "https://dashscope.aliyuncs.com" + dashScopePath
	DefaultDashScopeModel = "qwen-audio-3.1-asr-flash"
)

func normalizeDashScopeURL(endpoint string) string {
	if endpoint == "" {
		return DefaultDashScopeURL
	}
	u, err := url.Parse(endpoint)
	if err == nil && u.Host != "" && u.Path == "" {
		u.Path = dashScopePath
		return u.String()
	}
	return endpoint
}

func validateDashScopeURL(endpoint string) error {
	u, err := url.Parse(endpoint)
	if err != nil || u.Host == "" || (u.Scheme != "http" && u.Scheme != "https") {
		return fmt.Errorf("百炼接口地址需为有效的 http:// 或 https:// URL")
	}
	return nil
}

type dashScopeSentence struct {
	Text      string      `json:"text"`
	BeginTime float64     `json:"begin_time"`
	EndTime   float64     `json:"end_time"`
	SpeakerID FlexSpeaker `json:"speaker_id"`
}

type dashScopeResponse struct {
	Code      string `json:"code"`
	Message   string `json:"message"`
	RequestID string `json:"request_id"`
	Output    struct {
		Text      string              `json:"text"`
		Sentence  *dashScopeSentence  `json:"sentence"`
		Sentences []dashScopeSentence `json:"sentences"`
	} `json:"output"`
	Usage struct {
		Duration float64 `json:"duration"`
	} `json:"usage"`
}

func transcribeDashScope(ctx context.Context, cfg Config, opus []byte) (Result, error) {
	// The HTTP API accepts opus; RFC 7845 §9 defines audio/ogg for Ogg Opus.
	audio := "data:audio/ogg;base64," + base64.StdEncoding.EncodeToString(opus)
	sendOnce := func(enableSpeaker bool) (int, []byte, error) {
		payload := map[string]any{
			"model": cfg.Model,
			"input": map[string]any{"messages": []any{map[string]any{
				"role": "user",
				"content": []any{map[string]any{
					"type": "input_audio", "input_audio": map[string]string{"data": audio},
				}},
			}}},
			// Do not combine diarization with language_hints or keep_dialect.
			"parameters": map[string]any{"format": "opus", "speaker_diarization_enabled": enableSpeaker},
		}
		rawReq, err := json.Marshal(payload)
		if err != nil {
			return 0, nil, err
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, cfg.BaseURL, bytes.NewReader(rawReq))
		if err != nil {
			return 0, nil, err
		}
		req.Header.Set("Authorization", "Bearer "+cfg.APIKey)
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-DashScope-SSE", "disable")
		resp, err := (&http.Client{Timeout: 5 * time.Minute}).Do(req)
		if err != nil {
			return 0, nil, err
		}
		defer resp.Body.Close()
		raw, err := io.ReadAll(io.LimitReader(resp.Body, 8*1024*1024))
		return resp.StatusCode, raw, err
	}

	enableSpeaker := true
	backoffs := []time.Duration{time.Second, 2500 * time.Millisecond} // Same retry budget as Volcengine.
	for retries := 0; ; {
		httpCode, raw, err := sendOnce(enableSpeaker)
		var data dashScopeResponse
		if err == nil {
			parseErr := json.Unmarshal(raw, &data)
			if httpCode == http.StatusUnauthorized || httpCode == http.StatusForbidden ||
				strings.EqualFold(data.Code, "InvalidApiKey") || strings.EqualFold(data.Code, "invalid_api_key") ||
				strings.HasPrefix(data.Code, "AccessDenied") || strings.HasSuffix(data.Code, ".AccessDenied") {
				return Result{}, fmt.Errorf("百炼鉴权失败 (HTTP %d %s)：请检查百炼 API Key、所属地域与接口地址是否匹配，并确认已开通模型 %s", httpCode, data.Code, cfg.Model)
			}
			if data.Code == "Audio.AudioSilentError" {
				return Result{DurationS: math.Round(OggOpusDuration(opus)*100) / 100, Engine: ProviderDashScope, Model: cfg.Model, Segments: []Segment{}}, nil
			}
			parameterError := httpCode == 400 || httpCode == 422 || strings.HasPrefix(data.Code, "InvalidParameter") || data.Code == "UnsupportedParameter"
			if enableSpeaker && parameterError && strings.Contains(strings.ToLower(data.Message), "diarization") {
				enableSpeaker = false
				continue
			}
			retryable := httpCode == http.StatusTooManyRequests || httpCode >= 500
			if !retryable || retries >= len(backoffs) {
				if httpCode < 200 || httpCode >= 300 || data.Code != "" {
					return Result{}, fmt.Errorf("百炼返回 HTTP %d (code=%s, request_id=%s): %.200s", httpCode, data.Code, data.RequestID, data.Message)
				}
				if parseErr != nil {
					return Result{}, fmt.Errorf("解析百炼响应失败: %w", parseErr)
				}
				return dashScopeResult(data, cfg.Model, OggOpusDuration(opus)), nil
			}
		} else if ctx.Err() != nil || retries >= len(backoffs) {
			return Result{}, err
		}
		timer := time.NewTimer(backoffs[retries])
		select {
		case <-ctx.Done():
			timer.Stop()
			return Result{}, ctx.Err()
		case <-timer.C:
		}
		retries++
	}
}

func dashScopeResult(data dashScopeResponse, model string, localDur float64) Result {
	dur := data.Usage.Duration
	if dur <= 0 {
		dur = localDur
	}
	segs := make([]Segment, 0, len(data.Output.Sentences))
	sentences := data.Output.Sentences
	if len(sentences) == 0 && data.Output.Sentence != nil {
		sentences = []dashScopeSentence{*data.Output.Sentence}
	}
	var speechS float64
	var textParts []string
	for _, sentence := range sentences {
		txt := strings.TrimSpace(sentence.Text)
		if txt == "" {
			continue
		}
		start := sentence.BeginTime / 1000
		end := max(start, sentence.EndTime/1000)
		segs = append(segs, Segment{Start: start, End: end, Text: txt, Speaker: sentence.SpeakerID})
		speechS += end - start
		textParts = append(textParts, txt)
	}
	fullText := strings.TrimSpace(data.Output.Text)
	if fullText == "" {
		fullText = strings.Join(textParts, "")
	}
	// Without diarization, output.sentence describes only the current (last) sentence.
	// Preserve output.text rather than dropping earlier sentences from the transcript.
	synthetic := fullText != "" && (len(segs) == 0 || (len(data.Output.Sentences) == 0 && strings.Join(textParts, "") != fullText))
	if synthetic {
		segs = []Segment{{Start: 0, End: math.Round(dur*100) / 100, Text: fullText}}
		speechS = dur
	}
	return Result{
		Text: fullText, DurationS: math.Round(dur*100) / 100, SpeechS: math.Round(speechS*100) / 100,
		Engine: ProviderDashScope, Model: model, Segments: segs, SyntheticSegments: synthetic,
	}
}
