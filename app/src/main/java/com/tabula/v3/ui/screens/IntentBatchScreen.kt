package com.tabula.v3.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.tabula.v3.data.intent.IntentBatch
import com.tabula.v3.data.intent.IntentCandidate
import com.tabula.v3.data.intent.IntentCategory
import com.tabula.v3.data.intent.IntentRecommendation
import com.tabula.v3.ui.theme.LocalIsDarkTheme
import com.tabula.v3.di.CoilSetup
import com.tabula.v3.ui.util.HapticFeedback

/**
 * 「按整理目的凑一批图」（路线图第 8 项 / #94）。
 *
 * 这一页只做一件事：把暂存清理范围里**最可能愿意清**的图片排在前面，并说清楚为什么。
 * 三条不可越过的边界都体现在界面上：
 * 1. **不自动删**：勾选与「移入回收站」完全复用暂存清理的确认链路（仍走回收站，可撤销）；
 * 2. **保护类不出现**：被保护规则排除的图片不进列表，只在页脚显示张数；
 * 3. **不硬凑**：高置信不够时页脚如实写「只找到 N 张」，不拿低置信项填满。
 *
 * 理由文案由 [com.tabula.v3.data.intent.IntentTextSanitizer] 脱敏，界面不做二次拼接，
 * 因此不会把验证码、提取码或链接正文漏到屏幕上。
 */
