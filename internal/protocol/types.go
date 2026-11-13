// Package protocol 定义盲信箱 /v2 的共享消息结构与纯标准库服务端实现。
package protocol

const (
	SlotWorkerStatus  = "worker_status"
	SlotSpeakersMac   = "speakers_mac"
	SlotSpeakersPhone = "speakers_phone"
)

var AllSlots = []string{SlotWorkerStatus, SlotSpeakersMac, SlotSpeakersPhone}

type HealthResp struct {
	OK bool `json:"ok"`
}

type PutAudioResp struct {
	OK     bool   `json:"ok"`
	Size   int    `json:"size,omitempty"`
	Status string `json:"status,omitempty"`
}

type InboxItem struct {
	ID   string `json:"id"`
	Size int64  `json:"size"`
}

type InboxListResp struct {
	Ver   int         `json:"ver"`
	Items []InboxItem `json:"items"`
}

type PutTextResp struct {
	OK  bool `json:"ok"`
	Seq int  `json:"seq"`
}

type PutSlotResp struct {
	OK  bool `json:"ok"`
	Ver int  `json:"ver"`
}

type SlotMeta struct {
	Ver     int     `json:"ver"`
	AgeS    float64 `json:"age_s"`
	DataB64 string  `json:"data_b64,omitempty"`
}

type PollTextItem struct {
	Seq     int    `json:"seq"`
	ID      string `json:"id"`
	DataB64 string `json:"data_b64"`
}

type PollResp struct {
	Ver         int                 `json:"ver"`
	CursorReset bool                `json:"cursor_reset,omitempty"`
	InboxCount  int                 `json:"inbox_count"`
	HasMore     bool                `json:"has_more"`
	NewTexts    []PollTextItem      `json:"new_texts"`
	Slots       map[string]SlotMeta `json:"slots"`
}

// WorkerStatus 加密写入 slot:worker_status，供手机端实时展示电脑端工作状态。
type WorkerStatus struct {
	Phase         string  `json:"phase"` // "idle" | "transcribing" | "asr_down" | "error" | "paused"
	Current       *string `json:"current"`
	ASROK         bool    `json:"asr_ok"`
	ASRModel      *string `json:"asr_model"`
	LastDone      *string `json:"last_done"`
	LastError     *string `json:"last_error"`
	PhaseElapsedS float64 `json:"phase_elapsed_s"`
}

// Segment 转写文本单段结构（与 Android Transcripts.kt 保持一致）。
type Segment struct {
	Start   float64  `json:"start"`
	End     float64  `json:"end"`
	Text    string   `json:"text"`
	Speaker *string  `json:"speaker"`
	DB      *float64 `json:"db,omitempty"`
	ActiveS *float64 `json:"act_s,omitempty"`
}

// TranscriptPayload 加密写入 /v2/outbox/{item_id} 的转写结果。
type TranscriptPayload struct {
	Name      string    `json:"name"`
	StartedAt string    `json:"started_at"`
	DurationS float64   `json:"duration_s"`
	SpeechS   float64   `json:"speech_s"`
	Engine    string    `json:"engine"`
	Model     string    `json:"model"`
	Text      string    `json:"text"`
	Segments  []Segment `json:"segments"`
}

// MacSpeakerItem Worker 加密写入 slot:speakers_mac 的单个人物初始样本。
type MacSpeakerItem struct {
	FirstSeen   *string  `json:"first_seen"`
	Sample      *string  `json:"sample"`
	SampleName  *string  `json:"sample_name"`
	SampleStart *float64 `json:"sample_start"`
}

type MacSpeakerRoster struct {
	Items map[string]MacSpeakerItem `json:"items"`
}

// PhoneSpeakerItem 手机端加密写入 slot:speakers_phone 的人物改名与合并关系。
type PhoneSpeakerItem struct {
	Name       string  `json:"name,omitempty"`
	MergedInto *string `json:"merged_into,omitempty"`
}

type PhoneSpeakerRoster struct {
	Items map[string]PhoneSpeakerItem `json:"items"`
}
