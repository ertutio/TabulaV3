package com.tabula.v3.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
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
import com.tabula.v3.data.cleanup.StagedCleanupDayGroup
import com.tabula.v3.data.cleanup.StagedCleanupRules
import com.tabula.v3.data.cleanup.StagedCleanupSelection
import com.tabula.v3.data.cleanup.StagedCleanupSource
import com.tabula.v3.data.cleanup.cleanupIdentityKey
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.di.CoilSetup
import com.tabula.v3.ui.theme.LocalIsDarkTheme
import com.tabula.v3.ui.util.HapticFeedback
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 暂存清理（路线图第 1 项）。
 *
 * 入口：设置 → 数据统计 上方的「暂存清理」，或主界面顶部栏的清理按钮。
 *
 * 三步走，每一步都能单独退出且不改动任何照片：
 * 1. [StagedCleanupSourcePickerContent]：首次进入选来源（截图 / 下载 / 聊天保存目录）。
 * 2. [StagedCleanupCandidateContent]：按存入日期分组列出候选，超过保留天数的
 *    默认展开，未到期与时间未知的分列——用户勾选要清理的那些。
 * 3. 用户在底部条确认后，选中项走**现有回收站**（`RecycleBinManager.addAllToRecycleBin`），
 *    与卡片上滑删除完全同一条路径，因此回收站恢复同样可用。
 *
 * 这里不直接删除文件、不调用 MediaStore，也不写回收站——那些都在调用方
 * （MainActivity）里，界面只回传用户的选择（照片列表或身份键）。
 */

