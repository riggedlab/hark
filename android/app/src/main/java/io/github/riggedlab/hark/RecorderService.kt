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
import android.os.IBinder
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
    private val syncQueued = AtomicBoolean(false)
    @Volatile private var closing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        uploader = Uploader(this)
        chunks = ChunkRecorder(chunkDir(this), onChunkReady = { requestSync() })
        ble = BleLink(
            this,
            onAudio = { seq, opus ->
                io.execute {
                    try {
                        if (chunks.onPacket(seq, opus)) {
                            bytesThisSecond += opus.size
                            val writtenAt = chunks.lastWriteNanos
                            if (AppState.status.value.lastWriteNanos != writtenAt)
                                AppState.update { it.copy(lastWriteNanos = writtenAt) }
                            LiveAudioMonitor.offer(seq, opus)
                        }
                    } catch (_: Exception) {
                        AppState.update { it.copy(message = "录音写入失败，请检查手机存储空间") }
                    }
                }
            },
            onBattery = { mv -> AppState.update { it.copy(batteryMv = mv) } },
            onState = { state, info ->
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
        )
        // 每秒刷新一次统计
        io.scheduleAtFixedRate({
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
        io.scheduleAtFixedRate({ Recordings.cleanup(chunkDir(this)) }, 0, 1, TimeUnit.HOURS)
        // 每分钟检查一次有没有录好但还没上传的文件
        net.scheduleWithFixedDelay({ requestSync() }, 5, 60, TimeUnit.SECONDS)
    }

    private fun requestSync() {
        if (closing || !syncQueued.compareAndSet(false, true)) return
        try {
            net.execute {
                // 单线程防重入；执行中产生的新片段再排一次，不等下一分钟
                syncQueued.set(false)
                val (ok, left) = uploader.uploadPending()
                AppState.update { it.copy(pendingUploads = left, uploadedThisSession = it.uploadedThisSession + ok) }
                runBlocking { Sync.pollOnce(this@RecorderService) }
            }
        } catch (_: RejectedExecutionException) { syncQueued.set(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
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
        return START_STICKY      // 被系统杀掉后尽量自动重启
    }

    override fun onDestroy() {
        closing = true
        ble.stop()
        LiveAudioMonitor.reset()
        val ctx = applicationContext
        finishRecording(io, closeChunk = {
            try { chunks.closeChunk() }
            finally { wakeLock?.release(); wakeLock = null }
        }, upload = {
            try {
                val finalUploader = Uploader(ctx)
                if (finalUploader.hasPending()) {
                    val (uploaded, pending) = finalUploader.uploadPending()
                    AppState.update { it.copy(pendingUploads = pending,
                        uploadedThisSession = it.uploadedThisSession + uploaded) }
                    runBlocking { Sync.pollOnce(ctx, 0) }
                }
            } catch (e: Exception) {
                android.util.Log.w("RecorderService", "最后一段待下次前台同步重试", e)
            }
        })
        net.shutdownNow()
        io.shutdown()
        AppState.update { it.copy(link = LinkState.STOPPED) }
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "录音状态", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 有定位权限时同时声明 location 类型，这样后台也能定位；从后台被系统重启时不允许，就退回只用蓝牙类型
            val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            val withLoc = if (Places.hasPermission(this)) base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else base
            runCatching { startForeground(NOTIF_ID, n, withLoc) }
                .onFailure { startForeground(NOTIF_ID, n, base) }
        } else {
            startForeground(NOTIF_ID, n)
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
            .setContentTitle("Hark · 谛听")
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
