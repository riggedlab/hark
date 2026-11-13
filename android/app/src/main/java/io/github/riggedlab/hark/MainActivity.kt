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
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
import androidx.compose.ui.unit.sp
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
import kotlin.math.sin

private enum class RootDestination { MAIN, SETTINGS }

// ───────────────────── 后台预构建的 @Immutable 视图模型（主线程零磁盘 I/O、零无效重组） ─────────────────────

@Immutable
internal data class LineUi(
    val rec: Recording,
    val seg: Segment,
    val at: LocalDateTime,
    val endAt: LocalDateTime,
)

@Immutable
internal data class ParaUi(
    val lines: List<LineUi>,
    val who: String?,
    val speakerId: String?,
    val text: String,
    val textLower: String,
    val whoLower: String?,
    val hasAnyAudio: Boolean,
    val avgDb: Float?,
)

@Immutable
internal data class EventUi(
    val key: String,
    val day: LocalDate,
    val timeLabel: String,
    val place: Place?,
    val peopleText: String,
    val avgDb: Float?,
    val speakerLevelsSummary: String?,
    val paras: List<ParaUi>,
    val firstAudioLine: LineUi?,
    val copyText: String,
    val searchBlob: String,
)

@Immutable
internal data class DayEventsGroup(
    val day: LocalDate,
    val title: String,
    val countLabel: String,
    val events: List<EventUi>,
)

