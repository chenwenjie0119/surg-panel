package com.cwj.surgpanel.ui.focus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cwj.surgpanel.FOCUS_ID
import com.cwj.surgpanel.core.AiAction
import com.cwj.surgpanel.core.AiController
import com.cwj.surgpanel.core.AiFinding
import com.cwj.surgpanel.core.AlarmLevel
import com.cwj.surgpanel.core.AppMode
import com.cwj.surgpanel.core.FocusStage
import com.cwj.surgpanel.core.IncidentCase
import com.cwj.surgpanel.core.Severity
import com.cwj.surgpanel.core.SurgicalSession
import com.cwj.surgpanel.platform.GazePinchController
import com.cwj.surgpanel.platform.SurgicalAudio
import com.cwj.surgpanel.ui.common.ChipButton
import com.cwj.surgpanel.ui.common.KeyValueRow
import com.cwj.surgpanel.ui.common.MiniRadar
import com.cwj.surgpanel.ui.common.NarrationBlock
import com.cwj.surgpanel.ui.common.PanelFrame
import com.cwj.surgpanel.ui.common.SectionLabel
import com.cwj.surgpanel.ui.common.StatusDot
import com.cwj.surgpanel.ui.common.rememberVisualClock
import com.cwj.surgpanel.ui.theme.Palette
import com.pico.spatial.ui.design.Text
import kotlin.math.min
import kotlin.math.sin

/**
 * 智能异常处置聚焦窗。
 *
 * 出现异常时该窗口自动弹出并前置：先定位病灶，再把患者相应部位虚拟放大，
 * 随后自动剖切展示内部结构，最后给出证据与处置序列。
 * 演示模式下由前方测试按钮一键走到同一条链路。
 */
