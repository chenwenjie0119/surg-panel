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
 * 1. 在主线程协程里以 [delay] 逐字推进：Compose 状态只由主线程写，调度权交还给帧循环；
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

    /** 锁：串行化 [ensureWorker]，避免多线程同时判定“worker 空闲”而各起一个协程。 */
    private val workerLock = Any()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
        // 与 ensureWorker 共用同一把锁。若把「取消 + 置空」留在锁外，可能与
        // ensureWorker 刚启动的协程交叉：协程仍在跑，句柄却被置空，
        // 下一次 stream 会再起一个，两路同时改写 lines，表现为同一段文字被重复输出。
        synchronized(workerLock) {
            token++
            queue.clear()
            worker?.cancel()
            worker = null
        }
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

    /**
     * 确保播报协程在跑。
     *
     * 竞态点：`worker?.isActive` 判定与 `scope.launch` 之间不是原子操作，而播报请求可能同时来自
     * 主线程（点击）与手术时钟线程（体征越限自动触发）。两路同时判空会各起一个协程，两个协程
     * 交替改写 [lines]，表现为同一段文字被重复输出、字数回退。这里用锁把「判定 + 启动」做成一个
     * 原子段；协程体在 [Dispatchers.Main.immediate] 上首次挂起前即让出锁，不会造成阻塞。
     */
    private fun ensureWorker() {
        val launchToken: Int
        synchronized(workerLock) {
            if (worker?.isActive == true) return
            launchToken = token
            val job = scope.launch {
                drain(launchToken)
            }
            worker = job
            // 协程正常跑完（队列空）或异常结束时主动摘掉句柄：
            // 否则 worker 会一直指向一个已完成的任务，[Job.isActive] 判定虽仍正确，
            // 但它把「句柄是否为空」这个信号彻底废掉，后续排查并发问题时极易误判。
            job.invokeOnCompletion {
                synchronized(workerLock) {
                    if (worker === job) worker = null
                }
            }
        }
    }

    /** 逐字消费队列，直到队列空或轮次令牌失效。 */
    private suspend fun drain(myToken: Int) {
        typing = true
        val buffer = lines.toMutableList()
        while (myToken == token) {
            val item = queue.poll() ?: break
            buffer.add(NarrationLine(item.first, "", false))
            lines = buffer.toList()

            val builder = StringBuilder()
            for (ch in item.second) {
                if (myToken != token) return
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

/** 处置建议播报器。 */
object AiNarrator : Narrator()

/**
 * 病历调阅播报器。
 *
 * 独立实例：术者翻看病历摘要时，处置建议的流式输出不会被清屏或覆盖；
 * 逐字更快、行间更短，符合“快速翻阅”的阅读节奏。
 */
object RecordNarrator : Narrator(charDelayMs = 15L, punctPauseMs = 85L, lineGapMs = 55L)
