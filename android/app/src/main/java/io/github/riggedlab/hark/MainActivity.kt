@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package io.github.riggedlab.hark

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.MergeType
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private enum class RootDestination { MAIN, RECORDINGS, SETTINGS }

// ───────────────────── 后台预构建的 @Immutable 视图模型（分页懒加载 + 扁平化虚拟列表） ─────────────────────

@Immutable
internal data class LineUi(
    val rec: Recording,
    val seg: Segment,
    val at: LocalDateTime,
    val endAt: LocalDateTime,
)

@Immutable
internal data class ParaUi(
    val key: String,
    val lines: List<LineUi>,
    val who: String?,
    val speakerId: String?,
    val isSelf: Boolean,
    val text: String,
    val textLower: String,
    val whoLower: String?,
    val startAt: LocalDateTime,
    val timeHm: String,
    val durationSec: Int,
    val hasAnyAudio: Boolean,
    val avgDb: Float?,
    val proximity: String?,
    val voiceCue: String?,
    val affectCue: String?,
    val isPositive: Boolean,
    val isTense: Boolean,
    val isBackgroundNoise: Boolean,
)

@Immutable
internal data class EventUi(
    val key: String,
    val day: LocalDate,
    val startAt: LocalDateTime,
    val endAt: LocalDateTime,
    val newestHm: String,
    val timeLabel: String,
    val durationLabel: String?,
    val place: Place?,
    val peopleList: List<String>,
    val peopleText: String,
    val headline: String,
    val previewQuote: String,
    val isCompactNote: Boolean,
    val sceneSummary: String?,
    val eventVoiceCue: String?,
    val eventAffectCue: String?,
    val isPositive: Boolean,
    val isTense: Boolean,
    /** 按时间倒序排列（从近到远：最新说的一句排第一），确保信息流自上而下严格由近及远 */
    val parasNewestFirst: List<ParaUi>,
    val fgParaCount: Int,
    val bgParaCount: Int,
    val firstAudioLine: LineUi?,
    val copyText: String,
    val searchBlob: String,
)

@Immutable
internal data class AmbientSpanUi(
    val key: String,
    val day: LocalDate,
    val startAt: LocalDateTime,
    val endAt: LocalDateTime,
    val newestHm: String,
    val spanLabel: String,
    val summaryText: String,
    val searchBlob: String,
)

@Immutable
internal sealed interface StreamNodeUi {
    val key: String
    /** 统一用段末时间做倒序基准：刚刚结束的对话永远排在更早之前 */
    val sortTime: LocalDateTime
    val searchBlob: String

    @Immutable
    data class Conversation(val event: EventUi) : StreamNodeUi {
        override val key: String get() = event.key
        override val sortTime: LocalDateTime get() = event.endAt
        override val searchBlob: String get() = event.searchBlob
    }

    @Immutable
    data class Ambient(val span: AmbientSpanUi) : StreamNodeUi {
        override val key: String get() = span.key
        override val sortTime: LocalDateTime get() = span.endAt
        override val searchBlob: String get() = span.searchBlob
    }
}

/** 扁平化气泡流条目：每颗气泡独立进 LazyColumn 回收池，杜绝单卡片内百条气泡一次性全量渲染 */
@Immutable
private sealed interface FlatDialogueRow {
    val key: String
    val contentType: String

    @Immutable
    data class DayTitle(val dayKey: String, val title: String, val countLabel: String) : FlatDialogueRow {
        override val key: String get() = "dt_$dayKey"
        override val contentType: String get() = "day_title"
    }

    @Immutable
    data class AmbientRow(val span: AmbientSpanUi) : FlatDialogueRow {
        override val key: String get() = span.key
        override val contentType: String get() = "ambient_row"
    }

    @Immutable
    data class EventAnchor(
        val eventKey: String,
        val timeLabel: String,
        val place: Place?,
        val sceneSummary: String?,
        val copyText: String,
    ) : FlatDialogueRow {
        override val key: String get() = "anc_$eventKey"
        override val contentType: String get() = "event_anchor"
    }

    @Immutable
    data class Bubble(val para: ParaUi) : FlatDialogueRow {
        override val key: String get() = para.key
        override val contentType: String get() = "bubble"
    }

    @Immutable
    data class FoldToggle(
        val eventKey: String,
        val bgCount: Int,
        val kindText: String,
        val expanded: Boolean,
    ) : FlatDialogueRow {
        override val key: String get() = "fold_$eventKey"
        override val contentType: String get() = "fold_toggle"
    }
}

/** 扁平化无框左轴流条目：连展开后的逐句也拆为独立 LazyColumn 行，长对话展开零卡顿 */
@Immutable
private sealed interface FlatEditorialRow {
    val key: String
    val contentType: String

    @Immutable
    data class DayTitle(
        val dayKey: String,
        val title: String,
        val countLabel: String,
        val digest: DayDigestStats?,
    ) : FlatEditorialRow {
        override val key: String get() = "ed_dt_$dayKey"
        override val contentType: String get() = "ed_day"
    }

    @Immutable
    data class AmbientNode(val span: AmbientSpanUi, val drawLine: Boolean) : FlatEditorialRow {
        override val key: String get() = span.key
        override val contentType: String get() = "ed_ambient"
    }

    @Immutable
    data class EventHead(
        val ev: EventUi,
        val isOpen: Boolean,
        val forceExpand: Boolean,
        val drawLine: Boolean,
    ) : FlatEditorialRow {
        override val key: String get() = ev.key
        override val contentType: String get() = "ed_head"
    }

    @Immutable
    data class ExpandedTurn(
        val eventKey: String,
        val para: ParaUi,
        val drawLine: Boolean,
    ) : FlatEditorialRow {
        override val key: String get() = "ed_t_${para.key}"
        override val contentType: String get() = "ed_turn"
    }

    @Immutable
    data class ExpandedFooter(
        val eventKey: String,
        val bgCount: Int,
        val bgShown: Boolean,
        val canCollapse: Boolean,
        val drawLine: Boolean,
    ) : FlatEditorialRow {
        override val key: String get() = "ed_f_$eventKey"
        override val contentType: String get() = "ed_foot"
    }
}

@Immutable
internal data class DayDigestStats(
    val talkMinutes: Int,
    val peopleCount: Int,
    val mutedNoiseCount: Int,
    val vibeLabel: String,
    val vibePositive: Boolean,
)

@Immutable
internal data class DayEventsGroup(
    val day: LocalDate,
    val title: String,
    val countLabel: String,
    val digest: DayDigestStats,
    val events: List<EventUi>,
    val nodes: List<StreamNodeUi>,
)

internal enum class BaseChunkStatus {
    TRANSCRIBED,
    TRANSCRIBED_EMPTY,
    AUDIO_EXPIRED,
    SILENT,
    FAR_SKIPPED,
    BAD_AUDIO,
    UPLOADED,
    PENDING,
}

@Immutable
internal data class RecordingCardUi(
    val rec: Recording,
    val timeRangeLabel: String,
    val durationLabel: String,
    val placeLabel: String?,
    val sceneSummary: String?,
    val affectCue: String?,
    val isPositive: Boolean,
    val transcriptPreview: String?,
    val baseStatus: BaseChunkStatus,
    val searchBlob: String,
)

@Immutable
internal data class DayRecordingsGroup(
    val day: LocalDate,
    val title: String,
    val summaryLabel: String,
    val items: List<RecordingCardUi>,
)

@Immutable
internal data class PersonCardUi(
    val speaker: Speaker,
    val isSelf: Boolean,
    val count: Int,
    val aliases: List<Speaker>,
    val quote: String?,
    val clip: Pair<Recording, Segment>?,
)

@Immutable
internal data class PlaceCardUi(
    val place: Place,
    val statsLine: String,
    val aliases: List<Place>,
)

@Immutable
internal data class FeedFilterLens(
    val id: String,
    val label: String,
)

@Immutable
internal data class FeedPageSnapshot(
    val signature: String = "",
    val totalRecordingCount: Int = 0,
    val unuploadedCount: Int = 0,
    val loadedStubCount: Int = 0,
    val hasMoreHistory: Boolean = false,
    val dayEvents: List<DayEventsGroup> = emptyList(),
    val quickLenses: List<FeedFilterLens> = emptyList(),
)

@Immutable
private data class HeaderStatusSlice(
    val link: LinkState,
    val batteryPct: Int?,
    val hasWarning: Boolean,
    val transcribing: Boolean,
)

@Immutable
private data class ActiveSyncSlice(
    val localAsrRunning: String?,
    val hasLocalAsrError: Boolean,
    val workerOnline: Boolean,
    val workerPhase: String,
    val workerCurrent: String?,
    val mailboxReachable: Boolean?,
)

class MainActivity : ComponentActivity() {

    private lateinit var player: Player
    private lateinit var uploader: Uploader
    private var editing by mutableStateOf<Speaker?>(null)
    private var merging by mutableStateOf<Speaker?>(null)
    private var editingPlace by mutableStateOf<Place?>(null)
    private var mergingPlace by mutableStateOf<Place?>(null)
    private var refreshToken by mutableIntStateOf(0)
    private var configVersion by mutableIntStateOf(0)
    private var hasLocation by mutableStateOf(false)
    private var startAction: String? = null
    private var showBlePermissionDialog by mutableStateOf(false)
    private var blePermissionNeedsSettings by mutableStateOf(false)
    private var destination by mutableStateOf(RootDestination.MAIN)

    private val permLauncher = registerForActivityResult(RequestMultiplePermissions()) { grants ->
        hasLocation = Places.hasPermission(this)
        if (BlePermissions.hasRequired(Build.VERSION.SDK_INT) { grants[it] == true }) {
            startRecorder(startAction)
        } else {
            showBlePermissionDenied()
        }
    }

    private val locLauncher = registerForActivityResult(RequestMultiplePermissions()) {
        hasLocation = Places.hasPermission(this)
        if (hasLocation) {
            if (AppState.status.value.link != LinkState.STOPPED) startRecorder(RecorderService.ACTION_REFRESH)
            else Places.requestFix(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        player = Player()
        uploader = Uploader(this)
        hasLocation = Places.hasPermission(this)
        Places.backfill(this)
        Voiceprint.triggerBackgroundBootstrap(this)
        setContent { DitingTheme { RootHost() } }
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }

    private fun neededPermissions(): Array<String> = buildList {
        addAll(BlePermissions.required(Build.VERSION.SDK_INT))
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun requestStart(repair: Boolean = false) {
        startAction = if (repair) RecorderService.ACTION_REPAIR else null
        showBlePermissionDialog = false
        permLauncher.launch(neededPermissions())
    }

    private fun requestLocation() = locLauncher.launch(
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    )

    private fun startRecorder(action: String? = null) {
        if (!BlePermissions.hasRequired(this)) {
            showBlePermissionDenied()
            return
        }
        try {
            startForegroundService(Intent(this, RecorderService::class.java).setAction(action))
        } catch (_: SecurityException) {
            showBlePermissionDenied()
        } catch (e: IllegalStateException) {
            android.util.Log.w("RecorderService", "无法从当前状态启动前台录音服务", e)
            AppState.update { it.copy(message = "无法启动录音，请重试") }
        }
    }

    private fun showBlePermissionDenied(message: String = BlePermissions.DENIED_MESSAGE) {
        if (message == BlePermissions.REVOKED_MESSAGE) startAction = null
        blePermissionNeedsSettings = BlePermissions.required(Build.VERSION.SDK_INT).any {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                !shouldShowRequestPermissionRationale(it)
        }
        AppState.update { it.copy(message = message) }
        showBlePermissionDialog = true
    }

    private fun stopRecorder() =
        startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))

    private fun isBatteryWhitelisted() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun requestBatteryWhitelist() = startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
    )

    // ───────────────────────────── 分页懒加载快照构建器（首屏只读最新页，按需渐进加载） ─────────────────────────────

    private fun computeBaseSignature(textVer: Int, cfgVer: Int, manualVer: Int): String {
        val chunkDir = RecorderService.chunkDir(this)
        val opusNames = chunkDir.list() ?: emptyArray()
        var opusCount = 0
        var newestName = ""
        for (n in opusNames) {
            if (n.endsWith(".opus")) {
                opusCount++
                if (n > newestName) newestName = n
            }
        }
        val textCount = Transcripts.dir(this).list()?.count { it.endsWith(".json") } ?: 0
        val marks = uploader.snapshotMarks()
        val spkMacMod = java.io.File(filesDir, "speakers_mac.json").lastModified()
        val spkLocalMod = java.io.File(filesDir, "speakers_local.json").lastModified()
        val spkPhoneMod = java.io.File(filesDir, "speakers_phone.json").lastModified()
        val placeMod = java.io.File(filesDir, "places/places.json").lastModified()
        val fixMod = java.io.File(filesDir, "places/fixes.jsonl").lastModified()
        val sceneSig = SceneAndMood.sceneSignature(this)
        return "$opusCount:$newestName:$textCount:$sceneSig:${marks.uploaded.size}:${marks.silent.size}:${marks.farSkipped.size}:${marks.badAudio}:$spkMacMod:$spkLocalMod:$spkPhoneMod:$placeMod:$fixMod:$textVer:$cfgVer:$manualVer"
    }

    private fun resolveInRoster(id: String?, roster: Map<String, Speaker>): Speaker? {
        var cur = id?.let { roster[it] } ?: return null
        val seen = HashSet<String>(4)
        while (cur.mergedInto != null && seen.add(cur.id)) cur = roster[cur.mergedInto] ?: break
        return cur
    }

    private fun detectSelfSpeakerIds(roster: Map<String, Speaker>): Set<String> {
        val explicit = roster.values
            .filter { it.mergedInto == null && isSelfDisplayName(it.name) }
            .map { it.id }
            .toSet()
        if (explicit.isNotEmpty()) return explicit
        val fallback = roster.values.firstOrNull {
            it.mergedInto == null && (it.id == "spk_001" || it.id == "spk_01") && it.name == null
        }
        return if (fallback != null) setOf(fallback.id) else emptySet()
    }

