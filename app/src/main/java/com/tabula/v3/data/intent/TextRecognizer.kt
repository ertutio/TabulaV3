package com.tabula.v3.data.intent

import android.graphics.Bitmap

/**
 * 本地 OCR 的抽象接口。
 *
 * 为什么先抽象：OCR 引擎是唯一带原生库/模型体积的部件，规则、排序、缓存、
 * 脱敏都不该依赖它。这样
 * - JVM 单测可以用 [FakeTextRecognizer] 直接喂文本，不碰 Bitmap / MediaStore；
 * - 将来从 Tesseract 换到别的引擎只改一个实现；
 * - 引擎不可用（模型缺失、初始化失败）时，上层拿到 [OcrResult.Unavailable] 并降级成「证据不足」，
 *   不会让整条清理链路挂掉。
 */
interface TextRecognizer {
    /** 引擎是否可用（模型已就绪）。不可用时上层直接走「不确定」分支。 */
    val isAvailable: Boolean

    /** 引擎标识 + 版本，写进缓存 key，换引擎/换模型自动失效。 */
    val engineId: String

    /**
     * 识别一张图。
     *
     * @param bitmap 调用方解码好的位图；实现**不得**长期持有它
     * @return [OcrResult]；识别失败返回 [OcrResult.Failed] 而不是抛异常
     */
    suspend fun recognize(bitmap: Bitmap): OcrResult

    /** 释放原生资源（进程内单例，退出或换语言时调用）。 */
    fun close()
}

/** OCR 结果。 */
sealed interface OcrResult {
    /**
     * 识别成功。
     *
     * @param lines 文本行（已去首尾空白、去空行）
     * @param meanConfidence 引擎给出的平均置信度，归一化到 0~1
     */
    data class Success(
        val lines: List<String>,
        val meanConfidence: Float
    ) : OcrResult {
        val text: String get() = lines.joinToString("\n")
    }

    /** 图里没有文字（纯照片、渐变图…）。 */
    data object Empty : OcrResult

    /** 识别失败（解码失败、引擎异常）。 */
    data class Failed(val reason: String) : OcrResult

    /** 引擎不可用（模型缺失 / 初始化失败）——上层应整体降级，不要逐张重试。 */
    data class Unavailable(val reason: String) : OcrResult
}

/**
 * 测试用 / 硬件不可用时的文本识别器：文本由调用方提供，不做任何图像处理。
 *
 * 生产代码不注册它；它只出现在单测与合成样图测试里，用来把「OCR 之后」的所有逻辑
 * （规则、保护、脱敏、排序、缓存）与引擎解耦地钉住。
 */
class FakeTextRecognizer(
    private val responses: Map<String, OcrResult>,
    override val engineId: String = "fake-v1",
    override val isAvailable: Boolean = true,
    private val fallback: OcrResult = OcrResult.Empty
) : TextRecognizer {

    /** 按调用顺序记录被识别的 bitmap 标识，用于断言「只处理候选范围内的图」。 */
    val seenKeys = mutableListOf<String>()

    override suspend fun recognize(bitmap: Bitmap): OcrResult = fallback

    /** 供测试直接按键取结果，避免依赖真实 Bitmap。 */
    fun recognize(key: String): OcrResult {
        seenKeys += key
        return responses[key] ?: fallback
    }

    override fun close() = Unit
}
