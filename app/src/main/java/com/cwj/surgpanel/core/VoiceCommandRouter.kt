package com.cwj.surgpanel.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 语音链路的可视状态。 */
enum class VoiceState(val label: String) {
    IDLE("待命"),
    LISTENING("聆听中"),
    HEARD("已识别"),
    UNAVAILABLE("无识别服务"),
    DENIED("未授权麦克风"),
    ERROR("识别失败")
}

/** 语音意图：每条口令对应一个确定动作，匹配失败时一律不改动任何业务状态。 */
enum class VoiceIntent(val label: String, val sample: String) {
    BLEED_INSPECT("查看出血点", "立刻查看出血点"),
    OPEN_RECORD("调阅病历", "调出患者半年前的冠脉 CT"),
    CLOSE_RECORD("关闭病历", "关闭"),
    START_BLOCKADE("开始阻断", "开始阻断"),
    STOP_BLOCKADE("结束阻断", "结束阻断"),
    SYNC_TEACHING("同步教学端", "同步教学端")
}

/**
 * 术中语音指令路由。
 *
 * 语音只做“意图入口”，最终仍走 [AiController] 的同一条处置链路，
 * 因此语音触发的告警与定位与自动触发在行为上完全一致，不引入第二套判定。
 */
object VoiceCommandRouter {

    /** 核心口令：立刻查看出血点。 */
    const val COMMAND_BLEED = "立刻查看出血点"

    var state by mutableStateOf(VoiceState.IDLE)
        private set

    /** 最近一次识别文本。 */
    var lastText by mutableStateOf("")
        private set

    /** 最近一次命中的口令时间点（手术时间轴秒）。 */
    var lastHitSec by mutableIntStateOf(-1)
        private set

    /** 命中计数，控制台据此把聚焦窗前置到术者面前。 */
    var hitSeq by mutableIntStateOf(0)
        private set

    /** 麦克风上一次状态说明，展示在控制台的语音区。 */
    var statusNote by mutableStateOf("等待语音指令")
        private set

    /** 最近一次命中的归一化文本，用于区分“同一条口令重复上报”与“连续两条不同口令”。 */
    private var lastNorm = ""

    fun onListening() {
        state = VoiceState.LISTENING
        statusNote = "正在聆听…"
    }

    /** 识别引擎自身的进度文案（聆听中 / 未听清 / 权限缺失等）由识别层回填。 */
    fun note(text: String) {
        statusNote = text
    }

    fun onUnavailable(reason: String = "设备未提供系统语音识别服务") {
        state = VoiceState.UNAVAILABLE
        statusNote = reason
    }

    fun onDenied() {
        state = VoiceState.DENIED
        statusNote = "需要麦克风权限，授权后再点一次"
    }

    fun onError(message: String) {
        state = VoiceState.ERROR
        statusNote = message
    }

    fun onHeardText(text: String) {
        lastText = text
        statusNote = "识别到：$text"
    }

    /**
     * 口令匹配与派发。
     *
     * @param text 语音识别文本或手工口令按钮文本。
     * @param source 来源说明（写入事件流，便于追溯是麦克风还是口令面板触发）。
     * @return 是否命中并已执行。
     */
    fun handle(text: String, source: String): Boolean {
        val norm = normalize(text)
        if (norm.isEmpty()) return false
        val low = norm.lowercase()
        onHeardText(text)
        val intent = matchIntent(low)
        android.util.Log.i(
            "SurgeryVoice",
            "text=$text src=$source intent=$intent t=${SurgicalSession.elapsedSec}s"
        )
        if (intent == null) {
            statusNote = "未匹配指令：$text"
            return false
        }
        // 去重：识别层可能对同一句话连续回调，2 s 内文本完全相同的重复命中按同一事件处理；
        // 换了一条口令则必须立即执行，否则术者说“开始阻断”后 2 s 内喊“结束阻断”会被吞掉。
        val nowSec = SurgicalSession.elapsedSec
        if (hitSeq > 0 && norm == lastNorm && nowSec - lastHitSec < 2) {
            statusNote = "同一条口令已在执行，忽略重复上报"
            return true
        }
        lastNorm = norm
        lastHitSec = nowSec
        state = VoiceState.HEARD
        hitSeq++
        when (intent) {
            VoiceIntent.BLEED_INSPECT -> inspectBleed(text, source)
            VoiceIntent.OPEN_RECORD -> openRecord(text, source)
            VoiceIntent.CLOSE_RECORD -> closeRecord(source)
            VoiceIntent.START_BLOCKADE -> startBlockade()
            VoiceIntent.STOP_BLOCKADE -> stopBlockade()
            VoiceIntent.SYNC_TEACHING -> syncTeaching()
        }
        return true
    }

    /** 出血点查看：注入渗血事件并立即红警，不等下一拍数据推演。 */
    private fun inspectBleed(text: String, source: String) {
        SurgicalSession.manualBleed = true
        SurgicalSession.alarm = AlarmLevel.CRITICAL
        SurgicalSession.alarmText = "术野出血点待查（语音指令）"
        // 体征尚未越限时，判定逻辑不得在下一拍就把等级拉回，给出一个短保持窗口
        SurgicalSession.forceCriticalUntilMs =
            android.os.SystemClock.elapsedRealtime() + CRITICAL_HOLD_MS
        SurgicalSession.log("语音指令（$source）：$text → 立即定位出血点", critical = true)
        AiController.triggerVoiceInspect(text, source)
    }

