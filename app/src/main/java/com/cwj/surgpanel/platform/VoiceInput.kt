package com.cwj.surgpanel.platform

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cwj.surgpanel.core.VoiceCommandRouter
import java.util.Locale

/**
 * 系统语音识别封装。
 *
 * 只做“取一句中文话术”这一件事：识别结果交给 [VoiceCommandRouter] 判定，
 * 命令匹配与告警动作全部在实际业务层完成，本类不持有任何业务状态。
 */
object VoiceInput {

    /** 是否有识别进行中，供麦克风按钮转圈。 */
    var busy by mutableStateOf(false)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(context: Context): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    /**
     * 开始一次识别。
     *
     * @param onText 识别到文本后回调（已做去空白处理）。
     * @param onStatus 状态文案回调，用于控制台展示。
     */
    fun listen(context: Context, onText: (String) -> Unit, onStatus: (String) -> Unit) {
        if (busy) return
        if (!isAvailable(context)) {
            VoiceCommandRouter.onUnavailable("此设备无系统语音识别服务，请用口令面板")
            onStatus("无系统语音识别服务")
            return
        }
        busy = true
        VoiceCommandRouter.onListening()
        main.post {
            runCatching {
                if (recognizer == null) {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
                }
                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        onStatus("正在聆听…")
                    }

                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit

                    override fun onError(error: Int) {
                        busy = false
                        val reason = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未听清，请再说一次"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别引擎忙，稍后再试"
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "识别服务网络异常"
                            else -> "识别失败（$error）"
                        }
                        VoiceCommandRouter.onError(reason)
                        onStatus(reason)
                    }

                    override fun onResults(results: Bundle?) {
                        busy = false
                        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = list?.firstOrNull()?.trim().orEmpty()
                        if (text.isEmpty()) {
                            VoiceCommandRouter.onError("未听清，请再说一次")
                            onStatus("未听清")
                        } else {
                            onText(text)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    }
                }
                recognizer?.startListening(intent)
            }.onFailure { error ->
                busy = false
                val reason = "识别引擎启动失败：${error.message ?: "未知原因"}"
                VoiceCommandRouter.onError(reason)
                onStatus(reason)
            }
        }
    }

    /** 主动中止一次识别（例如术者放弃）。 */
    fun cancel() {
        runCatching { recognizer?.cancel() }
        busy = false
    }
}
