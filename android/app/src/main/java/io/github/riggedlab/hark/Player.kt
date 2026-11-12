package io.github.riggedlab.hark

import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 简单的播放器状态，给界面用 */
class Player {
    private var mp: MediaPlayer? = null
    var current by mutableStateOf<Recording?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set
    /** 只播一句时，播到这里自动暂停 */
    private var stopAtMs: Int? = null

    fun open(r: Recording) {
        if (!r.hasAudio) return
        release()
        current = r
        mp = MediaPlayer().apply {
            setDataSource(r.file.absolutePath)
            setOnCompletionListener { this@Player.playing = false; positionMs = durationMs }
            prepare()
            this@Player.durationMs = if (duration > 0) duration else (r.durationSec * 1000).toInt()
            start()
        }
        playing = true
    }

    /** 播放某段录音里的一句：从 startMs 开始，到 endMs 自动暂停 */
    fun playRange(r: Recording, startMs: Int, endMs: Int) {
        if (!r.hasAudio) return
        if (current?.file != r.file || mp == null) open(r)
        seekTo(startMs)
        mp?.let { if (!it.isPlaying) { it.start(); playing = true } }
        stopAtMs = endMs
    }

    fun pause() {
        stopAtMs = null
        mp?.let { if (it.isPlaying) { it.pause(); positionMs = it.currentPosition } }
        playing = false
    }

    fun toggle() {
        stopAtMs = null
        val p = mp ?: return
        if (p.isPlaying) { p.pause(); playing = false }
        else {
            if (positionMs >= durationMs) p.seekTo(0)
            p.start(); playing = true
        }
    }

    fun seekTo(ms: Int) {
        val p = mp ?: return
        stopAtMs = null
        val t = ms.coerceIn(0, durationMs)
        p.seekTo(t.toLong(), MediaPlayer.SEEK_CLOSEST)
        positionMs = t
    }

    fun skip(deltaMs: Int) = seekTo(positionMs + deltaMs)

    /** 界面每 200ms 调一次，刷新进度 */
    fun tick() {
        mp?.let {
            if (it.isPlaying) positionMs = it.currentPosition
            stopAtMs?.let { stop -> if (positionMs >= stop && it.isPlaying) { it.pause(); playing = false; stopAtMs = null } }
        }
    }

    fun release() {
        mp?.release()
        mp = null
        stopAtMs = null
        playing = false
        positionMs = 0
    }

    fun close() {
        release()
        current = null
    }
}
