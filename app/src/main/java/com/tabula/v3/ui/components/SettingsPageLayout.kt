package com.tabula.v3.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsPageLayout(
    background: Color,
    scrollState: ScrollState,
    header: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val page = Modifier.fillMaxSize().background(background).statusBarsPadding()
    if (!LocalLiquidGlassEnabled.current) {
        Column(page) {
            header()
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp),
                content = content
            )
        }
        return
    }

    val density = LocalDensity.current
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val headerHeight = with(density) { if (headerHeightPx > 0) headerHeightPx.toDp() else 72.dp }
    val list = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    val material = rememberGraphicsLayer()

    Box(page) {
        Column(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    if (scrollState.value > 0) {
                        list.record { this@drawWithContent.drawContent() }
                        drawLayer(list)
                    } else {
                        drawContent()
                    }
                }
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(headerHeight))
            content()
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(headerHeight + 12.dp)
                .drawWithCache {
                    val fadeStart = (headerHeight.toPx() / size.height).coerceIn(0f, 1f)
                    val mask = Brush.verticalGradient(
                        0f to Color.Black,
                        fadeStart to Color.Black,
                        1f to Color.Transparent
                    )
                    val veil = Brush.verticalGradient(
                        0f to background.copy(alpha = 0.92f),
                        1f to background.copy(alpha = 0.70f)
                    )
                    val radius = 13.dp.toPx()
                    blurred.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                    material.compositingStrategy = CompositingStrategy.Offscreen
                    val threshold = 24.dp.toPx()
                    onDrawBehind {
                        if (scrollState.value > 0) {
                            blurred.record { drawLayer(list) }
                            // Fade both the blurred list and its tint below the header controls.
                            material.record {
                                drawLayer(blurred)
                                drawRect(veil)
                                drawRect(mask, blendMode = BlendMode.DstIn)
                            }
                            material.alpha = (scrollState.value / threshold).coerceIn(0f, 1f)
                            drawLayer(material)
                        }
                    }
                }
        )

        Box(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { headerHeightPx = it.height }
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
        ) {
            header()
        }
    }
}
