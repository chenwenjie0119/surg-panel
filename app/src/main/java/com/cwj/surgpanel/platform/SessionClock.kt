package com.cwj.surgpanel.platform

import com.cwj.surgpanel.core.AiController
import com.cwj.surgpanel.core.AlarmLevel
import com.cwj.surgpanel.core.AppMode
import com.cwj.surgpanel.core.LinkMode
import com.cwj.surgpanel.core.ProceduralPhase
import com.cwj.surgpanel.core.SignalEngine
import com.cwj.surgpanel.core.SurgicalSession
import com.cwj.surgpanel.data.InstrumentRegistry
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 手术时钟：以固定 100 ms 步长推进全局会话状态（时间轴、体征、告警判定、阶段切换）。
 * 独立的守护线程驱动，因此任一悬浮窗被关闭都不会中断整台手术的数据流。
 *
 * 数据来源随运行模式切换：
 * - 产品模式：优先读取 [InstrumentRegistry] 中已接入的监护仪帧，未接入时回落到本地推演；
 * - 演示模式：始终使用本地推演，保证演示可重复。
 */
object SessionClock {

    private const val BLEED_START = 40
    private const val BLEED_END = 68
    private const val CYCLE = 140

    /** 联合危急的确认拍数：100 ms × 15 = 1.5 s。 */
    private const val FUSION_CONFIRM_TICKS = 15

    /**
     * NOTICE 退出的安静拍数：100 ms × 15 = 1.5 s。
     * 单项阈值（如 SpO2 < 97）在 96/97 间抖动时，按拍判定会让 NOTICE 与 NONE 每
     * 100~200 ms 翻转一次，青点变闪烁。NOTICE 只是静默微提示，进入可灵敏、退出须惰性：
     * 连续 1.5 s 不再命中才回落 NONE。
     */
    private const val NOTICE_QUIET_TICKS = 15

    @Volatile
    private var started = false

    private var lastAlarm = AlarmLevel.NONE

    /** 三联条件连续成立的拍数，用于 1.5 s 确认消抖。 */
    private var fusionHold = 0

    /** 上一次的渗血状态，用于边沿检测（一块纱布只记一次）。 */
    private var lastBleeding = false

    /** NOTICE 条件连续未命中的拍数，用于 1.5 s 退出消抖。 */
    private var noticeQuiet = 0

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

        // 产品模式优先使用仪器源
        val external = if (SurgicalSession.appMode == AppMode.PRODUCT) {
            InstrumentRegistry.vitals?.poll()
        } else {
            null
        }

        val bleeding: Boolean
        if (external != null) {
            SurgicalSession.heartRate = external.heartRate.coerceIn(30, 210)
            SurgicalSession.systolic = external.systolic.coerceIn(40, 230)
            SurgicalSession.diastolic = external.diastolic.coerceIn(20, 140)
            SurgicalSession.spo2 = external.spo2.coerceIn(50, 100)
            SurgicalSession.etco2 = external.etco2.coerceIn(5, 80)
            bleeding = external.systolic < 100 && external.heartRate > 108
        } else {
            // 渗血事件按周期复现：每 140 s 一轮（40–68 s 渗血，其后 20 s 线性回稳），便于反复演示
            val tc = t % CYCLE
            bleeding = (tc >= BLEED_START && tc < BLEED_END) || SurgicalSession.manualBleed
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
        }

        // ---- 多模态联合判定：三条独立证据交叉验证，单项越限只给青色微提示 ----
        val hr = SurgicalSession.heartRate
        val sbp = SurgicalSession.systolic
        val spo2 = SurgicalSession.spo2
        val exudate = SignalEngine.exudateScore(t, bleeding)
        SurgicalSession.scopeExudate = exudate

        val hrHit = hr > 120
        val sbpHit = sbp < 90
        val exudateHit = exudate >= 0.62f
        val hits = (if (hrHit) 1 else 0) + (if (sbpHit) 1 else 0) + (if (exudateHit) 1 else 0)
        SurgicalSession.fusionHits = hits
        SurgicalSession.fusionDetail = buildString {
            append(if (hrHit) "心率↑" else "心率·")
            append(' ')
            append(if (sbpHit) "血压↓" else "血压·")
            append(' ')
            append(if (exudateHit) "渗出+" else "渗出·")
        }

        // 三条件同时成立须连续保持 1.5 s 才升级为危急，单帧噪声不会拉起红色警报
        fusionHold = if (hits == 3) fusionHold + 1 else 0
        val fusionConfirm = hits == 3 && fusionHold >= FUSION_CONFIRM_TICKS

