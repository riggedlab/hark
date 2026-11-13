// Package asr 实现可插拔语音转写适配层（OpenAI 兼容接口 + Hark 本地声纹增强接口 + 内置连通探测）。
package asr

import (
	"bytes"
	"context"
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"strings"
	"time"
)

//go:embed probe.opus
var probeAudio []byte

const (
	ProviderVolcengine = "volcengine"
	ProviderOpenAI     = "openai"
	ProviderHarkHTTP   = "hark_http"
	ProviderMock       = "mock"
)

type Config struct {
	Provider string `json:"provider"` // "volcengine" | "openai" | "hark_http" | "mock"
	BaseURL  string `json:"base_url"`
	APIKey   string `json:"api_key,omitempty"`
	AppID    string `json:"app_id,omitempty"` // 火山旧版控制台可选 AppID；新版控制台单 APIKey 留空即可
	Model    string `json:"model"`
	Language string `json:"language"`
}

type Preset struct {
	ID          string `json:"id"`
	Label       string `json:"label"`
	Provider    string `json:"provider"`
	BaseURL     string `json:"base_url"`
	DefaultMod  string `json:"default_model"`
	NeedsAPIKey bool   `json:"needs_api_key"`
}

func Presets() []Preset {
	return []Preset{
		{ID: "siliconflow", Label: "硅基流动", Provider: ProviderOpenAI, BaseURL: "https://api.siliconflow.cn/v1", DefaultMod: "FunAudioLLM/SenseVoiceSmall", NeedsAPIKey: true},
		{ID: "groq", Label: "Groq", Provider: ProviderOpenAI, BaseURL: "https://api.groq.com/openai/v1", DefaultMod: "whisper-large-v3-turbo", NeedsAPIKey: true},
		{ID: "openai", Label: "OpenAI", Provider: ProviderOpenAI, BaseURL: "https://api.openai.com/v1", DefaultMod: "whisper-1", NeedsAPIKey: true},
		{ID: "dashscope", Label: "阿里云百炼", Provider: ProviderOpenAI, BaseURL: "https://dashscope.aliyuncs.com/compatible-mode/v1", DefaultMod: "paraformer-realtime-v2", NeedsAPIKey: true},
		{ID: "local_whisper", Label: "本地 faster-whisper", Provider: ProviderOpenAI, BaseURL: "http://127.0.0.1:8000/v1", DefaultMod: "Systran/faster-whisper-large-v3", NeedsAPIKey: false},
	}
}

// FlexSpeaker 兼容本地 ASR 服务返回整数编号（如 0、1）或字符串标识（如 "s0"）。
type FlexSpeaker string

func (f *FlexSpeaker) UnmarshalJSON(b []byte) error {
	s := strings.TrimSpace(string(b))
	if s == "" || s == "null" {
		*f = ""
		return nil
	}
	if strings.HasPrefix(s, `"`) {
		var str string
		if err := json.Unmarshal(b, &str); err != nil {
			return err
		}
		*f = FlexSpeaker(str)
		return nil
	}
	*f = FlexSpeaker(s)
	return nil
}

type Segment struct {
	Start   float64     `json:"start"`
	End     float64     `json:"end"`
	Text    string      `json:"text"`
	Speaker FlexSpeaker `json:"speaker,omitempty"`
	DB      *float64    `json:"db,omitempty"`
	ActiveS *float64    `json:"act_s,omitempty"`
}

type SpeakerEmbedding struct {
	Speaker   FlexSpeaker `json:"speaker"`
	SpeechS   float64     `json:"speech_s"`
	Embedding []float64   `json:"embedding"`
}

type Result struct {
	Text              string             `json:"text"`
	DurationS         float64            `json:"duration_s"`
	SpeechS           float64            `json:"speech_s"`
	Engine            string             `json:"engine"`
	Model             string             `json:"model"`
	EmbeddingModel    string             `json:"embedding_model,omitempty"`
	Segments          []Segment          `json:"segments"`
	Speakers          []SpeakerEmbedding `json:"speakers,omitempty"`
	SpeakerError      string             `json:"speaker_error,omitempty"`
	SyntheticSegments bool               `json:"-"`
}

