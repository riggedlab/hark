// Hark exposes the local recording archive to humans and local AI agents.
package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/riggedlab/hark/internal/worker"
)

const usage = `Hark 本地语音存档（查询命令只读）
  hark status [--data-dir DIR] [--config PATH] [--json]
  hark timeline [--date today|yesterday|7d|30d|all|YYYY-MM-DD] [--speaker WHO] [--limit 200] [--offset 0]
  hark search <query> [--date 30d] [--speaker WHO] [--limit 30] [--offset 0]
  hark get <name...>
  hark speakers
  hark worker [--config PATH] [--data-dir DIR] [--once] [--gen-key]
  hark gen-key
查询均支持 --data-dir、--config、--json；flag 可放在位置参数前后。
`

type timelineOutput struct {
	DBExists bool                      `json:"db_exists"`
	Items    []worker.TranscriptDetail `json:"items"`
	Total    int                       `json:"total"`
	Limit    int                       `json:"limit"`
	Offset   int                       `json:"offset"`
}

type searchOutput struct {
	DBExists bool `json:"db_exists"`
	worker.SearchPage
	Limit  int `json:"limit"`
	Offset int `json:"offset"`
}

type speakersOutput struct {
	DBExists bool                 `json:"db_exists"`
	Items    []worker.SpeakerInfo `json:"items"`
}

type getOutput struct {
	DBExists bool                      `json:"db_exists"`
	Items    []worker.TranscriptDetail `json:"items"`
}

func main() { os.Exit(run(os.Args[1:], os.Stdout, os.Stderr)) }

