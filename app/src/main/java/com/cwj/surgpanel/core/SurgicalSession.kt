package com.cwj.surgpanel.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 窗口联动强度：决定三个悬浮窗之间的数据互相牵引方式。 */
enum class LinkMode(val label: String, val hint: String) {
    FULL("全联动", "影像/体征/内窥镜共享同一条手术时间轴"),
    FOCUS("焦点联动", "仅当前聚焦窗口的数据被其余窗口镜像"),
    OFF("独立", "各窗口各自刷新，互不牵引")
}

/** 影像模态。 */
enum class Modality(val label: String, val unit: String) {
    CT("CT", "HU"),
    MRI("MRI T2", "SI")
}

/** 告警等级。 */
enum class AlarmLevel { NONE, WATCH, CRITICAL }

/** 手术阶段。 */
enum class ProceduralPhase(val label: String) {
    PREP("术前核查"),
    INDUCTION("麻醉诱导"),
    DISSECTION("分离胆囊三角"),
    CLIP("夹闭与离断"),
    CLOSURE("止血关腹")
}

/** 空间布局预设：直接决定三个悬浮窗的排布与开合。 */
enum class LayoutPreset(val label: String) {
    STANDARD("标准三窗"),
    IMAGING("影像主导"),
    VITALS("体征主导")
}

/** 手术事件日志条目。 */
data class CaseEvent(val atSec: Int, val text: String, val critical: Boolean = false) {
    fun stamp(): String = "%02d:%02d".format(atSec / 60, atSec % 60)
}

/**
 * 全局手术会话：三个 WindowContainer 属于同一进程，共享此单例状态，
 * 任一窗口写入，其余窗口在同一帧内感知，构成联动底座。
 */
object SurgicalSession {

    const val PATIENT = "王建国 · 男 · 54 岁"
    const val CASE_NO = "OR-2026-1107-03"
    const val PROCEDURE = "腹腔镜胆囊切除术"

    // ---- 手术时间轴 ----
    var elapsedSec by mutableIntStateOf(0)
    var running by mutableStateOf(true)
    var phase by mutableStateOf(ProceduralPhase.DISSECTION)

    // ---- 事件演示：手动诱发术野渗血（便于路演复现告警联动） ----
    var manualBleed by mutableStateOf(false)

    // ---- 联动与布局 ----
    var linkMode by mutableStateOf(LinkMode.FULL)
    var layoutPreset by mutableStateOf(LayoutPreset.STANDARD)
    var linkedSliceHighlight by mutableStateOf(false)

    // ---- 影像窗 ----
    var modality by mutableStateOf(Modality.CT)
    var sliceIndex by mutableIntStateOf(44)
    var windowWidth by mutableFloatStateOf(350f)
    var windowLevel by mutableFloatStateOf(40f)
    var roiLocked by mutableStateOf(true)
    var imagingZoom by mutableFloatStateOf(1f)

    // ---- 体征窗 ----
    var heartRate by mutableIntStateOf(78)
    var spo2 by mutableIntStateOf(98)
    var systolic by mutableIntStateOf(118)
    var diastolic by mutableIntStateOf(72)
    var etco2 by mutableIntStateOf(36)
    var alarm by mutableStateOf(AlarmLevel.NONE)
    var alarmText by mutableStateOf("")

    // ---- 内窥镜窗 ----
    var scopeZoom by mutableFloatStateOf(1f)
    var autoFluorescence by mutableStateOf(false)
    var recording by mutableStateOf(true)

    val events = mutableStateListOf(
        CaseEvent(0, "术前核查通过，气腹建立 12 mmHg"),
        CaseEvent(38, "胆囊三角暴露，进入分离阶段")
    )

    fun log(text: String, critical: Boolean = false) {
        events.add(0, CaseEvent(elapsedSec, text, critical))
        while (events.size > 20) events.removeAt(events.lastIndex)
    }

    fun clock(): String = "%02d:%02d".format(elapsedSec / 60, elapsedSec % 60)

    fun sliceLabel(): String = "${modality.label} · 第 ${sliceIndex + 1} / 96 层"

    /** 窗口排布随布局预设调整。 */
    fun applyPreset(preset: LayoutPreset) {
        layoutPreset = preset
        log("布局切换：${preset.label}")
    }
}