type ProbeReport struct {
	OK                 bool    `json:"ok"`
	LatencyMs          int64   `json:"latency_ms"`
	Engine             string  `json:"engine"`
	Model              string  `json:"model"`
	EmbeddingModel     string  `json:"embedding_model,omitempty"`
	DurationS          float64 `json:"duration_s"`
	Text               string  `json:"text"`
	HasTimestamps      bool    `json:"has_timestamps"`
	SegmentCount       int     `json:"segment_count"`
	HasSpeakers        bool    `json:"has_speakers"`
	HasDiarizationOnly bool    `json:"has_diarization_only"`
	SpeakerCount       int     `json:"speaker_count"`
	EmbeddingDim       int     `json:"embedding_dim"`
	Error              string  `json:"error,omitempty"`
}

func NormalizeConfig(c Config) Config {
	c.Provider = strings.ToLower(strings.TrimSpace(c.Provider))
	switch c.Provider {
	case "doubao", "volc":
		c.Provider = ProviderVolcengine
	case "custom_http", "hark", "":
		if c.Provider == "" && strings.Contains(c.BaseURL, "/v1") {
			c.Provider = ProviderOpenAI
		} else if c.Provider != "" {
			c.Provider = ProviderHarkHTTP
		} else {
			c.Provider = ProviderOpenAI
		}
	}
	c.BaseURL = strings.TrimRight(strings.TrimSpace(c.BaseURL), "/")
	c.APIKey = strings.TrimSpace(c.APIKey)
	c.AppID = strings.TrimSpace(c.AppID)
	c.Model = strings.TrimSpace(c.Model)
	c.Language = strings.TrimSpace(c.Language)
	if c.Provider == ProviderVolcengine {
		if c.BaseURL == "" {
			c.BaseURL = DefaultVolcFlashURL
		}
		if c.Model == "" {
			c.Model = DefaultVolcResourceID
		}
	}
	return c
}

func ValidateConfig(c Config) error {
	c = NormalizeConfig(c)
	switch c.Provider {
	case ProviderMock:
		return nil
	case ProviderVolcengine:
		if c.APIKey == "" {
			return errors.New("请填写豆包语音控制台签发的 API Key")
		}
		return nil
	case ProviderOpenAI:
		if c.BaseURL == "" {
			return errors.New("请填写 OpenAI 兼容接口的 Base URL")
		}
		if c.Model == "" {
			return errors.New("请填写转写模型名称（如 whisper-1 或 SenseVoiceSmall）")
		}
		return nil
	case ProviderHarkHTTP:
		if c.BaseURL == "" {
			return errors.New("请填写 Hark 本地转写服务地址（如 http://127.0.0.1:8765）")
		}
		return nil
	default:
		return fmt.Errorf("未知的 ASR 接入类型: %s", c.Provider)
	}
}

// CheckHealth 供后台 15 秒心跳调用；OpenAI 接口只做静态配置检查，绝不轮询消耗用户配额。
func CheckHealth(ctx context.Context, cfg Config) (bool, *string) {
	cfg = NormalizeConfig(cfg)
	if err := ValidateConfig(cfg); err != nil {
		return false, nil
	}
	switch cfg.Provider {
	case ProviderMock:
		m := "mock-v1"
		return true, &m
	case ProviderVolcengine:
		m := "豆包大模型 (" + cfg.Model + ")"
		return true, &m
	case ProviderOpenAI:
		m := cfg.Model
		return true, &m
	case ProviderHarkHTTP:
		reqCtx, cancel := context.WithTimeout(ctx, 4*time.Second)
		defer cancel()
		req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, cfg.BaseURL+"/health", nil)
		if err != nil {
			return false, nil
		}
		resp, err := (&http.Client{Timeout: 4 * time.Second}).Do(req)
		if err != nil {
			return false, nil
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			return false, nil
		}
		var body struct {
			OK    *bool  `json:"ok"`
			Model string `json:"model"`
		}
		if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
			return false, nil
		}
		if body.OK != nil && !*body.OK {
			return false, nil
		}
		var mp *string
		if body.Model != "" {
			m := body.Model
			mp = &m
		}
		return true, mp
	default:
		return false, nil
	}
}

