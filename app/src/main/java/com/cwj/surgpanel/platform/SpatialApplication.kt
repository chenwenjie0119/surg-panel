package com.cwj.surgpanel.platform

import android.app.Application
import com.cwj.surgpanel.core.PerfGovernor
import com.cwj.surgpanel.mainApp
import com.pico.spatial.ui.foundation.dsl.launch

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
        // 核心时钟先起：告警判定与时间轴在任何视觉优化之前就已满速运行。
        SessionClock.start()
        // 视觉预算守门器只读取电池与帧耗时，不注册线程、不干预上述时钟。
        PerfGovernor.attach(this)
        // 眼动与手势通道，仅提供交互事件。
        GazePinchController.attach(this)
        // 音效通道：纯听觉提示，异常就地降级为静默，不影响上述任何链路。
        SurgicalAudio.attach(this)
    }
}
