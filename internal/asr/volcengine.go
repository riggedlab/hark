package asr

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"strings"
	"time"
)

const (
	DefaultVolcFlashURL   = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash"
	DefaultVolcResourceID = "volc.bigasr.auc_turbo"
)

func randomUUIDv4() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%s-%s-%s-%s-%s",
		hex.EncodeToString(b[0:4]),
		hex.EncodeToString(b[4:6]),
		hex.EncodeToString(b[6:8]),
		hex.EncodeToString(b[8:10]),
		hex.EncodeToString(b[10:16]),
	)
}

type volcUtterance struct {
	Text      string      `json:"text"`
	StartTime int64       `json:"start_time"`
	EndTime   int64       `json:"end_time"`
	Definite  *bool       `json:"definite,omitempty"`
	Speaker   FlexSpeaker `json:"speaker,omitempty"`
	SpeakerID FlexSpeaker `json:"speaker_id,omitempty"`
	Additions map[string]any `json:"additions,omitempty"`
}

func extractVolcSpeaker(u volcUtterance) FlexSpeaker {
	if u.SpeakerID != "" {
		return u.SpeakerID
	}
	if u.Speaker != "" {
		return u.Speaker
	}
	if u.Additions != nil {
		for _, k := range []string{"speaker_id", "speaker"} {
			if v, ok := u.Additions[k]; ok && v != nil {
				s := strings.TrimSpace(fmt.Sprint(v))
				if s != "" && s != "<nil>" {
					return FlexSpeaker(s)
				}
			}
		}
	}
	return ""
}

