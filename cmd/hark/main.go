// Hark exposes the local recording archive to humans and local AI agents.
package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/riggedlab/hark/internal/worker"
	"github.com/spf13/cobra"
)

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

type exitError struct {
	code int
	err  error
}

func (e exitError) Error() string { return e.err.Error() }
func (e exitError) Unwrap() error { return e.err }

type queryFlags struct {
	dataDir string
	config  string
	asJSON  bool
}

var legacyLongFlags = map[string]bool{
	"data-dir": true,
	"config":   true,
	"json":     true,
	"date":     true,
	"speaker":  true,
	"limit":    true,
	"offset":   true,
	"once":     true,
	"gen-key":  true,
	"help":     true,
}

var legacyValueFlags = map[string]bool{
	"data-dir": true,
	"config":   true,
	"date":     true,
	"speaker":  true,
	"limit":    true,
	"offset":   true,
}

// normalizeLegacyFlags rewrites single-dash known long flags (e.g. -json, -gen-key)
// to --flag before "--", keeping compatibility with standard Go flag callers.
func normalizeLegacyFlags(args []string) []string {
	out := make([]string, 0, len(args))
	for i := 0; i < len(args); i++ {
		arg := args[i]
		if arg == "--" {
			out = append(out, args[i:]...)
			break
		}
		if strings.HasPrefix(arg, "--") {
			out = append(out, arg)
			name, _, hasEq := strings.Cut(arg[2:], "=")
			if legacyValueFlags[name] && !hasEq && i+1 < len(args) {
				i++
				out = append(out, args[i])
			}
			continue
		}
		if strings.HasPrefix(arg, "-") && len(arg) > 2 {
			name, rest, hasEq := strings.Cut(arg[1:], "=")
			if legacyLongFlags[name] {
				if hasEq {
					out = append(out, "--"+name+"="+rest)
				} else {
					out = append(out, "--"+name)
					if legacyValueFlags[name] && i+1 < len(args) {
						i++
						out = append(out, args[i])
					}
				}
				continue
			}
		}
		out = append(out, arg)
	}
	return out
}

func addQueryFlags(cmd *cobra.Command, q *queryFlags) {
	f := cmd.Flags()
	f.StringVar(&q.dataDir, "data-dir", "", "本地存档目录（优先于环境变量和配置）")
	f.StringVar(&q.config, "config", "", "配置文件路径（只读取 data_dir）")
	f.BoolVar(&q.asJSON, "json", false, "输出标准 JSON")
}

func addFilterFlags(cmd *cobra.Command, f *worker.ArchiveFilter, defaultDate string, defaultLimit int) {
	fl := cmd.Flags()
	fl.StringVar(&f.DateRange, "date", defaultDate, "时间范围：today|yesterday|7d|30d|all|YYYY-MM-DD")
	fl.StringVar(&f.SpeakerID, "speaker", "", "人物 ID 或显示名称（支持包含匹配）")
	fl.IntVar(&f.Limit, "limit", defaultLimit, "最多返回的录音条数（正整数）")
	fl.IntVar(&f.Offset, "offset", 0, "从最新录音起跳过的条数")
}

func main() { os.Exit(run(os.Args[1:], os.Stdout, os.Stderr)) }

