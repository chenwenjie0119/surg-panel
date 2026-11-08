package com.cwj.surgpanel.data

/**
 * 仪器接入层（产品模式）。
 *
 * 手术室内各类设备通过厂商 SDK、串口、HL7 / DICOM 网关或局域网组播接入，
 * 实现方只需把数据封装成下列帧结构并注册到 [InstrumentRegistry]，
 * 顶层面板与推理链路无需改动即可读取真实数据。
 */

/** 一帧监护仪数据。 */
data class VitalsFrame(
    val heartRate: Int,
    val systolic: Int,
    val diastolic: Int,
    val spo2: Int,
    val etco2: Int,
    val timestamp: Long
)

/** 生命体征源：监护仪 / 麻醉机 / 中央站。 */
interface VitalSignSource {
    val label: String
    fun open(): Boolean
    fun poll(): VitalsFrame?
    fun close()
}

/** 影像源：PACS / DICOM 工作站 / 术中 C 臂。 */
interface ImagingSource {
    val label: String
    fun open(): Boolean
    fun sliceCount(): Int
    fun pollSliceIndex(): Int?
    fun close()
}

/** 内窥镜源：术野摄像与镜头参数。 */
interface EndoscopeSource {
    val label: String
    fun open(): Boolean
    fun pollZoom(): Float?
    fun close()
}

/**
 * 仪器注册表。产品模式下由设备接入模块在启动时注册；
 * 未注册任何源时，应用不读取外部数据。
 */
object InstrumentRegistry {

    @Volatile
    var vitals: VitalSignSource? = null

    @Volatile
    var imaging: ImagingSource? = null

    @Volatile
    var endoscope: EndoscopeSource? = null

    fun attachVitals(source: VitalSignSource) {
        vitals = source
    }

    fun attachImaging(source: ImagingSource) {
        imaging = source
    }

    fun attachEndoscope(source: EndoscopeSource) {
        endoscope = source
    }

    fun detachAll() {
        vitals = null
        imaging = null
        endoscope = null
    }

    /** 已接入设备的展示名，未接入显示占位。 */
    fun vitalsLabel(): String = vitals?.label ?: "未接入"
    fun imagingLabel(): String = imaging?.label ?: "未接入"
    fun endoscopeLabel(): String = endoscope?.label ?: "未接入"
}
