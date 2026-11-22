package com.cwj.surgpanel.ui.record

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cwj.surgpanel.R
import com.cwj.surgpanel.core.CaseRecordBook
import com.cwj.surgpanel.core.NarrationTone
import com.cwj.surgpanel.core.RecordNarrator
import com.cwj.surgpanel.core.SurgicalSession
import com.cwj.surgpanel.core.VoiceCommandRouter
import com.cwj.surgpanel.ui.common.ChipButton
import com.cwj.surgpanel.ui.common.NarrationBlock
import com.cwj.surgpanel.ui.common.PanelFrame
import com.cwj.surgpanel.ui.common.SectionLabel
import com.cwj.surgpanel.ui.common.StatusDot
import com.cwj.surgpanel.ui.theme.Palette
import com.pico.spatial.ui.design.Text
import androidx.compose.ui.platform.LocalContext

/**
 * 无接触病历调阅面板。
 *
 * 设计约束：术者的手在无菌区内，任何"点一下屏幕"都等同于污染，
 * 因此本面板的唤出、换条目、关闭三条路径全部可由眼动注视与语音口令完成，
 * 面板内仅保留按钮作为兜底（巡回护士或洗手前使用）。
 *
 * 内容只给关键结论行，由 [RecordNarrator] 逐字流式输出，
 * 与处置建议播报器分属不同实例，翻病历不会冲掉正在输出的处置建议。
 */
@Composable
fun RecordPanel() {
    // 回调/绘制 lambda 不是 @Composable 上下文，改用 Context 资源取带参文案
    val res = LocalContext.current.resources

    // 点击/语音回调不是 @Composable 上下文，文案在此取出后传入
    val text_record_02 = stringResource(R.string.record_02)
    // 点击/语音回调不是 @Composable 上下文，文案在此取出后传入
    val text_record_03 = stringResource(R.string.record_03)
    val entry = CaseRecordBook.current()
    val seq = SurgicalSession.recordSeq

    // 每次唤出（seq 变化）或切换条目都重放一遍：先给结论，再给依据
    LaunchedEffect(seq, entry.id) {
        RecordNarrator.reset()
        entry.keyLines.forEachIndexed { index, line ->
            RecordNarrator.stream(
                tone = if (index == 0) NarrationTone.DETECT else NarrationTone.ANALYZE,
                text = line,
                clearFirst = index == 0
            )
        }
    }

    PanelFrame(
        title = stringResource(R.string.record_07, entry.title),
        tag = SurgicalSession.CASE_NO,
        accent = Palette.Cyan,
        gazeHot = SurgicalSession.recordOpen,
        trailing = {
            StatusDot(active = true, color = Palette.Cyan, label = "NO-TOUCH")
            Spacer(Modifier.width(10.dp))
            StatusDot(
                active = RecordNarrator.typing,
                color = Palette.Amber,
                label = if (RecordNarrator.typing) "STREAMING" else "READY"
            )
            Spacer(Modifier.width(10.dp))
            ChipButton(stringResource(R.string.record_01), false, Palette.Red) {
                VoiceCommandRouter.handle(text_record_02, text_record_03)
            }
        }
    ) {
        SectionLabel(stringResource(R.string.record_04), Palette.Cyan)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaseRecordBook.all().forEach { e ->
                ChipButton(e.tag, e.id == entry.id, Palette.Cyan) {
                    if (SurgicalSession.recordEntryId != e.id) {
                        SurgicalSession.recordEntryId = e.id
                        SurgicalSession.recordSeq++
                        SurgicalSession.log(res.getString(R.string.record_08, e.title))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel(stringResource(R.string.record_09, entry.tag), Palette.Mint)
        Spacer(Modifier.height(6.dp))
        NarrationBlock(
            accent = Palette.Cyan,
            visibleLines = 3,
            fontSize = 12,
            fixedHeight = 120,
            narrator = RecordNarrator
        )

        Spacer(Modifier.height(10.dp))
        Text(
            text = entry.meta,
            color = Palette.TextDim,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = "${SurgicalSession.PATIENT}　·　${SurgicalSession.PROCEDURE}",
            color = Palette.TextBody,
            fontSize = 11.sp
        )

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(7.dp))
                .background(Palette.PanelSoft)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(active = true, color = Palette.Mint, label = stringResource(R.string.record_05))
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.record_06),
                color = Palette.TextBody,
                fontSize = 11.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "NO CONTACT",
                color = Palette.TextDim,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}