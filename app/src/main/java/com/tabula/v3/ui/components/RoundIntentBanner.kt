package com.tabula.v3.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabula.v3.ui.theme.LocalIsDarkTheme
import com.tabula.v3.ui.util.HapticFeedback

/**
 * 本轮整理意图提示条（路线图 #83 第 7 项 / 审计 #90）。
 *
 * 从 DeckContent 抽出的真实生产组件：DeckScreen 在 [intentSourceName] 非空时
 * 渲染；「看这个」走 [onAcceptIntent]（MainActivity 里调
 * RecommendationEngine.acceptRoundIntent），「忽略」走 [onDismissIntent]。
 * Compose 入口测试用同一组件驱动真实回调链路。
 */
@Composable
fun RoundIntentBanner(
    intentSourceName: String,
    onAcceptIntent: () -> Unit,
    onDismissIntent: () -> Unit,
    modifier: Modifier = Modifier
) {
    val intentContext = LocalContext.current
    val isDark = LocalIsDarkTheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isDark) Color(0xFF2C2C2E) else Color.White)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "这轮再看几张「$intentSourceName」？",
                color = if (isDark) Color.White else Color.Black,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "接受后下一批优先这个来源，只影响一批",
                color = Color(0xFF8E8E93),
                fontSize = 11.sp
            )
        }
        TextButton(onClick = {
            HapticFeedback.lightTap(intentContext)
            onDismissIntent()
        }) {
            Text("忽略", color = Color(0xFF8E8E93), fontSize = 13.sp)
        }
        TextButton(onClick = {
            HapticFeedback.lightTap(intentContext)
            onAcceptIntent()
        }) {
            Text("看这个", color = Color(0xFF007AFF), fontSize = 13.sp)
        }
    }
}
