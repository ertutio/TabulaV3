package com.tabula.v3.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabula.v3.ui.components.LocalLiquidGlassEnabled
import com.tabula.v3.ui.theme.LocalIsDarkTheme
import com.tabula.v3.ui.theme.TabulaColors
import com.tabula.v3.ui.util.HapticFeedback

/**
 * 使用教程屏幕 - 包含各个界面和功能的使用教程
 * 
 * 功能特性：
 * 1. 分页展示不同功能的教程
 * 2. 动画演示手势操作
 * 3. 清晰的图文说明
 * 
 * @author Ti
 */
@Composable
fun TutorialScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val isDarkTheme = LocalIsDarkTheme.current
    val isLiquidGlass = LocalLiquidGlassEnabled.current
    
    val colors = MaterialTheme.colorScheme
    val backgroundColor = when {
        isLiquidGlass -> colors.background
        isDarkTheme -> Color.Black
        else -> Color(0xFFF2F2F7)
    }
    val cardColor = when {
        isLiquidGlass -> colors.surface
        isDarkTheme -> Color(0xFF1C1C1E)
        else -> Color.White
    }
    val textColor = when {
        isLiquidGlass -> colors.onSurface
        isDarkTheme -> Color.White
        else -> Color.Black
    }
    val secondaryTextColor = if (isLiquidGlass) colors.onSurfaceVariant else Color(0xFF8E8E93)
    val accentColor = TabulaColors.EyeGold
    
    // Pager 状态
    val pagerState = rememberPagerState(pageCount = { 4 })
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .statusBarsPadding()
    ) {
        // ========== 顶部标题栏 ==========
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // 返回按钮
            IconButton(
                onClick = {
                    HapticFeedback.lightTap(context)
                    onNavigateBack()
                },
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = textColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            // 标题
            Text(
                text = "使用教程",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                ),
                color = textColor,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        
        // ========== 页面指示器 ==========
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(4) { index ->
                val isSelected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (isSelected) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) accentColor
                            else secondaryTextColor.copy(alpha = 0.3f)
                        )
                )
            }
        }
        
        // ========== 教程内容分页 ==========
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            userScrollEnabled = true
        ) { page ->
            when (page) {
                0 -> SwipeGestureTutorialPage(
                    cardColor = cardColor,
                    textColor = textColor,
                    secondaryTextColor = secondaryTextColor,
                    accentColor = accentColor,
                    isDarkTheme = isDarkTheme
                )
                1 -> AlbumManagementTutorialPage(
                    cardColor = cardColor,
                    textColor = textColor,
                    secondaryTextColor = secondaryTextColor,
                    accentColor = accentColor,
                    isDarkTheme = isDarkTheme
                )
                2 -> RecycleBinTutorialPage(
                    cardColor = cardColor,
                    textColor = textColor,
                    secondaryTextColor = secondaryTextColor,
                    accentColor = accentColor,
                    isDarkTheme = isDarkTheme
                )
                3 -> SettingsTutorialPage(
                    cardColor = cardColor,
                    textColor = textColor,
                    secondaryTextColor = secondaryTextColor,
                    accentColor = accentColor,
                    isDarkTheme = isDarkTheme
                )
            }
        }
    }
}

/**
 * 手势操作教程页面 - 核心手势演示
 */