/** 来源选择页：把候选来源列出来让用户勾。 */
@Composable
fun StagedCleanupSourcePickerContent(
    sources: List<StagedCleanupSource>,
    selectedSourceKeys: Set<String>,
    isLoading: Boolean,
    onToggleSource: (String) -> Unit,
    onConfirm: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val isDarkTheme = LocalIsDarkTheme.current
    val backgroundColor = if (isDarkTheme) Color.Black else Color(0xFFF2F2F7)
    val cardColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color.White
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val secondaryTextColor = Color(0xFF8E8E93)
    val accentColor = Color(0xFF007AFF)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .statusBarsPadding()
    ) {
        StagedCleanupHeader(
            title = "暂存清理",
            subtitle = "选择要清理的来源",
            textColor = textColor,
            secondaryTextColor = secondaryTextColor,
            onNavigateBack = onNavigateBack
        )

        Text(
            text = "先选来源：截图、下载，以及聊天软件保存图片的目录。" +
                "之后每次进入会直接列出这些来源里存放超过七天的照片。",
            color = secondaryTextColor,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        when {
            isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = accentColor)
                        Spacer(Modifier.height(12.dp))
                        Text("正在扫描相册…", color = secondaryTextColor, fontSize = 13.sp)
                    }
                }
            }

            sources.isEmpty() -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "没有找到截图或下载目录。可能相册权限未开启，或者这台设备没有这些目录。",
                        color = secondaryTextColor,
                        fontSize = 14.sp
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // 审计修复（#84）：全部来源分组展示。
                    // 「推荐」= 截图/下载，预勾；「其他」= 相机、聊天软件等，
                    // 用户自己决定要不要清。两组都只是勾选项，不改变任何行为。
                    val recommended = sources.filter { it.isRecommended }
                    val others = sources.filterNot { it.isRecommended }

                    if (recommended.isNotEmpty()) {
                        item(key = "hdr_recommended") {
                            SourceGroupHeader(
                                title = "推荐清理",
                                subtitle = "截图与下载目录，已为你勾选",
                                secondaryTextColor = secondaryTextColor
                            )
                        }
                        items(recommended, key = { "rec|${it.key.volumeName}|${it.key.relativePath}" }) { source ->
                            SourceRowItem(
                                source = source,
                                selectedSourceKeys = selectedSourceKeys,
                                cardColor = cardColor,
                                textColor = textColor,
                                secondaryTextColor = secondaryTextColor,
                                accentColor = accentColor,
                                onToggleSource = onToggleSource
                            )
                        }
                    }

                    if (others.isNotEmpty()) {
                        item(key = "hdr_others") {
                            SourceGroupHeader(
                                title = "其他来源",
                                subtitle = "相机、聊天软件等保存目录，需要你自己勾选",
                                secondaryTextColor = secondaryTextColor
                            )
                        }
                        items(others, key = { "oth|${it.key.volumeName}|${it.key.relativePath}" }) { source ->
                            SourceRowItem(
                                source = source,
                                selectedSourceKeys = selectedSourceKeys,
                                cardColor = cardColor,
                                textColor = textColor,
                                secondaryTextColor = secondaryTextColor,
                                accentColor = accentColor,
                                onToggleSource = onToggleSource
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        HapticFeedback.mediumTap(context)
                        onConfirm()
                    },
                    enabled = selectedSourceKeys.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        disabledContainerColor = accentColor.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .navigationBarsPadding()
                        .height(50.dp)
                ) {
                    Text(
                        text = if (selectedSourceKeys.isEmpty()) {
                            "请选择至少一个来源"
                        } else {
                            "查看 ${selectedSourceKeys.size} 个来源的旧图"
                        },
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/** 候选页：按存入日期分组，用户勾选后确认进回收站。 */
@Composable
fun StagedCleanupCandidateContent(
    selection: StagedCleanupSelection,
    selectedIdentityKeys: Set<String>,
    isSaving: Boolean,
    onToggleImage: (ImageFile) -> Unit,
    onToggleGroup: (List<ImageFile>, Boolean) -> Unit,
    onSelectAllOld: () -> Unit,
    onClearSelection: () -> Unit,
    onSkip: () -> Unit,
    onRetainSelected: () -> Unit,
    onConfirm: () -> Unit,
    onNavigateBack: () -> Unit,
    onChangeSources: () -> Unit
) {
    val context = LocalContext.current
    val isDarkTheme = LocalIsDarkTheme.current
    val backgroundColor = if (isDarkTheme) Color.Black else Color(0xFFF2F2F7)
    val cardColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color.White
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val secondaryTextColor = Color(0xFF8E8E93)
    val accentColor = Color(0xFF007AFF)
    val imageLoader = remember(context) { CoilSetup.getImageLoader(context) }

    var sourcesExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .statusBarsPadding()
    ) {
        StagedCleanupHeader(
            title = "暂存清理",
            subtitle = "存放超过 ${StagedCleanupRules.DEFAULT_RETENTION_DAYS} 天的截图与下载图",
            textColor = textColor,
            secondaryTextColor = secondaryTextColor,
            onNavigateBack = onNavigateBack
        )

        // 来源摘要（可展开查看本轮范围）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { sourcesExpanded = !sourcesExpanded }
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (sourcesExpanded) {
                    "本轮来源：" + selection.selectedSources.joinToString("、") { it.displayName }
                } else {
                    "本轮 ${selection.selectedSources.size} 个来源 · 点击查看"
                },
                color = secondaryTextColor,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
                maxLines = if (sourcesExpanded) 4 else 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = {
                HapticFeedback.lightTap(context)
                onChangeSources()
            }) {
                Text("换来源", color = accentColor, fontSize = 12.sp)
            }
        }

        if (selection.isEmpty) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "这些来源里没有待清理的照片。\n可以换来源，或过几天再来。",
                    color = secondaryTextColor,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                if (selection.oldGroups.isNotEmpty()) {
                    item(key = "old_header") {
                        StagedCleanupSectionHeader(
                            title = "超过 ${StagedCleanupRules.DEFAULT_RETENTION_DAYS} 天（建议清理）",
                            trailing = "${selection.oldImages.size} 张",
                            textColor = textColor,
                            secondaryTextColor = secondaryTextColor
                        )
                    }
                    selection.oldGroups.forEach { group ->
                        item(key = "old_day_${group.dayStartMillis}") {
                            StagedCleanupDaySection(
                                group = group,
                                selectedIdentityKeys = selectedIdentityKeys,
                                imageLoader = imageLoader,
                                cardColor = cardColor,
                                textColor = textColor,
                                secondaryTextColor = secondaryTextColor,
                                accentColor = accentColor,
                                onToggleImage = onToggleImage,
                                onToggleGroup = onToggleGroup
                            )
                        }
                    }
                }

                if (selection.recentGroups.isNotEmpty()) {
                    item(key = "recent_header") {
                        StagedCleanupSectionHeader(
                            title = "最近 ${StagedCleanupRules.DEFAULT_RETENTION_DAYS} 天",
                            trailing = "${selection.recentGroups.sumOf { it.images.size }} 张",
                            textColor = textColor,
                            secondaryTextColor = secondaryTextColor
                        )
                    }
                    selection.recentGroups.forEach { group ->
                        item(key = "recent_day_${group.dayStartMillis}") {
                            StagedCleanupDaySection(
                                group = group,
                                selectedIdentityKeys = selectedIdentityKeys,
                                imageLoader = imageLoader,
                                cardColor = cardColor,
                                textColor = textColor,
                                secondaryTextColor = secondaryTextColor,
                                accentColor = accentColor,
                                onToggleImage = onToggleImage,
                                onToggleGroup = onToggleGroup
                            )
                        }
                    }
                }

                if (selection.unknownTimeImages.isNotEmpty()) {
                    item(key = "unknown_header") {
                        StagedCleanupSectionHeader(
                            title = "存入时间未知",
                            trailing = "${selection.unknownTimeImages.size} 张 · 请手动确认",
                            textColor = textColor,
                            secondaryTextColor = secondaryTextColor
                        )
                    }
                    item(key = "unknown_row") {
                        Text(
                            text = "这些照片相册没有记录存入时间，因此无法判断新旧。" +
                                "默认不勾选，需要你逐张看一眼再决定。",
                            color = secondaryTextColor,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        StagedCleanupDaySection(
                            group = StagedCleanupDayGroup(0L, selection.unknownTimeImages),
                            selectedIdentityKeys = selectedIdentityKeys,
                            imageLoader = imageLoader,
                            cardColor = cardColor,
                            textColor = textColor,
                            secondaryTextColor = secondaryTextColor,
                            accentColor = accentColor,
                            onToggleImage = onToggleImage,
                            onToggleGroup = onToggleGroup,
                            showDateInHeader = false
                        )
                    }
                }
            }

            // ========== 底部操作条 ==========
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        HapticFeedback.lightTap(context)
                        if (selectedIdentityKeys.isEmpty()) onSelectAllOld() else onClearSelection()
                    }) {
                        Text(
                            text = if (selectedIdentityKeys.isEmpty()) {
                                "全选超过 ${StagedCleanupRules.DEFAULT_RETENTION_DAYS} 天的"
                            } else {
                                "取消全选"
                            },
                            color = accentColor,
                            fontSize = 13.sp
                        )
                    }
                    TextButton(
                        onClick = {
                            HapticFeedback.lightTap(context)
                            onSkip()
                        },
                        enabled = !isSaving
                    ) {
                        Text("这次先不看", color = secondaryTextColor, fontSize = 13.sp)
                    }
                }

                // 「长期保留」与「这次先不看」是同一条底栏上的两个不同承诺：
                // 跳过只是这次不显示（重启后照片回来），保留是以后都别再提
                // （写入偏好，跨重启生效，且只影响暂存清理范围）。
                if (selectedIdentityKeys.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            HapticFeedback.lightTap(context)
                            onRetainSelected()
                        },
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "长期保留这 ${selectedIdentityKeys.size} 张（以后不再提示）",
                            color = secondaryTextColor,
                            fontSize = 12.sp
                        )
                    }
                }

                Button(
                    onClick = {
                        HapticFeedback.mediumTap(context)
                        onConfirm()
                    },
                    enabled = selectedIdentityKeys.isNotEmpty() && !isSaving,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF9500),
                        disabledContainerColor = Color(0xFFFF9500).copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = if (selectedIdentityKeys.isEmpty()) {
                                "选择要移入回收站的照片"
                            } else {
                                "移入回收站（${selectedIdentityKeys.size} 张）"
                            },
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Text(
                    text = "移入回收站后可在回收站恢复，不会立刻删除文件。",
                    color = secondaryTextColor,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun StagedCleanupHeader(
    title: String,
    subtitle: String,
    textColor: Color,
    secondaryTextColor: Color,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = {
            HapticFeedback.lightTap(context)
            onNavigateBack()
        }) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = textColor,
                modifier = Modifier.size(24.dp)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = textColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = secondaryTextColor, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StagedCleanupSectionHeader(
    title: String,
    trailing: String,
    textColor: Color,
    secondaryTextColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(trailing, color = secondaryTextColor, fontSize = 12.sp)
    }
}

