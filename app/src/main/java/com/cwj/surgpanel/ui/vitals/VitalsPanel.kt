package com.cwj.surgpanel.ui.vitals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import com.cwj.surgpanel.core.SignalEngine
import com.cwj.surgpanel.core.SurgicalSession
import com.cwj.surgpanel.ui.common.PanelFrame
import com.cwj.surgpanel.ui.common.StatusDot
import com.cwj.surgpanel.ui.common.rememberAnimatedSeconds
import com.cwj.surgpanel.ui.theme.Palette
import com.pico.spatial.ui.design.Text
import kotlin.math.min

/**
 * 生命体征面板：数值 + 三导波形（心电图 / 动脉压 / 血氧脉搏）。
 * 波形与影像面板共用同一条时间轴，形成"体征—影像"的双向联动。
 */
@Composable
fun VitalsPanel() {
    val t = rememberAnimatedSeconds()
    val alarm = SurgicalSession.alarm
    val alarmColor = when (alarm) {
        AlarmLevel.CRITICAL -> Palette.Red
        AlarmLevel.WATCH -> Palette.Amber
        AlarmLevel.NONE -> Palette.Mint
    }

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
            VitalCard("HR", "${SurgicalSession.heartRate}", "bpm", Palette.Mint, Modifier.weight(1f))
            VitalCard("SpO₂", "${SurgicalSession.spo2}", "%", Palette.Cyan, Modifier.weight(1f))
            VitalCard(
                "NIBP",
                "${SurgicalSession.systolic}/${SurgicalSession.diastolic}",
                "mmHg",
                Palette.Amber,
                Modifier.weight(1f)
            )
            VitalCard("EtCO₂", "${SurgicalSession.etco2}", "mmHg", Palette.Violet, Modifier.weight(1f))
        }

        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Palette.Panel)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val laneH = size.height / 3f
                val hr = SurgicalSession.heartRate

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
                        x += 2f
                    }
                    drawLine(
                        color = Palette.Stroke,
                        start = Offset(0f, mid),
                        end = Offset(w, mid),
                        strokeWidth = 1f
                    )
                    drawPath(path = path, color = color, style = Stroke(width = 2.4f))

                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.MONOSPACE
                        textSize = 10.sp.toPx()
                        this.color = android.graphics.Color.argb(170, 185, 199, 216)
                    }
                    drawContext.canvas.nativeCanvas.drawText(title, 6f, top + 14.sp.toPx(), paint)
                    drawContext.canvas.nativeCanvas.drawText(unit, w - 44.sp.toPx(), top + 14.sp.toPx(), paint)
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
                    strokeWidth = min(2f, 2f)
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