    /**
     * 仅加载最新 chunkBudget 条（若全是静音则自动多扫一段确保首屏有内容），
     * 绝不在冷启动时全量解析几百条历史转写与 Ogg 文件头。
     */
    private fun buildFeedPageSnapshot(
        sig: String,
        chunkBudget: Int,
        fullArchiveScan: Boolean,
    ): FeedPageSnapshot {
        val stubsDesc = Recordings.listStubsNewestFirst(this) // 严格从近到远（最新在前）
        val marks = uploader.snapshotMarks()
        val roster = Speakers.all(this)
        val selfIds = detectSelfSpeakerIds(roster)

        val selectedStubsDesc = ArrayList<RecordingStub>(minOf(stubsDesc.size, chunkBudget + 32))
        var spokenFound = 0
        for (idx in stubsDesc.indices) {
            val st = stubsDesc[idx]
            selectedStubsDesc.add(st)
            val likelySilent = st.name in marks.silent || st.name in marks.farSkipped
            if (!likelySilent) spokenFound++
            if (!fullArchiveScan && selectedStubsDesc.size >= chunkBudget &&
                (spokenFound >= MIN_SPOKEN_CHUNKS_FIRST_PAGE || selectedStubsDesc.size >= chunkBudget + MAX_SILENT_SKIP_EXTRA)
            ) {
                break
            }
        }
        val hasMore = selectedStubsDesc.size < stubsDesc.size

        val pageRecsDesc = selectedStubsDesc.map { Recordings.resolveRecording(this, it) }
        val transcribedNames = HashSet<String>(pageRecsDesc.size)

        // 先按时间正序聚合紧凑对话段，最后全面倒序输出，保证段间与段内自上而下全部从近到远！
        val lines = ArrayList<LineUi>(pageRecsDesc.size * 4)
        for (r in pageRecsDesc.asReversed()) {
            val tr = Transcripts.get(this, r.name) ?: continue
            var hasSeg = false
            for (seg in tr.segments) {
                if (seg.text.isNotBlank()) {
                    hasSeg = true
                    lines.add(
                        LineUi(
                            rec = r,
                            seg = seg,
                            at = r.start.plusNanos((seg.start * 1e9).toLong()),
                            endAt = r.start.plusNanos((seg.end * 1e9).toLong()),
                        ),
                    )
                }
            }
            if (hasSeg) transcribedNames.add(r.name)
        }
        lines.sortBy { it.at }

        // 紧凑对话切段（间隔 >75s 或单段累计满 4 分钟换气即自然分段，防止数小时粘成一坨把最新消息挤下去）
        val eventGroups = ArrayList<MutableList<LineUi>>()
        for (l in lines) {
            val cur = eventGroups.lastOrNull()
            val gapSec = cur?.let { Duration.between(it.last().endAt, l.at).seconds } ?: Long.MAX_VALUE
            val spanSec = cur?.let { Duration.between(it.first().at, l.endAt).seconds } ?: 0L
            if (cur == null || gapSec > EVENT_GAP_S || (spanSec >= MAX_EVENT_SPAN_S && gapSec >= 3L)) {
                eventGroups.add(mutableListOf(l))
            } else {
                cur.add(l)
            }
        }

        val allEventsAsc = eventGroups.map { g ->
            val paras = ArrayList<MutableList<LineUi>>()
            val paraMeta = ArrayList<Triple<String?, String?, String>>()
            for (l in g) {
                val rawSpk = l.seg.speaker
                val resolved = if (roster.isNotEmpty()) resolveInRoster(rawSpk, roster) else null
                val localIdx = rawSpk?.removePrefix("other_")?.toIntOrNull()
                val dispName = when {
                    resolved != null -> when {
                        resolved.name != null -> resolved.name
                        resolved.id in selfIds -> "我"
                        else -> resolved.defaultName
                    }
                    rawSpk in selfIds -> "我"
                    localIdx != null -> {
                        val dispNum = if (localIdx <= 0) localIdx + 1 else localIdx
                        "对话人 $dispNum"
                    }
                    else -> null
                }
                val meta = when {
                    resolved != null -> Triple(dispName, resolved.id, "global:${resolved.id}")
                    localIdx != null -> Triple(dispName, null, "local:${l.rec.name}:$localIdx")
                    rawSpk != null -> Triple(dispName, null, "raw:${l.rec.name}:$rawSpk")
                    else -> Triple(null, null, "unknown")
                }
                val lastLines = paras.lastOrNull()
                val lastMeta = paraMeta.lastOrNull()
                val gap = lastLines?.let { Duration.between(it.last().endAt, l.at).toMillis() / 1000.0 }
                val prevDb = lastLines?.last()?.seg?.db
                val curDb = l.seg.db
                val dbConsistent = prevDb == null || curDb == null || abs(prevDb - curDb) < 6.0f
                val prevProx = lastLines?.last()?.seg?.proximity
                val curProx = l.seg.proximity
                val proxConsistent = prevProx == null || curProx == null || prevProx == curProx
                val prevMood = lastLines?.last()?.seg?.mood
                val curMood = l.seg.mood
                val oppositePolarity = (prevMood == "positive" && curMood == "negative") ||
                    (prevMood == "negative" && curMood == "positive")
                if (lastLines != null && lastMeta != null && lastMeta.third == meta.third && gap != null && gap < PARA_GAP_S && dbConsistent && proxConsistent && !oppositePolarity) {
                    lastLines.add(l)
                } else {
                    paras.add(mutableListOf(l))
                    paraMeta.add(meta)
                }
            }

            val paraUisAsc = paras.mapIndexed { idx, pLines ->
                val (who, spkId, _) = paraMeta[idx]
                val isSelf = (spkId != null && spkId in selfIds) || isSelfDisplayName(who)
                val joined = pLines.joinToString("") { it.seg.text.trim() }
                val avgDb = computeEnergyWeightedDb(pLines)
                val prox = computeDominantProximity(pLines)
                val protectedSpk = isSelf || Proximity.isProtectedSpeakerId(spkId, roster)
                val isBg = (prox == Proximity.FAR || prox == Proximity.MEDIA) && !protectedSpk
                val moodPair = computeWeightedMood(pLines)
                val vTags = pLines.mapNotNull { it.seg.vocalTag }.distinct().take(2)
                val vCue = humanVoiceCue(prox, avgDb)
                val aCue = humanAffectCue(moodPair?.first, moodPair?.second, vTags)
                val firstL = pLines.first()
                val durS = (pLines.last().seg.end - firstL.seg.start).roundToInt().coerceAtLeast(1)
                ParaUi(
                    key = "p_${firstL.rec.name}_${firstL.seg.start}_$idx",
                    lines = pLines,
                    who = who,
                    speakerId = spkId,
                    isSelf = isSelf,
                    text = joined,
                    textLower = joined.lowercase(),
                    whoLower = who?.lowercase(),
                    startAt = firstL.at,
                    timeHm = firstL.at.format(HM),
                    durationSec = durS,
                    hasAnyAudio = pLines.any { it.rec.hasAudio },
                    avgDb = avgDb,
                    proximity = prox,
                    voiceCue = vCue,
                    affectCue = aCue,
                    isPositive = moodPair?.first == "positive" || "笑声" in vTags,
                    isTense = moodPair?.first == "negative",
                    isBackgroundNoise = isBg,
                )
            }

            val fgParasAsc = paraUisAsc.filter { !it.isBackgroundNoise }
            val mainParasAsc = fgParasAsc.ifEmpty { paraUisAsc }
            val mainLines = mainParasAsc.flatMap { it.lines }
            val start = g.first().at
            val end = g.last().endAt
            val day = end.toLocalDate()
            val sHm = start.format(HM)
            val eHm = end.format(HM)
            val timeLabel = if (sHm == eHm) eHm else "$eHm · $sHm–$eHm".substringAfter("· ")
            val spanMin = Duration.between(start, end).seconds / 60
            val durationLabel = if (spanMin >= 1) "${spanMin} 分钟" else null
            val place = Places.at(this, end) ?: Places.at(this, start)
            val rawSceneTags = aggregateSceneTags(
                g.map { it.rec.name }.distinct().map { SceneAndMood.getSceneTags(this, it) }.filter { it.isNotEmpty() },
            )
            val eventVocalTags = mainLines.mapNotNull { it.seg.vocalTag }.distinct().take(2)
            val sceneSummary = formatCleanSceneTags(rawSceneTags, excludeLaugh = "笑声" in eventVocalTags)
            val eventMoodPair = computeWeightedMood(mainLines)
            val eventAvgDb = computeEnergyWeightedDb(mainLines)
            val eventDomProx = computeDominantProximity(mainLines)
            val eventVoiceCue = humanVoiceCue(eventDomProx, eventAvgDb)
            val eventAffectCue = humanAffectCue(eventMoodPair?.first, eventMoodPair?.second, eventVocalTags)

            val peopleList = mainParasAsc.mapNotNull { it.who }.distinct()
            val peopleText = peopleList.joinToString("、")
            val nonSelfPeople = peopleList.filterNot { isSelfDisplayName(it) }
            val totalChars = mainParasAsc.sumOf { it.text.length }
            val isCompactNote = mainParasAsc.size <= 1 && totalChars <= 88
            val headline = when {
                nonSelfPeople.size == 1 -> "与 ${nonSelfPeople[0]} 的对话"
                nonSelfPeople.size == 2 -> "与 ${nonSelfPeople[0]}、${nonSelfPeople[1]} 的对话"
                nonSelfPeople.size > 2 -> "与 ${nonSelfPeople[0]}、${nonSelfPeople[1]} 等 ${nonSelfPeople.size} 人对话"
                mainParasAsc.all { it.isSelf } -> "随口独白"
                else -> "对话记录"
            }
            // 预览优先展示最近一句实质性发言
            val previewSource = mainParasAsc.asReversed().firstOrNull { it.text.length >= 10 } ?: mainParasAsc.last()
            val previewQuote = previewSource.text.let { t ->
                if (t.length <= 72) t else t.take(70).trimEnd('，', '。', '、', ' ') + "…"
            }

            // 复制文本保持人类阅读习惯，但不含任何工程分贝小数
            val copyText = buildString {
                append("[").append(day.format(MD)).append(" ").append(if (sHm == eHm) sHm else "$sHm–$eHm")
                place?.let { append(" · ").append(it.label) }
                sceneSummary?.let { append(" · ").append(it) }
                append("]\n")
                for (p in mainParasAsc) {
                    val cues = listOfNotNull(p.voiceCue, p.affectCue)
                    if (p.who != null) {
                        append(p.who)
                        if (cues.isNotEmpty()) append(" (").append(cues.joinToString(" · ")).append(")")
                        append("：")
                    } else if (cues.isNotEmpty()) {
                        append("(").append(cues.joinToString(" · ")).append(") ")
                    }
                    append(p.text).append('\n')
                }
            }.trimEnd()

            val searchBlob = buildString {
                place?.label?.let { append(it.lowercase()).append('\n') }
                rawSceneTags.forEach { append(it.label.lowercase()).append('\n') }
                for (p in paraUisAsc) {
                    p.voiceCue?.let { append(it.lowercase()).append('\n') }
                    p.affectCue?.let { append(it.lowercase()).append('\n') }
                }
                if (peopleText.isNotEmpty()) append(peopleText.lowercase()).append('\n')
                for (p in paraUisAsc) append(p.textLower).append('\n')
            }

            val firstLine = g.first()
            EventUi(
                key = "e_${firstLine.rec.name}_${firstLine.seg.start}",
                day = day,
                startAt = start,
                endAt = end,
                newestHm = eHm,
                timeLabel = timeLabel,
                durationLabel = durationLabel,
                place = place,
                peopleList = peopleList,
                peopleText = peopleText,
                headline = headline,
                previewQuote = previewQuote,
                isCompactNote = isCompactNote,
                sceneSummary = sceneSummary,
                eventVoiceCue = eventVoiceCue,
                eventAffectCue = eventAffectCue,
                isPositive = eventMoodPair?.first == "positive" || "笑声" in eventVocalTags,
                isTense = eventMoodPair?.first == "negative",
                parasNewestFirst = paraUisAsc.asReversed(),
                fgParaCount = fgParasAsc.size,
                bgParaCount = paraUisAsc.size - fgParasAsc.size,
                firstAudioLine = g.lastOrNull { it.rec.hasAudio } ?: g.firstOrNull { it.rec.hasAudio },
                copyText = copyText,
                searchBlob = searchBlob,
            )
        }

        // 凝聚连续 ≥15 分钟的环境音静默段
        val quietRecsAsc = pageRecsDesc.asReversed().filter { r ->
            r.name !in transcribedNames && (r.name in marks.silent || r.name in marks.farSkipped)
        }
        val ambientSpansByDay = HashMap<LocalDate, MutableList<AmbientSpanUi>>()
        var qGroup = ArrayList<Recording>()
        fun flushQuietGroup() {
            if (qGroup.isEmpty()) return
            val totalDur = qGroup.sumOf { it.durationSec }
            val start = qGroup.first().start
            val end = qGroup.last().end
            if (totalDur >= 15 * 60) {
                val day = end.toLocalDate()
                val place = Places.at(this, end) ?: Places.at(this, start)
                val tags = aggregateSceneTags(
                    qGroup.map { SceneAndMood.getSceneTags(this, it.name) }.filter { it.isNotEmpty() },
                )
                val cleanScene = tags.map { it.label }.take(2).joinToString("与").ifEmpty { "安静留白" }
                val durText = fmtMinutes((totalDur / 60.0).roundToInt().coerceAtLeast(15))
                val summary = buildString {
                    place?.let { append(it.label).append(" · ") }
                    append(cleanScene).append(" (").append(durText).append(")")
                }
                val span = AmbientSpanUi(
                    key = "amb_${qGroup.first().name}",
                    day = day,
                    startAt = start,
                    endAt = end,
                    newestHm = end.format(HM),
                    spanLabel = "${end.format(HM)} · ${start.format(HM)}–${end.format(HM)}".substringAfter("· "),
                    summaryText = summary,
                    searchBlob = "${place?.label.orEmpty()} $cleanScene".lowercase(),
                )
                ambientSpansByDay.getOrPut(day) { ArrayList() }.add(span)
            }
            qGroup = ArrayList()
        }
        for (r in quietRecsAsc) {
            val prev = qGroup.lastOrNull()
            if (prev != null && (r.start.toLocalDate() != prev.start.toLocalDate() || Duration.between(prev.end, r.start).seconds > 600)) {
                flushQuietGroup()
            }
            qGroup.add(r)
        }
        flushQuietGroup()

        val eventsByDay = allEventsAsc.groupBy { it.day }
        val allDaysDesc = (eventsByDay.keys + ambientSpansByDay.keys).distinct().sortedDescending()
        val farSkippedByDay = pageRecsDesc.filter { it.name in marks.farSkipped }.groupingBy { it.start.toLocalDate() }.eachCount()

        val dayEvents = allDaysDesc.mapNotNull { day ->
            val evAsc = eventsByDay[day].orEmpty()
            val ambAsc = ambientSpansByDay[day].orEmpty()
            if (evAsc.isEmpty() && ambAsc.isEmpty()) return@mapNotNull null

            val nodesDesc = buildList<StreamNodeUi> {
                evAsc.forEach { add(StreamNodeUi.Conversation(it)) }
                ambAsc.forEach { add(StreamNodeUi.Ambient(it)) }
            }.sortedByDescending { it.sortTime }

            val activeTalkSec = evAsc.sumOf { ev ->
                ev.parasNewestFirst.filter { !it.isBackgroundNoise }.sumOf { it.durationSec }
            }
            val talkMin = if (activeTalkSec <= 0) 0 else (activeTalkSec / 60.0).roundToInt().coerceAtLeast(1)
            val dayPeople = evAsc.flatMap { it.peopleList }.distinct().size
            val mutedCnt = evAsc.sumOf { it.bgParaCount } + (farSkippedByDay[day] ?: 0)
            val posCnt = evAsc.count { it.isPositive }
            val tenseCnt = evAsc.count { it.isTense }
            val (vibe, vibePos) = when {
                posCnt > tenseCnt && posCnt > 0 -> "松弛愉悦" to true
                tenseCnt > posCnt -> "紧凑专注" to false
                else -> "平和日常" to false
            }

            DayEventsGroup(
                day = day,
                title = dayLabel(day),
                countLabel = if (evAsc.isNotEmpty()) "${evAsc.size} 段对话" else "环境记录",
                digest = DayDigestStats(
                    talkMinutes = talkMin,
                    peopleCount = dayPeople,
                    mutedNoiseCount = mutedCnt,
                    vibeLabel = vibe,
                    vibePositive = vibePos,
                ),
                events = evAsc.sortedByDescending { it.endAt },
                nodes = nodesDesc,
            )
        }

        // 轻量构建顶部快筛胶囊（直接读内存已加载事件与微秒级本地花名册，零额外磁盘扫描）
        val namedSpeakers = roster.values
            .filter { it.mergedInto == null && it.id !in selfIds && it.name != null }
            .take(4)
        val topPlaces = Places.all(this).filter { it.mergedInto == null }.asReversed().take(2)
        val quickLenses = buildList {
            add(FeedFilterLens("all", "全部"))
            if (selfIds.isNotEmpty() || allEventsAsc.any { ev -> ev.parasNewestFirst.any { it.isSelf && !it.isBackgroundNoise } }) {
                add(FeedFilterLens("self", "只看我说"))
            }
            if (allEventsAsc.any { it.isPositive }) {
                add(FeedFilterLens("joy", "愉悦时刻"))
            }
            namedSpeakers.forEach { spk ->
                add(FeedFilterLens("spk:${spk.id}", "@${spk.name}"))
            }
            topPlaces.forEach { pl ->
                add(FeedFilterLens("plc:${pl.id}", pl.label))
            }
        }

        return FeedPageSnapshot(
            signature = sig,
            totalRecordingCount = stubsDesc.size,
            unuploadedCount = marks.unuploadedCount,
            loadedStubCount = selectedStubsDesc.size,
            hasMoreHistory = hasMore,
            dayEvents = dayEvents,
            quickLenses = quickLenses,
        )
    }

