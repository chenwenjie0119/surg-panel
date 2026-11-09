package com.cwj.surgpanel.ui.console

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cwj.surgpanel.FOCUS_ID
import com.cwj.surgpanel.IMAGING_ID
import com.cwj.surgpanel.RECORD_ID
import com.cwj.surgpanel.SCOPE_ID
import com.cwj.surgpanel.VITALS_ID
import com.cwj.surgpanel.core.AiController
import com.cwj.surgpanel.core.AlarmLevel
import com.cwj.surgpanel.core.AppMode
import com.cwj.surgpanel.core.LinkMode
import com.cwj.surgpanel.core.Modality
import com.cwj.surgpanel.core.PerfGovernor
import com.cwj.surgpanel.core.SurgicalSession
import com.cwj.surgpanel.core.VoiceCommandRouter
import com.cwj.surgpanel.core.VoiceIntent
import com.cwj.surgpanel.core.VoiceState
import com.cwj.surgpanel.data.AiGateway
import com.cwj.surgpanel.data.InstrumentRegistry
import com.cwj.surgpanel.platform.GazePinchController
import com.cwj.surgpanel.platform.SurgicalAudio
import com.cwj.surgpanel.platform.VoiceInput
import com.cwj.surgpanel.ui.common.ChipButton
import com.cwj.surgpanel.ui.common.KeyValueRow
import com.cwj.surgpanel.ui.common.MicButton
import com.cwj.surgpanel.ui.common.CoordStack
import com.cwj.surgpanel.ui.common.MiniRadar
import com.cwj.surgpanel.ui.common.NarrationBlock
import com.cwj.surgpanel.ui.common.PanelFrame
import com.cwj.surgpanel.ui.common.PrimaryButton
import com.cwj.surgpanel.ui.common.SectionLabel
import com.cwj.surgpanel.ui.common.StatusDot
import com.cwj.surgpanel.ui.theme.Palette
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator

/** 三个浮动面板统一使用的 tag：同一 id + 同一 tag 走复用模式，避免重复开窗。 */
private const val PANEL_TAG = "surg-main"

/** 麦克风运行时权限请求码。 */
private const val MIC_REQUEST = 4101

/**
 * 手术指挥台：控制三个悬浮面板的开合、联动策略与影像参数，
 * 同时汇总患者信息、手术阶段与告警事件。
 */
