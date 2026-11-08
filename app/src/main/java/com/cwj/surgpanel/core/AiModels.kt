package com.cwj.surgpanel.core

/** 事件与证据的严重度。 */
enum class Severity { INFO, WARN, CRITICAL }

/** 单条分析证据。 */
data class AiFinding(
    val title: String,
    val detail: String,
    val severity: Severity = Severity.INFO
)

/** 单条处置建议。 */
data class AiAction(
    val order: Int,
    val action: String,
    val detail: String,
    val eta: String = ""
)

/** 一次术中异常事件的结构化结论。 */
data class IncidentCase(
    val code: String,
    val region: String,
    val cause: String,
    val targetSlice: Int,
    val findings: List<AiFinding>,
    val actions: List<AiAction>,
    val confidence: Int,
    val riskLevel: Severity = Severity.CRITICAL
)

/** 聚焦展示的推进阶段，驱动聚焦窗画面与阶段条。 */
enum class FocusStage(val label: String, val step: Int) {
    IDLE("待命", 0),
    LOCALIZE("定位病灶", 1),
    ENLARGE("放大呈现", 2),
    SECTION("自动剖切", 3),
    ADVISE("分析处置", 4)
}

/** 送入推理服务的现场快照。 */
data class AiRequest(
    val caseNo: String,
    val patient: String,
    val procedure: String,
    val heartRate: Int,
    val systolic: Int,
    val diastolic: Int,
    val spo2: Int,
    val etco2: Int,
    val modality: String,
    val sliceIndex: Int,
    val phase: String,
    val trigger: String
)

/** 推理服务返回。 */
data class AiResponse(
    val incident: IncidentCase,
    val engine: String,
    val latencyMs: Long,
    val remote: Boolean
)
