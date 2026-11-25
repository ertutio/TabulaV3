package com.tabula.v3.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.tabula.v3.ui.components.liquidglass.LiquidGlassBackdropState

/**
 * 当前可用的液态玻璃背景来源。
 *
 * 背景由界面自己提供：谁负责绘制背景，谁就用 `LiquidGlassBackdropHost` 建立
 * 一个 [LiquidGlassBackdropState] 并通过这个 CompositionLocal 往下传。
 *
 * 默认 `null`，表示"这条界面没有可采样的背景"——此时所有玻璃组件都走原有
 * 材质，画面与接入前完全一致。这样接入是按界面逐个开启的，不需要改全局开关，
 * 也不会在没人提供背景的界面上凭空多出一层采样。
 */
val LocalLiquidGlassBackdrop = staticCompositionLocalOf<LiquidGlassBackdropState?> { null }
