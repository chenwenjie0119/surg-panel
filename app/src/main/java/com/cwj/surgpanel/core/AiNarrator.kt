package com.cwj.surgpanel.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/** 推理播报的语气分类，仅决定 UI 配色。 */
enum class NarrationTone { ANALYZE, DETECT, ADVISE }

/** 单行播报。[complete] 为 false 表示仍在逐字输出中。 */
data class NarrationLine(
    val tone: NarrationTone,
    val text: String,
    val complete: Boolean
)

/** 标点处的短停顿字符集，让节奏更像在“说话”。 */
private const val PUNCTUATION = "，。、；：！？…—,.;:!?"

/**
 * 流式播报器（打字机）的可复用实现。
 *
 * 实现要点：
 * 1. 全部在 [Dispatchers.Default] 的协程里以 [delay] 逐字推进，不占用、不阻塞任何线程；
 * 2. 与 [SessionClock] 的 100 ms 心跳完全无关——播报慢也只会让文字变慢，
 *    体征漂移、告警判级、阶段推进按原节拍满速运行；
 * 3. 只有读取 [lines] 的文本块会随字符重组，Canvas 与雷达等重型绘制不在其列。
 *
 * 各业务线各持一个实例（处置建议、病历调阅），互不覆盖内容。
 */
open class Narrator(
    private val charDelayMs: Long = 22L,
    private val punctPauseMs: Long = 130L,
    private val lineGapMs: Long = 180L
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = ConcurrentLinkedQueue<Pair<NarrationTone, String>>()

    /** 当前已产出的播报行（含正在输出的那一行）。 */
    var lines by mutableStateOf<List<NarrationLine>>(emptyList())
        private set

    /** 是否仍在逐字输出。 */
    var typing by mutableStateOf(false)
        private set

    @Volatile
    private var worker: Job? = null

    /** 轮次令牌：reset 后旧协程检测到令牌变化即自行退出，避免串台。 */
    @Volatile
    private var token = 0

    /** 清空播报并中止在途协程。 */
    fun reset() {
        token++
        queue.clear()
        worker?.cancel()
        worker = null
        lines = emptyList()
        typing = false
    }

    /**
     * 追加一行播报并在后台逐字输出。
     *
     * @param clearFirst 为 true 时先清空已有内容（新一轮推理开始时使用）。
     */
    fun stream(tone: NarrationTone, text: String, clearFirst: Boolean = false) {
        if (text.isBlank()) return
        if (clearFirst) reset()
        queue.add(tone to text)
        ensureWorker()
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        val myToken = token
        worker = scope.launch {
            typing = true
            val buffer = lines.toMutableList()
            while (myToken == token) {
                val item = queue.poll() ?: break
                buffer.add(NarrationLine(item.first, "", false))
                lines = buffer.toList()

                val builder = StringBuilder()
                for (ch in item.second) {
                    if (myToken != token) return@launch
                    builder.append(ch)
                    buffer[buffer.lastIndex] =
                        NarrationLine(item.first, builder.toString(), false)
                    lines = buffer.toList()
                    delay(if (ch in PUNCTUATION) punctPauseMs else charDelayMs)
                }
                buffer[buffer.lastIndex] =
                    NarrationLine(item.first, builder.toString(), true)
                lines = buffer.toList()
                // 行间留一拍的呼吸间隔
                delay(lineGapMs)
            }
            if (myToken == token) typing = false
        }
    }
}

/** 处置建议播报器。 */
object AiNarrator : Narrator()

/**
 * 病历调阅播报器。
 *
 * 独立实例：术者翻看病历摘要时，处置建议的流式输出不会被清屏或覆盖；
 * 逐字更快、行间更短，符合“快速翻阅”的阅读节奏。
 */
object RecordNarrator : Narrator(charDelayMs = 15L, punctPauseMs = 85L, lineGapMs = 55L)