func run(args []string, stdout, stderr io.Writer) int {
	if len(args) == 0 || args[0] == "help" || args[0] == "-h" || args[0] == "--help" {
		fmt.Fprint(stdout, usage)
		return 0
	}
	command, args := args[0], args[1:]
	if command == "worker" {
		ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
		defer stop()
		return worker.RunCLI(ctx, args, stdout, stderr)
	}
	if command == "gen-key" {
		if len(args) != 0 {
			fmt.Fprintln(stderr, "用法: hark gen-key")
			return 2
		}
		return worker.RunCLI(context.Background(), []string{"-gen-key"}, stdout, stderr)
	}
	if command != "status" && command != "timeline" && command != "search" && command != "get" && command != "speakers" {
		fmt.Fprintf(stderr, "未知命令: %s\n%s", command, usage)
		return 2
	}
	fs := flag.NewFlagSet("hark "+command, flag.ContinueOnError)
	fs.SetOutput(stderr)
	dataDir := fs.String("data-dir", "", "本地存档目录（优先于环境变量和配置）")
	conf := fs.String("config", "", "配置文件路径（只读取 data_dir）")
	asJSON := fs.Bool("json", false, "输出标准 JSON")
	f := worker.ArchiveFilter{}
	if command == "timeline" || command == "search" {
		date, limit := "today", 200
		if command == "search" {
			date, limit = "30d", 30
		}
		fs.StringVar(&f.DateRange, "date", date, "today|yesterday|7d|30d|all|YYYY-MM-DD")
		fs.StringVar(&f.SpeakerID, "speaker", "", "人物 ID 或显示名称（支持包含匹配）")
		fs.IntVar(&f.Limit, "limit", limit, "最多返回的录音条数（正整数）")
		fs.IntVar(&f.Offset, "offset", 0, "从最新录音起跳过的条数")
	}
	pos, err := parseInterspersed(fs, args)
	if err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return 0
		}
		return 2
	}
	if (command == "get" && len(pos) == 0) || (command == "search" && len(pos) != 1) ||
		(command != "get" && command != "search" && len(pos) != 0) {
		fmt.Fprintf(stderr, "命令参数不正确，运行 hark %s --help 查看用法\n", command)
		return 2
	}
	if command == "timeline" || command == "search" {
		if err := validateFilter(f); err != nil {
			fmt.Fprintln(stderr, err)
			return 2
		}
	}
	if command == "search" {
		f.Query = pos[0]
	}
	dir := worker.ResolveDataDir(*dataDir, *conf)
	store, exists, err := worker.OpenReadOnlyStore(dir)
	if err != nil {
		fmt.Fprintf(stderr, "打开本地存档失败: %v\n", err)
		return 1
	}
	if exists {
		defer store.Close()
	}
	var payload any
	var text strings.Builder
	switch command {
	case "status":
		absDir, _ := filepath.Abs(dir)
		st := worker.ReadOnlyStats{DBPath: filepath.Join(absDir, "hark.db")}
		if exists {
			st, err = store.ReadOnlyStats()
		}
		payload = st
		if exists && err == nil {
			fmt.Fprintf(&text, "本地存档: %s（%d 字节）\n录音 %d 条 · 时长 %.1fs · 语音 %.1fs · 人物 %d\n",
				st.DBPath, st.DBSizeBytes, st.TotalRecordings, st.TotalDurationS, st.TotalSpeechS, st.SpeakerCount)
			if st.LatestName != "" {
				fmt.Fprintf(&text, "最新: %s · %s\n", st.LatestName, st.LatestStartedAt)
			}
		}
	case "timeline":
		out := timelineOutput{DBExists: exists, Items: []worker.TranscriptDetail{}, Limit: f.Limit, Offset: f.Offset}
		if exists {
			out.Items, out.Total, err = store.Timeline(f)
		}
		payload = out
		for _, d := range out.Items {
			writeTranscript(&text, d, d.Segments)
		}
		writePageSummary(&text, out.Total, len(out.Items), f.Offset)
	case "search":
		out := searchOutput{DBExists: exists, SearchPage: worker.SearchPage{Items: []worker.SearchItem{}}, Limit: f.Limit, Offset: f.Offset}
		if exists {
			out.SearchPage, err = store.Search(f)
		}
		payload = out
		for _, it := range out.Items {
			writeTranscript(&text, worker.TranscriptDetail{Name: it.Name, StartedAt: it.StartedAt,
				DurationS: it.DurationS, SpeechS: it.SpeechS, Text: it.Preview}, it.MatchedSegments)
		}
		writePageSummary(&text, out.Total, len(out.Items), f.Offset)
	case "get":
		if !exists {
			err = errors.New("本地暂无录音存档，无法读取指定录音")
			break
		}
		out := getOutput{DBExists: true, Items: []worker.TranscriptDetail{}}
		for _, name := range pos {
			if !strings.HasSuffix(name, ".opus") {
				name += ".opus"
			}
			var d worker.TranscriptDetail
			d, err = store.GetDetail(name)
			if errors.Is(err, sql.ErrNoRows) {
				err = fmt.Errorf("录音不存在: %s", name)
			}
			if err != nil {
				break
			}
			out.Items = append(out.Items, d)
		}
		payload = out
		for _, d := range out.Items {
			writeTranscript(&text, d, d.Segments)
		}
	case "speakers":
		out := speakersOutput{DBExists: exists, Items: []worker.SpeakerInfo{}}
		if exists {
			out.Items, err = store.ListSpeakers()
		}
		payload = out
		for _, sp := range out.Items {
			fmt.Fprintf(&text, "- %s · %s · %.1fs", sp.ID, sp.Label, sp.WeightS)
			if len(sp.MergedIDs) > 0 {
				fmt.Fprintf(&text, " · 合并自 %s", strings.Join(sp.MergedIDs, ", "))
			}
			if sp.FirstSeen != "" {
				fmt.Fprintf(&text, " · 首次 %s", sp.FirstSeen)
			}
			fmt.Fprintln(&text)
			if sp.Sample != "" {
				fmt.Fprintf(&text, "  样本: %s（%s %s）\n", sp.Sample, sp.SampleName, segmentTime(sp.SampleStart))
			}
		}
		if len(out.Items) == 0 && exists {
			fmt.Fprintln(&text, "暂无已建档人物")
		}
	}
	if err != nil {
		fmt.Fprintf(stderr, "查询失败: %v\n", err)
		return 1
	}
	if *asJSON {
		enc := json.NewEncoder(stdout)
		enc.SetIndent("", "  ")
		err = enc.Encode(payload)
	} else {
		if !exists {
			text.Reset()
			fmt.Fprintln(&text, "本地暂无录音存档。请先通过桌面端或 hark worker 同步录音，或用 --data-dir 指定已有存档。")
		}
		_, err = io.WriteString(stdout, text.String())
	}
	if err != nil {
		fmt.Fprintf(stderr, "输出失败: %v\n", err)
		return 1
	}
	return 0
}

