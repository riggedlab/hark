@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package io.github.riggedlab.hark

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.Base64

private data class OpenAiPreset(
    val name: String,
    val baseUrl: String,
    val model: String,
    val hint: String,
)

private val OPENAI_PRESETS = listOf(
    OpenAiPreset("硅基流动", "https://api.siliconflow.cn/v1", "FunAudioLLM/SenseVoiceSmall", "中文口语快、性价比高"),
    OpenAiPreset("Groq", "https://api.groq.com/openai/v1", "whisper-large-v3-turbo", "极速响应，带分句时间"),
    OpenAiPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini-transcribe", "多语种混合识别好"),
)

@Composable
internal fun SettingsScreen(
    linkState: LinkState,
    deviceName: String?,
    whitelisted: Boolean,
    hasLocation: Boolean,
    unuploadedCount: Int,
    recordingCount: Int,
    onBack: () -> Unit,
    onOpenRawRecordings: () -> Unit,
    onConfigChanged: () -> Unit,
    onRepairBle: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
    onRequestLocation: () -> Unit,
    onCheckGitHubUpdate: suspend () -> Pair<String, String?>,
    onOpenUrl: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var feedStyle by remember { mutableStateOf(Config.feedStyle(ctx)) }
    var committedMode by remember { mutableStateOf(Config.mode(ctx)) }
    var selectedMode by remember { mutableStateOf(committedMode) }

    val initCloud = remember { Config.rawValues(ctx) }
    var serverUrl by remember { mutableStateOf(initCloud.first) }
    var phoneToken by remember { mutableStateOf(initCloud.second) }
    var e2eeKey by remember { mutableStateOf(initCloud.third) }
    var showCloudSecrets by remember { mutableStateOf(false) }

    val initAsr = remember { AsrConfig.rawValues(ctx) }
    var provider by remember { mutableStateOf(initAsr.provider) }
    var asrUrl by remember { mutableStateOf(initAsr.baseUrl) }
    var asrKey by remember { mutableStateOf(initAsr.apiKey) }
    var asrAppId by remember { mutableStateOf(initAsr.appId) }
    var asrModel by remember { mutableStateOf(initAsr.model) }
    var showAsrSecret by remember { mutableStateOf(false) }
    var showGuide by remember(provider) {
        mutableStateOf(
            when (provider) {
                AsrProvider.VOLCENGINE, AsrProvider.DASHSCOPE, AsrProvider.OPENAI -> initAsr.apiKey.isBlank()
                AsrProvider.HARK_HTTP -> initAsr.baseUrl.isBlank()
            },
        )
    }
    var showVolcAdv by remember {
        mutableStateOf(
            initAsr.appId.isNotBlank() ||
                (initAsr.model.isNotBlank() && initAsr.model != AsrProvider.VOLCENGINE.defaultModel) ||
                (initAsr.baseUrl.isNotBlank() && initAsr.baseUrl != AsrProvider.VOLCENGINE.defaultUrl),
        )
    }
    var probing by remember { mutableStateOf(false) }
    var probeJob by remember { mutableStateOf<Job?>(null) }
    var probeReport by remember { mutableStateOf<AsrProbeReport?>(null) }

    fun cancelProbe() {
        probeJob?.cancel()
        probeJob = null
        probing = false
    }

    var checkingUpdate by remember { mutableStateOf(false) }
    var updateHint by remember { mutableStateOf<String?>(null) }
    var updateUrl by remember { mutableStateOf<String?>(null) }

    val keyErr = e2eeKey.isNotBlank() && runCatching { Crypto.decodeKey(e2eeKey.trim()) }.isFailure
    val cloudComplete = serverUrl.isNotBlank() && phoneToken.isNotBlank() && e2eeKey.isNotBlank() && !keyErr
    val draftAsr = AsrConfig(provider = provider, baseUrl = asrUrl, apiKey = asrKey, appId = asrAppId, model = asrModel)
    val asrValidationErr = draftAsr.validationError()

    fun notify(msg: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    fun pasteClip(): String? = clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.pressScale()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "DT-1",
                            style = MaterialTheme.typography.labelSmall.merge(TabularMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 0. 记忆流视图风格切换（主客气泡流 B / 无框左轴流 C）
            item(key = "feed_style_selector") {
                SettingsGroupCard(
                    title = "记忆流视图",
                    subtitle = "选择全天对话与环境脉络的呈现方式，切换立即生效",
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FeedStyle.entries.forEach { style ->
                            ModeOptionTile(
                                title = style.label,
                                desc = style.desc,
                                selected = feedStyle == style,
                                active = feedStyle == style,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (feedStyle != style) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        Config.setFeedStyle(ctx, style)
                                        feedStyle = style
                                        onConfigChanged()
                                        notify("已切换为「${style.label}」")
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // 1. 清晰双路径选择卡（一眼看懂区别 + 当前生效标记）
            item(key = "mode_selector") {
                SettingsGroupCard(title = "转写路径", subtitle = "可随时切换；直连期间录音保留在本机，7 天内切回云端会自动加密补传") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ModeOptionTile(
                            title = "手机直连转写",
                            desc = "豆包 / 百炼 / OpenAI 出字 + 本机声纹认人",
                            selected = selectedMode == WorkMode.DIRECT_ASR,
                            active = committedMode == WorkMode.DIRECT_ASR,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (selectedMode != WorkMode.DIRECT_ASR) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedMode = WorkMode.DIRECT_ASR
                                    if (AsrConfig.load(ctx) != null) {
                                        Config.setMode(ctx, WorkMode.DIRECT_ASR)
                                        committedMode = WorkMode.DIRECT_ASR
                                        onConfigChanged()
                                        notify("已切换为「手机直连转写」")
                                    }
                                }
                            },
                        )
                        ModeOptionTile(
                            title = "云端信箱同步",
                            desc = "端到端加密传回电脑归档认人",
                            selected = selectedMode == WorkMode.CLOUD,
                            active = committedMode == WorkMode.CLOUD,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (selectedMode != WorkMode.CLOUD) {
                                    cancelProbe()
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedMode = WorkMode.CLOUD
                                    if (Config.load(ctx) != null) {
                                        Config.setMode(ctx, WorkMode.CLOUD)
                                        committedMode = WorkMode.CLOUD
                                        onConfigChanged()
                                        notify("已切换为「云端信箱同步」")
                                    }
                                }
                            },
                        )
                    }

                    val targetReady = if (selectedMode == WorkMode.CLOUD) Config.load(ctx) != null else AsrConfig.load(ctx) != null
                    AnimatedVisibility(
                        visible = (selectedMode != committedMode && !targetReady) || (selectedMode == WorkMode.DIRECT_ASR && unuploadedCount > 0),
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Column {
                            Spacer(Modifier.height(10.dp))
                            val pendingSwitch = selectedMode != committedMode && !targetReady
                            Text(
                                text = if (pendingSwitch) {
                                    "填写下方配置并点「${if (selectedMode == WorkMode.DIRECT_ASR) "测试并启用" else "保存并启用"}」后正式切换；启用前仍保持「${committedMode.label}」。"
                                } else {
                                    "本机有 $unuploadedCount 段录音尚未上传云端，切回「云端信箱同步」会自动补传。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (pendingSwitch) DeckTheme.colors.ledAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // 2. 当前所选路径配置（带分步引导、一键预设、一键粘贴、一键测试并启用）
            item(key = "active_mode_config") {
                AnimatedContent(
                    targetState = selectedMode,
                    transitionSpec = {
                        fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                    },
                    label = "modeConfigSwitch",
                ) { targetMode ->
                    if (targetMode == WorkMode.DIRECT_ASR) {
                        DirectAsrConfigCard(
                            provider = provider,
                            asrUrl = asrUrl,
                            asrKey = asrKey,
                            asrAppId = asrAppId,
                            asrModel = asrModel,
                            showSecret = showAsrSecret,
                            showGuide = showGuide,
                            showVolcAdv = showVolcAdv,
                            probing = probing,
                            probeReport = probeReport,
                            validationErr = asrValidationErr,
                            willSwitchMode = committedMode != WorkMode.DIRECT_ASR,
                            onProviderChange = { next ->
                                cancelProbe()
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val prev = provider
                                provider = next
                                if (asrUrl.isBlank() || asrUrl == prev.defaultUrl) asrUrl = next.defaultUrl
                                if (asrModel.isBlank() || asrModel == prev.defaultModel) asrModel = next.defaultModel
                                probeReport = null
                            },
                            onApplyPreset = { preset ->
                                cancelProbe()
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                asrUrl = preset.baseUrl
                                asrModel = preset.model
                                probeReport = null
                            },
                            onUrlChange = { cancelProbe(); asrUrl = it; probeReport = null },
                            onKeyChange = { cancelProbe(); asrKey = it; probeReport = null },
                            onAppIdChange = { cancelProbe(); asrAppId = it; probeReport = null },
                            onModelChange = { cancelProbe(); asrModel = it; probeReport = null },
                            onPasteKey = {
                                val v = pasteClip()
                                if (v != null) {
                                    cancelProbe()
                                    asrKey = v
                                    probeReport = null
                                    notify("已粘贴密钥")
                                } else notify("剪贴板为空")
                            },
                            onPasteUrl = {
                                val v = pasteClip()
                                if (v != null) {
                                    cancelProbe()
                                    asrUrl = v
                                    probeReport = null
                                } else notify("剪贴板为空")
                            },
                            onToggleSecret = { showAsrSecret = !showAsrSecret },
                            onToggleGuide = { showGuide = !showGuide },
                            onToggleVolcAdv = { showVolcAdv = !showVolcAdv },
                            onOpenConsole = {
                                onOpenUrl(if (provider == AsrProvider.DASHSCOPE) "https://bailian.console.aliyun.com/" else "https://console.volcengine.com/speech/new/setting/activate")
                            },
                            onProbeAndEnable = {
                                focusManager.clearFocus()
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                probeJob?.cancel()
                                val testedAsr = draftAsr
                                probing = true
                                probeReport = null
                                probeJob = scope.launch {
                                    val rep = withContext(Dispatchers.IO) { PhoneAsr.probe(ctx, testedAsr) }
                                    val stillCurrent = selectedMode == WorkMode.DIRECT_ASR &&
                                        AsrConfig(provider, asrUrl, asrKey, asrAppId, asrModel) == testedAsr
                                    probing = false
                                    if (!stillCurrent) return@launch
                                    probeReport = rep
                                    if (rep.ok) {
                                        AsrConfig.save(ctx, testedAsr)
                                        Config.setMode(ctx, WorkMode.DIRECT_ASR)
                                        committedMode = WorkMode.DIRECT_ASR
                                        showGuide = false
                                        onConfigChanged()
                                        notify("测试通过（${rep.latencyMs}ms），已保存并启用")
                                    }
                                }
                            },
                            onSaveOnly = {
                                cancelProbe()
                                focusManager.clearFocus()
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val wasSwitch = committedMode != WorkMode.DIRECT_ASR
                                AsrConfig.save(ctx, draftAsr)
                                Config.setMode(ctx, WorkMode.DIRECT_ASR)
                                committedMode = WorkMode.DIRECT_ASR
                                onConfigChanged()
                                notify(if (wasSwitch) "已保存并切换为「手机直连转写」" else "已保存直连配置")
                            },
                        )
                    } else {
                        CloudMailboxConfigCard(
                            serverUrl = serverUrl,
                            phoneToken = phoneToken,
                            e2eeKey = e2eeKey,
                            showSecrets = showCloudSecrets,
                            keyErr = keyErr,
                            canSave = cloudComplete,
                            saveLabel = if (committedMode != WorkMode.CLOUD) "保存并启用" else "保存信箱配置",
                            onUrlChange = { serverUrl = it },
                            onTokenChange = { phoneToken = it },
                            onKeyChange = { e2eeKey = it },
                            onPasteUrl = { pasteClip()?.let { serverUrl = it } ?: notify("剪贴板为空") },
                            onPasteToken = { pasteClip()?.let { phoneToken = it } ?: notify("剪贴板为空") },
                            onPasteKey = { pasteClip()?.let { e2eeKey = it } ?: notify("剪贴板为空") },
                            onToggleSecrets = { showCloudSecrets = !showCloudSecrets },
                            onGenerateKey = {
                                val b = ByteArray(32).also { SecureRandom().nextBytes(it) }
                                e2eeKey = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
                                showCloudSecrets = true
                                clipboard.setText(AnnotatedString(e2eeKey))
                                notify("已生成并复制新密钥，请在电脑端填入同一把密钥")
                            },
                            onCopyKey = {
                                if (e2eeKey.isNotBlank()) {
                                    clipboard.setText(AnnotatedString(e2eeKey.trim()))
                                    notify("已复制端到端密钥")
                                }
                            },
                            onSave = {
                                focusManager.clearFocus()
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val wasSwitch = committedMode != WorkMode.CLOUD
                                Config.save(ctx, serverUrl, phoneToken, e2eeKey)
                                Config.setMode(ctx, WorkMode.CLOUD)
                                committedMode = WorkMode.CLOUD
                                onConfigChanged()
                                notify(if (wasSwitch) "已保存并切换为「云端信箱同步」" else "已保存云端信箱配置")
                            },
                        )
                    }
                }
            }

            // 3. 硬件与系统权限
            item(key = "hardware_permissions") {
                OutlinedCard(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    val rowColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
                    ListItem(
                        colors = rowColors,
                        leadingContent = { Icon(Icons.Rounded.BluetoothAudio, contentDescription = null) },
                        headlineContent = { Text(deviceName ?: "Hark DT-1") },
                        supportingContent = { Text("当前状态：${linkState.label}") },
                        trailingContent = {
                            OutlinedButton(
                                onClick = {
                                    onRepairBle()
                                    notify("已发起蓝牙重新配对")
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.pressScale(),
                            ) { Text("重新配对") }
                        },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    ListItem(
                        colors = rowColors,
                        leadingContent = { Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null) },
                        headlineContent = { Text("后台不受限运行") },
                        supportingContent = {
                            Text(if (whitelisted) "已加入省电白名单，锁屏可持续收音" else "未加入白名单，锁屏后系统可能中断蓝牙录音")
                        },
                        trailingContent = {
                            if (whitelisted) {
                                Text("已开启", style = MaterialTheme.typography.labelMedium, color = DeckTheme.colors.ledGreen)
                            } else {
                                FilledTonalButton(
                                    onClick = onRequestBatteryWhitelist,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.pressScale(),
                                ) { Text("去开启") }
                            }
                        },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    ListItem(
                        colors = rowColors,
                        leadingContent = { Icon(Icons.Rounded.Place, contentDescription = null, tint = DeckTheme.colors.place) },
                        headlineContent = { Text("自动记录对话地点") },
                        supportingContent = { Text("仅存储在手机本机，绝不上传服务器") },
                        trailingContent = {
                            if (hasLocation) {
                                Text("已授权", style = MaterialTheme.typography.labelMedium, color = DeckTheme.colors.ledGreen)
                            } else {
                                OutlinedButton(
                                    onClick = onRequestLocation,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.pressScale(),
                                ) { Text("开启定位") }
                            }
                        },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    ListItem(
                        colors = rowColors,
                        leadingContent = { Icon(Icons.Rounded.GraphicEq, contentDescription = null) },
                        headlineContent = { Text("原始录音归档") },
                        supportingContent = {
                            Text(
                                text = "本机保存 $recordingCount 段音频切片 · 查看转写状态或回放原声",
                                style = MaterialTheme.typography.bodySmall.merge(TabularMono),
                            )
                        },
                        trailingContent = {
                            OutlinedButton(
                                onClick = onOpenRawRecordings,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.pressScale(),
                            ) { Text("查看") }
                        },
                    )
                }
            }

            // 4. 版本与更新
            item(key = "about_update") {
                OutlinedCard(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                        leadingContent = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
                        headlineContent = {
                            Text(
                                "Hark v${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.titleSmall.merge(TabularMono),
                            )
                        },
                        supportingContent = {
                            Text(updateHint ?: "录音本机保留 ${Recordings.RETENTION_DAYS} 天 · 转写文字永久保留")
                        },
                        trailingContent = {
                            val target = updateUrl
                            if (target != null) {
                                Button(
                                    onClick = { onOpenUrl(target) },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.pressScale(),
                                ) { Text("下载新版") }
                            } else {
                                OutlinedButton(
                                    enabled = !checkingUpdate,
                                    onClick = {
                                        checkingUpdate = true
                                        scope.launch {
                                            val (hint, url) = onCheckGitHubUpdate()
                                            updateHint = hint
                                            updateUrl = url
                                            checkingUpdate = false
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.pressScale(),
                                ) { Text(if (checkingUpdate) "检查中…" else "检查更新") }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeOptionTile(
    title: String,
    desc: String,
    selected: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val deck = DeckTheme.colors
    val borderColor by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.outlineVariant,
        animationSpec = tween(180),
        label = "modeBorder",
    )
    val bgColor by animateColorAsState(
        targetValue = if (selected) colors.surfaceContainerLow else colors.surfaceContainerLowest,
        animationSpec = tween(180),
        label = "modeBg",
    )
    Surface(
        onClick = onClick,
        modifier = modifier.pressScale(),
        shape = MaterialTheme.shapes.small,
        color = bgColor,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(deck.ledGreen))
                }
            }
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                minLines = 2,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (subtitle != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                trailing?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun DirectAsrConfigCard(
    provider: AsrProvider,
    asrUrl: String,
    asrKey: String,
    asrAppId: String,
    asrModel: String,
    showSecret: Boolean,
    showGuide: Boolean,
    showVolcAdv: Boolean,
    probing: Boolean,
    probeReport: AsrProbeReport?,
    validationErr: String?,
    willSwitchMode: Boolean,
    onProviderChange: (AsrProvider) -> Unit,
    onApplyPreset: (OpenAiPreset) -> Unit,
    onUrlChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onAppIdChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onPasteKey: () -> Unit,
    onPasteUrl: () -> Unit,
    onToggleSecret: () -> Unit,
    onToggleGuide: () -> Unit,
    onToggleVolcAdv: () -> Unit,
    onOpenConsole: () -> Unit,
    onProbeAndEnable: () -> Unit,
    onSaveOnly: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val deck = DeckTheme.colors
    val focusManager = LocalFocusManager.current

    SettingsGroupCard(
        title = "直连转写服务",
        subtitle = "云端极速出字 + 手机本地声纹认人（声纹仅留本机，不上传）",
        trailing = {
            TextButton(
                onClick = onToggleGuide,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(if (showGuide) "收起指引" else "配置指引", style = MaterialTheme.typography.labelMedium)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 服务商切换条（方角机械按键感）
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 2,
            ) {
                AsrProvider.entries.forEach { p ->
                    val selected = provider == p
                    Surface(
                        onClick = { onProviderChange(p) },
                        modifier = Modifier.weight(1f).pressScale(),
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) colors.primary else colors.surfaceContainerLow,
                        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 9.dp, horizontal = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = p.shortLabel,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) colors.onPrimary else colors.onSurface,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            // 分步新手引导（首次未填 Key 自动展开，配好后自动收起不挡视线）
            AnimatedVisibility(
                visible = showGuide,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = deck.tapeWell,
                    border = BorderStroke(1.dp, deck.tapeGroove),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        when (provider) {
                            AsrProvider.VOLCENGINE, AsrProvider.DASHSCOPE -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (provider == AsrProvider.DASHSCOPE) "阿里云百炼 3 步配置" else "豆包语音 3 步配置",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = "打开控制台 ↗",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.primary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable(onClick = onOpenConsole)
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                    )
                                }
                                if (provider == AsrProvider.DASHSCOPE) {
                                    GuideStep("1", "打开百炼控制台，选择北京或新加坡地域，开通 qwen-audio-3.1-asr-flash")
                                    GuideStep("2", "在「API Key 管理」创建 Key；新加坡需将接口域名改为 dashscope-intl.aliyuncs.com，或使用控制台的工作空间域名")
                                } else {
                                    GuideStep("1", "进入「开通管理 → 录音文件识别」，开通「大模型录音文件极速版」（勿选流式或异步标准版）")
                                    GuideStep("2", "在左侧「API Key 管理」新建一把 Key 并复制（注意与火山方舟 Key 不通用）")
                                }
                                GuideStep("3", "点下方输入框右侧「粘贴」，再点「测试并启用」即可")
                            }
                            AsrProvider.OPENAI -> {
                                Text("OpenAI 兼容接口快速接入", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                GuideStep("1", "先点下方常用平台预设（如硅基流动、Groq），自动填好地址与推荐模型")
                                GuideStep("2", "粘贴平台 API Key，点「测试并启用」自动验证生效")
                            }
                            AsrProvider.HARK_HTTP -> {
                                Text("局域网 / Tailscale 自建服务", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                GuideStep("1", "确保电脑或家庭服务器已运行自建声纹转写服务（实现 /transcribe）")
                                GuideStep("2", "填入局域网 IP 或 Tailscale 地址（如 http://100.x.x.x:8765）后点「测试并启用」")
                            }
                        }
                    }
                }
            }

            // 各服务商表单（平滑切换）
            AnimatedContent(
                targetState = provider,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
                label = "providerFields",
            ) { currentProvider ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (currentProvider) {
                        AsrProvider.VOLCENGINE -> {
                            SecretOrUrlField(
                                value = asrKey,
                                onValueChange = onKeyChange,
                                label = "豆包语音 API Key",
                                placeholder = "粘贴豆包语音控制台创建的 API Key",
                                isSecret = true,
                                showSecret = showSecret,
                                onToggleSecret = onToggleSecret,
                                onPaste = onPasteKey,
                                imeAction = ImeAction.Done,
                                onDone = { focusManager.clearFocus() },
                            )

                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(onClick = onToggleVolcAdv)
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (showVolcAdv) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = colors.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (showVolcAdv) "收起高级参数" else "高级参数（默认极速版 volc.bigasr.auc_turbo，一般无需改）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant,
                                )
                            }

                            AnimatedVisibility(visible = showVolcAdv) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = asrModel,
                                        onValueChange = onModelChange,
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = { Text("资源 ID (Resource ID)") },
                                        placeholder = { Text(AsrProvider.VOLCENGINE.defaultModel) },
                                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                                    )
                                    OutlinedTextField(
                                        value = asrAppId,
                                        onValueChange = onAppIdChange,
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = { Text("旧版 AppID（单 Key 模式留空）") },
                                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                                    )
                                    OutlinedTextField(
                                        value = asrUrl,
                                        onValueChange = onUrlChange,
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = { Text("接口地址") },
                                        placeholder = { Text(AsrProvider.VOLCENGINE.defaultUrl) },
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Uri,
                                            autoCorrectEnabled = false,
                                            imeAction = ImeAction.Done,
                                        ),
                                    )
                                }
                            }
                        }

                        AsrProvider.DASHSCOPE -> {
                            SecretOrUrlField(
                                value = asrKey,
                                onValueChange = onKeyChange,
                                label = "阿里云百炼 API Key",
                                placeholder = "粘贴百炼控制台创建的 API Key",
                                isSecret = true,
                                showSecret = showSecret,
                                onToggleSecret = onToggleSecret,
                                onPaste = onPasteKey,
                                imeAction = ImeAction.Next,
                                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                            )
                            OutlinedTextField(
                                value = asrModel,
                                onValueChange = onModelChange,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("模型名称") },
                                placeholder = { Text(AsrProvider.DASHSCOPE.defaultModel) },
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                            )
                            SecretOrUrlField(
                                value = asrUrl,
                                onValueChange = onUrlChange,
                                label = "原生接口地址（需与 Key 地域一致）",
                                placeholder = AsrProvider.DASHSCOPE.defaultUrl,
                                isSecret = false,
                                showSecret = true,
                                onToggleSecret = {},
                                onPaste = onPasteUrl,
                                imeAction = ImeAction.Done,
                                onDone = { focusManager.clearFocus() },
                            )
                            Text("直接发送 Ogg Opus，单段最长 5 分钟；支持分句时间戳和单段内说话人分离。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }

                        AsrProvider.OPENAI -> {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("一键填入常用平台参数：", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OPENAI_PRESETS.forEach { preset ->
                                        val matched = asrUrl.trim().trimEnd('/') == preset.baseUrl.trimEnd('/')
                                        FilterChip(
                                            selected = matched,
                                            onClick = { onApplyPreset(preset) },
                                            shape = RoundedCornerShape(8.dp),
                                            label = { Text(preset.name) },
                                        )
                                    }
                                }
                            }
                            SecretOrUrlField(
                                value = asrKey,
                                onValueChange = onKeyChange,
                                label = "API Key（本地免密服务可留空）",
                                placeholder = "sk-...",
                                isSecret = true,
                                showSecret = showSecret,
                                onToggleSecret = onToggleSecret,
                                onPaste = onPasteKey,
                                imeAction = ImeAction.Next,
                                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                            )
                            SecretOrUrlField(
                                value = asrUrl,
                                onValueChange = onUrlChange,
                                label = "接口根地址 (Base URL)",
                                placeholder = "https://api.siliconflow.cn/v1",
                                isSecret = false,
                                showSecret = true,
                                onToggleSecret = {},
                                onPaste = onPasteUrl,
                                imeAction = ImeAction.Next,
                                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                            )
                            OutlinedTextField(
                                value = asrModel,
                                onValueChange = onModelChange,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("模型名称") },
                                placeholder = { Text("FunAudioLLM/SenseVoiceSmall") },
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            )
                        }

                        AsrProvider.HARK_HTTP -> {
                            SecretOrUrlField(
                                value = asrUrl,
                                onValueChange = onUrlChange,
                                label = "自建服务地址",
                                placeholder = "http://hark-asr.local:8765",
                                isSecret = false,
                                showSecret = true,
                                onToggleSecret = {},
                                onPaste = onPasteUrl,
                                imeAction = ImeAction.Next,
                                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                            )
                            SecretOrUrlField(
                                value = asrKey,
                                onValueChange = onKeyChange,
                                label = "访问令牌 Bearer Token（可选）",
                                placeholder = "未设密码可留空",
                                isSecret = true,
                                showSecret = showSecret,
                                onToggleSecret = onToggleSecret,
                                onPaste = onPasteKey,
                                imeAction = ImeAction.Done,
                                onDone = { focusManager.clearFocus() },
                            )
                        }
                    }
                }
            }

            // 测试反馈面板
            AnimatedVisibility(visible = probeReport != null) {
                probeReport?.let { rep ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (rep.ok) colors.tertiaryContainer else colors.errorContainer,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = if (rep.ok) {
                                    buildString {
                                        append("连通正常 · ${rep.latencyMs}ms · 已自动启用")
                                        if (rep.speakerCount > 0) append(" · 区分 ${rep.speakerCount} 人")
                                    }
                                } else {
                                    "连接未通过，请核对上方参数"
                                },
                                style = MaterialTheme.typography.labelLarge.merge(TabularMono),
                                color = if (rep.ok) colors.onTertiaryContainer else colors.onErrorContainer,
                            )
                            Text(
                                text = if (rep.ok) "示例识别：${rep.text.ifBlank { "（无文本）" }}" else rep.error.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (rep.ok) colors.onTertiaryContainer else colors.onErrorContainer,
                            )
                        }
                    }
                }
            }

            // 主次操作行：一键「测试并启用」+ 离线「仅保存」
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onSaveOnly,
                    enabled = !probing && validationErr == null,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.pressScale(enabled = !probing && validationErr == null),
                ) {
                    Text(if (willSwitchMode) "直接启用" else "仅保存")
                }
                Button(
                    onClick = onProbeAndEnable,
                    enabled = !probing && validationErr == null,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).pressScale(enabled = !probing && validationErr == null),
                ) {
                    if (probing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正在验证示例音频…")
                    } else {
                        Text("测试并启用")
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideStep(num: String, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = num,
            style = MaterialTheme.typography.labelSmall.merge(TabularMono),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(top = 1.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SecretOrUrlField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    isSecret: Boolean,
    showSecret: Boolean,
    onToggleSecret: () -> Unit,
    onPaste: () -> Unit,
    isError: Boolean = false,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onNext: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = isError,
        visualTransformation = if (isSecret && !showSecret) PasswordVisualTransformation() else VisualTransformation.None,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        supportingText = supportingText?.let { msg -> { Text(msg) } },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isSecret) KeyboardType.Ascii else KeyboardType.Uri,
            autoCorrectEnabled = false,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onNext = onNext?.let { fn -> { fn() } },
            onDone = onDone?.let { fn -> { fn() } },
        ),
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                if (value.isBlank()) {
                    TextButton(
                        onClick = onPaste,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("粘贴", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "清空", modifier = Modifier.size(16.dp))
                    }
                    if (isSecret) {
                        IconButton(onClick = onToggleSecret, modifier = Modifier.size(34.dp)) {
                            Icon(
                                if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showSecret) "隐藏密钥" else "显示密钥",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun CloudMailboxConfigCard(
    serverUrl: String,
    phoneToken: String,
    e2eeKey: String,
    showSecrets: Boolean,
    keyErr: Boolean,
    canSave: Boolean,
    saveLabel: String,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onPasteUrl: () -> Unit,
    onPasteToken: () -> Unit,
    onPasteKey: () -> Unit,
    onToggleSecrets: () -> Unit,
    onGenerateKey: () -> Unit,
    onCopyKey: () -> Unit,
    onSave: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    SettingsGroupCard(
        title = "云端盲信箱与端到端密钥",
        subtitle = "录音在手机端加密后中转，服务器无密钥不可读；需与电脑端填写同一把端到端密钥",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SecretOrUrlField(
                value = serverUrl,
                onValueChange = onUrlChange,
                label = "信箱地址",
                placeholder = "https://hark.example.com",
                isSecret = false,
                showSecret = true,
                onToggleSecret = {},
                onPaste = onPasteUrl,
                imeAction = ImeAction.Next,
                onNext = { focusManager.moveFocus(FocusDirection.Down) },
            )
            SecretOrUrlField(
                value = phoneToken,
                onValueChange = onTokenChange,
                label = "手机访问令牌 (PHONE_TOKEN)",
                placeholder = "粘贴信箱服务端配置的令牌",
                isSecret = true,
                showSecret = showSecrets,
                onToggleSecret = onToggleSecrets,
                onPaste = onPasteToken,
                imeAction = ImeAction.Next,
                onNext = { focusManager.moveFocus(FocusDirection.Down) },
            )
            SecretOrUrlField(
                value = e2eeKey,
                onValueChange = onKeyChange,
                label = "端到端密钥 (E2EE_KEY)",
                placeholder = "点下方「生成新密钥」或粘贴已有密钥",
                isSecret = true,
                showSecret = showSecrets,
                onToggleSecret = onToggleSecrets,
                onPaste = onPasteKey,
                isError = keyErr,
                supportingText = if (keyErr) "格式无效：需 32 字节 Base64 或 64 位 hex" else null,
                imeAction = ImeAction.Done,
                onDone = { focusManager.clearFocus() },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onGenerateKey,
                    shape = RoundedCornerShape(8.dp),
                    leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null, Modifier.size(16.dp)) },
                    label = { Text("生成新密钥并复制") },
                )
                if (e2eeKey.isNotBlank()) {
                    AssistChip(
                        onClick = onCopyKey,
                        shape = RoundedCornerShape(8.dp),
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null, Modifier.size(16.dp)) },
                        label = { Text("复制密钥") },
                    )
                }
            }

            Button(
                onClick = onSave,
                enabled = canSave,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().pressScale(enabled = canSave),
            ) { Text(saveLabel) }
        }
    }
}
