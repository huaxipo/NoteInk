package com.noteink.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noteink.app.ink.Ink
import com.noteink.app.model.InkTool
import java.io.File

/**
 * 工具图标：直接画出「这个工具画出来的线」，比放个抽象图标更直观。
 */
@Composable
fun ToolGlyph(tool: InkTool, tint: Color, size: Dp = 26.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        when (tool) {
            InkTool.PEN -> drawLine(
                tint, Offset(s * 0.18f, s * 0.82f), Offset(s * 0.80f, s * 0.20f),
                strokeWidth = s * 0.10f, cap = StrokeCap.Round
            )
            InkTool.PENCIL -> {
                drawLine(
                    tint.copy(alpha = 0.85f),
                    Offset(s * 0.18f, s * 0.82f), Offset(s * 0.80f, s * 0.20f),
                    strokeWidth = s * 0.13f, cap = StrokeCap.Round
                )
                drawLine(
                    tint.copy(alpha = 0.35f),
                    Offset(s * 0.24f, s * 0.76f), Offset(s * 0.74f, s * 0.26f),
                    strokeWidth = s * 0.05f, cap = StrokeCap.Round
                )
            }
            InkTool.MARKER -> drawLine(
                tint, Offset(s * 0.18f, s * 0.80f), Offset(s * 0.80f, s * 0.22f),
                strokeWidth = s * 0.22f, cap = StrokeCap.Round
            )
            InkTool.HIGHLIGHTER -> drawLine(
                tint.copy(alpha = 0.55f),
                Offset(s * 0.16f, s * 0.72f), Offset(s * 0.84f, s * 0.30f),
                strokeWidth = s * 0.30f, cap = StrokeCap.Round
            )
            InkTool.ERASER -> {
                rotate(degrees = -35f, pivot = Offset(s * 0.5f, s * 0.5f)) {
                    drawRoundRect(
                        color = tint.copy(alpha = 0.85f),
                        topLeft = Offset(s * 0.20f, s * 0.36f),
                        size = Size(s * 0.60f, s * 0.28f),
                        cornerRadius = CornerRadius(s * 0.07f),
                        style = Stroke(width = s * 0.08f)
                    )
                }
            }
            InkTool.LASSO -> {
                val p = Path().apply {
                    moveTo(s * 0.50f, s * 0.20f)
                    cubicTo(s * 0.82f, s * 0.20f, s * 0.86f, s * 0.62f, s * 0.56f, s * 0.70f)
                    cubicTo(s * 0.26f, s * 0.78f, s * 0.14f, s * 0.50f, s * 0.26f, s * 0.34f)
                    cubicTo(s * 0.34f, s * 0.23f, s * 0.44f, s * 0.20f, s * 0.50f, s * 0.20f)
                }
                drawPath(
                    p, tint,
                    style = Stroke(
                        width = s * 0.075f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(s * 0.13f, s * 0.09f), 0f)
                    )
                )
            }
            InkTool.LASSO_ERASE -> {
                // 虚线圈 + 一道斜杠：圈住即删
                val p = Path().apply {
                    moveTo(s * 0.50f, s * 0.16f)
                    cubicTo(s * 0.84f, s * 0.16f, s * 0.90f, s * 0.62f, s * 0.52f, s * 0.72f)
                    cubicTo(s * 0.20f, s * 0.80f, s * 0.10f, s * 0.48f, s * 0.24f, s * 0.30f)
                    cubicTo(s * 0.32f, s * 0.20f, s * 0.43f, s * 0.16f, s * 0.50f, s * 0.16f)
                }
                drawPath(
                    p, tint.copy(alpha = 0.9f),
                    style = Stroke(
                        width = s * 0.075f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(s * 0.11f, s * 0.08f), 0f)
                    )
                )
                drawLine(
                    tint, Offset(s * 0.30f, s * 0.78f), Offset(s * 0.74f, s * 0.30f),
                    strokeWidth = s * 0.10f, cap = StrokeCap.Round
                )
            }
        }
    }
}

/** 调色盘色块 */
@Composable
fun ColorDot(
    color: Int,
    selected: Boolean,
    size: Dp = 30.dp,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(if (selected) size - 6.dp else size - 10.dp)
                .background(Color(color), CircleShape)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else Color(0x33000000),
                    shape = CircleShape
                )
        )
    }
}

/** HSV 自定义取色 */
@Composable
fun HsvColorDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit
) {
    val hsv = remember {
        val a = FloatArray(3)
        android.graphics.Color.colorToHSV(initial, a)
        a
    }
    var h by remember { mutableStateOf(hsv[0]) }
    var s by remember { mutableStateOf(hsv[1].coerceAtLeast(0.15f)) }
    var v by remember { mutableStateOf(hsv[2].coerceAtLeast(0.2f)) }
    val cur = Ink.hsv(h, s, v)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义颜色") },
        text = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(Color(cur), RoundedCornerShape(12.dp))
                )
                ChannelSlider("色相", h, 0f, 360f) { h = it }
                ChannelSlider("饱和", s, 0f, 1f) { s = it }
                ChannelSlider("明度", v, 0f, 1f) { v = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(cur); onDismiss() }) { Text("使用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun ChannelSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.size(width = 34.dp, height = 20.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = min..max,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 按目标宽度采样解码，避免大图吃内存 */
fun decodeSampled(file: File, reqW: Int): Bitmap? {
    if (!file.exists()) return null
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, o)
    if (o.outWidth <= 0) return null
    var sample = 1
    while (o.outWidth / (sample * 2) >= reqW) sample *= 2
    val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.absolutePath, o2)
}

@Composable
fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    )
}

@Composable
fun Spacer4() {
    Box(Modifier.padding(2.dp))
}

@Composable
fun TinySpacer(width: Dp) {
    Box(Modifier.size(width, 1.dp))
}

@Composable
fun ToolRowBase(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}