func run(args []string, stdout, stderr io.Writer) int {
	var workerExit int
	hasWorkerExit := false

	root := &cobra.Command{
		Use:           "hark",
		Short:         "Hark 本地语音存档查询与后台同步工具",
		Long:          "Hark 本地语音存档 CLI：查询子命令（status / timeline / search / get / speakers）均以纯只读方式打开本地 SQLite，不联网、不访问钥匙串。",
		SilenceErrors: true,
		SilenceUsage:  true,
		Args:          cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			return cmd.Help()
		},
	}
	root.CompletionOptions.DisableDefaultCmd = true
	root.SetOut(stdout)
	root.SetErr(stderr)
	root.SetArgs(normalizeLegacyFlags(args))

	root.AddCommand(
		newStatusCmd(stdout),
		newTimelineCmd(stdout),
		newSearchCmd(stdout),
		newGetCmd(stdout),
		newSpeakersCmd(stdout),
		newWorkerCmd(stdout, stderr, &workerExit, &hasWorkerExit),
		newGenKeyCmd(stdout, stderr, &workerExit, &hasWorkerExit),
		newCompletionCmd(root, stdout),
	)

	if err := root.Execute(); err != nil {
		var ee exitError
		if errors.As(err, &ee) {
			fmt.Fprintln(stderr, ee.err)
			return ee.code
		}
		fmt.Fprintln(stderr, err)
		return 2
	}
	if hasWorkerExit {
		return workerExit
	}
	return 0
}

func newStatusCmd(stdout io.Writer) *cobra.Command {
	var q queryFlags
	cmd := &cobra.Command{
		Use:   "status",
		Short: "查看本地语音存档状态与统计概览",
		Args:  cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			dir := worker.ResolveDataDir(q.dataDir, q.config)
			store, exists, err := openReadOnly(dir)
			if err != nil {
				return err
			}
			if exists {
				defer store.Close()
			}
			absDir, _ := filepath.Abs(dir)
			st := worker.ReadOnlyStats{DBPath: filepath.Join(absDir, "hark.db")}
			if exists {
				st, err = store.ReadOnlyStats()
				if err != nil {
					return queryErr(err)
				}
			}
			var text strings.Builder
			if exists {
				fmt.Fprintf(&text, "本地存档: %s（%d 字节）\n录音 %d 条 · 时长 %.1fs · 语音 %.1fs · 人物 %d\n",
					st.DBPath, st.DBSizeBytes, st.TotalRecordings, st.TotalDurationS, st.TotalSpeechS, st.SpeakerCount)
				if st.LatestName != "" {
					fmt.Fprintf(&text, "最新: %s · %s\n", st.LatestName, st.LatestStartedAt)
				}
			}
			return emitOutput(stdout, q.asJSON, exists, st, &text)
		},
	}
	addQueryFlags(cmd, &q)
	return cmd
}

func newTimelineCmd(stdout io.Writer) *cobra.Command {
	var q queryFlags
	var f worker.ArchiveFilter
	cmd := &cobra.Command{
		Use:   "timeline",
		Short: "按时间正序浏览指定日期范围的完整对话",
		Args:  cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			if err := validateFilter(f); err != nil {
				return err
			}
			store, exists, err := openReadOnly(worker.ResolveDataDir(q.dataDir, q.config))
			if err != nil {
				return err
			}
			if exists {
				defer store.Close()
			}
			out := timelineOutput{DBExists: exists, Items: []worker.TranscriptDetail{}, Limit: f.Limit, Offset: f.Offset}
			if exists {
				out.Items, out.Total, err = store.Timeline(f)
				if err != nil {
					return queryErr(err)
				}
			}
			var text strings.Builder
			for _, d := range out.Items {
				writeTranscript(&text, d, d.Segments)
			}
			writePageSummary(&text, out.Total, len(out.Items), f.Offset)
			return emitOutput(stdout, q.asJSON, exists, out, &text)
		},
	}
	addQueryFlags(cmd, &q)
	addFilterFlags(cmd, &f, "today", 200)
	return cmd
}