// Transcribe 执行单段 Ogg Opus 转写。
func Transcribe(ctx context.Context, cfg Config, opus []byte, name string) (Result, error) {
	cfg = NormalizeConfig(cfg)
	if err := ValidateConfig(cfg); err != nil {
		return Result{}, err
	}
	var res Result
	var err error
	switch cfg.Provider {
	case ProviderMock:
		dur := math.Round(max(1.0, OggOpusDuration(opus))*100) / 100
		txt := fmt.Sprintf("测试转写：%s", name)
		res = Result{
			Text:      txt,
			DurationS: dur,
			SpeechS:   dur,
			Engine:    "mock",
			Model:     "mock-v1",
			Segments:  []Segment{{Start: 0, End: dur, Text: txt}},
		}
	case ProviderVolcengine:
		res, err = transcribeVolcengine(ctx, cfg, opus)
	case ProviderOpenAI:
		res, err = transcribeOpenAI(ctx, cfg, opus, name)
	case ProviderHarkHTTP:
		res, err = transcribeHarkHTTP(ctx, cfg, opus, name)
	default:
		return Result{}, fmt.Errorf("不支持的 ASR 接入类型: %s", cfg.Provider)
	}
	if err != nil {
		return Result{}, err
	}
	AnnotateSegmentLoudness(opus, res.Segments)
	return res, nil
}

// Probe 发送内置双人中文 Ogg Opus 测试语音，实测连通性、耗时、分段时间戳与声纹能力。
func Probe(ctx context.Context, cfg Config) ProbeReport {
	t0 := time.Now()
	pCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()

	res, err := Transcribe(pCtx, cfg, probeAudio, "20260101-000000.opus")
	lat := time.Since(t0).Milliseconds()
	if err != nil {
		return ProbeReport{
			OK:        false,
			LatencyMs: lat,
			Error:     err.Error(),
		}
	}

	hasTS := false
	if !res.SyntheticSegments {
		for _, s := range res.Segments {
			if s.End > s.Start && strings.TrimSpace(s.Text) != "" {
				hasTS = true
				break
			}
		}
	}
	embDim := 0
	validSpk := 0
	for _, sp := range res.Speakers {
		if len(sp.Embedding) > 0 {
			validSpk++
			if embDim == 0 {
				embDim = len(sp.Embedding)
			}
		}
	}
	diarizationOnly := false
	if validSpk == 0 {
		segSpks := map[string]bool{}
		for _, s := range res.Segments {
			if sp := strings.TrimSpace(string(s.Speaker)); sp != "" {
				segSpks[sp] = true
			}
		}
		if len(segSpks) > 0 {
			validSpk = len(segSpks)
			diarizationOnly = true
		}
	}
	return ProbeReport{
		OK:                 true,
		LatencyMs:          lat,
		Engine:             res.Engine,
		Model:              res.Model,
		EmbeddingModel:     res.EmbeddingModel,
		DurationS:          res.DurationS,
		Text:               res.Text,
		HasTimestamps:      hasTS,
		SegmentCount:       len(res.Segments),
		HasSpeakers:        validSpk > 0,
		HasDiarizationOnly: diarizationOnly,
		SpeakerCount:       validSpk,
		EmbeddingDim:       embDim,
	}
}

func buildMultipart(fields map[string]string, fileField, fileName, mimeType string, fileBytes []byte) ([]byte, string, error) {
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	for k, v := range fields {
		if err := mw.WriteField(k, v); err != nil {
			return nil, "", err
		}
	}
	hdr := make(textproto.MIMEHeader)
	hdr.Set("Content-Disposition", fmt.Sprintf(`form-data; name="%s"; filename="%s"`, fileField, fileName))
	hdr.Set("Content-Type", mimeType)
	part, err := mw.CreatePart(hdr)
	if err != nil {
		return nil, "", err
	}
	if _, err := part.Write(fileBytes); err != nil {
		return nil, "", err
	}
	if err := mw.Close(); err != nil {
		return nil, "", err
	}
	return buf.Bytes(), mw.FormDataContentType(), nil
}

