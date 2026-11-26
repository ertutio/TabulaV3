package com.tabula.v3.ui.components.liquidglass

import kotlin.math.abs

/**
 * 查看器里的"背景采样与屏幕照片是否可保证一致"。
 *
 * 回收站全屏查看器把照片交给 `AsyncImage` + `graphicsLayer { scale/translation }`
 * 交互式绘制，而采样层是**另一份**按 `ContentScale.Fit` 静止绘制的 Bitmap。
 * 两者只有在"没翻页、没放大、没拖动"时才是同一幅画面；其余状态下采样层必然
 * 落后或错位。
 *
 * 处理原则（由需求确定）：**能同步的同步，不能保证的明确回退**——绝不允许
 * "继续显示错位采样"。因此这里把判定收成一个纯函数，让"什么时候必须回退"
 * 可以被 JVM 单测逐条锁住，而不是散落在 UI 代码里靠肉眼判断。
 *
 * @param pagerSettled 分页器已停稳（`!isScrollInProgress` 且没有残留偏移）
 * @param scale 当前缩放倍数（1 = 原比例）
 * @param panX/panY 当前平移量（像素，0 = 未拖动）
 */
data class ViewerBackdropSync(
    val pagerSettled: Boolean,
    val scale: Float,
    val panX: Float,
    val panY: Float
) {
    /** 采样层与屏幕照片是否可保证是同一幅画面。 */
    val canSample: Boolean
        get() = pagerSettled && isIdentityTransform(scale, panX, panY)

    /** 不可采样时的原因；可采样时为 null。 */
    val fallbackReason: BackdropFallbackReason?
        get() = when {
            canSample -> null
            !pagerSettled -> BackdropFallbackReason.VIEWER_SCROLLING
            else -> BackdropFallbackReason.VIEWER_TRANSFORMED
        }

    companion object {
        /** 未翻页、未缩放、未拖动的静止状态。 */
        val Settled = ViewerBackdropSync(
            pagerSettled = true,
            scale = 1f,
            panX = 0f,
            panY = 0f
        )
    }
}

/**
 * 变换是否等价于"什么都没做"。
 *
 * 缩放容差 [SCALE_EPSILON] 取 0.01：手指轻微抖动会让 `scale` 在 1.0 附近浮动，
 * 若要求严格相等，玻璃按钮会在"手指刚碰到屏幕"的瞬间闪一下回退。
 * 平移容差 [PAN_EPSILON_PX] 取 0.5px：亚像素位移在 48dp 的按钮上不可见，
 * 但会让采样层与屏幕差半个像素——那正是"错位采样"的起点，所以只要超过
 * 半个像素就必须回退，不做"看起来差不多"的宽容。
 */
fun isIdentityTransform(scale: Float, panX: Float, panY: Float): Boolean =
    abs(scale - 1f) <= SCALE_EPSILON &&
        abs(panX) <= PAN_EPSILON_PX &&
        abs(panY) <= PAN_EPSILON_PX

/** 缩放容差：手指抖动允许的浮动范围。 */
const val SCALE_EPSILON: Float = 0.01f

/** 平移容差：亚像素位移不可见，超过半个像素即判为"已拖动"。 */
const val PAN_EPSILON_PX: Float = 0.5f

/**
 * 照片背景采样是否应当被加载/建立。
 *
 * **顺序很重要**：这个判定必须在**发起图片请求之前**执行。早期实现先
 * `rememberBackdropPhoto(...)` 无条件加载一张限宽 Bitmap，再用
 * [shouldAttachPhotoBackdrop] 决定要不要接背景——功能关闭或系统不支持时
 * 仍然白白发了一次 Coil 请求（解码 + 内存占用）。
 *
 * 现在：门控先算，关着就不把 `data` 交给加载器，请求根本不会发出。
 *
 * @param liquidGlassEnabled 实验室开关（生产入口的 Android 15+ 判定由调用方保证）
 * @param sdkInt 当前系统版本
 */
fun shouldLoadBackdropPhoto(liquidGlassEnabled: Boolean, sdkInt: Int): Boolean =
    liquidGlassEnabled && sdkInt >= LIQUID_GLASS_BACKDROP_MIN_SDK