internal enum class BaseChunkStatus {
    TRANSCRIBED,
    TRANSCRIBED_EMPTY,
    AUDIO_EXPIRED,
    SILENT,
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
internal data class ArchiveSnapshot(
    val signature: String = "",
    val recs: List<Recording> = emptyList(),
    val unuploadedCount: Int = 0,
    val dayEvents: List<DayEventsGroup> = emptyList(),
    val dayRecordings: List<DayRecordingsGroup> = emptyList(),
    val people: List<PersonCardUi> = emptyList(),
    val unnamedPeopleCount: Int = 0,
    val places: List<PlaceCardUi> = emptyList(),
)

@Immutable
private data class HeaderStatusSlice(
    val link: LinkState,
    val batteryPct: Int?,
    val hasWarning: Boolean,
    val transcribing: Boolean,
)

@Immutable
private data class DeckStatusSlice(
    val link: LinkState,
    val message: String?,
    val chunkSec: Int,
    val written: Boolean,
    val liveUnavailable: Boolean,
    val fallbackLevel: Float?,
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
    private var destination by mutableStateOf(RootDestination.MAIN)

    private val permLauncher = registerForActivityResult(RequestMultiplePermissions()) {
        hasLocation = Places.hasPermission(this)
        startRecorder(startAction)
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

    // ───────────────────────────── 后台快照构建器（杜绝 UI 线程磁盘读取） ─────────────────────────────

    private fun computeArchiveSignature(textVer: Int, cfgVer: Int, manualVer: Int): String {
        val chunkFiles = RecorderService.chunkDir(this).listFiles() ?: emptyArray()
        var newestMod = 0L
        var opusCount = 0
        for (f in chunkFiles) {
            if (f.name.endsWith(".opus")) {
                opusCount++
                if (f.lastModified() > newestMod) newestMod = f.lastModified()
            }
        }
        val textCount = Transcripts.dir(this).list()?.count { it.endsWith(".json") } ?: 0
        val marks = uploader.snapshotMarks()
        val spkMacMod = java.io.File(filesDir, "speakers_mac.json").lastModified()
        val spkLocalMod = java.io.File(filesDir, "speakers_local.json").lastModified()
        val spkPhoneMod = java.io.File(filesDir, "speakers_phone.json").lastModified()
        val placeMod = java.io.File(filesDir, "places/places.json").lastModified()
        val fixMod = java.io.File(filesDir, "places/fixes.jsonl").lastModified()
        return "$opusCount:$newestMod:$textCount:${marks.uploaded.size}:${marks.silent.size}:${marks.badAudio}:$spkMacMod:$spkLocalMod:$spkPhoneMod:$placeMod:$fixMod:$textVer:$cfgVer:$manualVer"
    }

    private fun resolveInRoster(id: String?, roster: Map<String, Speaker>): Speaker? {
        var cur = id?.let { roster[it] } ?: return null
        val seen = HashSet<String>(4)
        while (cur.mergedInto != null && seen.add(cur.id)) cur = roster[cur.mergedInto] ?: break
        return cur
    }

    private fun buildArchiveSnapshot(sig: String): ArchiveSnapshot {
        val recs = Recordings.list(this)
        val marks = uploader.snapshotMarks()
        val roster = Speakers.all(this)

        // 1. 构建文字时间线 EventUi
        val lines = ArrayList<LineUi>(recs.size * 4)
        for (r in recs.asReversed()) {
            val tr = Transcripts.get(this, r.name) ?: continue
            for (seg in tr.segments) {
                if (seg.text.isNotBlank()) {
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
        }
        lines.sortBy { it.at }

        val eventGroups = ArrayList<MutableList<LineUi>>()
        for (l in lines) {
            val cur = eventGroups.lastOrNull()
            if (cur == null || Duration.between(cur.last().endAt, l.at).seconds > EVENT_GAP_S) {
                eventGroups.add(mutableListOf(l))
            } else {
                cur.add(l)
            }
        }

        val allEvents = eventGroups.map { g ->
            val paras = ArrayList<MutableList<LineUi>>()
            val paraMeta = ArrayList<Triple<String?, String?, String>>()
            for (l in g) {
                val rawSpk = l.seg.speaker
                val resolved = if (roster.isNotEmpty()) resolveInRoster(rawSpk, roster) else null
                val localIdx = rawSpk?.removePrefix("other_")?.toIntOrNull()
                val meta = when {
                    resolved != null -> Triple(resolved.name ?: resolved.defaultName, resolved.id, "global:${resolved.id}")
                    localIdx != null -> {
                        val dispNum = if (localIdx <= 0) localIdx + 1 else localIdx
                        Triple("对话人 $dispNum", null, "local:${l.rec.name}:$localIdx")
                    }
                    rawSpk != null -> Triple(null, null, "raw:${l.rec.name}:$rawSpk")
                    else -> Triple(null, null, "unknown")
                }
                val lastLines = paras.lastOrNull()
                val lastMeta = paraMeta.lastOrNull()
                val gap = lastLines?.let { Duration.between(it.last().endAt, l.at).toMillis() / 1000.0 }
                val prevDb = lastLines?.last()?.seg?.db
                val curDb = l.seg.db
                val dbConsistent = prevDb == null || curDb == null || abs(prevDb - curDb) < 5.0f
                if (lastLines != null && lastMeta != null && lastMeta.third == meta.third && gap != null && gap < PARA_GAP_S && dbConsistent) {
                    lastLines.add(l)
                } else {
                    paras.add(mutableListOf(l))
                    paraMeta.add(meta)
                }
            }
            val paraUis = paras.mapIndexed { idx, pLines ->
                val (who, spkId, _) = paraMeta[idx]
                val joined = pLines.joinToString("") { it.seg.text.trim() }
                ParaUi(
                    lines = pLines,
                    who = who,
                    speakerId = spkId,
                    text = joined,
                    textLower = joined.lowercase(),
                    whoLower = who?.lowercase(),
                    hasAnyAudio = pLines.any { it.rec.hasAudio },
                    avgDb = computeEnergyWeightedDb(pLines),
                )
            }
            val start = g.first().at
            val end = g.last().endAt
            val day = start.toLocalDate()
            val sHm = start.format(HM)
            val eHm = end.format(HM)
            val timeLabel = if (sHm == eHm) sHm else "$sHm – $eHm"
            val place = Places.at(this, start)
            val peopleList = paraUis.mapNotNull { it.who }.distinct()
            val peopleWithLevels = run {
                val byWho = LinkedHashMap<String, MutableList<LineUi>>()
                for (p in paraUis) {
                    val w = p.who ?: continue
                    byWho.getOrPut(w) { ArrayList() }.addAll(p.lines)
                }
                if (byWho.isEmpty()) ""
                else {
                    val entries = byWho.map { (w, ls) -> w to computeEnergyWeightedDb(ls) }
                    val sorted = if (entries.any { it.second != null }) {
                        entries.sortedByDescending { it.second ?: -99f }
                    } else entries
                    sorted.joinToString(" · ") { (w, db) ->
                        if (db != null) "$w ${formatDb(db)}" else w
                    }
                }
            }
            val peopleText = peopleList.joinToString("、")
            val eventAvgDb = computeEnergyWeightedDb(g)
            val copyText = buildString {
                append("[").append(day.format(MD)).append(" ").append(if (sHm == eHm) sHm else "$sHm–$eHm")
                place?.let { append(" · ").append(it.label) }
                append("]\n")
                for (p in paraUis) {
                    if (p.who != null) {
                        append(p.who)
                        p.avgDb?.let { append(" (").append(formatDb(it)).append(")") }
                        append("：")
                    } else if (p.avgDb != null) {
                        append("(").append(formatDb(p.avgDb)).append(") ")
                    }
                    if (p.lines.size <= 1) {
                        append(p.text)
                    } else {
                        p.lines.forEachIndexed { i, l ->
                            if (i > 0) append(' ')
                            append(l.seg.text.trim())
                            l.seg.db?.let { append(" [").append(formatDb(it)).append("]") }
                        }
                    }
                    append("\n")
                }
            }.trimEnd()
            val searchBlob = buildString {
                place?.label?.let { append(it.lowercase()).append('\n') }
                if (peopleText.isNotEmpty()) append(peopleText.lowercase()).append('\n')
                for (p in paraUis) append(p.textLower).append('\n')
            }
            val firstLine = g.first()
            EventUi(
                key = "e_${firstLine.rec.name}_${firstLine.seg.start}",
                day = day,
                timeLabel = timeLabel,
                place = place,
                peopleText = peopleText,
                avgDb = eventAvgDb,
                speakerLevelsSummary = peopleWithLevels.ifEmpty { null },
                paras = paraUis,
                firstAudioLine = g.firstOrNull { it.rec.hasAudio },
                copyText = copyText,
                searchBlob = searchBlob,
            )
        }

        val dayEvents = allEvents
            .groupBy { it.day }
            .entries
            .sortedByDescending { it.key }
            .map { (day, list) ->
                DayEventsGroup(
                    day = day,
                    title = dayLabel(day),
                    countLabel = "${list.size} 段对话",
                    events = list.asReversed(),
                )
            }

        // 2. 构建录音列表卡片模型
        val recCards = recs.map { r ->
            val tr = Transcripts.get(this, r.name)
            val place = Places.at(this, r.start)
            val baseStatus = when {
                tr != null -> if (tr.text.isBlank()) BaseChunkStatus.TRANSCRIBED_EMPTY else BaseChunkStatus.TRANSCRIBED
                !r.hasAudio -> BaseChunkStatus.AUDIO_EXPIRED
                r.name in marks.silent -> BaseChunkStatus.SILENT
                r.name in marks.badAudio -> BaseChunkStatus.BAD_AUDIO
                r.name in marks.uploaded -> BaseChunkStatus.UPLOADED
                else -> BaseChunkStatus.PENDING
            }
            val preview = tr?.text?.takeIf { it.isNotBlank() }
            val searchBlob = buildString {
                append(r.name.lowercase())
                place?.label?.let { append('\n').append(it.lowercase()) }
                preview?.let { append('\n').append(it.lowercase()) }
            }
            RecordingCardUi(
                rec = r,
                timeRangeLabel = "${r.start.format(T)} – ${r.end.format(T)}",
                durationLabel = fmtDuration(r.durationSec),
                placeLabel = place?.label,
                transcriptPreview = preview,
                baseStatus = baseStatus,
                searchBlob = searchBlob,
            )
        }

        val dayRecordings = recCards
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

        // 3. 构建人物档案模型
        val allSpeakers = roster
        val spkCount = HashMap<String, Int>()
        val spkClip = HashMap<String, Pair<Recording, Segment>>()
        val spkSample = HashMap<String, String>()
        if (allSpeakers.isNotEmpty()) {
            for (r in recs) {
                val tr = Transcripts.get(this, r.name) ?: continue
                for (seg in tr.segments) {
                    val who = resolveInRoster(seg.speaker, allSpeakers)?.id ?: continue
                    if (seg.text.isBlank()) continue
                    spkCount[who] = (spkCount[who] ?: 0) + 1
                    if ((spkSample[who]?.length ?: 0) < seg.text.length) spkSample[who] = seg.text
                    if (r.hasAudio) {
                        val old = spkClip[who]?.second
                        if (old == null || seg.end - seg.start > old.end - old.start) spkClip[who] = r to seg
                    }
                }
            }
        }
        val visibleSpeakers = allSpeakers.values
            .filter { it.mergedInto == null && ((spkCount[it.id] ?: 0) > 0 || it.name != null) }
            .sortedWith(compareBy<Speaker>({ it.name == null }, { -(spkCount[it.id] ?: 0) }))
        val peopleCards = visibleSpeakers.map { p ->
            val aliases = allSpeakers.values.filter {
                it.mergedInto != null && resolveInRoster(it.id, allSpeakers)?.id == p.id
            }
            PersonCardUi(
                speaker = p,
                count = spkCount[p.id] ?: 0,
                aliases = aliases,
                quote = spkSample[p.id] ?: p.sample,
                clip = spkClip[p.id],
            )
        }

        // 4. 构建地点模型
        val allPlaces = Places.all(this)
        val placeStats = Places.stats(this)
        val placeCards = allPlaces.filter { it.mergedInto == null }.map { p ->
            val st = placeStats[p.id]
            val aliases = allPlaces.filter { it.mergedInto == p.id }
            val line = buildString {
                if (st != null) {
                    append("停留 ${st.days} 天 · 约 ${fmtMinutes(st.minutes)}")
                    st.last?.let { append(" · 最近 ${it.format(MDHM)}") }
                }
                if (aliases.isNotEmpty()) append(" · 已合并 ${aliases.size} 处")
            }
            PlaceCardUi(place = p, statsLine = line, aliases = aliases)
        }

        return ArchiveSnapshot(
            signature = sig,
            recs = recs,
            unuploadedCount = marks.unuploadedCount,
            dayEvents = dayEvents,
            dayRecordings = dayRecordings,
            people = peopleCards,
            unnamedPeopleCount = visibleSpeakers.count { it.name == null },
            places = placeCards,
        )
    }

    // ───────────────────────────── 根路由与生命周期 ─────────────────────────────

    @Composable
    private fun RootHost() {
        var archive by remember { mutableStateOf(ArchiveSnapshot()) }
        var whitelisted by remember { mutableStateOf(isBatteryWhitelisted()) }

        // 监听 config SharedPreferences 变更，任何外部/页面配置写入立即同步刷新 UI
        DisposableEffect(Unit) {
            val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                configVersion++
                refreshToken++
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

        // 仅订阅 textVersion，避免 liveLevel / 秒表高频刷新根组件
        val textVersion by remember {
            AppState.status.map { it.textVersion }.distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = AppState.status.value.textVersion)

        // 按需增量刷新归档快照：签名未变时绝不重建对象、绝不触发列表重组
        LaunchedEffect(textVersion, configVersion, refreshToken) {
            val next = withContext(Dispatchers.IO) {
                val sig = computeArchiveSignature(textVersion, configVersion, refreshToken)
                if (sig == archive.signature && archive.signature.isNotEmpty()) null
                else buildArchiveSnapshot(sig)
            }
            if (next != null) archive = next
        }

        LaunchedEffect(Unit) {
            while (true) {
                delay(3000)
                whitelisted = isBatteryWhitelisted()
                hasLocation = Places.hasPermission(this@MainActivity)
                val tv = AppState.status.value.textVersion
                val cv = configVersion
                val rv = refreshToken
                val curSig = archive.signature
                val next = withContext(Dispatchers.IO) {
                    val sig = computeArchiveSignature(tv, cv, rv)
                    if (sig == curSig) null else buildArchiveSnapshot(sig)
                }
                if (next != null) archive = next
            }
        }

        // 仅在播放器真正处于回放态时才以 100ms 驱动进度条，不播放时零协程唤醒
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

        val stateHolder = rememberSaveableStateHolder()
        val pager = rememberPagerState { 4 }
        var searchOpen by rememberSaveable { mutableStateOf(false) }
        var searchQuery by rememberSaveable { mutableStateOf("") }

        val linkAndDevice by remember {
            AppState.status.map { it.link to it.deviceName }.distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = AppState.status.value.let { it.link to it.deviceName })

        AnimatedContent(
            targetState = destination,
            transitionSpec = {
                if (targetState == RootDestination.SETTINGS) {
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
                        archive = archive,
                        whitelisted = whitelisted,
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
                        linkState = linkAndDevice.first,
                        deviceName = linkAndDevice.second,
                        whitelisted = whitelisted,
                        hasLocation = hasLocation,
                        unuploadedCount = archive.unuploadedCount,
                        onBack = { destination = RootDestination.MAIN },
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
    }

    @Composable
    private fun MainWorkspaceHost(
        archive: ArchiveSnapshot,
        whitelisted: Boolean,
        pager: PagerState,
        searchOpen: Boolean,
        searchQuery: String,
        onSearchOpenChange: (Boolean) -> Unit,
        onSearchQueryChange: (String) -> Unit,
    ) {
        val mode = remember(configVersion) { Config.mode(this) }
        val asrConfig = remember(configVersion) { AsrConfig.load(this) }
        val cloudConfig = remember(configVersion) { Config.load(this) }
        val needsConfig = if (mode == WorkMode.DIRECT_ASR) asrConfig == null else cloudConfig == null
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val clipboard = LocalClipboardManager.current
        val haptic = LocalHapticFeedback.current
        var showStatusPanel by remember { mutableStateOf(false) }

        val tabs = remember { listOf("文字", "录音", "人物", "地点") }

        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopHardwareBar(
                        mode = mode,
                        needsConfig = needsConfig,
                        cloudConfigured = cloudConfig != null,
                        searchOpen = searchOpen,
                        statusOpen = showStatusPanel,
                        onToggleStatusPanel = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showStatusPanel = !showStatusPanel
                        },
                        onToggleSearch = { onSearchOpenChange(!searchOpen) },
                        onOpenSettings = { destination = RootDestination.SETTINGS },
                    )
                },
                bottomBar = { if (player.current != null) TapePlayerDeck(player) },
            ) { pad ->
                Column(Modifier.padding(pad).fillMaxSize()) {
                    RecorderDeckStrip(
                        mode = mode,
                        needsConfig = needsConfig,
                        providerLabel = asrConfig?.provider?.shortLabel,
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

                    AnimatedVisibility(
                        visible = searchOpen,
                        enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(150)),
                        exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeOut(tween(120)),
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
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
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    scope.launch { pager.animateScrollToPage(i) }
                                },
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
                            0 -> EventsPageView(
                                dayEvents = archive.dayEvents,
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
                            1 -> RecordingsPageView(
                                dayRecordings = archive.dayRecordings,
                                mode = mode,
                                asrConfigured = asrConfig != null,
                                query = searchQuery,
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
                            2 -> PeoplePageView(
                                people = archive.people,
                                unnamedCount = archive.unnamedPeopleCount,
                                onPlayClip = { c ->
                                    player.playRange(c.first, (c.second.start * 1000).toInt(), (c.second.end * 1000).toInt() + 300)
                                },
                                onRename = { editing = it },
                                onMerge = { merging = it },
                                onUnmerge = { aliasId ->
                                    scope.launch(Dispatchers.IO) {
                                        Speakers.edit(this@MainActivity, aliasId, unmerge = true)
                                        refreshToken++
                                    }
                                },
                            )
                            else -> PlacesPageView(
                                places = archive.places,
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
                }
            }

            // 顶栏锚定弹簧设备状态浮层：紧贴顶栏下方弹出，绝不底部裁切、绝不需下滑找按钮！
            DeviceStatusPopover(
                visible = showStatusPanel,
                mode = mode,
                asrProviderLabel = asrConfig?.provider?.shortLabel,
                whitelisted = whitelisted,
                unuploaded = archive.unuploadedCount,
                onDismiss = { showStatusPanel = false },
                onStart = {
                    showStatusPanel = false
                    requestStart()
                },
                onStop = {
                    showStatusPanel = false
                    stopRecorder()
                },
                onOpenSettings = {
                    showStatusPanel = false
                    destination = RootDestination.SETTINGS
                },
                onRequestWhitelist = { requestBatteryWhitelist() },
            )
        }

        PersonDialogs(archive.people)
        PlaceDialogs(archive.places)
    }

    @Composable
    private fun PersonDialogs(people: List<PersonCardUi>) {
        val scope = rememberCoroutineScope()
        editing?.let { p ->
            var text by remember(p.id) { mutableStateOf(p.name ?: "") }
            val clip = people.firstOrNull { it.speaker.id == p.id }?.clip
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
                            clip?.let { c ->
                                ActionChip(Icons.Rounded.PlayArrow, "试听原声") {
                                    player.playRange(c.first, (c.second.start * 1000).toInt(), (c.second.end * 1000).toInt() + 300)
                                }
                            }
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
        internal const val EVENT_GAP_S = 300L
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

        fun fmtMinutes(m: Int) = if (m >= 60) "${m / 60} 小时${if (m % 60 > 0) " ${m % 60} 分" else ""}" else "$m 分钟"

        fun fmtMs(ms: Int): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return "%02d:%02d".format(s / 60, s % 60)
        }
    }
}

// ───────────────────── 顶层可跳过（Skippable）UI 组件：完全隔离状态读取 ─────────────────────

@Composable
private fun TopHardwareBar(
    mode: WorkMode,
    needsConfig: Boolean,
    cloudConfigured: Boolean,
    searchOpen: Boolean,
    statusOpen: Boolean,
    onToggleStatusPanel: () -> Unit,
    onToggleSearch: () -> Unit,
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Hark",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))

        // 方角机械硬件状态铭牌（6dp 利落切角，彻底告别圆胶囊）
        Surface(
            onClick = onToggleStatusPanel,
            modifier = Modifier
                .pressScale()
                .semantics { contentDescription = "打开设备状态" },
            shape = RoundedCornerShape(6.dp),
            color = if (statusOpen) colors.surfaceContainerHigh else colors.surfaceContainerLow,
            border = BorderStroke(1.dp, if (statusOpen) colors.outline else colors.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(start = 9.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                val dotColor = when {
                    slice.link == LinkState.STREAMING -> deck.ledGreen
                    slice.hasWarning -> deck.ledAmber
                    else -> colors.outline
                }
                StatusDot(color = dotColor, breathing = slice.transcribing)
                Text(
                    text = when {
                        slice.transcribing -> "DT-1 · 转写中"
                        slice.batteryPct != null -> "DT-1 · ${slice.batteryPct}%"
                        slice.link == LinkState.STREAMING -> "DT-1 · 已连接"
                        slice.link == LinkState.STOPPED -> "DT-1"
                        else -> slice.link.label
                    },
                    style = MaterialTheme.typography.labelMedium.merge(TabularMono),
                    color = colors.onSurface,
                )
                Icon(
                    imageVector = if (statusOpen) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = colors.onSurfaceVariant,
                )
            }
        }

        // 搜索开关
        if (searchOpen) {
            FilledTonalIconButton(
                onClick = onToggleSearch,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(start = 4.dp).size(36.dp).pressScale(),
            ) {
                Icon(Icons.Rounded.Search, contentDescription = "收起搜索", modifier = Modifier.size(20.dp))
            }
        } else {
            IconButton(onClick = onToggleSearch, modifier = Modifier.pressScale()) {
                Icon(Icons.Rounded.Search, contentDescription = "搜索记录", tint = colors.onSurfaceVariant)
            }
        }

        // 独立设置按钮
        IconButton(onClick = onOpenSettings, modifier = Modifier.pressScale()) {
            BadgedBox(
                badge = {
                    if (needsConfig) Badge(containerColor = deck.signalRed)
                },
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = "设置", tint = colors.onSurface)
            }
        }
    }
}

@Composable
private fun RecorderDeckStrip(
    mode: WorkMode,
    needsConfig: Boolean,
    providerLabel: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRepair: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val slice by remember {
        AppState.status.map { s ->
            val streaming = s.link == LinkState.STREAMING
            val written = streaming && s.lastWriteNanos != 0L &&
                (System.nanoTime() - s.lastWriteNanos < 2_500_000_000L)
            DeckStatusSlice(
                link = s.link,
                message = s.message,
                chunkSec = s.currentChunkSeconds.toInt(),
                written = written,
                liveUnavailable = s.liveUnavailable,
                fallbackLevel = s.liveLevel,
            )
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = DeckStatusSlice(LinkState.STOPPED, null, 0, false, false, null),
    )

    val deck = DeckTheme.colors
    val colors = MaterialTheme.colorScheme

    val isStreaming = slice.link == LinkState.STREAMING
    val cardBg by animateColorAsState(
        targetValue = if (isStreaming) deck.tapeWell else colors.surfaceContainerLowest,
        animationSpec = tween(200),
        label = "deckBg",
    )
    val cardBorder by animateColorAsState(
        targetValue = if (isStreaming) deck.signalRed.copy(alpha = 0.35f) else colors.outlineVariant,
        animationSpec = tween(200),
        label = "deckBorder",
    )

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, cardBorder),
    ) {
        val stateKey = when (slice.link) {
            LinkState.STOPPED -> 0
            LinkState.STREAMING -> 1
            else -> 2
        }
        AnimatedContent(
            targetState = stateKey,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(110)) },
            label = "deckState",
        ) { target ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(66.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (target) {
                    0 -> {
                        TapeReelsGraphic(spinning = false)
                        Spacer(Modifier.width(12.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onOpenSettings),
                        ) {
                            val title = when {
                                needsConfig -> "待配置${mode.label}"
                                mode == WorkMode.DIRECT_ASR -> "手机直连 · ${providerLabel ?: "直连服务"}"
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
                        // 8dp 利落方角机械录音键（告别圆胶囊）
                        Button(
                            onClick = onStart,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
                            modifier = Modifier.pressScale(),
                        ) {
                            Box(Modifier.size(6.dp).clip(RoundedCornerShape(2.dp)).background(deck.signalRed))
                            Spacer(Modifier.width(7.dp))
                            Text("开始录音")
                        }
                    }

                    1 -> {
                        // 开阔一体化录音主控台（去除嵌套内胶囊，整块面板直接承载 REC 方标 + 走表 + 120Hz 频谱 + 方角停止键）
                        Column(horizontalAlignment = Alignment.Start) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(if (slice.written) deck.signalRed else deck.ledAmber)
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        text = "REC",
                                        style = MaterialTheme.typography.labelSmall.merge(TabularMono),
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = MainActivity.fmtMs(slice.chunkSec * 1000),
                                style = MaterialTheme.typography.titleMedium.merge(TabularMono),
                                fontWeight = FontWeight.SemiBold,
                                color = if (slice.written) colors.onSurface else deck.ledAmber,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.width(1.dp).height(30.dp).background(deck.tapeGroove))
                        Spacer(Modifier.width(12.dp))
                        when {
                            !slice.written -> Text(
                                text = "等待设备音频流…",
                                style = MaterialTheme.typography.labelSmall,
                                color = deck.ledAmber,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            slice.liveUnavailable -> Text(
                                text = "音频流写入中",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            else -> LiveSpectrumBar(
                                fallbackLevel = slice.fallbackLevel,
                                color = colors.onSurface,
                                peakColor = deck.signalRed,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        Spacer(Modifier.width(12.dp))
                        // 8dp 利落方角机械停止键
                        Surface(
                            onClick = onStop,
                            shape = RoundedCornerShape(8.dp),
                            color = colors.surfaceContainerLowest,
                            border = BorderStroke(1.dp, deck.signalRed.copy(alpha = 0.45f)),
                            modifier = Modifier.height(38.dp).pressScale(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(9.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(deck.signalRed),
                                )
                                Text(
                                    text = "停止",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = deck.signalRed,
                                )
                            }
                        }
                    }

                    else -> {
                        val problem = slice.link == LinkState.PAIRING_REQUIRED
                        StatusDot(color = if (problem) colors.error else deck.ledAmber, breathing = !problem)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = slice.message ?: slice.link.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (problem) colors.error else colors.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        if (problem) {
                            FilledTonalButton(
                                onClick = onRepair,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.pressScale(),
                            ) { Text("重新配对") }
                        } else {
                            OutlinedButton(
                                onClick = onStop,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.pressScale(),
                            ) { Text("停止") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 120Hz 亚像素连续滑动实时声压频谱：
 * - 后台音频解码线程按 20ms 帧实时累积峰值柱并维护环形历史（从设置页返回或解锁亮屏立刻衔接）；
 * - UI 仅在 Canvas Draw 阶段按 120Hz 计算亚像素偏移（subShift），0 次 Compose 重组、0 分配。
 */
@Composable
private fun LiveSpectrumBar(
    fallbackLevel: Float?,
    color: Color,
    peakColor: Color,
    modifier: Modifier = Modifier,
) {
    val snapshotBuf = remember { FloatArray(72) }
    val smoothHeadBox = remember { FloatArray(1) }
    val fb by rememberUpdatedState(fallbackLevel)
    val frameClock = rememberInfiniteTransition(label = "spectrum").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "spectrumClock",
    )

    Canvas(modifier.height(26.dp)) {
        // 仅在 Draw 阶段读取动画帧，触发 GPU RenderNode 120Hz 重绘，0 Snapshot 写入、0 重组
        @Suppress("UNUSED_VARIABLE")
        val tick = frameClock.value
        val now = System.nanoTime()
        val liveNow = LiveAudioMonitor.currentLevel(now, fb).coerceIn(0f, 1f)
        val prev = smoothHeadBox[0]
        val smoothHead = prev + (liveNow - prev) * (if (liveNow > prev) 0.5f else 0.2f)
        smoothHeadBox[0] = smoothHead
        val (count, frac) = LiveAudioMonitor.readSpectrum(now, snapshotBuf, fb)

        val bar = 2.5.dp.toPx()
        val step = bar + 2.2.dp.toPx()
        val maxBars = (size.width / step).toInt() + 2
        val subShift = frac * step

        for (i in 0 until maxBars) {
            val x = size.width - bar - i * step + step - subShift
            if (x + bar < 0f || x > size.width) continue
            val v = when {
                i == 0 -> smoothHead
                i - 1 < count -> snapshotBuf[i - 1]
                else -> 0f
            }
            if (v < 0.03f) {
                val idleH = 2.dp.toPx()
                drawRoundRect(
                    color = color.copy(alpha = 0.15f),
                    topLeft = Offset(x, (size.height - idleH) / 2f),
                    size = Size(bar, idleH),
                    cornerRadius = CornerRadius(bar / 2f),
                )
            } else {
                val h = (v * size.height).coerceAtLeast(3.dp.toPx())
                val c = if (v > 0.75f) peakColor else color
                val fade = (1f - 0.62f * (i.toFloat() / maxBars.coerceAtLeast(1))).coerceIn(0.28f, 1f)
                drawRoundRect(
                    color = c.copy(alpha = fade),
                    topLeft = Offset(x, (size.height - h) / 2f),
                    size = Size(bar, h),
                    cornerRadius = CornerRadius(bar / 2f),
                )
            }
        }
    }
}

// ───────────────────── 顶栏锚定弹簧设备状态浮层（零裁切、免滑动、120Hz 原生窗口内动画） ─────────────────────

@Composable
private fun DeviceStatusPopover(
    visible: Boolean,
    mode: WorkMode,
    asrProviderLabel: String?,
    whitelisted: Boolean,
    unuploaded: Int,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestWhitelist: () -> Unit,
) {
    if (visible) {
        BackHandler(onBack = onDismiss)
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(120)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(
            initialScale = 0.92f,
            transformOrigin = TransformOrigin(0.82f, 0f),
            animationSpec = spring(dampingRatio = 0.78f, stiffness = 540f),
        ) + fadeIn(tween(140)),
        exit = scaleOut(
            targetScale = 0.94f,
            transformOrigin = TransformOrigin(0.82f, 0f),
            animationSpec = tween(120),
        ) + fadeOut(tween(110)),
    ) {
        val s by AppState.status.collectAsStateWithLifecycle()
        val colors = MaterialTheme.colorScheme
        var showTech by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(top = 54.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(14.dp),
                color = colors.surfaceContainerLowest,
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, colors.outlineVariant),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(18.dp),
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
                        FilledTonalButton(
                            onClick = onOpenSettings,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.pressScale(),
                        ) {
                            Icon(Icons.Rounded.Settings, contentDescription = null, Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
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
                            sub = if (s.link == LinkState.STREAMING) "已录 ${MainActivity.fmtMs((s.currentChunkSeconds * 1000).toInt())}" else "随时可开始",
                            modifier = Modifier.weight(1f),
                        )
                        TelemetryMetricTile(
                            label = "设备电量",
                            value = s.batteryMv?.let { "${MainActivity.batteryPct(it)}%" } ?: "—",
                            sub = if (s.batteryMv != null) "蓝牙实时回传" else "连接设备后显示",
                            modifier = Modifier.weight(1f),
                        )
                    }

                    val syncVal: String
                    val syncSub: String
                    if (mode == WorkMode.DIRECT_ASR) {
                        syncVal = when {
                            asrProviderLabel == null -> "未配置服务商"
                            s.localAsrRunning != null -> "正在转写…"
                            s.localAsrError != null -> "转写待重试"
                            s.pendingUploads > 0 -> "待转写 ${s.pendingUploads} 段"
                            else -> "已全部转写"
                        }
                        syncSub = asrProviderLabel?.let { "$it（已启用）" }
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
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { showTech = !showTech }
                                .padding(vertical = 2.dp),
                        )
                    }

                    if (!whitelisted) {
                        Surface(
                            onClick = onRequestWhitelist,
                            shape = RoundedCornerShape(8.dp),
                            color = colors.secondaryContainer,
                            modifier = Modifier.pressScale(),
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

                    if (s.link == LinkState.STOPPED) {
                        Button(
                            onClick = onStart,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().pressScale(),
                        ) { Text("开始录音") }
                    } else {
                        OutlinedButton(
                            onClick = onStop,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().pressScale(),
                        ) { Text("停止录音") }
                    }
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
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
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

// ───────────────────── Tab 1：文字（全量 @Immutable 渲染，滚动满帧 120Hz） ─────────────────────

@Composable
private fun EventsPageView(
    dayEvents: List<DayEventsGroup>,
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
    val q = query.trim().lowercase()
    val filteredGroups = remember(dayEvents, q) {
        if (q.isEmpty()) dayEvents
        else dayEvents.mapNotNull { g ->
            val hits = g.events.filter { it.searchBlob.contains(q) }
            if (hits.isEmpty()) null else g.copy(countLabel = "${hits.size} 段对话", events = hits)
        }
    }

    PageList {
        if (filteredGroups.isEmpty()) {
            item(key = "empty_events") {
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
                            "开始录音后，手机会自动调用直连转写生成分段文字。"
                        } else {
                            "录音加密上传后，电脑端转写完会自动同步到这里。"
                        },
                        actionLabel = if (unconfigured) "去配置${mode.label}" else null,
                        onAction = if (unconfigured) onOpenSettings else null,
                    )
                }
            }
        }
        filteredGroups.forEach { group ->
            item(key = "d_${group.day}", contentType = "day_header") {
                DayHeader(group.title, group.countLabel)
            }
            items(
                items = group.events,
                key = { it.key },
                contentType = { "event_card" },
            ) { ev ->
                EventCardView(
                    ev = ev,
                    highlightQuery = q,
                    playingName = playingName,
                    playPosSec = playPosSec,
                    onSeekLine = onSeekLine,
                    onEditPlace = onEditPlace,
                    onEditSpeaker = onEditSpeaker,
                    onCopy = { onCopyEvent(ev.copyText) },
                )
            }
        }
    }
}

@Composable
private fun EventCardView(
    ev: EventUi,
    highlightQuery: String,
    playingName: String?,
    playPosSec: Double,
    onSeekLine: (Recording, Double) -> Unit,
    onEditPlace: (Place) -> Unit,
    onEditSpeaker: (String) -> Unit,
    onCopy: () -> Unit,
) {
    val deck = DeckTheme.colors
    val colors = MaterialTheme.colorScheme
    val firstAudio = ev.firstAudioLine

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = ev.timeLabel,
                    style = MaterialTheme.typography.titleMedium.merge(TabularMono),
                    fontWeight = FontWeight.SemiBold,
                )
                if (ev.place != null || ev.speakerLevelsSummary != null || ev.avgDb != null) {
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ev.place?.let { p ->
                            MetaTag(Icons.Rounded.Place, p.label, deck.place) { onEditPlace(p) }
                        }
                        if (ev.speakerLevelsSummary != null) {
                            MetaTag(Icons.Rounded.Group, ev.speakerLevelsSummary, colors.tertiary)
                        } else if (ev.avgDb != null) {
                            MetaTag(Icons.Rounded.GraphicEq, formatDb(ev.avgDb), colors.onSurfaceVariant)
                        }
                    }
                }
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(36.dp).pressScale()) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = "复制这段文字",
                    modifier = Modifier.size(18.dp),
                    tint = colors.onSurfaceVariant,
                )
            }
            if (firstAudio != null) {
                FilledTonalIconButton(
                    onClick = { onSeekLine(firstAudio.rec, firstAudio.seg.start) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(36.dp).pressScale(),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "从这里播放", modifier = Modifier.size(20.dp))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        var prevLabel: String? = null
        var prevDb: Float? = null
        val singleKnownSpeaker = ev.paras.all { it.who != null } && ev.paras.map { it.who }.distinct().size <= 1
        val showLabels = (ev.peopleText.isNotEmpty() && !singleKnownSpeaker) || ev.paras.size > 1

        val visibleParas = if (highlightQuery.isBlank() || ev.paras.size <= 2) {
            ev.paras
        } else {
            val matchIdx = ev.paras.indices.filter { i ->
                ev.paras[i].textLower.contains(highlightQuery) ||
                    ev.paras[i].whoLower?.contains(highlightQuery) == true
            }
            if (matchIdx.isEmpty()) ev.paras
            else ev.paras.filterIndexed { i, _ -> matchIdx.any { abs(it - i) <= 1 } }
        }

        visibleParas.forEach { p ->
            val label = p.who ?: "未识别"
            val dbShifted = p.avgDb != null && prevDb != null && abs(p.avgDb - prevDb!!) >= 4.0f
            if (showLabels && (label != prevLabel || dbShifted)) {
                Row(
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val labelMod = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .let { m ->
                            if (p.speakerId != null) m.clickable { onEditSpeaker(p.speakerId) } else m
                        }
                    Text(
                        text = highlightMatches(label, highlightQuery),
                        modifier = labelMod,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (p.speakerId != null) colors.tertiary else colors.onSurfaceVariant,
                    )
                    p.avgDb?.let { db ->
                        Text(
                            text = formatDb(db),
                            style = MaterialTheme.typography.labelSmall.merge(TabularMono),
                            color = colors.onSurfaceVariant.copy(alpha = 0.85f),
                        )
                    }
                }
            }
            prevLabel = label
            prevDb = p.avgDb

            val cardInvolvesPlaying = playingName != null && p.lines.any { it.rec.name == playingName }
            val paraActive = cardInvolvesPlaying && p.lines.any { l ->
                l.rec.name == playingName && playPosSec >= l.seg.start && playPosSec < l.seg.end + 0.3
            }

            // 非播放卡片对富文本做 remember 缓存，彻底免除滚动期间重复构建 AnnotatedString
            val activeBucket = if (paraActive) (playPosSec * 4).toInt() else -1
            val showInlineSentenceDb = p.lines.size > 1
            val inlineDbColor = colors.outline
            val annotatedPara = remember(p, highlightQuery, activeBucket, deck.signalRed, deck.signalRedContainer, inlineDbColor) {
                buildAnnotatedString {
                    p.lines.forEachIndexed { lineIdx, l ->
                        val sentence = l.seg.text.trim()
                        val lineActive = paraActive && l.rec.name == playingName &&
                            playPosSec >= l.seg.start && playPosSec < l.seg.end + 0.3
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
                        append(buildHighlightedText(sentence, highlightQuery, deck.signalRedContainer, deck.onSignalRedContainer))
                        if (showInlineSentenceDb && l.seg.db != null) {
                            withStyle(
                                SpanStyle(
                                    fontSize = 11.sp,
                                    fontFeatureSettings = "tnum",
                                    color = if (lineActive) deck.signalRed else inlineDbColor,
                                ),
                            ) {
                                append(" ${l.seg.db.roundToInt()}dB ")
                            }
                        }
                        if (lineActive) pop()
                        pop()
                    }
                }
            }
            var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
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
                            if (p.hasAnyAudio) {
                                m.pointerInput(p.lines) {
                                    detectTapGestures { tapOffset ->
                                        val charOff = layoutResult?.getOffsetForPosition(tapOffset) ?: 0
                                        val hitIdx = annotatedPara
                                            .getStringAnnotations("line", charOff, charOff)
                                            .firstOrNull()?.item?.toIntOrNull() ?: 0
                                        val target = p.lines.getOrNull(hitIdx)?.takeIf { it.rec.hasAudio }
                                            ?: p.lines.firstOrNull { it.rec.hasAudio }
                                            ?: return@detectTapGestures
                                        onSeekLine(target.rec, target.seg.start)
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

// ───────────────────── Tab 2：录音（主线程零磁盘 I/O） ─────────────────────

@Composable
private fun RecordingsPageView(
    dayRecordings: List<DayRecordingsGroup>,
    mode: WorkMode,
    asrConfigured: Boolean,
    query: String,
    selectedFile: String?,
    isPlaying: Boolean,
    playProgress: Float,
    onToggleOrOpen: (Recording) -> Unit,
    onSelectCard: (Recording) -> Unit,
) {
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

    PageList {
        if (filteredGroups.isEmpty()) {
            item(key = "empty_recs") {
                EmptyStateCard(
                    icon = Icons.Rounded.GraphicEq,
                    title = if (q.isNotEmpty()) "没有匹配的录音片段" else "还没有录音片段",
                    body = if (q.isNotEmpty()) "换个时间或关键词再试一次。" else "开始录音后，每 5 分钟自动封存一段音频。",
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
    BaseChunkStatus.TRANSCRIBED -> "已转写"
    BaseChunkStatus.TRANSCRIBED_EMPTY -> "已转写（无有效人声）"
    BaseChunkStatus.AUDIO_EXPIRED -> "录音已过期"
    BaseChunkStatus.SILENT -> if (mode == WorkMode.DIRECT_ASR) "无人声，已跳过" else "无人声，未上传"
    BaseChunkStatus.BAD_AUDIO -> "音频异常，已跳过"
    BaseChunkStatus.UPLOADED -> when {
        mode == WorkMode.DIRECT_ASR -> when {
            sync.localAsrRunning == item.rec.name -> "手机转写中…"
            !asrConfigured -> "待配直连转写"
            sync.hasLocalAsrError -> "转写待重试"
            else -> "排队手机转写"
        }
        sync.workerOnline && sync.workerPhase == "transcribing" && sync.workerCurrent == item.rec.name -> "电脑转写中…"
        sync.mailboxReachable == true && !sync.workerOnline -> "已上传 · 等电脑上线"
        else -> "排队转写中"
    }
    BaseChunkStatus.PENDING -> if (mode == WorkMode.DIRECT_ASR) {
        when {
            sync.localAsrRunning == item.rec.name -> "手机转写中…"
            !asrConfigured -> "待配直连转写"
            sync.hasLocalAsrError -> "转写待重试"
            else -> "排队手机转写"
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
                shape = RoundedCornerShape(10.dp),
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(statusText)
                    item.placeLabel?.let { MetaTag(Icons.Rounded.Place, it, deck.place) }
                }
            }
        }

        if (r.hasAudio) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(deck.tapeWell)
                    .border(1.dp, deck.tapeGroove, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Waveform(
                    levels = wave,
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                    progress = progress,
                    color = colors.onSurfaceVariant.copy(alpha = 0.65f),
                    playedColor = colors.onSurface,
                    playheadColor = if (selected) deck.signalRed else null,
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

// ───────────────────── Tab 3 & Tab 4：人物与地点 ─────────────────────

@Composable
private fun PeoplePageView(
    people: List<PersonCardUi>,
    unnamedCount: Int,
    onPlayClip: (Pair<Recording, Segment>) -> Unit,
    onRename: (Speaker) -> Unit,
    onMerge: (Speaker) -> Unit,
    onUnmerge: (String) -> Unit,
) {
    PageList {
        if (people.isEmpty()) {
            item(key = "empty_people") {
                EmptyStateCard(
                    icon = Icons.Rounded.Group,
                    title = "尚无声纹人物档案",
                    body = "录音中单人说话满 10 秒后，本机内置声纹模型会自动建档（豆包直连同样支持），可试听、起名或合并。",
                )
            }
        } else if (unnamedCount >= 2) {
            item(key = "hint_people") {
                Hint("有多个未命名编号时，可先试听原声，再从卡片菜单合并同一人。")
            }
        }
        items(people, key = { it.speaker.id }, contentType = { "person_card" }) { item ->
            val p = item.speaker
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
                            text = "${item.count} 句" + if (item.aliases.isNotEmpty()) " · 已合并 ${item.aliases.size} 个声纹" else "",
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
                                        leadingIcon = { Icon(Icons.Rounded.MergeType, contentDescription = null) },
                                        onClick = { menu = false; onMerge(p) },
                                    )
                                }
                                item.aliases.forEach { a ->
                                    DropdownMenuItem(
                                        text = { Text("拆出 ${a.name ?: a.defaultName}") },
                                        leadingIcon = { Icon(Icons.Rounded.CallSplit, contentDescription = null) },
                                        onClick = { menu = false; onUnmerge(a.id) },
                                    )
                                }
                            }
                        }
                    }
                }
                item.quote?.let { quote ->
                    Spacer(Modifier.height(8.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = DeckTheme.colors.tapeWell) {
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
                    item.clip?.let { c -> ActionChip(Icons.Rounded.PlayArrow, "试听原声") { onPlayClip(c) } }
                    ActionChip(Icons.Rounded.Edit, "改名") { onRename(p) }
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
                    title = if (hasLocation) "还没有记录到地点" else "定位权限未开启",
                    body = "开启后每 5 分钟在手机本地标记对话位置，位置只存在本机，绝不上传服务器。",
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
                                Text("新录音将不再标记位置（已有地点仍保留在本机）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            FilledTonalButton(
                                onClick = onRequestLocation,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.pressScale(),
                            ) { Text("重新开启") }
                        }
                    }
                }
            }
            item(key = "places_hint") { Hint("所有地点仅保存在这部手机上，可改名或合并常用场所。") }
        }
        items(places, key = { it.place.id }, contentType = { "place_card" }) { item ->
            val p = item.place
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
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
                    if (places.size > 1) ActionChip(Icons.Rounded.MergeType, "合并") { onMerge(p) }
                    item.aliases.forEach { a ->
                        ActionChip(Icons.Rounded.CallSplit, "拆出 ${a.label}") { onUnmerge(a.id) }
                    }
                }
            }
        }
    }
}

// ───────────────────── 底部紧凑回放台与通用机械构件 ─────────────────────

@Composable
private fun TapePlayerDeck(player: Player) {
    val r = player.current ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var dragging by remember { mutableStateOf<Float?>(null) }
    var showText by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val deck = DeckTheme.colors
    val colors = MaterialTheme.colorScheme
    val dur = player.durationMs.coerceAtLeast(1)
    val shown = dragging?.let { (it * dur).toInt() } ?: player.positionMs
    val tr = remember(r.name) { Transcripts.get(ctx, r.name) }

    Surface(
        color = colors.surfaceContainerLowest,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, colors.outlineVariant),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        ) {
            AnimatedVisibility(visible = showText && tr != null) {
                if (tr != null) TranscriptPane(r = r, tr = tr, player = player)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TapeReelsGraphic(spinning = player.playing)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${r.start.toLocalDate().format(MainActivity.MD)} ${r.start.plusSeconds((shown / 1000).toLong()).format(MainActivity.T)}",
                        style = MaterialTheme.typography.labelLarge.merge(TabularMono),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        text = "${MainActivity.fmtMs(shown)} / ${MainActivity.fmtMs(player.durationMs)}",
                        style = MaterialTheme.typography.labelSmall.merge(TabularMono),
                        color = colors.onSurfaceVariant,
                    )
                }
                if (tr?.segments?.isNotEmpty() == true) {
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
                IconButton(onClick = { player.skip(-10_000) }, modifier = Modifier.size(36.dp).pressScale()) {
                    Icon(Icons.Rounded.Replay10, contentDescription = "后退 10 秒", modifier = Modifier.size(20.dp))
                }
                FilledIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        player.toggle()
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(40.dp).pressScale(),
                ) {
                    Icon(
                        if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (player.playing) "暂停" else "播放",
                    )
                }
                IconButton(onClick = { player.skip(10_000) }, modifier = Modifier.size(36.dp).pressScale()) {
                    Icon(Icons.Rounded.Forward10, contentDescription = "前进 10 秒", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { player.close() }, modifier = Modifier.size(36.dp).pressScale()) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭播放器", modifier = Modifier.size(20.dp))
                }
            }

            Spacer(Modifier.height(6.dp))

            var wave by remember(r.name) { mutableStateOf(Waveforms.cached(r.file)) }
            LaunchedEffect(r.name) { if (wave == null) wave = Waveforms.get(r.file) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(deck.tapeWell)
                    .border(1.dp, deck.tapeGroove, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Waveform(
                    levels = wave,
                    modifier = Modifier.fillMaxWidth().height(34.dp),
                    progress = dragging ?: (player.positionMs.toFloat() / dur),
                    color = colors.onSurfaceVariant.copy(alpha = 0.6f),
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
private fun TranscriptPane(r: Recording, tr: Transcript, player: Player) {
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
            .clip(RoundedCornerShape(8.dp))
            .background(deck.tapeWell)
            .border(1.dp, deck.tapeGroove, RoundedCornerShape(8.dp))
            .padding(6.dp),
        state = listState,
    ) {
        items(tr.segments.size) { i ->
            val seg = tr.segments[i]
            val active = i == cur
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent)
                    .clickable { player.seekTo((seg.start * 1000).toInt()) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = r.start.plusSeconds(seg.start.toLong()).format(MainActivity.T),
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

@Composable
private fun TapeReelsGraphic(spinning: Boolean) {
    val deg = if (spinning) {
        val transition = rememberInfiniteTransition(label = "reels")
        val anim by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
            label = "reelAngle",
        )
        anim
    } else {
        0f
    }
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
            rotate(degrees = deg, pivot = c) {
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
    val shown by animateColorAsState(color, tween(220), label = "dot")
    val alpha = if (breathing) {
        val pulse by rememberInfiniteTransition(label = "dotPulse").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "dotAlpha",
        )
        pulse
    } else {
        1f
    }
    Box(
        Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(shown.copy(alpha = alpha)),
    )
}

@Composable
private fun MetaTag(icon: ImageVector, text: String, color: Color, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
        Spacer(Modifier.width(3.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatusPill(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

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
                    .clip(RoundedCornerShape(12.dp))
                    .background(DeckTheme.colors.tapeWell)
                    .border(1.dp, DeckTheme.colors.tapeGroove, RoundedCornerShape(12.dp)),
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
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.pressScale(),
                ) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun Avatar(text: String, bg: Color, fg: Color) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(bg), contentAlignment = Alignment.Center) {
        Text(text, color = fg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
    )
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

private fun formatDb(db: Float): String = String.format(Locale.US, "%.1fdB", db)

