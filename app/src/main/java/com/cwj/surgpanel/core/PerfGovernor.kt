package com.cwj.surgpanel.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 视觉预算守门器。
 *
 * 职责边界（硬约束，勿越界）：
 * 1. 本对象只回答“画面画多快、波形采样多密”，不承载也不参与任何业务判定；
 * 2. [SessionClock]、[AiController] 与告警判级一律不引用本对象，核心时钟永远满血运行；
 * 3. 降级只作用于 Canvas 重绘频率与历史波形采样步长，数值文本与红色告警框不参与降级；
 * 4. 不申请、不持有任何后台线程，绝不干预报警线程的存活。
 *
 * 触发依据只有两类外部信号：电池状态（低电量 / 省电模式）与渲染帧耗时滑动均值。
 */
object PerfGovernor {

    /**
     * 视觉等级。数值越小越完整。
     *
     * [visualHz] 决定 Canvas 重建频率，[waveStride] 决定历史波形点的采样步长（像素）。
     */
    enum class Level(val label: String, val visualHz: Int, val waveStride: Float) {
        FULL("FULL", 60, 2f),
        BALANCED("BAL", 30, 3.4f),
        LOW("LOW", 20, 5f);

        val redrawIntervalMs: Float get() = 1000f / visualHz
    }

    /** 当前视觉等级，仅 UI 层读取。 */
    var level by mutableStateOf(Level.FULL)
        private set

    /** 电量百分比，未知时为 -1。 */
    var batteryPercent by mutableStateOf(-1)
        private set

    /** 是否处于系统省电模式。 */
    var powerSave by mutableStateOf(false)
        private set

    /** 是否在充电。 */
    var charging by mutableStateOf(true)
        private set

    /** 渲染帧耗时滑动均值（毫秒），普通字段，避免每帧写入 Compose 快照。 */
    @Volatile
    var avgFrameMs: Float = 0f
        private set

    /**
     * 降级/回升确认窗口。
     *
     * 原先按“帧数”计数，在 60Hz 与 20Hz 下代表的真实时长相差三倍，
     * 这里统一换成累计帧耗时（毫秒），确认时间与刷新率解耦。
     */
    private const val WORSE_CONFIRM_MS = 400f
    private const val BETTER_CONFIRM_MS = 4000f
    private var worseMs = 0f
    private var betterMs = 0f
    private var attached = false
    private var appContext: Context? = null
    private var receiver: android.content.BroadcastReceiver? = null

    /**
     * 上一次被采信的帧号。
     *
     * 五个悬浮窗各自持有时钟，同一帧会被重复上报；只采信每帧第一次上报，
     * 否则帧间隔会被算成 0，再被 EMA 拉低，导致提前降级。
     */
    @Volatile
    private var lastFrameNs = 0L

    /** 挂接电池广播，只读系统状态，不改任何设置。 */
    fun attach(context: Context) {
        if (attached) return
        attached = true
        val app = context.applicationContext
        appContext = app
        val batteryReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val i = intent ?: return
                val pct = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (pct >= 0 && scale > 0) {
                    batteryPercent = (pct * 100f / scale).toInt().coerceIn(0, 100)
                }
                when (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                    BatteryManager.BATTERY_STATUS_CHARGING,
                    BatteryManager.BATTERY_STATUS_FULL -> charging = true
                    else -> charging = false
                }
                val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
                powerSave = pm?.isPowerSaveMode ?: false
                evaluate(0f)
            }
        }
        runCatching {
            app.registerReceiver(batteryReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            })
            receiver = batteryReceiver
        }
        runCatching {
            val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerSave = pm?.isPowerSaveMode ?: false
        }
    }

    /**
     * 帧耗时上报。由视觉时钟在每帧回调，内部仅做一次滑动平均与去抖，
     * 不写 Compose 状态，因此不会引起额外重组。
     *
     * @param frameNs Compose 的帧时间戳（[androidx.compose.runtime.withFrameNanos] 的 now）。
     *   多个悬浮窗同帧上报时传同一个值，第二次起直接丢弃，保证 EMA 只吃真实帧间隔。
     *   传 0 表示跳过帧号去重（供外部手工上报使用）。
     */
    fun reportFrame(frameMs: Float, frameNs: Long = 0L) {
        if (!frameMs.isFinite() || frameMs <= 0f) return
        if (frameNs != 0L) {
            if (frameNs == lastFrameNs) return
            lastFrameNs = frameNs
        }
        val dt = frameMs.coerceAtMost(200f)
        avgFrameMs = if (avgFrameMs <= 0f) dt else avgFrameMs * 0.94f + dt * 0.06f
        evaluate(dt)
    }

    /** 注销电池广播，避免进程内重复 attach 造成接收器泄漏。 */
    fun detach() {
        val r = receiver ?: return
        runCatching { appContext?.unregisterReceiver(r) }
        receiver = null
        appContext = null
        attached = false
    }

    /** 只读判定：当前条件下应处的等级。 */
    private fun decideLevel(): Level = when {
        powerSave -> Level.LOW
        !charging && batteryPercent in 0..12 -> Level.LOW
        avgFrameMs > 26f -> Level.LOW
        !charging && batteryPercent in 0..22 -> Level.BALANCED
        avgFrameMs > 19f -> Level.BALANCED
        else -> Level.FULL
    }

    /** 电源类硬条件：属于系统事实，不走帧时间去抖。 */
    private fun systemPressure(): Boolean = powerSave || (!charging && batteryPercent in 0..12)

    private fun evaluate(dtMs: Float) {
        val raw = decideLevel()
        if (raw == level) {
            worseMs = 0f
            betterMs = 0f
            return
        }
        if (raw.ordinal > level.ordinal) {
            // 省电模式 / 低电量立即降级：这是系统状态，不是渲染抖动。
            if (systemPressure()) {
                level = raw
                worseMs = 0f
                betterMs = 0f
                return
            }
            worseMs += dtMs
            betterMs = 0f
            if (worseMs >= WORSE_CONFIRM_MS) {
                level = raw
                worseMs = 0f
            }
        } else {
            betterMs += dtMs
            worseMs = 0f
            if (betterMs >= BETTER_CONFIRM_MS) {
                // 逐级回升，不跳级，避免亮度突变干扰术者。
                level = Level.values()[(level.ordinal - 1).coerceAtLeast(0)]
                betterMs = 0f
            }
        }
    }

    /** 状态行文案，供控制台展示。 */
    fun statusText(): String {
        val battery = if (batteryPercent >= 0) "$batteryPercent%" else "--"
        val frame = if (avgFrameMs > 0f) "${avgFrameMs.toInt()}ms" else "--"
        return "${level.label} · ${level.visualHz}Hz · 电量 $battery · 帧 $frame"
    }
}