    /** 按需构建人物档案（仅在切到「人物」页或弹出改名框时后台计算，不拖慢首页冷启动） */
    private fun buildPeopleSnapshot(): Pair<List<PersonCardUi>, Int> {
        val roster = Speakers.all(this)
        if (roster.isEmpty()) return emptyList<PersonCardUi>() to 0
        val selfIds = detectSelfSpeakerIds(roster)
        val stubsDesc = Recordings.listStubsNewestFirst(this)
        val spkCount = HashMap<String, Int>()
        val spkClip = HashMap<String, Pair<Recording, Segment>>()
        val spkSample = HashMap<String, String>()

        for (st in stubsDesc) {
            val tr = Transcripts.get(this, st.name) ?: continue
            var recObj: Recording? = null
            for (seg in tr.segments) {
                val who = resolveInRoster(seg.speaker, roster)?.id ?: continue
                if (seg.text.isBlank()) continue
                spkCount[who] = (spkCount[who] ?: 0) + 1
                if (spkSample[who] == null && seg.text.length >= 8) {
                    // stubsDesc 最新在前：首条命中的有效长句就是最新近况原话！
                    spkSample[who] = seg.text
                }
                if (st.hasAudio && spkClip[who] == null && seg.end - seg.start >= 2.0) {
                    val resolvedRec = recObj ?: Recordings.resolveRecording(this, st).also { recObj = it }
                    spkClip[who] = resolvedRec to seg
                }
            }
        }

        val visibleSpeakers = roster.values
            .filter { it.mergedInto == null && ((spkCount[it.id] ?: 0) > 0 || it.name != null) }
            .sortedWith(
                compareBy<Speaker>(
                    { it.id !in selfIds },
                    { it.name == null },
                    { -(spkCount[it.id] ?: 0) },
                ),
            )
        val cards = visibleSpeakers.map { p ->
            val aliases = roster.values.filter {
                it.mergedInto != null && resolveInRoster(it.id, roster)?.id == p.id
            }
            PersonCardUi(
                speaker = p,
                isSelf = p.id in selfIds,
                count = spkCount[p.id] ?: 0,
                aliases = aliases,
                quote = spkSample[p.id] ?: p.sample,
                clip = spkClip[p.id],
            )
        }
        val unnamed = visibleSpeakers.count { it.name == null && it.id !in selfIds }
        return cards to unnamed
    }

    /** 按需构建足迹列表，按最近到访时间从近到远排序 */
    private fun buildPlacesSnapshot(): List<PlaceCardUi> {
        val allPlaces = Places.all(this)
        if (allPlaces.isEmpty()) return emptyList()
        val placeStats = Places.stats(this)
        return allPlaces
            .filter { it.mergedInto == null }
            .sortedByDescending { p -> placeStats[p.id]?.last ?: LocalDateTime.MIN }
            .map { p ->
                val st = placeStats[p.id]
                val aliases = allPlaces.filter { it.mergedInto == p.id }
                val line = buildString {
                    if (st != null) {
                        st.last?.let { append("最近 ${it.format(MDHM)} · ") }
                        append("驻留 ${st.days} 天 · 约 ${fmtMinutes(st.minutes)}")
                    }
                    if (aliases.isNotEmpty()) append(" · 已合并 ${aliases.size} 处")
                }
                PlaceCardUi(place = p, statsLine = line, aliases = aliases)
            }
    }

    /** 按需分页构建原始录音归档列表（仅进入「原始录音归档」页时执行） */
    private fun buildRecordingsPageSnapshot(limit: Int): Pair<List<DayRecordingsGroup>, Boolean> {
        val stubsDesc = Recordings.listStubsNewestFirst(this)
        val slice = stubsDesc.take(limit)
        val marks = uploader.snapshotMarks()
        val recCards = slice.map { st ->
            val r = Recordings.resolveRecording(this, st)
            val tr = Transcripts.get(this, r.name)
            val place = Places.at(this, r.start)
            val baseStatus = when {
                tr != null -> if (tr.text.isBlank()) BaseChunkStatus.TRANSCRIBED_EMPTY else BaseChunkStatus.TRANSCRIBED
                !r.hasAudio -> BaseChunkStatus.AUDIO_EXPIRED
                r.name in marks.silent -> BaseChunkStatus.SILENT
                r.name in marks.farSkipped -> BaseChunkStatus.FAR_SKIPPED
                r.name in marks.badAudio -> BaseChunkStatus.BAD_AUDIO
                r.name in marks.uploaded -> BaseChunkStatus.UPLOADED
                else -> BaseChunkStatus.PENDING
            }
            val preview = tr?.text?.takeIf { it.isNotBlank() }
            val recSceneTags = SceneAndMood.getSceneTags(this, r.name)
            val recSceneSummary = formatCleanSceneTags(aggregateSceneTags(listOf(recSceneTags)), excludeLaugh = false)
            val recMoodPair = tr?.segments?.takeIf { it.isNotEmpty() }?.let { segs ->
                computeWeightedMoodFromSegments(segs)
            }
            val recVocal = tr?.segments?.mapNotNull { it.vocalTag }?.distinct()?.take(1).orEmpty()
            val recAffect = humanAffectCue(recMoodPair?.first, recMoodPair?.second, recVocal)
            val searchBlob = buildString {
                append(r.name.lowercase())
                place?.label?.let { append('\n').append(it.lowercase()) }
                recSceneTags.forEach { append('\n').append(it.label.lowercase()) }
                recAffect?.let { append('\n').append(it.lowercase()) }
                preview?.let { append('\n').append(it.lowercase()) }
            }
            RecordingCardUi(
                rec = r,
                timeRangeLabel = "${r.start.format(T)} – ${r.end.format(T)}",
                durationLabel = fmtDuration(r.durationSec),
                placeLabel = place?.label,
                sceneSummary = recSceneSummary,
                affectCue = recAffect,
                isPositive = recMoodPair?.first == "positive",
                transcriptPreview = preview,
                baseStatus = baseStatus,
                searchBlob = searchBlob,
            )
        }
        val groups = recCards
            .groupBy { it.rec.start.toLocalDate() }
            .entries
            .sortedByDescending { it.key }
            .map { (day, list) ->
                val sumSec = list.sumOf { it.rec.durationSec }
                DayRecordingsGroup(
                    day = day,
                    title = dayLabel(day),
                    summaryLabel = "${list.size} 段 · ${fmtDuration(sumSec)}",
                    items = list,
                )
            }
        return groups to (slice.size < stubsDesc.size)
    }

    // ───────────────────────────── 根路由与生命周期 ─────────────────────────────

