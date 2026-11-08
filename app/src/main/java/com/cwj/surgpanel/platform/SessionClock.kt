package com.cwj.surgpanel.platform

import com.cwj.surgpanel.core.AlarmLevel
import com.cwj.surgpanel.core.LinkMode
import com.cwj.surgpanel.core.ProceduralPhase
import com.cwj.surgpanel.core.SignalEngine
import com.cwj.surgpanel.core.SurgicalSession
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 手术时钟：以固定 100 ms 步长推进全局会话状态（时间轴、体征漂移、告警判定、阶段切换）。
 * 独立的守护线程驱动，因此任一悬浮窗被关闭都不会中断整台手术的数据流。
 */
object SessionClock {

    private const val BLEED_START = 40
    private const val BLEED_END = 68
    private const val CYCLE = 140

    @Volatile
    private var started = false

    private var lastAlarm = AlarmLevel.NONE

    fun start() {
        if (started) return
        started = true
        Thread({
            var tick = 0
            while (true) {
                Thread.sleep(100)
                tick++
                if (!SurgicalSession.running) continue
                if (tick % 10 == 0) SurgicalSession.elapsedSec += 1
                update(tick)
            }
        }, "surgical-clock").apply { isDaemon = true }.start()
    }

    private fun update(tick: Int) {
        val t = SurgicalSession.elapsedSec.toFloat() + (tick % 10) / 10f
        // 渗血事件按周期复现：每 140 s 一轮（40–68 s 渗血，其后 20 s 线性回稳），便于反复演示
        val tc = t % CYCLE
        val bleeding = (tc >= BLEED_START && tc < BLEED_END) || SurgicalSession.manualBleed
        val recovering = tc >= BLEED_END && tc < BLEED_END + 20
        // 回稳系数：出血刚结束时为 1，20 s 后归 0
        val recovery = if (recovering) 1f - (tc - BLEED_END) / 20f else 0f

        // 基线漂移 + 出血事件响应曲线（平稳期约 82/118/98，出血期约 132/84/94，回稳期线性回落）
        val drift = sin(t / 7.3f) * 3.2f + SignalEngine.jitter(t * 0.6f, 3) * 1.4f
        val hrBase = if (bleeding) 132f else 82f + 22f * recovery
        val sbpBase = if (bleeding) 84f else 118f - 26f * recovery
        val spo2Base = if (bleeding) 93f else 98f - 3f * recovery

        SurgicalSession.heartRate = (hrBase + drift).roundToInt().coerceIn(45, 165)
        SurgicalSession.systolic = (sbpBase + drift * 0.7f).roundToInt().coerceIn(60, 180)
        SurgicalSession.diastolic = (SurgicalSession.systolic * 0.61f).roundToInt().coerceIn(35, 110)
        SurgicalSession.spo2 = (spo2Base + SignalEngine.jitter(t * 0.4f, 9) * 0.8f)
            .roundToInt().coerceIn(80, 100)
        SurgicalSession.etco2 = (36 + SignalEngine.jitter(t * 0.3f, 5) * 2.2f - if (bleeding) 4f else 0f)
            .roundToInt().coerceIn(20, 60)

        // 告警判级：进入用高阈值、退出用低阈值（回滞），避免数值在阈值附近反复穿越导致状态灯抖动
        val hr = SurgicalSession.heartRate
        val sbp = SurgicalSession.systolic
        val spo2 = SurgicalSession.spo2
        val criticalIn = hr > 112 || sbp < 90 || spo2 < 94
        val criticalOut = hr <= 106 && sbp >= 96 && spo2 >= 95
        val watchIn = hr > 96 || sbp < 100 || spo2 < 96
        val watchOut = hr <= 90 && sbp >= 104 && spo2 >= 97

        val level = when (lastAlarm) {
            AlarmLevel.CRITICAL -> when {
                criticalOut -> if (watchIn) AlarmLevel.WATCH else AlarmLevel.NONE
                else -> AlarmLevel.CRITICAL
            }
            AlarmLevel.WATCH -> when {
                criticalIn -> AlarmLevel.CRITICAL
                watchOut -> AlarmLevel.NONE
                else -> AlarmLevel.WATCH
            }
            AlarmLevel.NONE -> when {
                criticalIn -> AlarmLevel.CRITICAL
                watchIn -> AlarmLevel.WATCH
                else -> AlarmLevel.NONE
            }
        }

        if (level != lastAlarm) {
            SurgicalSession.alarm = level
            android.util.Log.i(
                "SurgeryClock",
                "alarm=$level t=${t.toInt()}s hr=${SurgicalSession.heartRate} sbp=${SurgicalSession.systolic} spo2=${SurgicalSession.spo2} bleed=$bleeding"
            )
            SurgicalSession.alarmText = when {
                level == AlarmLevel.CRITICAL && bleeding -> "术野渗血：心率升、收缩压降"
                level == AlarmLevel.CRITICAL -> "生命体征超阈，请确认术野"
                level == AlarmLevel.WATCH -> "生命体征临界，持续观察"
                else -> ""
            }
            if (level == AlarmLevel.CRITICAL) {
                SurgicalSession.log("告警：${SurgicalSession.alarmText}", critical = true)
            } else if (level == AlarmLevel.NONE && lastAlarm == AlarmLevel.CRITICAL) {
                SurgicalSession.log("体征回稳，告警解除")
            }
            lastAlarm = level
        }

        // 联动：告警时把影像窗切到出血相关切层并锁 ROI
        if (SurgicalSession.linkMode == LinkMode.FULL) {
            SurgicalSession.linkedSliceHighlight = level == AlarmLevel.CRITICAL
            if (level == AlarmLevel.CRITICAL && SurgicalSession.sliceIndex != 46) {
                SurgicalSession.sliceIndex = 46
            }
        }

        val targetPhase = when {
            SurgicalSession.elapsedSec < 20 -> ProceduralPhase.INDUCTION
            SurgicalSession.elapsedSec < 140 -> ProceduralPhase.DISSECTION
            SurgicalSession.elapsedSec < 220 -> ProceduralPhase.CLIP
            else -> ProceduralPhase.CLOSURE
        }
        if (targetPhase != SurgicalSession.phase) {
            SurgicalSession.phase = targetPhase
            SurgicalSession.log("阶段推进：${targetPhase.label}")
        }
    }
}
