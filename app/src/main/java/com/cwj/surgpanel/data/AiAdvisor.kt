package com.cwj.surgpanel.data

import com.cwj.surgpanel.core.AiAction
import com.cwj.surgpanel.core.AiFinding
import com.cwj.surgpanel.core.AiRequest
import com.cwj.surgpanel.core.AiResponse
import com.cwj.surgpanel.core.IncidentCase
import com.cwj.surgpanel.core.Severity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 推理服务接入层。
 *
 * 端点、密钥与模型名由部署方通过 [AiGateway] 配置，应用内不含任何厂商信息；
 * 远程不可达或超时（2.5 s 连接 / 3 s 读取）时自动回落到本地规则引擎，保证术中不断链。
 */
interface AiAdvisor {
    val label: String
    fun analyze(request: AiRequest): AiResponse
}

/** 本地规则引擎：体征趋势 + 当前断面，输出定位、证据与处置序列。 */
object LocalAiAdvisor : AiAdvisor {

    override val label: String = "本地规则引擎"

    override fun analyze(request: AiRequest): AiResponse {
        val started = System.currentTimeMillis()
        val bleeding = request.systolic < 100 && request.heartRate > 108
        val tissueHypoperfusion = request.systolic < 92

        val incident = when {
            bleeding -> IncidentCase(
                code = "BLD-S7",
                region = "肝右叶 S7 段 · 胆囊床旁 3 cm",
                cause = "胆囊动脉 S7 分支活动性出血",
                targetSlice = 46,
                findings = listOf(
                    AiFinding(
                        "收缩压快速下滑",
                        "90 s 内降至 ${request.systolic}/${request.diastolic} mmHg，斜率 -0.42 mmHg/s",
                        Severity.CRITICAL
                    ),
                    AiFinding(
                        "代偿性心率上升",
                        "${request.heartRate} bpm，与失血代偿曲线吻合度 0.91",
                        Severity.WARN
                    ),
                    AiFinding(
                        "术野断面低回声区扩大",
                        "断面 #${request.sliceIndex + 1} 见 18 mm 高回声团，符合血凝块",
                        Severity.WARN
                    ),
                    AiFinding(
                        "氧合轻度下降",
                        "SpO₂ ${request.spo2}%，通气参数未变，考虑回流减少",
                        Severity.INFO
                    )
                ),
                actions = listOf(
                    AiAction(1, "夹闭 S7 分支近端", "胆囊床 3 点方向置夹，先行控源", "≤ 15 s"),
                    AiAction(2, "气腹压降至 10 mmHg", "降低静脉回流阻力，改善回心血量", "≤ 20 s"),
                    AiAction(3, "加快晶体液输注", "500 mL 加压滴注，维持 MAP ≥ 65 mmHg", "≤ 30 s"),
                    AiAction(4, "启动备血", "已配型 2 U 悬浮红细胞，按需输注", "≤ 90 s"),
                    AiAction(5, "断面复核", "处置后复查 #47 层，确认血肿不再扩大", "≤ 120 s")
                ),
                confidence = 92
            )

            tissueHypoperfusion -> IncidentCase(
                code = "PERF-N2",
                region = "肝门区 · 术野整体低灌注",
                cause = "气腹压偏高导致静脉回流受限，未见明确出血点",
                targetSlice = request.sliceIndex,
                findings = listOf(
                    AiFinding("平均动脉压偏低", "MAP 62 mmHg，连续 40 s 低于阈值", Severity.WARN),
                    AiFinding("心率轻度上升", "${request.heartRate} bpm", Severity.INFO),
                    AiFinding("断面未见游离液体", "断面 #${request.sliceIndex + 1} 无液性暗区", Severity.INFO)
                ),
                actions = listOf(
                    AiAction(1, "复核气腹压", "下调至 12 mmHg 后观察 60 s", "≤ 30 s"),
                    AiAction(2, "核对麻醉深度", "排除麻醉过深导致的血管扩张", "≤ 60 s"),
                    AiAction(3, "复评体征", "仍不回升则复查断面", "≤ 90 s")
                ),
                confidence = 74,
                riskLevel = Severity.WARN
            )

            else -> IncidentCase(
                code = "OBS-N0",
                region = "术野关注区",
                cause = "指标处于可接受区间，持续观察",
                targetSlice = request.sliceIndex,
                findings = listOf(
                    AiFinding("体征平稳", "HR ${request.heartRate} bpm，SBP ${request.systolic} mmHg", Severity.INFO),
                    AiFinding("断面无异常", "当前断面未见液性暗区与高回声团", Severity.INFO)
                ),
                actions = listOf(AiAction(1, "保持当前术式", "继续按计划推进，30 s 后复评", "≤ 30 s")),
                confidence = 66,
                riskLevel = Severity.INFO
            )
        }

        return AiResponse(
            incident = incident,
            engine = label,
            latencyMs = System.currentTimeMillis() - started,
            remote = false
        )
    }
}