    @Composable
    private fun RootHost() {
        val recorderMessage by remember {
            AppState.status.map { it.message }.distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = AppState.status.value.message)
        LaunchedEffect(recorderMessage) {
            if (recorderMessage == BlePermissions.DENIED_MESSAGE || recorderMessage == BlePermissions.REVOKED_MESSAGE) {
                showBlePermissionDenied(recorderMessage!!)
            }
        }
        var feedSnap by remember { mutableStateOf(FeedPageSnapshot()) }
        var chunkBudget by rememberSaveable { mutableIntStateOf(INITIAL_CHUNK_BUDGET) }
        var activeLens by rememberSaveable { mutableStateOf("all") }
        var searchOpen by rememberSaveable { mutableStateOf(false) }
        var searchQuery by rememberSaveable { mutableStateOf("") }

        var peopleState by remember { mutableStateOf<Pair<String, Pair<List<PersonCardUi>, Int>>?>(null) }
        var placesState by remember { mutableStateOf<Pair<String, List<PlaceCardUi>>?>(null) }
        var whitelisted by remember { mutableStateOf(isBatteryWhitelisted()) }

        val stateHolder = rememberSaveableStateHolder()
        // beyondViewportPageCount = 0：冷启动绝不在后台预渲染「人物」「足迹」重组件，首屏 100% 算力留给记忆流！
        val pager = rememberPagerState { 3 }

        DisposableEffect(Unit) {
            val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                configVersion++
                refreshToken++
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

        val textVersion by remember {
            AppState.status.map { it.textVersion }.distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = AppState.status.value.textVersion)

        val needFullScan = searchQuery.isNotBlank() || activeLens != "all"

        // 首页记忆流分页增量加载
        LaunchedEffect(textVersion, configVersion, refreshToken, chunkBudget, needFullScan) {
            if (searchQuery.isNotBlank()) delay(120) // 搜索输入微防抖，避免每敲一字扫全库
            val next = withContext(Dispatchers.IO) {
                val baseSig = computeBaseSignature(textVersion, configVersion, refreshToken)
                val pageSig = "$baseSig:b=$chunkBudget:f=$needFullScan"
                if (pageSig == feedSnap.signature && feedSnap.signature.isNotEmpty()) null
                else buildFeedPageSnapshot(pageSig, chunkBudget, needFullScan)
            }
            if (next != null) feedSnap = next
        }

        // 3s 低频轮询文件变更：签名不变时 O(1) 零解析直接跳过
        LaunchedEffect(Unit) {
            while (true) {
                delay(3000)
                whitelisted = isBatteryWhitelisted()
                hasLocation = Places.hasPermission(this@MainActivity)
                val tv = AppState.status.value.textVersion
                val cv = configVersion
                val rv = refreshToken
                val curBudget = chunkBudget
                val curFull = searchQuery.isNotBlank() || activeLens != "all"
                val curSig = feedSnap.signature
                val next = withContext(Dispatchers.IO) {
                    val baseSig = computeBaseSignature(tv, cv, rv)
                    val pageSig = "$baseSig:b=$curBudget:f=$curFull"
                    if (pageSig == curSig) null else buildFeedPageSnapshot(pageSig, curBudget, curFull)
                }
                if (next != null) feedSnap = next
            }
        }

        // 人物与足迹页：仅在用户真正滑到对应页或打开编辑框时按需加载
        val needPeople = pager.currentPage == 1 || editing != null || merging != null
        LaunchedEffect(needPeople, feedSnap.signature) {
            if (!needPeople) return@LaunchedEffect
            val baseSig = feedSnap.signature.substringBefore(":b=")
            if (peopleState?.first == baseSig) return@LaunchedEffect
            val loaded = withContext(Dispatchers.IO) { buildPeopleSnapshot() }
            peopleState = baseSig to loaded
        }

        val needPlaces = pager.currentPage == 2 || editingPlace != null || mergingPlace != null
        LaunchedEffect(needPlaces, feedSnap.signature) {
            if (!needPlaces) return@LaunchedEffect
            val baseSig = feedSnap.signature.substringBefore(":b=")
            if (placesState?.first == baseSig) return@LaunchedEffect
            val loaded = withContext(Dispatchers.IO) { buildPlacesSnapshot() }
            placesState = baseSig to loaded
        }

        val isPlaying = player.current != null
        LaunchedEffect(isPlaying) {
            if (!isPlaying) return@LaunchedEffect
            while (true) {
                player.tick()
                delay(100)
            }
        }

        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle, configVersion) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val mode = Config.mode(this@MainActivity)
                    val ok = withContext(Dispatchers.IO) {
                        val (done, pending) = uploader.processPending()
                        val cur = AppState.status.value
                        if (cur.pendingUploads != pending || done != 0) {
                            AppState.update {
                                it.copy(
                                    pendingUploads = pending,
                                    uploadedThisSession = it.uploadedThisSession + done,
                                )
                            }
                        }
                        if (mode == WorkMode.CLOUD) {
                            Sync.pollOnce(this@MainActivity, waitSec = 20)
                        } else {
                            done > 0
                        }
                    }
                    if (ok) refreshToken++
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

        val linkAndDevice by remember {
            AppState.status.map { it.link to it.deviceName }.distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = AppState.status.value.let { it.link to it.deviceName })

        val peoplePair = peopleState?.second ?: (emptyList<PersonCardUi>() to 0)
        val placesList = placesState?.second ?: emptyList()

        AnimatedContent(
            targetState = destination,
            transitionSpec = {
                if (targetState != RootDestination.MAIN) {
                    (slideInHorizontally(tween(220)) { it / 5 } + fadeIn(tween(180))) togetherWith
                        fadeOut(tween(120))
                } else {
                    fadeIn(tween(180)) togetherWith
                        (slideOutHorizontally(tween(180)) { it / 5 } + fadeOut(tween(120)))
                }
            },
            label = "rootNav",
        ) { screen ->
            stateHolder.SaveableStateProvider(screen.name) {
                when (screen) {
                    RootDestination.MAIN -> MainWorkspaceHost(
                        feedSnap = feedSnap,
                        people = peoplePair.first,
                        unnamedPeopleCount = peoplePair.second,
                        places = placesList,
                        whitelisted = whitelisted,
                        pager = pager,
                        activeLens = activeLens,
                        onActiveLensChange = { activeLens = it },
                        searchOpen = searchOpen,
                        searchQuery = searchQuery,
                        onSearchOpenChange = { open ->
                            searchOpen = open
                            if (!open) searchQuery = ""
                        },
                        onSearchQueryChange = { searchQuery = it },
                        onLoadMoreHistory = {
                            if (feedSnap.hasMoreHistory && chunkBudget <= feedSnap.loadedStubCount) {
                                chunkBudget = feedSnap.loadedStubCount + CHUNK_PAGE_STEP
                            }
                        },
                    )
                    RootDestination.RECORDINGS -> {
                        var recLimit by rememberSaveable { mutableIntStateOf(40) }
                        var recPage by remember { mutableStateOf<Pair<List<DayRecordingsGroup>, Boolean>>(emptyList<DayRecordingsGroup>() to false) }
                        LaunchedEffect(feedSnap.signature, recLimit) {
                            recPage = withContext(Dispatchers.IO) { buildRecordingsPageSnapshot(recLimit) }
                        }
                        RawRecordingsScreen(
                            dayRecordings = recPage.first,
                            hasMore = recPage.second,
                            onLoadMore = { recLimit += 40 },
                            mode = remember(configVersion) { Config.mode(this@MainActivity) },
                            asrConfigured = remember(configVersion) { AsrConfig.load(this@MainActivity) != null },
                            player = player,
                            onBack = { destination = RootDestination.MAIN },
                        )
                    }
                    RootDestination.SETTINGS -> SettingsScreen(
                        linkState = linkAndDevice.first,
                        deviceName = linkAndDevice.second,
                        whitelisted = whitelisted,
                        hasLocation = hasLocation,
                        unuploadedCount = feedSnap.unuploadedCount,
                        recordingCount = feedSnap.totalRecordingCount,
                        onBack = { destination = RootDestination.MAIN },
                        onOpenRawRecordings = { destination = RootDestination.RECORDINGS },
                        onConfigChanged = {
                            configVersion++
                            refreshToken++
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

        PersonDialogs(peoplePair.first)
        PlaceDialogs(placesList)
        if (showBlePermissionDialog) {
            AlertDialog(
                onDismissRequest = { showBlePermissionDialog = false },
                shape = MaterialTheme.shapes.large,
                title = { Text(if (recorderMessage == BlePermissions.REVOKED_MESSAGE) "录音已停止" else "设备权限未开启") },
                text = { Text(if (recorderMessage == BlePermissions.REVOKED_MESSAGE) BlePermissions.REVOKED_MESSAGE else BlePermissions.DENIED_MESSAGE) },
                confirmButton = {
                    TextButton(onClick = {
                        showBlePermissionDialog = false
                        if (blePermissionNeedsSettings) {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName")))
                        } else requestStart(repair = startAction == RecorderService.ACTION_REPAIR)
                    }) { Text(if (blePermissionNeedsSettings) "去设置" else "重试") }
                },
                dismissButton = {
                    TextButton(onClick = { showBlePermissionDialog = false }) { Text("取消") }
                },
            )
        }
    }

    @Composable
    private fun MainWorkspaceHost(
        feedSnap: FeedPageSnapshot,
        people: List<PersonCardUi>,
        unnamedPeopleCount: Int,
        places: List<PlaceCardUi>,
        whitelisted: Boolean,
        pager: PagerState,
        activeLens: String,
        onActiveLensChange: (String) -> Unit,
        searchOpen: Boolean,
        searchQuery: String,
        onSearchOpenChange: (Boolean) -> Unit,
        onSearchQueryChange: (String) -> Unit,
        onLoadMoreHistory: () -> Unit,
    ) {
        val mode = remember(configVersion) { Config.mode(this) }
        val feedStyle = remember(configVersion) { Config.feedStyle(this) }
        val asrConfig = remember(configVersion) { AsrConfig.load(this) }
        val cloudConfig = remember(configVersion) { Config.load(this) }
        val needsConfig = if (mode == WorkMode.DIRECT_ASR) asrConfig == null else cloudConfig == null
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val clipboard = LocalClipboardManager.current
        val haptic = LocalHapticFeedback.current
        var showStatusPanel by remember { mutableStateOf(false) }

        val recorderMessage by remember {
            AppState.status.map { it.message }.distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = AppState.status.value.message)
        LaunchedEffect(recorderMessage) {
            if (recorderMessage == "无法启动录音，请重试") snackbarHostState.showSnackbar(recorderMessage!!)
        }

        val feedTabLabel = if (feedStyle == FeedStyle.DIALOGUE) "对话流" else "今日脉络"
        val tabs = remember(feedTabLabel) { listOf(feedTabLabel, "人物", "足迹") }
        val pageTitle = tabs.getOrElse(pager.currentPage) { feedTabLabel }
        val canvasColor = if (pager.currentPage == 0 && feedStyle == FeedStyle.EDITORIAL) {
            DeckTheme.colors.paperCanvas
        } else {
            MaterialTheme.colorScheme.background
        }

        Box(Modifier.fillMaxSize().background(canvasColor)) {
            Scaffold(
                containerColor = Color.Transparent,
                snackbarHost = {
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.padding(bottom = 84.dp),
                    )
                },
                topBar = {
                    AppleStreamHeader(
                        pageTitle = pageTitle,
                        mode = mode,
                        needsConfig = needsConfig,
                        cloudConfigured = cloudConfig != null,
                        statusOpen = showStatusPanel,
                        onStartRecording = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (needsConfig) destination = RootDestination.SETTINGS else requestStart()
                        },
                        onToggleStatusPanel = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showStatusPanel = !showStatusPanel
                        },
                        onOpenSettings = { destination = RootDestination.SETTINGS },
                    )
                },
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 0) { page ->
                        when (page) {
                            0 -> when (feedStyle) {
                                FeedStyle.DIALOGUE -> DialogueStreamView(
                                    dayEvents = feedSnap.dayEvents,
                                    lenses = feedSnap.quickLenses,
                                    activeLens = activeLens,
                                    onSelectLens = onActiveLensChange,
                                    hasMoreHistory = feedSnap.hasMoreHistory,
                                    onLoadMore = onLoadMoreHistory,
                                    mode = mode,
                                    unconfigured = needsConfig,
                                    query = searchQuery,
                                    playingName = player.current?.name,
                                    isPlaying = player.playing,
                                    playPosSec = if (player.current != null) player.positionMs / 1000.0 else -1.0,
                                    onTapTurn = { rec, sec ->
                                        if (player.current?.name == rec.name && abs(player.positionMs / 1000.0 - sec) < 4.0) {
                                            player.toggle()
                                        } else {
                                            if (player.current?.name != rec.name) player.open(rec)
                                            player.seekTo((sec * 1000).toInt())
                                        }
                                    },
                                    onEditPlace = { editingPlace = it },
                                    onEditSpeaker = { spkId -> editing = Speakers.resolve(this@MainActivity, spkId) },
                                    onCopyEvent = { text ->
                                        clipboard.setText(AnnotatedString(text))
                                        scope.launch {
                                            snackbarHostState.currentSnackbarData?.dismiss()
                                            snackbarHostState.showSnackbar("已复制对话文字", duration = SnackbarDuration.Short)
                                        }
                                    },
                                    onOpenSettings = { destination = RootDestination.SETTINGS },
                                )
                                FeedStyle.EDITORIAL -> EditorialStreamView(
                                    dayEvents = feedSnap.dayEvents,
                                    hasMoreHistory = feedSnap.hasMoreHistory,
                                    onLoadMore = onLoadMoreHistory,
                                    mode = mode,
                                    unconfigured = needsConfig,
                                    query = searchQuery,
                                    playingName = player.current?.name,
                                    playPosSec = if (player.current != null) player.positionMs / 1000.0 else -1.0,
                                    onSeekLine = { rec, sec ->
                                        if (player.current?.name != rec.name) player.open(rec)
                                        player.seekTo((sec * 1000).toInt())
                                    },
                                    onEditPlace = { editingPlace = it },
                                    onEditSpeaker = { spkId -> editing = Speakers.resolve(this@MainActivity, spkId) },
                                    onCopyEvent = { text ->
                                        clipboard.setText(AnnotatedString(text))
                                        scope.launch {
                                            snackbarHostState.currentSnackbarData?.dismiss()
                                            snackbarHostState.showSnackbar("已复制对话文字", duration = SnackbarDuration.Short)
                                        }
                                    },
                                    onOpenSettings = { destination = RootDestination.SETTINGS },
                                )
                            }
                            1 -> PeoplePageView(
                                people = people,
                                unnamedCount = unnamedPeopleCount,
                                onPlayClip = { c ->
                                    player.playRange(c.first, (c.second.start * 1000).toInt(), (c.second.end * 1000).toInt() + 300)
                                },
                                onRename = { editing = it },
                                onMarkSelf = { spk ->
                                    scope.launch(Dispatchers.IO) {
                                        Speakers.edit(this@MainActivity, spk.id, name = "我")
                                        refreshToken++
                                    }
                                },
                                onMerge = { merging = it },
                                onUnmerge = { aliasId ->
                                    scope.launch(Dispatchers.IO) {
                                        Speakers.edit(this@MainActivity, aliasId, unmerge = true)
                                        refreshToken++
                                    }
                                },
                            )
                            else -> PlacesPageView(
                                places = places,
                                hasLocation = hasLocation,
                                onRequestLocation = { requestLocation() },
                                onRename = { editingPlace = it },
                                onMerge = { mergingPlace = it },
                                onUnmerge = { aliasId ->
                                    Places.merge(this@MainActivity, aliasId, null)
                                    refreshToken++
                                },
                            )
                        }
                    }

                    FloatingGlassDock(
                        tabs = tabs,
                        selectedTab = pager.currentPage,
                        searchOpen = searchOpen,
                        searchQuery = searchQuery,
                        player = player,
                        showMiniPlayer = player.current != null && (pager.currentPage != 0 || feedStyle == FeedStyle.EDITORIAL),
                        onSelectTab = { idx ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            scope.launch { pager.animateScrollToPage(idx) }
                        },
                        onToggleSearch = { onSearchOpenChange(!searchOpen) },
                        onSearchQueryChange = onSearchQueryChange,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            DeviceStatusPopover(
                visible = showStatusPanel,
                mode = mode,
                asrProviderLabel = asrConfig?.provider?.shortLabel,
                whitelisted = whitelisted,
                unuploaded = feedSnap.unuploadedCount,
                recordingCount = feedSnap.totalRecordingCount,
                onDismiss = { showStatusPanel = false },
                onStart = {
                    showStatusPanel = false
                    requestStart()
                },
                onStop = {
                    showStatusPanel = false
                    stopRecorder()
                },
                onOpenRawRecordings = {
                    showStatusPanel = false
                    destination = RootDestination.RECORDINGS
                },
                onOpenSettings = {
                    showStatusPanel = false
                    destination = RootDestination.SETTINGS
                },
                onRequestWhitelist = { requestBatteryWhitelist() },
            )
        }
    }

    @Composable
    private fun PersonDialogs(people: List<PersonCardUi>) {
        val scope = rememberCoroutineScope()
        editing?.let { p ->
            var text by remember(p.id) { mutableStateOf(p.name ?: "") }
            val clip = people.firstOrNull { it.speaker.id == p.id }?.clip
            AlertDialog(
                onDismissRequest = { editing = null },
                shape = MaterialTheme.shapes.large,
                title = { Text("给 ${p.name ?: p.defaultName} 起名字") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            shape = MaterialTheme.shapes.small,
                            placeholder = { Text("例如：我、张工、林姐") },
                        )
                        Spacer(Modifier.height(10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ActionChip(Icons.Rounded.Person, "设为「我」") {
                                text = "我"
                            }
                            clip?.let { c ->
                                ActionChip(Icons.Rounded.PlayArrow, "试听原声") {
                                    player.playRange(c.first, (c.second.start * 1000).toInt(), (c.second.end * 1000).toInt() + 300)
                                }
                            }
                            if (people.size > 1) {
                                ActionChip(Icons.AutoMirrored.Rounded.MergeType, "合并到已有名字") {
                                    merging = p
                                    editing = null
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            Speakers.edit(this@MainActivity, p.id, name = text)
                            refreshToken++
                        }
                        editing = null
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
            )
        }
        merging?.let { p ->
            val others = people.filter { it.speaker.id != p.id }
            AlertDialog(
                onDismissRequest = { merging = null },
                shape = MaterialTheme.shapes.large,
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
                        items(others, key = { it.speaker.id }) { item ->
                            val t = item.speaker
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable {
                                            scope.launch(Dispatchers.IO) {
                                                Speakers.edit(this@MainActivity, p.id, mergeInto = t.id)
                                                refreshToken++
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
                                    item.quote?.let {
                                        Text(
                                            "「$it」",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                item.clip?.let { c ->
                                    IconButton(onClick = {
                                        player.playRange(c.first, (c.second.start * 1000).toInt(), (c.second.end * 1000).toInt() + 300)
                                    }) {
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

    @Composable
    private fun PlaceDialogs(places: List<PlaceCardUi>) {
        editingPlace?.let { p ->
            var text by remember(p.id) { mutableStateOf(p.name ?: "") }
            AlertDialog(
                onDismissRequest = { editingPlace = null },
                shape = MaterialTheme.shapes.large,
                title = { Text("给「${p.label}」起常用名") },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        placeholder = { Text(p.auto ?: "例如：家、公司、咖啡馆") },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        Places.rename(this, p.id, text)
                        refreshToken++
                        editingPlace = null
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { editingPlace = null }) { Text("取消") } },
            )
        }
        mergingPlace?.let { p ->
            val others = places.map { it.place }.filter { it.id != p.id }
            AlertDialog(
                onDismissRequest = { mergingPlace = null },
                shape = MaterialTheme.shapes.large,
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
                                        refreshToken++
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
        internal const val PARA_GAP_S = 3.0
        internal const val EVENT_GAP_S = 75L
        internal const val MAX_EVENT_SPAN_S = 240L
        internal const val INITIAL_CHUNK_BUDGET = 28
        internal const val CHUNK_PAGE_STEP = 32
        private const val MIN_SPOKEN_CHUNKS_FIRST_PAGE = 8
        private const val MAX_SILENT_SKIP_EXTRA = 90

        internal val T = DateTimeFormatter.ofPattern("HH:mm:ss")
        internal val HM = DateTimeFormatter.ofPattern("HH:mm")
        internal val MD = DateTimeFormatter.ofPattern("M月d日")
        internal val MDHM = DateTimeFormatter.ofPattern("M月d日 HH:mm")

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

        fun fmtMinutes(m: Int) = if (m >= 60) "${m / 60}h${if (m % 60 > 0) " ${m % 60}m" else ""}" else "${m}m"

        fun fmtMs(ms: Int): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}

// ───────────────────── 触底自动分页懒加载触发器 ─────────────────────

@Composable
private fun BindLazyPagination(
    listState: LazyListState,
    hasMore: Boolean,
    itemCount: Int,
    onLoadMore: () -> Unit,
) {
    val shouldLoadMore by remember(listState, hasMore, itemCount) {
        derivedStateOf {
            if (!hasMore || itemCount <= 0) false
            else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= (itemCount - 6).coerceAtLeast(0)
            }
        }
    }
    LaunchedEffect(shouldLoadMore, itemCount, hasMore) {
        if (shouldLoadMore) onLoadMore()
    }
}

// ───────────────────── 极简苹果风顶栏（状态切换 0 跳动） ─────────────────────

@Composable
private fun AppleStreamHeader(
    pageTitle: String,
    mode: WorkMode,
    needsConfig: Boolean,
    cloudConfigured: Boolean,
    statusOpen: Boolean,
    onStartRecording: () -> Unit,
    onToggleStatusPanel: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val slice by remember(mode, needsConfig, cloudConfigured) {
        AppState.status.map { s ->
            val remote = s.remote
            val warn = if (mode == WorkMode.DIRECT_ASR) {
                needsConfig || s.localAsrError != null
            } else {
                cloudConfigured && (
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
            HeaderStatusSlice(
                link = s.link,
                batteryPct = s.batteryMv?.let { MainActivity.batteryPct(it) },
                hasWarning = warn,
                transcribing = transcribing,
            )
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = HeaderStatusSlice(LinkState.STOPPED, null, needsConfig, false),
    )

    val deck = DeckTheme.colors
    val colors = MaterialTheme.colorScheme
    val todayText = remember {
        val d = LocalDate.now()
        val w = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)
        "${d.format(MainActivity.MD)} · $w"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = "Hark",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    text = "· $todayText",
                    style = MaterialTheme.typography.labelSmall.merge(TabularMono),
                    color = colors.onSurfaceVariant.copy(alpha = 0.85f),
                )
            }
            Text(
                text = pageTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onSurface,
                maxLines = 1,
            )
        }

        if (slice.link == LinkState.STOPPED) {
            Surface(
                onClick = onStartRecording,
                modifier = Modifier.height(34.dp).pressScale(),
                shape = CircleShape,
                color = colors.primary,
                contentColor = colors.onPrimary,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(deck.signalRed))
                    Text(
                        text = if (needsConfig) "配置转写" else "开始录音",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Surface(
                onClick = onToggleStatusPanel,
                modifier = Modifier
                    .size(34.dp)
                    .pressScale()
                    .semantics { contentDescription = "打开设备状态" },
                shape = CircleShape,
                color = colors.surfaceContainerLowest,
                border = BorderStroke(1.dp, colors.outlineVariant),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.BluetoothAudio,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = if (slice.hasWarning) deck.ledAmber else colors.onSurfaceVariant,
                    )
                }
            }
        } else {
            Surface(
                onClick = onToggleStatusPanel,
                modifier = Modifier
                    .height(34.dp)
                    .pressScale()
                    .semantics { contentDescription = "打开设备状态" },
                shape = CircleShape,
                color = colors.surfaceContainerLowest,
                border = BorderStroke(1.dp, if (statusOpen) colors.outline else colors.outlineVariant),
                shadowElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val dotColor = when {
                        slice.link == LinkState.STREAMING -> deck.signalRed
                        slice.hasWarning -> deck.ledAmber
                        else -> deck.accentBlue
                    }
                    StatusDot(color = dotColor, breathing = slice.link == LinkState.STREAMING || slice.transcribing)
                    val label = when {
                        slice.transcribing -> buildString {
                            append("整理中")
                            slice.batteryPct?.let { append(" · ").append(it).append('%') }
                        }
                        slice.link == LinkState.STREAMING -> buildString {
                            append("记录中")
                            slice.batteryPct?.let { append(" · ").append(it).append('%') }
                        }
                        else -> slice.link.label
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge.merge(TabularMono),
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                }
            }
        }

        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.padding(start = 2.dp).size(38.dp).pressScale(),
        ) {
            BadgedBox(
                badge = {
                    if (needsConfig) Badge(containerColor = deck.signalRed)
                },
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = "设置", tint = colors.onSurfaceVariant)
            }
        }
    }
}

// ───────────────────── 顶栏锚定设备状态浮层 ─────────────────────

@Composable
private fun DeviceStatusPopover(
    visible: Boolean,
    mode: WorkMode,
    asrProviderLabel: String?,
    whitelisted: Boolean,
    unuploaded: Int,
    recordingCount: Int,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenRawRecordings: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestWhitelist: () -> Unit,
) {
    if (!visible) return
    val s by AppState.status.collectAsStateWithLifecycle()
    val deck = DeckTheme.colors
    val colors = MaterialTheme.colorScheme
    var showTech by remember { mutableStateOf(false) }

    BackHandler(onBack = onDismiss)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.22f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    )

    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 64.dp, start = 16.dp, end = 16.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = MaterialTheme.shapes.large,
            color = colors.surfaceContainerLowest,
            border = BorderStroke(1.dp, colors.outlineVariant),
            shadowElevation = 16.dp,
        ) {
            Column(
                Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "设备状态",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onOpenSettings,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    ) {
                        Text("设置", style = MaterialTheme.typography.labelLarge, color = deck.accentBlue)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TelemetryMetricTile(
                        label = "连接状态",
                        value = s.link.label,
                        sub = s.deviceName ?: "Hark 随身贴",
                        modifier = Modifier.weight(1f),
                    )
                    val pct = s.batteryMv?.let { MainActivity.batteryPct(it) }
                    TelemetryMetricTile(
                        label = "设备电量",
                        value = pct?.let { "$it%" } ?: "连接后读取",
                        sub = if (pct != null) "低功耗蓝牙采集中" else "待机就绪",
                        modifier = Modifier.weight(1f),
                    )
                }

                val syncVal: String
                val syncSub: String
                if (mode == WorkMode.DIRECT_ASR) {
                    val running = s.localAsrRunning
                    val err = s.localAsrError
                    syncVal = when {
                        running != null -> "正在整理文字…"
                        err != null -> "整理遇到问题"
                        asrProviderLabel != null -> "直连就绪"
                        else -> "待配置直连服务"
                    }
                    syncSub = asrProviderLabel?.let { "$it · 声纹与远近自动判别" }
                        ?: "点右上设置填写 API Key"
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

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TelemetryMetricTile(
                        label = if (mode == WorkMode.DIRECT_ASR) "手机直连转写" else "云端信箱同步",
                        value = syncVal,
                        sub = syncSub,
                        modifier = Modifier.weight(1f),
                    )
                    TelemetryMetricTile(
                        label = "原始录音归档",
                        value = "$recordingCount 段音频",
                        sub = if (unuploaded > 0 && mode == WorkMode.CLOUD) "待同步 $unuploaded 段 · 点击查看" else "查看切片与波形 ›",
                        modifier = Modifier.weight(1f),
                        onClick = onOpenRawRecordings,
                    )
                }

                if (s.link == LinkState.STREAMING) {
                    val lossPct = if (s.totalPackets > 0) s.lostPackets * 100.0 / (s.totalPackets + s.lostPackets) else 0.0
                    val all = s.totalPackets + s.lostPackets + s.silentFrames
                    val silentPct = if (s.silentFrames > 0 && all > 0) s.silentFrames * 100.0 / all else 0.0
                    Text(
                        text = if (showTech) {
                            "码率 %.1f KB/s · 丢包 %.2f%% · 静音省略 %.0f%%".format(s.bytesPerSec / 1000.0, lossPct, silentPct)
                        } else {
                            "查看无线链路诊断"
                        },
                        style = MaterialTheme.typography.labelSmall.merge(TabularMono),
                        color = colors.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showTech = !showTech }
                            .padding(vertical = 2.dp),
                    )
                }

                if (!whitelisted) {
                    Surface(
                        onClick = onRequestWhitelist,
                        shape = MaterialTheme.shapes.small,
                        color = colors.secondaryContainer,
                        modifier = Modifier.pressScale(),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.BatteryAlert, contentDescription = null, Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("建议开启后台不受限运行，防止锁屏断连", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text("去开启", style = MaterialTheme.typography.labelMedium, color = deck.accentBlue)
                        }
                    }
                }

                if (s.link == LinkState.STOPPED) {
                    Button(
                        onClick = onStart,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().pressScale(),
                    ) { Text("开始录音") }
                } else {
                    FilledTonalButton(
                        onClick = onStop,
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = deck.signalRedContainer,
                            contentColor = deck.signalRed,
                        ),
                        modifier = Modifier.fillMaxWidth().pressScale(),
                    ) { Text("停止录音") }
                }
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
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = if (onClick != null) modifier.pressScale() else modifier,
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
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

// ═══════════════════════════════════════════════════════════════════════════
// 模式 B：主客气泡流（严格从近到远 + 100% 扁平化虚拟列表 + 触底懒加载）
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun DialogueStreamView(
    dayEvents: List<DayEventsGroup>,
    lenses: List<FeedFilterLens>,
    activeLens: String,
    onSelectLens: (String) -> Unit,
    hasMoreHistory: Boolean,
    onLoadMore: () -> Unit,
    mode: WorkMode,
    unconfigured: Boolean,
    query: String,
    playingName: String?,
    isPlaying: Boolean,
    playPosSec: Double,
    onTapTurn: (Recording, Double) -> Unit,
    onEditPlace: (Place) -> Unit,
    onEditSpeaker: (String) -> Unit,
    onCopyEvent: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val listState = rememberLazyListState()
    val expandedBgKeys = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(lenses) {
        if (lenses.none { it.id == activeLens }) onSelectLens("all")
    }

    val q = query.trim().lowercase()

    // 预构建扁平化行数组：每颗气泡都是 LazyColumn 的原子一级项，屏幕外零测量、零组合
    val flatRows = remember(dayEvents, q, activeLens, expandedBgKeys.toMap()) {
        buildList<FlatDialogueRow> {
            for (group in dayEvents) {
                val dayStartSize = size
                add(FlatDialogueRow.DayTitle(group.day.toString(), group.title, group.countLabel))

                for (node in group.nodes) {
                    val textOk = q.isEmpty() || node.searchBlob.contains(q)
                    if (!textOk) continue

                    when (node) {
                        is StreamNodeUi.Ambient -> {
                            if (activeLens == "all") {
                                add(FlatDialogueRow.AmbientRow(node.span))
                            }
                        }
                        is StreamNodeUi.Conversation -> {
                            val ev = node.event
                            val lensOk = when {
                                activeLens == "all" -> true
                                activeLens == "self" -> ev.parasNewestFirst.any { it.isSelf && !it.isBackgroundNoise }
                                activeLens == "joy" -> ev.isPositive
                                activeLens.startsWith("spk:") -> {
                                    val id = activeLens.removePrefix("spk:")
                                    ev.parasNewestFirst.any { it.speakerId == id && !it.isBackgroundNoise }
                                }
                                activeLens.startsWith("plc:") -> {
                                    val id = activeLens.removePrefix("plc:").toIntOrNull()
                                    ev.place?.id == id
                                }
                                else -> true
                            }
                            if (!lensOk) continue

                            val queryHitsBg = q.isNotBlank() && ev.parasNewestFirst.any { p ->
                                p.isBackgroundNoise && (p.textLower.contains(q) || p.whoLower?.contains(q) == true)
                            }
                            val bgOpen = expandedBgKeys[ev.key] == true || queryHitsBg
                            val visibleParas = ev.parasNewestFirst.filter { p ->
                                val bgAllow = !p.isBackgroundNoise || bgOpen || ev.fgParaCount == 0
                                val selfAllow = activeLens != "self" || p.isSelf
                                bgAllow && selfAllow
                            }
                            if (visibleParas.isEmpty() && ev.bgParaCount == 0) continue

                            add(
                                FlatDialogueRow.EventAnchor(
                                    eventKey = ev.key,
                                    timeLabel = ev.timeLabel,
                                    place = ev.place,
                                    sceneSummary = ev.sceneSummary,
                                    copyText = ev.copyText,
                                ),
                            )
                            visibleParas.forEach { p ->
                                add(FlatDialogueRow.Bubble(p))
                            }
                            if (ev.bgParaCount > 0 && !queryHitsBg && activeLens != "self") {
                                val mediaCnt = ev.parasNewestFirst.count { it.isBackgroundNoise && it.proximity == Proximity.MEDIA }
                                val kind = when {
                                    mediaCnt == ev.bgParaCount -> "外放背景声"
                                    mediaCnt > 0 -> "邻桌与外放声"
                                    else -> "邻桌远场声"
                                }
                                add(FlatDialogueRow.FoldToggle(ev.key, ev.bgParaCount, kind, bgOpen))
                            }
                        }
                    }
                }

                // 若该日期下无命中节点，撤回刚刚插入的 DayTitle
                if (size == dayStartSize + 1) {
                    removeAt(lastIndex)
                }
            }
        }
    }

    BindLazyPagination(
        listState = listState,
        hasMore = hasMoreHistory && q.isEmpty() && activeLens == "all",
        itemCount = flatRows.size,
        onLoadMore = onLoadMore,
    )

    Column(Modifier.fillMaxSize()) {
        if (lenses.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(lenses, key = { it.id }) { lens ->
                    val selected = activeLens == lens.id
                    Surface(
                        onClick = { onSelectLens(lens.id) },
                        shape = CircleShape,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLowest,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.pressScale(),
                    ) {
                        Text(
                            text = lens.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (flatRows.isEmpty()) {
                item(key = "empty_dialogue") {
                    FeedEmptyState(q, activeLens != "all", mode, unconfigured, onOpenSettings)
                }
            }
            items(
                items = flatRows,
                key = { it.key },
                contentType = { it.contentType },
            ) { row ->
                when (row) {
                    is FlatDialogueRow.DayTitle -> DayHeader(row.title, row.countLabel)
                    is FlatDialogueRow.AmbientRow -> CenteredContextPill(
                        text = "${row.span.spanLabel} · ${row.span.summaryText}",
                    )
                    is FlatDialogueRow.EventAnchor -> DialogueContextAnchor(
                        timeLabel = row.timeLabel,
                        place = row.place,
                        sceneSummary = row.sceneSummary,
                        onEditPlace = onEditPlace,
                        onCopy = { onCopyEvent(row.copyText) },
                    )
                    is FlatDialogueRow.Bubble -> {
                        val p = row.para
                        val paraActive = playingName != null && p.lines.any { l ->
                            l.rec.name == playingName && playPosSec in (l.seg.start - 0.15)..(l.seg.end + 0.25)
                        }
                        DialogueBubbleRow(
                            p = p,
                            highlightQuery = q,
                            active = paraActive,
                            isPlaying = paraActive && isPlaying,
                            onTapBubble = {
                                val target = p.lines.firstOrNull { it.rec.hasAudio } ?: return@DialogueBubbleRow
                                onTapTurn(target.rec, target.seg.start)
                            },
                            onEditSpeaker = onEditSpeaker,
                        )
                    }
                    is FlatDialogueRow.FoldToggle -> {
                        Box(Modifier.fillMaxWidth().padding(bottom = 2.dp), contentAlignment = Alignment.Center) {
                            Surface(
                                onClick = { expandedBgKeys[row.eventKey] = !row.expanded },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
                                modifier = Modifier.pressScale(),
                            ) {
                                Text(
                                    text = if (row.expanded) "收起 ${row.bgCount} 段${row.kindText}" else "已静默 ${row.bgCount} 段${row.kindText} · 展开",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 4.5.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (hasMoreHistory && q.isEmpty() && activeLens == "all") {
                item(key = "dialogue_loading_more", contentType = "load_more") {
                    CenteredContextPill("正在加载更早记录…")
                }
            }
        }
    }
}

@Composable
private fun DialogueContextAnchor(
    timeLabel: String,
    place: Place?,
    sceneSummary: String?,
    onEditPlace: (Place) -> Unit,
    onCopy: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = colors.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 4.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = timeLabel,
                        style = MaterialTheme.typography.labelSmall.merge(TabularMono),
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurfaceVariant,
                    )
                    place?.let { p ->
                        Text(
                            text = "· ${p.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = DeckTheme.colors.place,
                            modifier = Modifier.clickable { onEditPlace(p) },
                        )
                    }
                    sceneSummary?.let { sc ->
                        Text(
                            text = "· $sc",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(26.dp).pressScale()) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = "复制这段文字",
                    modifier = Modifier.size(14.dp),
                    tint = colors.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun DialogueBubbleRow(
    p: ParaUi,
    highlightQuery: String,
    active: Boolean,
    isPlaying: Boolean,
    onTapBubble: () -> Unit,
    onEditSpeaker: (String) -> Unit,
) {
    val deck = DeckTheme.colors
    val colors = MaterialTheme.colorScheme
    val isSelf = p.isSelf && !p.isBackgroundNoise

    val bubbleShape = if (isSelf) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 6.dp)
    }
    val bubbleBg = when {
        p.isBackgroundNoise -> colors.surfaceContainer
        isSelf -> deck.selfBubble
        else -> colors.surfaceContainerLowest
    }
    val bubbleFg = when {
        p.isBackgroundNoise -> colors.onSurfaceVariant
        isSelf -> deck.onSelfBubble
        else -> colors.onSurface
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!isSelf) {
            val orbText = p.who?.removePrefix("对话人 ")?.take(1) ?: "·"
            Box(
                modifier = Modifier
                    .padding(end = 8.dp, bottom = 2.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHigh)
                    .let { m -> if (p.speakerId != null) m.clickable { onEditSpeaker(p.speakerId) } else m },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = orbText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (p.speakerId != null) deck.accentBlue else colors.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier.widthIn(max = 296.dp),
            horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (!isSelf && p.who != null) {
                    Text(
                        text = highlightMatches(p.who, highlightQuery),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (p.speakerId != null) deck.accentBlue else colors.onSurfaceVariant,
                        modifier = if (p.speakerId != null) Modifier.clickable { onEditSpeaker(p.speakerId) } else Modifier,
                    )
                }
                p.voiceCue?.let { cue ->
                    HumanCuePill(cue, kind = CueKind.VOICE)
                }
                p.affectCue?.let { cue ->
                    HumanCuePill(cue, kind = if (p.isPositive) CueKind.POSITIVE else if (p.isTense) CueKind.TENSE else CueKind.NEUTRAL)
                }
                Text(
                    text = p.timeHm,
                    style = MaterialTheme.typography.labelSmall.merge(TabularMono),
                    color = colors.onSurfaceVariant.copy(alpha = 0.65f),
                )
            }

            Surface(
                onClick = onTapBubble,
                enabled = p.hasAnyAudio,
                shape = bubbleShape,
                color = bubbleBg,
                border = if (!isSelf && !p.isBackgroundNoise) {
                    BorderStroke(1.dp, if (active) deck.accentBlue.copy(alpha = 0.55f) else colors.outlineVariant)
                } else if (active) {
                    BorderStroke(1.dp, deck.accentBlue.copy(alpha = 0.55f))
                } else null,
                shadowElevation = if (isSelf || p.isBackgroundNoise) 0.dp else 1.dp,
                modifier = Modifier.pressScale(enabled = p.hasAnyAudio, pressedScale = 0.985f),
            ) {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                    Text(
                        text = highlightMatches(p.text, highlightQuery),
                        style = MaterialTheme.typography.bodyLarge,
                        color = bubbleFg,
                    )

                    AnimatedVisibility(visible = active) {
                        Column {
                            Spacer(Modifier.height(7.dp))
                            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.6f))
                            Spacer(Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (isPlaying) "暂停" else "播放",
                                    modifier = Modifier.size(15.dp),
                                    tint = deck.accentBlue,
                                )
                                MiniStaticWaveBars(active = isPlaying, tint = deck.accentBlue)
                                Text(
                                    text = MainActivity.fmtDuration(p.durationSec.toDouble()),
                                    style = MaterialTheme.typography.labelSmall.merge(TabularMono),
                                    color = deck.accentBlue,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniStaticWaveBars(active: Boolean, tint: Color) {
    val heights = remember { floatArrayOf(0.45f, 0.85f, 0.6f, 1.0f, 0.5f, 0.8f, 0.4f, 0.75f, 0.55f) }
    val pulse = if (active) {
        val anim by rememberInfiniteTransition(label = "wave").animateFloat(
            initialValue = 0.72f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
            label = "waveScale",
        )
        anim
    } else 1f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        modifier = Modifier.height(13.dp),
    ) {
        heights.forEachIndexed { i, h ->
            Box(
                Modifier
                    .width(2.5.dp)
                    .fillMaxHeight(h)
                    .graphicsLayer {
                        scaleY = if (i % 2 == 0) pulse else (1f / pulse)
                    }
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.85f)),
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 模式 C：无框左轴流（严格从近到远 + 展开项扁平化虚拟列表 + 触底懒加载）
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun EditorialStreamView(
    dayEvents: List<DayEventsGroup>,
    hasMoreHistory: Boolean,
    onLoadMore: () -> Unit,
    mode: WorkMode,
    unconfigured: Boolean,
    query: String,
    playingName: String?,
    playPosSec: Double,
    onSeekLine: (Recording, Double) -> Unit,
    onEditPlace: (Place) -> Unit,
    onEditSpeaker: (String) -> Unit,
    onCopyEvent: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val listState = rememberLazyListState()
    val expandedEvents = remember { mutableStateMapOf<String, Boolean>() }
    val showBgEvents = remember { mutableStateMapOf<String, Boolean>() }
    val q = query.trim().lowercase()

    // 扁平化无框左轴流：展开长对话时，每一轮发言作为独立 LazyColumn 行按需虚拟化渲染
    val flatRows = remember(dayEvents, q, expandedEvents.toMap(), showBgEvents.toMap()) {
        buildList<FlatEditorialRow> {
            dayEvents.forEachIndexed { gIdx, group ->
                val hits = if (q.isEmpty()) group.nodes else group.nodes.filter { it.searchBlob.contains(q) }
                if (hits.isEmpty()) return@forEachIndexed

                val showDigest = gIdx == 0 && q.isEmpty() && group.events.isNotEmpty()
                add(FlatEditorialRow.DayTitle(group.day.toString(), group.title, group.countLabel, if (showDigest) group.digest else null))

                hits.forEachIndexed { idx, node ->
                    val hasNextNode = idx < hits.lastIndex
                    when (node) {
                        is StreamNodeUi.Ambient -> add(FlatEditorialRow.AmbientNode(node.span, drawLine = hasNextNode))
                        is StreamNodeUi.Conversation -> {
                            val ev = node.event
                            val forceExpand = q.isNotBlank()
                            val isOpen = expandedEvents[ev.key] == true || forceExpand
                            add(
                                FlatEditorialRow.EventHead(
                                    ev = ev,
                                    isOpen = isOpen,
                                    forceExpand = forceExpand,
                                    drawLine = hasNextNode || (!ev.isCompactNote && isOpen),
                                ),
                            )
                            if (!ev.isCompactNote && isOpen) {
                                val queryHitsBg = q.isNotBlank() && ev.parasNewestFirst.any {
                                    it.isBackgroundNoise && it.textLower.contains(q)
                                }
                                val bgShown = showBgEvents[ev.key] == true || queryHitsBg
                                val turns = if (bgShown || ev.bgParaCount == 0) {
                                    ev.parasNewestFirst
                                } else {
                                    ev.parasNewestFirst.filter { !it.isBackgroundNoise }
                                }
                                turns.forEach { p ->
                                    add(FlatEditorialRow.ExpandedTurn(ev.key, p, drawLine = true))
                                }
                                add(
                                    FlatEditorialRow.ExpandedFooter(
                                        eventKey = ev.key,
                                        bgCount = if (queryHitsBg) 0 else ev.bgParaCount,
                                        bgShown = bgShown,
                                        canCollapse = !forceExpand,
                                        drawLine = hasNextNode,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    BindLazyPagination(
        listState = listState,
        hasMore = hasMoreHistory && q.isEmpty(),
        itemCount = flatRows.size,
        onLoadMore = onLoadMore,
    )

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 112.dp),
    ) {
        if (flatRows.isEmpty()) {
            item(key = "empty_editorial") {
                FeedEmptyState(q, false, mode, unconfigured, onOpenSettings)
            }
        }
        items(
            items = flatRows,
            key = { it.key },
            contentType = { it.contentType },
        ) { row ->
            when (row) {
                is FlatEditorialRow.DayTitle -> {
                    Column {
                        DayHeader(row.title, row.countLabel)
                        if (row.digest != null) {
                            Spacer(Modifier.height(8.dp))
                            EditorialDayPulseStrip(row.digest)
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
                is FlatEditorialRow.AmbientNode -> EditorialTimelineRow(
                    timeHm = row.span.newestHm,
                    isQuiet = true,
                    showNodeDot = true,
                    drawLine = row.drawLine,
                    bottomPad = 16.dp,
                ) {
                    Text(
                        text = row.span.summaryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    )
                }
                is FlatEditorialRow.EventHead -> EditorialConversationHead(
                    ev = row.ev,
                    isOpen = row.isOpen,
                    drawLine = row.drawLine,
                    highlightQuery = q,
                    onToggleExpand = { expandedEvents[row.ev.key] = !row.isOpen },
                    onSeekLine = onSeekLine,
                    onEditPlace = onEditPlace,
                    onCopy = { onCopyEvent(row.ev.copyText) },
                )
                is FlatEditorialRow.ExpandedTurn -> {
                    val p = row.para
                    val linePlaying = playingName != null && p.lines.any { l ->
                        l.rec.name == playingName && playPosSec in (l.seg.start - 0.15)..(l.seg.end + 0.25)
                    }
                    EditorialTimelineRow(
                        timeHm = "",
                        isQuiet = false,
                        showNodeDot = false,
                        drawLine = row.drawLine,
                        bottomPad = 6.dp,
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Box(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                                EditorialTurnItem(
                                    p = p,
                                    highlightQuery = q,
                                    active = linePlaying,
                                    onSeekLine = onSeekLine,
                                    onEditSpeaker = onEditSpeaker,
                                )
                            }
                        }
                    }
                }
                is FlatEditorialRow.ExpandedFooter -> EditorialTimelineRow(
                    timeHm = "",
                    isQuiet = false,
                    showNodeDot = false,
                    drawLine = row.drawLine,
                    bottomPad = 16.dp,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (row.canCollapse) {
                            Text(
                                text = "收起逐字稿",
                                style = MaterialTheme.typography.labelMedium,
                                color = DeckTheme.colors.accentBlue,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { expandedEvents[row.eventKey] = false }
                                    .padding(vertical = 2.dp),
                            )
                        }
                        if (row.bgCount > 0) {
                            Text(
                                text = if (row.bgShown) "收起 ${row.bgCount} 段远场/外放声" else "已静默 ${row.bgCount} 段远场/外放声 · 点击查看",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { showBgEvents[row.eventKey] = !row.bgShown }
                                    .padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
        if (hasMoreHistory && q.isEmpty()) {
            item(key = "editorial_loading_more", contentType = "load_more") {
                CenteredContextPill("正在加载更早记录…")
            }
        }
    }
}

@Composable
private fun EditorialDayPulseStrip(digest: DayDigestStats) {
    val colors = MaterialTheme.colorScheme
    val deck = DeckTheme.colors
    Surface(
        shape = MaterialTheme.shapes.small,
        color = colors.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PulseMetricItem("有效开口", "${digest.talkMinutes} 分钟", colors.onSurface)
            Box(Modifier.width(1.dp).height(20.dp).background(colors.outlineVariant))
            PulseMetricItem("面对面人物", "${digest.peopleCount} 位", colors.onSurface)
            Box(Modifier.width(1.dp).height(20.dp).background(colors.outlineVariant))
            PulseMetricItem(
                label = if (digest.mutedNoiseCount > 0) "已静默杂音 ${digest.mutedNoiseCount} 段" else "整体状态",
                value = digest.vibeLabel,
                valueColor = if (digest.vibePositive) deck.ledGreen else colors.onSurface,
            )
        }
    }
}

@Composable
private fun PulseMetricItem(label: String, value: String, valueColor: Color) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.merge(TabularMono),
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.merge(TabularMono),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EditorialTimelineRow(
    timeHm: String,
    isQuiet: Boolean,
    showNodeDot: Boolean,
    drawLine: Boolean,
    bottomPad: androidx.compose.ui.unit.Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val deck = DeckTheme.colors
    val axisColor = colors.outlineVariant
    val dotBorder = if (isQuiet) colors.outline else deck.accentBlue
    val paperBg = deck.paperCanvas

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = 50.dp.toPx()
                val cy = 9.dp.toPx()
                if (drawLine) {
                    val startY = if (showNodeDot) cy + 5.dp.toPx() else 0f
                    drawLine(
                        color = axisColor,
                        start = Offset(x, startY),
                        end = Offset(x, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                if (showNodeDot) {
                    drawCircle(color = dotBorder, radius = 4.dp.toPx(), center = Offset(x, cy))
                    drawCircle(color = paperBg, radius = 2.2.dp.toPx(), center = Offset(x, cy))
                }
            }
            .padding(bottom = bottomPad),
    ) {
        Text(
            text = timeHm,
            style = MaterialTheme.typography.labelMedium.merge(TabularMono),
            fontWeight = FontWeight.SemiBold,
            color = if (isQuiet) colors.onSurfaceVariant.copy(alpha = 0.7f) else colors.onSurfaceVariant,
            modifier = Modifier.width(58.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f), content = content)
    }
}

@Composable
private fun EditorialConversationHead(
    ev: EventUi,
    isOpen: Boolean,
    drawLine: Boolean,
    highlightQuery: String,
    onToggleExpand: () -> Unit,
    onSeekLine: (Recording, Double) -> Unit,
    onEditPlace: (Place) -> Unit,
    onCopy: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val deck = DeckTheme.colors

    EditorialTimelineRow(
        timeHm = ev.newestHm,
        isQuiet = false,
        showNodeDot = true,
        drawLine = drawLine,
        bottomPad = if (!ev.isCompactNote && isOpen) 8.dp else 18.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ev.place?.let { p ->
                    Text(
                        text = p.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = deck.place,
                        modifier = Modifier.clickable { onEditPlace(p) },
                    )
                }
                ev.durationLabel?.let { dur ->
                    Text(
                        text = if (ev.place != null) "· $dur" else dur,
                        style = MaterialTheme.typography.labelSmall.merge(TabularMono),
                        color = colors.onSurfaceVariant,
                    )
                }
                ev.sceneSummary?.let { sc ->
                    val hasPrev = ev.place != null || ev.durationLabel != null
                    Text(
                        text = if (hasPrev) "· $sc" else sc,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                ev.eventVoiceCue?.let { HumanCuePill(it, CueKind.VOICE) }
                ev.eventAffectCue?.let {
                    HumanCuePill(it, if (ev.isPositive) CueKind.POSITIVE else if (ev.isTense) CueKind.TENSE else CueKind.NEUTRAL)
                }
            }

            IconButton(onClick = onCopy, modifier = Modifier.size(26.dp).pressScale()) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = "复制这段文字",
                    modifier = Modifier.size(14.dp),
                    tint = colors.onSurfaceVariant.copy(alpha = 0.72f),
                )
            }
            ev.firstAudioLine?.let { first ->
                IconButton(
                    onClick = { onSeekLine(first.rec, first.seg.start) },
                    modifier = Modifier.size(26.dp).pressScale(),
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = "从这里播放",
                        modifier = Modifier.size(17.dp),
                        tint = deck.accentBlue,
                    )
                }
            }
        }

        if (ev.isCompactNote) {
            val mainP = ev.parasNewestFirst.firstOrNull { !it.isBackgroundNoise } ?: ev.parasNewestFirst.first()
            Spacer(Modifier.height(3.dp))
            Text(
                text = highlightMatches(mainP.text, highlightQuery),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .let { m ->
                        val audio = mainP.lines.firstOrNull { it.rec.hasAudio }
                        if (audio != null) m.clickable { onSeekLine(audio.rec, audio.seg.start) } else m
                    },
            )
        } else {
            Spacer(Modifier.height(3.dp))
            Text(
                text = highlightMatches(ev.headline, highlightQuery),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggleExpand),
            )

            if (!isOpen) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "「${ev.previewQuote}」",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = buildString {
                        append("展开全篇 ${ev.fgParaCount} 段")
                        if (ev.bgParaCount > 0) append(" · 已静默 ${ev.bgParaCount} 段背景声")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = deck.accentBlue,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onToggleExpand)
                        .padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun EditorialTurnItem(
    p: ParaUi,
    highlightQuery: String,
    active: Boolean,
    onSeekLine: (Recording, Double) -> Unit,
    onEditSpeaker: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val deck = DeckTheme.colors
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val annotated = remember(p.lines, highlightQuery, deck.signalRedContainer, deck.onSignalRedContainer) {
        buildAnnotatedString {
            p.lines.forEachIndexed { idx, l ->
                pushStringAnnotation("line", idx.toString())
                append(
                    buildHighlightedText(
                        l.seg.text.trim(),
                        highlightQuery,
                        deck.signalRedContainer,
                        deck.onSignalRedContainer,
                    ),
                )
                pop()
            }
        }
    }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val whoLabel = p.who ?: "未命名"
            Text(
                text = highlightMatches(whoLabel, highlightQuery),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    p.isSelf -> deck.accentBlue
                    p.speakerId != null -> colors.onSurface
                    else -> colors.onSurfaceVariant
                },
                modifier = if (p.speakerId != null) Modifier.clickable { onEditSpeaker(p.speakerId) } else Modifier,
            )
            p.voiceCue?.let { HumanCuePill(it, CueKind.VOICE) }
            p.affectCue?.let {
                HumanCuePill(it, if (p.isPositive) CueKind.POSITIVE else if (p.isTense) CueKind.TENSE else CueKind.NEUTRAL)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = p.timeHm,
                style = MaterialTheme.typography.labelSmall.merge(TabularMono),
                color = colors.onSurfaceVariant.copy(alpha = 0.65f),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = annotated,
            onTextLayout = { layoutResult = it },
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                active -> deck.accentBlue
                p.isBackgroundNoise -> colors.onSurfaceVariant
                else -> colors.onSurface
            },
            modifier = Modifier.let { m ->
                if (p.hasAnyAudio) {
                    m.pointerInput(p.lines) {
                        detectTapGestures { offset ->
                            val charOff = layoutResult?.getOffsetForPosition(offset) ?: 0
                            val hitIdx = annotated.getStringAnnotations("line", charOff, charOff)
                                .firstOrNull()?.item?.toIntOrNull() ?: 0
                            val target = p.lines.getOrNull(hitIdx)?.takeIf { it.rec.hasAudio }
                                ?: p.lines.firstOrNull { it.rec.hasAudio }
                                ?: return@detectTapGestures
                            onSeekLine(target.rec, target.seg.start)
                        }
                    }
                } else m
            },
        )
    }
}

// ───────────────────── 底部 iOS 18 悬浮毛玻璃胶囊栏 + 搜索 + 迷你播放器 ─────────────────────

@Composable
private fun FloatingGlassDock(
    tabs: List<String>,
    selectedTab: Int,
    searchOpen: Boolean,
    searchQuery: String,
    player: Player,
    showMiniPlayer: Boolean,
    onSelectTab: (Int) -> Unit,
    onToggleSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val deck = DeckTheme.colors
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(
            visible = showMiniPlayer && player.current != null,
            enter = expandVertically() + fadeIn(tween(150)),
            exit = shrinkVertically() + fadeOut(tween(120)),
        ) {
            FloatingMiniPlayerCard(player)
        }

        AnimatedVisibility(
            visible = searchOpen,
            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(150)),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeOut(tween(120)),
        ) {
            Surface(
                shape = CircleShape,
                color = colors.surfaceContainerLowest,
                border = BorderStroke(1.dp, colors.outlineVariant),
                shadowElevation = 8.dp,
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = CircleShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    placeholder = { Text("搜索对话文字、人物或地点…") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 6.dp)) {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchQueryChange("") }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Rounded.Close, contentDescription = "清空搜索", modifier = Modifier.size(17.dp))
                                }
                            }
                            TextButton(onClick = onToggleSearch) { Text("收起") }
                        }
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.weight(1f).height(48.dp),
                shape = CircleShape,
                color = deck.glassSurface,
                border = BorderStroke(1.dp, colors.outlineVariant),
                shadowElevation = 10.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEachIndexed { idx, title ->
                        val active = selectedTab == idx
                        val tabBg by animateColorAsState(
                            if (active) colors.surfaceContainerLowest else Color.Transparent,
                            tween(180),
                            label = "tabPillBg",
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(tabBg)
                                .clickable { onSelectTab(idx) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (active) colors.onSurface else colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Surface(
                onClick = onToggleSearch,
                modifier = Modifier.size(48.dp).pressScale(),
                shape = CircleShape,
                color = if (searchOpen) colors.primary else deck.glassSurface,
                contentColor = if (searchOpen) colors.onPrimary else colors.onSurface,
                border = BorderStroke(1.dp, if (searchOpen) Color.Transparent else colors.outlineVariant),
                shadowElevation = 10.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = if (searchOpen) "收起搜索" else "搜索记录",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingMiniPlayerCard(player: Player) {
    val r = player.current ?: return
    var dragging by remember { mutableStateOf<Float?>(null) }
    val haptic = LocalHapticFeedback.current
    val deck = DeckTheme.colors
    val colors = MaterialTheme.colorScheme
    val dur = player.durationMs.coerceAtLeast(1)
    val shown = dragging?.let { (it * dur).toInt() } ?: player.positionMs

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainerLowest,
        border = BorderStroke(1.dp, colors.outlineVariant),
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${r.start.toLocalDate().format(MainActivity.MD)} ${r.start.plusSeconds((shown / 1000).toLong()).format(MainActivity.T)}",
                        style = MaterialTheme.typography.labelLarge.merge(TabularMono),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${MainActivity.fmtMs(shown)} / ${MainActivity.fmtMs(player.durationMs)}",
                        style = MaterialTheme.typography.labelSmall.merge(TabularMono),
                        color = colors.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { player.skip(-10_000) }, modifier = Modifier.size(34.dp).pressScale()) {
                    Icon(Icons.Rounded.Replay10, contentDescription = "后退 10 秒", modifier = Modifier.size(19.dp))
                }
                FilledIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        player.toggle()
                    },
                    shape = CircleShape,
                    modifier = Modifier.size(36.dp).pressScale(),
                ) {
                    Icon(
                        if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (player.playing) "暂停" else "播放",
                        modifier = Modifier.size(19.dp),
                    )
                }
                IconButton(onClick = { player.skip(10_000) }, modifier = Modifier.size(34.dp).pressScale()) {
                    Icon(Icons.Rounded.Forward10, contentDescription = "前进 10 秒", modifier = Modifier.size(19.dp))
                }
                IconButton(onClick = { player.close() }, modifier = Modifier.size(34.dp).pressScale()) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭播放器", modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(6.dp))
            var wave by remember(r.name) { mutableStateOf(Waveforms.cached(r.file)) }
            LaunchedEffect(r.name) { if (wave == null && r.hasAudio) wave = Waveforms.get(r.file) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceContainer)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Waveform(
                    levels = wave,
                    modifier = Modifier.fillMaxWidth().height(26.dp),
                    progress = dragging ?: (player.positionMs.toFloat() / dur),
                    color = colors.onSurfaceVariant.copy(alpha = 0.5f),
                    playedColor = colors.onSurface,
                    playheadColor = deck.accentBlue,
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

// ───────────────────── 人物 / 足迹 / 原始录音独立归档页 ─────────────────────

@Composable
private fun PeoplePageView(
    people: List<PersonCardUi>,
    unnamedCount: Int,
    onPlayClip: (Pair<Recording, Segment>) -> Unit,
    onRename: (Speaker) -> Unit,
    onMarkSelf: (Speaker) -> Unit,
    onMerge: (Speaker) -> Unit,
    onUnmerge: (String) -> Unit,
) {
    val hasSelf = remember(people) { people.any { it.isSelf } }
    PageList {
        if (people.isEmpty()) {
            item(key = "empty_people") {
                EmptyStateCard(
                    icon = Icons.Rounded.Group,
                    title = "尚无声纹人物档案",
                    body = "面对面说话满 10 秒后，本机声纹引擎会自动建立档案（自动忽略远场与外放杂音）。",
                )
            }
        } else if (!hasSelf) {
            item(key = "hint_self") {
                Hint("点自己声音卡片上的「设为我」，即可在气泡流中将你的话自动排在右侧浅蓝气泡。")
            }
        } else if (unnamedCount >= 2) {
            item(key = "hint_people") {
                Hint("有未命名声音时，可先试听原声，再起名或合并同一人。")
            }
        }
        items(people, key = { it.speaker.id }, contentType = { "person_card" }) { item ->
            val p = item.speaker
            val deck = DeckTheme.colors
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(
                        text = if (item.isSelf) "我" else (p.name?.take(1) ?: "#"),
                        bg = if (item.isSelf) deck.accentBlueContainer else MaterialTheme.colorScheme.secondaryContainer,
                        fg = if (item.isSelf) deck.accentBlue else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = p.name ?: if (item.isSelf) "我（本人）" else p.defaultName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (item.isSelf) {
                                HumanCuePill("佩戴者", CueKind.POSITIVE)
                            }
                        }
                        Text(
                            text = "${item.count} 句对话" + if (item.aliases.isNotEmpty()) " · 已合并 ${item.aliases.size} 个声纹" else "",
                            style = MaterialTheme.typography.labelMedium.merge(TabularMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (people.size > 1 || item.aliases.isNotEmpty()) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "更多操作", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                if (people.size > 1) {
                                    DropdownMenuItem(
                                        text = { Text("合并到其他人…") },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.MergeType, contentDescription = null) },
                                        onClick = { menu = false; onMerge(p) },
                                    )
                                }
                                item.aliases.forEach { a ->
                                    DropdownMenuItem(
                                        text = { Text("拆出 ${a.name ?: a.defaultName}") },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = null) },
                                        onClick = { menu = false; onUnmerge(a.id) },
                                    )
                                }
                            }
                        }
                    }
                }
                item.quote?.let { quote ->
                    Spacer(Modifier.height(10.dp))
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Text(
                            text = "「$quote」",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.clip?.let { c -> ActionChip(Icons.Rounded.PlayArrow, "试听原声") { onPlayClip(c) } }
                    ActionChip(Icons.Rounded.Edit, "改名") { onRename(p) }
                    if (!item.isSelf && !hasSelf) {
                        ActionChip(Icons.Rounded.Person, "设为我") { onMarkSelf(p) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlacesPageView(
    places: List<PlaceCardUi>,
    hasLocation: Boolean,
    onRequestLocation: () -> Unit,
    onRename: (Place) -> Unit,
    onMerge: (Place) -> Unit,
    onUnmerge: (Int) -> Unit,
) {
    val deck = DeckTheme.colors
    PageList {
        if (places.isEmpty()) {
            item(key = "empty_places") {
                EmptyStateCard(
                    icon = if (hasLocation) Icons.Rounded.Place else Icons.Rounded.LocationOff,
                    title = if (hasLocation) "还没有记录到足迹" else "定位权限未开启",
                    body = "开启后自动在本地标记驻留位置，所有足迹仅保存在这部手机上。",
                    actionLabel = if (!hasLocation) "开启定位" else null,
                    onAction = if (!hasLocation) onRequestLocation else null,
                )
            }
        } else {
            if (!hasLocation) {
                item(key = "loc_disabled") {
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.LocationOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("定位已关闭", style = MaterialTheme.typography.titleSmall)
                                Text("新录音将不再标记位置（已有足迹仍保留在本机）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            FilledTonalButton(
                                onClick = onRequestLocation,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.pressScale(),
                            ) { Text("开启") }
                        }
                    }
                }
            }
        }
        items(places, key = { it.place.id }, contentType = { "place_card" }) { item ->
            val p = item.place
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
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
                        if (item.statsLine.isNotEmpty()) {
                            Text(
                                text = item.statsLine,
                                style = MaterialTheme.typography.labelMedium.merge(TabularMono),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip(Icons.Rounded.Edit, "改名") { onRename(p) }
                    if (places.size > 1) ActionChip(Icons.AutoMirrored.Rounded.MergeType, "合并") { onMerge(p) }
                    item.aliases.forEach { a ->
                        ActionChip(Icons.AutoMirrored.Rounded.CallSplit, "拆出 ${a.label}") { onUnmerge(a.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RawRecordingsScreen(
    dayRecordings: List<DayRecordingsGroup>,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    mode: WorkMode,
    asrConfigured: Boolean,
    player: Player,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var query by rememberSaveable { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.pressScale()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("原始录音归档", fontWeight = FontWeight.SemiBold) },
            )
        },
        bottomBar = {
            if (player.current != null) {
                Box(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    FloatingMiniPlayerCard(player)
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                shape = CircleShape,
                placeholder = { Text("按时间、地点或内容筛选切片…") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清空")
                        }
                    }
                },
            )
            RecordingsPageView(
                dayRecordings = dayRecordings,
                hasMore = hasMore,
                onLoadMore = onLoadMore,
                mode = mode,
                asrConfigured = asrConfigured,
                query = query,
                selectedFile = player.current?.name,
                isPlaying = player.playing,
                playProgress = if (player.current != null && player.durationMs > 0) {
                    (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f)
                } else 0f,
                onToggleOrOpen = { rec ->
                    if (player.current?.name == rec.name) player.toggle() else player.open(rec)
                },
                onSelectCard = { rec ->
                    if (player.current?.name != rec.name) player.open(rec)
                },
            )
        }
    }
}

@Composable
private fun RecordingsPageView(
    dayRecordings: List<DayRecordingsGroup>,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    mode: WorkMode,
    asrConfigured: Boolean,
    query: String,
    selectedFile: String?,
    isPlaying: Boolean,
    playProgress: Float,
    onToggleOrOpen: (Recording) -> Unit,
    onSelectCard: (Recording) -> Unit,
) {
    val listState = rememberLazyListState()
    val syncSlice by remember {
        AppState.status.map { s ->
            ActiveSyncSlice(
                localAsrRunning = s.localAsrRunning,
                hasLocalAsrError = s.localAsrError != null,
                workerOnline = s.remote.workerOnline,
                workerPhase = s.remote.workerPhase ?: "idle",
                workerCurrent = s.remote.workerCurrent,
                mailboxReachable = s.remote.mailboxReachable,
            )
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = ActiveSyncSlice(null, false, false, "idle", null, null),
    )

    val q = query.trim().lowercase()
    val filteredGroups = remember(dayRecordings, q) {
        if (q.isEmpty()) dayRecordings
        else dayRecordings.mapNotNull { g ->
            val hits = g.items.filter { it.searchBlob.contains(q) }
            if (hits.isEmpty()) null else g.copy(items = hits)
        }
    }
    val totalItems = remember(filteredGroups) { filteredGroups.sumOf { it.items.size + 1 } }

    BindLazyPagination(
        listState = listState,
        hasMore = hasMore && q.isEmpty(),
        itemCount = totalItems,
        onLoadMore = onLoadMore,
    )

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (filteredGroups.isEmpty()) {
            item(key = "empty_recs") {
                EmptyStateCard(
                    icon = Icons.Rounded.GraphicEq,
                    title = if (q.isNotEmpty()) "没有匹配的录音切片" else "还没有录音切片",
                    body = if (q.isNotEmpty()) "换个时间或关键词再试一次。" else "开始录音后，系统会按自然对话与安静时段自动归档。",
                )
            }
        }
        filteredGroups.forEach { group ->
            item(key = "rh_${group.day}", contentType = "day_header") {
                DayHeader(group.title, group.summaryLabel)
            }
            items(
                items = group.items,
                key = { it.rec.name },
                contentType = { "recording_card" },
            ) { item ->
                val selected = selectedFile == item.rec.name
                RecordingCardItem(
                    item = item,
                    statusText = resolveRecordingStatus(item, mode, asrConfigured, syncSlice),
                    selected = selected,
                    playing = selected && isPlaying,
                    progress = if (selected) playProgress else 0f,
                    onToggleOrOpen = { onToggleOrOpen(item.rec) },
                    onSelectCard = { onSelectCard(item.rec) },
                )
            }
        }
    }
}

private fun resolveRecordingStatus(
    item: RecordingCardUi,
    mode: WorkMode,
    asrConfigured: Boolean,
    sync: ActiveSyncSlice,
): String = when (item.baseStatus) {
    BaseChunkStatus.TRANSCRIBED -> "已整理"
    BaseChunkStatus.TRANSCRIBED_EMPTY -> "已整理（无人声）"
    BaseChunkStatus.AUDIO_EXPIRED -> "录音已过期"
    BaseChunkStatus.SILENT -> "安静留白"
    BaseChunkStatus.FAR_SKIPPED -> "远场/外放，已静默"
    BaseChunkStatus.BAD_AUDIO -> "音频异常，已跳过"
    BaseChunkStatus.UPLOADED -> when {
        mode == WorkMode.DIRECT_ASR -> when {
            sync.localAsrRunning == item.rec.name -> "正在整理…"
            !asrConfigured -> "待配直连转写"
            sync.hasLocalAsrError -> "整理待重试"
            else -> "排队整理"
        }
        sync.workerOnline && sync.workerPhase == "transcribing" && sync.workerCurrent == item.rec.name -> "电脑整理中…"
        sync.mailboxReachable == true && !sync.workerOnline -> "已上传 · 等电脑上线"
        else -> "排队整理中"
    }
    BaseChunkStatus.PENDING -> if (mode == WorkMode.DIRECT_ASR) {
        when {
            sync.localAsrRunning == item.rec.name -> "正在整理…"
            !asrConfigured -> "待配直连转写"
            sync.hasLocalAsrError -> "整理待重试"
            else -> "排队整理"
        }
    } else {
        "待加密上传"
    }
}

@Composable
private fun RecordingCardItem(
    item: RecordingCardUi,
    statusText: String,
    selected: Boolean,
    playing: Boolean,
    progress: Float,
    onToggleOrOpen: () -> Unit,
    onSelectCard: () -> Unit,
) {
    val r = item.rec
    var wave by remember(r.name) { mutableStateOf(Waveforms.cached(r.file)) }
    LaunchedEffect(r.name) { if (wave == null && r.hasAudio) wave = Waveforms.get(r.file) }
    val deck = DeckTheme.colors
    val colors = MaterialTheme.colorScheme

    SectionCard(
        highlight = selected,
        onClick = if (r.hasAudio) onSelectCard else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                enabled = r.hasAudio,
                onClick = onToggleOrOpen,
                shape = CircleShape,
                modifier = Modifier.size(40.dp).pressScale(enabled = r.hasAudio),
            ) {
                Icon(
                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.timeRangeLabel,
                        style = MaterialTheme.typography.titleSmall.merge(TabularMono),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = item.durationLabel,
                        style = MaterialTheme.typography.labelMedium.merge(TabularMono),
                        color = colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusPill(statusText)
                    item.placeLabel?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = deck.place)
                    }
                    item.sceneSummary?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                    item.affectCue?.let {
                        HumanCuePill(it, if (item.isPositive) CueKind.POSITIVE else CueKind.NEUTRAL)
                    }
                }
            }
        }

        if (r.hasAudio) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceContainer)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Waveform(
                    levels = wave,
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                    progress = progress,
                    color = colors.onSurfaceVariant.copy(alpha = 0.55f),
                    playedColor = colors.onSurface,
                    playheadColor = if (selected) deck.accentBlue else null,
                )
            }
        }
        if (item.transcriptPreview != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = item.transcriptPreview,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

// ───────────────────── 共享日常原子组件（动画全部下沉 GPU RenderNode，零无效重组） ─────────────────────

private enum class CueKind { VOICE, POSITIVE, TENSE, NEUTRAL }

@Composable
private fun HumanCuePill(label: String, kind: CueKind) {
    val deck = DeckTheme.colors
    val colors = MaterialTheme.colorScheme
    val (bg, fg) = when (kind) {
        CueKind.POSITIVE -> deck.ledGreen.copy(alpha = 0.12f) to deck.ledGreen
        CueKind.TENSE -> deck.signalRed.copy(alpha = 0.12f) to deck.signalRed
        CueKind.VOICE -> if (label == "轻声") {
            deck.placeContainer to deck.place
        } else if (label == "声音洪亮") {
            deck.ledAmber.copy(alpha = 0.14f) to deck.ledAmber
        } else {
            colors.surfaceContainerHigh to colors.onSurfaceVariant
        }
        CueKind.NEUTRAL -> colors.surfaceContainerHigh to colors.onSurfaceVariant
    }
    Text(
        text = label,
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        maxLines = 1,
    )
}

@Composable
private fun CenteredContextPill(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.75f),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.merge(TabularMono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.5.dp),
            )
        }
    }
}

@Composable
private fun FeedEmptyState(
    query: String,
    filteredByLens: Boolean,
    mode: WorkMode,
    unconfigured: Boolean,
    onOpenSettings: () -> Unit,
) {
    if (query.isNotEmpty() || filteredByLens) {
        EmptyStateCard(
            icon = Icons.Rounded.SearchOff,
            title = "没有匹配的记忆片段",
            body = "试试切换上方筛选胶囊，或换个关键词检索。",
        )
    } else {
        EmptyStateCard(
            icon = Icons.AutoMirrored.Rounded.Notes,
            title = "今天还没有对话记录",
            body = if (mode == WorkMode.DIRECT_ASR) {
                "开启右上角录音后，开口交谈会自动分段整理成清晰的记忆流。"
            } else {
                "录音加密上传后，电脑端整理完会自动同步到这里。"
            },
            actionLabel = if (unconfigured) "去配置${mode.label}" else null,
            onAction = if (unconfigured) onOpenSettings else null,
        )
    }
}

/** 呼吸红点完全在 graphicsLayer 更新 alpha，录音期间 0 次重组！ */
@Composable
private fun StatusDot(color: Color, breathing: Boolean) {
    val shown by animateColorAsState(color, tween(220), label = "dot")
    val pulseState = if (breathing) {
        rememberInfiniteTransition(label = "dotPulse").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(950), RepeatMode.Reverse),
            label = "dotAlpha",
        )
    } else null
    Box(
        Modifier
            .size(7.dp)
            .graphicsLayer {
                alpha = pulseState?.value ?: 1f
            }
            .clip(CircleShape)
            .background(shown),
    )
}

@Composable
private fun StatusPill(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PageList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
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
        if (highlight) DeckTheme.colors.accentBlue.copy(alpha = 0.55f) else colors.outlineVariant,
        tween(180),
        label = "cardBorder",
    )
    val cardColors = CardDefaults.outlinedCardColors(containerColor = fill)
    val border = BorderStroke(1.dp, borderColor)
    val body: @Composable ColumnScope.() -> Unit = { Column(Modifier.padding(16.dp), content = content) }
    if (onClick != null) {
        OutlinedCard(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().pressScale(pressedScale = 0.985f),
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
                    .background(MaterialTheme.colorScheme.surfaceContainer),
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
                FilledTonalButton(
                    onClick = onAction,
                    shape = CircleShape,
                    modifier = Modifier.pressScale(),
                ) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun Avatar(text: String, bg: Color, fg: Color) {
    Box(Modifier.size(42.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(text, color = fg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        shape = CircleShape,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
    )
}

private fun buildHighlightedText(
    text: String,
    query: String,
    bg: Color,
    fg: Color,
): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    val lower = text.lowercase()
    return buildAnnotatedString {
        var idx = 0
        while (idx < text.length) {
            val hit = lower.indexOf(query, idx)
            if (hit < 0) {
                append(text.substring(idx))
                break
            }
            if (hit > idx) append(text.substring(idx, hit))
            withStyle(SpanStyle(background = bg, color = fg, fontWeight = FontWeight.SemiBold)) {
                append(text.substring(hit, hit + query.length))
            }
            idx = hit + query.length
        }
    }
}

@Composable
private fun highlightMatches(text: String, query: String): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    val deck = DeckTheme.colors
    return remember(text, query, deck.signalRedContainer, deck.onSignalRedContainer) {
        buildHighlightedText(text, query, deck.signalRedContainer, deck.onSignalRedContainer)
    }
}

private fun isSelfDisplayName(name: String?): Boolean {
    val clean = name?.trim()?.lowercase() ?: return false
    return clean == "我" || clean == "本人" || clean == "自己" || clean == "me"
}

private fun humanVoiceCue(proximity: String?, avgDb: Float?): String? = when (proximity) {
    Proximity.FAR -> "远场"
    Proximity.MEDIA -> "外放"
    else -> when {
        avgDb == null -> null
        avgDb <= -38.0f -> "轻声"
        avgDb >= -20.5f -> "声音洪亮"
        else -> null
    }
}

private fun humanAffectCue(mood: String?, moodScore: Float?, vocalTags: List<String>): String? {
    val firstTag = vocalTags.firstOrNull { it.isNotBlank() }
    return when {
        firstTag != null && mood == "positive" -> if (firstTag == "笑声") "愉悦 · 笑声" else "愉悦 · $firstTag"
        firstTag != null -> firstTag
        mood == "positive" && (moodScore == null || moodScore >= 0.35f) -> "愉悦"
        mood == "negative" && (moodScore == null || moodScore <= -0.35f) -> "语气急促"
        else -> null
    }
}

private fun workerShortLabel(remote: RemoteStatus): String = when {
    !remote.workerOnline -> remote.workerAgeSec?.let { "电脑离线 ${fmtAge(it)}" } ?: "等待电脑端连接"
    remote.workerPhase == "paused" -> "已暂停拉取"
    remote.asrOk == false || remote.workerPhase == "asr_down" -> "转写服务未就绪"
    remote.workerPhase == "transcribing" -> "正在整理 (${remote.workerPhaseElapsedSec.toInt()}s)"
    remote.workerPhase == "error" -> "出错：${remote.workerError ?: "未知"}"
    else -> remote.asrModel ?: "空闲就绪"
}

private fun fmtAge(seconds: Double): String = when {
    seconds < 60 -> "${seconds.toInt()}s"
    seconds < 3600 -> "${(seconds / 60).toInt()}m"
    seconds < 86400 -> "${(seconds / 3600).toInt()}h"
    else -> "${(seconds / 86400).toInt()}d"
}

private fun computeEnergyWeightedDb(lines: List<LineUi>): Float? {
    var weightedPower = 0.0
    var totalDur = 0.0
    for (l in lines) {
        val db = l.seg.db ?: continue
        val dur = (l.seg.activeSec?.toDouble() ?: (l.seg.end - l.seg.start)).coerceIn(0.05, 60.0)
        weightedPower += dur * 10.0.pow(db.toDouble() / 10.0)
        totalDur += dur
    }
    if (totalDur <= 0.0 || weightedPower <= 0.0) return null
    val raw = 10.0 * log10(weightedPower / totalDur)
    return ((raw * 10.0).roundToInt() / 10.0).toFloat()
}

private fun computeDominantProximity(lines: List<LineUi>): String? {
    var nearS = 0.0
    var farS = 0.0
    var mediaS = 0.0
    for (l in lines) {
        val dur = (l.seg.activeSec?.toDouble() ?: (l.seg.end - l.seg.start)).coerceIn(0.05, 60.0)
        when (l.seg.proximity) {
            Proximity.NEAR -> nearS += dur
            Proximity.FAR -> farS += dur
            Proximity.MEDIA -> mediaS += dur
        }
    }
    val total = nearS + farS + mediaS
    if (total <= 0.0) return null
    return when {
        nearS >= farS && nearS >= mediaS -> Proximity.NEAR
        mediaS >= farS -> Proximity.MEDIA
        else -> Proximity.FAR
    }
}

private fun computeWeightedMoodFromSegments(segs: List<Segment>): Pair<String, Float>? {
    var weighted = 0.0
    var totalDur = 0.0
    for (s in segs) {
        val sc = s.moodScore ?: continue
        val dur = (s.activeSec?.toDouble() ?: (s.end - s.start)).coerceIn(0.05, 60.0)
        weighted += dur * sc.toDouble()
        totalDur += dur
    }
    if (totalDur <= 0.0) return null
    val avg = ((weighted / totalDur * 100.0).roundToInt() / 100.0).toFloat().coerceIn(-0.99f, 0.99f)
    val mood = when {
        avg >= SceneAndMood.NEUTRAL_BAND -> "positive"
        avg <= -SceneAndMood.NEUTRAL_BAND -> "negative"
        else -> "neutral"
    }
    return mood to avg
}

private fun computeWeightedMood(lines: List<LineUi>): Pair<String, Float>? =
    computeWeightedMoodFromSegments(lines.map { it.seg })

private fun aggregateSceneTags(tagLists: List<List<SceneTag>>): List<SceneTag> {
    if (tagLists.isEmpty()) return emptyList()
    val merged = LinkedHashMap<String, Float>()
    for (list in tagLists) {
        for (t in list) {
            val prev = merged[t.label]
            if (prev == null || t.score > prev) merged[t.label] = t.score
        }
    }
    if (merged.isEmpty()) return emptyList()
    val nonQuiet = merged.filterKeys { it != "室内安静" && it != "安静无声" }
    val source = if (nonQuiet.isNotEmpty()) nonQuiet else merged
    return source.entries
        .sortedByDescending { it.value }
        .take(3)
        .map { SceneTag(it.key, it.value) }
}

private fun formatCleanSceneTags(tags: List<SceneTag>, excludeLaugh: Boolean): String? {
    val labels = tags.map { it.label }.filterNot { excludeLaugh && it == "笑声" }.take(2)
    return if (labels.isEmpty()) null else labels.joinToString(" · ")
}
