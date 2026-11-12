// Hark 桌面管理端（Go + MyGo）：菜单栏常驻拉取、本地语音上下文存档检索导出与 ASR 接入测活。
package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"sync"
	"time"

	"github.com/egoist/mygo"
	"github.com/riggedlab/hark/internal/asr"
	"github.com/riggedlab/hark/internal/e2ee"
	"github.com/riggedlab/hark/internal/worker"
)

type mygoSecretStore struct{}

func (mygoSecretStore) Get(key string) (string, error) {
	b, err := mygo.Secrets.Get(key)
	if errors.Is(err, mygo.ErrSecretNotFound) {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	return string(b), nil
}

func (mygoSecretStore) Set(key, value string) error {
	return mygo.Secrets.Set(key, []byte(value))
}

func (mygoSecretStore) Delete(key string) error {
	return mygo.Secrets.Delete(key)
}

type LiveSnapshot struct {
	Status worker.RuntimeStatus `json:"status"`
	Stats  worker.ArchiveStats  `json:"stats"`
}

type BootstrapPayload struct {
	Config         worker.Config        `json:"config"`
	Status         worker.RuntimeStatus `json:"status"`
	Stats          worker.ArchiveStats  `json:"stats"`
	Presets        []asr.Preset         `json:"presets"`
	KeychainActive bool                 `json:"keychain_active"`
}

type MailboxTestResult struct {
	OK        bool   `json:"ok"`
	LatencyMs int64  `json:"latency_ms"`
	Error     string `json:"error,omitempty"`
}

type CopyResult struct {
	Chars int `json:"chars"`
	Count int `json:"count"`
}

// StateChanged 后台转写状态或存档条数变化时推送给前端。
var StateChanged = mygo.NewEvent[LiveSnapshot]("stateChanged")

// HarkService 暴露给前端 TypeScript 调用的绑定服务。
type HarkService struct {
	mu      sync.Mutex
	cfgPath string
	cfg     worker.Config
	secrets worker.SecretStore
	store   *worker.Store
	engine  *worker.Engine

	win       *mygo.Window
	trayPhase *mygo.MenuItem
	trayPause *mygo.MenuItem
}

func (h *HarkService) emitUpdate() {
	h.mu.Lock()
	win := h.win
	eng := h.engine
	store := h.store
	phaseItem := h.trayPhase
	pauseItem := h.trayPause
	h.mu.Unlock()

	if eng == nil {
		return
	}
	st := eng.Status()
	var stats worker.ArchiveStats
	if store != nil {
		stats, _ = store.Stats()
	}

	if phaseItem != nil {
		phaseItem.SetLabel(formatTrayStatusLabel(st))
	}
	if pauseItem != nil {
		if st.Paused {
			pauseItem.SetLabel("恢复后台拉取")
		} else {
			pauseItem.SetLabel("暂停后台拉取")
		}
	}
	if win != nil {
		_ = StateChanged.Emit(win, LiveSnapshot{Status: st, Stats: stats})
	}
}

func formatTrayStatusLabel(st worker.RuntimeStatus) string {
	switch st.Phase {
	case "unconfigured":
		return "状态：待配置信箱或 ASR"
	case "paused":
		return "状态：已暂停拉取"
	case "transcribing":
		return "状态：正在转写 " + st.Current
	case "asr_down":
		return "状态：ASR 服务未就绪"
	case "error":
		return "状态：连接异常"
	default:
		return "状态：监听信箱中"
	}
}

func (h *HarkService) Bootstrap() BootstrapPayload {
	h.mu.Lock()
	cfg := h.cfg
	keychain := h.secrets != nil
	eng := h.engine
	store := h.store
	h.mu.Unlock()

	var stats worker.ArchiveStats
	if store != nil {
		stats, _ = store.Stats()
	}
	return BootstrapPayload{
		Config:         cfg,
		Status:         eng.Status(),
		Stats:          stats,
		Presets:        asr.Presets(),
		KeychainActive: keychain,
	}
}

func (h *HarkService) requireStore() (*worker.Store, error) {
	h.mu.Lock()
	store := h.store
	h.mu.Unlock()
	if store == nil {
		return nil, errors.New("本地存档目录无法打开，请先在「信箱与存储」更换有效目录并保存")
	}
	return store, nil
}

func (h *HarkService) SaveSettings(next worker.Config) (BootstrapPayload, error) {
	h.mu.Lock()
	cfgPath := h.cfgPath
	sec := h.secrets
	prevDir := h.cfg.DataDir
	prevLogin := h.cfg.OpenAtLogin
	curStore := h.store
	h.mu.Unlock()

	targetDir := worker.NormalizeDataDir(next.DataDir)
	var openedNewStore *worker.Store
	if curStore == nil || targetDir != prevDir {
		ns, err := worker.OpenStore(targetDir)
		if err != nil {
			return BootstrapPayload{}, fmt.Errorf("打开存档目录失败: %w", err)
		}
		openedNewStore = ns
	}

	if err := worker.SaveConfig(cfgPath, next, sec); err != nil {
		if openedNewStore != nil {
			_ = openedNewStore.Close()
		}
		return BootstrapPayload{}, err
	}
	reloaded, err := worker.LoadConfig(cfgPath, sec)
	if err != nil {
		if openedNewStore != nil {
			_ = openedNewStore.Close()
		}
		return BootstrapPayload{}, err
	}
	if reloaded.OpenAtLogin != prevLogin && mygo.App.IsPackaged() {
		_ = mygo.App.SetOpenAtLogin(reloaded.OpenAtLogin)
	}

	if openedNewStore != nil {
		h.mu.Lock()
		oldEngine := h.engine
		oldStore := h.store
		h.mu.Unlock()

		oldEngine.Stop()
		if oldStore != nil {
			_ = oldStore.Close()
		}
		newEng := worker.NewEngine(reloaded, openedNewStore, h.emitUpdate)
		h.mu.Lock()
		h.store = openedNewStore
		h.engine = newEng
		h.cfg = reloaded
		h.mu.Unlock()

		newEng.Start()
		h.emitUpdate()
		return h.Bootstrap(), nil
	}

	h.mu.Lock()
	h.cfg = reloaded
	eng := h.engine
	h.mu.Unlock()

	eng.UpdateConfig(reloaded)
	h.emitUpdate()
	return h.Bootstrap(), nil
}

func (h *HarkService) GenerateE2EEKey() (string, error) {
	return e2ee.GenerateKey()
}

func (h *HarkService) TestMailbox(ctx context.Context, candidate worker.Config) MailboxTestResult {
	t0 := time.Now()
	err := worker.CheckMailbox(ctx, candidate)
	lat := time.Since(t0).Milliseconds()
	if err != nil {
		return MailboxTestResult{OK: false, LatencyMs: lat, Error: err.Error()}
	}
	return MailboxTestResult{OK: true, LatencyMs: lat}
}

func (h *HarkService) ProbeASR(ctx context.Context, candidate asr.Config) asr.ProbeReport {
	return asr.Probe(ctx, candidate)
}

func (h *HarkService) TogglePause(paused bool) worker.RuntimeStatus {
	h.mu.Lock()
	eng := h.engine
	h.mu.Unlock()
	eng.SetPaused(paused)
	h.emitUpdate()
	return eng.Status()
}

func (h *HarkService) QueryArchive(filter worker.ArchiveFilter) (worker.ArchivePage, error) {
	store, err := h.requireStore()
	if err != nil {
		return worker.ArchivePage{Items: []worker.ArchiveItem{}}, nil
	}
	return store.QueryArchive(filter)
}

func (h *HarkService) GetTranscript(name string) (worker.TranscriptDetail, error) {
	store, err := h.requireStore()
	if err != nil {
		return worker.TranscriptDetail{}, err
	}
	return store.GetDetail(name)
}

func (h *HarkService) DeleteTranscript(name string) error {
	store, err := h.requireStore()
	if err != nil {
		return err
	}
	if err := store.DeleteTranscript(name); err != nil {
		return err
	}
	h.emitUpdate()
	return nil
}

func (h *HarkService) CopyMarkdown(filter worker.ArchiveFilter) (CopyResult, error) {
	store, err := h.requireStore()
	if err != nil {
		return CopyResult{}, err
	}
	md, err := store.ExportMarkdown(filter)
	if err != nil {
		return CopyResult{}, err
	}
	page, err := store.QueryArchive(worker.ArchiveFilter{
		Query:     filter.Query,
		DateRange: filter.DateRange,
		SpeakerID: filter.SpeakerID,
		Limit:     1,
	})
	if err != nil {
		return CopyResult{}, err
	}
	mygo.Clipboard.WriteText(md)
	return CopyResult{Chars: len([]rune(md)), Count: page.Total}, nil
}

func (h *HarkService) CopySingleMarkdown(name string) (CopyResult, error) {
	store, err := h.requireStore()
	if err != nil {
		return CopyResult{}, err
	}
	d, err := store.GetDetail(name)
	if err != nil {
		return CopyResult{}, err
	}
	header := d.StartedAt
	if len(header) >= 16 {
		header = header[:10] + " " + header[11:16]
	}
	md := fmt.Sprintf("## %s（%.0f 秒）\n\n", header, d.DurationS)
	for _, seg := range d.Segments {
		mm, ss := int(seg.Start)/60, int(seg.Start)%60
		if seg.SpeakerLabel != "" {
			md += fmt.Sprintf("- `%02d:%02d` **%s**：%s\n", mm, ss, seg.SpeakerLabel, seg.Text)
		} else {
			md += fmt.Sprintf("- `%02d:%02d` %s\n", mm, ss, seg.Text)
		}
	}
	mygo.Clipboard.WriteText(md)
	return CopyResult{Chars: len([]rune(md)), Count: 1}, nil
}

func (h *HarkService) ExportArchive(ctx context.Context, filter worker.ArchiveFilter, format string) (string, error) {
	store, err := h.requireStore()
	if err != nil {
		return "", err
	}

	ext := "md"
	if format == "json" {
		ext = "json"
	}
	defaultName := fmt.Sprintf("hark-context-%s.%s", time.Now().Format("20060102-1504"), ext)
	dest, err := mygo.Dialog.Save(mygo.SaveDialogOptions{
		Parent:      mygo.CallerWindow(ctx),
		Title:       "导出语音上下文存档",
		DefaultPath: defaultName,
	})
	if err != nil || dest == "" {
		return "", err
	}

	var content string
	if ext == "json" {
		content, err = store.ExportJSON(filter)
	} else {
		content, err = store.ExportMarkdown(filter)
	}
	if err != nil {
		return "", err
	}
	if err := os.WriteFile(dest, []byte(content), 0o600); err != nil {
		return "", err
	}
	return dest, nil
}

func (h *HarkService) BackupDatabase(ctx context.Context) (string, error) {
	store, err := h.requireStore()
	if err != nil {
		return "", err
	}

	defaultName := fmt.Sprintf("hark-backup-%s.db", time.Now().Format("20060102"))
	dest, err := mygo.Dialog.Save(mygo.SaveDialogOptions{
		Parent:      mygo.CallerWindow(ctx),
		Title:       "备份本地 SQLite 数据库",
		DefaultPath: defaultName,
	})
	if err != nil || dest == "" {
		return "", err
	}
	if err := store.BackupTo(dest); err != nil {
		return "", err
	}
	return dest, nil
}

func (h *HarkService) RevealDatabase() {
	store, err := h.requireStore()
	if err != nil {
		return
	}
	mygo.Shell.ShowItemInFolder(store.DBPath())
}

func (h *HarkService) OpenExternalDocs(docURL string) error {
	return mygo.Shell.OpenExternal(docURL)
}

func main() {
	if !mygo.App.RequestSingleInstanceLock() {
		return
	}

	var secStore worker.SecretStore
	if mygo.App.IsPackaged() {
		// 正式打包签名后启用系统钥匙串；开发态用 0600 本地文件免去每次热重载弹窗
		secStore = mygoSecretStore{}
	}

	cfgPath := worker.DefaultConfigPath()
	cfg, err := worker.LoadConfig(cfgPath, secStore)
	if err != nil {
		log.Printf("读取配置失败，使用默认配置: %v", err)
		cfg = worker.DefaultConfig()
	}

	store, err := worker.OpenStore(cfg.DataDir)
	if err != nil {
		// 严禁静默回退临时目录（防消费信箱后临时目录被系统清理丢档）；保持 store=nil 阻断拉取并在界面报错
		log.Printf("本地数据库无法打开 (%s): %v", cfg.DataDir, err)
	}

	svc := &HarkService{
		cfgPath: cfgPath,
		cfg:     cfg,
		secrets: secStore,
		store:   store,
	}
	svc.engine = worker.NewEngine(cfg, store, svc.emitUpdate)
	if err != nil {
		svc.engine.Stop()
	}
	mygo.BindAs("Hark", svc)

	var quitting bool
	mygo.App.OnBeforeQuit(func(*mygo.QuitEvent) {
		quitting = true
	})

	mygo.App.OnWindowAllClosed(func() {
		svc.mu.Lock()
		closeToTray := svc.cfg.CloseToTray
		svc.mu.Unlock()
		if !closeToTray {
			mygo.App.Quit()
		}
	})

	createOrShowWindow := func() {
		svc.mu.Lock()
		existing := svc.win
		svc.mu.Unlock()
		if existing != nil && !existing.IsDestroyed() {
			existing.Show()
			existing.Focus()
			return
		}
		win := mygo.NewWindow(mygo.WindowOptions{
			Title:           "Hark · 谛听",
			Width:           1080,
			Height:          700,
			MinWidth:        820,
			MinHeight:       540,
			TitleBarStyle:   mygo.TitleBarHiddenInset,
			BackgroundColor: "light-dark(#f5f5f2, #141619)",
			StateKey:        "hark-main",
			URL:             "/",
		})
		win.OnClose(func(e *mygo.CloseEvent) {
			svc.mu.Lock()
			closeToTray := svc.cfg.CloseToTray
			svc.mu.Unlock()
			if !quitting && closeToTray {
				e.PreventDefault()
				win.Hide()
			}
		})
		win.OnClosed(func() {
			svc.mu.Lock()
			if svc.win == win {
				svc.win = nil
			}
			svc.mu.Unlock()
		})
		svc.mu.Lock()
		svc.win = win
		svc.mu.Unlock()
	}

	mygo.App.OnSecondInstance(func(_ []string, _ string) {
		createOrShowWindow()
	})
	mygo.App.OnActivate(func(_ bool) {
		createOrShowWindow()
	})

	mygo.App.WhenReady(func() {
		// 防止 macOS 隐藏窗口后触发 App Nap 冻结后台信箱长轮询
		releaseKeepRunning := mygo.Power.KeepRunning("Hark 后台监听加密信箱与语音转写")
		mygo.App.OnQuit(func() {
			releaseKeepRunning()
			svc.engine.Stop()
			svc.mu.Lock()
			st := svc.store
			svc.store = nil
			svc.mu.Unlock()
			if st != nil {
				_ = st.Close()
			}
		})

		createOrShowWindow()

		phaseItem := &mygo.MenuItem{Label: formatTrayStatusLabel(svc.engine.Status()), Disabled: true}
		pauseItem := &mygo.MenuItem{
			Label: "暂停后台拉取",
			Click: func(_ *mygo.MenuItem, _ *mygo.Window) {
				cur := svc.engine.Status().Paused
				svc.TogglePause(!cur)
			},
		}
		trayMenu := mygo.NewMenu([]*mygo.MenuItem{
			phaseItem,
			mygo.Separator(),
			{Label: "打开存档与控制台", Accelerator: "CmdOrCtrl+O", Click: func(*mygo.MenuItem, *mygo.Window) { createOrShowWindow() }},
			pauseItem,
			{Label: "打开本地存档目录", Click: func(*mygo.MenuItem, *mygo.Window) { svc.RevealDatabase() }},
			mygo.Separator(),
			{Label: "退出 Hark", Role: mygo.RoleQuit},
		})
		if tray, err := mygo.NewTray(mygo.TrayOptions{
			ID:             "hark-status",
			Icon:           buildTrayTemplatePNG(),
			IconIsTemplate: true,
			ToolTip:        "Hark · 谛听",
			Menu:           trayMenu,
		}); err != nil {
			log.Printf("创建系统托盘图标失败: %v", err)
		} else {
			_ = tray
		}

		svc.mu.Lock()
		svc.trayPhase = phaseItem
		svc.trayPause = pauseItem
		svc.mu.Unlock()

		if store != nil {
			svc.engine.Start()
		}
	})

	if err := mygo.App.Run(); err != nil {
		log.Fatal(err)
	}
}