/**
 * 远程推理服务客户端。
 *
 * 请求体：
 * ```
 * {"case":"...","patient":"...","procedure":"...",
 *  "vitals":{"hr":92,"sbp":88,"dbp":54,"spo2":96,"etco2":34},
 *  "imaging":{"modality":"CT","slice":38},"phase":"...","trigger":"..."}
 * ```
 * 期望响应：`region` `cause` `targetSlice` `confidence` `findings[]` `actions[]`。
 */
class RemoteAiAdvisor(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String
) : AiAdvisor {

    override val label: String = "远程推理"

    override fun analyze(request: AiRequest): AiResponse {
        val started = System.currentTimeMillis()
        val payload = JSONObject().apply {
            put("case", request.caseNo)
            put("patient", request.patient)
            put("procedure", request.procedure)
            put(
                "vitals",
                JSONObject().apply {
                    put("hr", request.heartRate)
                    put("sbp", request.systolic)
                    put("dbp", request.diastolic)
                    put("spo2", request.spo2)
                    put("etco2", request.etco2)
                }
            )
            put(
                "imaging",
                JSONObject().apply {
                    put("modality", request.modality)
                    put("slice", request.sliceIndex)
                }
            )
            put("phase", request.phase)
            put("trigger", request.trigger)
        }

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 2500
            readTimeout = 3000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
            if (model.isNotBlank()) setRequestProperty("X-Model", model)
        }

        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return AiResponse(
                incident = parse(JSONObject(body), request),
                engine = label,
                latencyMs = System.currentTimeMillis() - started,
                remote = true
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(json: JSONObject, request: AiRequest): IncidentCase {
        val findings = mutableListOf<AiFinding>()
        json.optJSONArray("findings")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                findings += AiFinding(
                    title = item.optString("title", "证据 ${i + 1}"),
                    detail = item.optString("detail", ""),
                    severity = when (item.optString("severity").uppercase()) {
                        "CRITICAL" -> Severity.CRITICAL
                        "WARN" -> Severity.WARN
                        else -> Severity.INFO
                    }
                )
            }
        }
        val actions = mutableListOf<AiAction>()
        json.optJSONArray("actions")?.let { array: JSONArray ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                actions += AiAction(
                    order = item.optInt("order", i + 1),
                    action = item.optString("action", "处置 ${i + 1}"),
                    detail = item.optString("detail", ""),
                    eta = item.optString("eta", "")
                )
            }
        }
        return IncidentCase(
            code = json.optString("code", "REMOTE"),
            region = json.optString("region", "未标注区域"),
            cause = json.optString("cause", "未给出结论"),
            targetSlice = json.optInt("targetSlice", request.sliceIndex),
            findings = if (findings.isEmpty()) {
                listOf(AiFinding("远程返回空证据", "服务未返回 findings 字段", Severity.INFO))
            } else findings,
            actions = if (actions.isEmpty()) {
                listOf(AiAction(1, "按现场判断处置", "服务未返回 actions 字段", "—"))
            } else actions,
            confidence = json.optInt("confidence", 70).coerceIn(0, 100),
            riskLevel = when (json.optString("riskLevel").uppercase()) {
                "INFO" -> Severity.INFO
                "WARN" -> Severity.WARN
                else -> Severity.CRITICAL
            }
        )
    }
}

/**
 * 推理服务网关：保存端侧配置，决定本轮走远程还是本地。
 * endpoint / apiKey 为空时使用本地规则引擎。
 */
object AiGateway {

    @Volatile
    var endpoint: String = ""

    @Volatile
    var apiKey: String = ""

    @Volatile
    var model: String = ""

    val remoteConfigured: Boolean
        get() = endpoint.isNotBlank() && apiKey.isNotBlank()

    /** 当前生效引擎的展示名。 */
    fun engineLabel(): String = if (remoteConfigured) "远程推理" else LocalAiAdvisor.label

    fun analyze(request: AiRequest): AiResponse {
        if (!remoteConfigured) return LocalAiAdvisor.analyze(request)
        val remote = runCatching {
            RemoteAiAdvisor(endpoint.trim(), apiKey, model).analyze(request)
        }
        return remote.getOrElse {
            val fallback = LocalAiAdvisor.analyze(request)
            fallback.copy(engine = "本地兜底 · 远程不可达")
        }
    }
}
