@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

internal enum class RootDestination { MAIN, SETTINGS }

class MainActivity : ComponentActivity() {

    private val player = Player()
    private var dataVersion by mutableIntStateOf(0)
    private var configVersion by mutableIntStateOf(0)
    private var destination by mutableStateOf(RootDestination.MAIN)
    private var editing by mutableStateOf<Speaker?>(null)
    private var merging by mutableStateOf<Speaker?>(null)
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
        Thread { Places.backfill(this) }.start()
        setContent { DitingTheme { RootHost() } }
    }

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

    private fun requestLocation() = locLauncher.launch(
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    )

    private fun startRecorder(action: String? = null) =
        startForegroundService(Intent(this, RecorderService::class.java).setAction(action))

    private fun stopRecorder() =
        startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))

    private fun isBatteryWhitelisted() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun requestBatteryWhitelist() = startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
    )

    // ───────────────────────────── 根路由与生命周期 ─────────────────────────────

    @Composable
    private fun RootHost() {
        val s by AppState.status.collectAsStateWithLifecycle()
        var recs by remember { mutableStateOf(listOf<Recording>()) }
        var whitelisted by remember { mutableStateOf(isBatteryWhitelisted()) }

        LaunchedEffect(s.textVersion, configVersion) {
            recs = withContext(Dispatchers.IO) { Recordings.list(this@MainActivity) }
            dataVersion++
        }
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
        LaunchedEffect(Unit) {
            while (true) {
                player.tick()
                delay(200)
            }
        }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle, configVersion) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val mode = Config.mode(this@MainActivity)
                    val ok = withContext(Dispatchers.IO) {
                        val (done, pending) = uploader.processPending()
                        AppState.update {
                            it.copy(
                                pendingUploads = pending,
                                uploadedThisSession = it.uploadedThisSession + done,
                            )
                        }
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

        val unuploaded = remember(dataVersion, configVersion, s.pendingUploads) { uploader.unuploadedCount() }
        val stateHolder = rememberSaveableStateHolder()
        val pager = rememberPagerState { 4 }
        var searchOpen by rememberSaveable { mutableStateOf(false) }
        var searchQuery by rememberSaveable { mutableStateOf("") }

        AnimatedContent(
            targetState = destination,
            transitionSpec = {
                if (targetState == RootDestination.SETTINGS) {
                    (slideInHorizontally(tween(200)) { it / 5 } + fadeIn(tween(180))) togetherWith
                        fadeOut(tween(120))
                } else {
                    fadeIn(tween(180)) togetherWith
                        (slideOutHorizontally(tween(160)) { it / 5 } + fadeOut(tween(120)))
                }
            },
            label = "rootNav",
        ) { screen ->
            stateHolder.SaveableStateProvider(screen.name) {
                when (screen) {
                    RootDestination.MAIN -> MainWorkspace(
                        s = s,
                        recs = recs,
                        whitelisted = whitelisted,
                        unuploaded = unuploaded,
                        pager = pager,
                        searchOpen = searchOpen,
                        searchQuery = searchQuery,
                        onSearchOpenChange = { open ->
                            searchOpen = open
                            if (!open) searchQuery = ""
                        },
                        onSearchQueryChange = { searchQuery = it },
                    )
                    RootDestination.SETTINGS -> SettingsScreen(
                        status = s,
                        whitelisted = whitelisted,
                        hasLocation = hasLocation,
                        unuploadedCount = unuploaded,
                        onBack = { destination = RootDestination.MAIN },
                        onConfigChanged = {
                            configVersion++
                            dataVersion++
                        },
                        onRepairBle = { requestStart(repair = true) },
                        onRequestBatteryWhitelist = { requestBatteryWhitelist() },
                        onRequestLocation = { requestLocation() },
                        onCheckGitHubUpdate = { withContext(Dispatchers.IO) { checkGitHubUpdate() } },
                        onOpenUrl = { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                    )
                }
            }
        }
    }

    // ───────────────────────────── 主工作台 ─────────────────────────────

    @Composable
    private fun MainWorkspace(
        s: Status,
        recs: List<Recording>,
        whitelisted: Boolean,
        unuploaded: Int,
        pager: androidx.compose.foundation.pager.PagerState,
        searchOpen: Boolean,
        searchQuery: String,
        onSearchOpenChange: (Boolean) -> Unit,
        onSearchQueryChange: (String) -> Unit,
    ) {
        @Suppress("UNUSED_EXPRESSION") configVersion
        val mode = Config.mode(this)
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val clipboard = LocalClipboardManager.current
        val haptic = LocalHapticFeedback.current

        var showTelemetry by remember { mutableStateOf(false) }

        val tabs = listOf("文字", "录音", "人物", "地点")

        val needsConfig = if (mode == WorkMode.DIRECT_ASR) {
            AsrConfig.load(this) == null
        } else {
            Config.load(this) == null
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                HeaderBar(
                    s = s,
                    mode = mode,
                    needsConfig = needsConfig,
                    searchOpen = searchOpen,
                    onOpenTelemetry = { showTelemetry = true },
                    onToggleSearch = { onSearchOpenChange(!searchOpen) },
                    onOpenSettings = { destination = RootDestination.SETTINGS },
                )
            },
            bottomBar = { if (player.current != null) TapePlayerDeck() },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                // 顶部常驻精密磁带走带舱（固定高度基准，切换状态零跳动）
                RecorderDeckCard(
                    s = s,
                    mode = mode,
                    needsConfig = needsConfig,
                    onStart = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        requestStart()
                    },
                    onStop = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        stopRecorder()
                    },
                    onRepair = { requestStart(repair = true) },
                    onOpenSettings = { destination = RootDestination.SETTINGS },
                )

                AnimatedVisibility(visible = searchOpen) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        placeholder = { Text("搜索对话文字、人物或地点…") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchQueryChange("") }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "清空搜索")
                                }
                            }
                        },
                    )
                }

                PrimaryTabRow(
                    selectedTabIndex = pager.currentPage,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
                ) {
                    tabs.forEachIndexed { i, title ->
                        Tab(
                            selected = pager.currentPage == i,
                            onClick = { scope.launch { pager.animateScrollToPage(i) } },
                            text = {
                                Text(
                                    title,
                                    fontWeight = if (pager.currentPage == i) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                    when (page) {
                        0 -> EventsPage(
                            recs = recs,
                            query = searchQuery,
                            onCopyEvent = { text ->
                                clipboard.setText(AnnotatedString(text))
                                scope.launch {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    snackbarHostState.showSnackbar("已复制对话文字", duration = SnackbarDuration.Short)
                                }
                            },
                            onOpenSettings = { destination = RootDestination.SETTINGS },
                        )
                        1 -> RecordingsPage(recs = recs, s = s, query = searchQuery)
                        2 -> PeoplePage(recs = recs)
                        else -> PlacesPage()
                    }
                }
            }
        }

        // 实时遥测抽屉：强制 skipPartiallyExpanded = true，点开即一屏完整展开，绝不半截折叠
        if (showTelemetry) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showTelemetry = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                TelemetrySheet(
                    s = s,
                    mode = mode,
                    whitelisted = whitelisted,
                    unuploaded = unuploaded,
                    onStart = { requestStart(); showTelemetry = false },
                    onStop = { stopRecorder(); showTelemetry = false },
                    onOpenSettings = {
                        showTelemetry = false
                        destination = RootDestination.SETTINGS
                    },
                )
            }
        }

        PersonDialogs(recs)
        PlaceDialogs()
    }

    // ───────────────────────────── 顶栏与走带舱 ─────────────────────────────

    @Composable
    private fun HeaderBar(
        s: Status,
        mode: WorkMode,
        needsConfig: Boolean,
        searchOpen: Boolean,
        onOpenTelemetry: () -> Unit,
        onToggleSearch: () -> Unit,
        onOpenSettings: () -> Unit,
    ) {
        val deck = DeckTheme.colors
        val remote = s.remote
        val hasWarning = if (mode == WorkMode.DIRECT_ASR) {
            needsConfig || s.localAsrError != null
        } else {
            Config.load(this) != null && (
                remote.mailboxReachable == false ||
                    (remote.inboxCount > 0 && !remote.workerOnline) ||
                    remote.asrOk == false
                )
        }
        val transcribing = if (mode == WorkMode.DIRECT_ASR) {
            s.localAsrRunning != null
        } else {
            remote.workerOnline && remote.workerPhase == "transcribing"
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Hark · 谛听",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))

            // 1. 设备状态胶囊（带下拉指示，明确可点开查看详情）
            Surface(
                onClick = onOpenTelemetry,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    val dotColor = when {
                        s.link == LinkState.STREAMING -> deck.signalRed
                        hasWarning -> deck.ledAmber
                        else -> MaterialTheme.colorScheme.outline
                    }
                    StatusDot(color = dotColor, breathing = s.link == LinkState.STREAMING)
                    Text(
                        text = if (transcribing) "转写中" else shortLabel(s.link),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    s.batteryMv?.let { mv ->
                        Text(
                            "${batteryPct(mv)}%",
                            style = MaterialTheme.typography.labelSmall.merge(TabularMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 2. 搜索按钮
            if (searchOpen) {
                FilledTonalIconButton(onClick = onToggleSearch, modifier = Modifier.size(38.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "收起搜索",
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                IconButton(onClick = onToggleSearch) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "搜索记录",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 3. 独立设置按钮（标准齿轮图标，不再与筛选混淆）
            IconButton(onClick = onOpenSettings) {
                BadgedBox(
                    badge = {
                        if (needsConfig) {
                            Badge(containerColor = deck.signalRed)
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "设置",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }

    private fun shortLabel(l: LinkState) = when (l) {
        LinkState.STOPPED -> "未录音"
        LinkState.SCANNING -> "寻机中"
        LinkState.CONNECTING -> "连接中"
        LinkState.PAIRING -> "配对中"
        LinkState.PAIRING_REQUIRED -> "需重配"
        LinkState.STREAMING -> "录音中"
        LinkState.RECONNECTING -> "重连中"
    }

    /**
     * 常驻磁带走带控制舱（Braun / TP-7 轻机械质感）：
     * - 严格锁定最小高度 72.dp，待机/连接/走带切换时下方列表零抖动
     * - 走带态自带等宽跳秒计数器、微缩双轴磁带轮、内凹 VU 声平表与一键停止键
     */
    @Composable
    private fun RecorderDeckCard(
        s: Status,
        mode: WorkMode,
        needsConfig: Boolean,
        onStart: () -> Unit,
        onStop: () -> Unit,
        onRepair: () -> Unit,
        onOpenSettings: () -> Unit,
    ) {
        val deck = DeckTheme.colors
        val colors = MaterialTheme.colorScheme
        var now by remember { mutableLongStateOf(System.nanoTime()) }
        val streaming = s.link == LinkState.STREAMING
        val written = streaming && s.lastWriteNanos != 0L && now - s.lastWriteNanos < 2_000_000_000L
        val levels = remember { mutableStateListOf<Float>() }
        val level by rememberUpdatedState(if (written) s.liveLevel ?: 0f else 0f)

        LaunchedEffect(streaming) {
            if (!streaming) {
                levels.clear()
                return@LaunchedEffect
            }
            while (true) {
                now = System.nanoTime()
                levels.add(level)
                if (levels.size > 64) levels.removeAt(0)
                delay(100)
            }
        }

        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceContainerLowest),
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 68.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    s.link == LinkState.STOPPED -> {
                        TapeReelsGraphic(spinning = false)
                        Spacer(Modifier.width(12.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .clickable(onClick = onOpenSettings),
                        ) {
                            val title = when {
                                needsConfig -> "待配置${mode.label}"
                                mode == WorkMode.DIRECT_ASR -> {
                                    val p = AsrConfig.load(this@MainActivity)?.provider?.shortLabel ?: "直连服务"
                                    "手机直连 · $p"
                                }
                                else -> "云端信箱 · 端到端加密"
                            }
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (needsConfig) deck.ledAmber else colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (needsConfig) "点此填写凭据后开始录音" else "点此切换转写路径",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = onStart,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(deck.signalRed))
                            Spacer(Modifier.width(8.dp))
                            Text("开始录音")
                        }
                    }

                    streaming -> {
                        // 一体化内凹磁带视窗：双盘磁带仓 + 等宽计时器 + 声平表同处于一个机械视窗内，不碎片化抢视线
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(deck.tapeWell)
                                .border(1.dp, deck.tapeGroove, MaterialTheme.shapes.small)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TapeReelsGraphic(spinning = written)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = fmtMs((s.currentChunkSeconds * 1000).toInt()),
                                style = MaterialTheme.typography.titleSmall.merge(TabularMono),
                                color = if (written) colors.onSurface else deck.ledAmber,
                            )
                            Spacer(Modifier.width(10.dp))
                            Box(
                                Modifier
                                    .width(1.dp)
                                    .height(18.dp)
                                    .background(deck.tapeGroove),
                            )
                            Spacer(Modifier.width(10.dp))
                            if (!written) {
                                Text(
                                    "等待设备音频流…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = deck.ledAmber,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                )
                            } else if (s.liveUnavailable) {
                                Text(
                                    "蓝牙音频写入中",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                LevelWave(
                                    levels = levels,
                                    color = colors.onSurface,
                                    peakColor = deck.signalRed,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        Spacer(Modifier.width(10.dp))
                        FilledTonalIconButton(
                            onClick = onStop,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = "停止录音", tint = deck.signalRed)
                        }
                    }

                    else -> {
                        val problem = s.link == LinkState.PAIRING_REQUIRED
                        StatusDot(
                            color = if (problem) colors.error else deck.ledAmber,
                            breathing = !problem,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = s.message ?: s.link.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (problem) colors.error else colors.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        if (problem) {
                            FilledTonalButton(onClick = onRepair) { Text("重新配对") }
                        } else {
                            OutlinedButton(onClick = onStop) { Text("停止") }
                        }
                    }
                }
            }
        }
    }

    /** 微缩磁带双轴视窗徽章：带外框与内轴孔，不再像悬空小钟表 */
    @Composable
    private fun TapeReelsGraphic(spinning: Boolean) {
        val transition = rememberInfiniteTransition(label = "reels")
        val deg by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
            label = "reelAngle",
        )
        val angle = if (spinning) deg else 0f
        val strokeColor = MaterialTheme.colorScheme.onSurfaceVariant
        val frameColor = DeckTheme.colors.tapeGroove
        Canvas(Modifier.width(38.dp).height(20.dp)) {
            val cr = 4.dp.toPx()
            drawRoundRect(
                color = frameColor.copy(alpha = 0.45f),
                size = size,
                cornerRadius = CornerRadius(cr, cr),
            )
            drawRoundRect(
                color = strokeColor.copy(alpha = 0.32f),
                size = size,
                cornerRadius = CornerRadius(cr, cr),
                style = Stroke(width = 1.dp.toPx()),
            )
            val r = 5.5.dp.toPx()
            val cy = size.height / 2f
            val c1 = Offset(9.5.dp.toPx(), cy)
            val c2 = Offset(size.width - 9.5.dp.toPx(), cy)
            drawLine(
                color = strokeColor.copy(alpha = 0.5f),
                start = Offset(c1.x, cy - r),
                end = Offset(c2.x, cy - r),
                strokeWidth = 1.dp.toPx(),
            )
            for (c in listOf(c1, c2)) {
                drawCircle(color = strokeColor, radius = r, center = c, style = Stroke(width = 1.2.dp.toPx()))
                drawCircle(color = strokeColor, radius = 1.6.dp.toPx(), center = c)
                rotate(degrees = angle, pivot = c) {
                    for (spoke in listOf(0f, 120f, 240f)) {
                        rotate(degrees = spoke, pivot = c) {
                            drawLine(
                                color = strokeColor,
                                start = Offset(c.x, c.y - 2.dp.toPx()),
                                end = Offset(c.x, c.y - r + 0.8.dp.toPx()),
                                strokeWidth = 1.1.dp.toPx(),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusDot(color: Color, breathing: Boolean) {
        val shown by animateColorAsState(color, tween(240), label = "dot")
        val pulse by rememberInfiniteTransition(label = "dotPulse").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
            label = "dotAlpha",
        )
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(shown.copy(alpha = if (breathing) pulse else 1f)),
        )
    }

    @Composable
    private fun LevelWave(
        levels: List<Float>,
        color: Color,
        peakColor: Color,
        modifier: Modifier,
    ) {
        Canvas(modifier.height(24.dp)) {
            val bar = 2.4.dp.toPx()
            val step = bar + 2.dp.toPx()
            val count = (size.width / step).toInt().coerceAtLeast(1)
            val recent = levels.takeLast(count).asReversed()
            // 全宽精密电平刻度底槽：无信号槽位保留微弱机械刻度点，有信号槽位点亮
            for (i in 0 until count) {
                val x = size.width - bar - i * step
                val v = recent.getOrNull(i)?.coerceIn(0f, 1f)
                if (v == null || v < 0.03f) {
                    val idleH = 2.dp.toPx()
                    drawRoundRect(
                        color = color.copy(alpha = 0.14f),
                        topLeft = Offset(x, (size.height - idleH) / 2f),
                        size = Size(bar, idleH),
                        cornerRadius = CornerRadius(bar / 2f),
                    )
                } else {
                    val h = (v * size.height).coerceAtLeast(3.dp.toPx())
                    val base = if (v > 0.78f) peakColor else color
                    drawRoundRect(
                        color = base.copy(alpha = (1f - 0.68f * i / count).coerceIn(0.24f, 1f)),
                        topLeft = Offset(x, (size.height - h) / 2f),
                        size = Size(bar, h),
                        cornerRadius = CornerRadius(bar / 2f),
                    )
                }
            }
        }
    }

    // ───────────────────────────── 一屏紧凑遥测抽屉（不折叠、免滚动） ─────────────────────────────

    @Composable
    private fun TelemetrySheet(
        s: Status,
        mode: WorkMode,
        whitelisted: Boolean,
        unuploaded: Int,
        onStart: () -> Unit,
        onStop: () -> Unit,
        onOpenSettings: () -> Unit,
    ) {
        val colors = MaterialTheme.colorScheme
        var showTech by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("设备状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "${s.deviceName ?: "Hark DT-1"} · ${mode.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = onOpenSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("设置")
                }
            }

            s.message?.let {
                Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TelemetryMetricTile(
                    label = "蓝牙连接",
                    value = s.link.label,
                    sub = if (s.link == LinkState.STREAMING) "已录 ${fmtMs((s.currentChunkSeconds * 1000).toInt())}" else "随时可开始",
                    modifier = Modifier.weight(1f),
                )
                TelemetryMetricTile(
                    label = "设备电量",
                    value = s.batteryMv?.let { "${batteryPct(it)}%" } ?: "—",
                    sub = if (s.batteryMv != null) "蓝牙实时回传" else "连接设备后显示",
                    modifier = Modifier.weight(1f),
                )
            }

            val syncVal: String
            val syncSub: String
            if (mode == WorkMode.DIRECT_ASR) {
                val cfg = AsrConfig.load(this@MainActivity)
                syncVal = when {
                    cfg == null -> "未配置服务商"
                    s.localAsrRunning != null -> "正在转写…"
                    s.localAsrError != null -> "转写待重试"
                    s.pendingUploads > 0 -> "待转写 ${s.pendingUploads} 段"
                    else -> "已全部转写"
                }
                syncSub = cfg?.let { "${it.provider.shortLabel}（已配置）" }
                    ?: (if (unuploaded > 0) "$unuploaded 段待同步" else "点右上设置填写")
            } else {
                val remote = s.remote
                syncVal = when (remote.mailboxReachable) {
                    true -> if (remote.workerOnline) "电脑端在线" else "信箱已连通"
                    false -> "信箱未连通"
                    null -> "正在连接…"
                }
                syncSub = when {
                    s.pendingUploads > 0 -> "待上传 ${s.pendingUploads} 段"
                    remote.inboxCount > 0 -> "待电脑取走 ${remote.inboxCount} 段"
                    else -> workerShortLabel(remote)
                }
            }
            TelemetryMetricTile(
                label = if (mode == WorkMode.DIRECT_ASR) "手机直连转写" else "云端信箱同步",
                value = syncVal,
                sub = syncSub,
                modifier = Modifier.fillMaxWidth(),
            )

            if (s.link == LinkState.STREAMING) {
                val lossPct = if (s.totalPackets > 0) s.lostPackets * 100.0 / (s.totalPackets + s.lostPackets) else 0.0
                val all = s.totalPackets + s.lostPackets + s.silentFrames
                val silentPct = if (s.silentFrames > 0 && all > 0) s.silentFrames * 100.0 / all else 0.0
                Text(
                    text = if (showTech) {
                        "链路诊断：码率 %.1f KB/s · 丢包 %.2f%% · 静音省略 %.0f%%".format(s.bytesPerSec / 1000.0, lossPct, silentPct) +
                            (s.batteryMv?.let { " · %.2fV".format(it / 1000.0) } ?: "")
                    } else {
                        "查看链路诊断参数"
                    },
                    style = MaterialTheme.typography.labelSmall.merge(TabularMono),
                    color = colors.onSurfaceVariant,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.extraSmall)
                        .clickable { showTech = !showTech }
                        .padding(vertical = 2.dp),
                )
            }

            if (!whitelisted) {
                Surface(
                    onClick = { requestBatteryWhitelist() },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.BatteryAlert, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("建议开启后台不受限运行，防止锁屏断连", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text("去开启", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s.link == LinkState.STOPPED) {
                    Button(onClick = onStart, Modifier.fillMaxWidth()) { Text("开始录音") }
                } else {
                    OutlinedButton(onClick = onStop, Modifier.fillMaxWidth()) { Text("停止录音") }
                }
            }
        }
    }

    @Composable
    private fun TelemetryMetricTile(
        label: String,
        value: String,
        sub: String,
        modifier: Modifier = Modifier,
    ) {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.small,
            color = DeckTheme.colors.tapeWell,
            border = BorderStroke(1.dp, DeckTheme.colors.tapeGroove),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                Text(
                    value,
                    style = MaterialTheme.typography.titleSmall.merge(TabularMono),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall.merge(TabularMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    // ───────────────────────────── Tab 1：文字（按事件时间线） ─────────────────────────────

    private class Line(val rec: Recording, val seg: Segment) {
        val at: LocalDateTime = rec.start.plusNanos((seg.start * 1e9).toLong())
        val endAt: LocalDateTime = rec.start.plusNanos((seg.end * 1e9).toLong())
    }

    private class Para(val lines: MutableList<Line>, val who: String?, val speakerId: String?, val groupKey: String) {
        val text: String get() = lines.joinToString("") { it.seg.text.trim() }
    }

    private class Event(val paras: List<Para>, val place: Place?) {
        val start: LocalDateTime get() = paras.first().lines.first().at
        val end: LocalDateTime get() = paras.last().lines.last().endAt
        val people: List<String> get() = paras.mapNotNull { it.who }.distinct()

        fun formatForCopy(): String = buildString {
            val timeStr = if (start.format(HM) == end.format(HM)) start.format(HM) else "${start.format(HM)}–${end.format(HM)}"
            append("[").append(start.toLocalDate().format(MD)).append(" ").append(timeStr)
            place?.let { append(" · ").append(it.label) }
            append("]\n")
            for (p in paras) {
                if (p.who != null) append(p.who).append("：")
                append(p.text).append("\n")
            }
        }.trimEnd()
    }

    private fun buildEvents(recs: List<Recording>): List<Event> {
        val lines = recs.flatMap { r ->
            Transcripts.get(this, r.name)?.segments?.filter { it.text.isNotBlank() }?.map { Line(r, it) } ?: emptyList()
        }.sortedBy { it.at }
        val groups = ArrayList<MutableList<Line>>()
        for (l in lines) {
            val g = groups.lastOrNull()
            if (g != null && Duration.between(g.last().endAt, l.at).seconds <= EVENT_GAP_S &&
                g.first().at.toLocalDate() == l.at.toLocalDate()
            ) {
                g.add(l)
            } else {
                groups.add(mutableListOf(l))
            }
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
                if (last != null && last.groupKey == key && gap != null && gap < PARA_GAP_S) {
                    last.lines.add(l)
                } else {
                    paras.add(Para(mutableListOf(l), who, spkId, key))
                }
            }
            Event(paras, Places.at(this, g.first().at))
        }
    }

    @Composable
    private fun EventsPage(
        recs: List<Recording>,
        query: String,
        onCopyEvent: (String) -> Unit,
        onOpenSettings: () -> Unit,
    ) {
        @Suppress("UNUSED_EXPRESSION") dataVersion
        @Suppress("UNUSED_EXPRESSION") configVersion
        val allEvents = remember(recs, dataVersion) { buildEvents(recs) }
        val q = query.trim().lowercase()
        val events = remember(allEvents, q) {
            if (q.isEmpty()) allEvents
            else allEvents.filter { ev ->
                ev.place?.label?.lowercase()?.contains(q) == true ||
                    ev.people.any { it.lowercase().contains(q) } ||
                    ev.paras.any { it.text.lowercase().contains(q) }
            }
        }
        val byDay = events.groupBy { it.start.toLocalDate() }.toSortedMap(compareByDescending { it })
        val mode = Config.mode(this)
        val unconfigured = if (mode == WorkMode.DIRECT_ASR) AsrConfig.load(this) == null else Config.load(this) == null

        PageList {
            if (events.isEmpty()) {
                item {
                    if (q.isNotEmpty()) {
                        EmptyStateCard(
                            icon = Icons.Rounded.SearchOff,
                            title = "没有匹配的对话记录",
                            body = "试试缩短关键词，或搜索人名、地点名称。",
                        )
                    } else {
                        EmptyStateCard(
                            icon = Icons.Rounded.Notes,
                            title = "还没有对话文字",
                            body = if (mode == WorkMode.DIRECT_ASR) {
                                "佩戴录音后，手机会自动调用直连 ASR 生成分段文字。"
                            } else {
                                "录音加密上传后，电脑端转写完会自动同步到这里。"
                            },
                            actionLabel = if (unconfigured) "去配置${mode.label}" else null,
                            onAction = if (unconfigured) onOpenSettings else null,
                        )
                    }
                }
            }
            byDay.forEach { (day, evs) ->
                item(key = "d$day") { DayHeader(dayLabel(day), "${evs.size} 段对话") }
                items(
                    items = evs.sortedByDescending { it.start },
                    key = { "e" + it.paras.first().lines.first().let { l -> l.rec.name + l.seg.start } },
                ) { ev ->
                    EventCard(ev = ev, highlightQuery = q, onCopy = { onCopyEvent(ev.formatForCopy()) })
                }
            }
        }
    }

    @Composable
    private fun highlightMatches(text: String, query: String): AnnotatedString {
        if (query.isBlank()) return AnnotatedString(text)
        val deck = DeckTheme.colors
        val lower = text.lowercase()
        val needle = query.lowercase()
        return buildAnnotatedString {
            var idx = 0
            while (idx < text.length) {
                val hit = lower.indexOf(needle, idx)
                if (hit < 0) {
                    append(text.substring(idx))
                    break
                }
                if (hit > idx) append(text.substring(idx, hit))
                withStyle(
                    SpanStyle(
                        background = deck.signalRedContainer,
                        color = deck.onSignalRedContainer,
                        fontWeight = FontWeight.SemiBold,
                    ),
                ) {
                    append(text.substring(hit, hit + needle.length))
                }
                idx = hit + needle.length
            }
        }
    }

    @Composable
    private fun EventCard(ev: Event, highlightQuery: String, onCopy: () -> Unit) {
        val playingName = player.current?.name
        val pos = player.positionMs / 1000.0
        val firstAudioLine = ev.paras.asSequence().flatMap { it.lines.asSequence() }.firstOrNull { it.rec.hasAudio }
        val deck = DeckTheme.colors
        val colors = MaterialTheme.colorScheme

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val sameMinute = ev.start.format(HM) == ev.end.format(HM)
                    Text(
                        text = if (sameMinute) ev.start.format(HM) else "${ev.start.format(HM)} – ${ev.end.format(HM)}",
                        style = MaterialTheme.typography.titleMedium.merge(TabularMono),
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (ev.place != null || ev.people.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ev.place?.let { p ->
                                MetaTag(Icons.Rounded.Place, p.label, deck.place) { editingPlace = p }
                            }
                            if (ev.people.isNotEmpty()) {
                                MetaTag(Icons.Rounded.Group, ev.people.joinToString("、"), colors.tertiary)
                            }
                        }
                    }
                }
                IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = "复制这段文字",
                        modifier = Modifier.size(18.dp),
                        tint = colors.onSurfaceVariant,
                    )
                }
                if (firstAudioLine != null) {
                    FilledTonalIconButton(
                        onClick = {
                            if (player.current?.name != firstAudioLine.rec.name) player.open(firstAudioLine.rec)
                            player.seekTo((firstAudioLine.seg.start * 1000).toInt())
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "从这里播放", modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            var prevLabel: String? = null
            val showLabels = ev.people.isNotEmpty() && !(ev.people.size == 1 && ev.paras.all { it.who != null })

            // 搜索时若卡片包含多段且部分未命中，优先保留命中段及其紧邻上下文，并高亮匹配词
            val visibleParas = if (highlightQuery.isBlank() || ev.paras.size <= 2) {
                ev.paras
            } else {
                val matchIdx = ev.paras.indices.filter { i ->
                    ev.paras[i].text.lowercase().contains(highlightQuery) ||
                        ev.paras[i].who?.lowercase()?.contains(highlightQuery) == true
                }
                if (matchIdx.isEmpty()) ev.paras
                else ev.paras.filterIndexed { i, _ -> matchIdx.any { kotlin.math.abs(it - i) <= 1 } }
            }

            visibleParas.forEach { p ->
                val label = p.who ?: "未识别"
                if (showLabels && label != prevLabel) {
                    val labelMod = Modifier
                        .padding(top = 6.dp, bottom = 2.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .let { m ->
                            if (p.speakerId != null) {
                                m.clickable { editing = Speakers.resolve(this@MainActivity, p.speakerId) }
                            } else m
                        }
                    Text(
                        text = highlightMatches(label, highlightQuery),
                        modifier = labelMod,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (p.speakerId != null) colors.tertiary else colors.onSurfaceVariant,
                    )
                }
                prevLabel = label

                val paraActive = p.lines.any { l ->
                    l.rec.name == playingName && pos >= l.seg.start && pos < l.seg.end + 0.3
                }
                val hasAnyAudio = p.lines.any { it.rec.hasAudio }

                // 构建逐句定位注解 + 播放态高亮 + 搜索关键词高亮的复合文本
                val annotatedPara = buildAnnotatedString {
                    p.lines.forEachIndexed { lineIdx, l ->
                        val sentence = l.seg.text.trim()
                        val lineActive = l.rec.name == playingName && pos >= l.seg.start && pos < l.seg.end + 0.3
                        pushStringAnnotation("line", lineIdx.toString())
                        if (lineActive) {
                            pushStyle(
                                SpanStyle(
                                    color = deck.signalRed,
                                    background = deck.signalRedContainer.copy(alpha = 0.6f),
                                    fontWeight = FontWeight.Medium,
                                ),
                            )
                        }
                        append(highlightMatches(sentence, highlightQuery))
                        if (lineActive) pop()
                        pop()
                    }
                }
                var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(if (paraActive) colors.primaryContainer.copy(alpha = 0.4f) else Color.Transparent)
                        .padding(vertical = 3.dp),
                ) {
                    if (paraActive) {
                        Box(
                            Modifier
                                .width(2.5.dp)
                                .heightIn(min = 20.dp)
                                .clip(CircleShape)
                                .background(deck.signalRed),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = annotatedPara,
                        onTextLayout = { layoutResult = it },
                        modifier = Modifier
                            .weight(1f)
                            .let { m ->
                                if (hasAnyAudio) {
                                    m.pointerInput(p.lines) {
                                        detectTapGestures { tapOffset ->
                                            val charOff = layoutResult?.getOffsetForPosition(tapOffset) ?: 0
                                            val hitIdx = annotatedPara
                                                .getStringAnnotations("line", charOff, charOff)
                                                .firstOrNull()?.item?.toIntOrNull() ?: 0
                                            val target = p.lines.getOrNull(hitIdx)?.takeIf { it.rec.hasAudio }
                                                ?: p.lines.firstOrNull { it.rec.hasAudio }
                                                ?: return@detectTapGestures
                                            if (player.current?.name != target.rec.name) player.open(target.rec)
                                            player.seekTo((target.seg.start * 1000).toInt())
                                        }
                                    }
                                } else m
                            },
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurface,
                    )
                }
            }
        }
    }

    @Composable
    private fun MetaTag(icon: ImageVector, text: String, color: Color, onClick: (() -> Unit)? = null) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.extraSmall)
                .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
            Spacer(Modifier.width(3.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    // ───────────────────────────── Tab 2：录音（机械磁带索引卡） ─────────────────────────────

    @Composable
    private fun RecordingsPage(recs: List<Recording>, s: Status, query: String) {
        @Suppress("UNUSED_EXPRESSION") configVersion
        val q = query.trim().lowercase()
        val filtered = remember(recs, q, dataVersion) {
            if (q.isEmpty()) recs
            else recs.filter { r ->
                r.name.lowercase().contains(q) ||
                    Places.at(this, r.start)?.label?.lowercase()?.contains(q) == true ||
                    Transcripts.get(this, r.name)?.text?.lowercase()?.contains(q) == true
            }
        }
        val byDay = filtered.groupBy { it.start.toLocalDate() }
        PageList {
            if (filtered.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Rounded.GraphicEq,
                        title = if (q.isNotEmpty()) "没有匹配的录音片段" else "还没有录音片段",
                        body = if (q.isNotEmpty()) "换个时间或关键词再试一次。" else "开始录音后，每 5 分钟自动封存一盘加密片段。",
                    )
                }
            }
            byDay.forEach { (day, list) ->
                item(key = "h$day") {
                    DayHeader(dayLabel(day), "${list.size} 段 · ${fmtDuration(list.sumOf { it.durationSec })}")
                }
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
        val deck = DeckTheme.colors
        val colors = MaterialTheme.colorScheme

        SectionCard(
            highlight = selected,
            onClick = if (r.hasAudio) ({ if (!selected) player.open(r) }) else null,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    enabled = r.hasAudio,
                    onClick = { if (selected) player.toggle() else player.open(r) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (selected && player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (selected && player.playing) "暂停" else "播放",
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${r.start.format(T)} – ${r.end.format(T)}",
                            style = MaterialTheme.typography.titleSmall.merge(TabularMono),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = fmtDuration(r.durationSec),
                            style = MaterialTheme.typography.labelMedium.merge(TabularMono),
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(statusLabel(r, tr, s))
                        place?.let { MetaTag(Icons.Rounded.Place, it.label, deck.place) }
                    }
                }
            }

            if (r.hasAudio) {
                Spacer(Modifier.height(10.dp))
                // 内嵌机械声波走带窗（外圆角 14dp -> 内圆角 8dp 同心内缩）
                val prog = if (selected && player.durationMs > 0) {
                    (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f)
                } else 0f
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(deck.tapeWell)
                        .border(1.dp, deck.tapeGroove, MaterialTheme.shapes.small)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Waveform(
                        levels = wave,
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                        progress = prog,
                        color = colors.onSurfaceVariant.copy(alpha = 0.65f),
                        playedColor = colors.onSurface,
                        playheadColor = if (selected) deck.signalRed else null,
                    )
                }
            }
            if (tr != null && tr.text.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = tr.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun StatusPill(text: String) {
        Text(
            text = text,
            modifier = Modifier
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // ───────────────────────────── 紧凑底部机械回放台（TapePlayerDeck） ─────────────────────────────

    @Composable
    private fun TapePlayerDeck() {
        val r = player.current ?: return
        var dragging by remember { mutableStateOf<Float?>(null) }
        var showText by remember { mutableStateOf(false) }
        val haptic = LocalHapticFeedback.current
        val deck = DeckTheme.colors
        val colors = MaterialTheme.colorScheme
        val dur = player.durationMs.coerceAtLeast(1)
        val shown = dragging?.let { (it * dur).toInt() } ?: player.positionMs

        Surface(
            color = colors.surfaceContainerLowest,
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, colors.outlineVariant),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            ) {
                AnimatedVisibility(visible = showText) {
                    TranscriptPane(r)
                }

                // 第一行：走带时间码 + 紧凑回放控制键组（省去冗余大块留白，给正文多留一屏视野）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TapeReelsGraphic(spinning = player.playing)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "${r.start.toLocalDate().format(MD)} ${r.start.plusSeconds((shown / 1000).toLong()).format(T)}",
                            style = MaterialTheme.typography.labelLarge.merge(TabularMono),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            text = "${fmtMs(shown)} / ${fmtMs(player.durationMs)}",
                            style = MaterialTheme.typography.labelSmall.merge(TabularMono),
                            color = colors.onSurfaceVariant,
                        )
                    }
                    if (Transcripts.get(this@MainActivity, r.name)?.segments?.isNotEmpty() == true) {
                        IconToggleButton(
                            checked = showText,
                            onCheckedChange = { showText = it },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Subtitles,
                                contentDescription = if (showText) "收起字幕" else "展开字幕",
                                modifier = Modifier.size(20.dp),
                                tint = if (showText) deck.signalRed else colors.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { player.skip(-10_000) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Replay10, contentDescription = "后退 10 秒", modifier = Modifier.size(20.dp))
                    }
                    FilledIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            player.toggle()
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (player.playing) "暂停" else "播放",
                        )
                    }
                    IconButton(onClick = { player.skip(10_000) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Forward10, contentDescription = "前进 10 秒", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { player.close() }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭播放器", modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(Modifier.height(6.dp))

                // 第二行：内凹可拖拽磁头波形刻度尺（带朱砂红磁头读针）
                var wave by remember(r.file.name) { mutableStateOf(Waveforms.cached(r.file)) }
                LaunchedEffect(r.file.name) { if (wave == null) wave = Waveforms.get(r.file) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 4.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(deck.tapeWell)
                        .border(1.dp, deck.tapeGroove, MaterialTheme.shapes.small)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Waveform(
                        levels = wave,
                        modifier = Modifier.fillMaxWidth().height(34.dp),
                        progress = dragging ?: (player.positionMs.toFloat() / dur),
                        color = colors.outline.copy(alpha = 0.45f),
                        playedColor = colors.onSurface,
                        playheadColor = deck.signalRed,
                        onSeekPreview = { pos ->
                            if (dragging == null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            dragging = pos
                        },
                        onSeek = { pos ->
                            player.seekTo((pos * dur).toInt())
                            dragging = null
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun TranscriptPane(r: Recording) {
        val tr = Transcripts.get(this, r.name) ?: return
        val pos = player.positionMs / 1000.0
        val listState = rememberLazyListState()
        val cur = tr.segments.indexOfLast { it.start <= pos + 0.2 }
        val deck = DeckTheme.colors
        LaunchedEffect(cur) { if (cur >= 0) listState.animateScrollToItem(maxOf(0, cur - 1)) }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 132.dp)
                .padding(bottom = 8.dp, end = 4.dp)
                .clip(MaterialTheme.shapes.small)
                .background(deck.tapeWell)
                .border(1.dp, deck.tapeGroove, MaterialTheme.shapes.small)
                .padding(6.dp),
            state = listState,
        ) {
            items(tr.segments.size) { i ->
                val seg = tr.segments[i]
                val active = i == cur
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(if (active) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent)
                        .clickable { player.seekTo((seg.start * 1000).toInt()) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = r.start.plusSeconds(seg.start.toLong()).format(T),
                        style = MaterialTheme.typography.labelSmall.merge(TabularMono),
                        color = if (active) deck.signalRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(62.dp).padding(top = 2.dp),
                    )
                    Text(
                        text = seg.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }

    // ───────────────────────────── Tab 3：人物（声纹名册） ─────────────────────────────

    private class PersonStats(val count: Int, val clip: Pair<Recording, Segment>?, val sample: String?)

    private fun personStats(recs: List<Recording>): Map<String, PersonStats> {
        val count = HashMap<String, Int>()
        val clip = HashMap<String, Pair<Recording, Segment>>()
        val sample = HashMap<String, String>()
        recs.forEach { r ->
            Transcripts.get(this, r.name)?.segments?.forEach { seg ->
                val who = Speakers.resolve(this, seg.speaker)?.id ?: return@forEach
                if (seg.text.isBlank()) return@forEach
                count[who] = (count[who] ?: 0) + 1
                if ((sample[who]?.length ?: 0) < seg.text.length) sample[who] = seg.text
                if (r.hasAudio) {
                    val old = clip[who]?.second
                    if (old == null || seg.end - seg.start > old.end - old.start) clip[who] = r to seg
                }
            }
        }
        return count.keys.associateWith { PersonStats(count[it] ?: 0, clip[it], sample[it]) }
    }

    private fun playClip(c: Pair<Recording, Segment>) =
        player.playRange(c.first, (c.second.start * 1000).toInt(), (c.second.end * 1000).toInt() + 300)

    private fun visiblePeople(stats: Map<String, PersonStats>) =
        Speakers.all(this).values
            .filter { it.mergedInto == null && ((stats[it.id]?.count ?: 0) > 0 || it.name != null) }
            .sortedWith(compareBy<Speaker>({ it.name == null }, { -(stats[it.id]?.count ?: 0) }))

    @Composable
    private fun PeoplePage(recs: List<Recording>) {
        @Suppress("UNUSED_EXPRESSION") dataVersion
        val all = Speakers.all(this)
        val scope = rememberCoroutineScope()
        val stats = remember(recs, dataVersion) { personStats(recs) }
        val people = visiblePeople(stats)
        PageList {
            if (people.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Rounded.Group,
                        title = "尚无声纹人物档案",
                        body = "当电脑端开启本地声纹聚类后，识别到的说话人会自动归档在此，可起名或合并。",
                    )
                }
            } else if (people.count { it.name == null } >= 2) {
                item {
                    Hint("有多个未命名编号时，可先试听原声，再从卡片菜单合并同一人。")
                }
            }
            items(people, key = { it.id }) { p ->
                val st = stats[p.id]
                val aliases = all.values.filter { it.mergedInto != null && Speakers.resolve(this@MainActivity, it.id)?.id == p.id }
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(
                            text = p.name?.take(1) ?: "#",
                            bg = MaterialTheme.colorScheme.tertiaryContainer,
                            fg = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = p.name ?: p.defaultName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (p.name == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "${st?.count ?: 0} 句" + if (aliases.isNotEmpty()) " · 已合并 ${aliases.size} 个声纹" else "",
                                style = MaterialTheme.typography.labelMedium.merge(TabularMono),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (people.size > 1 || aliases.isNotEmpty()) {
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(Icons.Rounded.MoreVert, contentDescription = "更多操作", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                    if (people.size > 1) {
                                        DropdownMenuItem(
                                            text = { Text("合并到其他人…") },
                                            leadingIcon = { Icon(Icons.Rounded.MergeType, contentDescription = null) },
                                            onClick = { menu = false; merging = p },
                                        )
                                    }
                                    aliases.forEach { a ->
                                        DropdownMenuItem(
                                            text = { Text("拆出 ${a.name ?: a.defaultName}") },
                                            leadingIcon = { Icon(Icons.Rounded.CallSplit, contentDescription = null) },
                                            onClick = {
                                                menu = false
                                                scope.launch(Dispatchers.IO) {
                                                    Speakers.edit(this@MainActivity, a.id, unmerge = true)
                                                    dataVersion++
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    (st?.sample ?: p.sample)?.let { quote ->
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = DeckTheme.colors.tapeWell,
                        ) {
                            Text(
                                text = "「$quote」",
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        st?.clip?.let { c -> ActionChip(Icons.Rounded.PlayArrow, "试听原声") { playClip(c) } }
                        ActionChip(Icons.Rounded.Edit, "改名") { editing = p }
                    }
                }
            }
        }
    }

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
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            placeholder = { Text("例如：我、老王、张工") },
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            clip?.let { c -> ActionChip(Icons.Rounded.PlayArrow, "试听原声") { playClip(c) } }
                            ActionChip(Icons.Rounded.MergeType, "合并到已有名字") {
                                merging = p
                                editing = null
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            Speakers.edit(this@MainActivity, p.id, name = text)
                            dataVersion++
                        }
                        editing = null
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
            )
        }
        merging?.let { p ->
            val stats = personStats(recs)
            val others = visiblePeople(stats).filter { it.id != p.id }
            AlertDialog(
                onDismissRequest = { merging = null },
                title = { Text("将 ${p.name ?: p.defaultName} 合并到谁？") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        item {
                            Text(
                                "合并后两边声纹都归为同一人，后续随时可拆开恢复。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        items(others, key = { it.id }) { t ->
                            val st = stats[t.id]
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable {
                                            scope.launch(Dispatchers.IO) {
                                                Speakers.edit(this@MainActivity, p.id, mergeInto = t.id)
                                                dataVersion++
                                            }
                                            merging = null
                                        }
                                        .padding(8.dp),
                                ) {
                                    Text(
                                        t.name ?: t.defaultName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    st?.sample?.let {
                                        Text(
                                            "「$it」",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                st?.clip?.let { c ->
                                    IconButton(onClick = { playClip(c) }) {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = "试听")
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { merging = null }) { Text("取消") } },
            )
        }
    }

    // ───────────────────────────── Tab 4：地点（纯本机留存） ─────────────────────────────

    @Composable
    private fun PlacesPage() {
        @Suppress("UNUSED_EXPRESSION") dataVersion
        val all = Places.all(this)
        val stats = remember(dataVersion) { Places.stats(this) }
        val places = all.filter { it.mergedInto == null }
        val deck = DeckTheme.colors

        PageList {
            if (places.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = if (hasLocation) Icons.Rounded.Place else Icons.Rounded.LocationOff,
                        title = if (hasLocation) "还没有记录到地点" else "定位权限未开启",
                        body = "开启后每 5 分钟在手机本地标记对话位置，位置只存在本机，绝不上传服务器。",
                        actionLabel = if (!hasLocation) "开启定位" else null,
                        onAction = if (!hasLocation) ({ requestLocation() }) else null,
                    )
                }
            } else {
                if (!hasLocation) {
                    item {
                        SectionCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.LocationOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("定位已关闭", style = MaterialTheme.typography.titleSmall)
                                    Text("新录音将不再标记位置（已有地点仍保留在本机）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                FilledTonalButton(onClick = { requestLocation() }) { Text("重新开启") }
                            }
                        }
                    }
                }
                item { Hint("所有地点仅保存在这部手机上，可改名或合并常用场所。") }
            }
            items(places, key = { it.id }) { p ->
                val st = stats[p.id]
                val aliases = all.filter { it.mergedInto == p.id }
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(deck.placeContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Place, contentDescription = null, tint = deck.place)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (p.name != null && p.auto != null) {
                                Text(p.auto, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            p.area?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                text = buildString {
                                    if (st != null) {
                                        append("停留 ${st.days} 天 · 约 ${fmtMinutes(st.minutes)}")
                                        st.last?.let { append(" · 最近 ${it.format(MDHM)}") }
                                    }
                                    if (aliases.isNotEmpty()) append(" · 已合并 ${aliases.size} 处")
                                },
                                style = MaterialTheme.typography.labelMedium.merge(TabularMono),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionChip(Icons.Rounded.Edit, "改名") { editingPlace = p }
                        if (places.size > 1) ActionChip(Icons.Rounded.MergeType, "合并") { mergingPlace = p }
                        aliases.forEach { a ->
                            ActionChip(Icons.Rounded.CallSplit, "拆出 ${a.label}") {
                                Places.merge(this@MainActivity, a.id, null)
                                dataVersion++
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PlaceDialogs() {
        editingPlace?.let { p ->
            var text by remember(p.id) { mutableStateOf(p.name ?: "") }
            AlertDialog(
                onDismissRequest = { editingPlace = null },
                title = { Text("给「${p.label}」起常用名") },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        placeholder = { Text(p.auto ?: "例如：家、公司、咖啡馆") },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        Places.rename(this, p.id, text)
                        dataVersion++
                        editingPlace = null
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { editingPlace = null }) { Text("取消") } },
            )
        }
        mergingPlace?.let { p ->
            val others = Places.all(this).filter { it.mergedInto == null && it.id != p.id }
            AlertDialog(
                onDismissRequest = { mergingPlace = null },
                title = { Text("将「${p.label}」合并到哪一处？") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(others, key = { it.id }) { t ->
                            Text(
                                text = t.label,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        Places.merge(this@MainActivity, p.id, t.id)
                                        dataVersion++
                                        mergingPlace = null
                                    }
                                    .padding(10.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { mergingPlace = null }) { Text("取消") } },
            )
        }
    }

    // ───────────────────────────── 原生通用构件 ─────────────────────────────

    @Composable
    private fun PageList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }

    @Composable
    private fun SectionCard(
        highlight: Boolean = false,
        onClick: (() -> Unit)? = null,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        val colors = MaterialTheme.colorScheme
        val fill by animateColorAsState(
            if (highlight) colors.surfaceContainerLow else colors.surfaceContainerLowest,
            tween(180),
            label = "cardFill",
        )
        val borderColor by animateColorAsState(
            if (highlight) DeckTheme.colors.signalRed.copy(alpha = 0.6f) else colors.outlineVariant,
            tween(180),
            label = "cardBorder",
        )
        val cardColors = CardDefaults.outlinedCardColors(containerColor = fill)
        val border = BorderStroke(1.dp, borderColor)
        val body: @Composable ColumnScope.() -> Unit = { Column(Modifier.padding(14.dp), content = content) }
        if (onClick != null) {
            OutlinedCard(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = cardColors,
                border = border,
                content = body,
            )
        } else {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = cardColors,
                border = border,
                content = body,
            )
        }
    }

    @Composable
    private fun DayHeader(title: String, trailing: String? = null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            trailing?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall.merge(TabularMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun Hint(text: String) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    @Composable
    private fun EmptyStateCard(
        icon: ImageVector,
        title: String,
        body: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        OutlinedCard(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(DeckTheme.colors.tapeWell)
                        .border(1.dp, DeckTheme.colors.tapeGroove, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (actionLabel != null && onAction != null) {
                    Spacer(Modifier.height(14.dp))
                    FilledTonalButton(onClick = onAction) { Text(actionLabel) }
                }
            }
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
        AssistChip(
            onClick = onClick,
            label = { Text(label) },
            leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        )
    }

    // ───────────────────────────── 状态文案与更新工具 ─────────────────────────────

    private fun statusLabel(r: Recording, tr: Transcript?, s: Status): String {
        val mode = Config.mode(this)
        val remote = s.remote
        return when {
            tr != null -> if (tr.text.isBlank()) "已转写（无有效人声）" else "已转写"
            !r.hasAudio -> "录音已过期"
            uploader.isSilent(r.file) -> if (mode == WorkMode.DIRECT_ASR) "无人声，已跳过" else "无人声，未上传"
            mode == WorkMode.DIRECT_ASR -> when {
                s.localAsrRunning == r.file.name -> "手机转写中…"
                AsrConfig.load(this) == null -> "待配直连 ASR"
                uploader.isSkippedBadAudio(r.file) -> "音频异常，已跳过"
                s.localAsrError != null -> "转写待重试"
                else -> "排队手机转写"
            }
            uploader.isUploaded(r.file) -> when {
                remote.workerOnline && remote.workerPhase == "transcribing" && remote.workerCurrent == r.file.name -> "电脑转写中…"
                remote.mailboxReachable == true && !remote.workerOnline -> "已上传 · 等电脑上线"
                else -> "排队转写中"
            }
            else -> "待加密上传"
        }
    }

    private fun workerShortLabel(remote: RemoteStatus): String = when {
        !remote.workerOnline -> remote.workerAgeSec?.let { "电脑离线 ${fmtAge(it)}" } ?: "等待电脑端连接"
        remote.workerPhase == "paused" -> "已暂停拉取"
        remote.asrOk == false || remote.workerPhase == "asr_down" -> "转写服务未就绪"
        remote.workerPhase == "transcribing" -> "正在转写 (${remote.workerPhaseElapsedSec.toInt()}s)"
        remote.workerPhase == "error" -> "出错：${remote.workerError ?: "未知"}"
        else -> remote.asrModel ?: "空闲就绪"
    }

    private fun fmtAge(seconds: Double): String = when {
        seconds < 60 -> "${seconds.toInt()}s"
        seconds < 3600 -> "${(seconds / 60).toInt()}m"
        seconds < 86400 -> "${(seconds / 3600).toInt()}h"
        else -> "${(seconds / 86400).toInt()}d"
    }

    private fun checkGitHubUpdate(): Pair<String, String?> = try {
        val conn = URL("https://api.github.com/repos/riggedlab/hark/releases/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        if (conn.responseCode == 404) {
            "当前 v${BuildConfig.VERSION_NAME}（仓库公开后支持自动检测）" to null
        } else {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name").removePrefix("v")
            if (isNewerSemVer(tag, BuildConfig.VERSION_NAME)) {
                val assets = json.optJSONArray("assets")
                var apk: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val u = assets.getJSONObject(i).optString("browser_download_url")
                        if (u.endsWith(".apk")) {
                            apk = u
                            break
                        }
                    }
                }
                "发现新版本 v$tag（当前 v${BuildConfig.VERSION_NAME}）" to (apk ?: json.optString("html_url"))
            } else {
                "已是最新版本 v${BuildConfig.VERSION_NAME}" to null
            }
        }
    } catch (e: Exception) {
        "检查失败：${e.message?.take(60)}" to null
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

        private val BATT_CURVE = listOf(
            4200 to 100, 4100 to 90, 4000 to 78, 3900 to 65, 3800 to 50,
            3750 to 40, 3700 to 30, 3650 to 20, 3600 to 12, 3500 to 5, 3300 to 0,
        )

        fun batteryPct(mv: Int): Int {
            if (mv >= BATT_CURVE.first().first) return 100
            if (mv <= BATT_CURVE.last().first) return 0
            for (i in 0 until BATT_CURVE.size - 1) {
                val (v1, p1) = BATT_CURVE[i]
                val (v2, p2) = BATT_CURVE[i + 1]
                if (mv in v2..v1) return p2 + (p1 - p2) * (mv - v2) / (v1 - v2)
            }
            return 0
        }

        fun fmtDuration(sec: Double): String {
            val s = sec.toLong()
            return when {
                s >= 3600 -> "${s / 3600}h${(s % 3600) / 60}m"
                s >= 60 -> "%d:%02d".format(s / 60, s % 60)
                else -> "0:%02d".format(s)
            }
        }

        fun fmtMinutes(m: Int) = if (m >= 60) "${m / 60} 小时${if (m % 60 > 0) " ${m % 60} 分" else ""}" else "$m 分钟"

        fun fmtMs(ms: Int): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return "%02d:%02d".format(s / 60, s % 60)
        }
    }
}
