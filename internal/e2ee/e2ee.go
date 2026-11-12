// Package e2ee 手机与电脑端共用的 /v2 端到端加密封包格式；盲信箱服务端不依赖此包。
package e2ee

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"regexp"
	"strings"
)

const (
	KeySize    = 32
	NonceSize  = 12
	PacketVer  = 1
	MinPacket  = 1 + NonceSize + 16 // version(1) + nonce(12) + gcmTag(16)
	itemDomain = "diting:item:"     // 与 Android Crypto.kt 保持字节级兼容
)

var (
	nameRe = regexp.MustCompile(`^[0-9]{8}-[0-9]{6}\.opus$`)
	hexRe  = regexp.MustCompile(`^[0-9a-fA-F]{64}$`)
)

// GenerateKey 生成一把新的 32 字节密钥，返回无填充 URL-safe Base64 字符串。
func GenerateKey() (string, error) {
	buf := make([]byte, KeySize)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(buf), nil
}

// DecodeKey 支持 64 位 hex，或带/不带填充的标准 / URL-safe Base64（解码后须为 32 字节）。
func DecodeKey(raw string) ([]byte, error) {
	s := strings.TrimSpace(raw)
	if s == "" {
		return nil, errors.New("E2EE_KEY 不能为空")
	}
	if hexRe.MatchString(s) {
		b, err := hex.DecodeString(s)
		if err != nil || len(b) != KeySize {
			return nil, errors.New("E2EE_KEY hex 解码失败")
		}
		return b, nil
	}
	normalized := strings.NewReplacer("-", "+", "_", "/").Replace(s)
	if rem := len(normalized) % 4; rem != 0 {
		if rem == 1 {
			return nil, errors.New("E2EE_KEY Base64 长度非法")
		}
		normalized += strings.Repeat("=", 4-rem)
	}
	b, err := base64.StdEncoding.DecodeString(normalized)
	if err != nil || len(b) != KeySize {
		return nil, errors.New("E2EE_KEY 必须解码为 32 字节")
	}
	return b, nil
}

func checkKey(key []byte) error {
	if len(key) != KeySize {
		return fmt.Errorf("E2EE_KEY 长度必须为 %d 字节（当前 %d）", KeySize, len(key))
	}
	return nil
}

// ItemID 计算录音文件名对应的 32 位十六进制不透明标识，盲信箱看不到原始时间戳文件名。
func ItemID(key []byte, name string) (string, error) {
	if err := checkKey(key); err != nil {
		return "", err
	}
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(itemDomain + name))
	return hex.EncodeToString(mac.Sum(nil)[:16]), nil
}

// Encrypt 封包格式：0x01 || 12字节随机Nonce || AES-256-GCM(plaintext, aad)。
// nonce 传 nil 时自动生成密码学安全随机 Nonce。
func Encrypt(key, plaintext, aad, nonce []byte) ([]byte, error) {
	if err := checkKey(key); err != nil {
		return nil, err
	}
	if nonce == nil {
		nonce = make([]byte, NonceSize)
		if _, err := rand.Read(nonce); err != nil {
			return nil, err
		}
	} else if len(nonce) != NonceSize {
		return nil, errors.New("nonce 必须为 12 字节")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	out := make([]byte, 1+NonceSize, 1+NonceSize+len(plaintext)+gcm.Overhead())
	out[0] = PacketVer
	copy(out[1:1+NonceSize], nonce)
	return gcm.Seal(out, nonce, plaintext, aad), nil
}

// Decrypt 校验版本与 AEAD 认证标签后返回明文。
func Decrypt(key, packet, aad []byte) ([]byte, error) {
	if err := checkKey(key); err != nil {
		return nil, err
	}
	if len(packet) < MinPacket || packet[0] != PacketVer {
		return nil, errors.New("密文包长度或版本错误")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return gcm.Open(nil, packet[1:1+NonceSize], packet[1+NonceSize:], aad)
}

// ValidAudioName 检查是否为合法录音段文件名（YYYYMMDD-HHMMSS.opus）。
func ValidAudioName(name string) bool {
	return nameRe.MatchString(name)
}

// PackAudio 将原始文件名与 Ogg Opus 字节绑定进同一密文包。
func PackAudio(key []byte, name string, opus []byte) ([]byte, error) {
	if !ValidAudioName(name) {
		return nil, fmt.Errorf("录音文件名格式错误: %q", name)
	}
	iid, err := ItemID(key, name)
	if err != nil {
		return nil, err
	}
	plain := make([]byte, len(name)+1+len(opus))
	copy(plain, name)
	plain[len(name)] = 0
	copy(plain[len(name)+1:], opus)
	return Encrypt(key, plain, []byte("audio:"+iid), nil)
}

// UnpackAudio 解密音频包并校验内嵌文件名与 itemID 一致，防服务端替换或重放。
func UnpackAudio(key []byte, iid string, packet []byte) (string, []byte, error) {
	plain, err := Decrypt(key, packet, []byte("audio:"+iid))
	if err != nil {
		return "", nil, err
	}
	sep := bytes.IndexByte(plain, 0)
	if sep <= 0 {
		return "", nil, errors.New("音频包缺少文件名分隔符")
	}
	name := string(plain[:sep])
	expectedID, err := ItemID(key, name)
	if err != nil || !ValidAudioName(name) || expectedID != iid {
		return "", nil, errors.New("录音文件名或条目 ID 不匹配")
	}
	return name, plain[sep+1:], nil
}
