package asr

import (
	"bytes"
	"encoding/binary"
	"math"
	"sort"

	"github.com/pion/opus"
)

const (
	opusFrameMs      = 20.0
	opusRate48k      = 48000
	opusFrameSamples = 960 // 20ms @ 48kHz
	minFloorEnergy   = 1e-10
	activeGateMinDB  = -62.0
	activeSpanDB     = 15.0
	minClampDB       = -65.0
)

// AnnotateSegmentLoudness 从 Ogg Opus 原始流逐帧（20ms）解码计算每句有效语音的平均响度（dBFS），
// 写入 segments[i].DB（保留 1 位小数，例如 -20.4）。若某句已有服务端计算的 DB 或音频非标准 Opus 则安全跳过。
func AnnotateSegmentLoudness(ogg []byte, segments []Segment) {
	if len(ogg) < 27 || len(segments) == 0 {
		return
	}
	needAny := false
	for i := range segments {
		if segments[i].DB == nil && segments[i].End > segments[i].Start {
			needAny = true
			break
		}
	}
	if !needAny {
		return
	}

	frameEnergies := decodeOpusFrameEnergies(ogg)
	if len(frameEnergies) == 0 {
		return
	}
	for i := range segments {
		if segments[i].DB != nil {
			continue
		}
		if db, actS, ok := computeActiveSpeechStats(frameEnergies, opusFrameMs/1000.0, segments[i].Start, segments[i].End); ok {
			vDB := db
			vAct := actS
			segments[i].DB = &vDB
			segments[i].ActiveS = &vAct
		}
	}
}

// computeActiveSpeechStats 计算区间 [startS, endS] 内有效发声帧的平均能量分贝（dBFS）与有效发声秒数：
// 取区间内 90 分位帧响度 p90，过滤掉低于 max(p90-15dB, -62dBFS) 的句中停顿静音帧，避免说话停顿拉低真实距离响度。
func computeActiveSpeechStats(frameEnergies []float64, frameDurS, startS, endS float64) (float64, float64, bool) {
	if len(frameEnergies) == 0 || endS <= startS || frameDurS <= 0 {
		return 0, 0, false
	}
	sIdx := int(math.Floor(startS / frameDurS))
	eIdx := int(math.Ceil(endS / frameDurS))
	if sIdx < 0 {
		sIdx = 0
	}
	if eIdx > len(frameEnergies) {
		eIdx = len(frameEnergies)
	}
	if eIdx <= sIdx {
		return 0, 0, false
	}

	slice := frameEnergies[sIdx:eIdx]
	dbs := make([]float64, len(slice))
	for i, e := range slice {
		dbs[i] = 10.0 * math.Log10(max(e, minFloorEnergy))
	}
	sorted := append([]float64(nil), dbs...)
	sort.Float64s(sorted)
	p90 := sorted[min(len(sorted)-1, int(float64(len(sorted))*0.90))]
	gate := max(p90-activeSpanDB, activeGateMinDB)

	var activeSum float64
	var activeCnt int
	var allSum float64
	for i, e := range slice {
		allSum += e
		if dbs[i] >= gate {
			activeSum += e
			activeCnt++
		}
	}
	meanE := allSum / float64(len(slice))
	effFrames := len(slice)
	if activeCnt > 0 {
		meanE = activeSum / float64(activeCnt)
		effFrames = activeCnt
	}
	rawDB := 10.0 * math.Log10(max(meanE, minFloorEnergy))
	clamped := min(0.0, max(minClampDB, rawDB))
	actSec := max(0.02, math.Round(float64(effFrames)*frameDurS*100.0)/100.0)
	return math.Round(clamped*10.0) / 10.0, actSec, true
}

func computeActiveSpeechDB(frameEnergies []float64, frameDurS, startS, endS float64) (float64, bool) {
	db, _, ok := computeActiveSpeechStats(frameEnergies, frameDurS, startS, endS)
	return db, ok
}

