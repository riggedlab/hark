import {
  Hark,
  events,
  type ArchiveFilter,
  type ArchiveItem,
  type ArchiveStats,
  type Config,
  type MailboxTestResult,
  type Preset,
  type ProbeReport,
  type RuntimeStatus,
  type SpeakerBadge,
  type TranscriptDetail,
} from "./mygo";

type Tab = "archive" | "asr" | "system";

interface UIState {
  tab: Tab;
  appVersion: string;
  availableUpdate: string;
  config: Config | null;
  draft: Config | null;
  dirty: boolean;
  keychainActive: boolean;
  status: RuntimeStatus | null;
  stats: ArchiveStats | null;
  presets: Preset[];
  providerUrls: Record<string, string>;
  providerModels: Record<string, string>;
  providerKeys: Record<string, string>;
  draftRev: number;

  filter: ArchiveFilter;
  items: ArchiveItem[];
  total: number;
  speakers: SpeakerBadge[];
  selectedName: string | null;
  detail: TranscriptDetail | null;

  exportMenuOpen: boolean;
  confirmDelete: boolean;
  showSecrets: boolean;

  probingASR: boolean;
  probeReport: ProbeReport | null;
  testingMailbox: boolean;
  mailboxResult: MailboxTestResult | null;
  saving: boolean;

  toast: string | null;
}

const state: UIState = {
  tab: "archive",
  appVersion: "0.1.0",
  availableUpdate: "",
  config: null,
  draft: null,
  dirty: false,
  keychainActive: false,
  status: null,
  stats: null,
  presets: [],
  providerUrls: {
    volcengine: "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash",
    dashscope: "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
    openai: "https://api.siliconflow.cn/v1",
    hark_http: "http://127.0.0.1:8765",
  },
  providerModels: {
    volcengine: "volc.bigasr.auc_turbo",
    dashscope: "qwen-audio-3.1-asr-flash",
    openai: "FunAudioLLM/SenseVoiceSmall",
    hark_http: "",
  },
  providerKeys: {},
  draftRev: 0,

  filter: { query: "", date_range: "all", speaker_id: "", limit: 150, offset: 0 },
  items: [],
  total: 0,
  speakers: [],
  selectedName: null,
  detail: null,

  exportMenuOpen: false,
  confirmDelete: false,
  showSecrets: false,

  probingASR: false,
  probeReport: null,
  testingMailbox: false,
  mailboxResult: null,
  saving: false,

  toast: null,
};

let toastTimer: ReturnType<typeof setTimeout> | null = null;
let searchTimer: ReturnType<typeof setTimeout> | null = null;
let archiveReqSeq = 0;
let detailReqSeq = 0;

function showToast(msg: string) {
  state.toast = msg;
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    state.toast = null;
    render();
  }, 2600);
  render();
}

function cloneConfig(c: Config): Config {
  return JSON.parse(JSON.stringify(c)) as Config;
}

