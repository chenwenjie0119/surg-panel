package com.cwj.surgpanel.core

import com.cwj.surgpanel.CONSOLE_ID
import com.cwj.surgpanel.FOCUS_ID
import com.cwj.surgpanel.IMAGING_ID
import com.cwj.surgpanel.SCOPE_ID
import com.cwj.surgpanel.VITALS_ID

/**
 * 单个窗口在空间中的设计位姿（世界坐标，单位米）。
 *
 * 坐标系：右手系，原点为手术指挥台中心，+X 指向术者右侧，+Y 向上，+Z 指向术者。
 * 窗口平面法向恒为 +Z（正对术者）。
 */
data class PanelPose(
    val id: String,
    /** 雷达图与坐标行使用的两字母代号。 */
    val code: String,
    val name: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val halfW: Float,
    val halfH: Float
) {
    fun coordText(): String = "%.2f / %.2f / %.2f".format(x, y, z)

    fun distanceText(): String = "%.2f m".format(kotlin.math.sqrt(x * x + y * y + z * z))
}

/**
 * 五窗空间位姿模型。
 *
 * 尺寸与排布严格对齐 [com.cwj.surgpanel.mainApp] 中的 defaultSize 与 Placement：
 * 控制台为基准面，影像窗贴其右侧（26dp），体征窗贴其上方（26dp），
 * 内窥镜窗贴其左侧（26dp），聚焦窗贴影像窗右侧（26dp）。
 *
 * dp → 米按 1dp ≈ 1.05mm 折算（WorldScale.Dynamic 下的可读尺度），
 * 用于雷达图与视线求交；仅作空间示意与命中判定的统一标尺。
 */
object WindowPoseModel {

    private const val DP = 0.00105f
    private const val GAP_DP = 26f

    private fun m(dp: Float): Float = dp * DP

    /** 控制台：1080 x 640 dp，位于术者正前方 1.60 m。 */
    val console: PanelPose by lazy {
        PanelPose(
            id = CONSOLE_ID, code = "CN", name = "指挥台",
            x = 0f, y = 1.32f, z = -1.60f,
            halfW = m(1080f) / 2f, halfH = m(640f) / 2f
        )
    }

    /** 影像窗：860 x 700 dp，控制台右侧 26dp。 */
    val imaging: PanelPose by lazy {
        PanelPose(
            id = IMAGING_ID, code = "IM", name = "影像窗",
            x = (m(1080f) + m(GAP_DP) + m(860f)) / 2f, y = 1.30f, z = -1.62f,
            halfW = m(860f) / 2f, halfH = m(700f) / 2f
        )
    }

    /** 体征窗：780 x 440 dp，控制台上方 26dp。 */
    val vitals: PanelPose by lazy {
        PanelPose(
            id = VITALS_ID, code = "VT", name = "体征窗",
            x = 0f, y = 1.32f + (m(640f) + m(GAP_DP) + m(440f)) / 2f, z = -1.60f,
            halfW = m(780f) / 2f, halfH = m(440f) / 2f
        )
    }

    /** 内窥镜窗：720 x 640 dp，控制台左侧 26dp。 */
    val scope: PanelPose by lazy {
        PanelPose(
            id = SCOPE_ID, code = "SC", name = "内窥镜",
            x = -(m(1080f) + m(GAP_DP) + m(720f)) / 2f, y = 1.30f, z = -1.62f,
            halfW = m(720f) / 2f, halfH = m(640f) / 2f
        )
    }

    /** 聚焦窗：940 x 760 dp，影像窗右侧 26dp。 */
    val focus: PanelPose by lazy {
        PanelPose(
            id = FOCUS_ID, code = "FO", name = "聚焦窗",
            x = imaging.x + imaging.halfW + m(GAP_DP) + m(940f) / 2f,
            y = 1.30f, z = -1.64f,
            halfW = m(940f) / 2f, halfH = m(760f) / 2f
        )
    }

    fun all(): List<PanelPose> = listOf(console, imaging, vitals, scope, focus)

    fun byId(id: String): PanelPose? = all().firstOrNull { it.id == id }

    /** 术者眼位（世界坐标），用于雷达极坐标与视线求交的兜底。 */
    val observerX: Float = 0f
    val observerY: Float = 1.55f
    val observerZ: Float = 0f
}