@Composable
private fun SwipeGestureTutorialPage(
    cardColor: Color,
    textColor: Color,
    secondaryTextColor: Color,
    accentColor: Color,
    isDarkTheme: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        
        // 页面标题
        Icon(
            imageVector = Icons.Outlined.Gesture,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(48.dp)
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Text(
            text = "手势操作",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = textColor
        )
        
        Text(
            text = "掌握这些手势，快速整理照片",
            style = MaterialTheme.typography.bodyMedium,
            color = secondaryTextColor,
            modifier = Modifier.padding(top = 4.dp)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // 手势动画演示区域
        SwipeGestureAnimationDemo(
            cardColor = cardColor,
            accentColor = accentColor,
            isDarkTheme = isDarkTheme
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // 手势说明列表 - 按照实际功能修正
        GestureExplanationItem(
            icon = Icons.Rounded.KeyboardArrowUp,
            iconColor = Color(0xFFFF3B30), // Red
            title = "上滑标记",
            description = "将照片移入回收站，可随时恢复或永久删除",
            cardColor = cardColor,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        GestureExplanationItem(
            icon = Icons.Rounded.KeyboardArrowDown,
            iconColor = Color(0xFF34C759), // Green
            title = "下滑整理",
            description = "将照片快速归类到指定图集",
            cardColor = cardColor,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        GestureExplanationItem(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            iconColor = Color(0xFF007AFF), // Blue
            title = "左滑下一张",
            description = "切换到下一张照片继续浏览",
            cardColor = cardColor,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        GestureExplanationItem(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            iconColor = Color(0xFF007AFF), // Blue
            title = "右滑上一张",
            description = "返回查看上一张照片",
            cardColor = cardColor,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        BottomSwipeHint(secondaryTextColor = secondaryTextColor)
    }
}

/**
 * 底部滑动提示 - 放在滚动内容最后，沉浸式设计
 */
@Composable
private fun BottomSwipeHint(
    secondaryTextColor: Color
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = null,
                tint = secondaryTextColor.copy(alpha = 0.3f),
                modifier = Modifier.size(14.dp)
            )
            
            Spacer(modifier = Modifier.width(4.dp))
            
            Text(
                text = "左右滑动切换页面",
                style = MaterialTheme.typography.labelSmall,
                color = secondaryTextColor.copy(alpha = 0.4f)
            )
            
            Spacer(modifier = Modifier.width(4.dp))
            
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = secondaryTextColor.copy(alpha = 0.3f),
                modifier = Modifier.size(14.dp)
            )
        }
        
        // 沉浸式底部间距
        Spacer(modifier = Modifier
            .height(16.dp)
            .navigationBarsPadding()
        )
    }
}

/**
 * 手势动画演示组件 - 使用 Reverse 模式实现平滑来回动画
 */
@Composable
private fun SwipeGestureAnimationDemo(
    cardColor: Color,
    accentColor: Color,
    isDarkTheme: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "gesture_demo")
    
    // 上箭头动画
    val upArrowOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -12f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "upArrow"
    )
    
    // 下箭头动画
    val downArrowOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "downArrow"
    )
    
    // 左箭头动画
    val leftArrowOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -12f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "leftArrow"
    )
    
    // 右箭头动画
    val rightArrowOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rightArrow"
    )
    
    val cardBgColor = if (isDarkTheme) Color(0xFF2C2C2E) else Color.White
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(cardColor),
        contentAlignment = Alignment.Center
    ) {
        // 中心卡片示意
        Box(
            modifier = Modifier
                .size(80.dp, 100.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(cardBgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = secondaryTextColor.copy(alpha = 0.4f),
                modifier = Modifier.size(32.dp)
            )
        }
        
        // 上方：标记
        GestureIndicator(
            icon = Icons.Rounded.KeyboardArrowUp,
            label = "标记",
            color = Color(0xFFFF3B30),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .offset(y = upArrowOffset.dp)
        )
        
        // 下方：整理
        GestureIndicator(
            icon = Icons.Rounded.KeyboardArrowDown,
            label = "整理",
            color = Color(0xFF34C759),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .offset(y = downArrowOffset.dp)
        )
        
        // 左侧：下一张
        GestureIndicator(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            label = "下一张",
            color = Color(0xFF007AFF),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 24.dp)
                .offset(x = leftArrowOffset.dp)
        )
        
        // 右侧：上一张
        GestureIndicator(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            label = "上一张",
            color = Color(0xFF007AFF),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 24.dp)
                .offset(x = rightArrowOffset.dp)
        )
    }
}

/**
 * 手势指示器
 */
@Composable
private fun GestureIndicator(
    icon: ImageVector,
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(28.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

// 用于 GestureIndicator 的 secondaryTextColor
private val secondaryTextColor = Color(0xFF8E8E93)

/**
 * 手势说明项
 */
@Composable
private fun GestureExplanationItem(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    description: String,
    cardColor: Color,
    textColor: Color,
    secondaryTextColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cardColor)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        // 文字
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = textColor
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = secondaryTextColor
            )
        }
    }
}

/**
 * 教程功能项
 */
@Composable
private fun TutorialFeatureItem(
    title: String,
    description: String,
    cardColor: Color,
    textColor: Color,
    secondaryTextColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cardColor)
            .padding(16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = textColor
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = secondaryTextColor
        )
    }
}

/**
 * 图集管理教程页面
 */
