@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package io.github.riggedlab.hark

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.Base64

@Composable
internal fun SettingsScreen(
    status: Status,
    whitelisted: Boolean,
    hasLocation: Boolean,
    unuploadedCount: Int,
    onBack: () -> Unit,
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
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var selectedMode by remember { mutableStateOf(Config.mode(ctx)) }
    val committedMode = Config.mode(ctx)

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
    var showVolcAdv by remember {
        mutableStateOf(
            initAsr.appId.isNotBlank() ||
                (initAsr.baseUrl.isNotBlank() && initAsr.baseUrl != AsrProvider.VOLCENGINE.defaultUrl),
        )
    }
    var probing by remember { mutableStateOf(false) }
    var probeReport by remember { mutableStateOf<AsrProbeReport?>(null) }
    var showInactiveModeConfig by remember { mutableStateOf(false) }

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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("设置", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "DT-1",
                            style = MaterialTheme.typography.labelSmall.merge(TabularMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. 工作模式切换卡
            item(key = "mode") {
                SettingsGroupCard(title = "转写路径") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        WorkMode.entries.forEachIndexed { idx, item ->
                            SegmentedButton(
                                selected = selectedMode == item,
                                onClick = {
                                    if (selectedMode != item) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        selectedMode = item
                                        showInactiveModeConfig = false
                                        val ready = if (item == WorkMode.CLOUD) Config.load(ctx) != null else AsrConfig.load(ctx) != null
                                        if (ready) {
                                            Config.setMode(ctx, item)
                                            onConfigChanged()
                                            notify("已切换为「${item.label}」")
                                        }
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = WorkMode.entries.size),
                            ) { Text(item.label) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    val targetReady = if (selectedMode == WorkMode.CLOUD) Config.load(ctx) != null else AsrConfig.load(ctx) != null
                    Text(
                        text = when {
                            selectedMode != committedMode && !targetReady ->
                                "填写下方配置并点「保存并启用」后正式切换；启用前仍保持「${committedMode.label}」。"
                            selectedMode == WorkMode.DIRECT_ASR -> buildString {
                                append("录音由手机直接发往下方所选服务商转写，不经过云端信箱。")
                                if (unuploadedCount > 0) {
                                    append("本机有 $unuploadedCount 段录音尚未同步，7 天内切回「云端信箱同步」会自动加密补传。")
                                } else {
                                    append("7 天内切回「云端信箱同步」会自动补传期间录音。")
                                }
                            }
                            else -> "录音在手机端加密后上传信箱，由你的电脑拉取转写并同步回手机。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selectedMode != committedMode && !targetReady) DeckTheme.colors.ledAmber
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 2. 当前所选模式的主配置卡
            item(key = "primary_config") {
                val willSwitchMode = selectedMode != committedMode
                if (selectedMode == WorkMode.DIRECT_ASR) {
                    DirectAsrConfigCard(
                        provider = provider,
                        asrUrl = asrUrl,
                        asrKey = asrKey,
                        asrAppId = asrAppId,
                        asrModel = asrModel,
                        showSecret = showAsrSecret,
                        showVolcAdv = showVolcAdv,
                        probing = probing,
                        probeReport = probeReport,
                        validationErr = asrValidationErr,
                        saveLabel = if (willSwitchMode) "保存并启用" else "保存配置",
                        onProviderChange = { next ->
                            val prev = provider
                            provider = next
                            if (asrUrl.isBlank() || asrUrl == prev.defaultUrl) asrUrl = next.defaultUrl
                            if (asrModel.isBlank() || asrModel == prev.defaultModel) asrModel = next.defaultModel
                            probeReport = null
                        },
                        onUrlChange = { asrUrl = it; probeReport = null },
                        onKeyChange = { asrKey = it; probeReport = null },
                        onAppIdChange = { asrAppId = it; probeReport = null },
                        onModelChange = { asrModel = it; probeReport = null },
                        onToggleSecret = { showAsrSecret = !showAsrSecret },
                        onToggleVolcAdv = { showVolcAdv = !showVolcAdv },
                        onProbe = {
                            probing = true
                            probeReport = null
                            scope.launch {
                                probeReport = withContext(Dispatchers.IO) { PhoneAsr.probe(ctx, draftAsr) }
                                probing = false
                            }
                        },
                        onSave = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            AsrConfig.save(ctx, draftAsr)
                            Config.setMode(ctx, WorkMode.DIRECT_ASR)
                            onConfigChanged()
                            notify(if (willSwitchMode) "已保存并切换为「手机直连转写」" else "已保存直连转写配置")
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
                        saveLabel = if (willSwitchMode) "保存并启用" else "保存信箱配置",
                        onUrlChange = { serverUrl = it },
                        onTokenChange = { phoneToken = it },
                        onKeyChange = { e2eeKey = it },
                        onToggleSecrets = { showCloudSecrets = !showCloudSecrets },
                        onGenerateKey = {
                            val b = ByteArray(32).also { SecureRandom().nextBytes(it) }
                            e2eeKey = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
                            showCloudSecrets = true
                            notify("已生成新密钥，请同步填入电脑端并点保存")
                        },
                        onCopyKey = {
                            if (e2eeKey.isNotBlank()) {
                                clipboard.setText(AnnotatedString(e2eeKey.trim()))
                                notify("已复制端到端密钥")
                            }
                        },
                        onSave = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            Config.save(ctx, serverUrl, phoneToken, e2eeKey)
                            Config.setMode(ctx, WorkMode.CLOUD)
                            onConfigChanged()
                            notify(if (willSwitchMode) "已保存并切换为「云端信箱同步」" else "已保存云端信箱配置")
                        },
                    )
                }
            }

            // 3. 另一路径折叠预配入口
            item(key = "secondary_config") {
                val otherLabel = if (selectedMode == WorkMode.DIRECT_ASR) "预配云端信箱凭据" else "预配手机直连服务商"
                OutlinedCard(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                        headlineContent = { Text(otherLabel, style = MaterialTheme.typography.titleSmall) },
                        supportingContent = {
                            Text(
                                if (selectedMode == WorkMode.DIRECT_ASR) "提前填好信箱与密钥，切模式即可自动同步"
                                else "提前填好豆包或 OpenAI Key，出门一键切换直连转写",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = { showInactiveModeConfig = !showInactiveModeConfig }) {
                                Text(if (showInactiveModeConfig) "收起" else "展开")
                            }
                        },
                    )
                    AnimatedVisibility(visible = showInactiveModeConfig) {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(12.dp))
                            if (selectedMode == WorkMode.DIRECT_ASR) {
                                CloudMailboxFields(
                                    serverUrl = serverUrl,
                                    phoneToken = phoneToken,
                                    e2eeKey = e2eeKey,
                                    showSecrets = showCloudSecrets,
                                    keyErr = keyErr,
                                    canSave = cloudComplete,
                                    saveLabel = "保存备用凭据",
                                    onUrlChange = { serverUrl = it },
                                    onTokenChange = { phoneToken = it },
                                    onKeyChange = { e2eeKey = it },
                                    onToggleSecrets = { showCloudSecrets = !showCloudSecrets },
                                    onGenerateKey = {
                                        val b = ByteArray(32).also { SecureRandom().nextBytes(it) }
                                        e2eeKey = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
                                        showCloudSecrets = true
                                    },
                                    onCopyKey = {
                                        if (e2eeKey.isNotBlank()) {
                                            clipboard.setText(AnnotatedString(e2eeKey.trim()))
                                            notify("已复制端到端密钥")
                                        }
                                    },
                                    onSave = {
                                        Config.save(ctx, serverUrl, phoneToken, e2eeKey)
                                        onConfigChanged()
                                        notify("已保存云端信箱备用凭据")
                                    },
                                )
                            } else {
                                DirectAsrFields(
                                    provider = provider,
                                    asrUrl = asrUrl,
                                    asrKey = asrKey,
                                    asrAppId = asrAppId,
                                    asrModel = asrModel,
                                    showSecret = showAsrSecret,
                                    showVolcAdv = showVolcAdv,
                                    probing = probing,
                                    probeReport = probeReport,
                                    validationErr = asrValidationErr,
                                    saveLabel = "保存备用配置",
                                    onProviderChange = { next ->
                                        val prev = provider
                                        provider = next
                                        if (asrUrl.isBlank() || asrUrl == prev.defaultUrl) asrUrl = next.defaultUrl
                                        if (asrModel.isBlank() || asrModel == prev.defaultModel) asrModel = next.defaultModel
                                        probeReport = null
                                    },
                                    onUrlChange = { asrUrl = it; probeReport = null },
                                    onKeyChange = { asrKey = it; probeReport = null },
                                    onAppIdChange = { asrAppId = it; probeReport = null },
                                    onModelChange = { asrModel = it; probeReport = null },
                                    onToggleSecret = { showAsrSecret = !showAsrSecret },
                                    onToggleVolcAdv = { showVolcAdv = !showVolcAdv },
                                    onProbe = {
                                        probing = true
                                        probeReport = null
                                        scope.launch {
                                            probeReport = withContext(Dispatchers.IO) { PhoneAsr.probe(ctx, draftAsr) }
                                            probing = false
                                        }
                                    },
                                    onSave = {
                                        AsrConfig.save(ctx, draftAsr)
                                        onConfigChanged()
                                        notify("已保存直连转写备用配置")
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // 4. 硬件与系统权限
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
                        headlineContent = { Text(status.deviceName ?: "Hark DT-1 随身拾音器") },
                        supportingContent = { Text("当前状态：${status.link.label}") },
                        trailingContent = {
                            OutlinedButton(onClick = {
                                onRepairBle()
                                notify("已发起蓝牙重新配对")
                            }) { Text("重新配对") }
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
                                FilledTonalButton(onClick = onRequestBatteryWhitelist) { Text("去开启") }
                            }
                        },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    ListItem(
                        colors = rowColors,
                        leadingContent = { Icon(Icons.Rounded.Place, contentDescription = null, tint = DeckTheme.colors.place) },
                        headlineContent = { Text("自动记录对话地点") },
                        supportingContent = { Text("仅存储在手机本机，绝不上传云端信箱") },
                        trailingContent = {
                            if (hasLocation) {
                                Text("已授权", style = MaterialTheme.typography.labelMedium, color = DeckTheme.colors.ledGreen)
                            } else {
                                OutlinedButton(onClick = onRequestLocation) { Text("开启定位") }
                            }
                        },
                    )
                }
            }

            // 5. 版本与更新
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
                                "Hark · 谛听 v${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.titleSmall.merge(TabularMono),
                            )
                        },
                        supportingContent = {
                            Text(updateHint ?: "录音本机保留 ${Recordings.RETENTION_DAYS} 天 · 转写文字永久保留")
                        },
                        trailingContent = {
                            val target = updateUrl
                            if (target != null) {
                                Button(onClick = { onOpenUrl(target) }) { Text("下载新版") }
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
private fun SettingsGroupCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    showVolcAdv: Boolean,
    probing: Boolean,
    probeReport: AsrProbeReport?,
    validationErr: String?,
    saveLabel: String,
    onProviderChange: (AsrProvider) -> Unit,
    onUrlChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onAppIdChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onToggleSecret: () -> Unit,
    onToggleVolcAdv: () -> Unit,
    onProbe: () -> Unit,
    onSave: () -> Unit,
) {
    SettingsGroupCard(title = "直连转写服务", subtitle = "点「测试转写」会用当前填写的参数发送内置示例音频") {
        DirectAsrFields(
            provider = provider,
            asrUrl = asrUrl,
            asrKey = asrKey,
            asrAppId = asrAppId,
            asrModel = asrModel,
            showSecret = showSecret,
            showVolcAdv = showVolcAdv,
            probing = probing,
            probeReport = probeReport,
            validationErr = validationErr,
            saveLabel = saveLabel,
            onProviderChange = onProviderChange,
            onUrlChange = onUrlChange,
            onKeyChange = onKeyChange,
            onAppIdChange = onAppIdChange,
            onModelChange = onModelChange,
            onToggleSecret = onToggleSecret,
            onToggleVolcAdv = onToggleVolcAdv,
            onProbe = onProbe,
            onSave = onSave,
        )
    }
}

@Composable
private fun DirectAsrFields(
    provider: AsrProvider,
    asrUrl: String,
    asrKey: String,
    asrAppId: String,
    asrModel: String,
    showSecret: Boolean,
    showVolcAdv: Boolean,
    probing: Boolean,
    probeReport: AsrProbeReport?,
    validationErr: String?,
    saveLabel: String,
    onProviderChange: (AsrProvider) -> Unit,
    onUrlChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onAppIdChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onToggleSecret: () -> Unit,
    onToggleVolcAdv: () -> Unit,
    onProbe: () -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AsrProvider.entries.forEach { p ->
                FilterChip(
                    selected = provider == p,
                    onClick = { onProviderChange(p) },
                    label = { Text(p.shortLabel) },
                )
            }
        }
        when (provider) {
            AsrProvider.VOLCENGINE -> {
                OutlinedTextField(
                    value = asrKey,
                    onValueChange = onKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    label = { Text("豆包语音 API Key / Access Token") },
                    supportingText = { Text("请填「豆包语音控制台」Key（与火山方舟 Key 不通用）") },
                    trailingIcon = {
                        IconButton(onClick = onToggleSecret) {
                            Icon(
                                if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showSecret) "隐藏密钥" else "显示密钥",
                            )
                        }
                    },
                )
                OutlinedTextField(
                    value = asrModel,
                    onValueChange = onModelChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("资源 ID (Resource ID)") },
                    placeholder = { Text(AsrProvider.VOLCENGINE.defaultModel) },
                )
                AnimatedVisibility(visible = showVolcAdv) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = asrAppId,
                            onValueChange = onAppIdChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("旧版 AppID（单 Key 模式留空）") },
                        )
                        OutlinedTextField(
                            value = asrUrl,
                            onValueChange = onUrlChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("接口地址") },
                            placeholder = { Text(AsrProvider.VOLCENGINE.defaultUrl) },
                        )
                    }
                }
                AssistChip(
                    onClick = onToggleVolcAdv,
                    leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null, Modifier.size(16.dp)) },
                    label = { Text(if (showVolcAdv) "收起旧版 AppID 与地址" else "旧版 AppID 鉴权 / 自定义地址") },
                )
            }
            AsrProvider.OPENAI -> {
                OutlinedTextField(
                    value = asrUrl,
                    onValueChange = onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("接口根地址 (Base URL)") },
                    placeholder = { Text("https://api.openai.com/v1") },
                )
                OutlinedTextField(
                    value = asrKey,
                    onValueChange = onKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    label = { Text("API Key（本地免密服务可留空）") },
                    trailingIcon = {
                        IconButton(onClick = onToggleSecret) {
                            Icon(
                                if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showSecret) "隐藏密钥" else "显示密钥",
                            )
                        }
                    },
                )
                OutlinedTextField(
                    value = asrModel,
                    onValueChange = onModelChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("模型名称") },
                    placeholder = { Text("gpt-4o-mini-transcribe / whisper-large-v3-turbo") },
                )
            }
            AsrProvider.HARK_HTTP -> {
                OutlinedTextField(
                    value = asrUrl,
                    onValueChange = onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("自建服务地址") },
                    placeholder = { Text("http://hark-asr.local:8787") },
                    supportingText = { Text("支持局域网或 Tailscale 直连家里的本地声纹转写服务") },
                )
                OutlinedTextField(
                    value = asrKey,
                    onValueChange = onKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    label = { Text("Bearer Token（可选）") },
                    trailingIcon = {
                        IconButton(onClick = onToggleSecret) {
                            Icon(
                                if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showSecret) "隐藏密钥" else "显示密钥",
                            )
                        }
                    },
                )
            }
        }

        probeReport?.let { rep ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (rep.ok) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer,
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (rep.ok) {
                            buildString {
                                append("连通正常 · ${rep.latencyMs}ms")
                                if (rep.speakerCount > 0) append(" · 区分 ${rep.speakerCount} 位对话人")
                            }
                        } else {
                            "连接未通过"
                        },
                        style = MaterialTheme.typography.labelLarge.merge(TabularMono),
                        color = if (rep.ok) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = if (rep.ok) "识别结果：${rep.text.ifBlank { "（无文本）" }}" else rep.error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (rep.ok) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onProbe,
                enabled = !probing && validationErr == null,
                modifier = Modifier.weight(1f),
            ) { Text(if (probing) "测试中…" else "测试转写") }
            Button(
                onClick = onSave,
                enabled = validationErr == null,
                modifier = Modifier.weight(1f),
            ) { Text(saveLabel) }
        }
    }
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
    onToggleSecrets: () -> Unit,
    onGenerateKey: () -> Unit,
    onCopyKey: () -> Unit,
    onSave: () -> Unit,
) {
    SettingsGroupCard(title = "云端盲信箱与端到端密钥", subtitle = "服务器不保存解密密钥，仅持有同一密钥的设备可读取") {
        CloudMailboxFields(
            serverUrl = serverUrl,
            phoneToken = phoneToken,
            e2eeKey = e2eeKey,
            showSecrets = showSecrets,
            keyErr = keyErr,
            canSave = canSave,
            saveLabel = saveLabel,
            onUrlChange = onUrlChange,
            onTokenChange = onTokenChange,
            onKeyChange = onKeyChange,
            onToggleSecrets = onToggleSecrets,
            onGenerateKey = onGenerateKey,
            onCopyKey = onCopyKey,
            onSave = onSave,
        )
    }
}

