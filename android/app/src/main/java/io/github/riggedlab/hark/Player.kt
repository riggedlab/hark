package io.github.riggedlab.hark

import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 播放器状态，给界面用。
 * - 打开新录音用 prepareAsync，不卡主线程；准备好之前的 seek / 播放 / 暂停都先记下，准备好后一次到位，
 *   先 seek 再出声，不会先漏出录音开头。
 * - 状态（current / playing / positionMs）立即更新，界面点下去马上有反馈。
 */
class Player {
    private var mp: MediaPlayer? = null
    private var prepared = false
    /** 准备完成后要开始的位置 */
    private var pendingStartMs = 0

    var current by mutableStateOf<Recording?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set
    /** 播放倍速；按住气泡时临时 2x */
    var speed by mutableFloatStateOf(1f)
        private set
    /** 只播一句时，播到这里自动暂停 */
    private var stopAtMs: Int? = null
    /** 最近一次从 MediaPlayer 读到位置的时刻，用于两次读数之间平滑插值 */
    private var sampledAt = 0L

    fun open(r: Recording) = play(r, 0)

    /** 从 startMs 开始播放 r；同一段录音已就绪时直接跳转，否则后台准备 */
    fun play(r: Recording, startMs: Int) {
        if (!r.hasAudio) return
        stopAtMs = null
        if (current?.file == r.file && mp != null) {
            seekTo(startMs)
            resume()
            return
        }
        release()
        current = r
        durationMs = (r.durationSec * 1000).toInt()
        pendingStartMs = startMs.coerceAtLeast(0)
        positionMs = pendingStartMs
        playing = true
        sampledAt = SystemClock.uptimeMillis()
        val player = MediaPlayer()
        mp = player
        runCatching {
            player.setDataSource(r.file.absolutePath)
            player.setOnPreparedListener { p ->
                if (mp !== p) return@setOnPreparedListener
                prepared = true
                if (p.duration > 0) durationMs = p.duration
                if (pendingStartMs > 0) p.seekTo(pendingStartMs.coerceIn(0, durationMs).toLong(), MediaPlayer.SEEK_CLOSEST)
                if (playing) startAtSpeed(p)
                sampledAt = SystemClock.uptimeMillis()
            }
            player.setOnCompletionListener {
                playing = false
                stopAtMs = null
                positionMs = durationMs
            }
            player.setOnErrorListener { _, what, extra ->
                Log.w("Player", "play ${r.name} error $what/$extra")
                close()
                true
            }
            player.prepareAsync()
        }.onFailure {
            Log.w("Player", "open ${r.name} failed", it)
            close()
        }
    }

    /** 播放某段录音里的一句：从 startMs 开始，到 endMs 自动暂停 */
    fun playRange(r: Recording, startMs: Int, endMs: Int) {
        play(r, startMs)
        stopAtMs = endMs
    }

    private fun resume() {
        val p = mp ?: return
        if (prepared && positionMs >= durationMs) p.seekTo(0)
        if (prepared && !p.isPlaying) startAtSpeed(p)
        playing = true
        sampledAt = SystemClock.uptimeMillis()
    }

    /** 改倍速；MediaPlayer 在暂停时设置倍速会自动开始播放，所以暂停中只记下来，下次开始时再生效 */
    fun changeSpeed(value: Float) {
        if (speed == value) return
        val p = mp
        if (p != null && prepared && p.isPlaying) {
            positionMs = p.currentPosition
            runCatching { p.playbackParams = p.playbackParams.setSpeed(value) }
        }
        speed = value
        sampledAt = SystemClock.uptimeMillis()
    }

    /** 播到 endMs 自动暂停（null 取消），不改变当前播放位置 */
    fun limitTo(endMs: Int?) {
        stopAtMs = endMs
    }

    private fun startAtSpeed(p: MediaPlayer) {
        if (speed != 1f) runCatching { p.playbackParams = p.playbackParams.setSpeed(speed) }
        p.start()
    }

    fun pause() {
        stopAtMs = null
        val p = mp
        if (p != null && prepared && p.isPlaying) {
            p.pause()
            positionMs = p.currentPosition
        }
        playing = false
    }

    fun toggle() {
        stopAtMs = null
        if (mp == null) return
        if (playing) pause() else resume()
    }

    fun seekTo(ms: Int) {
        val p = mp ?: return
        stopAtMs = null
        val t = ms.coerceIn(0, durationMs.coerceAtLeast(0))
        if (prepared) p.seekTo(t.toLong(), MediaPlayer.SEEK_CLOSEST) else pendingStartMs = t
        positionMs = t
        sampledAt = SystemClock.uptimeMillis()
    }

    fun skip(deltaMs: Int) = seekTo(positionMs + deltaMs)

    /** 界面定时调用，刷新进度 */
    fun tick() {
        val p = mp ?: return
        if (!prepared) return
        if (p.isPlaying) {
            positionMs = p.currentPosition
            sampledAt = SystemClock.uptimeMillis()
        }
        stopAtMs?.let { stop ->
            if (positionMs >= stop && p.isPlaying) {
                p.pause()
                playing = false
                stopAtMs = null
            }
        }
    }

    /** 两次 tick 之间按真实时间插值的位置，只给逐帧动画用（进度条、高亮），不改变状态 */
    fun smoothPositionMs(): Int {
        if (!playing || !prepared) return positionMs
        val dt = (SystemClock.uptimeMillis() - sampledAt).coerceIn(0L, 300L)
        return (positionMs + dt * speed).toInt().coerceAtMost(durationMs)
    }

    fun release() {
        mp?.let { p ->
            p.setOnPreparedListener(null)
            p.setOnCompletionListener(null)
            p.setOnErrorListener(null)
            p.release()
        }
        mp = null
        prepared = false
        stopAtMs = null
        playing = false
        positionMs = 0
        speed = 1f
    }

    fun close() {
        release()
        current = null
    }
}