    /** 无接触调阅：选好条目、置状态并拉前面板，内容由面板自己流式产出。 */
    private fun openRecord(text: String, source: String) {
        val entry = CaseRecordBook.pick(text)
        SurgicalSession.recordEntryId = entry.id
        SurgicalSession.recordOpen = true
        SurgicalSession.recordSeq++
        SurgicalSession.log("语音指令（$source）：调阅病历「${entry.title}」")
    }

    private fun closeRecord(source: String) {
        if (!SurgicalSession.recordOpen) {
            statusNote = "病历面板未展开"
            return
        }
        SurgicalSession.recordOpen = false
        SurgicalSession.recordSeq++
        RecordNarrator.reset()
        SurgicalSession.log("语音指令（$source）：关闭病历面板")
    }

    private fun startBlockade() {
        if (SurgicalSession.blockadeRunning) {
            statusNote = "阻断已在进行中，剩余 ${SurgicalSession.blockadeLabel()}"
            return
        }
        SurgicalSession.blockadeSec = 0
        SurgicalSession.blockadeRunning = true
        SurgicalSession.log("语音指令：开始血管阻断，启动 15 min 倒计时")
    }

    private fun stopBlockade() {
        if (!SurgicalSession.blockadeRunning) {
            statusNote = "当前未处于阻断状态"
            return
        }
        SurgicalSession.blockadeRunning = false
        SurgicalSession.log("语音指令：结束阻断，本次阻断 ${SurgicalSession.blockadeSec} s")
    }

    /**
     * 教学 / 会诊同步：语音与面板按钮共用同一入口。
     *
     * @param source 触发来源，写入事件流便于追溯。
     */
    fun syncTeaching(source: String = "口令面板") {
        if (SurgicalSession.teachingSync) {
            statusNote = "教学端已在同步中（${SurgicalSession.teachingPeers} 端）"
            return
        }
        SurgicalSession.teachingSync = true
        SurgicalSession.teachingPeers = 3
        SurgicalSession.log("已同步至教学端 · 3 个接入点（来源：$source，主刀第一视角）")
    }

    /** 全角转半角、去空白与标点，只保留用于匹配的字符。 */
    private fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val c = when {
                ch.code == 0x3000 -> ' '
                ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                else -> ch
            }
            if (c.isLetterOrDigit()) sb.append(c)
        }
        return sb.toString()
    }

    private fun matchBleed(norm: String): Boolean {
        val bleed = norm.contains("出血") || norm.contains("渗血") || norm.contains("止血点")
        val view = norm.contains("查看") || norm.contains("立刻") ||
            norm.contains("马上") || norm.contains("定位") || norm.contains("看看")
        val bleeding = norm.contains("bleeding", ignoreCase = true) ||
            norm.contains("blood", ignoreCase = true)
        return (bleed && view) || (bleeding && norm.contains("show", ignoreCase = true))
    }

    /**
     * 意图匹配。顺序即优先级：
     * 阻断 → 关闭 → 出血查看 → 教学同步 → 病历调阅，避免同一句话被错的路由抢走。
     *
     * @param norm 已归一化并转小写（英文口令按小写比对，中文不受影响）的识别文本。
     */
    private fun matchIntent(norm: String): VoiceIntent? {
        if (norm.contains("阻断") || norm.contains("阻断带") || norm.contains("pringle")) {
            val stop = norm.contains("结束") || norm.contains("松开") || norm.contains("放开") ||
                norm.contains("停止") || norm.contains("解除") || norm.contains("stop")
            return if (stop) VoiceIntent.STOP_BLOCKADE else VoiceIntent.START_BLOCKADE
        }
        if (norm.contains("关闭") || norm.contains("收起") || norm.contains("勾销")) {
            return VoiceIntent.CLOSE_RECORD
        }
        if (matchBleed(norm)) return VoiceIntent.BLEED_INSPECT
        if (matchTeaching(norm)) return VoiceIntent.SYNC_TEACHING
        if (matchRecord(norm)) return VoiceIntent.OPEN_RECORD
        return null
    }

    /** 教学 / 会诊同步：识别“同步”“教学”“会诊”等意图词，动作由 handle 统一派发。 */
    private fun matchTeaching(norm: String): Boolean {
        val push = norm.contains("同步") || norm.contains("推送") || norm.contains("共享")
        val teaching = norm.contains("教学") || norm.contains("会诊") ||
            norm.contains("学生") || norm.contains("助手")
        return push && teaching
    }

    /**
     * 病历调阅：必须同时出现“资料对象”与“取用动作”，
     * 只说“CT”不会触发，避免术中闲聊误开面板。
     */
    private fun matchRecord(norm: String): Boolean {
        val subject = norm.contains("病历") || norm.contains("病史") || norm.contains("过敏") ||
            norm.contains("化验") || norm.contains("凝血") || norm.contains("血常规") ||
            norm.contains("报告") || norm.contains("冠脉") || norm.contains("ct") ||
            norm.contains("影像资料") || norm.contains("history") || norm.contains("record")
        val action = norm.contains("调出") || norm.contains("调阅") || norm.contains("打开") ||
            norm.contains("查看") || norm.contains("查一下") || norm.contains("看") ||
            norm.contains("open") || norm.contains("show")
        return subject && action
    }

    /** 强制危急的保持时长：够面板完成一次红色闪烁与音效，又不掩盖真实体征回落。 */
    private const val CRITICAL_HOLD_MS = 8_000L
}
