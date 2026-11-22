package com.tabula.v3.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import coil.request.ImageRequest
import com.tabula.v3.data.burst.BurstSelectionRules
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.SimilarGroup
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.di.CoilSetup
import com.tabula.v3.ui.theme.LocalIsDarkTheme
import com.tabula.v3.ui.util.HapticFeedback
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 连拍选片（路线图 #83 第 4 项）。
 *
 * 入口：相似推荐模式下，一批照片来自同一个相似组时，从卡片页进入本页。
 *
 * 交互口径（与 [BurstSelectionRules] 一致）：
 * - **先勾选要保留的**，其余自动成为待回收候选——默认保守
 * - 点缩略图切换保留状态；「这组都留」一键全保留
 * - 至少保留一张，否则确认按钮不可用（全删不在这个界面做）
 * - 取消/返回：什么都不提交，调用方保留该组的原有进度
 * - 确认：把待回收候选交给回收站，整组才算处理完成
 */
@Composable
fun BurstSelectionScreen(
    group: SimilarGroup,
    keptIdentityKeys: Set<String>,
    isCommitting: Boolean,
    onToggleKeep: (ImageFile) -> Unit,
    onKeepAll: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current
    val isDarkTheme = LocalIsDarkTheme.current
    val backgroundColor = if (isDarkTheme) Color.Black else Color(0xFFF2F2F7)
    val cardColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color.White
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val secondaryTextColor = Color(0xFF8E8E93)
    val accentColor = Color(0xFF007AFF)
    val discardColor = Color(0xFFFF9500)
    val imageLoader = remember(context) { CoilSetup.getImageLoader(context) }

    val decision = remember(group, keptIdentityKeys) {
        BurstSelectionRules.resolve(group, keptIdentityKeys)
    }
    val validDecision = (decision as? BurstSelectionRules.Result.Valid)?.decision
    var showDiscardPreview by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .statusBarsPadding()
    ) {
        // ========== 顶部栏 ==========
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                HapticFeedback.lightTap(context)
                onCancel()
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回（不提交）",
                    tint = textColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text("连拍选片", color = textColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "${group.size} 张 · ${formatSpan(group)}",
                    color = secondaryTextColor,
                    fontSize = 13.sp
                )
            }
            TextButton(
                onClick = {
                    HapticFeedback.lightTap(context)
                    onKeepAll()
                },
                enabled = !isCommitting
            ) {
                Text("这组都留", color = accentColor, fontSize = 13.sp)
            }
        }

        Text(
            text = "点一下勾选要保留的，其余会被移入回收站。至少保留一张。",
            color = secondaryTextColor,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // ========== 缩略图网格 ==========
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            group.images.chunked(THUMBNAILS_PER_ROW).forEach { rowImages ->
                item(key = "row_${rowImages.first().mediaIdentityKey()}") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowImages.forEach { image ->
                            val identityKey = image.mediaIdentityKey()
                            val isKept = identityKey in keptIdentityKeys
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(cardColor)
                                    .then(
                                        if (isKept) {
                                            Modifier.border(2.dp, accentColor, RoundedCornerShape(10.dp))
                                        } else {
                                            Modifier.border(
                                                2.dp,
                                                discardColor.copy(alpha = 0.6f),
                                                RoundedCornerShape(10.dp)
                                            )
                                        }
                                    )
                                    .clickable {
                                        HapticFeedback.lightTap(context)
                                        onToggleKeep(image)
                                    }
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(image.uri)
                                        .crossfade(100)
                                        .build(),
                                    contentDescription = image.displayName,
                                    imageLoader = imageLoader,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                // 保留标记
                                if (isKept) {
                                    Icon(
                                        imageVector = Icons.Outlined.Check,
                                        contentDescription = "保留",
                                        tint = Color.White,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                            .size(20.dp)
                                            .background(accentColor, CircleShape)
                                            .padding(3.dp)
                                    )
                                } else {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .background(discardColor.copy(alpha = 0.18f))
                                    )
                                }
                                // 底部时间戳，帮用户分辨连拍顺序
                                Text(
                                    text = formatTime(image.dateModified),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(4.dp)
                                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        repeat(THUMBNAILS_PER_ROW - rowImages.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // 待回收预览
            if (validDecision != null && validDecision.discardCount > 0) {
                item(key = "discard_preview") {
                    TextButton(
                        onClick = {
                            HapticFeedback.lightTap(context)
                            showDiscardPreview = !showDiscardPreview
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (showDiscardPreview) {
                                "收起待回收列表"
                            } else {
                                "查看待移入回收站的 ${validDecision.discardCount} 张"
                            },
                            color = discardColor,
                            fontSize = 13.sp
                        )
                    }
                }
                if (showDiscardPreview) {
                    items(
                        validDecision.discardCandidates,
                        key = { "discard_${it.mediaIdentityKey()}" }
                    ) { image ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(image.uri)
                                    .crossfade(100)
                                    .build(),
                                contentDescription = image.displayName,
                                imageLoader = imageLoader,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                            Spacer(Modifier.size(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = image.displayName,
                                    color = textColor,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatTime(image.dateModified),
                                    color = secondaryTextColor,
                                    fontSize = 11.sp
                                )
                            }
                            TextButton(onClick = {
                                HapticFeedback.lightTap(context)
                                onToggleKeep(image)
                            }) {
                                Text("改留这张", color = accentColor, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // ========== 底部确认条 ==========
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardColor)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (validDecision != null) {
                Text(
                    text = "保留 ${validDecision.keepCount} 张 · 回收 ${validDecision.discardCount} 张",
                    color = secondaryTextColor,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            } else {
                Text(
                    text = when ((decision as? BurstSelectionRules.Result.Invalid)?.error) {
                        BurstSelectionRules.BurstSelectionError.KeepNothing ->
                            "至少勾选一张保留，才能确认"
                        BurstSelectionRules.BurstSelectionError.KeepOutsideGroup ->
                            "勾选里有不属于这组的照片，请重新选"
                        BurstSelectionRules.BurstSelectionError.DuplicateMembers ->
                            "这组里有重复成员，已停止提交"
                        BurstSelectionRules.BurstSelectionError.EmptyGroup ->
                            "这组没有照片"
                        null -> ""
                    },
                    color = discardColor,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Button(
                onClick = {
                    HapticFeedback.mediumTap(context)
                    onConfirm()
                },
                enabled = validDecision != null && !isCommitting && validDecision.discardCount > 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentColor,
                    disabledContainerColor = accentColor.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (isCommitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = when {
                            validDecision == null -> "先勾选要保留的"
                            validDecision.keepsEverything -> "这组都保留（没有要回收的）"
                            else -> "确认：回收 ${validDecision.discardCount} 张"
                        },
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Text(
                text = "移入回收站后可恢复；返回不会提交任何改动。",
                color = secondaryTextColor,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
        }
    }
}

private const val THUMBNAILS_PER_ROW = 3

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(millis))

private fun formatSpan(group: SimilarGroup): String {
    val seconds = (group.endTime - group.startTime) / 1000
    return when {
        seconds < 60 -> "${seconds} 秒内"
        seconds < 3600 -> "${seconds / 60} 分钟内"
        else -> SimpleDateFormat("M 月 d 日", Locale.CHINA).format(Date(group.startTime))
    }
}