// flag.FlagSet stops at the first positional argument. Separate flags and their
// values first, preserving -- as the explicit positional-only boundary.
func parseInterspersed(fs *flag.FlagSet, args []string) ([]string, error) {
	flags, pos := []string{}, []string{}
	for i := 0; i < len(args); i++ {
		arg := args[i]
		if arg == "--" {
			pos = append(pos, args[i+1:]...)
			break
		}
		if arg == "-" || !strings.HasPrefix(arg, "-") {
			pos = append(pos, arg)
			continue
		}
		flags = append(flags, arg)
		name, _, inline := strings.Cut(strings.TrimLeft(arg, "-"), "=")
		if fl := fs.Lookup(name); fl != nil && !inline {
			bf, isBool := fl.Value.(interface{ IsBoolFlag() bool })
			if (!isBool || !bf.IsBoolFlag()) && i+1 < len(args) {
				i++
				flags = append(flags, args[i])
			}
		}
	}
	return pos, fs.Parse(flags)
}

func validateFilter(f worker.ArchiveFilter) error {
	switch f.DateRange {
	case "today", "yesterday", "7d", "30d", "all":
	default:
		if _, err := time.Parse("2006-01-02", f.DateRange); err != nil {
			return fmt.Errorf("无效日期: %s（使用 today|yesterday|7d|30d|all|YYYY-MM-DD）", f.DateRange)
		}
	}
	if f.Limit <= 0 || f.Offset < 0 {
		return errors.New("--limit 必须为正整数，--offset 不能为负数")
	}
	return nil
}

func writeTranscript(w io.Writer, d worker.TranscriptDetail, segments []worker.ResolvedSegment) {
	start, end := d.StartedAt, ""
	for _, layout := range []string{time.RFC3339Nano, "2006-01-02T15:04:05"} {
		if t, err := time.ParseInLocation(layout, d.StartedAt, time.Local); err == nil {
			start = t.Format("2006-01-02 15:04:05")
			end = t.Add(time.Duration(d.DurationS * float64(time.Second))).Format("2006-01-02 15:04:05")
			break
		}
	}
	fmt.Fprintf(w, "## %s | %s", d.Name, start)
	if end != "" {
		fmt.Fprintf(w, " → %s", end)
	}
	fmt.Fprintf(w, " | %.1fs（语音 %.1fs）\n", d.DurationS, d.SpeechS)
	for _, seg := range segments {
		fmt.Fprintf(w, "- [%s–%s] ", segmentTime(seg.Start), segmentTime(seg.End))
		if seg.SpeakerLabel != "" {
			fmt.Fprintf(w, "%s (%s): ", seg.SpeakerLabel, seg.SpeakerID)
		}
		fmt.Fprintln(w, seg.Text)
	}
	if len(segments) == 0 && d.Text != "" {
		fmt.Fprintln(w, d.Text)
	}
	fmt.Fprintln(w)
}

func segmentTime(seconds float64) string {
	s := int(seconds)
	return fmt.Sprintf("%02d:%02d", s/60, s%60)
}

func writePageSummary(w io.Writer, total, count, offset int) {
	if total == 0 {
		fmt.Fprintln(w, "当前筛选条件下无记录")
	} else if total > count {
		fmt.Fprintf(w, "共 %d 条，当前展示最近 %d 条（跳过 %d 条；--offset %d 查看下一页）\n", total, count, offset, offset+count)
	}
}
