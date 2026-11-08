package com.cwj.surgpanel.core

import com.cwj.surgpanel.data.AiGateway

/**
 * 智能异常处置编排器。
 *
 * 两条入口：
 * - [triggerDemo]：演示模式的前方测试按钮，一键注入术中意外并跑完整链路（定位 → 放大 → 剖切 → 建议）；
 * - [onAlarmTriggered]：产品模式下由监护数据越限自动触发，无需人工干预。
 *
 * 链路在后台线程推进，阶段变化写入 [SurgicalSession]，聚焦窗与其余面板在同一帧内响应。
 */
object AiController {

    /** 演示场景的维持时长，到期后自动回稳，便于连续演示。 */
    private const val DEMO_HOLD_MS = 16_000L

    /** 阶段动画步进。 */
    private const val STAGE_TICK_MS = 20L

    @Volatile
    private var generation = 0

    /** 是否正在执行链路。 */
    val running: Boolean
        get() = SurgicalSession.aiRunning

    /** 演示模式入口：一键触发；链路运行中再次点击则提前收起。 */
    fun triggerDemo() {
        if (SurgicalSession.aiRunning) {
            cancelDemo()
            return
        }
        val gen = ++generation
        SurgicalSession.manualBleed = true
        SurgicalSession.log("演示触发：术中渗血场景注入", critical = true)
        startPipeline(gen, "演示按钮")
        Thread({
            try {
                Thread.sleep(DEMO_HOLD_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (gen == generation) {
                SurgicalSession.manualBleed = false
                SurgicalSession.log("演示：出血控制完成，体征回稳")
            }
        }, "surgpanel-demo-hold").apply { isDaemon = true }.start()
    }

    /** 产品模式入口：监护数据越限自动触发。 */
    fun onAlarmTriggered(level: AlarmLevel) {
        if (level == AlarmLevel.NONE) {
            onResolved()
            return
        }
        if (SurgicalSession.focusStage != FocusStage.IDLE) return
        val gen = ++generation
        SurgicalSession.log("监护数据越限，自动启动智能分析")
        startPipeline(gen, "监护阈值越限")
    }

    /**
     * 异常解除，回到待命。
     *
     * [fromData] 为真表示由监护数据回稳自动触发：演示模式下忽略，
     * 避免演示结束后的体征回升把医生正在看的处置结论提前收走。
     */
    fun onResolved(fromData: Boolean = false) {
        if (fromData && SurgicalSession.appMode == AppMode.DEMO) return
        if (SurgicalSession.focusStage == FocusStage.IDLE) return
        generation++
        SurgicalSession.incident = null
        SurgicalSession.focusStage = FocusStage.IDLE
        SurgicalSession.focusAnim = 0f
        SurgicalSession.aiRunning = false
        SurgicalSession.log("异常解除，智能分析回到待命")
    }

    private fun cancelDemo() {
        generation++
        SurgicalSession.manualBleed = false
        SurgicalSession.focusStage = FocusStage.IDLE
        SurgicalSession.focusAnim = 0f
        SurgicalSession.aiRunning = false
        SurgicalSession.log("演示：场景已收起")
    }

    private fun startPipeline(gen: Int, trigger: String) {
        Thread({ runPipeline(gen, trigger) }, "surgpanel-ai").apply { isDaemon = true }.start()
    }

    private fun runPipeline(gen: Int, trigger: String) {
        if (gen != generation) return
        SurgicalSession.aiRunning = true
        SurgicalSession.incident = null

        stage(gen, FocusStage.LOCALIZE, 500)
        if (gen != generation) return

        val request = AiRequest(
            caseNo = SurgicalSession.CASE_NO,
            patient = SurgicalSession.PATIENT,
            procedure = SurgicalSession.PROCEDURE,
            heartRate = SurgicalSession.heartRate,
            systolic = SurgicalSession.systolic,
            diastolic = SurgicalSession.diastolic,
            spo2 = SurgicalSession.spo2,
            etco2 = SurgicalSession.etco2,
            modality = SurgicalSession.modality.label,
            sliceIndex = SurgicalSession.sliceIndex,
            phase = SurgicalSession.phase.label,
            trigger = trigger
        )
        val response = AiGateway.analyze(request)
        if (gen != generation) return

        SurgicalSession.aiEngine = response.engine
        SurgicalSession.aiLatencyMs = response.latencyMs.toInt()
        SurgicalSession.incident = response.incident
        SurgicalSession.log("智能定位：${response.incident.region}")

        stage(gen, FocusStage.ENLARGE, 900)
        if (gen != generation) return
        SurgicalSession.log("病灶区已放大，虚拟呈现在医生正前方视野")

        stage(gen, FocusStage.SECTION, 1000)
        if (gen != generation) return
        SurgicalSession.sliceIndex = response.incident.targetSlice.coerceIn(0, 95)
        SurgicalSession.log("自动剖切：断面切至第 ${response.incident.targetSlice + 1} 层，内部结构展开")

        stage(gen, FocusStage.ADVISE, 700)
        if (gen != generation) return
        SurgicalSession.aiRunning = false
        SurgicalSession.log(
            "处置建议已生成 ${response.incident.actions.size} 项，置信度 ${response.incident.confidence}%",
            critical = true
        )
    }

    private fun stage(gen: Int, stage: FocusStage, durationMs: Long) {
        if (gen != generation) return
        SurgicalSession.focusStage = stage
        val steps = (durationMs / STAGE_TICK_MS).toInt().coerceAtLeast(1)
        for (i in 0..steps) {
            if (gen != generation) return
            SurgicalSession.focusAnim = i.toFloat() / steps
            try {
                Thread.sleep(STAGE_TICK_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }
}