func newSearchCmd(stdout io.Writer) *cobra.Command {
	var q queryFlags
	var f worker.ArchiveFilter
	cmd := &cobra.Command{
		Use:   "search <query>",
		Short: "在本地语音存档中检索关键词或人物对话",
		Args:  cobra.ExactArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			if err := validateFilter(f); err != nil {
				return err
			}
			f.Query = args[0]
			store, exists, err := openReadOnly(worker.ResolveDataDir(q.dataDir, q.config))
			if err != nil {
				return err
			}
			if exists {
				defer store.Close()
			}
			out := searchOutput{DBExists: exists, SearchPage: worker.SearchPage{Items: []worker.SearchItem{}}, Limit: f.Limit, Offset: f.Offset}
			if exists {
				out.SearchPage, err = store.Search(f)
				if err != nil {
					return queryErr(err)
				}
			}
			var text strings.Builder
			for _, it := range out.Items {
				writeTranscript(&text, worker.TranscriptDetail{
					Name: it.Name, StartedAt: it.StartedAt,
					DurationS: it.DurationS, SpeechS: it.SpeechS, Text: it.Preview,
				}, it.MatchedSegments)
			}
			writePageSummary(&text, out.Total, len(out.Items), f.Offset)
			return emitOutput(stdout, q.asJSON, exists, out, &text)
		},
	}
	addQueryFlags(cmd, &q)
	addFilterFlags(cmd, &f, "30d", 30)
	return cmd
}

func newGetCmd(stdout io.Writer) *cobra.Command {
	var q queryFlags
	cmd := &cobra.Command{
		Use:   "get <name...>",
		Short: "读取一段或多段录音的完整转写与说话人分段",
		Args:  cobra.MinimumNArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			store, exists, err := openReadOnly(worker.ResolveDataDir(q.dataDir, q.config))
			if err != nil {
				return err
			}
			if !exists {
				return queryErr(errors.New("本地暂无录音存档，无法读取指定录音"))
			}
			defer store.Close()

			out := getOutput{DBExists: true, Items: []worker.TranscriptDetail{}}
			for _, name := range args {
				if !strings.HasSuffix(name, ".opus") {
					name += ".opus"
				}
				d, err := store.GetDetail(name)
				if errors.Is(err, sql.ErrNoRows) {
					return queryErr(fmt.Errorf("录音不存在: %s", name))
				}
				if err != nil {
					return queryErr(err)
				}
				out.Items = append(out.Items, d)
			}
			var text strings.Builder
			for _, d := range out.Items {
				writeTranscript(&text, d, d.Segments)
			}
			return emitOutput(stdout, q.asJSON, true, out, &text)
		},
	}
	addQueryFlags(cmd, &q)
	return cmd
}

func newSpeakersCmd(stdout io.Writer) *cobra.Command {
	var q queryFlags
	cmd := &cobra.Command{
		Use:   "speakers",
		Short: "列出本地已建档人物及合并关系",
		Args:  cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			store, exists, err := openReadOnly(worker.ResolveDataDir(q.dataDir, q.config))
			if err != nil {
				return err
			}
			if exists {
				defer store.Close()
			}
			out := speakersOutput{DBExists: exists, Items: []worker.SpeakerInfo{}}
			if exists {
				out.Items, err = store.ListSpeakers()
				if err != nil {
					return queryErr(err)
				}
			}
			var text strings.Builder
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
			return emitOutput(stdout, q.asJSON, exists, out, &text)
		},
	}
	addQueryFlags(cmd, &q)
	return cmd
}

func newWorkerCmd(stdout, stderr io.Writer, codeOut *int, hasExit *bool) *cobra.Command {
	var confPath, dataDir string
	var once, genKey bool
	cmd := &cobra.Command{
		Use:   "worker",
		Short: "启动无界面后台拉取与转写引擎（兼容 hark-worker 全部参数）",
		Args:  cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			var workerArgs []string
			if confPath != "" {
				workerArgs = append(workerArgs, "-config", confPath)
			}
			if dataDir != "" {
				workerArgs = append(workerArgs, "-data-dir", dataDir)
			}
			if once {
				workerArgs = append(workerArgs, "-once")
			}
			if genKey {
				workerArgs = append(workerArgs, "-gen-key")
			}
			ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
			defer stop()
			*hasExit = true
			*codeOut = worker.RunCLI(ctx, workerArgs, stdout, stderr)
			return nil
		},
	}
	f := cmd.Flags()
	f.StringVar(&confPath, "config", "", "配置文件路径（默认 ~/.config/hark/config.json 或兼容 worker.env）")
	f.StringVar(&dataDir, "data-dir", "", "本地 SQLite 存档目录（默认 ~/hark）")
	f.BoolVar(&once, "once", false, "处理完当前信箱队列后立即退出")
	f.BoolVar(&genKey, "gen-key", false, "生成一把新的 32 字节端到端加密密钥并退出")
	return cmd
}

