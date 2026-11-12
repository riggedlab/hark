package worker

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"math"
	"sort"
	"strings"

	"github.com/riggedlab/hark/internal/asr"
	"github.com/riggedlab/hark/internal/protocol"
)

const (
	minSpeechS     = 2.0
	newSpeakerMinS = 10.0
)

type knownSpeaker struct {
	id             string
	centroid       []float64
	weight         float64
	embeddingModel string
}

func l2Norm(v []float64) []float64 {
	var sum float64
	for _, x := range v {
		sum += x * x
	}
	n := math.Sqrt(sum)
	if n == 0 {
		n = 1
	}
	out := make([]float64, len(v))
	for i, x := range v {
		out[i] = x / n
	}
	return out
}

func cosine(a, b []float64) float64 {
	if len(a) != len(b) || len(a) == 0 {
		return -1
	}
	var sum float64
	for i := range a {
		sum += a[i] * b[i]
	}
	return sum
}

func (s *Store) resolveSpeakerLocked(sid string) string {
	seen := map[string]bool{}
	cur := sid
	for cur != "" && !seen[cur] {
		seen[cur] = true
		var merged sql.NullString
		err := s.db.QueryRow(`SELECT merged_into FROM speakers WHERE id = ?`, cur).Scan(&merged)
		if err != nil || !merged.Valid || merged.String == "" {
			return cur
		}
		cur = merged.String
	}
	return cur
}

// ApplyPhoneRoster 同步手机端的合并关系与人物自定义名称（声纹原型固定保留，手机拆开可无损恢复）。
func (s *Store) ApplyPhoneRoster(items map[string]protocol.PhoneSpeakerItem) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	rows, err := s.db.Query(`SELECT id, COALESCE(merged_into, '') FROM speakers`)
	if err != nil {
		return err
	}
	current := map[string]string{}
	for rows.Next() {
		var id, m string
		if err := rows.Scan(&id, &m); err == nil {
			current[id] = m
		}
	}
	rows.Close()

	proposed := map[string]string{}
	for k, v := range current {
		proposed[k] = v
	}
	for sid, info := range items {
		if _, ok := current[sid]; ok {
			if info.MergedInto != nil {
				proposed[sid] = strings.TrimSpace(*info.MergedInto)
			} else {
				proposed[sid] = ""
			}
		}
	}

	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	for sid, info := range items {
		if _, ok := current[sid]; !ok {
			continue
		}
		// 更新只读展示名（供电脑端存档检索与导出直接显示人名）
		_, _ = tx.Exec(`UPDATE speakers SET display_name = ? WHERE id = ?`, strings.TrimSpace(info.Name), sid)

		// 检查合并链无环且目标存在
		seen := map[string]bool{sid: true}
		target := proposed[sid]
		valid := true
		for target != "" {
			if seen[target] {
				valid = false
				break
			}
			next, exists := proposed[target]
			if !exists {
				valid = false
				break
			}
			seen[target] = true
			target = next
		}
		if valid {
			var m any
			if proposed[sid] != "" {
				m = proposed[sid]
			}
			if _, err := tx.Exec(`UPDATE speakers SET merged_into = ? WHERE id = ?`, m, sid); err != nil {
				return err
			}
		}
	}
	return tx.Commit()
}

// ExportMacRoster 导出给 slot:speakers_mac 的人物首次出现与样本句。
func (s *Store) ExportMacRoster() (protocol.MacSpeakerRoster, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	rows, err := s.db.Query(`SELECT id, first_seen, sample, sample_name, sample_start FROM speakers`)
	if err != nil {
		return protocol.MacSpeakerRoster{}, err
	}
	defer rows.Close()

	items := map[string]protocol.MacSpeakerItem{}
	for rows.Next() {
		var id string
		var fs, sample, sname sql.NullString
		var sstart sql.NullFloat64
		if err := rows.Scan(&id, &fs, &sample, &sname, &sstart); err != nil {
			continue
		}
		var it protocol.MacSpeakerItem
		if fs.Valid && fs.String != "" {
			v := fs.String
			it.FirstSeen = &v
		}
		if sample.Valid && sample.String != "" {
			v := sample.String
			it.Sample = &v
		}
		if sname.Valid && sname.String != "" {
			v := sname.String
			it.SampleName = &v
		}
		if sstart.Valid {
			v := sstart.Float64
			it.SampleStart = &v
		}
		items[id] = it
	}
	return protocol.MacSpeakerRoster{Items: items}, nil
}

