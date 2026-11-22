package com.cwj.surgpanel.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cwj.surgpanel.R
import com.cwj.surgpanel.core.AiNarrator
import com.cwj.surgpanel.core.NarrationLine
import com.cwj.surgpanel.core.NarrationTone
import com.cwj.surgpanel.core.Narrator
import com.cwj.surgpanel.ui.theme.Palette
import com.pico.spatial.ui.design.Text
import kotlinx.coroutines.delay

/**
 * 智能建议的流式文本块（打字机）。
 *
 * 文本由 [AiNarrator] 在后台协程里逐字产出，本组件只读取并渲染最近 [visibleLines] 行，
 * 因此每来一个字只会重组这一小块文本，不会牵动窗口内其它内容与自绘图层。
 *
 * @param fixedHeight 传非空值时固定高度，超出部分只在末行可见区域滚动式替换（保留最近几行）。
 */
@Composable
fun NarrationBlock(
    accent: Color = Palette.Amber,
    visibleLines: Int = 2,
    fontSize: Int = 11,
    fixedHeight: Int? = 64,
    narrator: Narrator = AiNarrator,
    modifier: Modifier = Modifier
) {
    val lines = narrator.lines
    val typing = narrator.typing
    var caretOn by remember { mutableStateOf(true) }

    // 光标以低频翻转，避免为闪烁单独驱动逐帧动画
    LaunchedEffect(typing) {
        caretOn = true
        if (!typing) return@LaunchedEffect
        while (true) {
            delay(430L)
            caretOn = !caretOn
        }
    }

    val shown = remember(lines, visibleLines) { lines.takeLast(visibleLines) }
    val body: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                active = typing,
                color = accent,
                label = if (typing) "STREAMING" else "READY"
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (typing) stringResource(R.string.narration_01) else stringResource(R.string.narration_02),
                color = Palette.TextDim,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(5.dp))
        if (shown.isEmpty()) {
            Text(
                text = stringResource(R.string.narration_03),
                color = Palette.TextDim.copy(alpha = 0.75f),
                fontSize = fontSize.sp
            )
        } else {
            shown.forEach { line -> NarrationRow(line, caretOn, fontSize) }
        }
    }

    if (fixedHeight == null) {
        Column(modifier) { body() }
    } else {
        Column(
            modifier
                .fillMaxWidth()
                .height(fixedHeight.dp)
                .background(Palette.PanelSoft.copy(alpha = 0.55f))
                .border(1.dp, Palette.Stroke)
                .padding(horizontal = 9.dp, vertical = 7.dp)
        ) {
            body()
        }
    }
}

@Composable
private fun NarrationRow(line: NarrationLine, caretOn: Boolean, fontSize: Int) {
    val tint = when (line.tone) {
        NarrationTone.ANALYZE -> Palette.Cyan
        NarrationTone.DETECT -> Palette.Amber
        NarrationTone.ADVISE -> Palette.Mint
    }
    val caret = if (!line.complete && caretOn) "▌" else ""
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(width = 2.dp, height = 11.dp)
                .background(tint.copy(alpha = if (line.complete) 0.5f else 0.95f))
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = line.text + caret,
            color = if (line.complete) Palette.TextBody else Palette.TextPrimary,
            fontSize = fontSize.sp
        )
    }
}