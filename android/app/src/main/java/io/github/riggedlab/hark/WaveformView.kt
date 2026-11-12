package io.github.riggedlab.hark

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * 画波形。progress (0..1) 之前的部分用 playedColor。
 * 如果给了 onSeek，就可以在波形上点击或左右拖动来跳转（参数是 0..1）。
 */
@Composable
fun Waveform(
    levels: FloatArray?,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    color: Color,
    playedColor: Color = color,
    onSeekPreview: ((Float) -> Unit)? = null,
    onSeek: ((Float) -> Unit)? = null,
) {
    var m = modifier
    if (onSeek != null) {
        m = m.pointerInput(Unit) {
            detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) }
        }.pointerInput(Unit) {
            var last = 0f
            detectHorizontalDragGestures(
                onDragStart = { last = (it.x / size.width).coerceIn(0f, 1f); onSeekPreview?.invoke(last) },
                onDragEnd = { onSeek(last) },
                onHorizontalDrag = { ch, _ -> last = (ch.position.x / size.width).coerceIn(0f, 1f); onSeekPreview?.invoke(last) },
            )
        }
    }
    Canvas(m) {
        val w = size.width
        val h = size.height
        val barW = 2.dp.toPx()
        val gap = 1.dp.toPx()
        val bars = max(1, (w / (barW + gap)).toInt())
        val mid = h / 2
        if (levels == null || levels.isEmpty()) {
            drawRect(color.copy(alpha = 0.25f), Offset(0f, mid - 0.5f), Size(w, 1f))
            return@Canvas
        }
        for (b in 0 until bars) {
            // 每根柱子取它覆盖范围内的最大值，短促的声音也不会被抹掉
            val from = b * levels.size / bars
            val to = max(from + 1, (b + 1) * levels.size / bars).coerceAtMost(levels.size)
            var v = 0f
            for (i in from until to) if (levels[i] > v) v = levels[i]
            val bh = max(1.5f, v * h)
            val x = b * (barW + gap)
            val c = if (x / w <= progress) playedColor else color
            drawRect(c, Offset(x, mid - bh / 2), Size(barW, bh))
        }
    }
}

