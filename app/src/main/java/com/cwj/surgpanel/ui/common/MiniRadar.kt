package com.cwj.surgpanel.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cwj.surgpanel.core.WindowPoseModel
import com.cwj.surgpanel.platform.GazePinchController
import com.cwj.surgpanel.ui.theme.Palette
import com.pico.spatial.ui.design.Text
import kotlin.math.cos
import kotlin.math.sin

/** 雷达半视场半径对应 2.9 m，足够覆盖五窗全部排布。 */
private const val SPAN_M = 2.9f

/** 拖尾采样间隔：约 18 Hz，够看出轨迹又几乎不占视觉预算。 */
private const val TRAIL_SAMPLE_MS = 55L

/** 拖尾寿命：一个采样点从出现到完全淡出的时长。 */
private const val TRAIL_LIFE_MS = 620f

/** 每个窗口保留的历史采样数上限。 */
private const val TRAIL_MAX_POINTS = 10

/** 拖尾采样点：窗口标识 + 世界坐标（米）+ 采样时刻（毫秒）。 */
private class TrailPoint(
    val panelId: String,
    val x: Float,
    val y: Float,
    val stampMs: Long
)

/**
 * 极简全局缩略雷达。
 *
 * 以术者眼位为原点做俯视投影：横轴为空间 X（左右），纵轴为空间 Y（上下），
 * 五个窗口按 [WindowPoseModel] 的真实相对位移与尺寸等比缩小绘制，
 * 视线驻留命中的窗口以青色描边点亮，下方的坐标行给出该窗的实时绝对坐标。
 *
 * 本组件只读状态，不产生任何业务副作用，绘制开销为常数级（5 个矩形 + 3 个圆）。
 */
@Composable
fun MiniRadar(modifier: Modifier = Modifier, diameter: Dp = 124.dp) {
    val hot = GazePinchController.hotPanelId
    val gaze = GazePinchController.gazePanelId
    val activeKey = hot ?: gaze
    val active = activeKey?.let { WindowPoseModel.byId(it) } ?: WindowPoseModel.focus

    Column(modifier) {
        BoxWithConstraints(
            Modifier
                .size(diameter)
                .clip(RoundedCornerShape(diameter / 2))
                .background(Palette.Panel)
                .border(1.dp, Palette.Stroke, RoundedCornerShape(diameter / 2))
        ) {
            val radiusDp: Dp = maxWidth / 2

            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                val cx = size.width / 2f
                val cy = size.height / 2f
                val s = r / SPAN_M

                drawCircle(Palette.Void, radius = r, center = Offset(cx, cy))
                listOf(0.34f, 0.67f, 1f).forEach { k ->
                    drawCircle(
                        Palette.Stroke.copy(alpha = 0.55f),
                        radius = r * k,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1f)
                    )
                }
                drawLine(
                    Palette.Stroke.copy(alpha = 0.5f),
                    Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth = 1f
                )
                drawLine(
                    Palette.Stroke.copy(alpha = 0.5f),
                    Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = 1f
                )

                WindowPoseModel.all().forEach { pose ->
                    val px = cx + (pose.x - WindowPoseModel.observerX) * s
                    val py = cy - (pose.y - WindowPoseModel.observerY) * s
                    val hw = pose.halfW * s
                    val hh = pose.halfH * s
                    val isHot = pose.id == hot
                    val isGaze = pose.id == gaze
                    val stroke = when {
                        isHot -> Palette.Cyan
                        isGaze -> Palette.Cyan.copy(alpha = 0.62f)
                        pose.id == WindowPoseModel.console.id -> Palette.Violet
                        else -> Palette.StrokeBright
                    }
                    drawRoundRect(
                        color = stroke.copy(alpha = if (isHot) 0.3f else 0.13f),
                        topLeft = Offset(px - hw, py - hh),
                        size = Size(hw * 2f, hh * 2f),
                        cornerRadius = CornerRadius(2f, 2f)
                    )
                    drawRoundRect(
                        color = stroke,
                        topLeft = Offset(px - hw, py - hh),
                        size = Size(hw * 2f, hh * 2f),
                        cornerRadius = CornerRadius(2f, 2f),
                        style = Stroke(width = if (isHot) 2f else 1f)
                    )
                    if (isHot) {
                        drawCircle(Palette.TextPrimary, radius = 1.7f, center = Offset(px, py))
                    }
                }

                // 术者位置：底部中心的小三角
                val observer = Path().apply {
                    moveTo(cx, cy - r * 0.12f)
                    lineTo(cx - r * 0.06f, cy + r * 0.02f)
                    lineTo(cx + r * 0.06f, cy + r * 0.02f)
                    close()
                }
                drawPath(observer, Palette.Mint)
            }

            // 光点拖尾层：独立 Canvas 与独立采样协程，主雷达的矩形/圆点绘制不受其刷新影响
            RadarTrail()

            // 代号标注：按同一投影公式定位
            WindowPoseModel.all().forEach { pose ->
                val dxRatio = (pose.x - WindowPoseModel.observerX) / SPAN_M
                val dyRatio = (pose.y - WindowPoseModel.observerY) / SPAN_M
                val selected = pose.id == hot || pose.id == gaze
                Text(
                    text = pose.code,
                    color = if (selected) Palette.Cyan else Palette.TextDim,
                    fontSize = 7.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.offset(
                        x = radiusDp * (1f + dxRatio) - 6.dp,
                        y = radiusDp * (1f - dyRatio) - 4.dp
                    )
                )
            }
        }
    }
}

