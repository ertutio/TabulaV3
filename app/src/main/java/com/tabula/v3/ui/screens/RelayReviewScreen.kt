package com.tabula.v3.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.relay.RelayReviewRules
import com.tabula.v3.ui.theme.LocalIsDarkTheme
import com.tabula.v3.ui.util.HapticFeedback

/** 系统分享产生的待清记录：用户可见、可撤销、可核对、可移入回收站。 */
@Composable
fun RelayReviewScreen(
    rows: List<RelayReviewRules.ReviewRow>,
    library: List<ImageFile>,
    showReviewOnly: Boolean,
    onToggleReviewOnly: () -> Unit,
    onRecycle: (ImageFile, com.tabula.v3.data.relay.RelayMark) -> Unit,
    onRemoveMark: (RelayReviewRules.ReviewRow) -> Unit,
    onChooseImage: (RelayReviewRules.ReviewRow, ImageFile) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val isDark = LocalIsDarkTheme.current
    val bg = if (isDark) Color.Black else Color(0xFFF2F2F7)
    val card = if (isDark) Color(0xFF1C1C1E) else Color.White
    val text = if (isDark) Color.White else Color.Black
    val secondary = Color(0xFF8E8E93)
    val accent = Color(0xFF007AFF)
    val visibleRows = if (showReviewOnly) RelayReviewRules.pendingReviewRows(rows) else rows

    Column(Modifier.fillMaxSize().background(bg)) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                HapticFeedback.lightTap(context)
                onNavigateBack()
            }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = text)
            }
            Column(Modifier.weight(1f)) {
                Text("分享待清", color = text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("标记不是删除；撤销不会移动照片", color = secondary, fontSize = 12.sp)
            }
            TextButton(onClick = onToggleReviewOnly) {
                Text(if (showReviewOnly) "全部记录" else "待核对", color = accent)
            }
        }
        if (visibleRows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                Text(
                    if (showReviewOnly) "没有待核对记录" else "还没有通过分享产生的待清记录",
                    color = secondary
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(visibleRows, key = { it.mark.identityKey ?: "uri|${it.mark.uriString}" }) { row ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp)).background(card).padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val image = row.image
                            if (image != null) {
                                AsyncImage(
                                    model = image.uri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                                )
                            } else {
                                Box(Modifier.size(56.dp).background(secondary.copy(alpha = .2f)))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    row.mark.displayName ?: "分享的照片",
                                    color = text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (row.isActionable) "已定位原图，可移入回收站"
                                    else row.reviewReason.orEmpty(),
                                    color = secondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                HapticFeedback.lightTap(context)
                                onRemoveMark(row)
                            }) {
                                Text(
                                    if (row.canRetryMarkRemoval) "重试移除标记" else "撤销标记",
                                    color = if (row.canRetryMarkRemoval) accent else secondary
                                )
                            }
                            val image = row.image
                            if (image != null) {
                                TextButton(onClick = {
                                    HapticFeedback.lightTap(context)
                                    onRecycle(image, row.mark)
                                }) { Text("移入回收站", color = accent) }
                            } else if (row.mark.identityKey == null) {
                                Text("手动指定原图：", color = secondary, fontSize = 12.sp)
                                library.take(20).forEach { candidate ->
                                    IconButton(onClick = {
                                        HapticFeedback.lightTap(context)
                                        onChooseImage(row, candidate)
                                    }) {
                                        Icon(Icons.Outlined.CheckCircle, "指定原图 ${candidate.displayName}", tint = accent)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