// AssignSpeakers 将单段音频内的局部说话人映射为全局 spk_xxx 或局部 other_N。
// 当 ASR 不返回声纹（如第三方 OpenAI 接口）时优雅降级，返回空映射。
func (s *Store) AssignSpeakers(res asr.Result, threshold float64) (map[string]string, error) {
	if len(res.Speakers) == 0 {
		return map[string]string{}, nil
	}
	if threshold <= 0 || threshold >= 1 {
		threshold = 0.60
	}

	spoken := map[string]bool{}
	for _, seg := range res.Segments {
		if strings.TrimSpace(seg.Text) != "" && string(seg.Speaker) != "" {
			spoken[string(seg.Speaker)] = true
		}
	}

	type localSpk struct {
		speaker string
		speechS float64
		emb     []float64
	}
	var locals []localSpk
	for _, sp := range res.Speakers {
		if len(sp.Embedding) > 0 && sp.SpeechS >= minSpeechS && spoken[string(sp.Speaker)] {
			locals = append(locals, localSpk{
				speaker: string(sp.Speaker),
				speechS: sp.SpeechS,
				emb:     l2Norm(sp.Embedding),
			})
		}
	}
	if len(locals) == 0 {
		return map[string]string{}, nil
	}
	sort.Slice(locals, func(i, j int) bool { return locals[i].speechS > locals[j].speechS })

	// 1. 文件内全连接聚类（防 A-B-C 链式串味）
	var clusters [][]localSpk
	for _, sp := range locals {
		placed := false
		for ci := range clusters {
			allClose := true
			for _, m := range clusters[ci] {
				if cosine(sp.emb, m.emb) < threshold {
					allClose = false
					break
				}
			}
			if allClose {
				clusters[ci] = append(clusters[ci], sp)
				placed = true
				break
			}
		}
		if !placed {
			clusters = append(clusters, []localSpk{sp})
		}
	}

	type mergedGroup struct {
		members []string
		speechS float64
		emb     []float64
	}
	groups := make([]mergedGroup, len(clusters))
	for i, members := range clusters {
		dim := len(members[0].emb)
		sumVec := make([]float64, dim)
		var totalS float64
		names := make([]string, len(members))
		for j, m := range members {
			names[j] = m.speaker
			totalS += m.speechS
			for k := 0; k < dim; k++ {
				sumVec[k] += m.emb[k] * m.speechS
			}
		}
		groups[i] = mergedGroup{members: names, speechS: totalS, emb: l2Norm(sumVec)}
	}

	embModel := strings.TrimSpace(res.EmbeddingModel)
	if embModel == "" {
		embModel = fmt.Sprintf("hark-default-%d", len(groups[0].emb))
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	rows, err := s.db.Query(`SELECT id, centroid, weight, COALESCE(embedding_model, '') FROM speakers`)
	if err != nil {
		return nil, err
	}
	var known []knownSpeaker
	for rows.Next() {
		var id, rawC, rowModel string
		var w float64
		if err := rows.Scan(&id, &rawC, &w, &rowModel); err != nil {
			continue
		}
		var vec []float64
		if err := json.Unmarshal([]byte(rawC), &vec); err == nil && len(vec) > 0 {
			known = append(known, knownSpeaker{id: id, centroid: vec, weight: w, embeddingModel: rowModel})
		}
	}
	rows.Close()

	nextSpeakerID := func() (string, error) {
		var count int
		_ = s.db.QueryRow(`SELECT COUNT(*) FROM speakers`).Scan(&count)
		for {
			count++
			cand := fmt.Sprintf("spk_%03d", count)
			var exists int
			err := s.db.QueryRow(`SELECT 1 FROM speakers WHERE id = ?`, cand).Scan(&exists)
			if err == sql.ErrNoRows {
				return cand, nil
			}
		}
	}

	mapping := map[string]string{}
	otherIdx := 0
	for _, g := range groups {
		bestSim := -1.0
		bestID := ""
		for _, k := range known {
			// 只与相同声纹模型的历史原型比对；兼容早期未填 embedding_model 但同维度的旧记录
			modelCompatible := k.embeddingModel == embModel || (k.embeddingModel == "" && len(k.centroid) == len(g.emb))
			if !modelCompatible {
				continue
			}
			if sim := cosine(g.emb, k.centroid); sim > bestSim {
				bestSim = sim
				bestID = k.id
			}
		}

		var target string
		switch {
		case bestSim >= threshold:
			target = s.resolveSpeakerLocked(bestID)
		case g.speechS >= newSpeakerMinS:
			sid, err := nextSpeakerID()
			if err != nil {
				return nil, err
			}
			rawVec, _ := json.Marshal(g.emb)
			if _, err := s.db.Exec(
				`INSERT INTO speakers(id, centroid, weight, embedding_model) VALUES (?, ?, ?, ?)`,
				sid, string(rawVec), g.speechS, embModel,
			); err != nil {
				return nil, err
			}
			known = append(known, knownSpeaker{id: sid, centroid: g.emb, weight: g.speechS, embeddingModel: embModel})
			target = sid
		default:
			otherIdx++
			target = fmt.Sprintf("other_%d", otherIdx)
		}
		for _, m := range g.members {
			mapping[m] = target
		}
	}
	return mapping, nil
}

func (s *Store) UpdateSpeakerMeta(sid, firstSeen, sample, sampleName string, sampleStart float64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	runes := []rune(sample)
	if len(runes) > 80 {
		sample = string(runes[:80])
	}
	_, err := s.db.Exec(`
		UPDATE speakers SET
			first_seen = COALESCE(NULLIF(first_seen, ''), ?),
			sample = COALESCE(NULLIF(sample, ''), ?),
			sample_name = COALESCE(NULLIF(sample_name, ''), ?),
			sample_start = COALESCE(sample_start, ?)
		WHERE id = ?`,
		firstSeen, sample, sampleName, sampleStart, sid,
	)
	return err
}
