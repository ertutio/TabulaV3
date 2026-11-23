package com.tabula.v3.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.ui.theme.LocalIsDarkTheme
import com.tabula.v3.ui.util.HapticFeedback
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val imageLoader = androidx.compose.runtime.remember(context) {
        com.tabula.v3.di.CoilSetup.getImageLoader(context)
    }
    val visibleRows = if (showReviewOnly) RelayReviewRules.pendingReviewRows(rows) else rows
    // 待核对项的原图选择：null = 未在选择；非 null = 正在为这条记录浏览候选
    var pickingFor by remember { mutableStateOf<RelayReviewRules.ReviewRow?>(null) }

    Column(Modifier.fillMaxSize().background(bg)) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                HapticFeedback.lightTap(context)
                if (pickingFor != null) pickingFor = null else onNavigateBack()
            }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = text)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (pickingFor != null) "选择原图" else "分享待清",
                    color = text, fontSize = 20.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    if (pickingFor != null) "点一张你确认的就是分享的那张"
                    else "标记不是删除；撤销不会移动照片",
                    color = secondary, fontSize = 12.sp
                )
            }
            if (pickingFor == null) {
                TextButton(onClick = onToggleReviewOnly) {
                    Text(if (showReviewOnly) "全部记录" else "待核对", color = accent)
                }
            }
        }

        if (pickingFor != null) {
            // ===== 原图选择：全部可访问照片的缩略图网格 =====
            val pending = pickingFor!!
            Column(Modifier.weight(1f).fillMaxWidth()) {
                Text(
                    text = "待核对：${pending.mark.displayName ?: "分享的照片"}" +
                        "（${SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(pending.mark.markedAt))} 分享）",
                    color = text, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                if (library.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                        Text(
                            if (showReviewOnly || rows.isEmpty()) "当前没有可访问的照片"
                            else "图库为空",
                            color = secondary
                        )
                    }
                } else {
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        gridItems(
                            library,
                            key = { it.mediaIdentityKey() }
                        ) { candidate ->
                            Column(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(card)
                                    .clickable {
                                        HapticFeedback.lightTap(context)
                                        onChooseImage(pending, candidate)
                                    }
                            ) {
                                AsyncImage(
                                    model = candidate.uri,
                                    contentDescription = candidate.displayName,
                                    imageLoader = imageLoader,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                                )
                                Text(
                                    text = candidate.displayName,
                                    color = text, fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                Text(
                                    text = formatCandidateDate(candidate.dateModified),
                                    color = secondary, fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
                Text(
                    text = "确认后 Tabula 只记录你指定的关联，不保证它与分享来源字节相同。",
                    color = secondary, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Button(
                    onClick = {
                        HapticFeedback.lightTap(context)
                        pickingFor = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) { Text("取消选择", color = Color.White) }
            }
        } else {
            // ===== 记录列表 =====
            if (visibleRows.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                    Text(
                        if (showReviewOnly) "没有待核对记录" else "还没有通过分享产生的待清记录",
                        color = secondary
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(
                        visibleRows,
                        key = { it.mark.identityKey ?: "uri|${it.mark.uriString}" }
                    ) { row ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(card)
                                .padding(12.dp)
                        ) {
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
                                        color = text, maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        when {
                                            row.alreadyInRecycleBin -> "已在回收站，标记待重试移除"
                                            row.isActionable -> "已定位原图，可移入回收站"
                                            else -> row.reviewReason.orEmpty()
                                        },
                                        color = secondary, fontSize = 12.sp
                                    )
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
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
                                    TextButton(onClick = {
                                        HapticFeedback.lightTap(context)
                                        pickingFor = row
                                    }) { Text("浏览图库指定原图", color = accent) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatCandidateDate(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))
