// Package worker 实现拉取解密、ASR 转写、声纹认人、SQLite 存档管理与状态回传。
package worker

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/riggedlab/hark/internal/asr"
	"github.com/riggedlab/hark/internal/e2ee"
)

// SecretStore 抽象系统钥匙串（桌面端注入 mygo.Secrets，无头 CLI 或未签名开发包自动回退 0600 本地文件）。
type SecretStore interface {
	Get(key string) (string, error)
	Set(key, value string) error
	Delete(key string) error
}

type Config struct {
	ServerURL        string     `json:"server_url"`
	WorkerToken      string     `json:"worker_token,omitempty"`
	E2EEKey          string     `json:"e2ee_key,omitempty"`
	KeychainStored   bool       `json:"keychain_stored,omitempty"`
	ServerCA         string     `json:"server_ca,omitempty"`
	SpeakerThreshold float64    `json:"speaker_threshold"`
	DataDir          string     `json:"data_dir"`
	OpenAtLogin      bool       `json:"open_at_login"`
	CloseToTray      bool       `json:"close_to_tray"`
	ASR              asr.Config `json:"asr"`
}

func DefaultDataDir() string {
	if v := os.Getenv("HARK_HOME"); v != "" {
		return expandHome(v)
	}
	if v := os.Getenv("DITING_HOME"); v != "" {
		return expandHome(v)
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, "hark")
}

func NormalizeDataDir(raw string) string {
	d := expandHome(strings.TrimSpace(raw))
	if d == "" {
		return DefaultDataDir()
	}
	return d
}

func DefaultConfigPath() string {
	if v := os.Getenv("HARK_CONF"); v != "" {
		return expandHome(v)
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".config", "hark", "config.json")
}

func expandHome(p string) string {
	if strings.HasPrefix(p, "~/") {
		if home, err := os.UserHomeDir(); err == nil {
			return filepath.Join(home, p[2:])
		}
	}
	return p
}

func DefaultConfig() Config {
	return Config{
		SpeakerThreshold: 0.60,
		DataDir:          DefaultDataDir(),
		CloseToTray:      true,
		ASR: asr.Config{
			Provider: asr.ProviderOpenAI,
			BaseURL:  "https://api.siliconflow.cn/v1",
			Model:    "FunAudioLLM/SenseVoiceSmall",
			Language: "zh",
		},
	}
}

// ValidateReady 检查配置是否足以开始拉取信箱。
func (c Config) ValidateReady() ([]byte, error) {
	if strings.TrimSpace(c.ServerURL) == "" {
		return nil, errors.New("未填写盲信箱地址")
	}
	if strings.TrimSpace(c.WorkerToken) == "" {
		return nil, errors.New("未填写电脑端连接令牌")
	}
	key, err := e2ee.DecodeKey(c.E2EEKey)
	if err != nil {
		return nil, err
	}
	if err := asr.ValidateConfig(c.ASR); err != nil {
		return nil, err
	}
	return key, nil
}

const (
	secretWorkerToken = "worker_token"
	secretE2EEKey     = "e2ee_key"
	secretASRKey      = "asr_api_key"
)

