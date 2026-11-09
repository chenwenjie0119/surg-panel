package com.cwj.surgpanel.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * 波形发生器：以纯函数方式合成心电、动脉压、呼吸与血氧脉动波，
 * 采样稳定可复现，便于在多个窗口之间对齐同一条时间轴。
 */
object SignalEngine {

    /** 高斯脉冲，用于合成心电的 P/QRS/T 波。 */
    private fun pulse(t: Float, center: Float, width: Float, amp: Float): Float {
        val d = (t - center) / width
        return amp * exp(-d * d)
    }

    /** 心电波形，周期 = 60/心率 秒，t 为周期内相位（0..1）。 */
    fun ecgPhase(phase: Float, heartRate: Int): Float {
        val p = phase * (heartRate / 60f) % 1f
        var v = 0f
        v += pulse(p, 0.14f, 0.022f, 0.16f)    // P 波
        v += pulse(p, 0.24f, 0.008f, -0.14f)   // Q
        v += pulse(p, 0.27f, 0.010f, 1.0f)     // R
        v += pulse(p, 0.30f, 0.009f, -0.28f)   // S
        v += pulse(p, 0.47f, 0.045f, 0.26f)    // T 波
        return v
    }

    /** 动脉压波形（归一化 0..1）。 */
    fun abpPhase(phase: Float, heartRate: Int): Float {
        val p = phase * (heartRate / 60f) % 1f
        val up = sin(PI.toFloat() * (p / 0.18f).coerceIn(0f, 1f))
        val decay = exp(-((p - 0.18f).coerceAtLeast(0f)) * 9f)
        val dicrotic = pulse(p, 0.42f, 0.05f, 0.16f)
        return (0.35f * up * decay + 0.18f * decay + dicrotic).coerceIn(0f, 1f)
    }

    /** 呼吸波形（12 次/分）。 */
    fun respPhase(phase: Float): Float =
        (sin(2f * PI.toFloat() * phase / 5f) * 0.5f + 0.5f)

    /** 血氧脉搏波。 */
    fun plethPhase(phase: Float, heartRate: Int): Float {
        val p = phase * (heartRate / 60f) % 1f
        val main = pulse(p, 0.22f, 0.075f, 1f)
        val echo = pulse(p, 0.48f, 0.09f, 0.42f)
        return (main + echo).coerceIn(0f, 1f)
    }

    /** 稳定伪噪声（无随机源，形变由相位驱动）。 */
    fun jitter(phase: Float, seed: Int): Float {
        val a = sin(phase * 37.1f + seed * 1.7f)
        val b = sin(phase * 91.3f + seed * 3.1f)
        return (a * 0.6f + b * 0.4f)
    }

    /** 影像切面的组织密度，返回 0..1。 */
    fun tissueDensity(x: Float, y: Float, slice: Int, seed: Int): Float {
        // 以椭圆体近似躯干横断面，叠加器官团块与切层渐变
        val cx = x - 0.5f
        val cy = y - 0.5f
        val body = 1f - (cx * cx / 0.19f + cy * cy / 0.13f)
        if (body <= 0f) return 0f
        val organA = gauss(x, y, 0.42f, 0.46f, 0.10f, 0.075f)
        val organB = gauss(x, y, 0.60f, 0.52f, 0.055f, 0.05f)
        val sliceF = 1f - abs(slice - 44) / 96f
        val texture = 0.06f * sin(x * 42f + seed) * sin(y * 38f - seed)
        return ((body * 0.55f + organA * 0.6f + organB * 0.45f) * (0.65f + 0.35f * sliceF) + texture)
            .coerceIn(0f, 1f)
    }

    /**
     * 内窥镜画面的渗出识别置信度，返回 0..1。
     *
     * 纯函数：以术野状态与时间正弦叠加模拟分割置信度的起伏，无随机源、无状态，
     * 因此同一时刻多次调用结果恒等，便于在任何面板上复算与回放。
     */
    fun exudateScore(t: Float, bleeding: Boolean): Float {
        val pulse = 0.5f + 0.5f * sin(t * 1.7f)
        val wobble = 0.5f + 0.5f * sin(t * 0.63f + 1.1f)
        return if (bleeding) {
            (0.74f + 0.18f * pulse - 0.05f * wobble).coerceIn(0f, 1f)
        } else {
            (0.09f + 0.07f * wobble).coerceIn(0f, 1f)
        }
    }

    private fun gauss(x: Float, y: Float, mx: Float, my: Float, sx: Float, sy: Float): Float {
        val dx = (x - mx) / sx
        val dy = (y - my) / sy
        return exp(-(dx * dx + dy * dy) * 0.5f)
    }

    /** 内窥镜视野组织纹理。 */
    fun scopeTissue(x: Float, y: Float, t: Float, zoom: Float): Float {
        val dx = (x - 0.5f) / zoom
        val dy = (y - 0.5f) / zoom
        val r = dx * dx + dy * dy
        if (r > 0.25f) return 0f
        val folds = sin(dx * 26f + t * 0.6f) * 0.5f + 0.5f
        val vessels = sin(dy * 18f - t * 0.35f + folds) * 0.5f + 0.5f
        val base = 0.55f + 0.18f * folds + 0.14f * vessels
        val vignette = (1f - r * 3.4f).coerceIn(0f, 1f)
        return (base * vignette).coerceIn(0f, 1f)
    }
}