func newGenKeyCmd(stdout, stderr io.Writer, codeOut *int, hasExit *bool) *cobra.Command {
	return &cobra.Command{
		Use:   "gen-key",
		Short: "生成一把新的 32 字节端到端加密共享密钥",
		Args:  cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			*hasExit = true
			*codeOut = worker.RunCLI(context.Background(), []string{"-gen-key"}, stdout, stderr)
			return nil
		},
	}
}

func newCompletionCmd(root *cobra.Command, stdout io.Writer) *cobra.Command {
	var noDesc bool
	cmd := &cobra.Command{
		Use:       "completion <bash|zsh|fish|powershell>",
		Short:     "生成指定终端的命令自动补全脚本",
		ValidArgs: []string{"bash", "zsh", "fish", "powershell"},
		Args:      cobra.ExactArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			var err error
			switch args[0] {
			case "bash":
				err = root.GenBashCompletionV2(stdout, !noDesc)
			case "zsh":
				if noDesc {
					err = root.GenZshCompletionNoDesc(stdout)
				} else {
					err = root.GenZshCompletion(stdout)
				}
			case "fish":
				err = root.GenFishCompletion(stdout, !noDesc)
			case "powershell":
				if noDesc {
					err = root.GenPowerShellCompletion(stdout)
				} else {
					err = root.GenPowerShellCompletionWithDesc(stdout)
				}
			default:
				return fmt.Errorf("不支持的 shell: %s（可选 bash|zsh|fish|powershell）", args[0])
			}
			if err != nil {
				return exitError{code: 1, err: err}
			}
			return nil
		},
	}
	cmd.Flags().BoolVar(&noDesc, "no-descriptions", false, "禁用补全项描述")
	return cmd
}

func openReadOnly(dir string) (*worker.Store, bool, error) {
	store, exists, err := worker.OpenReadOnlyStore(dir)
	if err != nil {
		return nil, false, exitError{code: 1, err: fmt.Errorf("打开本地存档失败: %w", err)}
	}
	return store, exists, nil
}

func queryErr(err error) error {
	return exitError{code: 1, err: fmt.Errorf("查询失败: %w", err)}
}

func emitOutput(stdout io.Writer, asJSON, exists bool, payload any, text *strings.Builder) error {
	var err error
	if asJSON {
		enc := json.NewEncoder(stdout)
		enc.SetIndent("", "  ")
		err = enc.Encode(payload)
	} else {
		if !exists {
			text.Reset()
			fmt.Fprintln(text, "本地暂无录音存档。请先通过桌面端或 hark worker 同步录音，或用 --data-dir 指定已有存档。")
		}
		_, err = io.WriteString(stdout, text.String())
	}
	if err != nil {
		return exitError{code: 1, err: fmt.Errorf("输出失败: %w", err)}
	}
	return nil
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
		return
	}
	if count == 0 {
		fmt.Fprintf(w, "共 %d 条，当前偏移 --offset %d 已无更多记录\n", total, offset)
		return
	}
	if offset+count < total {
		fmt.Fprintf(w, "共 %d 条，当前展示第 %d–%d 条（--offset %d 查看下一页）\n", total, offset+1, offset+count, offset+count)
	} else if offset > 0 {
		fmt.Fprintf(w, "共 %d 条，当前展示第 %d–%d 条（已到末页）\n", total, offset+1, offset+count)
	}
}
