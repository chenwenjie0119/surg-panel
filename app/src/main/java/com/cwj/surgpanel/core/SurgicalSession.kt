package com.cwj.surgpanel.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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

/**
 * 告警等级。
 *
 * [NOTICE] 为几乎不打扰的青色微提示：单项体征轻度越界时只点亮一个点，
 * 不出声、不写事件流、不改变任何面板底色，用于对抗手术室“警报疲劳”。
 */
enum class AlarmLevel { NONE, NOTICE, WATCH, CRITICAL }

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
    fun stamp(): String = String.format(java.util.Locale.US, "%02d:%02d", atSec / 60, atSec % 60)
}

/**
 * 全局手术会话：三个 WindowContainer 属于同一进程，共享此单例状态，
 * 任一窗口写入，其余窗口在同一帧内感知，构成联动底座。
 */
object SurgicalSession {

    const val PATIENT = "王建国 · 男 · 54 岁"
    const val CASE_NO = "OR-2026-1107-03"
    const val PROCEDURE = "腹腔镜胆囊切除术"

    /** 肝门阻断时限：15 min 为临床常用上限。 */
    const val BLOCKADE_LIMIT_SEC = 900

    // ---- 手术时间轴 ----
    var elapsedSec by mutableIntStateOf(0)
    var running by mutableStateOf(true)
    var phase by mutableStateOf(ProceduralPhase.DISSECTION)

    // ---- 事件演示：手动诱发术野渗血（便于路演复现告警联动） ----
    var manualBleed by mutableStateOf(false)

    // ---- 运行模式 ----
    var appMode by mutableStateOf(AppMode.DEMO)

    // ---- 智能异常处置 ----
    var incident by mutableStateOf<IncidentCase?>(null)
    var focusStage by mutableStateOf(FocusStage.IDLE)
    var focusAnim by mutableFloatStateOf(0f)
    var aiRunning by mutableStateOf(false)
    var aiEngine by mutableStateOf("本地规则引擎")
    var aiLatencyMs by mutableIntStateOf(0)

    /** 处置序列是否已被确认（捏合或点击），确认后聚焦窗转为已执行态。 */
    var adviceConfirmed by mutableStateOf(false)

    /** 最近一次语义来源，用于在聚焦窗展示“自动触发 / 语音指令 / 捏合确认”。 */
    var triggerSource by mutableStateOf("")

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

    /**
     * 人为强制的危急保持截止时刻（uptime 毫秒）。
     *
     * 语音或面板手动升级告警后，若体征本身尚未越限，判定逻辑不得在下一拍就把等级拉回，
     * 因此给出一个短的保持窗口，窗口内由判定逻辑直接沿用 CRITICAL。
     */
    var forceCriticalUntilMs by mutableLongStateOf(0L)

    // ---- 内窥镜窗 ----
    var scopeZoom by mutableFloatStateOf(1f)
    var autoFluorescence by mutableStateOf(false)
    var recording by mutableStateOf(true)

    // ---- 多模态联合判定（警报疲劳治理）----
    /** 内窥镜画面渗出识别置信度 0..1，画面侧推断结果。 */
    var scopeExudate by mutableFloatStateOf(0f)
    /** 联合判定已成立的条件数（0..3），用于面板展示“交叉验证进度”。 */
    var fusionHits by mutableIntStateOf(0)
    /** 联合判定条件明细，如“心率↑ 血压↓ 渗出+”。 */
    var fusionDetail by mutableStateOf("—")

    // ---- 关键时限与耗材 ----
    /** 肝门阻断是否进行中。 */
    var blockadeRunning by mutableStateOf(false)
    /** 已阻断秒数。 */
    var blockadeSec by mutableIntStateOf(0)
    /** 台上纱布数量。 */
    var spongeOnField by mutableIntStateOf(6)
    /** 清点确认数量，与台上数量一致方可关腹。 */
    var spongeCounted by mutableIntStateOf(6)
    /** 估算出血量（mL）。 */
    var eblMl by mutableIntStateOf(0)
    /** 出血量累加缓冲，避免每拍取整丢精度。 */
    var eblAcc by mutableFloatStateOf(0f)

    // ---- 主刀视角同步 ----
    /** 是否处于教学 / 会诊同步状态。 */
    var teachingSync by mutableStateOf(false)
    /** 已接入的接收端数量。 */
    var teachingPeers by mutableIntStateOf(0)

    // ---- 无接触病历调阅 ----
    /** 病历面板是否处于展开态（由注视或语音唤出）。 */
    var recordOpen by mutableStateOf(false)
    /** 当前调阅条目 id。 */
    var recordEntryId by mutableStateOf("")
    /** 唤出序号，面板侧据此把窗口前置。 */
    var recordSeq by mutableIntStateOf(0)

    val events = mutableStateListOf(
        CaseEvent(0, "术前核查通过，气腹建立 12 mmHg"),
        CaseEvent(38, "胆囊三角暴露，进入分离阶段")
    )

    /**
     * 事件日志写入。
     *
     * [events] 是 Compose 快照列表，只允许在主线程修改，而日志来源横跨三处：
     * SessionClock 报警线程、AiController 推理协程、UI 交互。这里统一收口到主线程，
     * 非主线程调用转为 post；时间戳在调用线程取定，避免排队一拍后记到错误的秒数上。
     */
    fun log(text: String, critical: Boolean = false) {
        val atSec = elapsedSec
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            writeEvent(atSec, text, critical)
        } else {
            mainHandler.post { writeEvent(atSec, text, critical) }
        }
    }

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    private fun writeEvent(atSec: Int, text: String, critical: Boolean) {
        events.add(0, CaseEvent(atSec, text, critical))
        while (events.size > 20) events.removeAt(events.lastIndex)
    }

    fun clock(): String = String.format(java.util.Locale.US, "%02d:%02d", elapsedSec / 60, elapsedSec % 60)

    /** 阻断剩余秒数（未开始阻断时按满额显示）。 */
    fun blockadeRemain(): Int = (BLOCKADE_LIMIT_SEC - blockadeSec).coerceAtLeast(0)

    /** 阻断倒计时文本 MM:SS。 */
    fun blockadeLabel(): String {
        val r = blockadeRemain()
        return String.format(java.util.Locale.US, "%02d:%02d", r / 60, r % 60)
    }

    /** 台上纱布与清点数量是否一致，一致方可关腹。 */
    fun countMatched(): Boolean = spongeOnField == spongeCounted

    /** 出血量文本。 */
    fun eblLabel(): String = "$eblMl mL"

    fun sliceLabel(): String = "${modality.label} · 第 ${sliceIndex + 1} / 96 层"
}
