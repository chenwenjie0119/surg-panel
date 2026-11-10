package com.cwj.surgpanel.ui.vitals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cwj.surgpanel.core.AlarmLevel
import com.cwj.surgpanel.core.LinkMode
import com.cwj.surgpanel.core.PerfGovernor
import com.cwj.surgpanel.core.SignalEngine
import com.cwj.surgpanel.core.SurgicalSession
import com.cwj.surgpanel.ui.common.PanelFrame
import com.cwj.surgpanel.ui.common.StatusDot
import com.cwj.surgpanel.ui.common.rememberVisualClock
import com.cwj.surgpanel.ui.common.rememberSmoothFloat
import com.cwj.surgpanel.ui.theme.Palette
import com.pico.spatial.ui.design.Text
import kotlin.math.roundToInt

/**
 * 生命体征面板：数值 + 三导波形（心电图 / 动脉压 / 血氧脉搏）。
 * 波形与影像面板共用同一条时间轴，形成"体征—影像"的双向联动。
 */
@Composable
fun VitalsPanel() {
    // 只拿时钟句柄，不在组合阶段读值：面板本身不再每帧重组
    val clock = rememberVisualClock()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // 标注画笔复用：三条泳道每帧不再各自新建 Paint，满血与降级状态下对象开销恒定。
    val labelPaint = androidx.compose.runtime.remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
            color = android.graphics.Color.argb(170, 185, 199, 216)
        }
    }
    val labelSizePx = with(density) { 10.sp.toPx() }
    val labelTopPadPx = with(density) { 14.sp.toPx() }
    val unitRightInsetPx = with(density) { 44.sp.toPx() }
    val alarm = SurgicalSession.alarm
    val alarmColor = when (alarm) {
        AlarmLevel.CRITICAL -> Palette.Red
        AlarmLevel.WATCH -> Palette.Amber
        AlarmLevel.NOTICE -> Palette.Cyan
        AlarmLevel.NONE -> Palette.Mint
    }

    // 数值走插值通道：真值由时钟推进，显示层只做平滑，不改变任何判定结果。
    val hrSmooth = rememberSmoothFloat(SurgicalSession.heartRate.toFloat(), 0.30f)
    val spo2Smooth = rememberSmoothFloat(SurgicalSession.spo2.toFloat(), 0.30f)
    val sysSmooth = rememberSmoothFloat(SurgicalSession.systolic.toFloat(), 0.28f)
    val diaSmooth = rememberSmoothFloat(SurgicalSession.diastolic.toFloat(), 0.28f)
    val etSmooth = rememberSmoothFloat(SurgicalSession.etco2.toFloat(), 0.28f)

    PanelFrame(
        title = "生命体征监护",
        tag = "VITALS",
        accent = Palette.Mint,
        trailing = {
            StatusDot(active = alarm != AlarmLevel.NONE, color = alarmColor, label = alarm.name)
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (SurgicalSession.linkMode == LinkMode.OFF) "OFFLINE SYNC" else "SYNCED",
                color = Palette.TextDim,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            VitalCard("HR", "${hrSmooth.roundToInt()}", "bpm", Palette.Mint, Modifier.weight(1f))
            VitalCard("SpO₂", "${spo2Smooth.roundToInt()}", "%", Palette.Cyan, Modifier.weight(1f))
            VitalCard(
                "NIBP",
                "${sysSmooth.roundToInt()}/${diaSmooth.roundToInt()}",
                "mmHg",
                Palette.Amber,
                Modifier.weight(1f)
            )
            VitalCard("EtCO₂", "${etSmooth.roundToInt()}", "mmHg", Palette.Violet, Modifier.weight(1f))
        }

        Spacer(Modifier.height(10.dp))

        val remain = SurgicalSession.blockadeRemain()
        val blockColor = when {
            !SurgicalSession.blockadeRunning -> Palette.TextDim
            remain <= 60 -> Palette.Red
            remain <= 180 -> Palette.Amber
            else -> Palette.Mint
        }

        // 阻断进行中把计时提为整行大字：流程项需要一臂之外也能一眼读出
        if (SurgicalSession.blockadeRunning) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(blockColor.copy(alpha = 0.14f))
                    .border(1.dp, blockColor.copy(alpha = 0.60f), RoundedCornerShape(7.dp))
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "肝门阻断", color = blockColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                    Text(text = "上限 15:00", color = Palette.TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = SurgicalSession.blockadeLabel(),
                        color = blockColor,
                        fontSize = 26.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---- 关键流程条：阻断倒计时 / 出血量 / 纱布清点 / 交叉验证进度 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FlowChip(
                label = if (SurgicalSession.blockadeRunning) "阻断剩余" else "阻断未开始",
                value = SurgicalSession.blockadeLabel(),
                accent = blockColor,
                active = SurgicalSession.blockadeRunning,
                modifier = Modifier.weight(1.05f)
            )
            FlowChip(
                label = "估算出血 EBL",
                value = SurgicalSession.eblLabel(),
                accent = if (SurgicalSession.eblMl >= 400) Palette.Red else Palette.Amber,
                active = SurgicalSession.eblMl > 0,
                modifier = Modifier.weight(1f)
            )
            FlowChip(
                label = "纱布 台上/清点",
                value = "${SurgicalSession.spongeOnField}/${SurgicalSession.spongeCounted}",
                accent = if (SurgicalSession.countMatched()) Palette.Mint else Palette.Red,
                active = !SurgicalSession.countMatched(),
                modifier = Modifier.weight(1f)
            )
            FlowChip(
                label = "交叉验证",
                value = "${SurgicalSession.fusionHits}/3",
                accent = when (SurgicalSession.fusionHits) {
                    3 -> Palette.Red
                    0 -> Palette.TextDim
                    else -> Palette.Cyan
                },
                active = SurgicalSession.fusionHits > 0,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "联合判定：${SurgicalSession.fusionDetail}　·　三项同现才红闪急报",
            color = Palette.TextDim,
            fontSize = 10.sp
        )

        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Palette.Panel)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // 时钟值在绘制阶段读取：只触发重绘，不触发组合阶段重组
                val t = clock.value
                val w = size.width
                val laneH = size.height / 3f
                val hr = hrSmooth.roundToInt()
                // 视觉得分预算：FULL 每 2px 一点，降级后步长变粗，只影响曲线密度。
                val stride = PerfGovernor.level.waveStride

                fun drawLane(
                    lane: Int,
                    cycles: Float,
                    color: Color,
                    title: String,
                    unit: String,
                    sampler: (Float) -> Float
                ) {
                    val top = laneH * lane
                    val mid = top + laneH / 2f
                    val path = Path()
                    var x = 0f
                    while (x <= w + 2f) {
                        val phase = ((t * 1.35f - (1f - x / w) * cycles) % 1f + 1f) % 1f
                        val v = sampler(phase)
                        val y = mid - v * laneH * 0.40f
                        if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                        x += stride
                    }
                    drawLine(
                        color = Palette.Stroke,
                        start = Offset(0f, mid),
                        end = Offset(w, mid),
                        strokeWidth = 1f
                    )
                    drawPath(path = path, color = color, style = Stroke(width = 2.4f))

                    labelPaint.textSize = labelSizePx
                    drawContext.canvas.nativeCanvas.drawText(title, 6f, top + labelTopPadPx, labelPaint)
                    drawContext.canvas.nativeCanvas.drawText(unit, w - unitRightInsetPx, top + labelTopPadPx, labelPaint)
                }

                drawLane(0, 3.2f, Palette.Mint, "ECG II", "25 mm/s") { p -> SignalEngine.ecgPhase(p, hr) * 0.9f }
                drawLane(1, 2.6f, Palette.Amber, "ART", "mmHg") { p -> SignalEngine.abpPhase(p, hr) }
                drawLane(2, 2.9f, Palette.Cyan, "SpO₂ Pleth", "%") { p -> SignalEngine.plethPhase(p, hr) }

                // 扫掠光标
                val cursorX = w * (0.999f)
                drawLine(
                    color = Color.White.copy(alpha = 0.10f),
                    start = Offset(cursorX, 0f),
                    end = Offset(cursorX, size.height),
                    strokeWidth = 1.6f
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 告警 / 状态条
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (alarm == AlarmLevel.NONE) Palette.PanelSoft else alarmColor.copy(alpha = 0.18f)),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (alarm) {
                        AlarmLevel.NONE -> "血流动力学稳定 · 无告警"
                        AlarmLevel.NOTICE -> "轻提示：${SurgicalSession.alarmText}（未达联合判定）"
                        AlarmLevel.WATCH -> "关注：${SurgicalSession.alarmText}"
                        AlarmLevel.CRITICAL -> "危急：${SurgicalSession.alarmText}"
                    },
                    color = if (alarm == AlarmLevel.NONE) Palette.TextBody else alarmColor,
                    fontSize = 11.sp,
                    fontWeight = if (alarm == AlarmLevel.NONE) FontWeight.Normal else FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${SurgicalSession.phase.label} · ${SurgicalSession.clock()}",
                    color = Palette.TextDim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/** 流程条单元：一个大号数值 + 一行说明，颜色即状态（正常 / 临界 / 超限）。 */
@Composable
private fun FlowChip(
    label: String,
    value: String,
    accent: Color,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Palette.PanelSoft)
            .border(
                1.dp,
                if (active) accent.copy(alpha = 0.55f) else Palette.Stroke,
                RoundedCornerShape(7.dp)
            )
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = Palette.TextDim,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            color = accent,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun VitalCard(
    label: String,
    value: String,
    unit: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Panel)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(text = label, color = Palette.TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = accent,
                fontSize = 26.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(4.dp))
            Text(text = unit, color = Palette.TextDim, fontSize = 9.sp)
        }
    }
}