@Composable
fun IntentBatchContent(
    title: String,
    batch: IntentBatch?,
    selectedIdentityKeys: Set<String>,
    isAnalyzing: Boolean,
    isSaving: Boolean,
    engineNote: String?,
    statusLine: String,
    assistAvailable: Boolean,
    assistKeyConfigured: Boolean,
    assistEnabledForBatch: Boolean,
    onToggleAssistForBatch: (Boolean) -> Unit,
    onOpenAssistSettings: () -> Unit,
    onToggleImage: (IntentCandidate) -> Unit,
    onRetainSelected: () -> Unit,
    onConfirm: () -> Unit,
    onAnalyze: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val isDark = LocalIsDarkTheme.current
    val backgroundColor = if (isDark) Color.Black else Color(0xFFF2F2F7)
    val cardColor = if (isDark) Color(0xFF1C1C1E) else Color.White
    val textColor = if (isDark) Color.White else Color.Black
    val secondary = Color(0xFF8E8E93)
    val accent = Color(0xFF007AFF)
    val warn = Color(0xFFFF9500)
    val imageLoader = remember(context) { CoilSetup.getImageLoader(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .statusBarsPadding()
    ) {
        // ---------- 顶部栏 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = textColor)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = textColor, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "只排序，不会自动删除；仍由你决定保留或移入回收站",
                    color = secondary,
                    fontSize = 11.sp
                )
            }
        }

        // ---------- 文字辅助开关（默认关闭，本批次生效） ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("文字辅助（可选）", color = textColor, fontSize = 13.sp)
                Text(
                    when {
                        !assistAvailable -> "未提供文字辅助实现"
                        !assistKeyConfigured -> "未配置 Key；本地规则照常可用"
                        else -> "仅发送脱敏后的文字片段，不发原图"
                    },
                    color = secondary,
                    fontSize = 11.sp
                )
            }
            TextButton(onClick = onOpenAssistSettings) {
                Text("设置", color = accent, fontSize = 12.sp)
            }
            Switch(
                checked = assistEnabledForBatch && assistKeyConfigured,
                enabled = assistAvailable && assistKeyConfigured,
                onCheckedChange = {
                    HapticFeedback.lightTap(context)
                    onToggleAssistForBatch(it)
                }
            )
        }

        if (statusLine.isNotBlank()) {
            Text(
                statusLine,
                color = secondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        when {
            engineNote != null -> {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(engineNote, color = secondary, fontSize = 14.sp)
                }
            }
            isAnalyzing -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = accent)
                        Spacer(Modifier.height(12.dp))
                        Text("正在本地识别文字…", color = secondary, fontSize = 13.sp)
                    }
                }
            }
            batch == null || batch.isEmpty -> {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "这批范围里没有找到足够明确的候选。",
                            color = secondary,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "证据不足时我们不会硬凑一批；可以换来源，或过几天再来。",
                            color = secondary,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onAnalyze) { Text("重新分析") }
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "hint") {
                        Text(
                            "按「为什么值得清」排序：有可核对截止时间的在前，越新越靠前。",
                            color = secondary,
                            fontSize = 11.sp
                        )
                    }
                    items(batch.ordered, key = { "rec_${it.image.volumeName}|${it.image.id}" }) { candidate ->
                        IntentCandidateRow(
                            candidate = candidate,
                            checked = intentIdentity(candidate) in selectedIdentityKeys,
                            cardColor = cardColor,
                            textColor = textColor,
                            secondary = secondary,
                            accent = accent,
                            imageLoader = imageLoader,
                            onToggle = { onToggleImage(candidate) }
                        )
                    }
                    if (batch.uncertain.isNotEmpty()) {
                        item(key = "uncertain_header") {
                            Text(
                                "证据不足，需要你自己看（默认不勾选）",
                                color = secondary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        items(batch.uncertain, key = { "unc_${it.image.volumeName}|${it.image.id}" }) { candidate ->
                            IntentCandidateRow(
                                candidate = candidate,
                                checked = intentIdentity(candidate) in selectedIdentityKeys,
                                cardColor = cardColor,
                                textColor = textColor,
                                secondary = secondary,
                                accent = accent,
                                imageLoader = imageLoader,
                                onToggle = { onToggleImage(candidate) }
                            )
                        }
                    }
                    item(key = "footer") {
                        Column(modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                            Text(
                                "本轮：推荐 ${batch.recommended.size} 张 · 低优先级 ${batch.lowerPriority.size} 张" +
                                    " · 待定 ${batch.uncertain.size} 张",
                                color = secondary,
                                fontSize = 11.sp
                            )
                            if (batch.protectedCount > 0 || batch.evergreenCount > 0) {
                                Text(
                                    "已排除 ${batch.protectedCount} 张保护项（票务/订单/凭证/课件/长期保留）" +
                                        if (batch.evergreenCount > 0) "，以及 ${batch.evergreenCount} 张长文资料" else "",
                                    color = secondary,
                                    fontSize = 11.sp
                                )
                            }
                            if (batch.shortOfLimit) {
                                Text(
                                    "只找到 ${batch.ordered.size} 张足够明确的候选，没有为了凑满批次硬塞。",
                                    color = secondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                // ---------- 底部操作条（与暂存清理同一条回收站链路） ----------
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardColor)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "已选 ${selectedIdentityKeys.size} 张",
                            color = secondary,
                            fontSize = 12.sp
                        )
                        TextButton(
                            onClick = { HapticFeedback.lightTap(context); onRetainSelected() },
                            enabled = selectedIdentityKeys.isNotEmpty() && !isSaving
                        ) {
                            Text("长期保留", color = secondary, fontSize = 12.sp)
                        }
                    }
                    Button(
                        onClick = { HapticFeedback.mediumTap(context); onConfirm() },
                        enabled = selectedIdentityKeys.isNotEmpty() && !isSaving,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = warn,
                            disabledContainerColor = warn.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                if (selectedIdentityKeys.isEmpty()) "选择要移入回收站的照片"
                                else "移入回收站（${selectedIdentityKeys.size} 张）",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Text(
                        "移入回收站后可在回收站恢复，不会立刻删除文件。",
                        color = secondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

private fun intentIdentity(candidate: IntentCandidate): String =
    "${candidate.image.volumeName ?: ""}|${candidate.image.id}"

@Composable
private fun IntentCandidateRow(
    candidate: IntentCandidate,
    checked: Boolean,
    cardColor: Color,
    textColor: Color,
    secondary: Color,
    accent: Color,
    imageLoader: coil.ImageLoader,
    onToggle: () -> Unit
) {
    val verdict = candidate.verdict
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardColor)
            .clickable { onToggle() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            AsyncImage(
                model = candidate.image.uri,
                imageLoader = imageLoader,
                // 缩略图必须有无障碍标签：读屏用户与设备测试都靠它定位这张图。
                // 用文件名（不含路径），既是稳定标识也不泄露目录结构。
                contentDescription = candidate.image.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            if (checked) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = "已选中",
                    tint = accent,
                    modifier = Modifier.size(20.dp).align(Alignment.TopEnd)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    categoryLabel(verdict.category),
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                if (verdict.recommendation == IntentRecommendation.LOWER_PRIORITY) {
                    Text(" · 低优先级", color = secondary, fontSize = 10.sp)
                }
                if (verdict.recommendation == IntentRecommendation.UNCERTAIN) {
                    Text(" · 待定", color = secondary, fontSize = 10.sp)
                }
                if (verdict.assistantUsed) {
                    Text(" · 文字辅助", color = secondary, fontSize = 10.sp)
                }
            }
            Text(
                verdict.reason,
                color = textColor,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 分类的界面文案。刻意短，且不含任何原文片段。 */
internal fun categoryLabel(category: IntentCategory): String = when (category) {
    IntentCategory.EXPIRED_COUPON -> "优惠"
    IntentCategory.OLD_PRICE -> "旧比价"
    IntentCategory.STALE_NEWS -> "时效内容"
    IntentCategory.SHARE -> "分享"
    IntentCategory.TEMP_CODE -> "临时码"
    IntentCategory.EVENT_REMINDER -> "活动提醒"
    IntentCategory.PROTECTED -> "保护"
    IntentCategory.EVERGREEN -> "长文资料"
    IntentCategory.UNCERTAIN -> "看不清"
}

/**
 * 文字辅助的 Key 管理对话框。
 *
 * 行为对齐 #94 方案：可单独重填、更换、删除；删除后本地规则照常工作，
 * 不阻塞任何本地功能。输入框不回显已存内容（只显示「已配置」状态）。
 */
@Composable
fun IntentAssistKeyDialog(
    currentState: String,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    val isDark = LocalIsDarkTheme.current
    val textColor = if (isDark) Color.White else Color.Black
    val secondary = Color(0xFF8E8E93)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文字辅助 API Key") },
        text = {
            Column {
                Text(
                    "Key 只保存在 App 私有目录（不参与备份与迁移），不写入日志或导出。" +
                        "不配置也能用本地规则；失效时自动回退本地结果。",
                    color = secondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                Text("当前：$currentState", color = textColor, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("粘贴新的 Key（留空表示不修改）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (input.isNotBlank()) onSave(input)
                input = ""
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    onDelete()
                    input = ""
                    onDismiss()
                }) { Text("删除 Key", color = Color(0xFFFF3B30)) }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

/** 小号提示条：用于「OCR 不可用」这类**必须让用户知道**的降级说明。 */
@Composable
fun IntentNotice(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = Color(0xFF8E8E93),
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color(0xFF8E8E93), fontSize = 11.sp)
    }
}
