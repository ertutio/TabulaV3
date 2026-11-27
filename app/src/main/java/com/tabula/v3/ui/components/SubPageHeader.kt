package com.tabula.v3.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabula.v3.ui.util.HapticFeedback

/**
 * 子页统一头部（返回 + 标题层级）。
 *
 * 暂存清理、分享待清、连拍选片三个子页原先各写一份：内边距 4dp/8dp 与 8dp 混用、
 * 标题 17sp/18sp/20sp 三种、副标题 11sp/12sp/13sp 三种。同一类页面在返回手势下
 * 来回切换时，标题位置会跳一下。这里统一成一份：
 *
 * | 项 | 值 |
 * | --- | --- |
 * | 左右内边距 | 4dp（返回按钮自己的触控区再补内边距） |
 * | 标题 | 18sp / Bold |
 * | 副标题 | 13sp / 次要色 |
 * | 返回按钮 | 40dp 触控、语义「返回」 |
 *
 * @param title 主标题
 * @param subtitle 一行副标题（说明这一页做什么，不放大段解释）
 * @param trailing 右侧可选操作（例如「待核对」「这组都留」）
 */
@Composable
fun SubPageHeader(
    title: String,
    subtitle: String,
    textColor: Color,
    secondaryTextColor: Color,
    onNavigateBack: () -> Unit,
    backContentDescription: String = "返回",
    trailing: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                HapticFeedback.lightTap(context)
                onNavigateBack()
            },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = backContentDescription,
                tint = textColor,
                modifier = Modifier.size(24.dp)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, color = textColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = secondaryTextColor, fontSize = 13.sp)
        }
        if (trailing != null) {
            trailing()
        }
    }
}
