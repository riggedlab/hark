@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.riggedlab.hark

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val player = Player()
    private var dataVersion by mutableIntStateOf(0)              // 人物/地点名单变了就让界面刷新
    private var configVersion by mutableIntStateOf(0)            // 服务器/密钥设置变了立即重连
    private var editingServer by mutableStateOf(false)
    private var editing by mutableStateOf<Speaker?>(null)        // 正在改名字的人
    private var merging by mutableStateOf<Speaker?>(null)        // 正在合并的人
    private var editingPlace by mutableStateOf<Place?>(null)
    private var mergingPlace by mutableStateOf<Place?>(null)
    private var hasLocation by mutableStateOf(false)
    private val uploader by lazy { Uploader(this) }
    private var startAction: String? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            hasLocation = Places.hasPermission(this)
            val ble = if (Build.VERSION.SDK_INT >= 31)
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            if (ble.all { res[it] ?: (checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED) })
                startRecorder(startAction)
        }

    private val locLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            hasLocation = Places.hasPermission(this)
            if (hasLocation && AppState.status.value.link != LinkState.STOPPED) {
                startForegroundService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_REFRESH))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Recordings.cleanup(RecorderService.chunkDir(this), Config.mode(this))
        hasLocation = Places.hasPermission(this)
        Thread { Places.backfill(this) }.start()     // 之前没查到名字的地点，补查一次
        setContent { DitingTheme { Screen() } }
    }

    // 锁屏或者切到别的 App 就暂停播放（录音不受影响，录音在后台服务里）
    override fun onStart() {
        super.onStart()
        LiveAudioMonitor.start(applicationContext)
    }

    override fun onStop() {
        LiveAudioMonitor.stop()
        player.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player.close()
        super.onDestroy()
    }

    private fun neededPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun requestStart(repair: Boolean = false) {
        startAction = if (repair) RecorderService.ACTION_REPAIR else null
        permLauncher.launch(neededPermissions())
    }
    private fun requestLocation() = locLauncher.launch(arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    private fun startRecorder(action: String? = null) =
        startForegroundService(Intent(this, RecorderService::class.java).setAction(action))
    private fun stopRecorder() =
        startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))

    private fun isBatteryWhitelisted() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun requestBatteryWhitelist() = startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))

    // ───────────────────────────── 整体框架 ─────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        val s by AppState.status.collectAsStateWithLifecycle()
        var recs by remember { mutableStateOf(listOf<Recording>()) }
        var whitelisted by remember { mutableStateOf(isBatteryWhitelisted()) }
        LaunchedEffect(Unit) {
            while (true) {
                recs = withContext(Dispatchers.IO) { Recordings.list(this@MainActivity) }
                whitelisted = isBatteryWhitelisted()
                hasLocation = Places.hasPermission(this@MainActivity)
                AppState.updateRemote { it }
                dataVersion++
                delay(3000)
            }
        }
        LaunchedEffect(Unit) { while (true) { player.tick(); delay(200) } }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle, configVersion) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val mode = Config.mode(this@MainActivity)
                    val ok = withContext(Dispatchers.IO) {
                        val (done, pending) = uploader.processPending()
                        AppState.update { it.copy(pendingUploads = pending,
                            uploadedThisSession = it.uploadedThisSession + done) }
                        if (mode == WorkMode.CLOUD) {
                            Sync.pollOnce(this@MainActivity, waitSec = 20)
                        } else {
                            done > 0
                        }
                    }
                    if (ok) recs = withContext(Dispatchers.IO) { Recordings.list(this@MainActivity) }
                    dataVersion++
                    if (!ok) {
                        val waitMs = when {
                            mode == WorkMode.DIRECT_ASR && AppState.status.value.localAsrError != null -> 20_000L
                            mode == WorkMode.DIRECT_ASR -> 3_000L
                            else -> 5_000L
                        }
                        delay(waitMs)
                    }
                }
            }
        }

        val tabs = listOf("文字", "录音", "人物", "地点")
        val pager = rememberPagerState { tabs.size }
        val scope = rememberCoroutineScope()
        var showStatus by remember { mutableStateOf(false) }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { Header(s) { showStatus = true } },
            bottomBar = { if (player.current != null) PlayerBar() },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                Crossfade(s.link == LinkState.STOPPED, label = "status") { stopped ->
                    if (stopped) StartBanner() else LiveCard(s)
                }
                TabRow(
                    selectedTabIndex = pager.currentPage,
                    containerColor = MaterialTheme.colorScheme.background,
                    divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
                ) {
                    tabs.forEachIndexed { i, t ->
                        Tab(selected = pager.currentPage == i,
                            onClick = { scope.launch { pager.animateScrollToPage(i) } },
                            text = { Text(t, fontWeight = if (pager.currentPage == i) FontWeight.SemiBold else FontWeight.Normal) },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // 左右滑动切换标签
                HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                    when (page) {
                        0 -> EventsPage(recs)
                        1 -> RecordingsPage(recs, s)
                        2 -> PeoplePage(recs)
                        else -> PlacesPage()
                    }
                }
            }
        }
        if (showStatus) {
            ModalBottomSheet(onDismissRequest = { showStatus = false }) { StatusSheet(s, whitelisted) }
        }
        SettingsDialog()
        PersonDialogs(recs)
        PlaceDialogs()
    }

    @Composable
    private fun Header(s: Status, onStatus: () -> Unit) {
        @Suppress("UNUSED_EXPRESSION") configVersion
        val mode = Config.mode(this@MainActivity)
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Hark · 谛听", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Surface(onClick = onStatus, shape = CircleShape, color = Color.Transparent) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(shortLabel(s.link), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val transcribing = if (mode == WorkMode.DIRECT_ASR) {
                        s.localAsrRunning != null
                    } else {
                        s.remote.workerOnline && s.remote.workerPhase == "transcribing"
                    }
                    if (transcribing) {
                        Spacer(Modifier.width(8.dp))
                        Text("转写中", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val remote = s.remote
                    val hasWarning = if (mode == WorkMode.DIRECT_ASR) {
                        AsrConfig.load(this@MainActivity) == null || s.localAsrError != null
                    } else {
                        Config.load(this@MainActivity) != null && (remote.mailboxReachable == false ||
                            (remote.inboxCount > 0 && !remote.workerOnline) || remote.asrOk == false)
                    }
                    if (hasWarning) {
                        Spacer(Modifier.width(8.dp))
                        StatusDot(Color(0xFFF2A33A), breathing = false)
                    }
                    s.batteryMv?.let {
                        Spacer(Modifier.width(10.dp))
                        Icon(batteryIcon(batteryPct(it)), null, Modifier.size(16.dp))
                        Text("${batteryPct(it)}%", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    private fun shortLabel(l: LinkState) = when (l) {
        LinkState.STOPPED -> "未录音"
        LinkState.SCANNING -> "找设备中"
        LinkState.CONNECTING -> "连接中"
        LinkState.PAIRING -> "配对中"
        LinkState.PAIRING_REQUIRED -> "需重新配对"
        LinkState.STREAMING -> "录音中"
        LinkState.RECONNECTING -> "等待重连"
    }

    private fun batteryIcon(p: Int): ImageVector = when {
        p >= 90 -> Icons.Rounded.BatteryFull
        p >= 70 -> Icons.Rounded.Battery5Bar
        p >= 45 -> Icons.Rounded.Battery4Bar
        p >= 25 -> Icons.Rounded.Battery2Bar
        else -> Icons.Rounded.Battery1Bar
    }

    /** 录音状态条：未录音、连接中、录音中都是同一个高度，切换时下面的内容不跳 */
    @Composable
    private fun StatusStrip(content: @Composable RowScope.() -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, content = content)
    }

    /** 状态圆点：工作中缓慢呼吸，颜色变化用过渡 */
    @Composable
    private fun StatusDot(color: Color, breathing: Boolean) {
        val shown by animateColorAsState(color, tween(300), label = "dot")
        val pulse by rememberInfiniteTransition(label = "dot").animateFloat(0.35f, 1f,
            infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "alpha")
        Box(Modifier.size(9.dp).clip(CircleShape).background(shown.copy(alpha = if (breathing) pulse else 1f)))
    }

    /** 滚动声波：最新的在最右边，越旧越淡 */
    @Composable
    private fun LevelWave(levels: List<Float>, color: Color, modifier: Modifier) {
        Canvas(modifier.height(30.dp)) {
            val bar = 3.dp.toPx()
            val step = bar + 2.5.dp.toPx()
            val count = (size.width / step).toInt()
            levels.takeLast(count).asReversed().forEachIndexed { i, v ->
                val h = (v.coerceIn(0f, 1f) * size.height).coerceAtLeast(bar)
                drawRoundRect(color.copy(alpha = 1f - 0.75f * i / count.coerceAtLeast(1)),
                    topLeft = androidx.compose.ui.geometry.Offset(size.width - bar - i * step, (size.height - h) / 2),
                    size = androidx.compose.ui.geometry.Size(bar, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(bar / 2))
            }
        }
    }

    @Composable
    private fun StartBanner() {
        StatusStrip {
            StatusDot(MaterialTheme.colorScheme.outline, breathing = false)
            Spacer(Modifier.width(12.dp))
            Text("现在没有在录音", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = { requestStart() }, Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 16.dp)) { Text("开始录音") }
        }
    }

    @Composable
    private fun LiveCard(s: Status) {
        var now by remember { mutableLongStateOf(System.nanoTime()) }
        val streaming = s.link == LinkState.STREAMING
        val written = streaming && s.lastWriteNanos != 0L && now - s.lastWriteNanos < 2_000_000_000L
        val levels = remember { mutableStateListOf<Float>() }
        val level by rememberUpdatedState(if (written) s.liveLevel ?: 0f else 0f)
        LaunchedEffect(Unit) {
            while (true) {
                now = System.nanoTime()
                levels.add(level)
                if (levels.size > 80) levels.removeAt(0)
                delay(100)
            }
        }
        val colors = MaterialTheme.colorScheme
        StatusStrip {
            if (!streaming) {
                val problem = s.link == LinkState.PAIRING_REQUIRED
                StatusDot(if (problem) colors.error else Color(0xFFF2A33A), breathing = !problem)
                Spacer(Modifier.width(12.dp))
                Text(s.message ?: s.link.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = if (problem) colors.error else colors.onSurfaceVariant, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            } else {
                StatusDot(if (written) Color(0xFF2FB36B) else Color(0xFFF2A33A), breathing = written)
                Spacer(Modifier.width(12.dp))
                Text(if (written) "正在录上" else "没有收到数据", style = MaterialTheme.typography.titleSmall)
                if (s.liveUnavailable) {
                    Spacer(Modifier.weight(1f))
                } else {
                    Spacer(Modifier.width(14.dp))
                    LevelWave(levels, colors.primary, Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    val speech = written && s.liveSpeech
                    val bg by animateColorAsState(if (speech) colors.primary else colors.surfaceContainerHigh,
                        tween(180), label = "speechBg")
                    val fg by animateColorAsState(if (speech) colors.onPrimary else colors.onSurfaceVariant,
                        tween(180), label = "speechFg")
                    Text("人声", Modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
                        color = fg, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    @Composable
    private fun StatusSheet(s: Status, whitelisted: Boolean) {
        @Suppress("UNUSED_EXPRESSION") configVersion
        val mode = Config.mode(this@MainActivity)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("设备状态", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                WorkMode.entries.forEachIndexed { idx, item ->
                    SegmentedButton(
                        selected = mode == item,
                        onClick = {
                            if (mode != item) {
                                Config.setMode(this@MainActivity, item)
                                configVersion++
                                dataVersion++
                                val ready = if (item == WorkMode.CLOUD) Config.load(this@MainActivity) != null
                                else AsrConfig.load(this@MainActivity) != null
                                if (!ready) editingServer = true
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = WorkMode.entries.size),
                    ) { Text(item.label) }
                }
            }
            Spacer(Modifier.height(12.dp))
            s.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            InfoRow("连接", s.link.label)
            s.deviceName?.let { InfoRow("设备", it) }
            InfoRow("电量", s.batteryMv?.let { "${batteryPct(it)}%%（%.2f V，按电压估算）".format(it / 1000.0) } ?: "—")
            if (s.link == LinkState.STREAMING) {
                InfoRow("数据", "%.1f KB/s".format(s.bytesPerSec / 1000.0))
                val lossPct = if (s.totalPackets > 0) s.lostPackets * 100.0 / (s.totalPackets + s.lostPackets) else 0.0
                InfoRow("丢包", "%.2f%%".format(lossPct))
                val all = s.totalPackets + s.lostPackets + s.silentFrames
                if (s.silentFrames > 0 && all > 0)
                    InfoRow("安静省略", "%.0f%%（这些时间设备没有发送，省电）".format(s.silentFrames * 100.0 / all))
                s.currentChunk?.let { name ->
                    val start = Recordings.parseStart(java.io.File(name))
                    InfoRow("当前片段", "${start?.format(T) ?: ""} 起，已录 ${fmtDuration(s.currentChunkSeconds)}")
                }
            }
            if (mode == WorkMode.DIRECT_ASR) {
                val asrHint = AsrConfig.statusHint(this@MainActivity)
                val asrCfg = AsrConfig.load(this@MainActivity)
                InfoRow("转写服务", asrCfg?.let { "${it.provider.shortLabel}（${it.model.ifBlank { it.baseUrl }}）" } ?: (asrHint ?: "未配置"))
                InfoRow("本机转写", when {
                    asrHint != null -> asrHint
                    s.localAsrRunning != null -> {
                        val at = Recordings.parseStart(java.io.File(s.localAsrRunning))?.format(T) ?: s.localAsrRunning
                        "正在转写 $at 片段…"
                    }
                    s.localAsrError != null -> "待重试（${s.localAsrError}）"
                    s.pendingUploads > 0 -> "待本机转写 ${s.pendingUploads} 段"
                    else -> "已全部本机转写"
                })
                val unbacked = remember(dataVersion, configVersion, s.pendingUploads) { uploader.unuploadedCount() }
                if (unbacked > 0) {
                    InfoRow("云端归档", "有 $unbacked 段录音仅在手机，切到「云端信箱同步」可自动补传到电脑 hark.db")
                }
            } else {
                val hint = Config.statusHint(this@MainActivity)
                InfoRow("上传", when {
                    hint != null -> hint
                    s.pendingUploads > 0 -> "手机待上传 ${s.pendingUploads} 段（每分钟自动重试）"
                    else -> "已全部加密上传"
                })
                val remote = s.remote
                InfoRow("信箱", when (remote.mailboxReachable) {
                    true -> "已连接（端到端加密" + (if (remote.inboxCount > 0) " · 待取 ${remote.inboxCount} 段）" else "）")
                    false -> "连接不上（${remote.mailboxError ?: "请检查网络"}）"
                    null -> "正在连接…"
                })
                InfoRow("转写端", workerLabel(remote))
            }
            InfoRow("定位", if (hasLocation) "已开启（记录只存在手机上）" else "未开启")
            var updateHint by remember { mutableStateOf<String?>(null) }
            var updateUrl by remember { mutableStateOf<String?>(null) }
            var checkingUpdate by remember { mutableStateOf(false) }
            val updateScope = rememberCoroutineScope()
            updateHint?.let { InfoRow("版本", it) }
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.link == LinkState.STOPPED) Button(onClick = { requestStart() }) { Text("开始录音") }
                else OutlinedButton(onClick = { stopRecorder() }) { Text("停止录音") }
                OutlinedButton(onClick = { editingServer = true }) { Text("转写与同步设置") }
                OutlinedButton(onClick = { requestStart(repair = true) }) { Text("重新配对") }
                if (updateUrl != null) {
                    Button(onClick = {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl)))
                    }) { Text("下载新版本") }
                } else {
                    OutlinedButton(enabled = !checkingUpdate, onClick = {
                        checkingUpdate = true
                        updateScope.launch {
                            val (hint, url) = withContext(Dispatchers.IO) { checkGitHubUpdate() }
                            updateHint = hint
                            updateUrl = url
                            checkingUpdate = false
                        }
                    }) { Text(if (checkingUpdate) "检查中…" else "检查更新 (v${BuildConfig.VERSION_NAME})") }
                }
                if (!whitelisted) OutlinedButton(onClick = { requestBatteryWhitelist() }) { Text("允许后台运行") }
                if (!hasLocation) OutlinedButton(onClick = { requestLocation() }) { Text("开启定位") }
            }
        }
    }

    @Composable
    private fun InfoRow(k: String, v: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(k, Modifier.width(80.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
            Text(v, style = MaterialTheme.typography.bodyMedium)
        }
    }

    // ───────────────────────────── 文字（按事件） ─────────────────────────────

    private class Line(val rec: Recording, val seg: Segment) {
        val at: LocalDateTime = rec.start.plusNanos((seg.start * 1e9).toLong())
        val endAt: LocalDateTime = rec.start.plusNanos((seg.end * 1e9).toLong())
    }
    private class Para(val lines: MutableList<Line>, val who: String?, val speakerId: String?, val groupKey: String)
    private class Event(val paras: List<Para>, val place: Place?) {
        val start get() = paras.first().lines.first().at
        val end get() = paras.last().lines.last().endAt
        val people get() = paras.mapNotNull { it.who }.distinct()
    }

    /**
     * 文字按"事件"分组：一段连续的对话（中间安静不超过 EVENT_GAP_S）算一件事，
     * 卡片顶上写 时间 · 地点 · 人物。事件里同一个人、停顿不到 PARA_GAP_S 秒的几句合成一段。
     */
    private fun buildEvents(recs: List<Recording>): List<Event> {
        val lines = recs.flatMap { r ->
            Transcripts.get(this, r.name)?.segments?.filter { it.text.isNotBlank() }?.map { Line(r, it) } ?: emptyList()
        }.sortedBy { it.at }
        val groups = ArrayList<MutableList<Line>>()
        for (l in lines) {
            val g = groups.lastOrNull()
            if (g != null && Duration.between(g.last().endAt, l.at).seconds <= EVENT_GAP_S &&
                g.first().at.toLocalDate() == l.at.toLocalDate()) g.add(l)
            else groups.add(mutableListOf(l))
        }
        return groups.map { g ->
            val otherKeys = g.mapNotNull { l ->
                l.seg.speaker?.takeIf { it.startsWith("other_") }?.let { "${l.rec.name}:$it" }
            }.distinct()
            val hasKnownVoiceprint = g.any { l ->
                val raw = l.seg.speaker
                raw != null && !raw.startsWith("other_") && Speakers.resolve(this, raw) != null
            }
            val paras = ArrayList<Para>()
            for (l in g) {
                val raw = l.seg.speaker
                val sp = if (raw != null && !raw.startsWith("other_")) Speakers.resolve(this, raw) else null
                val (who, spkId, key) = when {
                    sp != null -> Triple(sp.name ?: sp.defaultName, sp.id, sp.id)
                    raw != null && raw.startsWith("other_") -> {
                        val k = "${l.rec.name}:$raw"
                        when {
                            hasKnownVoiceprint -> {
                                val label = if (otherKeys.size > 1) "其他人 ${otherKeys.indexOf(k) + 1}" else "其他人"
                                Triple(label, null, k)
                            }
                            otherKeys.size > 1 -> Triple("对话人 ${otherKeys.indexOf(k) + 1}", null, k)
                            else -> Triple(null, null, k)
                        }
                    }
                    else -> Triple(null, null, "unknown")
                }
                val last = paras.lastOrNull()
                val gap = last?.let { Duration.between(it.lines.last().endAt, l.at).toMillis() / 1000.0 }
                if (last != null && last.groupKey == key && gap != null && gap < PARA_GAP_S) last.lines.add(l)
                else paras.add(Para(mutableListOf(l), who, spkId, key))
            }
            Event(paras, Places.at(this, g.first().at))
        }
    }

    @Composable
    private fun EventsPage(recs: List<Recording>) {
        @Suppress("UNUSED_EXPRESSION") dataVersion
        @Suppress("UNUSED_EXPRESSION") configVersion
        val events = remember(recs, dataVersion) { buildEvents(recs) }
        val byDay = events.groupBy { it.start.toLocalDate() }.toSortedMap(compareByDescending { it })
        val emptyBody = if (Config.mode(this@MainActivity) == WorkMode.DIRECT_ASR) {
            "开始录音后，手机会自动调用直连 ASR 生成文字。"
        } else {
            "录音加密上传后，电脑端转写完会自动同步到这里。"
        }
        PageList {
            if (events.isEmpty()) item { Empty(Icons.Rounded.Notes, "还没有文字", emptyBody) }
            byDay.forEach { (day, evs) ->
                item(key = "d$day") { DayHeader(dayLabel(day)) }
                items(evs.sortedByDescending { it.start }, key = { "e" + it.paras.first().lines.first().let { l -> l.rec.name + l.seg.start } }) { ev ->
                    EventCard(ev)
                }
            }
        }
    }

    @Composable
    private fun EventCard(ev: Event) {
        val playingName = player.current?.name
        val pos = player.positionMs / 1000.0
        val first = ev.paras.first().lines.first()
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val sameMinute = ev.start.format(HM) == ev.end.format(HM)
                    Text(if (sameMinute) ev.start.format(HM) else "${ev.start.format(HM)} – ${ev.end.format(HM)}",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ev.place?.let { p -> Meta(Icons.Rounded.Place, p.label, PlaceColor) { editingPlace = p } }
                        if (ev.people.isNotEmpty())
                            Meta(Icons.Rounded.Group, ev.people.joinToString("、"), MaterialTheme.colorScheme.tertiary)
                    }
                }
                if (first.rec.hasAudio) {
                    IconButton(onClick = {
                        if (player.current?.name != first.rec.name) player.open(first.rec)
                        player.seekTo((first.seg.start * 1000).toInt())
                    }) { Icon(Icons.Rounded.PlayArrow, "从这里播放", tint = MaterialTheme.colorScheme.primary) }
                }
            }
            Spacer(Modifier.height(10.dp))
            var prevLabel: String? = null
            // 只有整张卡片全是同一个已知人说话时才省掉段落标签；掺了其他人或未识别句子时都要标清楚，避免把别人的话算在自己头上
            val showLabels = ev.people.isNotEmpty() && !(ev.people.size == 1 && ev.paras.all { it.who != null })
            ev.paras.forEach { p ->
                val label = p.who ?: "未识别"
                if (showLabels && label != prevLabel) {
                    val m = Modifier.padding(top = 6.dp, bottom = 2.dp).clip(RoundedCornerShape(6.dp))
                    Text(
                        label,
                        if (p.speakerId != null) m.clickable { editing = Speakers.resolve(this@MainActivity, p.speakerId) } else m,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (p.speakerId != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                prevLabel = label
                // 一段里的每一句拼成一段文字，正在播放的那句高亮；点哪句从哪句播
                val text = buildAnnotatedString {
                    p.lines.forEachIndexed { idx, l ->
                        val active = l.rec.name == playingName && pos >= l.seg.start && pos < l.seg.end + 0.3
                        pushStringAnnotation("line", idx.toString())
                        if (active) pushStyle(SpanStyle(color = MaterialTheme.colorScheme.primary,
                            background = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)))
                        append(l.seg.text.trim())
                        if (active) pop()
                        pop()
                    }
                }
                @Suppress("DEPRECATION")
                androidx.compose.foundation.text.ClickableText(
                    text, Modifier.padding(bottom = 4.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface, lineHeight = 27.sp),
                ) { off ->
                    val idx = text.getStringAnnotations("line", off, off).firstOrNull()?.item?.toInt() ?: 0
                    val l = p.lines[idx]
                    if (l.rec.hasAudio) {
                        if (player.current?.name != l.rec.name) player.open(l.rec)
                        player.seekTo((l.seg.start * 1000).toInt())
                    }
                }
            }
        }
    }

    @Composable
    private fun Meta(icon: ImageVector, text: String, color: Color, onClick: (() -> Unit)? = null) {
        Row(Modifier.clip(RoundedCornerShape(6.dp)).let { if (onClick != null) it.clickable(onClick = onClick) else it },
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(15.dp), tint = color)
            Spacer(Modifier.width(3.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }

    // ───────────────────────────── 录音 ─────────────────────────────

    @Composable
    private fun RecordingsPage(recs: List<Recording>, s: Status) {
        @Suppress("UNUSED_EXPRESSION") configVersion
        val byDay = recs.groupBy { it.start.toLocalDate() }
        PageList {
            if (recs.isEmpty()) item { Empty(Icons.Rounded.GraphicEq, "还没有录音", "开始录音后，每 5 分钟会生成一段。") }
            byDay.forEach { (day, list) ->
                item(key = "h$day") { DayHeader(dayLabel(day), "共 ${fmtDuration(list.sumOf { it.durationSec })}") }
                items(list, key = { it.file.name }) { r -> RecordingCard(r, s) }
            }
        }
    }

    @Composable
    private fun RecordingCard(r: Recording, s: Status) {
        val selected = player.current?.file == r.file
        val tr = Transcripts.get(this@MainActivity, r.name)
        var wave by remember(r.file.name) { mutableStateOf(Waveforms.cached(r.file)) }
        LaunchedEffect(r.file.name) { if (wave == null && r.hasAudio) wave = Waveforms.get(r.file) }
        val place = remember(r.name, dataVersion) { Places.at(this, r.start) }
        SectionCard(highlight = selected, onClick = if (r.hasAudio) ({ if (!selected) player.open(r) }) else null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = r.hasAudio, onClick = {
                    if (selected) player.toggle() else player.open(r)
                }) { Icon(if (selected && player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "播放",
                    tint = if (r.hasAudio) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(r.start.format(T), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(" – ${r.end.format(T)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text(fmtDuration(r.durationSec), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(statusLabel(r, tr, s))
                        place?.let { Meta(Icons.Rounded.Place, it.label, PlaceColor) }
                    }
                }
            }
            if (r.hasAudio) {
                Spacer(Modifier.height(10.dp))
                Waveform(wave, Modifier.fillMaxWidth().height(28.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
            }
            if (tr != null && tr.text.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(tr.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun StatusPill(text: String) {
        Text(text, Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    // ───────────────────────────── 播放器 ─────────────────────────────

    @Composable
    private fun PlayerBar() {
        val r = player.current ?: return
        var dragging by remember { mutableStateOf<Float?>(null) }
        var showText by remember { mutableStateOf(false) }
        Surface(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), shadowElevation = 16.dp,
            color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 10.dp)) {
                val dur = player.durationMs.coerceAtLeast(1)
                val shown = dragging?.let { (it * dur).toInt() } ?: player.positionMs
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${dayLabel(r.start.toLocalDate())} ${r.start.format(T)} 的录音",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("正在播放 " + r.start.plusSeconds((shown / 1000).toLong()).format(T),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (Transcripts.get(this@MainActivity, r.name)?.segments?.isNotEmpty() == true) {
                        IconToggleButton(checked = showText, onCheckedChange = { showText = it }) {
                            Icon(Icons.Rounded.Subtitles, "显示文字",
                                tint = if (showText) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { player.close() }) { Icon(Icons.Rounded.Close, "关闭") }
                }
                if (showText) TranscriptPane(r)
                Spacer(Modifier.height(6.dp))
                var wave by remember(r.file.name) { mutableStateOf(Waveforms.cached(r.file)) }
                LaunchedEffect(r.file.name) { if (wave == null) wave = Waveforms.get(r.file) }
                Box(Modifier.padding(end = 8.dp)) {
                    Waveform(
                        wave, Modifier.fillMaxWidth().height(52.dp),
                        progress = dragging ?: (player.positionMs.toFloat() / dur),
                        color = MaterialTheme.colorScheme.outlineVariant,
                        playedColor = MaterialTheme.colorScheme.primary,
                        onSeekPreview = { dragging = it },
                        onSeek = { player.seekTo((it * dur).toInt()); dragging = null },
                    )
                }
                Row(Modifier.fillMaxWidth().padding(end = 8.dp, top = 2.dp)) {
                    Text(fmtMs(shown), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(fmtMs(player.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { player.skip(-10_000) }, Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.Replay10, "后退 10 秒", Modifier.size(30.dp))
                    }
                    Spacer(Modifier.width(28.dp))
                    FilledIconButton(onClick = { player.toggle() }, Modifier.size(64.dp)) {
                        Icon(if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (player.playing) "暂停" else "播放", Modifier.size(36.dp))
                    }
                    Spacer(Modifier.width(28.dp))
                    IconButton(onClick = { player.skip(10_000) }, Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.Forward10, "前进 10 秒", Modifier.size(30.dp))
                    }
                }
            }
        }
    }

    /** 播放器里的逐句文字：正在播放的那句高亮，点一下跳过去 */
    @Composable
    private fun TranscriptPane(r: Recording) {
        val tr = Transcripts.get(this, r.name) ?: return
        val pos = player.positionMs / 1000.0
        val listState = rememberLazyListState()
        val cur = tr.segments.indexOfLast { it.start <= pos + 0.2 }
        LaunchedEffect(cur) { if (cur >= 0) listState.animateScrollToItem(maxOf(0, cur - 1)) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 150.dp).padding(top = 6.dp, end = 8.dp), state = listState) {
            items(tr.segments.size) { i ->
                val seg = tr.segments[i]
                val active = i == cur
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                    .clickable { player.seekTo((seg.start * 1000).toInt()) }
                    .padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text(r.start.plusSeconds(seg.start.toLong()).format(T),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(60.dp).padding(top = 2.dp))
                    Text(seg.text, style = MaterialTheme.typography.bodyMedium,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }

    // ───────────────────────────── 人物 ─────────────────────────────

    /** 每个人（合并后）说了几句，以及他说得最长的一句（手机上还有录音的），用来"听一下" */
    private class PersonStats(val count: Int, val clip: Pair<Recording, Segment>?, val sample: String?)

    private fun personStats(recs: List<Recording>): Map<String, PersonStats> {
        val count = HashMap<String, Int>()
        val clip = HashMap<String, Pair<Recording, Segment>>()
        val sample = HashMap<String, String>()
        recs.forEach { r -> Transcripts.get(this, r.name)?.segments?.forEach { seg ->
            val who = Speakers.resolve(this, seg.speaker)?.id ?: return@forEach
            if (seg.text.isBlank()) return@forEach
            count[who] = (count[who] ?: 0) + 1
            if ((sample[who]?.length ?: 0) < seg.text.length) sample[who] = seg.text
            if (r.hasAudio) {
                val old = clip[who]?.second
                if (old == null || seg.end - seg.start > old.end - old.start) clip[who] = r to seg
            }
        } }
        return count.keys.associateWith { PersonStats(count[it] ?: 0, clip[it], sample[it]) }
    }

    private fun playClip(c: Pair<Recording, Segment>) =
        player.playRange(c.first, (c.second.start * 1000).toInt(), (c.second.end * 1000).toInt() + 300)

    private fun visiblePeople(stats: Map<String, PersonStats>) =
        Speakers.all(this).values.filter { it.mergedInto == null && ((stats[it.id]?.count ?: 0) > 0 || it.name != null) }
            .sortedWith(compareBy<Speaker>({ it.name == null }, { -(stats[it.id]?.count ?: 0) }))

    @Composable
    private fun PeoplePage(recs: List<Recording>) {
        @Suppress("UNUSED_EXPRESSION") dataVersion
        val all = Speakers.all(this)
        val scope = rememberCoroutineScope()
        val stats = remember(recs, dataVersion) { personStats(recs) }
        val people = visiblePeople(stats)
        PageList {
            if (people.isEmpty()) item { Empty(Icons.Rounded.Group, "还没有认出任何人", "转写结果里出现的人会列在这里，你可以给他们起名字。") }
            else item {
                Hint("同一个人被分成了好几个？先点「听一下」确认，再从右上角菜单合并。")
            }
            items(people, key = { it.id }) { p ->
                val st = stats[p.id]
                val aliases = all.values.filter { it.mergedInto != null && Speakers.resolve(this@MainActivity, it.id)?.id == p.id }
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p.name?.take(1) ?: "?", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name ?: p.defaultName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                color = if (p.name == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            Text("说过 ${st?.count ?: 0} 句" + if (aliases.isNotEmpty()) " · 已合并 ${aliases.size} 个" else "",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // 合并和拆开不常用，收进菜单，卡片上只留听和改名
                        if (people.size > 1 || aliases.isNotEmpty()) {
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(Icons.Rounded.MoreVert, "管理", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                    if (people.size > 1) DropdownMenuItem(text = { Text("合并到…") },
                                        leadingIcon = { Icon(Icons.Rounded.MergeType, null) },
                                        onClick = { menu = false; merging = p })
                                    aliases.forEach { a ->
                                        DropdownMenuItem(text = { Text("拆开 ${a.name ?: a.defaultName}") },
                                            leadingIcon = { Icon(Icons.Rounded.CallSplit, null) },
                                            onClick = {
                                                menu = false
                                                scope.launch(Dispatchers.IO) { Speakers.edit(this@MainActivity, a.id, unmerge = true); dataVersion++ }
                                            })
                                    }
                                }
                            }
                        }
                    }
                    (st?.sample ?: p.sample)?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("「$it」", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        st?.clip?.let { c -> ActionChip(Icons.Rounded.PlayArrow, "听一下") { playClip(c) } }
                        ActionChip(Icons.Rounded.Edit, "改名") { editing = p }
                    }
                }
            }
        }
    }

    /** 改名字 / 合并 的对话框，文字页和人物页共用 */
    @Composable
    private fun PersonDialogs(recs: List<Recording>) {
        val scope = rememberCoroutineScope()
        editing?.let { p ->
            var text by remember(p.id) { mutableStateOf(p.name ?: "") }
            val clip = personStats(recs)[p.id]?.clip
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text("给 ${p.name ?: p.defaultName} 起名字") },
                text = {
                    Column {
                        OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("比如：我、老王、妈妈") })
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            clip?.let { c -> ActionChip(Icons.Rounded.PlayArrow, "听一下") { playClip(c) } }
                            ActionChip(Icons.Rounded.MergeType, "其实是别人") { merging = p; editing = null }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = {
                    scope.launch(Dispatchers.IO) { Speakers.edit(this@MainActivity, p.id, name = text); dataVersion++ }
                    editing = null }) { Text("保存") } },
                dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
            )
        }
        merging?.let { p ->
            val stats = personStats(recs)
            val others = visiblePeople(stats).filter { it.id != p.id }
            AlertDialog(
                onDismissRequest = { merging = null },
                title = { Text("${p.name ?: p.defaultName} 其实是谁？") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        item {
                            Text("点名字合并，以后两种声音都认成同一个人。合并后还能拆开。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(others, key = { it.id }) { t ->
                            val st = stats[t.id]
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable {
                                    scope.launch(Dispatchers.IO) { Speakers.edit(this@MainActivity, p.id, mergeInto = t.id); dataVersion++ }
                                    merging = null
                                }.padding(8.dp)) {
                                    Text(t.name ?: t.defaultName, style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                    st?.sample?.let { Text("「$it」", style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                st?.clip?.let { c -> IconButton(onClick = { playClip(c) }) { Icon(Icons.Rounded.PlayArrow, "听一下") } }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { merging = null }) { Text("取消") } },
            )
        }
    }

    // ───────────────────────────── 地点 ─────────────────────────────

    @Composable
    private fun PlacesPage() {
        @Suppress("UNUSED_EXPRESSION") dataVersion
        val all = Places.all(this)
        val stats = remember(dataVersion) { Places.stats(this) }
        val places = all.filter { it.mergedInto == null }
            .sortedByDescending { stats[it.id]?.last }   // 最近去过的在前
        PageList {
            if (!hasLocation) item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.LocationOff, null, tint = PlaceColor)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("定位没有开启", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("开启后，录音时每 5 分钟记一次位置，自动显示地名。位置记录只存在这台手机上，不上传到 Hark 信箱（地名解析由系统定位服务完成）。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { requestLocation() }) { Text("开启定位") }
                }
            }
            if (places.isEmpty() && hasLocation) item {
                Empty(Icons.Rounded.Place, "还没有记录到地点", "录音时每 5 分钟记一次位置，自动显示地名。")
            }
            if (places.isNotEmpty()) item { Hint("地名是自动查的，200 米以内算同一个地方。想叫得顺口（比如「家」「公司」）可以改名，不改也行。") }
            items(places, key = { it.id }) { p ->
                val st = stats[p.id]
                val aliases = all.filter { it.mergedInto != null && Places.resolve(this@MainActivity, it.id)?.id == p.id }
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(PlaceColor.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Place, null, tint = PlaceColor)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (p.name != null && p.auto != null) Text(p.auto, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            p.area?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text(buildString {
                                if (st != null) {
                                    append("去过 ${st.days} 天 · 约 ${fmtMinutes(st.minutes)}")
                                    st.last?.let { append(" · 最近 ${it.format(MDHM)}") }
                                }
                                if (aliases.isNotEmpty()) append(" · 已合并 ${aliases.size} 个")
                            }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionChip(Icons.Rounded.Edit, "改名") { editingPlace = p }
                        if (places.size > 1) ActionChip(Icons.Rounded.MergeType, "合并") { mergingPlace = p }
                        aliases.forEach { a ->
                            ActionChip(Icons.Rounded.CallSplit, "拆开 ${a.label}") { Places.merge(this@MainActivity, a.id, null); dataVersion++ }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsDialog() {
        if (!editingServer) return
        val initCloud = remember { Config.rawValues(this) }
        val initAsr = remember { AsrConfig.rawValues(this) }
        var selectedMode by remember { mutableStateOf(Config.mode(this)) }

        var url by remember { mutableStateOf(initCloud.first) }
        var token by remember { mutableStateOf(initCloud.second) }
        var e2eeKey by remember { mutableStateOf(initCloud.third) }

        var provider by remember { mutableStateOf(initAsr.provider) }
        var asrUrl by remember { mutableStateOf(initAsr.baseUrl) }
        var asrKey by remember { mutableStateOf(initAsr.apiKey) }
        var asrAppId by remember { mutableStateOf(initAsr.appId) }
        var asrModel by remember { mutableStateOf(initAsr.model) }
        var showSecret by remember { mutableStateOf(false) }
        var showVolcAdv by remember { mutableStateOf(initAsr.appId.isNotBlank() || (initAsr.baseUrl.isNotBlank() && initAsr.baseUrl != AsrProvider.VOLCENGINE.defaultUrl)) }
        var probing by remember { mutableStateOf(false) }
        var probeReport by remember { mutableStateOf<AsrProbeReport?>(null) }
        val scope = rememberCoroutineScope()

        val keyErr = selectedMode == WorkMode.CLOUD && e2eeKey.isNotBlank() &&
            runCatching { Crypto.decodeKey(e2eeKey.trim()) }.isFailure
        val draftAsr = AsrConfig(provider = provider, baseUrl = asrUrl, apiKey = asrKey, appId = asrAppId, model = asrModel)
        val asrErr = if (selectedMode == WorkMode.DIRECT_ASR) draftAsr.validationError() else null

        AlertDialog(
            onDismissRequest = { editingServer = false },
            title = { Text("转写与同步设置") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        WorkMode.entries.forEachIndexed { idx, item ->
                            SegmentedButton(
                                selected = selectedMode == item,
                                onClick = { selectedMode = item; probeReport = null },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = WorkMode.entries.size),
                            ) { Text(item.label) }
                        }
                    }
                    if (selectedMode == WorkMode.CLOUD) {
                        Text(
                            "录音经 AES-256-GCM 加密后上传盲信箱，由电脑桌面端或 hark worker 拉取转写并归档到 hark.db（供本地 hark CLI 与 AI 读取）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("信箱地址") }, placeholder = { Text("https://hark.example.com") })
                        OutlinedTextField(
                            token, { token = it }, Modifier.fillMaxWidth(), singleLine = true,
                            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                            label = { Text("手机 Token (PHONE_TOKEN)") },
                        )
                        OutlinedTextField(
                            e2eeKey, { e2eeKey = it }, Modifier.fillMaxWidth(), singleLine = true, isError = keyErr,
                            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                            label = { Text("端到端密钥 (E2EE_KEY)") },
                            supportingText = {
                                Text(if (keyErr) "需要 32 字节 Base64 或 64 位 hex"
                                     else "与电脑端填同一把密钥，信箱服务器不保存它")
                            },
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionChip(Icons.Rounded.Key, "随机生成新密钥") {
                                val b = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                                e2eeKey = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
                                showSecret = true
                            }
                            ActionChip(
                                if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                if (showSecret) "隐藏密钥" else "显示密钥",
                            ) { showSecret = !showSecret }
                        }
                    } else {
                        Text(
                            "手机录完直接调用所选 ASR 在本机出字（不连信箱）；7 天内切回「云端信箱同步」时，未上云录音会自动加密补传给电脑端。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AsrProvider.entries.forEach { p ->
                                FilterChip(
                                    selected = provider == p,
                                    onClick = {
                                        val prev = provider
                                        provider = p
                                        if (asrUrl.isBlank() || asrUrl == prev.defaultUrl) asrUrl = p.defaultUrl
                                        if (asrModel.isBlank() || asrModel == prev.defaultModel) asrModel = p.defaultModel
                                        probeReport = null
                                    },
                                    label = { Text(p.shortLabel) },
                                )
                            }
                        }
                        when (provider) {
                            AsrProvider.VOLCENGINE -> {
                                OutlinedTextField(
                                    asrKey, { asrKey = it; probeReport = null }, Modifier.fillMaxWidth(), singleLine = true,
                                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                                    label = { Text("豆包语音 API Key / Access Token") },
                                    supportingText = { Text("需使用「豆包语音控制台」Key，默认自带标点顺滑与对话人区分") },
                                )
                                OutlinedTextField(
                                    asrModel, { asrModel = it; probeReport = null }, Modifier.fillMaxWidth(), singleLine = true,
                                    label = { Text("资源 ID (Resource ID)") },
                                    placeholder = { Text(AsrProvider.VOLCENGINE.defaultModel) },
                                )
                                if (showVolcAdv) {
                                    OutlinedTextField(
                                        asrAppId, { asrAppId = it; probeReport = null }, Modifier.fillMaxWidth(), singleLine = true,
                                        label = { Text("旧版 AppID（单 Key 鉴权留空）") },
                                    )
                                    OutlinedTextField(
                                        asrUrl, { asrUrl = it; probeReport = null }, Modifier.fillMaxWidth(), singleLine = true,
                                        label = { Text("接口地址") },
                                        placeholder = { Text(AsrProvider.VOLCENGINE.defaultUrl) },
                                    )
                                }
                            }
                            AsrProvider.OPENAI -> {
                                OutlinedTextField(
                                    asrUrl, { asrUrl = it; probeReport = null }, Modifier.fillMaxWidth(), singleLine = true,
                                    label = { Text("接口根地址 (Base URL)") },
                                    placeholder = { Text("https://api.openai.com/v1") },
                                )
                                OutlinedTextField(
                                    asrKey, { asrKey = it; probeReport = null }, Modifier.fillMaxWidth(), singleLine = true,
                                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                                    label = { Text("API Key（本地免密服务可留空）") },
                                )
                                OutlinedTextField(
                                    asrModel, { asrModel = it; probeReport = null }, Modifier.fillMaxWidth(), singleLine = true,
                                    label = { Text("模型名称") },
                                    placeholder = { Text("gpt-4o-mini-transcribe / whisper-large-v3-turbo") },
                                )
                            }
                            AsrProvider.HARK_HTTP -> {
                                OutlinedTextField(
                                    asrUrl, { asrUrl = it; probeReport = null }, Modifier.fillMaxWidth(), singleLine = true,
                                    label = { Text("自建服务地址") },
                                    placeholder = { Text("http://hark-asr.local:8787") },
                                    supportingText = { Text("可通过局域网或 Tailscale 直连家里的转写服务") },
                                )
                                OutlinedTextField(
                                    asrKey, { asrKey = it; probeReport = null }, Modifier.fillMaxWidth(), singleLine = true,
                                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                                    label = { Text("Bearer Token（可选）") },
                                )
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = !probing && asrErr == null,
                                onClick = {
                                    probing = true
                                    probeReport = null
                                    scope.launch {
                                        probeReport = withContext(Dispatchers.IO) {
                                            PhoneAsr.probe(this@MainActivity, draftAsr)
                                        }
                                        probing = false
                                    }
                                },
                            ) { Text(if (probing) "正在实测…" else "测试转写连接") }
                            ActionChip(
                                if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                if (showSecret) "隐藏密钥" else "显示密钥",
                            ) { showSecret = !showSecret }
                            if (provider == AsrProvider.VOLCENGINE) {
                                ActionChip(Icons.Rounded.Tune, if (showVolcAdv) "收起高级项" else "旧版 AppID") {
                                    showVolcAdv = !showVolcAdv
                                }
                            }
                        }
                        probeReport?.let { rep ->
                            Text(
                                text = if (rep.ok) {
                                    buildString {
                                        append("✓ 连通 (${rep.latencyMs}ms")
                                        if (rep.speakerCount > 0) append(" · 识别 ${rep.speakerCount} 位说话人")
                                        append(")：")
                                        append(rep.text.ifBlank { "（无文本）" }.take(60))
                                    }
                                } else {
                                    "✕ ${rep.error}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (rep.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !keyErr && asrErr == null, onClick = {
                    Config.save(this, url, token, e2eeKey)
                    AsrConfig.save(this, draftAsr)
                    Config.setMode(this, selectedMode)
                    configVersion++
                    dataVersion++
                    editingServer = false
                }) { Text("保存并启用") }
            },
            dismissButton = { TextButton(onClick = { editingServer = false }) { Text("取消") } },
        )
    }

    @Composable
    private fun PlaceDialogs() {
        editingPlace?.let { p ->
            var text by remember(p.id) { mutableStateOf(p.name ?: "") }
            AlertDialog(
                onDismissRequest = { editingPlace = null },
                title = { Text("给 ${p.label} 起名字") },
                text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text(p.auto ?: "比如：家、公司、健身房") }) },
                confirmButton = { TextButton(onClick = { Places.rename(this, p.id, text); dataVersion++; editingPlace = null }) { Text("保存") } },
                dismissButton = { TextButton(onClick = { editingPlace = null }) { Text("取消") } },
            )
        }
        mergingPlace?.let { p ->
            val others = Places.all(this).filter { it.mergedInto == null && it.id != p.id }
            AlertDialog(
                onDismissRequest = { mergingPlace = null },
                title = { Text("${p.label} 其实是哪儿？") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(others, key = { it.id }) { t ->
                            Text(t.label, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                Places.merge(this@MainActivity, p.id, t.id); dataVersion++; mergingPlace = null
                            }.padding(10.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { mergingPlace = null }) { Text("取消") } },
            )
        }
    }

    // ───────────────────────────── 通用小部件 ─────────────────────────────

    @Composable
    private fun PageList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }

    @Composable
    private fun SectionCard(highlight: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
        val fill by animateColorAsState(
            if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
            tween(220), label = "card")
        val colors = CardDefaults.cardColors(containerColor = fill)
        val body: @Composable ColumnScope.() -> Unit = { Column(Modifier.padding(16.dp), content = content) }
        if (onClick != null) Card(onClick = onClick, Modifier.fillMaxWidth(), colors = colors, content = body)
        else Card(Modifier.fillMaxWidth(), colors = colors, content = body)
    }

    @Composable
    private fun DayHeader(title: String, trailing: String? = null) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    @Composable
    private fun Hint(text: String) {
        Text(text, Modifier.padding(horizontal = 4.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    @Composable
    private fun Empty(icon: ImageVector, title: String, body: String) {
        Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun Avatar(text: String, bg: Color, fg: Color) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Text(text, color = fg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
        AssistChip(onClick = onClick, label = { Text(label) },
            leadingIcon = { Icon(icon, null, Modifier.size(AssistChipDefaults.IconSize)) })
    }

    private fun statusLabel(r: Recording, tr: Transcript?, s: Status): String {
        val mode = Config.mode(this)
        val remote = s.remote
        return when {
            tr != null -> if (tr.text.isBlank()) "已转写（无有效语音）" else "已转写"
            !r.hasAudio -> "录音已过期"
            uploader.isSilent(r.file) -> if (mode == WorkMode.DIRECT_ASR) "没有人声，已跳过" else "没有人声，未上传"
            mode == WorkMode.DIRECT_ASR -> when {
                s.localAsrRunning == r.file.name -> "手机转写中…"
                AsrConfig.load(this) == null -> "待配置直连 ASR"
                uploader.isSkippedBadAudio(r.file) -> "音频异常，已跳过本机转写"
                s.localAsrError != null -> "转写待重试"
                else -> "排队手机转写"
            }
            uploader.isUploaded(r.file) -> when {
                remote.workerOnline && remote.workerPhase == "transcribing" && remote.workerCurrent == r.file.name -> "正在转写…"
                remote.mailboxReachable == true && !remote.workerOnline -> "已加密上传 · 等电脑上线"
                else -> "排队转写中"
            }
            else -> "待加密上传"
        }
    }

    private fun workerLabel(remote: RemoteStatus): String = when {
        !remote.workerOnline -> remote.workerAgeSec?.let { "离线（上次在线 ${fmtAge(it)}前）" } ?: "尚未收到电脑端状态"
        remote.workerPhase == "paused" -> "在线 · 已暂停拉取"
        remote.asrOk == false || remote.workerPhase == "asr_down" -> "电脑端在线，但转写服务未就绪"
        remote.workerPhase == "transcribing" -> {
            val at = remote.workerCurrent?.let { Recordings.parseStart(java.io.File(it))?.format(T) } ?: "未知时间"
            "正在转写 $at 片段（已用 ${remote.workerPhaseElapsedSec.toInt()}秒）"
        }
        remote.workerPhase == "error" -> "出错：${remote.workerError ?: "未知错误"}"
        else -> "在线 · 空闲" + (remote.asrModel?.let { "（$it）" } ?: "")
    }

    private fun fmtAge(seconds: Double): String = when {
        seconds < 60 -> "${seconds.toInt()}秒"
        seconds < 3600 -> "${(seconds / 60).toInt()}分钟"
        seconds < 86400 -> "${(seconds / 3600).toInt()}小时"
        else -> "${(seconds / 86400).toInt()}天"
    }

    private fun checkGitHubUpdate(): Pair<String, String?> = try {
        val conn = java.net.URL("https://api.github.com/repos/riggedlab/hark/releases/latest").openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        if (conn.responseCode == 404) {
            "当前 v${BuildConfig.VERSION_NAME}（仓库尚未设为公开）" to null
        } else {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            val json = org.json.JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name").removePrefix("v")
            if (isNewerSemVer(tag, BuildConfig.VERSION_NAME)) {
                val assets = json.optJSONArray("assets")
                var apk: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val u = assets.getJSONObject(i).optString("browser_download_url")
                        if (u.endsWith(".apk")) { apk = u; break }
                    }
                }
                "发现新版本 v$tag（当前 v${BuildConfig.VERSION_NAME}）" to (apk ?: json.optString("html_url"))
            } else {
                "已是最新版本 v${BuildConfig.VERSION_NAME}" to null
            }
        }
    } catch (e: Exception) {
        "检查更新失败：${e.message?.take(60)}" to null
    }

    private fun isNewerSemVer(candidate: String, current: String): Boolean {
        val c1 = candidate.substringBefore("-").split(".").mapNotNull { it.toIntOrNull() }
        val c2 = current.substringBefore("-").split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(c1.size, c2.size)) {
            val a = c1.getOrElse(i) { 0 }
            val b = c2.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    companion object {
        private const val PARA_GAP_S = 3.0
        private const val EVENT_GAP_S = 300L
        private val T = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val HM = DateTimeFormatter.ofPattern("HH:mm")
        private val MD = DateTimeFormatter.ofPattern("M月d日")
        private val MDHM = DateTimeFormatter.ofPattern("M月d日 HH:mm")

        fun dayLabel(d: LocalDate): String {
            val today = LocalDate.now()
            val week = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)
            return when (d) {
                today -> "今天 · ${d.format(MD)}"
                today.minusDays(1) -> "昨天 · ${d.format(MD)}"
                else -> "${d.format(MD)} $week"
            }
        }

        /** 锂电池电压 → 大致电量。小电池放电曲线，误差 ±10% 左右；插着 USB 充电时会偏高 */
        private val BATT_CURVE = listOf(4200 to 100, 4100 to 90, 4000 to 78, 3900 to 65, 3800 to 50,
            3750 to 40, 3700 to 30, 3650 to 20, 3600 to 12, 3500 to 5, 3300 to 0)

        fun batteryPct(mv: Int): Int {
            if (mv >= BATT_CURVE.first().first) return 100
            if (mv <= BATT_CURVE.last().first) return 0
            for (i in 0 until BATT_CURVE.size - 1) {
                val (v1, p1) = BATT_CURVE[i]; val (v2, p2) = BATT_CURVE[i + 1]
                if (mv in v2..v1) return p2 + (p1 - p2) * (mv - v2) / (v1 - v2)
            }
            return 0
        }

        fun fmtDuration(sec: Double): String {
            val s = sec.toLong()
            return when {
                s >= 3600 -> "${s / 3600}小时${(s % 3600) / 60}分"
                s >= 60 -> "${s / 60}分${s % 60}秒"
                else -> "${s}秒"
            }
        }

        fun fmtMinutes(m: Int) = if (m >= 60) "${m / 60} 小时${if (m % 60 > 0) " ${m % 60} 分" else ""}" else "$m 分钟"

        fun fmtMs(ms: Int): String {
            val s = ms / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
