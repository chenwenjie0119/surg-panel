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

    private const val WORSE_CONFIRM_FRAMES = 30
    private const val BETTER_CONFIRM_FRAMES = 240
    private var worseFrames = 0
    private var betterFrames = 0
    private var attached = false

    /** 挂接电池广播，只读系统状态，不改任何设置。 */
    fun attach(context: Context) {
        if (attached) return
        attached = true
        val app = context.applicationContext
        val receiver = object : android.content.BroadcastReceiver() {
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
                evaluate()
            }
        }
        runCatching {
            app.registerReceiver(receiver, IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            })
        }
        runCatching {
            val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerSave = pm?.isPowerSaveMode ?: false
        }
    }

    /**
     * 帧耗时上报。由视觉时钟在每帧回调，内部仅做一次滑动平均与去抖，
     * 不写 Compose 状态，因此不会引起额外重组。
     */
    fun reportFrame(frameMs: Float) {
        if (!frameMs.isFinite() || frameMs <= 0f) return
        avgFrameMs = if (avgFrameMs <= 0f) frameMs else avgFrameMs * 0.94f + frameMs * 0.06f
        evaluate()
    }

    private fun evaluate() {
        val raw = when {
            powerSave -> Level.LOW
            !charging && batteryPercent in 0..12 -> Level.LOW
            avgFrameMs > 26f -> Level.LOW
            !charging && batteryPercent in 0..22 -> Level.BALANCED
            avgFrameMs > 19f -> Level.BALANCED
            else -> Level.FULL
        }
        if (raw == level) {
            worseFrames = 0
            betterFrames = 0
            return
        }
        if (raw.ordinal > level.ordinal) {
            worseFrames++
            betterFrames = 0
            if (worseFrames >= WORSE_CONFIRM_FRAMES) {
                level = raw
                worseFrames = 0
            }
        } else {
            betterFrames++
            worseFrames = 0
            if (betterFrames >= BETTER_CONFIRM_FRAMES) {
                // 逐级回升，不跳级，避免亮度突变干扰术者。
                level = Level.values()[(level.ordinal - 1).coerceAtLeast(0)]
                betterFrames = 0
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
