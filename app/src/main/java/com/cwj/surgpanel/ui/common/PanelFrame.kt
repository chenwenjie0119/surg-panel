package com.cwj.surgpanel.ui.common

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cwj.surgpanel.ui.theme.Palette
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.LocalSpatialContainerStateManager

/**
 * 统一的医疗面板外框：标题条 + 强调色 + 焦点状态 + 内容区。
 * 焦点状态取自 Spatial SDK 的容器状态管理器，用于呈现窗口的 onstage/focus 语义。
 */
@Composable
fun PanelFrame(
    title: String,
    tag: String,
    accent: Color,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val stateManager = LocalSpatialContainerStateManager.current
    val focused by stateManager.isFocused

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Void)
            .border(1.dp, if (focused) accent.copy(alpha = 0.55f) else Palette.Stroke)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(Palette.PanelSoft)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 20.dp)
                    .background(accent)
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

/** 帧驱动的秒计时，用于波形与纹理的连续动画。 */
@Composable
fun rememberAnimatedSeconds(): Float {
    var seconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var prev = 0L
        while (true) {
            withFrameNanos { now ->
                if (prev != 0L) {
                    seconds += ((now - prev) / 1_000_000_000f).coerceIn(0f, 0.1f)
                }
                prev = now
            }
        }
    }
    return seconds
}
