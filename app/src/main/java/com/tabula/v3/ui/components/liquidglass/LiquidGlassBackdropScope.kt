package com.tabula.v3.ui.components.liquidglass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.tabula.v3.ui.components.LocalLiquidGlassBackdrop

/**
 * 一段界面接入背景采样层的最小包装。
 *
 * 用法：把原来"自己画背景 + 在上面放玻璃控件"的那块内容换成这个包装，
 * 传入背景的绘制方式和一个内容版本号即可。屏幕内的玻璃控件（顶部操作按钮等）
 * 会自动通过 [LocalLiquidGlassBackdrop] 拿到同一个背景来源。
 *
 * ```
 * LiquidGlassBackdropScope(
 *     backgroundRevision = currentImage?.uri,   // 换图/滚动时变化
 *     background = { drawBackdrop() }
 * ) {
 *     // 正常内容；里面的玻璃控件会自动采样这块背景
 * }
 * ```
 *
 * 背景用 `DrawScope.() -> Unit` 传入：宿主铺满画一次，采样层在玻璃大小的小层里
 * 局部重绘一次。两次都是直接绘制，不产生"录成整屏位图再引用"的中间物。
 *
 * 只有"背景真的会变化、且有内容可折射"的界面才值得接入。纯色背景即使接入，
 * 采到的也是同一个纯色，看不出折射，却要多画一遍，因此不划算。
 */
@Composable
fun LiquidGlassBackdropScope(
    backgroundRevision: Any?,
    modifier: Modifier = Modifier,
    background: DrawScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val state = rememberLiquidGlassBackdropState()

    // 内容版本变化时通知采样层重新对齐；静止画面不触发（remember 的键不变就
    // 不会重跑）。只递增一个计数器，不参与组合，避免反过来触发重组。
    remember(backgroundRevision) {
        state.invalidateContent()
        backgroundRevision
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 背景层：宿主铺满绘制
        LiquidGlassBackdropHost(
            state = state,
            modifier = Modifier.fillMaxSize(),
            background = background
        )
        // 内容层：玻璃控件从这里取背景，但自身不在背景里
        CompositionLocalProvider(LocalLiquidGlassBackdrop provides state) {
            Box(modifier = Modifier.fillMaxSize(), content = content)
        }
    }
}
