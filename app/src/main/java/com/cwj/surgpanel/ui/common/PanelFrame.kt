package com.cwj.surgpanel.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cwj.surgpanel.R
import com.cwj.surgpanel.core.AlarmLevel
import com.cwj.surgpanel.core.PerfGovernor
import com.cwj.surgpanel.core.SurgicalSession
import com.cwj.surgpanel.ui.theme.Palette
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.LocalSpatialContainerStateManager
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 统一的医疗面板外框：标题条 + 强调色 + 焦点状态 + 内容区。
 * 焦点状态取自 Spatial SDK 的容器状态管理器，用于呈现窗口的 onstage/focus 语义。
 *
 * @param gazeHot 视线驻留命中本窗时为 true，此时加粗描边并在标题条亮起 GAZE 标记。
 */
@Composable
fun PanelFrame(
    title: String,
    tag: String,
    accent: Color,
    gazeHot: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val stateManager = LocalSpatialContainerStateManager.current
    val focused by stateManager.isFocused
    // 全息光晕的强脉冲只在红色警报时点亮；视线驻留次之；其余保持极淡的静态边缘
    val alert = SurgicalSession.alarm == AlarmLevel.CRITICAL

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Palette.Void)
                .border(
                    width = if (gazeHot) 2.dp else 1.dp,
                    color = when {
                        gazeHot -> Palette.Cyan.copy(alpha = 0.92f)
                        focused -> accent.copy(alpha = 0.55f)
                        else -> Palette.Stroke
                    }
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(if (gazeHot) Palette.Cyan.copy(alpha = 0.08f) else Palette.PanelSoft)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(width = 3.dp, height = 20.dp)
                        .background(if (gazeHot) Palette.Cyan else accent)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    color = Palette.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = tag,
                    color = Palette.TextDim,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(12.dp))
                StatusDot(active = focused, color = accent)
                if (gazeHot) {
                    Spacer(Modifier.width(10.dp))
                    StatusDot(active = true, color = Palette.Cyan, label = "GAZE")
                }
                Spacer(Modifier.weight(1f))
                trailing?.invoke(this)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Palette.Stroke)
            )
            Column(Modifier.fillMaxSize().padding(14.dp), content = content)
        }

        HoloEdgeOverlay(accent = accent, gazeHot = gazeHot, alert = alert)
    }
}

/** 小圆点状态灯。 */
@Composable
fun StatusDot(active: Boolean, color: Color, label: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (active) color else Palette.StrokeBright)
        )
        if (label != null) {
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                color = if (active) color else Palette.TextDim,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/** 自绘胶囊按钮：避免依赖额外组件样式，交互反馈可控。 */
@Composable
fun ChipButton(
    label: String,
    active: Boolean,
    accent: Color = Palette.Cyan,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val border = when {
        !enabled -> Palette.Stroke
        active -> accent
        else -> Palette.StrokeBright
    }
    val fill = when {
        !enabled -> Palette.Panel
        active -> accent.copy(alpha = 0.16f)
        else -> Palette.PanelSoft
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(fill)
            .border(1.dp, border, RoundedCornerShape(7.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = when {
                !enabled -> Palette.TextDim.copy(alpha = 0.5f)
                active -> accent
                else -> Palette.TextBody
            },
            fontSize = 12.sp
        )
    }
}

/** 前方主操作按钮：整行可点，用于一键触发类动作。 */
@Composable
fun PrimaryButton(
    title: String,
    subtitle: String,
    accent: Color = Palette.Red,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) accent.copy(alpha = 0.14f) else Palette.Panel)
            .border(
                1.dp,
                if (enabled) accent.copy(alpha = 0.7f) else Palette.Stroke,
                RoundedCornerShape(9.dp)
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 30.dp)
                .background(if (enabled) accent else Palette.Stroke)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) Palette.TextPrimary else Palette.TextDim,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(3.dp))
            Text(text = subtitle, color = Palette.TextDim, fontSize = 10.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text(text = ">", color = if (enabled) accent else Palette.TextDim, fontSize = 16.sp)
    }
}

