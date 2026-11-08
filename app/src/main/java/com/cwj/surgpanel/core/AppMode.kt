package com.cwj.surgpanel.core

/** 运行模式：产品模式接入真实仪器与远程推理服务，演示模式用本地数据源与内置场景。 */
enum class AppMode(val label: String, val hint: String, val badge: String) {
    PRODUCT("产品模式", "接入监护仪 / DICOM / 内窥镜与远程推理服务", "PRODUCT"),
    DEMO("演示模式", "本地数据源 + 内置场景，一键复现完整处置链路", "DEMO")
}
