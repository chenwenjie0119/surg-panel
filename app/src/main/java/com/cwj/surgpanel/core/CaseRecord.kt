package com.cwj.surgpanel.core

/**
 * 病历条目：一条可在空间里被单独调阅的 2D 资料。
 *
 * 之所以只放少量“关键行”而不是整份病历，是因为术中术者需要的是
 * 能在 2~3 秒内读完的结论，而不是需要滚动翻阅的长文档。
 */
data class RecordEntry(
    val id: String,
    val title: String,
    val meta: String,
    val tag: String,
    val keyLines: List<String>
)

/**
 * 术前调阅资料库。
 *
 * 全部为本地静态数据：术中不联网、不依赖院内 HIS，
 * 保证“看一眼就关”的调阅动作没有加载等待与网络不确定因素。
 */
object CaseRecordBook {

    private val entries = listOf(
        RecordEntry(
            id = "ct-coronary",
            title = "冠脉 CT · 半年前",
            meta = "2026-03-14 · 外院影像 1 份",
            tag = "影像",
            keyLines = listOf(
                "左前降支中段钙化斑块，管腔狭窄约 40%。",
                "右冠状动脉未见明显狭窄，心肌桥阴性。",
                "建议术中避免长时间低血压，维持舒张压。"
            )
        ),
        RecordEntry(
            id = "allergy",
            title = "过敏史 · 已双人核对",
            meta = "2026-11-07 术前访视",
            tag = "过敏",
            keyLines = listOf(
                "青霉素：皮疹（中度），术中禁用。",
                "碘造影剂：无明确过敏史。",
                "头孢类需皮试阴性后使用。"
            )
        ),
        RecordEntry(
            id = "coag",
            title = "凝血 + 血常规 · 今晨",
            meta = "2026-11-08 06:20 · 本院检验",
            tag = "检验",
            keyLines = listOf(
                "Hb 118 g/L，PLT 188×10⁹/L。",
                "PT 12.4 s，INR 1.06，纤维蛋白原 3.1 g/L。",
                "Hb 较昨日下降 6 g/L，术中关注出血量。"
            )
        ),
        RecordEntry(
            id = "summary",
            title = "术前小结 · 重点",
            meta = "OR-2026-1107-03 · 主刀已阅",
            tag = "小结",
            keyLines = listOf(
                "诊断：胆囊结石伴慢性胆囊炎。",
                "术式：腹腔镜胆囊切除术，预计 60 min。",
                "风险点：胆囊三角粘连，注意胆管损伤。"
            )
        )
    )

    /** 默认条目：只说“病历”而不指定资料时展示术前小结。 */
    private val fallback = entries.last()

    fun all(): List<RecordEntry> = entries

    fun byId(id: String): RecordEntry = entries.firstOrNull { it.id == id } ?: fallback

    /**
     * 按识别文本挑选条目。
     *
     * 只做关键词归并，不做语义推断：命中不了就回落到术前小结，
     * 保证“喊一句就有东西看”，而不是弹出空面板。
     */
    fun pick(text: String): RecordEntry {
        val norm = text.lowercase()
        return when {
            norm.contains("过敏") -> byId("allergy")
            norm.contains("凝血") || norm.contains("血常规") || norm.contains("化验") -> byId("coag")
            norm.contains("ct") || norm.contains("冠脉") || norm.contains("影像") -> byId("ct-coronary")
            else -> fallback
        }
    }

    fun current(): RecordEntry = byId(SurgicalSession.recordEntryId)
}