function escapeHTML(s: string): string {
  return s
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function fmtDuration(sec: number): string {
  const s = Math.round(sec || 0);
  if (s >= 3600) {
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    return m > 0 ? `${h}小时${m}分` : `${h}小时`;
  }
  if (s >= 60) {
    const m = Math.floor(s / 60);
    const r = s % 60;
    return r > 0 ? `${m}分${r}秒` : `${m}分`;
  }
  return `${s}秒`;
}

function fmtBytes(bytes: number): string {
  if (!bytes || bytes < 1024) return `${bytes || 0} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function fmtClock(iso: string): string {
  if (iso && iso.length >= 16) return iso.slice(11, 16);
  return iso;
}

function fmtDayGroup(iso: string): string {
  const day = iso.slice(0, 10);
  const today = new Date();
  const fmt = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  if (day === fmt(today)) return "今天";
  const yest = new Date(today);
  yest.setDate(today.getDate() - 1);
  if (day === fmt(yest)) return "昨天";
  return day || "更早";
}

function fmtMMSS(sec: number): string {
  const s = Math.max(0, Math.floor(sec || 0));
  return `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

function humanProvider(p: string): string {
  if (p === "volcengine") return "豆包大模型";
  if (p === "dashscope") return "阿里云百炼";
  if (p === "hark_http") return "本地声纹";
  if (p === "mock") return "内置模拟";
  return "云端 API";
}

function phaseMeta(st: RuntimeStatus | null): { dot: string; label: string; sub: string } {
  if (!st) return { dot: "", label: "正在读取…", sub: "" };
  const provName = humanProvider(st.asr_provider);
  switch (st.phase) {
    case "unconfigured":
      return { dot: "warn", label: "待完成配置", sub: st.config_hint || "请配置信箱与 ASR" };
    case "paused":
      return { dot: "", label: "已暂停拉取", sub: "点击继续恢复监听" };
    case "transcribing":
      return { dot: "busy", label: "正在转写", sub: st.current || "" };
    case "asr_down":
      return { dot: "warn", label: "转写未就绪", sub: `当前运行：${provName}` };
    case "error":
      return { dot: "err", label: "同步受阻", sub: st.last_error || "稍后自动重试" };
    default:
      return {
        dot: "ok",
        label: "监听信箱中",
        sub: st.asr_model ? `运行中：${st.asr_model}` : `运行中：${provName}`,
      };
  }
}

async function refreshArchive(preserveSelection = true) {
  const qSeq = ++archiveReqSeq;
  const reqFilter = { ...state.filter, offset: 0 };
  const page = await Hark.queryArchive(reqFilter);
  if (qSeq !== archiveReqSeq) return;
  state.filter.offset = 0;
  state.items = page.items || [];
  state.total = page.total;
  state.speakers = page.speakers || [];

  let targetName: string | null = null;
  if (preserveSelection && state.selectedName && state.items.some((i) => i.name === state.selectedName)) {
    targetName = state.selectedName;
  } else if (state.items.length > 0) {
    targetName = state.items[0].name;
  }

  state.selectedName = targetName;
  state.confirmDelete = false;
  if (!targetName) {
    detailReqSeq++;
    state.detail = null;
    render();
    return;
  }
  const reqId = ++detailReqSeq;
  const detail = await Hark.getTranscript(targetName);
  if (reqId !== detailReqSeq || state.selectedName !== targetName) return;
  state.detail = detail;
  render();
}

async function loadMoreArchive() {
  const qSeq = ++archiveReqSeq;
  const reqFilter = { ...state.filter, offset: state.items.length };
  const page = await Hark.queryArchive(reqFilter);
  if (qSeq !== archiveReqSeq) return;
  state.filter.offset = reqFilter.offset;
  const existing = new Set(state.items.map((x) => x.name));
  for (const it of page.items || []) {
    if (!existing.has(it.name)) state.items.push(it);
  }
  state.total = page.total;
  render();
}

async function selectRecording(name: string) {
  state.selectedName = name;
  state.confirmDelete = false;
  render();
  const reqId = ++detailReqSeq;
  const detail = await Hark.getTranscript(name);
  if (reqId !== detailReqSeq || state.selectedName !== name) return;
  state.detail = detail;
  render();
}

async function saveDraft(silent = false) {
  if (!state.draft || state.saving) return;
  const revAtStart = state.draftRev;
  const snapshot = cloneConfig(state.draft);
  state.saving = true;
  render();
  try {
    const boot = await Hark.saveSettings(snapshot);
    state.config = boot.config;
    if (boot.config.asr.base_url) {
      state.providerUrls[boot.config.asr.provider] = boot.config.asr.base_url;
    }
    state.providerModels[boot.config.asr.provider] = boot.config.asr.model || "";
    if (boot.config.asr.api_key) {
      state.providerKeys[boot.config.asr.provider] = boot.config.asr.api_key;
    }
    // 仅当保存期间用户没有继续修改表单时才用回包覆盖草稿
    if (state.draftRev === revAtStart) {
      state.draft = cloneConfig(boot.config);
      state.dirty = false;
    }
    state.status = boot.status;
    state.stats = boot.stats;
    if (!silent) showToast("配置已保存并生效");
    await refreshArchive(true);
  } catch (err) {
    showToast(`保存失败：${err instanceof Error ? err.message : String(err)}`);
  } finally {
    state.saving = false;
    render();
  }
}

function renderSidebar(): string {
  const pm = phaseMeta(state.status);
  const recCount = state.stats?.total_recordings ?? 0;
  const speechDur = fmtDuration(state.stats?.total_speech_s ?? 0);
  const activeProv =
    state.config?.asr.provider === "volcengine"
      ? "豆包"
      : state.config?.asr.provider === "dashscope"
        ? "百炼"
        : state.config?.asr.provider === "hark_http"
        ? "声纹"
        : state.config?.asr.provider === "mock"
          ? "模拟"
          : "API";

  return `
    <aside class="sidebar">
      <div class="sidebar-drag"></div>
      <div class="brand">
        <span class="brand-name">Hark · 谛听</span>
        <span class="brand-sub num">v${escapeHTML(state.appVersion)}</span>
      </div>

      <div class="status-box">
        <div class="status-row">
          <div class="status-indicator">
            <span class="dot ${pm.dot}"></span>
            <span>${escapeHTML(pm.label)}</span>
          </div>
          ${
            state.status?.configured
              ? `<button class="btn btn-ghost" data-action="toggle-pause">${state.status.paused ? "继续" : "暂停"}</button>`
              : ""
          }
        </div>
        <div class="status-sub" title="${escapeHTML(pm.sub)}">${escapeHTML(pm.sub)}</div>
      </div>

      <nav class="nav-list" role="tablist" aria-label="主导航">
        <button class="nav-btn ${state.tab === "archive" ? "active" : ""}" role="tab" aria-selected="${state.tab === "archive"}" data-tab="archive">
          <span>语音存档</span>
          <span class="nav-count">${recCount}</span>
        </button>
        <button class="nav-btn ${state.tab === "asr" ? "active" : ""}" role="tab" aria-selected="${state.tab === "asr"}" data-tab="asr">
          <span>ASR 接入</span>
          <span class="nav-count">${escapeHTML(activeProv)}</span>
        </button>
        <button class="nav-btn ${state.tab === "system" ? "active" : ""}" role="tab" aria-selected="${state.tab === "system"}" data-tab="system">
          <span>信箱与存储</span>
        </button>
      </nav>

      <div class="sidebar-footer">
        <span class="num">累计人声 ${speechDur}</span>
        <span class="num">已建档 ${state.stats?.speaker_count ?? 0} 人</span>
      </div>
    </aside>
  `;
}

function renderArchiveWorkspace(): string {
  let listHTML = "";
  let lastGroup = "";
  for (const it of state.items) {
    const grp = fmtDayGroup(it.started_at);
    if (grp !== lastGroup) {
      lastGroup = grp;
      listHTML += `<div class="day-header">${escapeHTML(grp)}</div>`;
    }
    const active = it.name === state.selectedName ? "active" : "";
    const badges = (it.speakers || [])
      .slice(0, 3)
      .map((sp) => {
        const isNamed = !sp.label.startsWith("说话人 ") && !sp.label.startsWith("对话人 ");
        return `<span class="spk-pill ${isNamed ? "named" : ""}">${escapeHTML(sp.label)}</span>`;
      })
      .join("");
    listHTML += `
      <button class="rec-item ${active}" data-rec="${escapeHTML(it.name)}">
        <div class="rec-meta">
          <span class="rec-time">${escapeHTML(fmtClock(it.started_at))}</span>
          <span class="rec-dur">${escapeHTML(fmtDuration(it.duration_s))}</span>
        </div>
        <div class="rec-preview">${escapeHTML(it.preview || "（空白文本）")}</div>
        ${badges ? `<div class="badge-row">${badges}</div>` : ""}
      </button>
    `;
  }

  if (state.items.length > 0 && state.items.length < state.total) {
    listHTML += `
      <div style="padding:10px 8px 4px;">
        <button class="btn" style="width:100%;justify-content:center;" data-action="load-more">
          加载更多（已显示 ${state.items.length} / 共 ${state.total} 段）
        </button>
      </div>
    `;
  }

  if (state.items.length === 0) {
    if (!state.status?.configured) {
      listHTML = `
        <div class="empty-box">
          <p class="empty-title">尚未连接盲信箱</p>
          <p class="empty-desc">${escapeHTML(state.status?.config_hint || "填入盲信箱地址、共享密钥与 ASR 接口后，手机录音会自动同步归档到本地 SQLite。")}</p>
          <div style="display:flex;gap:8px;">
            <button class="btn btn-primary" data-tab="system">配置信箱与密钥</button>
            <button class="btn" data-tab="asr">配置 ASR</button>
          </div>
        </div>
      `;
    } else if (state.filter.query || state.filter.date_range !== "all" || state.filter.speaker_id) {
      listHTML = `
        <div class="empty-box">
          <p class="empty-title">没有匹配的录音段</p>
          <p class="empty-desc">尝试清空关键词或放宽日期范围。</p>
          <button class="btn" data-action="reset-filter">重置筛选条件</button>
        </div>
      `;
    } else {
      listHTML = `
        <div class="empty-box">
          <p class="empty-title">正在监听信箱队列</p>
          <p class="empty-desc">电脑端已就绪。手机上传加密录音段后，转写结果将实时写入本机数据库并显示在这里。</p>
        </div>
      `;
    }
  }

  let detailHTML = "";
  if (state.detail) {
    const d = state.detail;
    const headerDate =
      d.started_at.length >= 16 ? `${d.started_at.slice(0, 10)} ${d.started_at.slice(11, 16)}` : d.started_at;
    const segRows =
      d.segments && d.segments.length > 0
        ? d.segments
            .map(
              (s) => `
          <div class="seg-row">
            <div class="seg-ts">${fmtMMSS(s.start)}</div>
            <div class="seg-body">
              ${s.speaker_label ? `<div class="seg-speaker">${escapeHTML(s.speaker_label)}</div>` : ""}
              <p class="seg-text">${escapeHTML(s.text)}</p>
            </div>
          </div>
        `,
            )
            .join("")
        : `<p class="seg-text">${escapeHTML(d.text)}</p>`;

    detailHTML = `
      <div class="detail-header">
        <div>
          <h2 class="detail-title num">${escapeHTML(headerDate)}</h2>
          <div class="detail-sub">
            <span>总长 ${escapeHTML(fmtDuration(d.duration_s))}</span>
            <span>有效人声 ${escapeHTML(fmtDuration(d.speech_s))}</span>
            ${d.model ? `<span>${escapeHTML(d.model)}</span>` : ""}
          </div>
        </div>
        <div class="detail-actions">
          <button class="btn" data-action="copy-single">复制本段 Markdown</button>
          <button class="btn btn-ghost" data-action="ask-delete" aria-label="删除本地存档">删除</button>
          ${
            state.confirmDelete
              ? `
            <div class="popconfirm" role="alertdialog" aria-label="确认删除">
              <div style="font-size:var(--fs-meta);color:var(--text-secondary);">从本机存档移除这段记录？（不影响手机已有记录）</div>
              <div style="display:flex;justify-content:flex-end;gap:6px;">
                <button class="btn btn-ghost" data-action="cancel-delete">取消</button>
                <button class="btn btn-danger" data-action="confirm-delete" data-del-name="${escapeHTML(d.name)}">删除</button>
              </div>
            </div>
          `
              : ""
          }
        </div>
      </div>
      <div class="dialogue-scroll">${segRows}</div>
    `;
  } else {
    detailHTML = `
      <div class="empty-box" style="color:var(--text-muted);">
        选择左侧一条录音查看对话全文与导出选项。
      </div>
    `;
  }

  return `
    <header class="topbar">
      <div class="topbar-title">本地语音存档</div>
      <div class="topbar-actions">
        <div class="popover-anchor">
          <button class="btn ${state.total > 0 ? "btn-primary" : ""}" data-action="toggle-export" ${state.total === 0 ? "disabled" : ""}>导出 ${state.total} 段 ▾</button>
          ${
            state.exportMenuOpen
              ? `
            <div class="popover-menu" role="menu">
              <button class="pop-item" role="menuitem" data-action="copy-filtered-md">复制 Markdown 到剪贴板</button>
              <button class="pop-item" role="menuitem" data-action="export-md">导出为 .md 上下文文件…</button>
              <button class="pop-item" role="menuitem" data-action="export-json">导出为 .json 结构化文件…</button>
            </div>
          `
              : ""
          }
        </div>
      </div>
    </header>

    <div class="archive-split">
      <div class="archive-list-pane">
        <div class="filter-bar">
          <div class="search-wrap">
            <input id="archive-search" type="text" placeholder="搜索对话内容或人名…" value="${escapeHTML(state.filter.query)}" />
            <span class="search-kbd">/</span>
          </div>
          <div class="filter-row">
            <div class="seg-group" role="group" aria-label="时间范围">
              ${[
                ["all", "全部"],
                ["today", "今天"],
                ["7d", "7天"],
                ["30d", "30天"],
              ]
                .map(
                  ([val, lbl]) =>
                    `<button class="seg-btn ${state.filter.date_range === val ? "active" : ""}" data-range="${val}">${lbl}</button>`,
                )
                .join("")}
            </div>
            ${
              state.speakers.length > 0
                ? `
              <select class="speaker-select" id="speaker-filter" aria-label="按人物筛选">
                <option value="">全部人物</option>
                ${state.speakers
                  .map(
                    (sp) =>
                      `<option value="${escapeHTML(sp.id)}" ${state.filter.speaker_id === sp.id ? "selected" : ""}>${escapeHTML(sp.label)}</option>`,
                  )
                  .join("")}
              </select>
            `
                : ""
            }
          </div>
        </div>
        <div class="rec-scroll">${listHTML}</div>
      </div>
      <section class="detail-pane">${detailHTML}</section>
    </div>
  `;
}

function renderASRWorkspace(): string {
  const d = state.draft!;
  const prov = d.asr.provider || "openai";
  const metrics = state.stats?.asr;

  let formFields = "";
  if (prov === "volcengine") {
    formFields = `
      <div class="field">
        <label class="field-label" for="volc-key">
          <span>豆包语音 API Key</span>
          ${state.keychainActive ? `<span style="font-weight:400;color:var(--text-muted);">系统钥匙串保护</span>` : `<span style="font-weight:400;color:var(--text-muted);">在「豆包语音控制台 → API Key 管理」创建（与火山方舟 Key 不通用）</span>`}
        </label>
        <div class="input-group">
          <input id="volc-key" type="${state.showSecrets ? "text" : "password"}" class="mono" placeholder="填入豆包语音 API Key（旧版控制台填 Access Token）" value="${escapeHTML(d.asr.api_key || "")}" data-field="asr.api_key" />
          <button class="btn" data-action="toggle-secrets">${state.showSecrets ? "隐藏" : "显示"}</button>
        </div>
      </div>
      <div class="field-row">
        <div class="field">
          <label class="field-label" for="volc-res"><span>资源 ID（需在控制台开通极速版）</span></label>
          <input id="volc-res" type="text" class="mono" placeholder="volc.bigasr.auc_turbo" value="${escapeHTML(d.asr.model || "volc.bigasr.auc_turbo")}" data-field="asr.model" />
        </div>
        <div class="field">
          <label class="field-label" for="volc-appid"><span>旧版 App ID（新版留空）</span></label>
          <input id="volc-appid" type="text" class="mono" placeholder="新版单 Key 留空" value="${escapeHTML(d.asr.app_id || "")}" data-field="asr.app_id" />
        </div>
      </div>
      <p class="note-line">同步直传内存中的 Ogg Opus 音频，无需中转对象存储；自带标点顺滑、精确分句时间戳与单段内说话人分离。</p>
    `;
  } else if (prov === "dashscope") {
    formFields = `
      <div class="field">
        <label class="field-label" for="dashscope-key">
          <span>阿里云百炼 API Key</span>
          ${state.keychainActive ? `<span style="font-weight:400;color:var(--text-muted);">系统钥匙串保护</span>` : ""}
        </label>
        <div class="input-group">
          <input id="dashscope-key" type="${state.showSecrets ? "text" : "password"}" class="mono" placeholder="粘贴百炼控制台创建的 API Key" value="${escapeHTML(d.asr.api_key || "")}" data-field="asr.api_key" />
          <button class="btn" data-action="toggle-secrets">${state.showSecrets ? "隐藏" : "显示"}</button>
        </div>
      </div>
      <div class="field">
        <label class="field-label" for="dashscope-model"><span>模型名称</span></label>
        <input id="dashscope-model" type="text" class="mono" placeholder="qwen-audio-3.1-asr-flash" value="${escapeHTML(d.asr.model || "qwen-audio-3.1-asr-flash")}" data-field="asr.model" />
      </div>
      <div class="field">
        <label class="field-label" for="dashscope-url"><span>原生接口地址（需与 Key 的地域一致）</span></label>
        <input id="dashscope-url" type="text" class="mono" placeholder="https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation" value="${escapeHTML(d.asr.base_url)}" data-field="asr.base_url" />
      </div>
      <p class="note-line">在百炼控制台开通模型并创建 API Key；新加坡地域可用 dashscope-intl.aliyuncs.com，也可填控制台提供的工作空间域名。直接发送 Ogg Opus，单段最长 5 分钟，支持分句时间戳与单段说话人分离。</p>
      <button class="btn" data-action="open-dashscope-console">打开百炼控制台 ↗</button>
    `;
  } else if (prov === "openai") {
    const chips = state.presets
      .filter((p) => p.provider === "openai")
      .map((p) => {
        const active = d.asr.base_url === p.base_url ? "active" : "";
        return `<button class="preset-chip ${active}" data-preset="${escapeHTML(p.id)}">${escapeHTML(p.label)}</button>`;
      })
      .join("");

    formFields = `
      <div class="field">
        <span class="field-label">服务商快捷预设</span>
        <div class="preset-row">${chips}</div>
      </div>
      <div class="field">
        <label class="field-label" for="asr-url"><span>接口地址 (Base URL)</span></label>
        <input id="asr-url" type="text" class="mono" placeholder="https://api.siliconflow.cn/v1" value="${escapeHTML(d.asr.base_url)}" data-field="asr.base_url" />
      </div>
      <div class="field-row">
        <div class="field">
          <label class="field-label" for="asr-model"><span>模型名称</span></label>
          <input id="asr-model" type="text" class="mono" placeholder="FunAudioLLM/SenseVoiceSmall" value="${escapeHTML(d.asr.model)}" data-field="asr.model" />
        </div>
        <div class="field">
          <label class="field-label" for="asr-lang"><span>语言</span></label>
          <input id="asr-lang" type="text" class="mono" placeholder="zh" value="${escapeHTML(d.asr.language)}" data-field="asr.language" />
        </div>
      </div>
      <div class="field">
        <label class="field-label" for="asr-key">
          <span>API Key（本地服务可留空）</span>
          ${state.keychainActive ? `<span style="font-weight:400;color:var(--text-muted);">系统钥匙串保护</span>` : ""}
        </label>
        <div class="input-group">
          <input id="asr-key" type="${state.showSecrets ? "text" : "password"}" class="mono" placeholder="sk-..." value="${escapeHTML(d.asr.api_key || "")}" data-field="asr.api_key" />
          <button class="btn" data-action="toggle-secrets">${state.showSecrets ? "隐藏" : "显示"}</button>
        </div>
      </div>
      <p class="note-line">三方接口不返回声纹时自动跳过认人，仅按时间线存档对话文字。</p>
    `;
  } else if (prov === "hark_http") {
    formFields = `
      <div class="field-row">
        <div class="field">
          <label class="field-label" for="hark-url"><span>本地增强服务地址</span></label>
          <input id="hark-url" type="text" class="mono" placeholder="http://127.0.0.1:8765" value="${escapeHTML(d.asr.base_url)}" data-field="asr.base_url" />
        </div>
        <div class="field">
          <label class="field-label" for="spk-thresh"><span>跨录音认人阈值</span></label>
          <input id="spk-thresh" type="number" step="0.02" min="0.3" max="0.95" class="num" value="${d.speaker_threshold}" data-field="speaker_threshold" />
        </div>
      </div>
      <div class="field">
        <label class="field-label" for="hark-engine"><span>可选引擎标识（留空用服务端默认）</span></label>
        <input id="hark-engine" type="text" class="mono" placeholder="例如 qwen3-asr（大多数本地服务留空即可）" value="${escapeHTML(d.asr.model)}" data-field="asr.model" />
      </div>
      <p class="note-line">同时返回分段文字与说话人声纹时自动启用跨录音认人；声纹只留本机，手机改名自动同步。</p>
    `;
  } else {
    formFields = `
      <p class="note-line">内置模拟模式不请求外部服务，收到录音后直接回传示例文本，用于快速验通手机到电脑的链路。</p>
    `;
  }

  let probeHTML = "";
  if (state.probeReport) {
    const r = state.probeReport;
    if (!r.ok) {
      probeHTML = `<div class="probe-box err">测试未通过（${r.latency_ms} ms）：${escapeHTML(r.error || "未知错误")}</div>`;
    } else {
      const spkText = !r.has_speakers
        ? "无声纹（纯文本模式）"
        : r.has_diarization_only
          ? `单段分人 (检出 ${r.speaker_count} 人)`
          : `跨录音声纹 (检出 ${r.speaker_count} 人)`;
      probeHTML = `
        <div class="probe-box ok">
          <div class="cap-grid">
            <span class="cap-tag good">连通正常 · ${r.latency_ms} ms</span>
            <span class="cap-tag ${r.has_timestamps ? "good" : ""}">时间戳：${r.has_timestamps ? `支持 (${r.segment_count} 段)` : "仅整段文本"}</span>
            <span class="cap-tag ${r.has_speakers ? "good" : ""}">区分说话人：${spkText}</span>
          </div>
          <div style="font-size:var(--fs-meta);color:var(--text-secondary);">测试结果：「${escapeHTML(r.text || "空")}」</div>
        </div>
      `;
    }
  }

  const dirtyText = state.dirty ? "有未保存的修改（保存后切换后台生效）" : "当前显示为已生效配置";

  return `
    <header class="topbar">
      <div class="topbar-title">ASR 语音转写接入</div>
      <div class="topbar-actions"></div>
    </header>

    <div class="settings-scroll">
      <div class="settings-col">
        <div class="panel">
          <div class="panel-head">
            <h3 class="panel-title">接入协议</h3>
            <div class="seg-group" role="group" aria-label="ASR 协议类型">
              <button class="seg-btn ${prov === "volcengine" ? "active" : ""}" data-provider="volcengine">豆包大模型</button>
              <button class="seg-btn ${prov === "dashscope" ? "active" : ""}" data-provider="dashscope">阿里云百炼</button>
              <button class="seg-btn ${prov === "openai" ? "active" : ""}" data-provider="openai">OpenAI 兼容</button>
              <button class="seg-btn ${prov === "hark_http" ? "active" : ""}" data-provider="hark_http">Hark 本地声纹</button>
              <button class="seg-btn ${prov === "mock" ? "active" : ""}" data-provider="mock">内置模拟</button>
            </div>
          </div>
          ${formFields}
          ${probeHTML}
          <div class="panel-footer">
            <span class="dirty-hint ${state.dirty ? "unsaved" : ""}">${dirtyText}</span>
            <div style="display:flex;gap:8px;">
              <button class="btn" data-action="probe-asr" ${state.probingASR ? "disabled" : ""}>${state.probingASR ? "发送测试语音…" : "测试连接"}</button>
              <button class="btn btn-primary" data-action="save-settings" ${!state.dirty || state.saving ? "disabled" : ""}>${state.saving ? "保存中…" : "保存配置"}</button>
            </div>
          </div>
        </div>

        <div class="metric-inline">
          <span>最近 ${metrics?.window_total ?? 0} 次转写</span>
          <div class="metric-inline-stats">
            <span>成功率 <strong>${metrics && metrics.window_total > 0 ? `${metrics.success_rate.toFixed(0)}%` : "—"}</strong></span>
            <span>平均耗时 <strong>${metrics && metrics.success_count > 0 ? `${metrics.avg_latency_ms} ms` : "—"}</strong></span>
            <span>成功 <strong>${metrics?.success_count ?? 0}</strong> / 失败 <strong>${(metrics?.window_total ?? 0) - (metrics?.success_count ?? 0)}</strong></span>
          </div>
        </div>
        ${metrics?.last_error ? `<div class="probe-box err">最近异常：${escapeHTML(metrics.last_error)}</div>` : ""}
      </div>
    </div>
  `;
}

function renderSystemWorkspace(): string {
  const d = state.draft!;
  const st = state.stats;

  let mbHTML = "";
  if (state.mailboxResult) {
    const r = state.mailboxResult;
    mbHTML = r.ok
      ? `<div class="probe-box ok"><div class="cap-grid"><span class="cap-tag good">信箱连通且令牌有效 · ${r.latency_ms} ms</span></div><div style="font-size:var(--fs-meta);color:var(--text-secondary);">共享密钥不发往服务器，将在手机上传录音时端到端核验。</div></div>`
      : `<div class="probe-box err">信箱连接失败：${escapeHTML(r.error || "")}</div>`;
  } else if (state.status?.phase === "error" && state.status.last_error) {
    mbHTML = `<div class="probe-box err">当前同步受阻原因：${escapeHTML(state.status.last_error)}</div>`;
  }

  const dirtyText = state.dirty ? "有未保存的修改" : "已生效";

  return `
    <header class="topbar">
      <div class="topbar-title">信箱与存储</div>
      <div class="topbar-actions"></div>
    </header>

    <div class="settings-scroll">
      <div class="settings-col">
        <div class="panel">
          <div class="panel-head">
            <h3 class="panel-title">盲信箱与端到端配对</h3>
            ${state.keychainActive ? `<span style="font-size:var(--fs-meta);color:var(--text-muted);">系统钥匙串保护</span>` : ""}
          </div>
          <div class="field">
            <label class="field-label" for="mb-url"><span>盲信箱地址</span></label>
            <input id="mb-url" type="text" class="mono" placeholder="https://hark.example.com" value="${escapeHTML(d.server_url)}" data-field="server_url" />
          </div>
          <div class="field">
            <label class="field-label" for="mb-tok"><span>电脑端连接令牌</span></label>
            <div class="input-group">
              <input id="mb-tok" type="${state.showSecrets ? "text" : "password"}" class="mono" placeholder="对应信箱服务配置的 WORKER_TOKEN" value="${escapeHTML(d.worker_token || "")}" data-field="worker_token" />
              <button class="btn" data-action="toggle-secrets">${state.showSecrets ? "隐藏" : "显示"}</button>
            </div>
          </div>
          <div class="field">
            <label class="field-label" for="mb-key">
              <span>端到端共享密钥</span>
              <span style="font-weight:400;color:var(--text-muted);">复制后填入手机 App「服务器设置」完成配对</span>
            </label>
            <div class="input-group">
              <input id="mb-key" type="${state.showSecrets ? "text" : "password"}" class="mono" placeholder="32 字节 Base64 或 64 位 hex" value="${escapeHTML(d.e2ee_key || "")}" data-field="e2ee_key" />
              <button class="btn" data-action="gen-key">生成新密钥</button>
              <button class="btn" data-action="copy-key">复制</button>
            </div>
          </div>
          <div class="field">
            <label class="field-label" for="mb-ca">
              <span>自签证书路径（可选）</span>
              <span style="font-weight:400;color:var(--text-muted);">公网 HTTPS 域名留空；IP 自签证书可选择 .pem 文件</span>
            </label>
            <div class="input-group">
              <input id="mb-ca" type="text" class="mono" placeholder="公网正规证书留空" value="${escapeHTML(d.server_ca || "")}" data-field="server_ca" />
              <button class="btn" data-action="pick-ca">选择证书…</button>
            </div>
          </div>
          ${mbHTML}
          <div class="panel-footer">
            <span class="dirty-hint ${state.dirty ? "unsaved" : ""}">${dirtyText}</span>
            <div style="display:flex;gap:8px;">
              <button class="btn" data-action="test-mailbox" ${state.testingMailbox ? "disabled" : ""}>${state.testingMailbox ? "检测中…" : "测试信箱连通"}</button>
              <button class="btn btn-primary" data-action="save-settings" ${!state.dirty || state.saving ? "disabled" : ""}>${state.saving ? "保存中…" : "保存配置"}</button>
            </div>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <h3 class="panel-title">本地 SQLite 存档库</h3>
            <div style="display:flex;gap:6px;">
              <button class="btn" data-action="reveal-db">打开所在文件夹</button>
              <button class="btn" data-action="backup-db">备份数据库…</button>
            </div>
          </div>
          <div class="field">
            <label class="field-label" for="data-dir"><span>存档目录</span><span class="num">${fmtBytes(st?.db_size_bytes ?? 0)} · ${st?.total_recordings ?? 0} 条记录</span></label>
            <input id="data-dir" type="text" class="mono" value="${escapeHTML(d.data_dir)}" data-field="data_dir" />
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <h3 class="panel-title">桌面常驻与自动更新</h3>
            <div style="display:flex;align-items:center;gap:8px;">
              ${
                state.availableUpdate
                  ? `<span class="spk-pill named num">新版本 v${escapeHTML(state.availableUpdate)}</span>`
                  : `<span style="font-size:var(--fs-meta);color:var(--text-muted);" class="num">当前 v${escapeHTML(state.appVersion)}</span>`
              }
              <button class="btn" data-action="check-updates">检查更新…</button>
            </div>
          </div>
          <div class="toggle-row" data-switch="close_to_tray">
            <span>关闭窗口时退驻菜单栏继续后台拉取</span>
            <button type="button" class="switch" role="switch" aria-checked="${d.close_to_tray}" aria-label="关闭窗口退驻菜单栏"></button>
          </div>
          <div class="toggle-row" data-switch="open_at_login">
            <span>登录系统时自动启动 Hark</span>
            <button type="button" class="switch" role="switch" aria-checked="${d.open_at_login}" aria-label="登录系统时自动启动"></button>
          </div>
        </div>
      </div>
    </div>
  `;
}

function render() {
  const root = document.getElementById("app");
  if (!root || !state.draft) return;

  const activeEl = document.activeElement as HTMLInputElement | null;
  const activeId = activeEl?.id;
  const selStart = activeEl && "selectionStart" in activeEl ? activeEl.selectionStart : null;
  const selEnd = activeEl && "selectionEnd" in activeEl ? activeEl.selectionEnd : null;

  const mainHTML =
    state.tab === "archive"
      ? renderArchiveWorkspace()
      : state.tab === "asr"
        ? renderASRWorkspace()
        : renderSystemWorkspace();

  root.innerHTML = `
    <div class="shell">
      ${renderSidebar()}
      <main class="workspace">${mainHTML}</main>
    </div>
    ${state.toast ? `<div class="toast" role="status">${escapeHTML(state.toast)}</div>` : ""}
  `;

  if (activeId) {
    const next = document.getElementById(activeId) as HTMLInputElement | null;
    if (next) {
      next.focus();
      if (selStart !== null && selEnd !== null && next.type !== "number") {
        try {
          next.setSelectionRange(selStart, selEnd);
        } catch {
          // ignore
        }
      }
    }
  }
}

function bindGlobalListeners() {
  document.addEventListener("click", async (ev) => {
    const target = ev.target as HTMLElement | null;
    if (!target) return;

    const btn = target.closest<HTMLElement>(
      "[data-tab],[data-rec],[data-range],[data-provider],[data-preset],[data-action],[data-switch]",
    );
    if (!btn) {
      if (state.exportMenuOpen || state.confirmDelete) {
        state.exportMenuOpen = false;
        state.confirmDelete = false;
        render();
      }
      return;
    }

    if (btn.dataset.switch && state.draft) {
      const k = btn.dataset.switch;
      if (k === "close_to_tray") state.draft.close_to_tray = !state.draft.close_to_tray;
      else if (k === "open_at_login") state.draft.open_at_login = !state.draft.open_at_login;
      state.draftRev++;
      if (!state.dirty) {
        await saveDraft(true);
        showToast("已更新常驻设置");
      } else {
        render();
      }
      return;
    }

    if (btn.dataset.tab) {
      state.tab = btn.dataset.tab as Tab;
      state.exportMenuOpen = false;
      state.confirmDelete = false;
      render();
      return;
    }

    if (btn.dataset.rec) {
      await selectRecording(btn.dataset.rec);
      return;
    }

    if (btn.dataset.range) {
      state.filter.date_range = btn.dataset.range;
      await refreshArchive(false);
      return;
    }

    if (btn.dataset.provider && state.draft) {
      const prev = state.draft.asr.provider;
      if (state.draft.asr.base_url) {
        state.providerUrls[prev] = state.draft.asr.base_url;
      }
      state.providerModels[prev] = state.draft.asr.model || "";
      state.providerKeys[prev] = state.draft.asr.api_key || "";
      const p = btn.dataset.provider;
      state.draft.asr.provider = p;
      if (p === "volcengine") {
        state.draft.asr.base_url =
          state.providerUrls.volcengine || "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash";
        state.draft.asr.model = state.providerModels.volcengine || "volc.bigasr.auc_turbo";
        state.draft.asr.api_key = state.providerKeys.volcengine || "";
      } else if (p === "dashscope") {
        state.draft.asr.base_url = state.providerUrls.dashscope || "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
        state.draft.asr.model = state.providerModels.dashscope || "qwen-audio-3.1-asr-flash";
        state.draft.asr.api_key = state.providerKeys.dashscope || "";
      } else if (p === "hark_http") {
        state.draft.asr.base_url = state.providerUrls.hark_http || "http://127.0.0.1:8765";
        state.draft.asr.model = state.providerModels.hark_http || "";
        state.draft.asr.api_key = state.providerKeys.hark_http || "";
      } else if (p === "openai") {
        state.draft.asr.base_url = state.providerUrls.openai || "https://api.siliconflow.cn/v1";
        state.draft.asr.model = state.providerModels.openai || "FunAudioLLM/SenseVoiceSmall";
        state.draft.asr.api_key = state.providerKeys.openai || "";
      }
      state.draftRev++;
      state.dirty = true;
      state.probeReport = null;
      render();
      return;
    }

    if (btn.dataset.preset && state.draft) {
      const preset = state.presets.find((x) => x.id === btn.dataset.preset);
      if (preset) {
        state.draft.asr.provider = preset.provider;
        state.draft.asr.base_url = preset.base_url;
        state.draft.asr.model = preset.default_model;
        state.providerUrls[preset.provider] = preset.base_url;
        state.providerModels[preset.provider] = preset.default_model;
        state.draftRev++;
        state.dirty = true;
        state.probeReport = null;
        render();
      }
      return;
    }

    switch (btn.dataset.action) {
      case "open-dashscope-console": {
        await Hark.openExternalDocs("https://bailian.console.aliyun.com/");
        break;
      }
      case "toggle-pause": {
        const next = !(state.status?.paused ?? false);
        state.status = await Hark.togglePause(next);
        render();
        break;
      }
      case "reset-filter": {
        state.filter = { query: "", date_range: "all", speaker_id: "", limit: 150, offset: 0 };
        await refreshArchive(false);
        break;
      }
      case "load-more": {
        await loadMoreArchive();
        break;
      }
      case "toggle-export": {
        state.exportMenuOpen = !state.exportMenuOpen;
        render();
        break;
      }
      case "copy-filtered-md": {
        state.exportMenuOpen = false;
        const res = await Hark.copyMarkdown(state.filter);
        showToast(`已复制 ${res.count} 段对话（${res.chars} 字）到剪贴板`);
        break;
      }
      case "export-md":
      case "export-json": {
        const fmt = btn.dataset.action === "export-json" ? "json" : "md";
        state.exportMenuOpen = false;
        render();
        const saved = await Hark.exportArchive(state.filter, fmt);
        if (saved) showToast(`已导出至 ${saved}`);
        break;
      }
      case "copy-single": {
        if (!state.selectedName) break;
        const res = await Hark.copySingleMarkdown(state.selectedName);
        showToast(`已复制本段 Markdown（${res.chars} 字）`);
        break;
      }
      case "ask-delete": {
        state.confirmDelete = !state.confirmDelete;
        render();
        break;
      }
      case "cancel-delete": {
        state.confirmDelete = false;
        render();
        break;
      }
      case "confirm-delete": {
        const delName = btn.dataset.delName || state.detail?.name;
        if (!delName) break;
        await Hark.deleteTranscript(delName);
        state.confirmDelete = false;
        showToast("已从本机存档移除");
        await refreshArchive(false);
        break;
      }
      case "toggle-secrets": {
        state.showSecrets = !state.showSecrets;
        render();
        break;
      }
      case "probe-asr": {
        if (!state.draft || state.probingASR) break;
        state.probingASR = true;
        state.probeReport = null;
        render();
        try {
          state.probeReport = await Hark.probeASR(state.draft.asr);
        } finally {
          state.probingASR = false;
          render();
        }
        break;
      }
      case "test-mailbox": {
        if (!state.draft || state.testingMailbox) break;
        state.testingMailbox = true;
        state.mailboxResult = null;
        render();
        try {
          state.mailboxResult = await Hark.testMailbox(state.draft);
        } finally {
          state.testingMailbox = false;
          render();
        }
        break;
      }
      case "gen-key": {
        if (!state.draft) break;
        state.draft.e2ee_key = await Hark.generateE2EEKey();
        state.showSecrets = true;
        state.draftRev++;
        state.dirty = true;
        showToast("已生成新密钥，点「保存配置」后生效");
        break;
      }
      case "copy-key": {
        const k = state.draft?.e2ee_key?.trim();
        if (!k) {
          showToast("密钥为空");
          break;
        }
        await navigator.clipboard.writeText(k);
        showToast("已复制共享密钥，可直接发往手机填入");
        break;
      }
      case "reveal-db": {
        await Hark.revealDatabase();
        break;
      }
      case "check-updates": {
        await Hark.checkForUpdates();
        break;
      }
      case "pick-ca": {
        if (!state.draft) break;
        const chosen = await Hark.pickCAFile();
        if (chosen) {
          state.draft.server_ca = chosen;
          state.draftRev++;
          state.dirty = true;
          render();
        }
        break;
      }
      case "backup-db": {
        const dest = await Hark.backupDatabase();
        if (dest) showToast(`数据库已在线备份至 ${dest}`);
        break;
      }
      case "save-settings": {
        await saveDraft(false);
        break;
      }
    }
  });

  document.addEventListener("input", (ev) => {
    const el = ev.target as HTMLInputElement | HTMLSelectElement | null;
    if (!el) return;

    if (el.id === "archive-search") {
      state.filter.query = el.value;
      if (searchTimer) clearTimeout(searchTimer);
      searchTimer = setTimeout(() => {
        void refreshArchive(false);
      }, 160);
      return;
    }

    if (el.id === "speaker-filter") {
      state.filter.speaker_id = el.value;
      void refreshArchive(false);
      return;
    }

    if (!state.draft) return;
    const field = el.dataset.field;
    if (field) {
      if (field === "asr.base_url") {
        state.draft.asr.base_url = el.value;
        state.providerUrls[state.draft.asr.provider] = el.value;
      } else if (field === "asr.model") {
        state.draft.asr.model = el.value;
        state.providerModels[state.draft.asr.provider] = el.value;
      } else if (field === "asr.language") state.draft.asr.language = el.value;
      else if (field === "asr.api_key") {
        state.draft.asr.api_key = el.value;
        state.providerKeys[state.draft.asr.provider] = el.value;
      } else if (field === "asr.app_id") state.draft.asr.app_id = el.value;
      else if (field === "speaker_threshold") state.draft.speaker_threshold = Number(el.value) || 0.6;
      else if (field === "server_url") state.draft.server_url = el.value;
      else if (field === "worker_token") state.draft.worker_token = el.value;
      else if (field === "e2ee_key") state.draft.e2ee_key = el.value;
      else if (field === "server_ca") state.draft.server_ca = el.value;
      else if (field === "data_dir") state.draft.data_dir = el.value;
      state.draftRev++;
      state.dirty = true;
      document.querySelectorAll<HTMLButtonElement>('[data-action="save-settings"]').forEach((b) => {
        b.disabled = false;
      });
      document.querySelectorAll<HTMLElement>(".dirty-hint").forEach((h) => {
        h.textContent = "有未保存的修改";
        h.classList.add("unsaved");
      });
    }
  });

  document.addEventListener("keydown", (ev) => {
    const tag = (ev.target as HTMLElement | null)?.tagName;
    const inInput = tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT";
    if (ev.key === "Escape") {
      if (state.exportMenuOpen || state.confirmDelete) {
        state.exportMenuOpen = false;
        state.confirmDelete = false;
        render();
      }
      return;
    }
    if (!inInput && ev.key === "/" && state.tab === "archive") {
      ev.preventDefault();
      document.getElementById("archive-search")?.focus();
      return;
    }
    if (!inInput && state.tab === "archive" && (ev.key === "ArrowDown" || ev.key === "ArrowUp") && state.items.length > 0) {
      ev.preventDefault();
      const idx = state.items.findIndex((x) => x.name === state.selectedName);
      const nextIdx = ev.key === "ArrowDown" ? Math.min(state.items.length - 1, idx + 1) : Math.max(0, idx - 1);
      if (nextIdx >= 0 && state.items[nextIdx]) {
        void selectRecording(state.items[nextIdx].name);
      }
    }
  });
}

async function init() {
  bindGlobalListeners();
  const boot = await Hark.bootstrap();
  state.appVersion = boot.app_version || "0.1.0";
  state.availableUpdate = boot.available_update || "";
  state.config = boot.config;
  state.draft = cloneConfig(boot.config);
  if (boot.config.asr.base_url) {
    state.providerUrls[boot.config.asr.provider] = boot.config.asr.base_url;
  }
  state.providerModels[boot.config.asr.provider] = boot.config.asr.model || "";
  if (boot.config.asr.api_key) {
    state.providerKeys[boot.config.asr.provider] = boot.config.asr.api_key;
  }
  state.status = boot.status;
  state.stats = boot.stats;
  state.presets = boot.presets || [];
  state.keychainActive = boot.keychain_active;

  events.stateChanged.on((snap) => {
    const prevCount = state.stats?.total_recordings ?? 0;
    const prevRoster = state.status?.roster_ver ?? 0;
    state.status = snap.status;
    state.stats = snap.stats;
    state.availableUpdate = snap.available_update || "";
    if (snap.stats.total_recordings !== prevCount || snap.status.roster_ver !== prevRoster) {
      void refreshArchive(true);
    } else {
      render();
    }
  });

  await refreshArchive(false);
}

void init();
