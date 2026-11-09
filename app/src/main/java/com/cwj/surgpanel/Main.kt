package com.cwj.surgpanel

import androidx.compose.ui.unit.dp
import com.cwj.surgpanel.ui.console.ConsolePage
import com.cwj.surgpanel.ui.focus.FocusPanel
import com.cwj.surgpanel.ui.imaging.ImagingPanel
import com.cwj.surgpanel.ui.record.RecordPanel
import com.cwj.surgpanel.ui.scope.ScopePanel
import com.cwj.surgpanel.ui.vitals.VitalsPanel
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import com.pico.spatial.ui.platform.resize.ContainerResizeType

const val CONSOLE_ID = "surgical-console"
const val IMAGING_ID = "imaging-panel"
const val VITALS_ID = "vitals-panel"
const val SCOPE_ID = "scope-panel"
const val FOCUS_ID = "focus-panel"
const val RECORD_ID = "record-panel"

/**
 * 应用入口：一个默认控制台窗口 + 三个可自由拖拽/缩放的浮动面板。
 * 三个面板以控制台为锚点分别吸附在右、上、左三侧，用户仍可用系统手势任意移动与缩放。
 */
fun mainApp(scope: SpatialAppScope) = with(scope) {
    DefaultWindowContainer {
        PicoTheme {
            ConsolePage()
        }
    }

    // 影像面板：吸附在控制台右侧
    WindowContainer(
        id = IMAGING_ID,
        form = Form.Planar,
        defaultSize = WindowContainerSize(860.dp, 700.dp),
        resizeType = ContainerResizeType.ContentMinSize,
        enableMaterialBackground = true,
        placement = {
            containers.firstOrNull { it.id == CONSOLE_ID }
                ?.let { Placement.right(it, 26.dp) }
                ?: Placement.none()
        },
    ) {
        PicoTheme { ImagingPanel() }
    }

    // 生命体征面板：吸附在控制台上方
    WindowContainer(
        id = VITALS_ID,
        form = Form.Planar,
        defaultSize = WindowContainerSize(780.dp, 440.dp),
        resizeType = ContainerResizeType.ContentMinSize,
        enableMaterialBackground = true,
        placement = {
            containers.firstOrNull { it.id == CONSOLE_ID }
                ?.let { Placement.top(it, 26.dp) }
                ?: Placement.none()
        },
    ) {
        PicoTheme { VitalsPanel() }
    }

    // 内窥镜面板：吸附在控制台左侧
    WindowContainer(
        id = SCOPE_ID,
        form = Form.Planar,
        defaultSize = WindowContainerSize(720.dp, 640.dp),
        resizeType = ContainerResizeType.ContentMinSize,
        enableMaterialBackground = true,
        placement = {
            containers.firstOrNull { it.id == CONSOLE_ID }
                ?.let { Placement.left(it, 26.dp) }
                ?: Placement.none()
        },
    ) {
        PicoTheme { ScopePanel() }
    }

    // 智能异常处置聚焦窗：出现在控制台正前方，异常发生时由控制台自动前置
    WindowContainer(
        id = FOCUS_ID,
        form = Form.Planar,
        defaultSize = WindowContainerSize(940.dp, 760.dp),
        resizeType = ContainerResizeType.ContentMinSize,
        enableMaterialBackground = true,
        placement = {
            containers.firstOrNull { it.id == IMAGING_ID }
                ?.let { Placement.top(it, 30.dp) }
                ?: containers.firstOrNull { it.id == CONSOLE_ID }
                    ?.let { Placement.right(it, 26.dp) }
                ?: Placement.none()
        },
    ) {
        PicoTheme { FocusPanel() }
    }

    // 无接触病历面板：默认不开窗，由注视或语音唤出后置于影像窗右侧
    WindowContainer(
        id = RECORD_ID,
        form = Form.Planar,
        defaultSize = WindowContainerSize(560.dp, 400.dp),
        resizeType = ContainerResizeType.ContentMinSize,
        enableMaterialBackground = true,
        placement = {
            containers.firstOrNull { it.id == IMAGING_ID }
                ?.let { Placement.right(it, 26.dp) }
                ?: containers.firstOrNull { it.id == CONSOLE_ID }
                    ?.let { Placement.right(it, 26.dp) }
                ?: Placement.none()
        },
    ) {
        PicoTheme { RecordPanel() }
    }
}