/**
 * 雷达光点拖尾层。
 *
 * 每个窗口对应一个在窗面内做缓慢游走的光点：按 [WindowPoseModel] 的尺寸取窗面内椭圆振幅，
 * 以固定间隔把当前位置压入历史队列，绘制时按“年龄”把透明度与半径线性递减，
 * 于是得到一条由亮到暗的短尾。
 *
 * 性能隔离：
 * 1. 采样与绘制全部收在本组件内部，历史点存放在本组件私有的 State 中，
 *    每 [TRAIL_SAMPLE_MS] 才整体替换一次（约 18 Hz），不会触发窗口内容重组；
 * 2. 只保留每个窗口最近 [TRAIL_MAX_POINTS] 个采样、并剔除超出 [TRAIL_LIFE_MS] 的点，
 *    队列长度恒定，绘制指令数为常数级；
 * 3. 只读窗口坐标模型，不写任何业务状态。
 */
@Composable
private fun RadarTrail() {
    var points by remember { mutableStateOf<List<TrailPoint>>(emptyList()) }
    var lastSample by remember { mutableLongStateOf(0L) }
    // 复用全局唯一的帧回调，不再自建 withFrameNanos 循环，避免同一帧被多份时钟采样
    rememberFrameCallback { frameNs ->
        val nowMs = frameNs / 1_000_000L
        if (lastSample == 0L) lastSample = nowMs
        if (nowMs - lastSample >= TRAIL_SAMPLE_MS) {
            lastSample = nowMs
            val t = nowMs / 1000f
            val alive = points.filter { nowMs - it.stampMs <= TRAIL_LIFE_MS.toLong() }
            val next = ArrayList<TrailPoint>(alive.size + 5)
            next.addAll(alive)
            WindowPoseModel.all().forEachIndexed { index, pose ->
                val phase = index * 1.27f
                val dx = sin(t * 0.9f + phase) * pose.halfW * 0.6f
                val dy = cos(t * 0.63f + phase * 1.4f) * pose.halfH * 0.6f
                next.add(TrailPoint(pose.id, pose.x + dx, pose.y + dy, nowMs))
            }
            points = next
                .groupBy { it.panelId }
                .flatMap { (_, list) -> list.takeLast(TRAIL_MAX_POINTS) }
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        val r = size.minDimension / 2f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val s = r / SPAN_M
        val nowMs = System.nanoTime() / 1_000_000L

        points.forEach { p ->
            val age = ((nowMs - p.stampMs) / TRAIL_LIFE_MS).coerceIn(0f, 1f)
            val fade = 1f - age
            val px = cx + (p.x - WindowPoseModel.observerX) * s
            val py = cy - (p.y - WindowPoseModel.observerY) * s
            val tint = if (p.panelId == WindowPoseModel.console.id) Palette.Violet else Palette.Cyan
            drawCircle(
                color = tint.copy(alpha = 0.40f * fade * fade),
                radius = 0.5f + 1.8f * fade,
                center = Offset(px, py)
            )
        }

        // 光点心：每个窗口只画最新一点，避免整条拖尾糊成一片
        WindowPoseModel.all().forEach { pose ->
            val head = points.lastOrNull { it.panelId == pose.id } ?: return@forEach
            val px = cx + (head.x - WindowPoseModel.observerX) * s
            val py = cy - (head.y - WindowPoseModel.observerY) * s
            val tint = if (pose.id == WindowPoseModel.console.id) Palette.Violet else Palette.Cyan
            drawCircle(tint.copy(alpha = 0.30f), radius = 4.2f, center = Offset(px, py))
            drawCircle(tint.copy(alpha = 0.9f), radius = 1.7f, center = Offset(px, py))
        }
    }
}

/**
 * 五窗绝对坐标栈：每行一个窗口，格式 `代号 X / Y / Z`（单位 m，原点为指挥台中心）。
 *
 * 视线驻留命中的窗口以青色点亮；本组件只读 [WindowPoseModel] 与眼动状态，无业务副作用。
 */
@Composable
fun CoordStack(fontSize: Int = 8, modifier: Modifier = Modifier) {
    val hot = GazePinchController.hotPanelId
    val gaze = GazePinchController.gazePanelId
    Column(modifier) {
        WindowPoseModel.all().forEach { pose ->
            val selected = pose.id == hot || pose.id == gaze
            Text(
                text = "${pose.code} ${pose.coordText()}",
                color = if (selected) Palette.Cyan else Palette.TextDim,
                fontSize = fontSize.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
