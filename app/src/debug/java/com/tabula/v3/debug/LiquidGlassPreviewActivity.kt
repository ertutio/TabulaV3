package com.tabula.v3.debug

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabula.v3.ui.components.liquidglass.DebugBackdropKind
import com.tabula.v3.ui.components.liquidglass.DebugGlassShape
import com.tabula.v3.ui.components.liquidglass.LIQUID_GLASS_BACKDROP_MIN_SDK
import com.tabula.v3.ui.components.liquidglass.LiquidGlassDebugPreview

/**
 * 独立调试预览入口（仅 debug 变体）。
 *
 * 不经过应用主流程、不依赖权限与相册：`adb shell am start` 直接打开。
 * 支持用 intent extra 固定初始状态，让"同一背景、同一组材质参数、仅切换
 * 折射开关"的前后对照可以脚本化复现，而不靠手工点按：
 *
 * ```
 * adb shell am start -n com.tabula.v3.recyclebin.test/com.tabula.v3.debug.LiquidGlassPreviewActivity \
 *   --ez refraction true --ez photo true --ei offsetY 220
 * ```
 */
class LiquidGlassPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val refraction = intent.getBooleanExtra(EXTRA_REFRACTION, true)
        val photo = intent.getBooleanExtra(EXTRA_PHOTO, false)
        val offsetX = intent.getIntExtra(EXTRA_OFFSET_X, 0).toFloat()
        val offsetY = intent.getIntExtra(EXTRA_OFFSET_Y, 0).toFloat()
        val glassHeightDp = intent.getIntExtra(EXTRA_GLASS_HEIGHT_DP, 160).toFloat()
        val diagnosticBlurOnly = intent.getBooleanExtra(EXTRA_DIAGNOSTIC_BLUR, false)
        val refractionAmountDp = intent.getIntExtra(EXTRA_REFRACTION_AMOUNT_DP, 10).toFloat()
        val shape = intent.getStringExtra(EXTRA_SHAPE)
            ?.let { name -> DebugGlassShape.entries.firstOrNull { it.name == name } }
            ?: DebugGlassShape.RoundedRect
        val backdropKind = intent.getStringExtra(EXTRA_BACKDROP)
            ?.let { name -> DebugBackdropKind.entries.firstOrNull { it.name == name } }
        val hostScalePercent = intent.getIntExtra(EXTRA_HOST_SCALE_PERCENT, 100)
        // 生产接线预览：同一个 Activity 用 extra 切换，探针不必再开一个入口
        val productionWiring = intent.getBooleanExtra(EXTRA_PRODUCTION_WIRING, false)
        val labSwitchOn = intent.getBooleanExtra(EXTRA_LAB_SWITCH, true)
        val photoAvailable = intent.getBooleanExtra(EXTRA_PHOTO_AVAILABLE, true)

        setContent {
            if (Build.VERSION.SDK_INT < LIQUID_GLASS_BACKDROP_MIN_SDK) {
                SdkTooLowNotice()
            } else if (productionWiring) {
                LiquidGlassProductionWiringPreview(
                    modifier = Modifier.fillMaxSize(),
                    labSwitchOn = labSwitchOn,
                    photoAvailable = photoAvailable,
                    photoOffsetX = offsetX.toInt(),
                    photoOffsetY = offsetY.toInt()
                )
            } else {
                LiquidGlassDebugPreview(
                    modifier = Modifier.fillMaxSize(),
                    initialRefractionOn = refraction,
                    initialUsePhoto = photo,
                    initialOffsetX = offsetX,
                    initialOffsetY = offsetY,
                    initialGlassHeightDp = glassHeightDp,
                    initialRefractionAmountDp = refractionAmountDp,
                    initialShape = shape,
                    initialBackdropKind = backdropKind,
                    initialHostScalePercent = hostScalePercent,
                    diagnosticBlurOnly = diagnosticBlurOnly
                )
            }
        }
    }

    companion object {
        const val EXTRA_REFRACTION = "refraction"
        const val EXTRA_PHOTO = "photo"
        const val EXTRA_OFFSET_X = "offsetX"
        const val EXTRA_OFFSET_Y = "offsetY"
        const val EXTRA_GLASS_HEIGHT_DP = "glassHeightDp"
        const val EXTRA_DIAGNOSTIC_BLUR = "diagBlur"

        /** 折射位移（dp）。对照测试只改它，模糊与采样范围保持不变。 */
        const val EXTRA_REFRACTION_AMOUNT_DP = "refractionAmountDp"

        /** 玻璃形状：`RoundedRect` / `Circle` / `CutCorner`（后者应回退）。 */
        const val EXTRA_SHAPE = "shape"

        /** 背景来源：`Grid` / `Photo` / `SizeDependent`。 */
        const val EXTRA_BACKDROP = "backdrop"

        /** 宿主缩放百分比（30~100）。宿主与玻璃尺寸不同才能验证尺寸契约。 */
        const val EXTRA_HOST_SCALE_PERCENT = "hostScale"

        /** true 时渲染**生产接线**预览（LiquidGlassPhotoBackdropViewer 本体）。 */
        const val EXTRA_PRODUCTION_WIRING = "productionWiring"

        /** 生产入口的实验室开关；false 时整条接线不生效。 */
        const val EXTRA_LAB_SWITCH = "labSwitch"

        /** false 时模拟"背景来源不可用"（照片被删 / 解码失败）。 */
        const val EXTRA_PHOTO_AVAILABLE = "photoAvailable"
    }
}

@androidx.compose.runtime.Composable
private fun SdkTooLowNotice() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101018))
            .padding(24.dp)
    ) {
        Text(
            text = "当前系统 API ${Build.VERSION.SDK_INT}",
            color = Color.White,
            fontSize = 16.sp
        )
        Text(
            text = "背景采样层要求 API $LIQUID_GLASS_BACKDROP_MIN_SDK+；" +
                "此版本下玻璃组件按原有材质显示。",
            color = Color(0xFFFFD400),
            fontSize = 14.sp
        )
    }
}
