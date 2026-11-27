package com.tabula.v3.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabula.v3.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.delay

internal const val UNDO_SNACKBAR_DURATION_MS = 3000L

/**
 * 撤销操作 Snackbar
 *
 * 显示在屏幕底部，支持自动消失和手动撤销。
 * 设计风格：现代毛玻璃 + 圆角 + 阴影
 *
 * @param visible 是否显示
 * @param message 提示消息
 * @param undoText 撤销按钮文字
 * @param duration 自动消失时间（毫秒）
 * @param onUndo 撤销回调
 * @param onDismiss 超时回调。由调用方计时时留空。
 */
@Composable
fun UndoSnackbar(
    visible: Boolean,
    message: String,
    undoText: String = "撤销",
    duration: Long = UNDO_SNACKBAR_DURATION_MS,
    onUndo: () -> Unit,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    animate: Boolean = true
) {
    val isDarkTheme = LocalIsDarkTheme.current

    // 有独立计时的调用方只使用这里的展示和按钮。
    LaunchedEffect(visible) {
        if (visible && onDismiss != null) {
            delay(duration)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = if (animate) slideInVertically(
            initialOffsetY = { it },
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
        ) + fadeIn() else EnterTransition.None,
        exit = if (animate) slideOutVertically(
            targetOffsetY = { it },
            animationSpec = spring(stiffness = Spring.StiffnessMedium)
        ) + fadeOut() else ExitTransition.None,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            // Snackbar 内容
            Row(
                modifier = Modifier
                    .shadow(
                        elevation = 12.dp,
                        shape = RoundedCornerShape(16.dp),
                        ambientColor = Color.Black.copy(alpha = 0.15f),
                        spotColor = Color.Black.copy(alpha = 0.15f)
                    )
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (isDarkTheme) Color(0xFF2C2C2E) else Color(0xFF1C1C1E)
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 消息文字
                Text(
                    text = message,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(modifier = Modifier.width(16.dp))

                // 撤销按钮
                Text(
                    text = undoText,
                    color = Color(0xFF64D2FF), // iOS 风格的亮蓝色
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onUndo()
                        }
                        .padding(vertical = 4.dp, horizontal = 8.dp)
                )
            }
        }
    }
}

/**
 * Snackbar 状态管理
 */
data class SnackbarState(
    val visible: Boolean = false,
    val message: String = "",
    val albumName: String = ""
)

/**
 * 便捷的 remember 函数
 */
@Composable
fun rememberSnackbarState(): SnackbarStateHolder {
    var state by remember { mutableStateOf(SnackbarState()) }
    
    return remember {
        SnackbarStateHolder(
            getState = { state },
            setState = { state = it }
        )
    }
}

class SnackbarStateHolder(
    private val getState: () -> SnackbarState,
    private val setState: (SnackbarState) -> Unit
) {
    val state: SnackbarState get() = getState()
    
    fun show(message: String, albumName: String = "") {
        setState(SnackbarState(visible = true, message = message, albumName = albumName))
    }
    
    fun dismiss() {
        setState(getState().copy(visible = false))
    }
}
