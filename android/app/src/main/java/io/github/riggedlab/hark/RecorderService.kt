package io.github.riggedlab.hark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.RejectedExecutionException
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 前台服务：保持蓝牙连接，把音频写成文件。App 切到后台、锁屏后也继续工作。 */
class RecorderService : Service() {

    private lateinit var ble: BleLink
    private lateinit var chunks: ChunkRecorder
    private val io = Executors.newSingleThreadScheduledExecutor()   // 写录音文件都在这个线程
    private val net = Executors.newSingleThreadScheduledExecutor()  // 判断人声、上传、取文字（比较慢，不能挡住写录音）
    private var wakeLock: PowerManager.WakeLock? = null
    private var bytesThisSecond = 0
    private lateinit var uploader: Uploader
    private var started = false
    private val main = Handler(Looper.getMainLooper())
    private val syncQueued = AtomicBoolean(false)
    @Volatile private var closing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!BlePermissions.hasRequired(this)) {
            stopWithMessage(BlePermissions.DENIED_MESSAGE)
            return
        }
        uploader = Uploader(this)
        chunks = ChunkRecorder(chunkDir(this), onChunkReady = { requestSync() })
        val notifySpeech = Runnable { if (!closing) chunks.onSpeechDetected() }
        LiveAudioMonitor.start(this) {
            if (!closing) runCatching { io.execute(notifySpeech) }
        }
        ble = BleLink(
            this,
            onAudio = audio@ { seq, opus ->
                if (closing) return@audio
                try {
                    io.execute {
                        try {
                            if (chunks.onPacket(seq, opus)) {
                                bytesThisSecond += opus.size
                                val writtenAt = chunks.lastWriteNanos
                                val prev = AppState.status.value.lastWriteNanos
                                if (writtenAt != 0L && (prev == 0L || writtenAt - prev >= 400_000_000L)) {
                                    AppState.update { it.copy(lastWriteNanos = writtenAt) }
                                }
                                LiveAudioMonitor.offer(seq, opus)
                            }
                        } catch (_: Exception) {
                            AppState.update { it.copy(message = "录音写入失败，请检查手机存储空间") }
                        }
                    }
                } catch (_: RejectedExecutionException) { /* 撤权停止时丢弃迟到的回调 */ }
            },
            onBattery = { mv -> AppState.update { it.copy(batteryMv = mv) } },
            onState = stateChanged@ { state, info ->
                if (closing) return@stateChanged
                android.util.Log.i("BleLink", "state $state ${info ?: ""}")
                if (state != LinkState.STREAMING) {
                    LiveAudioMonitor.reset()
                    io.execute {
                        runCatching { chunks.closeChunk() }
                        chunks.resetSequence()
                    }
                }
                AppState.update {
                    it.copy(link = state,
                        deviceName = if (state == LinkState.STREAMING) info else it.deviceName,
                        message = if (state != LinkState.STREAMING) info else null,
                        lastWriteNanos = if (state == LinkState.STREAMING) it.lastWriteNanos else 0,
                        batteryMv = if (state == LinkState.SCANNING || state == LinkState.PAIRING_REQUIRED) null else it.batteryMv)
                }
                updateNotification()
            },
            onPermissionLost = { stopWithMessage(BlePermissions.REVOKED_MESSAGE) },
        )
        // 每秒刷新一次统计
        io.scheduleAtFixedRate({
            // 正在扫描或静默等待重连时，系统未必再回调 BLE；复用统计节拍检测撤权。
            if (!closing && !BlePermissions.hasRequired(this)) {
                main.post { if (!closing) stopWithMessage(BlePermissions.REVOKED_MESSAGE) }
                return@scheduleAtFixedRate
            }
            val bps = bytesThisSecond
            bytesThisSecond = 0
            AppState.update {
                it.copy(totalPackets = chunks.totalPackets, lostPackets = chunks.lostPackets, silentFrames = chunks.silentFrames,
                    bytesPerSec = bps, currentChunk = chunks.currentFileName,
                    currentChunkSeconds = chunks.currentSeconds)
            }
        }, 1, 1, TimeUnit.SECONDS)
        // 录音时每 5 分钟记一次位置（只存在手机上）
        io.scheduleWithFixedDelay({
            if (AppState.status.value.link == LinkState.STREAMING) Places.requestFix(this)
            Places.backfill(this)
        }, 10, 300, TimeUnit.SECONDS)
        // 每小时清理一次 7 天前的录音
        io.scheduleAtFixedRate({ Recordings.cleanup(chunkDir(this), Config.mode(this)) }, 0, 1, TimeUnit.HOURS)
        // 每分钟检查一次有没有录好但还没上传/转写的文件
        net.scheduleWithFixedDelay({ requestSync() }, 5, 60, TimeUnit.SECONDS)
    }

    private fun requestSync() {
        if (closing || !syncQueued.compareAndSet(false, true)) return
        try {
            net.execute {
                // 单线程防重入；执行中产生的新片段再排一次，不等下一分钟
                syncQueued.set(false)
                val (ok, left) = uploader.processPending()
                AppState.update { it.copy(pendingUploads = left, uploadedThisSession = it.uploadedThisSession + ok) }
                if (Config.mode(this@RecorderService) == WorkMode.CLOUD) {
                    runBlocking { Sync.pollOnce(this@RecorderService) }
                }
            }
        } catch (_: RejectedExecutionException) { syncQueued.set(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (closing) return START_NOT_STICKY
        if (!BlePermissions.hasRequired(this)) {
            return stopWithMessage(if (started) BlePermissions.REVOKED_MESSAGE else BlePermissions.DENIED_MESSAGE)
        }
        if (!startForegroundCompat()) return START_NOT_STICKY
        // 刚给了定位权限：只需要重新声明前台服务类型，蓝牙不用重连
        if (intent?.action == ACTION_REFRESH && started) { Places.requestFix(this); return START_STICKY }
        val wasStarted = started
        started = true
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hark:recorder").apply { acquire() }
        }
        if (intent?.action == ACTION_REPAIR) ble.repair()
        else if (!wasStarted) ble.start()
        if (closing) return START_NOT_STICKY
        return START_STICKY      // 被系统杀掉后尽量自动重启
    }

    private fun stopWithMessage(message: String): Int {
        closing = true
        if (::ble.isInitialized) ble.stop()
        AppState.update { it.copy(link = LinkState.STOPPED, message = message,
            lastWriteNanos = 0, bytesPerSec = 0) }
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        closing = true
        main.removeCallbacksAndMessages(null)
        if (::ble.isInitialized) ble.stop()
        LiveAudioMonitor.stop()
        val ctx = applicationContext
        if (::chunks.isInitialized) finishRecording(io, closeChunk = {
            try { chunks.closeChunk() }
            finally { wakeLock?.release(); wakeLock = null }
        }, upload = {
            try {
                val finalUploader = Uploader(ctx)
                if (finalUploader.hasPendingWork()) {
                    val (done, pending) = finalUploader.processPending()
                    AppState.update { it.copy(pendingUploads = pending,
                        uploadedThisSession = it.uploadedThisSession + done) }
                    if (Config.mode(ctx) == WorkMode.CLOUD) {
                        runBlocking { Sync.pollOnce(ctx, 0) }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("RecorderService", "最后一段待下次前台同步重试", e)
            }
        })
        net.shutdownNow()
        io.shutdown()
        AppState.update { it.copy(link = LinkState.STOPPED, lastWriteNanos = 0, bytesPerSec = 0) }
        super.onDestroy()
    }

    private fun startForegroundCompat(): Boolean {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "录音状态", NotificationManager.IMPORTANCE_LOW))
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // location 提权失败时退回蓝牙类型；回退也由外层捕获，不能因缺权限崩溃。
                val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                val withLoc = if (Places.hasPermission(this)) base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else base
                if (withLoc != base) {
                    try { startForeground(NOTIF_ID, n, withLoc) }
                    catch (_: RuntimeException) { startForeground(NOTIF_ID, n, base) }
                } else startForeground(NOTIF_ID, n, base)
            } else {
                startForeground(NOTIF_ID, n)
            }
            return true
        } catch (e: RuntimeException) {
            android.util.Log.w("RecorderService", "前台录音服务启动失败", e)
            stopWithMessage(if (!BlePermissions.hasRequired(this)) {
                if (started) BlePermissions.REVOKED_MESSAGE else BlePermissions.DENIED_MESSAGE
            } else "无法启动录音，请重试")
            return false
        }
    }

    private fun buildNotification(): Notification {
        val s = AppState.status.value
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val text = buildString {
            append(s.link.label)
            s.batteryMv?.let { append(" · 电量 ${MainActivity.batteryPct(it)}%") }
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Hark")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    companion object {
        const val ACTION_STOP = "stop"
        const val ACTION_REFRESH = "refresh"
        const val ACTION_REPAIR = "repair"
        private const val CHANNEL = "recorder"
        private const val NOTIF_ID = 1
        fun chunkDir(ctx: Context) = File(ctx.filesDir, "chunks")
    }
}
