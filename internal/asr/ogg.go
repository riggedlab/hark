package asr

import (
	"bytes"
	"encoding/binary"
)

// OggOpusDuration 从 Ogg Opus 字节流末尾读取最后一个 OggS 页的 granule position，计算精确秒数（48kHz 时基）。
func OggOpusDuration(data []byte) float64 {
	if len(data) < 27 || !bytes.Equal(data[:4], []byte("OggS")) {
		return 0
	}
	var preSkip uint16
	if idx := bytes.Index(data[:min(len(data), 512)], []byte("OpusHead")); idx >= 0 && idx+12 <= len(data) {
		preSkip = binary.LittleEndian.Uint16(data[idx+10 : idx+12])
	}
	tailStart := max(0, len(data)-65536)
	tail := data[tailStart:]
	pos := bytes.LastIndex(tail, []byte("OggS"))
	for pos >= 0 {
		if pos+14 <= len(tail) {
			granule := int64(binary.LittleEndian.Uint64(tail[pos+6 : pos+14]))
			if granule > 0 {
				samples := max(int64(0), granule-int64(preSkip))
				return float64(samples) / 48000.0
			}
		}
		if pos == 0 {
			break
		}
		pos = bytes.LastIndex(tail[:pos], []byte("OggS"))
	}
	return 0
}