@Composable
fun ConsolePage() {
    val navigator = LocalSpatialNavigator.current
    val context = LocalContext.current
    var opened by remember { mutableStateOf(setOf(IMAGING_ID, VITALS_ID, SCOPE_ID)) }

    // 首次进入时展开三个面板：同一 id + 同一 tag 走复用模式，重复调用只会前置已有窗口
    LaunchedEffect(Unit) {
        // 界面侧回填 Activity 上下文，供眼动等运行时权限申请使用
        GazePinchController.bindHostContext(context)
        opened.forEach { id ->
            runCatching { navigator.openWindowContainer(id = id, tag = PANEL_TAG) }
        }
    }

    // 智能分析产出结论后，把聚焦窗自动前置到医生面前
    val incident = SurgicalSession.incident
    LaunchedEffect(incident) {
        if (incident != null) {
            runCatching { navigator.openWindowContainer(id = FOCUS_ID, tag = PANEL_TAG) }
            opened = opened + FOCUS_ID
        }
    }

    // 语音口令命中后立刻把聚焦窗拉到眼前，不等分析结论产出
    val voiceHits = VoiceCommandRouter.hitSeq
    LaunchedEffect(voiceHits) {
        if (voiceHits > 0) {
            runCatching { navigator.openWindowContainer(id = FOCUS_ID, tag = PANEL_TAG) }
            opened = opened + FOCUS_ID
        }
    }

    // 病历面板随 recordSeq 开合：语音、注视、面板按钮三条路径共用同一会话状态
    val recordSeq = SurgicalSession.recordSeq
    LaunchedEffect(recordSeq) {
        if (recordSeq <= 0) return@LaunchedEffect
        runCatching {
            if (SurgicalSession.recordOpen) {
                navigator.openWindowContainer(id = RECORD_ID, tag = PANEL_TAG)
            } else {
                navigator.closeWindowContainer(id = RECORD_ID, tag = PANEL_TAG)
            }
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

    // 麦克风入口：权限未授权时先申请，已授权则直接进入一次识别
    fun startVoice() {
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            VoiceCommandRouter.onDenied()
            runCatching {
                (context as? android.app.Activity)
                    ?.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
            }
            return
        }
        VoiceInput.listen(
            context = context,
            onText = { VoiceCommandRouter.handle(it, "麦克风") },
            onStatus = { VoiceCommandRouter.note(it) }
        )
    }

    // 口令命中的听觉回执：状态机边沿触发，只在“听到”那一刻响一次
    val voiceState = VoiceCommandRouter.state
    var lastVoiceState by remember { mutableStateOf<VoiceState?>(null) }
    LaunchedEffect(voiceState) {
        if (voiceState == VoiceState.HEARD && lastVoiceState != VoiceState.HEARD) {
            SurgicalAudio.confirm()
        }
        lastVoiceState = voiceState
    }

    PanelFrame(
        title = "手术指挥台 · OR-03",
        tag = SurgicalSession.CASE_NO,
        accent = Palette.Cyan,
        trailing = {
            val alarmColor = when (SurgicalSession.alarm) {
                AlarmLevel.CRITICAL -> Palette.Red
                AlarmLevel.WATCH -> Palette.Amber
                AlarmLevel.NOTICE -> Palette.Cyan
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
            Spacer(Modifier.width(12.dp))
            MicButton(listening = VoiceInput.busy) { startVoice() }
        }
    ) {
        Row(Modifier.fillMaxSize()) {
            // ---------- 左栏：窗口与联动控制 ----------
            Column(Modifier.weight(1.15f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionLabel("运行模式")
                    Spacer(Modifier.weight(1f))
                    AppMode.values().forEach { mode ->
                        ChipButton(
                            label = mode.label,
                            active = SurgicalSession.appMode == mode,
                            accent = if (mode == AppMode.DEMO) Palette.Amber else Palette.Mint
                        ) {
                            if (SurgicalSession.appMode != mode) {
                                SurgicalSession.appMode = mode
                                SurgicalSession.log("运行模式：${mode.label}")
                                if (mode == AppMode.DEMO) AiController.onResolved()
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    text = SurgicalSession.appMode.hint,
                    color = Palette.TextDim,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(9.dp))
                PrimaryButton(
                    title = "一键演示 · 术中意外应急处置",
                    subtitle = if (SurgicalSession.appMode == AppMode.DEMO) {
                        "点击后自动定位病灶、虚拟放大、剖切并输出处置建议"
                    } else {
                        "产品模式下由监护数据越限自动触发，此处用于现场手动复核"
                    },
                    accent = Palette.Red
                ) { AiController.triggerDemo() }
                Spacer(Modifier.height(7.dp))
                Text(
                    text = when {
                        SurgicalSession.aiRunning -> "智能分析执行中 · ${SurgicalSession.focusStage.label}"
                        incident != null -> "事件 ${incident.code} · ${incident.region}"
                        else -> "智能分析待命 · 引擎 ${AiGateway.engineLabel()}"
                    },
                    color = if (incident != null) Palette.Red else Palette.TextDim,
                    fontSize = 11.sp
                )

                Spacer(Modifier.height(14.dp))
                SectionLabel("空间窗口 · 可拖拽 / 缩放 / 吸附")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipButton("影像窗", IMAGING_ID in opened, Palette.Cyan) { switchWindow(IMAGING_ID) }
                    ChipButton("体征窗", VITALS_ID in opened, Palette.Mint) { switchWindow(VITALS_ID) }
                    ChipButton("内窥镜", SCOPE_ID in opened, Palette.Violet) { switchWindow(SCOPE_ID) }
                    ChipButton("聚焦窗", FOCUS_ID in opened, Palette.Red) { switchWindow(FOCUS_ID) }
                    ChipButton("病历窗", SurgicalSession.recordOpen, Palette.Cyan) {
                        VoiceCommandRouter.handle(
                            if (SurgicalSession.recordOpen) VoiceIntent.CLOSE_RECORD.sample
                            else VoiceIntent.OPEN_RECORD.sample,
                            "控制台"
                        )
                    }
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
                SectionLabel("语音指令 · 术者免手操作", Palette.Red)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MicButton(listening = VoiceInput.busy) { startVoice() }
                    Spacer(Modifier.width(8.dp))
                    // 免手口令面板：逐条点按等价于说同一句话，供现场无麦克风环境复核演示
                    VoiceIntent.values()
                        .filter { it != VoiceIntent.CLOSE_RECORD && it != VoiceIntent.STOP_BLOCKADE }
                        .forEach { intent ->
                            ChipButton(
                                label = intent.label,
                                active = VoiceCommandRouter.lastText.contains(intent.sample),
                                accent = Palette.Red
                            ) {
                                VoiceCommandRouter.handle(intent.sample, "口令面板")
                                SurgicalAudio.confirm()
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                    StatusDot(
                        active = VoiceCommandRouter.state == VoiceState.LISTENING,
                        color = Palette.Amber,
                        label = VoiceCommandRouter.state.label
                    )
                    Spacer(Modifier.width(10.dp))
                    // 音效总开关：只影响听觉提示，关掉后监护判定与告警等级依然照常运行
                    ChipButton(
                        label = if (SurgicalAudio.enabled) "音效 开" else "音效 关",
                        active = SurgicalAudio.enabled,
                        accent = Palette.Mint
                    ) {
                        SurgicalAudio.enabled = !SurgicalAudio.enabled
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "说「${VoiceIntent.OPEN_RECORD.sample}」→ 空间内展开病历面板；说「关闭」→ 收起，全程零接触",
                    color = Palette.TextDim,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "说「开始阻断」→ 15 min 倒计时；说「结束阻断」→ 归档阻断时长；说「同步教学端」→ 推送主刀视角",
                    color = Palette.TextDim,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = VoiceCommandRouter.statusNote,
                    color = when (VoiceCommandRouter.state) {
                        VoiceState.HEARD -> Palette.Red
                        VoiceState.ERROR, VoiceState.UNAVAILABLE, VoiceState.DENIED -> Palette.Amber
                        else -> Palette.TextBody
                    },
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "音频 · ${SurgicalAudio.statusNote}",
                    color = if (SurgicalAudio.ready) Palette.TextBody else Palette.Amber,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(Modifier.height(14.dp))
                SectionLabel("事件演示")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipButton(
                        label = if (SurgicalSession.blockadeRunning) "结束阻断" else "开始阻断",
                        active = SurgicalSession.blockadeRunning,
                        accent = Palette.Amber
                    ) {
                        VoiceCommandRouter.handle(
                            if (SurgicalSession.blockadeRunning) VoiceIntent.STOP_BLOCKADE.sample
                            else VoiceIntent.START_BLOCKADE.sample,
                            "控制台"
                        )
                    }
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
                        AiController.onResolved()
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

                Spacer(Modifier.height(10.dp))
                SectionLabel("仪器与接口", Palette.Cyan)
                Spacer(Modifier.height(6.dp))
                KeyValueRow("监护仪", InstrumentRegistry.vitalsLabel())
                KeyValueRow("影像源", InstrumentRegistry.imagingLabel())
                KeyValueRow("内窥镜", InstrumentRegistry.endoscopeLabel())
                KeyValueRow(
                    "推理服务",
                    if (AiGateway.remoteConfigured) AiGateway.endpoint else "未配置 · 本地规则引擎",
                    if (AiGateway.remoteConfigured) Palette.Mint else Palette.TextBody
                )

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    MiniRadar(Modifier.width(116.dp), diameter = 104.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        SectionLabel("空间缩略雷达 · 五窗绝对坐标", Palette.Cyan)
                        Spacer(Modifier.height(5.dp))
                        CoordStack(fontSize = 8)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "俯视投影 · 原点为指挥台 · 单位 m",
                            color = Palette.TextDim,
                            fontSize = 9.sp
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                SectionLabel("智能建议 · 流式输出", Palette.Amber)
                Spacer(Modifier.height(6.dp))
                NarrationBlock(accent = Palette.Amber, visibleLines = 2, fixedHeight = 62)

                Spacer(Modifier.height(10.dp))
                SectionLabel("视觉预算 · 核心时钟独立", Palette.Mint)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = PerfGovernor.statusText(),
                    color = Palette.TextBody,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "降级仅作用于 Canvas 重绘与波形采样；100 ms 心跳与告警判定恒定满速",
                    color = Palette.TextDim,
                    fontSize = 10.sp
                )

                Spacer(Modifier.height(12.dp))
                SectionLabel("实时事件流", Palette.Amber)
                Spacer(Modifier.height(8.dp))
                Column {
                    SurgicalSession.events.take(3).forEach { event ->
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
