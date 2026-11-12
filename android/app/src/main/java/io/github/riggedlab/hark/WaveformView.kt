package io.github.riggedlab.hark

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * 机械走带声波尺：
 * - 支持静态概览与可拖拽磁头定位（onSeek != null 时显示朱砂红磁头读针 Playhead）
 * - 内置磁带中轴基准细线与顶部微刻度，轻拟物不抢戏
 */
@Composable
fun Waveform(
    levels: FloatArray?,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    color: Color,
    playedColor: Color = color,
    playheadColor: Color? = null,
    onSeekPreview: ((Float) -> Unit)? = null,
    onSeek: ((Float) -> Unit)? = null,
) {
    var m = modifier
    if (onSeek != null) {
        m = m.pointerInput(Unit) {
            detectTapGestures { onSeek((it.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)) }
        }.pointerInput(Unit) {
            var last = 0f
            detectHorizontalDragGestures(
                onDragStart = {
                    last = (it.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onSeekPreview?.invoke(last)
                },
                onDragEnd = { onSeek(last) },
                onHorizontalDrag = { ch, _ ->
                    last = (ch.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onSeekPreview?.invoke(last)
                },
            )
        }
    }
    Canvas(m) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        val barW = 2.2.dp.toPx()
        val gap = 1.6.dp.toPx()
        val step = barW + gap
        val bars = max(1, (w / step).toInt())
        val radius = CornerRadius(barW / 2f, barW / 2f)

        // 精密走带尺顶部微刻度线（每 12 格一短刻）
        val tickColor = color.copy(alpha = 0.28f)
        val tickH = 2.5.dp.toPx()
        for (b in 0 until bars step 12) {
            val tx = b * step + barW / 2f
            drawLine(tickColor, Offset(tx, 0f), Offset(tx, tickH), strokeWidth = 1.dp.toPx())
        }

        // 中轴基准导轨细线
        drawLine(color.copy(alpha = 0.22f), Offset(0f, mid), Offset(w, mid), strokeWidth = 1.dp.toPx())

        val verticalPad = 5.dp.toPx()
        val usableH = max(4f, h - verticalPad * 2f)
        if (levels != null && levels.isNotEmpty()) {
            for (b in 0 until bars) {
                val from = b * levels.size / bars
                val to = max(from + 1, (b + 1) * levels.size / bars).coerceAtMost(levels.size)
                var v = 0f
                for (i in from until to) if (levels[i] > v) v = levels[i]
                val bh = max(2.dp.toPx(), v * usableH)
                val x = b * step
                val active = progress > 0.001f && (x / w.coerceAtLeast(1f)) <= progress
                drawRoundRect(
                    color = if (active) playedColor else color,
                    topLeft = Offset(x, mid - bh / 2f),
                    size = Size(barW, bh),
                    cornerRadius = radius,
                )
            }
        }

        // 垂直磁头读针（Playhead Needle）
        if (playheadColor != null && (onSeek != null || progress > 0.001f)) {
            val px = (progress.coerceIn(0f, 1f) * w).coerceIn(1.dp.toPx(), max(1.dp.toPx(), w - 1.dp.toPx()))
            drawLine(
                color = playheadColor,
                start = Offset(px, 0f),
                end = Offset(px, h),
                strokeWidth = 1.8.dp.toPx(),
            )
            val capW = 5.dp.toPx()
            val capH = 3.dp.toPx()
            drawRoundRect(
                color = playheadColor,
                topLeft = Offset(px - capW / 2f, 0f),
                size = Size(capW, capH),
                cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
            )
        }
    }
}
