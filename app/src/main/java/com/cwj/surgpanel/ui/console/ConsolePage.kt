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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cwj.surgpanel.FOCUS_ID
import com.cwj.surgpanel.IMAGING_ID
import com.cwj.surgpanel.R
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
import com.cwj.surgpanel.ui.common.CoordStack
import com.cwj.surgpanel.ui.common.KeyValueRow
import com.cwj.surgpanel.ui.common.MicButton
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
    // 回调/绘制 lambda 不是 @Composable 上下文，改用 Context 资源取带参文案
    val res = LocalContext.current.resources

    // 点击/语音回调不是 @Composable 上下文，文案在此取出后传入
    val text_console_01 = stringResource(R.string.console_01)
    // 点击/语音回调不是 @Composable 上下文，文案在此取出后传入
    val text_console_13 = stringResource(R.string.console_13)
    // 点击/语音回调不是 @Composable 上下文，文案在此取出后传入
    val text_console_21 = stringResource(R.string.console_21)
    // 点击/语音回调不是 @Composable 上下文，文案在此取出后传入
    val text_console_28 = stringResource(R.string.console_28)
    // 点击/语音回调不是 @Composable 上下文，文案在此取出后传入
    val text_console_30 = stringResource(R.string.console_30)
    // 点击/语音回调不是 @Composable 上下文，文案在此取出后传入
    val text_console_31 = stringResource(R.string.console_31)
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
            onText = { VoiceCommandRouter.handle(it, text_console_01) },
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
        title = stringResource(R.string.console_02),
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
                    SectionLabel(stringResource(R.string.console_03))
                    Spacer(Modifier.weight(1f))
                    AppMode.values().forEach { mode ->
                        ChipButton(
                            label = mode.label,
                            active = SurgicalSession.appMode == mode,
                            accent = if (mode == AppMode.DEMO) Palette.Amber else Palette.Mint
                        ) {
                            if (SurgicalSession.appMode != mode) {
                                SurgicalSession.appMode = mode
                                SurgicalSession.log(res.getString(R.string.console_54, mode.label))
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
                    title = stringResource(R.string.console_04),
                    subtitle = if (SurgicalSession.appMode == AppMode.DEMO) {
                        stringResource(R.string.console_05)
                    } else {
                        stringResource(R.string.console_06)
                    },
                    accent = Palette.Red
                ) { AiController.triggerDemo() }
                Spacer(Modifier.height(7.dp))
                Text(
                    text = when {
                        SurgicalSession.aiRunning -> stringResource(R.string.console_55, SurgicalSession.focusStage.label)
                        incident != null -> stringResource(R.string.console_56, incident.code, incident.region)
                        else -> stringResource(R.string.console_57, AiGateway.engineLabel())
                    },
                    color = if (incident != null) Palette.Red else Palette.TextDim,
                    fontSize = 11.sp
                )

                Spacer(Modifier.height(14.dp))
                SectionLabel(stringResource(R.string.console_07))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipButton(stringResource(R.string.console_08), IMAGING_ID in opened, Palette.Cyan) { switchWindow(IMAGING_ID) }
                    ChipButton(stringResource(R.string.console_09), VITALS_ID in opened, Palette.Mint) { switchWindow(VITALS_ID) }
                    ChipButton(stringResource(R.string.console_10), SCOPE_ID in opened, Palette.Violet) { switchWindow(SCOPE_ID) }
                    ChipButton(stringResource(R.string.console_11), FOCUS_ID in opened, Palette.Red) { switchWindow(FOCUS_ID) }
                    ChipButton(stringResource(R.string.console_12), SurgicalSession.recordOpen, Palette.Cyan) {
                        VoiceCommandRouter.handle(
                            if (SurgicalSession.recordOpen) VoiceIntent.CLOSE_RECORD.sample
                            else VoiceIntent.OPEN_RECORD.sample,
                            text_console_13
                        )
                    }
                    ChipButton(
                        label = stringResource(R.string.console_14),
                        active = false,
                        accent = Palette.TextBody
                    ) {
                        listOf(IMAGING_ID, VITALS_ID, SCOPE_ID)
                            .filter { it !in opened }
                            .forEach { switchWindow(it) }
                    }
                }

                Spacer(Modifier.height(14.dp))
                SectionLabel(stringResource(R.string.console_15))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinkMode.values().forEach { mode ->
                        ChipButton(mode.label, SurgicalSession.linkMode == mode) {
                            SurgicalSession.linkMode = mode
                            SurgicalSession.log(res.getString(R.string.console_58, mode.label))
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
                SectionLabel(stringResource(R.string.console_16))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Modality.values().forEach { m ->
                        ChipButton(m.label, SurgicalSession.modality == m, Palette.Cyan) {
                            SurgicalSession.modality = m
                        }
                    }
                    ChipButton(stringResource(R.string.console_17), false, Palette.TextBody) {
                        SurgicalSession.sliceIndex = (SurgicalSession.sliceIndex - 5).coerceAtLeast(0)
                    }
                    ChipButton(stringResource(R.string.console_18), false, Palette.TextBody) {
                        SurgicalSession.sliceIndex = (SurgicalSession.sliceIndex + 5).coerceAtMost(95)
                    }
                    ChipButton(stringResource(R.string.console_19), SurgicalSession.roiLocked, Palette.Amber) {
                        SurgicalSession.roiLocked = !SurgicalSession.roiLocked
                    }
                }

                Spacer(Modifier.height(14.dp))
                SectionLabel(stringResource(R.string.console_20), Palette.Red)
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
                                VoiceCommandRouter.handle(intent.sample, text_console_21)
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
                        label = if (SurgicalAudio.enabled) stringResource(R.string.console_22) else stringResource(R.string.console_23),
                        active = SurgicalAudio.enabled,
                        accent = Palette.Mint
                    ) {
                        SurgicalAudio.enabled = !SurgicalAudio.enabled
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    text = stringResource(R.string.console_59, VoiceIntent.OPEN_RECORD.sample),
                    color = Palette.TextDim,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.console_24),
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
                    text = stringResource(R.string.console_60, SurgicalAudio.statusNote),
                    color = if (SurgicalAudio.ready) Palette.TextBody else Palette.Amber,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(Modifier.height(14.dp))
                SectionLabel(stringResource(R.string.console_25))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipButton(
                        label = if (SurgicalSession.blockadeRunning) stringResource(R.string.console_26) else stringResource(R.string.console_27),
                        active = SurgicalSession.blockadeRunning,
                        accent = Palette.Amber
                    ) {
                        VoiceCommandRouter.handle(
                            if (SurgicalSession.blockadeRunning) VoiceIntent.STOP_BLOCKADE.sample
                            else VoiceIntent.START_BLOCKADE.sample,
                            text_console_28
                        )
                    }
                    ChipButton(stringResource(R.string.console_29), SurgicalSession.manualBleed, Palette.Red) {
                        SurgicalSession.manualBleed = !SurgicalSession.manualBleed
                        SurgicalSession.log(
                            if (SurgicalSession.manualBleed) text_console_30 else text_console_31,
                            critical = SurgicalSession.manualBleed
                        )
                    }
                    ChipButton(stringResource(R.string.console_32), false, Palette.TextBody) {
                        SurgicalSession.elapsedSec = 0
                        SurgicalSession.manualBleed = false
                        AiController.onResolved()
                        SurgicalSession.log(res.getString(R.string.console_62))
                    }
                }

                Spacer(Modifier.height(14.dp))
                SectionLabel(stringResource(R.string.console_33))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.console_61, SurgicalSession.phase.label, SurgicalSession.clock()),
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
                SectionLabel(stringResource(R.string.console_34), Palette.Mint)
                Spacer(Modifier.height(8.dp))
                KeyValueRow(stringResource(R.string.console_35), SurgicalSession.PATIENT)
                KeyValueRow(stringResource(R.string.console_36), SurgicalSession.PROCEDURE)
                KeyValueRow(stringResource(R.string.console_37), stringResource(R.string.console_38))
                KeyValueRow(stringResource(R.string.console_39), stringResource(R.string.console_40))
                KeyValueRow(
                    stringResource(R.string.console_41),
                    "${SurgicalSession.modality.label} #${SurgicalSession.sliceIndex + 1}",
                    Palette.Cyan
                )

                Spacer(Modifier.height(10.dp))
                SectionLabel(stringResource(R.string.console_42), Palette.Cyan)
                Spacer(Modifier.height(6.dp))
                KeyValueRow(stringResource(R.string.console_43), InstrumentRegistry.vitalsLabel())
                KeyValueRow(stringResource(R.string.console_44), InstrumentRegistry.imagingLabel())
                KeyValueRow(stringResource(R.string.console_45), InstrumentRegistry.endoscopeLabel())
                KeyValueRow(
                    stringResource(R.string.console_46),
                    if (AiGateway.remoteConfigured) AiGateway.endpoint else stringResource(R.string.console_47),
                    if (AiGateway.remoteConfigured) Palette.Mint else Palette.TextBody
                )

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    MiniRadar(Modifier.width(116.dp), diameter = 104.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        SectionLabel(stringResource(R.string.console_48), Palette.Cyan)
                        Spacer(Modifier.height(5.dp))
                        CoordStack(fontSize = 8)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = stringResource(R.string.console_49),
                            color = Palette.TextDim,
                            fontSize = 9.sp
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                SectionLabel(stringResource(R.string.console_50), Palette.Amber)
                Spacer(Modifier.height(6.dp))
                NarrationBlock(accent = Palette.Amber, visibleLines = 2, fixedHeight = 62)

                Spacer(Modifier.height(10.dp))
                SectionLabel(stringResource(R.string.console_51), Palette.Mint)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = PerfGovernor.statusText(),
                    color = Palette.TextBody,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.console_52),
                    color = Palette.TextDim,
                    fontSize = 10.sp
                )

                Spacer(Modifier.height(12.dp))
                SectionLabel(stringResource(R.string.console_53), Palette.Amber)
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