func transcribeVolcengine(ctx context.Context, cfg Config, opus []byte) (Result, error) {
	endpoint := cfg.BaseURL
	if endpoint == "" {
		endpoint = DefaultVolcFlashURL
	} else if !strings.Contains(endpoint, "/api/") {
		endpoint = strings.TrimRight(endpoint, "/") + "/api/v3/auc/bigmodel/recognize/flash"
	}
	resourceID := cfg.Model
	if resourceID == "" {
		resourceID = DefaultVolcResourceID
	}

	b64Audio := base64.StdEncoding.EncodeToString(opus)

	sendOnce := func(enableSpeaker bool) (int, http.Header, []byte, error) {
		uid := "hark"
		if cfg.AppID != "" {
			uid = cfg.AppID
		}
		reqMap := map[string]any{
			"model_name":      "bigmodel",
			"enable_itn":      true,
			"enable_punc":     true,
			"enable_ddc":      true,
			"show_utterances": true,
		}
		if enableSpeaker {
			reqMap["enable_speaker_info"] = true
		}
		payload := map[string]any{
			"user": map[string]string{"uid": uid},
			"audio": map[string]any{
				"data":   b64Audio,
				"format": "ogg",
				"codec":  "opus",
			},
			"request": reqMap,
		}
		rawReq, err := json.Marshal(payload)
		if err != nil {
			return 0, nil, nil, err
		}

		req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(rawReq))
		if err != nil {
			return 0, nil, nil, err
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Api-Resource-Id", resourceID)
		req.Header.Set("X-Api-Request-Id", randomUUIDv4())
		req.Header.Set("X-Api-Sequence", "-1")
		if cfg.AppID != "" {
			req.Header.Set("X-Api-App-Key", cfg.AppID)
			req.Header.Set("X-Api-Access-Key", cfg.APIKey)
		} else {
			req.Header.Set("X-Api-Key", cfg.APIKey)
		}

		resp, err := (&http.Client{Timeout: 5 * time.Minute}).Do(req)
		if err != nil {
			return 0, nil, nil, err
		}
		defer resp.Body.Close()
		body, err := io.ReadAll(io.LimitReader(resp.Body, 8*1024*1024))
		return resp.StatusCode, resp.Header, body, err
	}

	enableSpeaker := true
	backoffs := []time.Duration{time.Second, 2500 * time.Millisecond}
	var httpCode int
	var hdr http.Header
	var raw []byte
	var err error

	for attempt := 0; ; attempt++ {
		httpCode, hdr, raw, err = sendOnce(enableSpeaker)
		if err == nil {
			apiCode := strings.TrimSpace(hdr.Get("X-Api-Status-Code"))
			// 若账号资源档位不支持 enable_speaker_info 参数报 45000001，自动降级关闭说话人分离重试一次
			if apiCode == "45000001" && enableSpeaker {
				enableSpeaker = false
				continue
			}
			retryable := httpCode == http.StatusTooManyRequests || httpCode >= 500 ||
				apiCode == "55000031" || strings.HasPrefix(apiCode, "550")
			if !retryable || attempt >= len(backoffs) {
				break
			}
		} else if ctx.Err() != nil || attempt >= len(backoffs) {
			return Result{}, err
		}

		timer := time.NewTimer(backoffs[attempt])
		select {
		case <-ctx.Done():
			timer.Stop()
			return Result{}, ctx.Err()
		case <-timer.C:
		}
	}

	apiCode := strings.TrimSpace(hdr.Get("X-Api-Status-Code"))
	apiMsg := strings.TrimSpace(hdr.Get("X-Api-Message"))
	logID := strings.TrimSpace(hdr.Get("X-Tt-Logid"))

	if httpCode == http.StatusUnauthorized || httpCode == http.StatusForbidden {
		return Result{}, fmt.Errorf("豆包语音鉴权失败 (HTTP %d %s)：请确认使用的是「豆包语音控制台」API Key（与火山方舟 Key 不通用），且已开通资源 %s", httpCode, apiMsg, resourceID)
	}
	if httpCode < 200 || httpCode >= 300 {
		snippet := strings.TrimSpace(string(raw))
		if len(snippet) > 160 {
			snippet = snippet[:160]
		}
		return Result{}, fmt.Errorf("豆包语音返回 HTTP %d (code=%s msg=%s): %s", httpCode, apiCode, apiMsg, snippet)
	}

	localDur := math.Round(OggOpusDuration(opus)*100) / 100
	// 20000003 表示静音音频（无人声），视为空文本正常完成，不作报错重试
	if apiCode == "20000003" {
		return Result{
			DurationS: localDur,
			SpeechS:   0,
			Engine:    ProviderVolcengine,
			Model:     resourceID,
			Segments:  []Segment{},
		}, nil
	}
	if apiCode != "" && apiCode != "20000000" {
		return Result{}, fmt.Errorf("豆包语音错误 %s (%s, logid=%s)", apiCode, apiMsg, logID)
	}

	var data struct {
		AudioInfo struct {
			Duration int64 `json:"duration"`
		} `json:"audio_info"`
		Result struct {
			Text       string          `json:"text"`
			Utterances []volcUtterance `json:"utterances"`
		} `json:"result"`
	}
	if err := json.Unmarshal(raw, &data); err != nil {
		return Result{}, fmt.Errorf("解析豆包语音响应失败: %w", err)
	}

	durS := float64(data.AudioInfo.Duration) / 1000.0
	if durS <= 0 {
		durS = localDur
	}

	var segs []Segment
	var speechS float64
	for _, u := range data.Result.Utterances {
		if u.Definite != nil && !*u.Definite {
			continue
		}
		txt := strings.TrimSpace(u.Text)
		if txt == "" {
			continue
		}
		st := math.Round(float64(u.StartTime)/10.0) / 100.0
		en := math.Round(float64(u.EndTime)/10.0) / 100.0
		segs = append(segs, Segment{
			Start:   st,
			End:     en,
			Text:    txt,
			Speaker: extractVolcSpeaker(u),
		})
		if en > st {
			speechS += en - st
		}
	}

	fullText := strings.TrimSpace(data.Result.Text)
	if fullText == "" && len(segs) > 0 {
		parts := make([]string, len(segs))
		for i, s := range segs {
			parts[i] = s.Text
		}
		fullText = strings.Join(parts, "")
	}
	synthetic := false
	if len(segs) == 0 && fullText != "" {
		segs = []Segment{{Start: 0, End: math.Round(durS*100) / 100, Text: fullText}}
		speechS = durS
		synthetic = true
	}

	return Result{
		Text:              fullText,
		DurationS:         math.Round(durS*100) / 100,
		SpeechS:           math.Round(speechS*100) / 100,
		Engine:            ProviderVolcengine,
		Model:             resourceID,
		Segments:          segs,
		SyntheticSegments: synthetic,
	}, nil
}
