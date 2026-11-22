package com.cwj.surgpanel.core

import com.cwj.surgpanel.data.AiGateway

/**
 * 智能异常处置编排器。
 *
 * 两条入口：
 * - [triggerDemo]：演示模式的前方测试按钮，一键注入术中意外并跑完整链路（定位 → 放大 → 剖切 → 建议）；
 * - [onAlarmTriggered]：产品模式下由监护数据越限自动触发，无需人工干预。
 *
 * 链路在后台线程推进，阶段变化回投主线程写入 [SurgicalSession]，聚焦窗与其余面板在同一帧内响应。
 */
object AiController {

    /** 演示场景的维持时长，到期后自动回稳，便于连续演示。 */
    private const val DEMO_HOLD_MS = 16_000L

    /** 阶段动画步进。 */
    private const val STAGE_TICK_MS = 20L

    /** 主线程投递口：链路在后台线程推进，Compose 状态一律回投主线程写。 */
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * 在主线程执行 [block]。
     *
     * 链路跑在 `surgpanel-ai` 后台线程上，而 [SurgicalSession] 的字段是 Compose 快照状态。
     * 快照状态只在主线程写才不会与重组竞争（否则可能刚写入就被同帧快照覆盖，或触发 snapshot apply 冲突）。
     * 已经在主线程时直接执行，避免多余的一次投递延迟。
     */
    private inline fun ui(crossinline block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

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
        SurgicalSession.adviceConfirmed = false
        SurgicalSession.triggerSource = "演示按钮"
        SurgicalSession.log("演示触发：术中渗血场景注入", critical = true)
        AiNarrator.stream(NarrationTone.ANALYZE, "正在分析术野画面与监护趋势……", clearFirst = true)
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

    /**
     * 语音指令入口：术者说出“立刻查看出血点”后立即启动同一条处置链路。
     *
     * 与自动触发的唯一区别是来源标记；链路本身、告警判级与动画时序完全一致，
     * 不引入第二套逻辑，避免出现两套行为。
     */
    fun triggerVoiceInspect(command: String, source: String) {
        val gen = ++generation
        SurgicalSession.manualBleed = true
        SurgicalSession.adviceConfirmed = false
        SurgicalSession.triggerSource = "语音指令"
        SurgicalSession.log("语音触发处置链路（$source）：$command", critical = true)
        AiNarrator.stream(NarrationTone.ANALYZE, "已接到口令，正在锁定术野关注区……", clearFirst = true)
        startPipeline(gen, "语音指令")
    }

    /** 处置序列确认（捏合或点击）。只在建议已产出后生效，幂等。 */
    fun confirmAdvice(source: String) {
        if (SurgicalSession.focusStage == FocusStage.IDLE) return
        if (SurgicalSession.adviceConfirmed) return
        if (SurgicalSession.focusStage != FocusStage.ADVISE) {
            SurgicalSession.log("处置序列尚未生成，暂不能确认（$source）")
            return
        }
        SurgicalSession.adviceConfirmed = true
        SurgicalSession.log("处置序列已确认执行：夹闭出血点 / 缝扎 / 冲洗（$source）", critical = true)
    }

    /** 产品模式入口：监护数据越限自动触发。 */
    fun onAlarmTriggered(level: AlarmLevel) {
        if (level == AlarmLevel.NONE) {
            onResolved()
            return
        }
        if (SurgicalSession.focusStage != FocusStage.IDLE) return
        val gen = ++generation
        SurgicalSession.adviceConfirmed = false
        SurgicalSession.triggerSource = "监护阈值越限"
        SurgicalSession.log("监护数据越限，自动启动智能分析")
        AiNarrator.stream(NarrationTone.ANALYZE, "监护指标越限，正在核对体征曲线……", clearFirst = true)
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
        SurgicalSession.adviceConfirmed = false
        SurgicalSession.triggerSource = ""
        AiNarrator.reset()
        SurgicalSession.log("异常解除，智能分析回到待命")
    }

    private fun cancelDemo() {
        generation++
        SurgicalSession.manualBleed = false
        SurgicalSession.focusStage = FocusStage.IDLE
        SurgicalSession.focusAnim = 0f
        SurgicalSession.aiRunning = false
        SurgicalSession.adviceConfirmed = false
        SurgicalSession.triggerSource = ""
        AiNarrator.reset()
        SurgicalSession.log("演示：场景已收起")
    }

    private fun startPipeline(gen: Int, trigger: String) {
        Thread({ runPipeline(gen, trigger) }, "surgpanel-ai").apply { isDaemon = true }.start()
    }

    private fun runPipeline(gen: Int, trigger: String) {
        if (gen != generation) return
        ui { SurgicalSession.aiRunning = true }
        ui { SurgicalSession.incident = null }

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

        ui { SurgicalSession.aiEngine = response.engine }
        ui { SurgicalSession.aiLatencyMs = response.latencyMs.toInt() }
        ui { SurgicalSession.incident = response.incident }
        SurgicalSession.log("智能定位：${response.incident.region}")
        AiNarrator.stream(
            NarrationTone.DETECT,
            "检测到${response.incident.cause}，位置在${response.incident.region}。"
        )

        stage(gen, FocusStage.ENLARGE, 900)
        if (gen != generation) return
        SurgicalSession.log("病灶区已放大，虚拟呈现在医生正前方视野")

        stage(gen, FocusStage.SECTION, 1000)
        if (gen != generation) return
        ui { SurgicalSession.sliceIndex = response.incident.targetSlice.coerceIn(0, 95) }
        SurgicalSession.log("自动剖切：断面切至第 ${response.incident.targetSlice + 1} 层，内部结构展开")
        AiNarrator.stream(
            NarrationTone.ANALYZE,
            "剖面已切至第 ${response.incident.targetSlice + 1} 层，出血点边界与毗邻血管已确认。"
        )

        stage(gen, FocusStage.ADVISE, 700)
        if (gen != generation) return
        ui { SurgicalSession.aiRunning = false }
        SurgicalSession.log(
            "处置建议已生成 ${response.incident.actions.size} 项，置信度 ${response.incident.confidence}%",
            critical = true
        )
        val primary = response.incident.actions.firstOrNull()
        if (primary != null) {
            AiNarrator.stream(NarrationTone.ADVISE, "建议立即${primary.action}。")
            response.incident.actions.drop(1).take(2).forEach { next ->
                AiNarrator.stream(NarrationTone.ADVISE, "随后${next.action}。")
            }
        } else {
            AiNarrator.stream(NarrationTone.ADVISE, "建议维持现有操作，持续观察体征变化。")
        }
    }

    /**
     * 阶段进度动画。
     *
     * 原先由管线线程每 20 ms 直接向主线程 post 一次动画进度：一条完整链路的四个阶段累计要投递
     * 近百条消息，把 AI 分析结果等业务消息压在消息队列里排队，动画推进又与渲染帧不同步；取消时
     * 已入队的过期进度还会继续改写界面。
     *
     * 现在改为「管线线程只等，进度在主线程按拍推进」：每个阶段只在进入时投递一次，进度由主线程
     * Handler 自增，与渲染节奏一致；[generation] 变化（取消 / 重来）时主线程侧立即停拍并放行
     * 管线线程，不会再有残留消息改写新阶段的进度。
     */
    private fun stage(gen: Int, stage: FocusStage, durationMs: Long) {
        if (gen != generation) return
        val steps = (durationMs / STAGE_TICK_MS).toInt().coerceAtLeast(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            if (gen != generation) {
                finished.countDown()
                return@post
            }
            SurgicalSession.focusStage = stage
            SurgicalSession.focusAnim = 0f
            var step = 0
            val ticker = object : Runnable {
                override fun run() {
                    if (gen != generation) {
                        finished.countDown()
                        return
                    }
                    step++
                    SurgicalSession.focusAnim = step.toFloat() / steps
                    if (step >= steps) finished.countDown()
                    else mainHandler.postDelayed(this, STAGE_TICK_MS)
                }
            }
            mainHandler.postDelayed(ticker, STAGE_TICK_MS)
        }
        // 等待本阶段走完。上限为标称时长 + 2 s：主线程若被阻塞也不会让管线永久挂起。
        try {
            finished.await(durationMs + 2_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}