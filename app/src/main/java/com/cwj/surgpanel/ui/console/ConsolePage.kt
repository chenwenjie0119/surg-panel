package com.cwj.surgpanel.ui.console

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cwj.surgpanel.IMAGING_ID
import com.cwj.surgpanel.SCOPE_ID
import com.cwj.surgpanel.VITALS_ID
import com.cwj.surgpanel.core.AlarmLevel
import com.cwj.surgpanel.core.LinkMode
import com.cwj.surgpanel.core.Modality
import com.cwj.surgpanel.core.SurgicalSession
import com.cwj.surgpanel.ui.common.ChipButton
import com.cwj.surgpanel.ui.common.KeyValueRow
import com.cwj.surgpanel.ui.common.PanelFrame
import com.cwj.surgpanel.ui.common.SectionLabel
import com.cwj.surgpanel.ui.common.StatusDot
import com.cwj.surgpanel.ui.theme.Palette
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator

/** 三个浮动面板统一使用的 tag：同一 id + 同一 tag 走复用模式，避免重复开窗。 */
private const val PANEL_TAG = "surg-main"

/**
 * 手术指挥台：控制三个悬浮面板的开合、联动策略与影像参数，
 * 同时汇总患者信息、手术阶段与告警事件。
 */
@Composable
fun ConsolePage() {
    val navigator = LocalSpatialNavigator.current
    var opened by remember { mutableStateOf(setOf(IMAGING_ID, VITALS_ID, SCOPE_ID)) }

    // 首次进入时展开三个面板：同一 id + 同一 tag 走复用模式，重复调用只会前置已有窗口
    LaunchedEffect(Unit) {
        opened.forEach { id ->
            runCatching { navigator.openWindowContainer(id = id, tag = PANEL_TAG) }
        }
    }

    fun switchWindow(id: String) {
        val isOpen = id in opened
        runCatching {
            if (isOpen) {
                navigator.closeWindowContainer(id = id, tag = PANEL_TAG)
            } else {
                navigator.openWindowContainer(id = id, tag = PANEL_TAG)
            }
        }
        opened = if (isOpen) opened - id else opened + id
    }

    PanelFrame(
        title = "手术指挥台 · OR-03",
        tag = SurgicalSession.CASE_NO,
        accent = Palette.Cyan,
        trailing = {
            val alarmColor = when (SurgicalSession.alarm) {
                AlarmLevel.CRITICAL -> Palette.Red
                AlarmLevel.WATCH -> Palette.Amber
                AlarmLevel.NONE -> Palette.Mint
            }
            StatusDot(active = true, color = alarmColor, label = "LIVE")
            Spacer(Modifier.width(12.dp))
            Text(
                text = SurgicalSession.clock(),
                color = Palette.TextPrimary,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }
    ) {
        Row(Modifier.fillMaxSize()) {
            // ---------- 左栏：窗口与联动控制 ----------
            Column(Modifier.weight(1.15f)) {
                SectionLabel("空间窗口 · 可拖拽 / 缩放 / 吸附")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipButton("影像窗", IMAGING_ID in opened, Palette.Cyan) { switchWindow(IMAGING_ID) }
                    ChipButton("体征窗", VITALS_ID in opened, Palette.Mint) { switchWindow(VITALS_ID) }
                    ChipButton("内窥镜", SCOPE_ID in opened, Palette.Violet) { switchWindow(SCOPE_ID) }
                    ChipButton(
                        label = "全部展开",
                        active = false,
                        accent = Palette.TextBody
                    ) {
                        listOf(IMAGING_ID, VITALS_ID, SCOPE_ID)
                            .filter { it !in opened }
                            .forEach { switchWindow(it) }
                    }
                }

                Spacer(Modifier.height(14.dp))
                SectionLabel("联动策略")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinkMode.values().forEach { mode ->
                        ChipButton(mode.label, SurgicalSession.linkMode == mode) {
                            SurgicalSession.linkMode = mode
                            SurgicalSession.log("联动切换：${mode.label}")
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    text = SurgicalSession.linkMode.hint,
                    color = Palette.TextDim,
                    fontSize = 11.sp
                )

                Spacer(Modifier.height(14.dp))
                SectionLabel("影像窗共享参数")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Modality.values().forEach { m ->
                        ChipButton(m.label, SurgicalSession.modality == m, Palette.Cyan) {
                            SurgicalSession.modality = m
                        }
                    }
                    ChipButton("切层 -5", false, Palette.TextBody) {
                        SurgicalSession.sliceIndex = (SurgicalSession.sliceIndex - 5).coerceAtLeast(0)
                    }
                    ChipButton("切层 +5", false, Palette.TextBody) {
                        SurgicalSession.sliceIndex = (SurgicalSession.sliceIndex + 5).coerceAtMost(95)
                    }
                    ChipButton("ROI 锁定", SurgicalSession.roiLocked, Palette.Amber) {
                        SurgicalSession.roiLocked = !SurgicalSession.roiLocked
                    }
                }

                Spacer(Modifier.height(14.dp))
                SectionLabel("事件演示")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipButton("诱发术野渗血", SurgicalSession.manualBleed, Palette.Red) {
                        SurgicalSession.manualBleed = !SurgicalSession.manualBleed
                        SurgicalSession.log(
                            if (SurgicalSession.manualBleed) "演示注入：术野渗血事件" else "演示结束：渗血已控制",
                            critical = SurgicalSession.manualBleed
                        )
                    }
                    ChipButton("时间轴复位", false, Palette.TextBody) {
                        SurgicalSession.elapsedSec = 0
                        SurgicalSession.manualBleed = false
                        SurgicalSession.log("演示复位：时间轴归零")
                    }
                }

                Spacer(Modifier.height(14.dp))
                SectionLabel("手术阶段")
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${SurgicalSession.phase.label}　·　已进行 ${SurgicalSession.clock()}",
                    color = Palette.TextPrimary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(6.dp))
                val progress = (SurgicalSession.elapsedSec / 300f).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Palette.PanelSoft)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Palette.Cyan)
                    )
                }
            }

            Spacer(Modifier.width(18.dp))

            // ---------- 右栏：患者与事件 ----------
            Column(Modifier.weight(1f)) {
                SectionLabel("患者与术式", Palette.Mint)
                Spacer(Modifier.height(8.dp))
                KeyValueRow("患者", SurgicalSession.PATIENT)
                KeyValueRow("术式", SurgicalSession.PROCEDURE)
                KeyValueRow("入路", "腹腔镜 4 孔")
                KeyValueRow("麻醉", "全麻 · 已插管")
                KeyValueRow(
                    "当前切片",
                    "${SurgicalSession.modality.label} #${SurgicalSession.sliceIndex + 1}",
                    Palette.Cyan
                )

                Spacer(Modifier.height(14.dp))
                SectionLabel("实时事件流", Palette.Amber)
                Spacer(Modifier.height(8.dp))
                Column {
                    SurgicalSession.events.take(6).forEach { event ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = event.stamp(),
                                color = Palette.TextDim,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = event.text,
                                color = if (event.critical) Palette.Red else Palette.TextBody,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