func decodeOpusFrameEnergies(ogg []byte) []float64 {
	if len(ogg) < 27 || !bytes.Equal(ogg[:4], []byte("OggS")) {
		return nil
	}
	var preSkip int
	if idx := bytes.Index(ogg[:min(len(ogg), 512)], []byte("OpusHead")); idx >= 0 && idx+12 <= len(ogg) {
		preSkip = int(binary.LittleEndian.Uint16(ogg[idx+10 : idx+12]))
	}

	pkts := extractOggAudioPackets(ogg)
	if len(pkts) == 0 {
		return nil
	}

	dec := opus.NewDecoder()
	// 单包最大 120ms @ 48kHz 双声道 s16le = 5760 * 4 = 23040 字节
	pcmBuf := make([]byte, 5760*4)
	energies := make([]float64, 0, len(pkts))

	skippedSamples := 0
	var winSumSq float64
	var winCount int

	for _, pkt := range pkts {
		if len(pkt) == 0 {
			continue
		}
		// pion/opus.NewDecoder() 固定输出 48kHz 单声道 s16le（每采样点 2 字节，立体声包已在解码器内部下混）
		if _, _, err := dec.Decode(pkt, pcmBuf); err != nil {
			continue
		}
		samplesPerChan := opusPacketSamples48k(pkt)
		if samplesPerChan <= 0 || samplesPerChan > 5760 {
			samplesPerChan = opusFrameSamples
		}
		limit := min(len(pcmBuf), samplesPerChan*2)
		for off := 0; off+1 < limit; off += 2 {
			if skippedSamples < preSkip {
				skippedSamples++
				continue
			}
			s := float64(int16(binary.LittleEndian.Uint16(pcmBuf[off:off+2]))) / 32768.0
			winSumSq += s * s
			winCount++
			if winCount == opusFrameSamples {
				energies = append(energies, winSumSq/float64(opusFrameSamples))
				winSumSq = 0
				winCount = 0
			}
		}
	}
	if winCount >= opusFrameSamples/4 {
		energies = append(energies, winSumSq/float64(winCount))
	}
	return energies
}

// opusPacketSamples48k 按 RFC 6716 第 3.1 节 TOC 字节解析单个 Opus 包对应的 48kHz 采样点数。
func opusPacketSamples48k(pkt []byte) int {
	if len(pkt) == 0 {
		return 0
	}
	toc := pkt[0]
	cfg := toc >> 3
	var frameSize int
	switch {
	case cfg < 12: // SILK-only: 10, 20, 40, 60 ms
		frameSize = [...]int{480, 960, 1920, 2880}[cfg&3]
	case cfg < 16: // Hybrid: 10, 20 ms
		frameSize = [...]int{480, 960}[cfg&1]
	default: // CELT-only: 2.5, 5, 10, 20 ms
		frameSize = [...]int{120, 240, 480, 960}[cfg&3]
	}
	code := toc & 3
	switch code {
	case 0:
		return frameSize
	case 1, 2:
		return frameSize * 2
	default:
		if len(pkt) < 2 {
			return frameSize
		}
		return frameSize * int(pkt[1]&0x3F)
	}
}

func extractOggAudioPackets(ogg []byte) [][]byte {
	var pkts [][]byte
	var cur []byte
	pos := 0
	for pos+27 <= len(ogg) {
		if !bytes.Equal(ogg[pos:pos+4], []byte("OggS")) {
			next := bytes.Index(ogg[pos:], []byte("OggS"))
			if next < 0 {
				break
			}
			pos += next
			continue
		}
		nSegs := int(ogg[pos+26])
		if pos+27+nSegs > len(ogg) {
			break
		}
		lacing := ogg[pos+27 : pos+27+nSegs]
		dataPos := pos + 27 + nSegs
		for _, l := range lacing {
			sz := int(l)
			if dataPos+sz > len(ogg) {
				return pkts
			}
			cur = append(cur, ogg[dataPos:dataPos+sz]...)
			dataPos += sz
			if sz < 255 {
				if len(cur) > 0 && !bytes.HasPrefix(cur, []byte("OpusHead")) && !bytes.HasPrefix(cur, []byte("OpusTags")) {
					cp := make([]byte, len(cur))
					copy(cp, cur)
					pkts = append(pkts, cp)
				}
				cur = cur[:0]
			}
		}
		pos = dataPos
	}
	return pkts
}