@Composable
private fun AlbumManagementTutorialPage(
    cardColor: Color,
    textColor: Color,
    secondaryTextColor: Color,
    accentColor: Color,
    isDarkTheme: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        
        Icon(
            imageVector = Icons.Outlined.Collections,
            contentDescription = null,
            tint = Color(0xFF34C759),
            modifier = Modifier.size(48.dp)
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Text(
            text = "图集管理",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = textColor
        )
        
        Text(
            text = "轻松创建和管理你的照片图集",
            style = MaterialTheme.typography.bodyMedium,
            color = secondaryTextColor,
            modifier = Modifier.padding(top = 4.dp)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // 图集网格演示
        AlbumGridDemo(
            cardColor = cardColor,
            accentColor = accentColor,
            isDarkTheme = isDarkTheme
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        TutorialFeatureItem(
            title = "下滑归类",
            description = "在主界面下滑照片，选择目标图集即可快速归类",
            cardColor = cardColor,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        TutorialFeatureItem(
            title = "创建图集",
            description = "下滑归类时选择「新建图集」可创建新图集",
            cardColor = cardColor,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        TutorialFeatureItem(
            title = "编辑图集",
            description = "长按图集可以编辑名称或删除",
            cardColor = cardColor,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        TutorialFeatureItem(
            title = "切换视图",
            description = "点击右上角可在卡片模式和图集模式之间切换",
            cardColor = cardColor,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        BottomSwipeHint(secondaryTextColor = secondaryTextColor)
    }
}

/**
 * 图集网格演示
 */
@Composable
private fun AlbumGridDemo(
    cardColor: Color,
    accentColor: Color,
    isDarkTheme: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "album_grid")
    
    // 使用 Reverse 避免跳跃
    val highlightAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "highlight"
    )
    
    val albumColors = listOf(
        Color(0xFFFF3B30),
        Color(0xFF34C759),
        Color(0xFF007AFF),
        Color(0xFFAF52DE)
    )
    
    val emojis = listOf("📸", "🏖️", "🎉", "❤️")
    val names = listOf("精选", "旅行", "聚会", "收藏")
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(cardColor)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            repeat(4) { index ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(60.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                albumColors[index].copy(
                                    alpha = if (index == 1) highlightAlpha else 0.2f
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = emojis[index],
                            fontSize = 20.sp
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = names[index],
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDarkTheme) Color.White else Color.Black,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * 回收站教程页面
 */
@Composable
private fun RecycleBinTutorialPage(
    cardColor: Color,
    textColor: Color,
    secondaryTextColor: Color,
    accentColor: Color,
    isDarkTheme: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        
        Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = null,
            tint = Color(0xFFFF9F0A),
            modifier = Modifier.size(48.dp)
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Text(
            text = "回收站",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = textColor
        )
        
        Text(
            text = "上滑标记的照片会暂存在这里",
            style = MaterialTheme.typography.bodyMedium,
            color = secondaryTextColor,
            modifier = Modifier.padding(top = 4.dp)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // 回收站动画演示
        RecycleBinDemo(
            cardColor = cardColor,
            isDarkTheme = isDarkTheme
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        TutorialFeatureItem(
            title = "进入回收站",
            description = "点击左上角的回收站图标进入",
            cardColor = cardColor,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        TutorialFeatureItem(
            title = "恢复照片",
            description = "点击照片后选择「恢复」，照片会回到原来的位置",
            cardColor = cardColor,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        TutorialFeatureItem(
            title = "永久删除",
            description = "选择「永久删除」后，照片将无法恢复",
            cardColor = cardColor,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        BottomSwipeHint(secondaryTextColor = secondaryTextColor)
    }
}

/**
 * 回收站动画演示 - 使用 Reverse 避免跳跃
 */
@Composable
private fun RecycleBinDemo(
    cardColor: Color,
    isDarkTheme: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recycle_bin")
    
    // 照片落入动画 - 使用 Reverse 来回运动
    val photoOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "photo_fall"
    )
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(cardColor),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(140.dp)) {
            val centerX = size.width / 2
            val centerY = size.height / 2
            
            // 回收站桶
            val binWidth = 50.dp.toPx()
            val binHeight = 60.dp.toPx()
            val binX = centerX - binWidth / 2
            val binY = centerY - binHeight / 2 + 15.dp.toPx()
            
            // 桶身
            drawRoundRect(
                color = Color(0xFFFF9F0A).copy(alpha = 0.3f),
                topLeft = Offset(binX, binY + 8.dp.toPx()),
                size = Size(binWidth, binHeight - 8.dp.toPx()),
                cornerRadius = CornerRadius(6.dp.toPx())
            )
            
            // 桶盖
            drawRoundRect(
                color = Color(0xFFFF9F0A).copy(alpha = 0.5f),
                topLeft = Offset(binX - 3.dp.toPx(), binY),
                size = Size(binWidth + 6.dp.toPx(), 10.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx())
            )
            
            // 照片来回移动动画
            val photoY = -30.dp.toPx() + 50.dp.toPx() * photoOffset
            val photoAlpha = 0.4f + 0.4f * (1f - photoOffset)
            val photoSize = 25.dp.toPx()
            
            drawRoundRect(
                color = Color(0xFF007AFF).copy(alpha = photoAlpha),
                topLeft = Offset(centerX - photoSize / 2, photoY),
                size = Size(photoSize, photoSize),
                cornerRadius = CornerRadius(4.dp.toPx())
            )
        }
        
        Text(
            text = "上滑照片进入回收站",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFFF9F0A),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
        )
    }
}

/**
 * 设置教程页面
 */
@Composable
private fun SettingsTutorialPage(
    cardColor: Color,
    textColor: Color,
    secondaryTextColor: Color,
    accentColor: Color,
    isDarkTheme: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        
        Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = null,
            tint = Color(0xFF8E8E93),
            modifier = Modifier.size(48.dp)
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Text(
            text = "个性化设置",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = textColor
        )
        
        Text(
            text = "打造属于你的 Tabula",
            style = MaterialTheme.typography.bodyMedium,
            color = secondaryTextColor,
            modifier = Modifier.padding(top = 4.dp)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // 设置项演示
        SettingsDemo(
            cardColor = cardColor,
            accentColor = accentColor,
            isDarkTheme = isDarkTheme
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        TutorialFeatureItem(
            title = "进入设置",
            description = "点击右上角的设置图标进入设置页面",
            cardColor = cardColor,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        TutorialFeatureItem(
            title = "显示设置",
            description = "调整卡片样式、标签布局等显示偏好",
            cardColor = cardColor,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        TutorialFeatureItem(
            title = "推荐模式",
            description = "切换随机漫步或相似推荐算法",
            cardColor = cardColor,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        TutorialFeatureItem(
            title = "振动与声音",
            description = "调整触感反馈和声音效果",
            cardColor = cardColor,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        BottomSwipeHint(secondaryTextColor = secondaryTextColor)
    }
}

/**
 * 设置页面演示
 */
@Composable
private fun SettingsDemo(
    cardColor: Color,
    accentColor: Color,
    isDarkTheme: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "settings")
    
    // 使用 Reverse 平滑切换
    val toggleProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "toggle"
    )
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(cardColor)
            .padding(16.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 模拟设置项
            SettingsDemoItem(
                title = "触感反馈",
                isToggled = toggleProgress > 0.5f,
                toggleColor = Color(0xFF34C759),
                isDarkTheme = isDarkTheme
            )
            
            SettingsDemoItem(
                title = "动态照片声音",
                isToggled = true,
                toggleColor = Color(0xFF007AFF),
                isDarkTheme = isDarkTheme
            )
            
            SettingsDemoItem(
                title = "HDR 标识",
                isToggled = toggleProgress > 0.5f,
                toggleColor = accentColor,
                isDarkTheme = isDarkTheme
            )
        }
    }
}

/**
 * 设置演示项
 */
@Composable
private fun SettingsDemoItem(
    title: String,
    isToggled: Boolean,
    toggleColor: Color,
    isDarkTheme: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isDarkTheme) Color(0xFF2C2C2E) else Color(0xFFF2F2F7))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isDarkTheme) Color.White else Color.Black
        )
        
        // 模拟开关
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isToggled) toggleColor else Color(0xFF787880).copy(alpha = 0.3f))
        ) {
            val offset by animateFloatAsState(
                targetValue = if (isToggled) 18f else 2f,
                animationSpec = tween(200),
                label = "toggle_offset"
            )
            
            Box(
                modifier = Modifier
                    .offset(x = offset.dp, y = 2.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}