@Composable
fun FocusPanel() {
    // 只持句柄不读值：放大镜视图以外的区域不随帧重组
    val clock = rememberVisualClock()
    val stage = SurgicalSession.focusStage
    val anim = SurgicalSession.focusAnim
    val incident = SurgicalSession.incident
    val running = SurgicalSession.aiRunning
    val gazeHot = GazePinchController.hotPanelId == FOCUS_ID
    val accent = when {
        incident == null -> Palette.Violet
        SurgicalSession.alarm == AlarmLevel.CRITICAL -> Palette.Red
        else -> Palette.Amber
    }

    DisposableEffect(Unit) {
        GazePinchController.setConfirmHandler(FOCUS_ID) {
            AiController.confirmAdvice("捏合确认")
            SurgicalAudio.confirm()
        }
        onDispose { GazePinchController.setConfirmHandler(FOCUS_ID, null) }
    }

    PanelFrame(
        title = "智能异常处置 · 病灶聚焦",
        tag = "FOCUS",
        accent = accent,
        gazeHot = gazeHot,
        trailing = {
            StatusDot(active = running, color = accent, label = if (running) "ANALYZING" else stage.label)
            Spacer(Modifier.width(10.dp))
            Text(
                text = SurgicalSession.appMode.badge,
                color = Palette.TextDim,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    ) {
        StageBar(stage, anim)
        Spacer(Modifier.height(8.dp))
        GazeStrip(gazeHot)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().weight(1f)) {
            FocusViewport(stage, anim, clock, incident, Modifier.weight(1.12f).fillMaxHeight())
            Spacer(Modifier.width(14.dp))
            AnalysisColumn(incident, Modifier.weight(1f).fillMaxHeight())
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ChipButton(
                label = if (SurgicalSession.appMode == AppMode.DEMO) "一键演示 · 术中意外" else "手动触发分析",
                active = true,
                accent = Palette.Red
            ) {
                AiController.triggerDemo()
            }
            Spacer(Modifier.width(10.dp))
            ChipButton(
                label = if (SurgicalSession.adviceConfirmed) "处置已确认" else "捏合确认处置",
                active = SurgicalSession.adviceConfirmed,
                accent = Palette.Mint
            ) {
                AiController.confirmAdvice("点击确认")
                SurgicalAudio.confirm()
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = when {
                    SurgicalSession.adviceConfirmed -> "处置序列已下发，事件保持展开直至收起"
                    SurgicalSession.appMode == AppMode.DEMO -> "单次演示约 16 s 后自动回稳，可重复触发"
                    else -> "产品模式下监护数据越限将自动触发本链路"
                },
                color = Palette.TextDim,
                fontSize = 11.sp
            )
            Spacer(Modifier.weight(1f))
            // 视线左下角常驻的极简全局雷达：异常态下医生主要看本窗，雷达放这里随时可见。
            MiniRadar(Modifier.width(92.dp), diameter = 78.dp)
            Spacer(Modifier.width(10.dp))
            ChipButton("收起事件", false) { AiController.onResolved() }
        }
    }
}

/**
 * 眼动交互条：呈现视线驻留进度与捏合状态。
 *
 * 驻留进度由 [GazePinchController] 在独立协程里推进，本组件只做只读渲染，
 * 不阻断也不拖慢任何主链路；即使整条降级也不影响处置流程本身。
 */
@Composable
private fun GazeStrip(hot: Boolean) {
    val dwell = GazePinchController.dwell
    val pinch = GazePinchController.pinchArmed
    val ready = GazePinchController.trackingReady
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(active = hot, color = Palette.Cyan, label = if (hot) "EYE LOCK" else "EYE")
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .width(64.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Palette.Stroke)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(dwell.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(Palette.Cyan)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = when {
                !ready -> "眼动通道初始化中"
                hot -> "视线驻留 ${(dwell * 100).toInt()}%"
                else -> "注视本窗 0.8 s 锁定"
            },
            color = if (hot) Palette.Cyan else Palette.TextDim,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.width(12.dp))
        StatusDot(
            active = pinch,
            color = Palette.Mint,
            label = if (pinch) "PINCH ARMED" else "PINCH"
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (SurgicalSession.adviceConfirmed) {
                "处置序列已确认"
            } else {
                "锁定后捏合手指确认处置"
            },
            color = if (SurgicalSession.adviceConfirmed) Palette.Mint else Palette.TextDim,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

/** 阶段推进条：定位 → 放大 → 剖切 → 处置。 */
@Composable
private fun StageBar(stage: FocusStage, anim: Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FocusStage.values().filter { it != FocusStage.IDLE }.forEach { item ->
            val done = stage.step > item.step
            val active = stage.step == item.step
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(if (done) Palette.Mint else Palette.Stroke)
                ) {
                    if (active) {
                        Box(
                            Modifier
                                .fillMaxWidth(anim.coerceIn(0f, 1f))
                                .height(3.dp)
                                .background(Palette.Red)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.label,
                    color = when {
                        done -> Palette.Mint
                        active -> Palette.Red
                        else -> Palette.TextDim
                    },
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/** 解剖视图：定位扫描 → 放大 → 剖切。 */
@Composable
private fun FocusViewport(
    stage: FocusStage,
    anim: Float,
    clock: androidx.compose.runtime.State<Float>,
    incident: IncidentCase?,
    modifier: Modifier
) {
    Box(
        modifier
            .background(Palette.Panel)
            .border(1.dp, Palette.Stroke)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // 时钟在绘制阶段读取，视图区每帧只重绘
            drawFocusScene(stage, anim, clock.value, incident != null || stage != FocusStage.IDLE)
        }

        Box(Modifier.align(Alignment.TopStart).padding(10.dp)) {
            Column {
                Text(
                    text = "VIRTUAL ENLARGEMENT",
                    color = Palette.TextDim,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = incident?.region ?: "等待异常事件",
                    color = if (incident != null) Palette.TextPrimary else Palette.TextDim,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Box(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Text(
                text = when (stage) {
                    FocusStage.SECTION, FocusStage.ADVISE -> "剖切面 · 内部结构已展开"
                    FocusStage.ENLARGE -> "病灶区放大 ${(100 + 44 * anim).toInt()}%"
                    FocusStage.LOCALIZE -> "断面扫描定位中"
                    FocusStage.IDLE -> "链路待命"
                },
                color = if (stage == FocusStage.IDLE) Palette.TextDim else Palette.Amber,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/** 右侧结论栏：证据 + 处置序列。 */
@Composable
private fun AnalysisColumn(incident: IncidentCase?, modifier: Modifier) {
    Column(
        modifier
            .background(Palette.Panel)
            .border(1.dp, Palette.Stroke)
            .padding(12.dp)
    ) {
        SectionLabel("智能分析", Palette.Amber)
        Spacer(Modifier.height(6.dp))
        NarrationBlock(accent = Palette.Amber, visibleLines = 2, fixedHeight = 60)
        Spacer(Modifier.height(8.dp))
        if (incident == null) {
            Text(
                text = "暂无异常事件",
                color = Palette.TextBody,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = if (SurgicalSession.appMode == AppMode.DEMO) {
                    "点击下方「一键演示」，自动完成定位、放大、剖切与建议输出。"
                } else {
                    "接入监护仪后，体征越限将自动触发定位、放大、剖切与建议输出。"
                },
                color = Palette.TextDim,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(10.dp))
            KeyValueRow("运行模式", SurgicalSession.appMode.label)
            KeyValueRow("推理引擎", SurgicalSession.aiEngine)
            KeyValueRow("影像断面", "第 ${SurgicalSession.sliceIndex + 1} 层")
            return@Column
        }

        KeyValueRow("事件编码", incident.code, Palette.Red)
        KeyValueRow("定位", incident.region)
        KeyValueRow("判断", incident.cause)
        KeyValueRow("置信度", "${incident.confidence}%", Palette.Mint)
        KeyValueRow("引擎 / 耗时", "${SurgicalSession.aiEngine} · ${SurgicalSession.aiLatencyMs} ms")
        Spacer(Modifier.height(8.dp))
        SectionLabel("证据", Palette.Cyan)
        Spacer(Modifier.height(4.dp))
        incident.findings.forEach { finding -> FindingRow(finding) }
        Spacer(Modifier.height(8.dp))
        SectionLabel("处置建议", Palette.Mint)
        Spacer(Modifier.height(4.dp))
        incident.actions.forEach { action -> ActionRow(action) }
    }
}

@Composable
private fun FindingRow(finding: AiFinding) {
    val color = when (finding.severity) {
        Severity.CRITICAL -> Palette.Red
        Severity.WARN -> Palette.Amber
        Severity.INFO -> Palette.Cyan
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = finding.title,
                color = Palette.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(text = finding.detail, color = Palette.TextDim, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ActionRow(action: AiAction) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Box(
            Modifier
                .size(15.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Palette.Mint.copy(alpha = 0.16f))
                .border(1.dp, Palette.Mint.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = action.order.toString(),
                    color = Palette.Mint,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = action.action,
                    color = Palette.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                if (action.eta.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = action.eta,
                        color = Palette.Mint,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Text(text = action.detail, color = Palette.TextDim, fontSize = 10.sp)
        }
    }
}

/**
 * 解剖画布：肝脏轮廓、血供分支、出血点，随阶段推进做放大与剖切。
 * 全部使用相对坐标，窗口缩放时保持构图。
 */
private fun DrawScope.drawFocusScene(stage: FocusStage, anim: Float, t: Float, hasCase: Boolean) {
    val w = size.width
    val h = size.height
    val cx = w * 0.5f
    val cy = h * 0.55f

    val scale = when (stage) {
        FocusStage.IDLE, FocusStage.LOCALIZE -> 0.62f
        FocusStage.ENLARGE -> 0.62f + 0.46f * anim
        FocusStage.SECTION, FocusStage.ADVISE -> 1.08f
    }
    val r = min(w, h) * 0.34f * scale

    // 网格底纹
    val grid = Palette.Stroke.copy(alpha = 0.42f)
    var gx = 0f
    while (gx < w) {
        drawLine(grid, Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f)
        gx += 30f
    }
    var gy = 0f
    while (gy < h) {
        drawLine(grid, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
        gy += 30f
    }

    // 肝右叶
    drawOval(
        color = Palette.Tissue.copy(alpha = 0.24f),
        topLeft = Offset(cx - r, cy - r * 0.82f),
        size = Size(r * 2f, r * 1.64f)
    )
    drawOval(
        color = Palette.Tissue.copy(alpha = 0.55f),
        topLeft = Offset(cx - r, cy - r * 0.82f),
        size = Size(r * 2f, r * 1.64f),
        style = Stroke(width = 2f)
    )
    // 肝左叶
    drawOval(
        color = Palette.Tissue.copy(alpha = 0.16f),
        topLeft = Offset(cx - r * 1.55f, cy - r * 0.44f),
        size = Size(r * 0.95f, r * 0.88f)
    )
    drawOval(
        color = Palette.Tissue.copy(alpha = 0.42f),
        topLeft = Offset(cx - r * 1.55f, cy - r * 0.44f),
        size = Size(r * 0.95f, r * 0.88f),
        style = Stroke(width = 1.5f)
    )

    // 血供分支
    val vessel = Palette.Violet.copy(alpha = 0.55f)
    drawLine(
        vessel,
        Offset(cx - r * 0.95f, cy - r * 0.08f),
        Offset(cx + r * 0.56f, cy - r * 0.3f),
        strokeWidth = 2.5f
    )
    drawLine(
        vessel,
        Offset(cx + r * 0.08f, cy - r * 0.18f),
        Offset(cx + r * 0.62f, cy + r * 0.44f),
        strokeWidth = 2f
    )

    // 病灶与出血点：环形与光晕的尺度变化收敛到 3% 以内，读作稳定标注而非频闪
    val lesion = Offset(cx + r * 0.32f, cy - r * 0.16f)
    val pulse = 0.97f + 0.03f * sin(t * 1.4f)
    if (hasCase) {
        drawCircle(Palette.Red.copy(alpha = 0.2f), radius = r * 0.26f * pulse, center = lesion)
        drawCircle(Palette.Red, radius = r * 0.1f, center = lesion)
        drawCircle(
            Palette.Red.copy(alpha = 0.85f),
            radius = r * 0.2f * pulse,
            center = lesion,
            style = Stroke(width = 2f)
        )
    }

    // 定位扫描
    if (stage == FocusStage.LOCALIZE) {
        val top = cy - r * 0.92f
        val scanY = top + (r * 1.84f) * anim
        drawRect(
            Palette.Cyan.copy(alpha = 0.07f),
            topLeft = Offset(cx - r * 1.35f, top),
            size = Size(r * 2.7f, (scanY - top).coerceAtLeast(0f))
        )
        drawLine(
            Palette.Cyan.copy(alpha = 0.9f),
            Offset(cx - r * 1.35f, scanY),
            Offset(cx + r * 1.35f, scanY),
            strokeWidth = 2f
        )
    }

    // 放大取景框
    if (stage != FocusStage.IDLE && stage != FocusStage.LOCALIZE) {
        val bw = r * 0.78f
        val bh = r * 0.8f
        drawRect(
            Palette.Amber.copy(alpha = 0.85f),
            topLeft = Offset(lesion.x - bw, lesion.y - bh),
            size = Size(bw * 2f, bh * 2f),
            style = Stroke(width = 2f)
        )
        val arm = r * 0.16f
        drawLine(Palette.Amber, Offset(lesion.x - bw, lesion.y - bh), Offset(lesion.x - bw + arm, lesion.y - bh), strokeWidth = 3f)
        drawLine(Palette.Amber, Offset(lesion.x + bw, lesion.y + bh), Offset(lesion.x + bw - arm, lesion.y + bh), strokeWidth = 3f)
    }

    // 自动剖切：切口自左向右推进，切开区域展示内部
    if (stage == FocusStage.SECTION || stage == FocusStage.ADVISE) {
        val progress = if (stage == FocusStage.SECTION) anim.coerceIn(0f, 1f) else 1f
        val leftEdge = lesion.x - r * 0.74f
        val cutX = leftEdge + (r * 1.48f) * progress
        val topY = lesion.y - r * 0.72f
        val heightPx = r * 1.44f

        drawRect(
            Palette.Void.copy(alpha = 0.88f),
            topLeft = Offset(leftEdge, topY),
            size = Size((cutX - leftEdge).coerceAtLeast(0f), heightPx)
        )
        // 内部血管断面
        for (i in 0..2) {
            drawCircle(
                Palette.Red.copy(alpha = 0.7f),
                radius = r * 0.055f,
                center = Offset(leftEdge + r * 0.28f + i * r * 0.3f, topY + r * 0.3f + i * r * 0.28f),
                style = Stroke(width = 2f)
            )
        }
        // 血肿
        drawOval(
            Palette.Red.copy(alpha = 0.5f),
            topLeft = Offset(leftEdge + r * 0.22f, lesion.y + r * 0.06f),
            size = Size(r * 0.52f, r * 0.3f)
        )
        // 切口线
        drawLine(Palette.TextPrimary.copy(alpha = 0.85f), Offset(cutX, topY), Offset(cutX, topY + heightPx), strokeWidth = 2f)
        drawLine(Palette.Red, Offset(cutX, topY), Offset(cutX, topY + heightPx), strokeWidth = 5f)
    }

    // 处置标记：夹闭点
    if (stage == FocusStage.ADVISE) {
        drawRect(
            Palette.Mint,
            topLeft = Offset(lesion.x - r * 0.2f, lesion.y - r * 0.24f),
            size = Size(r * 0.18f, r * 0.07f)
        )
        drawLine(
            Palette.Mint.copy(alpha = 0.8f),
            Offset(lesion.x - r * 0.11f, lesion.y - r * 0.2f),
            Offset(lesion.x + r * 0.32f, lesion.y - r * 0.56f),
            strokeWidth = 1.5f
        )
    }
}
