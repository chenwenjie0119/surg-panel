package com.cwj.surgpanel.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cwj.surgpanel.R
import com.cwj.surgpanel.core.AlarmLevel
import com.cwj.surgpanel.core.SurgicalSession

/**
 * 空间音效通道。
 *
 * 职责边界（硬约束）：
 * 1. 音效只是听觉提示，不参与任何业务判定，也不回写体征或告警等级；
 * 2. 用独立的低优先级守护线程按真实时间拍点，绝不占用 [SessionClock] 的 100 ms 节拍，
 *    即使音频线程整体异常退出，监护判定与处置链路照常满速运行；
 * 3. 音源缺失、解码失败、播放器异常一律就地捕获并降级为静默（仅打日志），
 *    不允许任何异常冒泡到主线程造成闪退。
 *
 * 音源为本地轻量 PCM（res/raw）：监护滴声、告警急促音、界面确认音。
 */
object SurgicalAudio {

    private const val TAG = "SurgAudio"

    /** 拍点检查步长：远小于最快告警节拍，保证节拍误差不超过一个步长。 */
    private const val TICK_MS = 15L

    /** CRITICAL 下的告警节拍。 */
    private const val ALARM_INTERVAL_MS = 165L

    /** 音效总开关，默认开启；由控制台切换。 */
    var enabled by mutableStateOf(true)

    /** 播放器是否就绪。UI 只做展示，不参与判定。 */
    var ready by mutableStateOf(false)
        private set

    /** 通道状态说明，供控制台展示。 */
    var statusNote by mutableStateOf("音频通道待初始化")
        private set
    private var pool: SoundPool? = null
    private var beepSample = 0
    private var alarmSample = 0
    private var confirmSample = 0
    private var started = false
    private var mockLogged = false

    /** 挂接音频通道。重复调用无副作用。 */
    fun attach(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val soundPool = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(attrs)
                .build()
            soundPool.setOnLoadCompleteListener { _, sampleId, status ->
                if (status != 0) Log.w(TAG, "音源加载失败 sid=$sampleId status=$status")
            }
            beepSample = soundPool.load(app, R.raw.monitor_beep, 1)
            alarmSample = soundPool.load(app, R.raw.alarm_urgent, 1)
            confirmSample = soundPool.load(app, R.raw.ui_confirm, 1)
            pool = soundPool
            ready = true
            statusNote = "监护滴声 · 告警急促音 · 确认音"
            Log.i(TAG, "音频通道就绪 beep=$beepSample alarm=$alarmSample confirm=$confirmSample")
        } catch (t: Throwable) {
            pool = null
            ready = false
            statusNote = "音频不可用，已降级为静默"
            Log.w(TAG, "音频初始化失败，业务链路不受影响", t)
        }
        startBeatLoop()
    }

    /** 界面确认音：语音口令命中、处置确认等交互回执。 */
    fun confirm() {
        play(confirmSample, volume = 0.55f, rate = 1f)
    }

    /** 释放播放器。仅在进程收尾时调用，异常同样就地吞掉。 */
    fun release() {
        try {
            pool?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "释放音频通道异常，已忽略", t)
        }
        pool = null
        ready = false
    }

    /**
     * 节拍线程：按心率排出监护滴声的节奏，CRITICAL 时切为急促尖锐的告警音。
     *
     * 只读取 [SurgicalSession] 的心率与告警等级，不写任何状态；
     * 线程体整体包一层异常保护，任何未预期异常都只记录日志并退出，不影响其它链路。
     */
    private fun startBeatLoop() {
        Thread({
            try {
                var nextBeatAt = System.nanoTime()
                while (true) {
                    Thread.sleep(TICK_MS)
                    if (!enabled || !ready) {
                        nextBeatAt = System.nanoTime()
                        continue
                    }
                    val now = System.nanoTime()
                    if (now < nextBeatAt) continue

                    val level = SurgicalSession.alarm
                    val heartRate = SurgicalSession.heartRate.coerceIn(30, 220)
                    when (level) {
                        AlarmLevel.CRITICAL -> {
                            // 双声道满音量 + 高音区，音源本身已是双段尖锐音
                            play(alarmSample, volume = 0.95f, rate = 1f)
                            nextBeatAt = now + ALARM_INTERVAL_MS * 1_000_000L
                        }
                        AlarmLevel.WATCH -> {
                            play(beepSample, volume = 0.6f, rate = 1.12f)
                            nextBeatAt = now + (60_000f / heartRate * 0.86f).toLong() * 1_000_000L
                        }
                        // NOTICE 属于抗疲劳设计的一部分：只留正常监护滴声，不额外报警
                        AlarmLevel.NONE, AlarmLevel.NOTICE -> {
                            play(beepSample, volume = 0.45f, rate = 1f)
                            nextBeatAt = now + (60_000f / heartRate).toLong() * 1_000_000L
                        }
                    }
                }
            } catch (t: InterruptedException) {
                Log.i(TAG, "节拍线程结束")
            } catch (t: Throwable) {
                Log.w(TAG, "节拍线程异常退出，业务链路不受影响", t)
            }
        }, "surgical-audio").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }.start()
    }

    /** 播放单元：音源缺失时走 Mock（只打日志），播放异常就地忽略。 */
    private fun play(sampleId: Int, volume: Float, rate: Float) {
        val soundPool = pool
        if (soundPool == null || sampleId == 0) {
            if (!mockLogged) {
                mockLogged = true
                Log.i(TAG, "mock: 无可用音源，跳过播放 sample=$sampleId vol=$volume rate=$rate")
            }
            return
        }
        try {
            val result = soundPool.play(
                sampleId,
                volume.coerceIn(0f, 1f),
                volume.coerceIn(0f, 1f),
                1,
                0,
                rate.coerceIn(0.5f, 2f)
            )
            if (result == 0) Log.w(TAG, "播放返回 0，音源可能尚未加载完成 sample=$sampleId")
        } catch (t: Throwable) {
            Log.w(TAG, "播放异常已忽略 sample=$sampleId", t)
        }
    }
}
