package asr

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// ErrBadAudio marks an unusable recording; retrying the same audio cannot fix it.
var ErrBadAudio = errors.New("坏音频，跳过不重试")
var ErrReadTimeout = errors.New("百炼读取超时，停止自动重试以避免重复计费")

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
	Text      string          `json:"text"`
	BeginTime float64         `json:"begin_time"`
	EndTime   float64         `json:"end_time"`
	SpeakerID FlexSpeaker     `json:"speaker_id"`
	Words     []dashScopeWord `json:"words"`
}

type dashScopeWord struct {
	Text        string  `json:"text"`
	Punctuation string  `json:"punctuation"`
	BeginTime   float64 `json:"begin_time"`
	EndTime     float64 `json:"end_time"`
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
			if strings.HasPrefix(data.Code, "InvalidFile.") || strings.HasPrefix(data.Code, "Audio.") || httpCode == 413 || httpCode == 415 {
				return Result{}, fmt.Errorf("%w: %s %s", ErrBadAudio, data.Code, data.Message)
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
				return dashScopeResult(data, cfg.Model, OggOpusDuration(opus), enableSpeaker), nil
			}
		} else {
			if dashScopeReadTimeout(err) {
				return Result{}, fmt.Errorf("%w: %w", ErrReadTimeout, err)
			}
			if ctx.Err() != nil || retries >= len(backoffs) {
				return Result{}, err
			}
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

func dashScopeReadTimeout(err error) bool {
	// Dial failures are safe to retry. A timeout after connecting may already have been billed.
	var op *net.OpError
	if errors.As(err, &op) && op.Op == "dial" {
		return false
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return true
	}
	var timeout net.Error
	return errors.As(err, &timeout) && timeout.Timeout()
}

func dashScopeResult(data dashScopeResponse, model string, localDur float64, enableSpeaker bool) Result {
	dur := data.Usage.Duration
	if dur <= 0 {
		dur = localDur
	}
	segs := make([]Segment, 0, len(data.Output.Sentences))
	sentences := data.Output.Sentences
	if len(sentences) == 0 && data.Output.Sentence != nil {
		sentences = []dashScopeSentence{*data.Output.Sentence}
	}
	if !enableSpeaker || len(data.Output.Sentences) == 0 {
		var words []dashScopeWord
		for _, sentence := range sentences {
			words = append(words, sentence.Words...)
		}
		if len(words) == 0 && data.Output.Sentence != nil {
			words = data.Output.Sentence.Words
		}
		segs = dashScopeWordSegments(words)
	} else {
		for _, sentence := range sentences {
			txt := strings.TrimSpace(sentence.Text)
			if txt == "" {
				continue
			}
			start := sentence.BeginTime / 1000
			end := max(start, sentence.EndTime/1000)
			segs = append(segs, Segment{Start: start, End: end, Text: txt, Speaker: sentence.SpeakerID})
		}
	}
	var speechS float64
	var textParts []string
	for _, s := range segs {
		speechS += s.End - s.Start
		textParts = append(textParts, s.Text)
	}
	fullText := strings.TrimSpace(data.Output.Text)
	if fullText == "" {
		fullText = strings.Join(textParts, "")
	}
	if fullText == "" && data.Output.Sentence != nil {
		fullText = strings.TrimSpace(data.Output.Sentence.Text)
	}
	synthetic := fullText != "" && len(segs) == 0
	if synthetic {
		segs = []Segment{{Start: 0, End: math.Round(dur*100) / 100, Text: fullText}}
		speechS = dur
	}
	return Result{
		Text: fullText, DurationS: math.Round(dur*100) / 100, SpeechS: math.Round(speechS*100) / 100,
		Engine: ProviderDashScope, Model: model, Segments: segs, SyntheticSegments: synthetic,
	}
}

func dashScopeWordSegments(words []dashScopeWord) []Segment {
	segs := []Segment{}
	var text strings.Builder
	var startMs, endMs float64
	flush := func() {
		if txt := strings.TrimSpace(text.String()); txt != "" {
			segs = append(segs, Segment{Start: startMs / 1000, End: endMs / 1000, Text: txt})
		}
		text.Reset()
	}
	for _, w := range words {
		if strings.TrimSpace(w.Text+w.Punctuation) == "" {
			continue
		}
		if text.Len() > 0 && w.BeginTime-endMs > 800 {
			flush()
		}
		if text.Len() == 0 {
			startMs = w.BeginTime
		}
		endMs = w.EndTime
		text.WriteString(w.Text)
		if w.Punctuation != "" && !strings.HasSuffix(w.Text, w.Punctuation) {
			text.WriteString(w.Punctuation)
		}
		if strings.ContainsAny(w.Text+w.Punctuation, "。！？!?；") {
			flush()
		}
	}
	flush()
	return segs
}