// LoadConfig 从 JSON 或兼容的 .env 文件读取配置，并优先从系统钥匙串合并敏感凭据。
func LoadConfig(path string, secrets SecretStore) (Config, error) {
	cfg := DefaultConfig()
	if path == "" {
		path = DefaultConfigPath()
	}
	path = expandHome(path)

	if raw, err := os.ReadFile(path); err == nil {
		trimmed := strings.TrimSpace(string(raw))
		if strings.HasPrefix(trimmed, "{") {
			if err := json.Unmarshal(raw, &cfg); err != nil {
				return cfg, err
			}
		} else {
			parseLegacyEnv(trimmed, &cfg)
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return cfg, err
	} else {
		// 兼容旧版 ~/.config/hark/worker.env
		home, _ := os.UserHomeDir()
		legacy := filepath.Join(home, ".config", "hark", "worker.env")
		if raw, err := os.ReadFile(legacy); err == nil {
			parseLegacyEnv(string(raw), &cfg)
		}
	}

	if secrets != nil && cfg.KeychainStored {
		if v, err := secrets.Get(secretWorkerToken); err == nil && v != "" {
			cfg.WorkerToken = v
		}
		if v, err := secrets.Get(secretE2EEKey); err == nil && v != "" {
			cfg.E2EEKey = v
		}
		if v, err := secrets.Get(secretASRKey); err == nil && v != "" {
			cfg.ASR.APIKey = v
		}
	}

	cfg.ServerURL = strings.TrimRight(strings.TrimSpace(cfg.ServerURL), "/")
	cfg.WorkerToken = strings.TrimSpace(cfg.WorkerToken)
	cfg.E2EEKey = strings.TrimSpace(cfg.E2EEKey)
	cfg.DataDir = expandHome(strings.TrimSpace(cfg.DataDir))
	if cfg.DataDir == "" {
		cfg.DataDir = DefaultDataDir()
	}
	if cfg.SpeakerThreshold <= 0 || cfg.SpeakerThreshold >= 1 {
		cfg.SpeakerThreshold = 0.60
	}
	cfg.ASR = asr.NormalizeConfig(cfg.ASR)
	return cfg, nil
}

// SaveConfig 保存配置；若系统钥匙串可用，敏感凭据写入钥匙串并从磁盘 JSON 抹除。
func SaveConfig(path string, cfg Config, secrets SecretStore) error {
	if path == "" {
		path = DefaultConfigPath()
	}
	path = expandHome(path)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}

	cfg.ServerURL = strings.TrimRight(strings.TrimSpace(cfg.ServerURL), "/")
	cfg.WorkerToken = strings.TrimSpace(cfg.WorkerToken)
	cfg.E2EEKey = strings.TrimSpace(cfg.E2EEKey)
	cfg.DataDir = expandHome(strings.TrimSpace(cfg.DataDir))
	if cfg.DataDir == "" {
		cfg.DataDir = DefaultDataDir()
	}
	if cfg.SpeakerThreshold <= 0 || cfg.SpeakerThreshold >= 1 {
		cfg.SpeakerThreshold = 0.60
	}
	cfg.ASR = asr.NormalizeConfig(cfg.ASR)

	onDisk := cfg
	onDisk.KeychainStored = false
	if secrets != nil {
		keptInKeychain := true
		saveSecret := func(k, v string) {
			if !keptInKeychain {
				return
			}
			if v == "" {
				_ = secrets.Delete(k)
				return
			}
			if err := secrets.Set(k, v); err != nil {
				keptInKeychain = false
			}
		}
		saveSecret(secretWorkerToken, cfg.WorkerToken)
		saveSecret(secretE2EEKey, cfg.E2EEKey)
		saveSecret(secretASRKey, cfg.ASR.APIKey)
		if keptInKeychain {
			onDisk.KeychainStored = true
			onDisk.WorkerToken = ""
			onDisk.E2EEKey = ""
			onDisk.ASR.APIKey = ""
		} else {
			_ = secrets.Delete(secretWorkerToken)
			_ = secrets.Delete(secretE2EEKey)
			_ = secrets.Delete(secretASRKey)
		}
	}

	raw, err := json.MarshalIndent(onDisk, "", "  ")
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func parseLegacyEnv(content string, cfg *Config) {
	kv := map[string]string{}
	for _, line := range strings.Split(content, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		idx := strings.IndexByte(line, '=')
		if idx <= 0 {
			continue
		}
		kv[strings.TrimSpace(line[:idx])] = strings.TrimSpace(line[idx+1:])
	}
	if v := kv["SERVER_URL"]; v != "" {
		cfg.ServerURL = v
	}
	if v := kv["WORKER_TOKEN"]; v != "" {
		cfg.WorkerToken = v
	}
	if v := kv["E2EE_KEY"]; v != "" {
		cfg.E2EEKey = v
	}
	if v := kv["SERVER_CA"]; v != "" {
		cfg.ServerCA = v
	}
	if v, err := strconv.ParseFloat(kv["SPEAKER_THRESHOLD"], 64); err == nil && v > 0 && v < 1 {
		cfg.SpeakerThreshold = v
	}
	if v := kv["ASR_PROVIDER"]; v != "" {
		cfg.ASR.Provider = v
	}
	if v := kv["ASR_URL"]; v != "" {
		cfg.ASR.BaseURL = v
	}
	if v := kv["ASR_BASE_URL"]; v != "" {
		cfg.ASR.BaseURL = v
	}
	if v := kv["ASR_API_KEY"]; v != "" {
		cfg.ASR.APIKey = v
	}
	if v := kv["ASR_APP_ID"]; v != "" {
		cfg.ASR.AppID = v
	}
	if v := kv["ASR_MODEL"]; v != "" {
		cfg.ASR.Model = v
	} else if v := kv["ASR_ENGINE"]; v != "" {
		cfg.ASR.Model = v
	}
	if v := kv["ASR_LANGUAGE"]; v != "" {
		cfg.ASR.Language = v
	}
}