        // 危急：联合三条同时成立，或血氧单项跌破安全线（低氧本身即足以致死，不进联合门槛）
        val criticalIn = fusionConfirm || spo2 < 92
        // 退出须三条证据全部回落（含渗出识别与本次渗血状态），避免刚解除就二次拉响
        val criticalOut = hr <= 112 && sbp >= 95 && exudate < 0.45f && spo2 >= 94
        val watchIn = hits == 2 || hr > 96 || sbp < 100 || spo2 < 96
        val watchOut = hits <= 1 && hr <= 90 && sbp >= 104 && spo2 >= 97
        // 微提示门槛：单项轻度越界即点亮青点，全程无声、不写事件流
        val noticeIn = hits >= 1 || hr > 90 || sbp < 105 || spo2 < 97

        // NOTICE 进入灵敏、退出惰性，详见 NOTICE_QUIET_TICKS 注释
        noticeQuiet = if (noticeIn) 0 else noticeQuiet + 1
        val noticeSettled = noticeQuiet >= NOTICE_QUIET_TICKS

        val forced = android.os.SystemClock.elapsedRealtime() < SurgicalSession.forceCriticalUntilMs
        val level = when {
            forced -> AlarmLevel.CRITICAL
            lastAlarm == AlarmLevel.CRITICAL ->
                if (criticalOut) {
                    when {
                        watchIn -> AlarmLevel.WATCH
                        noticeIn -> AlarmLevel.NOTICE
                        noticeSettled -> AlarmLevel.NONE
                        else -> AlarmLevel.NOTICE
                    }
                } else {
                    AlarmLevel.CRITICAL
                }
            criticalIn -> AlarmLevel.CRITICAL
            lastAlarm == AlarmLevel.WATCH ->
                if (watchOut) {
                    if (noticeIn) AlarmLevel.NOTICE else AlarmLevel.NONE
                } else {
                    AlarmLevel.WATCH
                }
            watchIn -> AlarmLevel.WATCH
            lastAlarm == AlarmLevel.NOTICE ->
                if (noticeSettled) AlarmLevel.NONE else AlarmLevel.NOTICE
            noticeIn -> AlarmLevel.NOTICE
            else -> AlarmLevel.NONE
        }

        if (level != lastAlarm) {
            SurgicalSession.alarm = level
            val exudateTxt = String.format(java.util.Locale.US, "%.2f", exudate)
            android.util.Log.i(
                "SurgeryClock",
                "alarm=$level fusion=$hits[$hrHit,$sbpHit,$exudateHit] mode=${SurgicalSession.appMode.name} " +
                    "t=${t.toInt()}s hr=$hr sbp=$sbp spo2=$spo2 exudate=$exudateTxt " +
                    "bleed=$bleeding instrument=${external != null}"
            )
            SurgicalSession.alarmText = when (level) {
                AlarmLevel.CRITICAL ->
                    if (bleeding) "联合危急：心率↑ + 血压↓ + 渗出识别阳性" else "生命体征超阈，请确认术野"
                AlarmLevel.WATCH -> "多参数临界（$hits/3），密切观察"
                AlarmLevel.NOTICE -> "单项轻度越界，静默记录"
                AlarmLevel.NONE -> ""
            }
            if (level == AlarmLevel.CRITICAL) {
                SurgicalSession.log("告警：${SurgicalSession.alarmText}", critical = true)
                // 产品模式：由数据越限自动启动智能分析；演示模式只走前方测试按钮
                if (SurgicalSession.appMode == AppMode.PRODUCT) {
                    AiController.onAlarmTriggered(level)
                }
            } else if (level == AlarmLevel.NONE && lastAlarm >= AlarmLevel.WATCH) {
                SurgicalSession.log("体征回稳，告警解除")
                AiController.onResolved(fromData = true)
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

        // ---- 关键时限：血管阻断倒计时（语音「开始阻断」启动，满 15 min 自动叫停）----
        if (SurgicalSession.blockadeRunning && tick % 10 == 0) {
            val before = SurgicalSession.blockadeSec
            SurgicalSession.blockadeSec = before + 1
            if (before < SurgicalSession.BLOCKADE_LIMIT_SEC &&
                SurgicalSession.blockadeSec >= SurgicalSession.BLOCKADE_LIMIT_SEC
            ) {
                SurgicalSession.blockadeRunning = false
                SurgicalSession.log("阻断已达 15 min 上限，请立即松开阻断带", critical = true)
            }
        }

        // ---- 耗材与出血量：渗血边沿记一块纱布，出血量按渗血速率累积 ----
        if (bleeding != lastBleeding) {
            lastBleeding = bleeding
            if (bleeding) {
                SurgicalSession.spongeOnField += 1
                SurgicalSession.log("渗血浸透纱布，台上 +1 块，待同步清点")
            }
        }
        SurgicalSession.eblAcc += if (bleeding) 0.55f else 0.015f
        if (SurgicalSession.eblAcc >= 1f) {
            val add = SurgicalSession.eblAcc.toInt()
            SurgicalSession.eblAcc -= add
            SurgicalSession.eblMl += add
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