func transcribeOpenAI(ctx context.Context, cfg Config, opus []byte, name string) (Result, error) {
	base := cfg.BaseURL
	if !strings.HasSuffix(base, "/v1") {
		base += "/v1"
	}
	endpoint := base + "/audio/transcriptions"
	uploadName := strings.TrimSuffix(name, ".opus") + ".ogg" // 众多云服务白名单认 .ogg 不认 .opus

	doPost := func(respFormat string) (*http.Response, []byte, error) {
		fields := map[string]string{
			"model":           cfg.Model,
			"response_format": respFormat,
		}
		if cfg.Language != "" {
			fields["language"] = cfg.Language
		}
		body, ct, err := buildMultipart(fields, "file", uploadName, "audio/ogg", opus)
		if err != nil {
			return nil, nil, err
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
		if err != nil {
			return nil, nil, err
		}
		req.Header.Set("Content-Type", ct)
		if cfg.APIKey != "" {
			req.Header.Set("Authorization", "Bearer "+cfg.APIKey)
		}
		resp, err := (&http.Client{Timeout: 5 * time.Minute}).Do(req)
		if err != nil {
			return nil, nil, err
		}
		defer resp.Body.Close()
		raw, err := io.ReadAll(io.LimitReader(resp.Body, 4*1024*1024))
		return resp, raw, err
	}

	resp, raw, err := doPost("verbose_json")
	if err != nil {
		return Result{}, err
	}
	if resp.StatusCode == http.StatusBadRequest || resp.StatusCode == http.StatusUnprocessableEntity {
		// 部分模型（如 SenseVoiceSmall、gpt-4o-mini-transcribe）不支持 verbose_json，自动回退 json
		resp, raw, err = doPost("json")
		if err != nil {
			return Result{}, err
		}
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		msg := strings.TrimSpace(string(raw))
		if len(msg) > 200 {
			msg = msg[:200]
		}
		return Result{}, fmt.Errorf("ASR 接口返回 HTTP %d: %s", resp.StatusCode, msg)
	}

	var data struct {
		Text     string  `json:"text"`
		Duration float64 `json:"duration"`
		Segments []struct {
			Start float64 `json:"start"`
			End   float64 `json:"end"`
			Text  string  `json:"text"`
		} `json:"segments"`
	}
	if err := json.Unmarshal(raw, &data); err != nil {
		return Result{}, fmt.Errorf("解析 OpenAI ASR 响应失败: %w", err)
	}

	localDur := OggOpusDuration(opus)
	dur := data.Duration
	if dur <= 0 {
		dur = localDur
	}

	var segs []Segment
	var speechS float64
	for _, s := range data.Segments {
		txt := strings.TrimSpace(s.Text)
		if txt == "" {
			continue
		}
		segs = append(segs, Segment{
			Start: math.Round(s.Start*100) / 100,
			End:   math.Round(s.End*100) / 100,
			Text:  txt,
		})
		speechS += max(0, s.End-s.Start)
	}
	fullText := strings.TrimSpace(data.Text)
	if fullText == "" && len(segs) > 0 {
		parts := make([]string, len(segs))
		for i, s := range segs {
			parts[i] = s.Text
		}
		fullText = strings.Join(parts, "")
	}
	synthetic := false
	if len(segs) == 0 && fullText != "" {
		segs = []Segment{{Start: 0, End: math.Round(dur*100) / 100, Text: fullText}}
		speechS = dur
		synthetic = true
	}
	return Result{
		Text:              fullText,
		DurationS:         math.Round(dur*100) / 100,
		SpeechS:           math.Round(speechS*100) / 100,
		Engine:            ProviderOpenAI,
		Model:             cfg.Model,
		Segments:          segs,
		SyntheticSegments: synthetic,
	}, nil
}

func transcribeHarkHTTP(ctx context.Context, cfg Config, opus []byte, name string) (Result, error) {
	var fields map[string]string
	if cfg.Model != "" {
		fields = map[string]string{"engine": cfg.Model}
	}
	body, ct, err := buildMultipart(fields, "file", name, "audio/ogg", opus)
	if err != nil {
		return Result{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, cfg.BaseURL+"/transcribe", bytes.NewReader(body))
	if err != nil {
		return Result{}, err
	}
	req.Header.Set("Content-Type", ct)
	if cfg.APIKey != "" {
		req.Header.Set("Authorization", "Bearer "+cfg.APIKey)
	}
	resp, err := (&http.Client{Timeout: 5 * time.Minute}).Do(req)
	if err != nil {
		return Result{}, err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 8*1024*1024))
	if err != nil {
		return Result{}, err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		msg := strings.TrimSpace(string(raw))
		if len(msg) > 200 {
			msg = msg[:200]
		}
		return Result{}, fmt.Errorf("本地转写服务返回 HTTP %d: %s", resp.StatusCode, msg)
	}

	var res Result
	if err := json.Unmarshal(raw, &res); err != nil {
		return Result{}, fmt.Errorf("解析本地转写响应失败: %w", err)
	}
	if res.Engine == "" {
		res.Engine = ProviderHarkHTTP
	}
	if res.DurationS <= 0 {
		res.DurationS = math.Round(OggOpusDuration(opus)*100) / 100
	}
	if len(res.Speakers) > 0 && res.EmbeddingModel == "" {
		dim := len(res.Speakers[0].Embedding)
		if dim > 0 {
			res.EmbeddingModel = fmt.Sprintf("hark-default-%d", dim)
		}
	}
	return res, nil
}
