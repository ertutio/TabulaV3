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

        setContent {
            if (Build.VERSION.SDK_INT < LIQUID_GLASS_BACKDROP_MIN_SDK) {
                SdkTooLowNotice()
            } else {
                LiquidGlassDebugPreview(
                    modifier = Modifier.fillMaxSize(),
                    initialRefractionOn = refraction,
                    initialUsePhoto = photo,
                    initialOffsetX = offsetX,
                    initialOffsetY = offsetY,
                    initialGlassHeightDp = glassHeightDp,
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
