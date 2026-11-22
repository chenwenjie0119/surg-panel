package com.cwj.surgpanel.ui.imaging

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cwj.surgpanel.R
import com.cwj.surgpanel.core.LinkMode
import com.cwj.surgpanel.core.Modality
import com.cwj.surgpanel.core.SignalEngine
import com.cwj.surgpanel.core.SurgicalSession
import com.cwj.surgpanel.ui.common.ChipButton
import com.cwj.surgpanel.ui.common.PanelFrame
import com.cwj.surgpanel.ui.common.SectionLabel
import com.cwj.surgpanel.ui.common.StatusDot
import com.cwj.surgpanel.ui.common.rememberVisualClock
import com.cwj.surgpanel.ui.theme.Palette
import com.pico.spatial.ui.design.Text
import kotlin.math.min

/**
 * 影像面板：CT / MRI 轴位切面。
 * 与体征面板共享同一条手术时间轴——当联动开启时，切层索引会跟随外科医生的关注点自动推进。
 */
@Composable
fun ImagingPanel() {
    // 只持句柄不读值：面板主体不参与每帧重组
    val clock = rememberVisualClock()
    val modality = SurgicalSession.modality
    val slice = SurgicalSession.sliceIndex
    val ww = SurgicalSession.windowWidth
    val wl = SurgicalSession.windowLevel
    val zoom = SurgicalSession.imagingZoom

    val image = remember(modality, slice, (ww / 25f).toInt(), (wl / 10f).toInt()) {
        renderSlice(modality, slice, ww, wl)
    }

    PanelFrame(
        title = stringResource(R.string.imaging_12, modality.label),
        tag = "IMAGING",
        accent = Palette.Cyan,
        trailing = {
            StatusDot(active = SurgicalSession.linkMode != LinkMode.OFF, color = Palette.Mint, label = "LINK")
            Spacer(Modifier.width(10.dp))
            Text(
                text = String.format(java.util.Locale.US, "%.2fx", zoom),
                color = Palette.Cyan,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    ) {
        // 工具条
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Modality.values().forEach { m ->
                ChipButton(m.label, modality == m, Palette.Cyan) { SurgicalSession.modality = m }
            }
            Spacer(Modifier.width(6.dp))
            ChipButton(stringResource(R.string.imaging_01), false) {
                SurgicalSession.sliceIndex = (slice - 1).coerceAtLeast(0)
            }
            ChipButton(stringResource(R.string.imaging_02), false) {
                SurgicalSession.sliceIndex = (slice + 1).coerceAtMost(95)
            }
            ChipButton(stringResource(R.string.imaging_03), false) {
                SurgicalSession.windowWidth = (ww - 50f).coerceAtLeast(80f)
            }
            ChipButton(stringResource(R.string.imaging_04), false) {
                SurgicalSession.windowWidth = (ww + 50f).coerceAtMost(1600f)
            }
            ChipButton(stringResource(R.string.imaging_05), false) {
                SurgicalSession.windowLevel = (wl - 10f).coerceAtLeast(-200f)
            }
            ChipButton(stringResource(R.string.imaging_06), false) {
                SurgicalSession.windowLevel = (wl + 10f).coerceAtMost(300f)
            }
            ChipButton("ROI", SurgicalSession.roiLocked, Palette.Amber) {
                SurgicalSession.roiLocked = !SurgicalSession.roiLocked
            }
            ChipButton(stringResource(R.string.imaging_07), true, Palette.Cyan) {
                SurgicalSession.imagingZoom = when {
                    zoom < 1.3f -> zoom + 0.15f
                    else -> 1f
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 影像画布
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // 绘制阶段读取时钟：每帧只重绘，不重组
                val t = clock.value
                // 缩放矩阵：以画布中心为锚点等比放大切面，越界部分由裁剪域收敛到窗口内
                val side = min(size.width, size.height) * 0.94f * zoom
                val dst = side.toInt()
                val ox = ((size.width - side) / 2f).toInt()
                val oy = ((size.height - side) / 2f).toInt()

                clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
                    drawImage(
                        image = image,
                        dstOffset = IntOffset(ox, oy),
                        dstSize = IntSize(dst, dst)
                    )

                    // 外框
                    drawRect(
                        color = Palette.StrokeBright,
                        topLeft = Offset(ox.toFloat(), oy.toFloat()),
                        size = Size(dst.toFloat(), dst.toFloat()),
                        style = Stroke(width = 1.5f)
                    )
                }

                // 十字准线
                val cx = ox + dst / 2f
                val cy = oy + dst / 2f
                drawLine(
                    color = Palette.Cyan.copy(alpha = 0.28f),
                    start = Offset(cx, oy.toFloat()),
                    end = Offset(cx, (oy + dst).toFloat()),
                    strokeWidth = 1f
                )
                drawLine(
                    color = Palette.Cyan.copy(alpha = 0.28f),
                    start = Offset(ox.toFloat(), cy),
                    end = Offset((ox + dst).toFloat(), cy),
                    strokeWidth = 1f
                )

                // ROI 关注框：位置固定，仅以极低频微光标示“已锁定”，不脉动、不漂移
                if (SurgicalSession.roiLocked) {
                    // 亮度 0.20~0.32、周期约 1.2s，视觉上为静态微光而非呼吸闪烁
                    val glow = 0.26f + 0.06f * kotlin.math.sin(t * 5.24f)
                    val roiSize = dst * 0.26f
                    val rx = cx - roiSize / 2f
                    val ry = cy - roiSize / 2f
                    drawRect(
                        color = Palette.Amber.copy(alpha = glow),
                        topLeft = Offset(rx, ry),
                        size = Size(roiSize, roiSize),
                        style = Stroke(width = 2f)
                    )
                    drawRect(
                        color = Palette.Amber.copy(alpha = 0.06f),
                        topLeft = Offset(rx, ry),
                        size = Size(roiSize, roiSize)
                    )
                }

                // 标注文本
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.MONOSPACE
                    textSize = 10.sp.toPx()
                }

                // 叠加层坐标收敛：缩放后图元可能越界，标注一律夹回画布内，保证始终可读
                fun clampX(v: Float, pad: Float = 6f) = v.coerceIn(pad, (size.width - pad).coerceAtLeast(pad))
                fun clampY(v: Float, pad: Float = 12f) = v.coerceIn(pad, (size.height - pad).coerceAtLeast(pad))

                paint.color = android.graphics.Color.argb(200, 120, 214, 232)
                drawContext.canvas.nativeCanvas.apply {
                    drawText("R", clampX(ox + dst + 6f, 14f), cy + 4f, paint)
                    drawText("L", clampX(ox - 14f, 14f), cy + 4f, paint)
                    drawText("A", cx - 4f, clampY(oy - 6f), paint)
                    drawText("P", cx - 4f, clampY(oy + dst + 14f), paint)
                }

                // 左下角比例尺
                paint.color = android.graphics.Color.argb(180, 233, 241, 251)
                val barW = dst * 0.18f
                val barX = clampX(ox + 8f, 8f)
                val barY = clampY(oy + dst - 14f, 24f)
                drawContext.canvas.nativeCanvas.apply {
                    drawText("5 cm", barX, barY - 4f, paint)
                }
                drawLine(
                    color = Palette.TextPrimary.copy(alpha = 0.7f),
                    start = Offset(barX, barY),
                    end = Offset(barX + barW, barY),
                    strokeWidth = 2f
                )

                // 右上角切层号
                paint.color = android.graphics.Color.argb(230, 55, 216, 232)
                drawContext.canvas.nativeCanvas.drawText(
                    "SLICE ${slice + 1}/96  ·  ${SurgicalSession.modality.label}",
                    clampX(ox + 8f, 8f),
                    clampY(oy + 16f, 16f),
                    paint
                )

                // 左下角窗宽窗位
                paint.color = android.graphics.Color.argb(170, 185, 199, 216)
                drawContext.canvas.nativeCanvas.drawText(
                    "W ${ww.toInt()}  L ${wl.toInt()}  ${SurgicalSession.modality.unit}",
                    clampX(ox + 8f, 8f),
                    clampY(oy + 32f, 32f),
                    paint
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 底部信息条
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionLabel(stringResource(R.string.imaging_08), Palette.Cyan)
            Spacer(Modifier.weight(1f))
            Text(
                text = SurgicalSession.sliceLabel(),
                color = Palette.TextBody,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = when (SurgicalSession.linkMode) {
                    LinkMode.FULL -> stringResource(R.string.imaging_09)
                    LinkMode.FOCUS -> stringResource(R.string.imaging_10)
                    LinkMode.OFF -> stringResource(R.string.imaging_11)
                },
                color = Palette.TextDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal
            )
        }
    }
}

/** 将组织密度函数烘焙成一张 96×96 的灰阶位图，避免逐帧逐像素绘制。 */
private fun renderSlice(modality: Modality, slice: Int, ww: Float, wl: Float): ImageBitmap {
    val n = 96
    val pixels = IntArray(n * n)
    val mri = modality == Modality.MRI
    val lo = wl - ww / 2f
    val span = ww.coerceAtLeast(1f)
    for (y in 0 until n) {
        val fy = y / (n - 1f)
        for (x in 0 until n) {
            val fx = x / (n - 1f)
            val density = SignalEngine.tissueDensity(fx, fy, slice, if (mri) 7 else 3)
            val hu = if (mri) density * 1600f - 200f else density * 2400f - 1100f
            var v = ((hu - lo) / span).coerceIn(0f, 1f)
            v = v * v * (3f - 2f * v)
            val g = (v * 255f).toInt()
            val r = if (mri) (g * 0.72f).toInt() else g
            val gg = if (mri) (g * 0.82f).toInt() else g
            val b = if (mri) g else (g * 0.96f).toInt()
            pixels[y * n + x] = (0xFF shl 24) or (r shl 16) or (gg shl 8) or b
        }
    }
    return Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
}