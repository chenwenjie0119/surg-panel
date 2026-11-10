package com.cwj.surgpanel.ui.scope

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cwj.surgpanel.core.LinkMode
import com.cwj.surgpanel.core.SurgicalSession
import com.cwj.surgpanel.core.VoiceCommandRouter
import com.cwj.surgpanel.ui.common.ChipButton
import com.cwj.surgpanel.ui.common.PanelFrame
import com.cwj.surgpanel.ui.common.SectionLabel
import com.cwj.surgpanel.ui.common.StatusDot
import com.cwj.surgpanel.ui.common.rememberVisualClock
import com.cwj.surgpanel.ui.theme.Palette
import com.pico.spatial.ui.design.Text
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 主刀视角面板：内窥镜 / 显微镜画面。
 * 视野中心的器械位置与缩放比例参与联动——影像窗的 ROI 与内窥镜的放大倍数保持一致。
 */
@Composable
fun ScopePanel() {
    // 只持句柄不读值：除画布外的文本行不再随帧重组
    val clock = rememberVisualClock()
    val zoom = SurgicalSession.scopeZoom

    PanelFrame(
        title = "主刀视野 · 内窥镜",
        tag = "SCOPE",
        accent = Palette.Violet,
        trailing = {
            StatusDot(active = SurgicalSession.recording, color = Palette.Red, label = "REC")
            Spacer(Modifier.width(10.dp))
            Text(text = String.format(java.util.Locale.US, "%.2fx", zoom), color = Palette.Violet, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF05070B))
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // 绘制阶段读取时钟：每帧只重绘，不重组
                val t = clock.value
                val fluorescent = SurgicalSession.autoFluorescence
                // 缩放矩阵：视野半径随放大倍数变化，等价于内窥镜光学 zoom
                val r = min(size.width, size.height) * 0.45f * zoom
                val center = Offset(size.width / 2f, size.height / 2f)

                // 成像模式着色：白光为组织原色；荧光为近红外伪彩（青底 + 绿色富集信号）
                val tissueOuter = if (fluorescent) Color(0xFF17B6A4) else Color(0xFFC0705C)
                val tissueMid = if (fluorescent) Color(0xFF0A5C68) else Color(0xFF6E2A22)
                val tissueInner = if (fluorescent) Color(0xFF03161A) else Color(0xFF1A0A08)
                val foldColor = if (fluorescent) Color(0xFF7DF3DE) else Color(0xFFF0A78F)
                val vesselColor = if (fluorescent) Color(0xFF00E8A6) else Color(0xFF7E1B18)
                val shaftA = if (fluorescent) Color(0xFF11262E) else Color(0xFF2C333D)
                val shaftB = if (fluorescent) Color(0xFF1B3742) else Color(0xFF3A424E)

                // 圆形视野底：组织底色
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(tissueOuter, tissueMid, tissueInner),
                        center = center,
                        radius = r
                    ),
                    radius = r,
                    center = center
                )

                val oval = Path().apply {
                    addOval(Rect(center = center, radius = r))
                }

                clipPath(oval) {
                    // 皱襞
                    for (i in 0 until 6) {
                        val path = Path()
                        val baseY = center.y + (i - 2.5f) * r * 0.26f
                        var x = center.x - r
                        var first = true
                        while (x <= center.x + r) {
                            val y = baseY + sin((x - center.x) / r * 2.6f + t * 0.5f + i) * r * 0.07f
                            if (first) {
                                path.moveTo(x, y)
                                first = false
                            } else {
                                path.lineTo(x, y)
                            }
                            x += r * 0.06f
                        }
                        drawPath(
                            path = path,
                            color = Color(0xFFF0A78F).copy(alpha = 0.16f),
                            style = Stroke(width = r * 0.05f)
                        )
                    }

                    // 血管
                    for (i in 0 until 5) {
                        val path = Path()
                        val startX = center.x - r * 0.8f + i * r * 0.35f
                        path.moveTo(startX, center.y - r)
                        var step = 0
                        while (step < 12) {
                            val px = startX + sin(step * 0.7f + i + t * 0.3f) * r * 0.12f
                            val py = center.y - r + step * (r * 2f / 12f)
                            path.lineTo(px, py)
                            step++
                        }
                        drawPath(
                            path = path,
                            color = vesselColor.copy(alpha = if (fluorescent) 0.68f else 0.45f),
                            style = Stroke(width = r * 0.012f)
                        )
                    }

                    // 渗血点：形变与亮度收敛到 8%，读作“稳定出血点”而不是搏动闪烁
                    val bleedPulse = 0.92f + 0.08f * sin(t * 2.09f)
                    val exudate = SurgicalSession.scopeExudate
                    drawCircle(
                        color = Palette.Red.copy(alpha = 0.30f * bleedPulse),
                        radius = r * 0.10f * bleedPulse,
                        center = Offset(center.x + r * 0.32f, center.y + r * 0.22f)
                    )
                    // 渗出识别命中：置信度过阈值时叠加一圈高亮扩散环（模拟图像识别标注）
                    if (exudate >= 0.62f) {
                        // 识别命中环：保留低频微光表达“已标注”，不做高频呼吸
                        val ring = 0.82f + 0.08f * sin(t * 1.75f)
                        drawCircle(
                            color = Palette.Red.copy(alpha = 0.55f * ring),
                            radius = r * 0.16f,
                            center = Offset(center.x + r * 0.32f, center.y + r * 0.22f),
                            style = Stroke(width = r * 0.014f)
                        )
                        drawCircle(
                            color = Palette.Red.copy(alpha = 0.22f * ring),
                            radius = r * 0.24f,
                            center = Offset(center.x + r * 0.32f, center.y + r * 0.22f),
                            style = Stroke(width = r * 0.008f)
                        )
                    }

                    // 荧光模式的富集信号：病灶区叠加绿色辉光，与白光模式形成可辨差异
                    if (fluorescent) {
                        // 荧光富集辉光：周期约 6s 的极缓亮度游走，静态观感为主
                        val glow = 0.62f + 0.10f * sin(t * 1.05f)
                        val spot = Offset(center.x + r * 0.32f, center.y + r * 0.22f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF39FFB0).copy(alpha = 0.52f * glow),
                                    Color(0xFF39FFB0).copy(alpha = 0.10f * glow),
                                    Color.Transparent
                                ),
                                center = spot,
                                radius = r * 0.36f
                            ),
                            radius = r * 0.36f,
                            center = spot
                        )
                    }

                    // 器械：分离钳两根
                    val bite = sin(t * 0.8f) * r * 0.03f
                    drawLine(
                        color = shaftA,
                        start = Offset(center.x - r * 1.1f, center.y + r * 0.95f),
                        end = Offset(center.x - r * 0.12f + bite, center.y),
                        strokeWidth = r * 0.07f
                    )
                    drawLine(
                        color = shaftB,
                        start = Offset(center.x + r * 1.1f, center.y + r * 0.95f),
                        end = Offset(center.x + r * 0.12f - bite, center.y),
                        strokeWidth = r * 0.07f
                    )
                    drawCircle(
                        color = Color(0xFF5A6472),
                        radius = r * 0.035f,
                        center = Offset(center.x - r * 0.12f + bite, center.y)
                    )
                    drawCircle(
                        color = Color(0xFF5A6472),
                        radius = r * 0.035f,
                        center = Offset(center.x + r * 0.12f - bite, center.y)
                    )
                }

                // ---- 主刀注视点（Gaze Focus）：四角括号绿框标出"我正在看哪里" ----
                val focus = Offset(center.x + r * 0.32f, center.y + r * 0.22f)
                val boxR = r * 0.11f
                val gazeColor = if (SurgicalSession.teachingSync) {
                    Palette.Mint
                } else {
                    Palette.Mint.copy(alpha = 0.72f)
                }
                val corner = boxR * 0.62f
                fun bracket(sx: Float, sy: Float) {
                    drawLine(
                        color = gazeColor,
                        start = Offset(focus.x + sx * boxR, focus.y + sy * boxR),
                        end = Offset(focus.x + sx * boxR - sx * corner, focus.y + sy * boxR),
                        strokeWidth = 2f
                    )
                    drawLine(
                        color = gazeColor,
                        start = Offset(focus.x + sx * boxR, focus.y + sy * boxR),
                        end = Offset(focus.x + sx * boxR, focus.y + sy * boxR - sy * corner),
                        strokeWidth = 2f
                    )
                }
                bracket(-1f, -1f)
                bracket(1f, -1f)
                bracket(-1f, 1f)
                bracket(1f, 1f)
                drawCircle(
                    color = gazeColor,
                    radius = boxR * 0.16f,
                    center = focus,
                    style = Stroke(width = 1.2f)
                )

                // 视野边缘
                drawCircle(
                    color = Palette.Violet.copy(alpha = 0.45f),
                    radius = r,
                    center = center,
                    style = Stroke(width = 2.5f)
                )

                // 刻度环
                for (i in 0 until 24) {
                    val angle = i * (Math.PI / 12).toFloat()
                    val inner = r * 0.94f
                    val outer = r * (if (i % 6 == 0) 0.87f else 0.91f)
                    drawLine(
                        color = Palette.TextDim.copy(alpha = 0.5f),
                        start = Offset(center.x + cos(angle) * inner, center.y + sin(angle) * inner),
                        end = Offset(center.x + cos(angle) * outer, center.y + sin(angle) * outer),
                        strokeWidth = 1.5f
                    )
                }

                // 中心准星
                drawLine(
                    color = Palette.Mint.copy(alpha = 0.8f),
                    start = Offset(center.x - r * 0.06f, center.y),
                    end = Offset(center.x + r * 0.06f, center.y),
                    strokeWidth = 1.5f
                )
                drawLine(
                    color = Palette.Mint.copy(alpha = 0.8f),
                    start = Offset(center.x, center.y - r * 0.06f),
                    end = Offset(center.x, center.y + r * 0.06f),
                    strokeWidth = 1.5f
                )

                // 标注
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.MONOSPACE
                    textSize = 10.sp.toPx()
                    color = android.graphics.Color.argb(200, 233, 241, 251)
                }
                // 标注：坐标随缩放收敛到画布内，避免大倍率下文字越界不可读
                val labelX = (center.x - r).coerceIn(6f, (size.width - 96f).coerceAtLeast(6f))
                val topY = (center.y - r - 6f).coerceIn(12f, (size.height - 4f).coerceAtLeast(12f))
                val botY = (center.y + r + 16f).coerceIn(12f, (size.height - 4f).coerceAtLeast(12f))
                drawContext.canvas.nativeCanvas.apply {
                    drawText(String.format(java.util.Locale.US, "30° 镜  ·  %.2fx", zoom), labelX, topY, paint)
                    drawText(if (fluorescent) "近红外 800nm  ·  增益 42%" else "白光  ·  光斑 42%", labelX, botY, paint)
                }

                // 注视点标注：教学同步开启时改写成"已推送教学端"
                paint.color = android.graphics.Color.argb(230, 109, 240, 198)
                val gazeTx = (focus.x - boxR).coerceIn(6f, (size.width - 132f).coerceAtLeast(6f))
                val gazeTy = (focus.y - boxR - 6f).coerceIn(12f, (size.height - 4f).coerceAtLeast(12f))
                drawContext.canvas.nativeCanvas.drawText(
                    if (SurgicalSession.teachingSync) {
                        "GAZE ▸ 教学端 ${SurgicalSession.teachingPeers} 端"
                    } else {
                        "GAZE FOCUS"
                    },
                    gazeTx,
                    gazeTy,
                    paint
                )
                paint.color = android.graphics.Color.argb(200, 233, 241, 251)

                if (SurgicalSession.recording) {
                    paint.color = android.graphics.Color.argb(230, 255, 84, 104)
                    val recX = (center.x + r - 46.sp.toPx())
                        .coerceIn(6f, (size.width - 46.sp.toPx() - 6f).coerceAtLeast(6f))
                    drawContext.canvas.nativeCanvas.apply {
                        drawText("● REC", recX, topY, paint)
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel("视野控制", Palette.Violet)
            Spacer(Modifier.width(6.dp))
            ChipButton("放大 −", false) {
                SurgicalSession.scopeZoom = (zoom - 0.2f).coerceAtLeast(0.6f)
            }
            ChipButton("放大 ＋", false) {
                SurgicalSession.scopeZoom = (zoom + 0.2f).coerceAtMost(2.2f)
            }
            ChipButton("白光", !SurgicalSession.autoFluorescence, Palette.Violet) {
                SurgicalSession.autoFluorescence = !SurgicalSession.autoFluorescence
            }
            ChipButton("荧光", SurgicalSession.autoFluorescence, Palette.Mint) {
                SurgicalSession.autoFluorescence = !SurgicalSession.autoFluorescence
            }
            ChipButton("录制", SurgicalSession.recording, Palette.Red) {
                SurgicalSession.recording = !SurgicalSession.recording
            }
            ChipButton("同步教学端", SurgicalSession.teachingSync, Palette.Mint) {
                if (SurgicalSession.teachingSync) {
                    SurgicalSession.teachingSync = false
                    SurgicalSession.log("教学同步：已断开")
                } else {
                    VoiceCommandRouter.syncTeaching("面板")
                }
            }
            Spacer(Modifier.weight(1f))
            StatusDot(
                active = SurgicalSession.scopeExudate >= 0.62f,
                color = Palette.Red,
                label = String.format(java.util.Locale.US, "渗出 %.2f", SurgicalSession.scopeExudate)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (SurgicalSession.linkMode == LinkMode.OFF) "独立视图" else "联动 · 与影像 ROI 同步",
                color = Palette.TextDim,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