@Composable
private fun StagedCleanupDaySection(
    group: StagedCleanupDayGroup,
    selectedIdentityKeys: Set<String>,
    imageLoader: coil.ImageLoader,
    cardColor: Color,
    textColor: Color,
    secondaryTextColor: Color,
    accentColor: Color,
    onToggleImage: (ImageFile) -> Unit,
    onToggleGroup: (List<ImageFile>, Boolean) -> Unit,
    showDateInHeader: Boolean = true
) {
    val context = LocalContext.current
    val allSelected = group.images.isNotEmpty() &&
        group.images.all { cleanupIdentityKey(it) in selectedIdentityKeys }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (showDateInHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        HapticFeedback.lightTap(context)
                        onToggleGroup(group.images, !allSelected)
                    }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatDay(group.dayStartMillis),
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${group.images.size} 张 · ${if (allSelected) "取消这天" else "全选这天"}",
                    color = secondaryTextColor,
                    fontSize = 12.sp
                )
            }
        }

        // 按每行 3 张切块手排：内嵌在 LazyColumn 里时不依赖嵌套滚动测量，
        // 行高由 cell 的 aspectRatio(1f) 决定，缺口用等宽占位补齐（不抖动）。
        group.images.chunked(THUMBNAILS_PER_ROW).forEach { rowImages ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                rowImages.forEach { image ->
                    val identityKey = cleanupIdentityKey(image)
                    val isSelected = identityKey in selectedIdentityKeys
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(cardColor)
                            .clickable {
                                HapticFeedback.lightTap(context)
                                onToggleImage(image)
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
                        if (isSelected) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(accentColor.copy(alpha = 0.25f))
                            )
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = "已选中",
                                tint = Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(20.dp)
                                    .background(accentColor, CircleShape)
                            )
                        }
                    }
                }
                repeat(THUMBNAILS_PER_ROW - rowImages.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SourceRow(
    source: StagedCleanupSource,
    isSelected: Boolean,
    cardColor: Color,
    textColor: Color,
    secondaryTextColor: Color,
    accentColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isSelected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isSelected) accentColor else secondaryTextColor,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(source.displayName, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                text = "${source.imageCount} 张 · ${source.key.relativePath}",
                color = secondaryTextColor,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 固定高度网格：内嵌在 LazyColumn 里，按行数算高，避免嵌套滚动的测量问题。 */
private val THUMBNAILS_PER_ROW = 3

private fun formatDay(dayStartMillis: Long): String =
    SimpleDateFormat("M 月 d 日 EEEE", Locale.CHINA).format(Date(dayStartMillis))

/** 来源分组的标题行（推荐 / 其他）。 */
@Composable
private fun SourceGroupHeader(
    title: String,
    subtitle: String,
    secondaryTextColor: Color
) {
    Column(modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)) {
        Text(
            text = title,
            color = secondaryTextColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(text = subtitle, color = secondaryTextColor, fontSize = 11.sp)
    }
}

/** 单个来源行（推荐与其他两组共用，行为完全一致）。 */
@Composable
private fun SourceRowItem(
    source: StagedCleanupSource,
    selectedSourceKeys: Set<String>,
    cardColor: Color,
    textColor: Color,
    secondaryTextColor: Color,
    accentColor: Color,
    onToggleSource: (String) -> Unit
) {
    val context = LocalContext.current
    val key = "${source.key.volumeName}|${source.key.relativePath}"
    SourceRow(
        source = source,
        isSelected = key in selectedSourceKeys,
        cardColor = cardColor,
        textColor = textColor,
        secondaryTextColor = secondaryTextColor,
        accentColor = accentColor,
        onClick = {
            HapticFeedback.lightTap(context)
            onToggleSource(key)
        }
    )
    Spacer(Modifier.height(8.dp))
}