@Composable
private fun CloudMailboxFields(
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
    onToggleSecrets: () -> Unit,
    onGenerateKey: () -> Unit,
    onCopyKey: () -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = serverUrl,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("信箱地址") },
            placeholder = { Text("https://hark.example.com") },
        )
        OutlinedTextField(
            value = phoneToken,
            onValueChange = onTokenChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showSecrets) VisualTransformation.None else PasswordVisualTransformation(),
            label = { Text("手机访问令牌 (PHONE_TOKEN)") },
            trailingIcon = {
                IconButton(onClick = onToggleSecrets) {
                    Icon(
                        if (showSecrets) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (showSecrets) "隐藏凭据" else "显示凭据",
                    )
                }
            },
        )
        OutlinedTextField(
            value = e2eeKey,
            onValueChange = onKeyChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = keyErr,
            visualTransformation = if (showSecrets) VisualTransformation.None else PasswordVisualTransformation(),
            label = { Text("端到端密钥 (E2EE_KEY)") },
            supportingText = {
                Text(if (keyErr) "格式无效：需 32 字节 Base64 或 64 位 hex" else "需与电脑端填写同一把密钥")
            },
            trailingIcon = {
                IconButton(onClick = onToggleSecrets) {
                    Icon(
                        if (showSecrets) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (showSecrets) "隐藏凭据" else "显示凭据",
                    )
                }
            },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = onGenerateKey,
                leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null, Modifier.size(16.dp)) },
                label = { Text("生成新密钥") },
            )
            if (e2eeKey.isNotBlank()) {
                AssistChip(
                    onClick = onCopyKey,
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null, Modifier.size(16.dp)) },
                    label = { Text("复制密钥") },
                )
            }
        }
        Button(
            onClick = onSave,
            enabled = canSave,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(saveLabel) }
    }
}