/** 键值数据行。 */
@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color = Palette.TextPrimary) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = key, color = Palette.TextDim, fontSize = 11.sp)
        Text(text = value, color = valueColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

/** 分组标题。 */
@Composable
fun SectionLabel(text: String, accent: Color = Palette.Cyan) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 10.dp, height = 1.dp).background(accent))
        Spacer(Modifier.width(6.dp))
        Text(text = text, color = accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

/**
 * 视觉时钟：按 [PerfGovernor] 给出的视觉预算输出连续秒数。
 *
 * 与 [com.cwj.surgpanel.platform.SessionClock] 完全无关：本时钟只驱动画面，
 * 步进使用真实经过时间（不是固定步长），因此降频后动画速度不变，只是采样变粗。
 *
 * 全进程只有一条帧循环（[VisualClockDriver]）：五个面板共用同一份秒数与同一个帧回调。
 * 若每个面板各起一条 withFrameNanos 循环，同一帧会被多份回调重复采样——既重复上报帧耗时
 * （降级判定的平均值被同一帧灌多次），也让帧回调数量随面板数线性膨胀。
 * 驱动在被引用时启动、无人引用时取消，因此面板全部收起后不残留空转。
 */
@Composable
fun rememberVisualClock(active: Boolean = true): State<Float> {
    DisposableEffect(active) {
        if (active) VisualClockDriver.attach()
        onDispose { if (active) VisualClockDriver.detach() }
    }
    return VisualClockDriver.seconds
}

/**
 * 订阅全局唯一的帧回调。
 *
 * 逐帧动画（拖尾采样、数值平滑等）一律走这里，禁止再各自起 `withFrameNanos` 循环：
 * 多份循环会让同一帧被多次回调采样，既重复上报帧耗时，也让各面板的动相位互不同步。
 * 全进程只有 [VisualClockDriver] 内的一条帧循环，所有消费者在这里登记/注销。
 */
@Composable
fun rememberFrameCallback(onFrame: (Long) -> Unit) {
    val latest by rememberUpdatedState(onFrame)
    DisposableEffect(Unit) {
        val listener: (Long) -> Unit = { now -> latest(now) }
        VisualClockDriver.subscribe(listener)
        onDispose { VisualClockDriver.unsubscribe(listener) }
    }
}

/**
 * 视觉时钟的唯一驱动。
 *
 * 只在主线程调用（组合与 dispose 都在主线程），因此引用计数无需加锁。
 */
private object VisualClockDriver {

    val seconds = mutableFloatStateOf(0f)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var users = 0

    /**
     * 逐帧订阅者。全进程共享同一条帧循环，这里只登记消费端。
     *
     * 只在主线程增删（组合与 dispose 都在主线程），因此无需加锁。
     */
    private val frameListeners = ArrayList<(Long) -> Unit>(8)

    fun attach() {
        users++
        ensureRunning()
    }

    fun detach() {
        users = (users - 1).coerceAtLeast(0)
        stopIfIdle()
    }

    fun subscribe(listener: (Long) -> Unit) {
        frameListeners.add(listener)
        ensureRunning()
    }

    fun unsubscribe(listener: (Long) -> Unit) {
        frameListeners.remove(listener)
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (users > 0 || frameListeners.isNotEmpty()) return
        job?.cancel()
        // prev / lastEmit 都是协程内的局部量，重启协程即从 0 开始，
        // 因此停机期间不会在下次挂载时被误算成一帧的耗时。
        job = null
    }

    private fun ensureRunning() {
        if (job?.isActive == true) return
        job = scope.launch {
            var prev = 0L
            var lastEmit = 0L
            while (true) {
                withFrameNanos { now ->
                    if (prev != 0L) {
                        // 每帧只上报一次：这是降级判定唯一的帧耗时来源。
                        // 所有面板共用这一条循环，因此不存在同帧被多份时钟重复上报的问题。
                        PerfGovernor.reportFrame((now - prev) / 1_000_000f, now)
                        val intervalMs = PerfGovernor.level.redrawIntervalMs
                        if (lastEmit == 0L) {
                            lastEmit = now
                        } else if ((now - lastEmit) / 1_000_000f >= intervalMs) {
                            seconds.floatValue +=
                                ((now - lastEmit) / 1_000_000_000f).coerceIn(0f, 0.25f)
                            lastEmit = now
                        }
                    } else {
                        lastEmit = now
                    }
                    prev = now
                    // 逐帧消费者统一分发（拖尾采样、数值平滑等），它们不再自建帧循环
                    if (frameListeners.isNotEmpty()) {
                        val snapshot = frameListeners.toTypedArray()
                        snapshot.forEach { it(now) }
                    }
                }
            }
        }
    }
}

/**
 * 数值平滑器：把外部变化缓慢插值到当前值，避免波形幅值与图表出现跳变。
 *
 * 注意：只作用于“画面上跟随的中间量”。插值目标始终是业务真值，收敛后与真值一致；
 * 告警判级、红框与事件流一律读真值，不受平滑影响。
 */
@Composable
fun rememberSmoothFloat(target: Float, factor: Float = 0.18f): Float {
    val latest by rememberUpdatedState(target)
    var value by remember { mutableFloatStateOf(target) }
    rememberFrameCallback {
        val goal = latest
        val delta = goal - value
        value = if (abs(delta) < 0.01f) goal else value + delta * factor
    }
    return value
}

/**
 * 麦克风按钮：小尺寸话筒图形 + 状态文案。
 * 只负责发出“开始聆听”的意图，识别与命令判定在业务层完成。
 */
@Composable
fun MicButton(
    listening: Boolean,
    accent: Color = Palette.Red,
    onClick: () -> Unit
) {
    val tint = if (listening) accent else Palette.TextBody
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (listening) accent.copy(alpha = 0.22f) else Palette.PanelSoft)
            .border(
                1.dp,
                if (listening) accent else Palette.StrokeBright,
                RoundedCornerShape(7.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(11.dp)) {
            val cx = size.width / 2f
            val h = size.height
            drawRoundRect(
                color = tint,
                topLeft = Offset(cx - 2.2f, 0f),
                size = Size(4.4f, h * 0.56f),
                cornerRadius = CornerRadius(2.2f, 2.2f)
            )
            drawLine(tint, Offset(cx, h * 0.56f), Offset(cx, h * 0.82f), strokeWidth = 1.3f)
            drawLine(tint, Offset(cx - 3.2f, h * 0.84f), Offset(cx + 3.2f, h * 0.84f), strokeWidth = 1.3f)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (listening) stringResource(R.string.panel_01) else "MIC",
            color = tint,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * 窗口边缘全息层：四边渐变光晕 + 内描边亮线 + 四角刻线。
 *
 * 视觉克制（P2）：
 * 1. 扫描线与频闪呼吸默认全关：待命、视线驻留态均为完全静态边缘，界面不再出现持续游走的亮线；
 *    仅红色警报保留一次极低频呼吸（周期约 1.8s、亮度振幅 8%），用于传达事件本身而非装饰；
 * 2. 四边渐变 Brush 按强调色与边缘厚度缓存，每帧只递交 alpha，逐帧零 Brush 分配；
 * 3. 不使用实时高斯模糊，全部用线性渐变的纯色填充模拟发光，GPU 代价与普通矩形填充同级；
 * 4. 帧时钟只在“红色警报”时启动，其余状态协程直接退出，不产生任何帧回调；
 * 5. 该层不挂任何指针输入修饰符，不影响窗口拖拽、缩放与内容点击。
 */
@Composable
private fun HoloEdgeOverlay(accent: Color, gazeHot: Boolean, alert: Boolean) {
    val tint = when {
        alert -> Palette.Red
        gazeHot -> Palette.Cyan
        else -> accent
    }
    val strength = when {
        alert -> 1f
        gazeHot -> 0.85f
        else -> 0.35f
    }
    // 只有红色警报才启动帧时钟；active=false 时协程立即退出，不订阅帧回调
    val clock = rememberVisualClock(active = alert)
    val edgePx = with(androidx.compose.ui.platform.LocalDensity.current) { 30.dp.toPx() }
    // 渐变端点只依赖强调色与边缘厚度：缓存四个 Brush，每帧仅调整 alpha
    val brushes = remember(tint, edgePx) {
        listOf(
            Brush.verticalGradient(listOf(tint, Color.Transparent), startY = 0f, endY = edgePx),
            Brush.verticalGradient(listOf(Color.Transparent, tint), startY = 0f, endY = edgePx),
            Brush.horizontalGradient(listOf(tint, Color.Transparent), startX = 0f, endX = edgePx),
            Brush.horizontalGradient(listOf(Color.Transparent, tint), startX = 0f, endX = edgePx)
        )
    }

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val edge = edgePx.coerceAtMost(minOf(w, h) * 0.16f)
        // 警报时的极低频呼吸：周期约 1.8s、亮度振幅 8%，读取发生在绘制阶段
        val breath = if (alert) 0.92f + 0.08f * ((sin(clock.value * 3.49f) + 1f) / 2f) else 1f
        val glow = (if (alert || gazeHot) 0.16f else 0.07f) * strength * breath

        drawRect(brush = brushes[0], size = Size(w, edge), alpha = glow)
        drawRect(brush = brushes[1], topLeft = Offset(0f, h - edge), size = Size(w, edge), alpha = glow)
        drawRect(brush = brushes[2], size = Size(edge, h), alpha = glow * 0.8f)
        drawRect(brush = brushes[3], topLeft = Offset(w - edge, 0f), size = Size(edge, h), alpha = glow * 0.8f)

        // 内描边亮线
        drawRect(
            color = tint.copy(alpha = 0.22f * strength * breath),
            topLeft = Offset(1f, 1f),
            size = Size((w - 2f).coerceAtLeast(0f), (h - 2f).coerceAtLeast(0f)),
            style = Stroke(width = 1.4f)
        )

        // 四角刻线
        val tick = 18.dp.toPx()
        val tickColor = tint.copy(alpha = 0.32f * strength)
        drawLine(tickColor, Offset(0f, 0f), Offset(0f, tick), strokeWidth = 1.6f)
        drawLine(tickColor, Offset(0f, 0f), Offset(tick, 0f), strokeWidth = 1.6f)
        drawLine(tickColor, Offset(w, 0f), Offset(w, tick), strokeWidth = 1.6f)
        drawLine(tickColor, Offset(w, 0f), Offset(w - tick, 0f), strokeWidth = 1.6f)
        drawLine(tickColor, Offset(0f, h), Offset(0f, h - tick), strokeWidth = 1.6f)
        drawLine(tickColor, Offset(0f, h), Offset(tick, h), strokeWidth = 1.6f)
        drawLine(tickColor, Offset(w, h), Offset(w, h - tick), strokeWidth = 1.6f)
        drawLine(tickColor, Offset(w, h), Offset(w - tick, h), strokeWidth = 1.6f)
    }
}