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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.tabula.v3.data.intent.IntentAssistService
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
    /**
     * 当前批是否已授权。调用方必须读授权状态机（`IntentAssistAuthorization.granted`），
     * 不要读一份界面自己的副本——两份状态在「重建批次」时会短暂不一致，
     * 表现为「已提交、批次已重建，但开关还显示启用」。
     */
    assistEnabledForBatch: Boolean,
    /** 用户是否已经看过「只发送脱敏文字」的明确说明并同意打开（打开前必须确认）。 */
    assistNoticeAcknowledged: Boolean,
    /**
     * 当前选用的服务。默认 DeepSeek，保持既有调用点不必立刻改签名。
     *
     * 界面显示的是这个值，而不是界面自己记的一份：服务与授权一样只允许一个事实来源。
     */
    assistService: IntentAssistService = IntentAssistService.DEFAULT,
    /**
     * 每个服务各自的 Key 是否已配置。缺省为空集合时退回只看 [assistKeyConfigured]，
     * 保证旧调用点（只传一个布尔）行为不变。
     */
    assistKeyConfiguredByService: Map<IntentAssistService, Boolean> = emptyMap(),
    /** 用户切换服务。实现方负责吊销本批授权并按关闭态重跑本地分析。 */
    onSelectAssistService: (IntentAssistService) -> Unit = {},
    onToggleAssistForBatch: (Boolean) -> Unit,
    onAcknowledgeAssistNotice: () -> Unit,
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
    // 「开启前确认」对话框：每次进页面回到 false，不跨批次记住。
    val listState = rememberLazyListState()
    var showAssistNotice by remember { mutableStateOf(false) }
    // 「切换服务会撤销本批授权」的提示条：切完显示一次，用户下次操作即消失。
    var serviceSwitchNotice by remember { mutableStateOf(false) }
    // 当前服务自己的 Key 状态：优先用按服务的那张表，缺省时退回旧参数。
    val serviceKeyConfigured = assistKeyConfiguredByService[assistService] ?: assistKeyConfigured

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

        // ---------- 文字辅助（实验性，默认关闭，只在本批次生效） ----------
        //
        // 四条必须让用户看见的事：
        // 1. 标「实验性」——它不是默认路径，本地 OCR + 规则才是；
        // 2. 打开前明确说明「只发送必要的脱敏 OCR 文字」，用户确认后才允许打开；
        // 3. 每次进入新批次都恢复关闭（由调用方保证），界面这里只反映当前状态；
        // 4. **服务可选**（DeepSeek / OpenCode Go），各用各的 Key；换服务 = 换外发对象，
        //    本批授权必须回收，界面在此明确写出来。
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("文字辅助", color = textColor, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    "实验性",
                    color = warn,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(warn.copy(alpha = 0.12f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpenAssistSettings) {
                    Text("设置 Key", color = accent, fontSize = 12.sp)
                }
                Switch(
                    checked = assistEnabledForBatch && serviceKeyConfigured && assistNoticeAcknowledged,
                    enabled = assistAvailable && serviceKeyConfigured,
                    onCheckedChange = { want ->
                        HapticFeedback.lightTap(context)
                        if (!want) {
                            onToggleAssistForBatch(false)
                        } else if (!assistNoticeAcknowledged) {
                            // 先让用户看到外发边界，同意后才真的打开
                            showAssistNotice = true
                        } else {
                            onToggleAssistForBatch(true)
                        }
                    }
                )
            }

            // ---- 服务选择（两个选项，各管各的 Key）----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("服务", color = secondary, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                IntentAssistService.entries.forEach { service ->
                    val isCurrent = service == assistService
                    val hasKey = assistKeyConfiguredByService[service] ?: (isCurrent && assistKeyConfigured)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isCurrent) accent.copy(alpha = 0.12f) else Color.Transparent
                            )
                            .clickable(enabled = assistAvailable && !isCurrent) {
                                HapticFeedback.lightTap(context)
                                // 换服务 → 调用方吊销本批授权并重跑本地分析（关闭态）
                                serviceSwitchNotice = true
                                onSelectAssistService(service)
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            service.displayName,
                            color = if (isCurrent) accent else secondary,
                            fontSize = 11.sp,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                        )
                        if (hasKey) {
                            Spacer(Modifier.width(3.dp))
                            Text("•", color = accent, fontSize = 11.sp)
                        }
                    }
                }
            }

            Text(
                when {
                    !assistAvailable -> "本地 OCR 与规则照常可用；此处未提供文字辅助实现"
                    !serviceKeyConfigured ->
                        // 保留「未配置 Key」这个连续短语：既有页面流程测试按它断言
                        // （IntentBatchPageFlowTest.assistSwitchIsOffByDefaultAndBlockedWithoutKey）。
                        "未配置 Key（当前服务 ${assistService.displayName}）。" +
                            "默认路径是本地 OCR + 规则，不需要它"
                    assistEnabledForBatch -> "本批次已启用：只发送脱敏后的文字片段，不发原图"
                    else -> "默认关闭。开启后只向 ${assistService.displayName} 发送必要的脱敏 OCR 文字"
                },
                color = secondary,
                fontSize = 11.sp
            )
            Text(
                "默认关闭、按批授权；只发送脱敏文字，不发原图。" +
                    "当前服务 ${assistService.displayName}（Key 与另一服务独立存放）。",
                color = secondary,
                fontSize = 10.sp
            )
            if (serviceSwitchNotice) {
                Text(
                    "切换会撤销本批授权，需要重新开启。",
                    color = warn,
                    fontSize = 10.sp
                )
            }
        }

        if (showAssistNotice) {
            AlertDialog(
                onDismissRequest = { showAssistNotice = false },
                title = { Text("开启前请确认") },
                text = {
                    Column {
                        Text(
                            "开启后，仅当本地规则判断证据不足时，才会向 ${assistService.displayName} 发送" +
                                "**必要的脱敏 OCR 文字片段**（已掩掉验证码、提取码、链接、手机号、订单号）。",
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "不会发送原图、缩略图、图片编码或文件路径。模型只给分类建议，" +
                                "不能覆盖保护规则，也不能单独认定过期或触发清理。",
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "这是实验性功能，默认关闭，且每次进入新批次都会恢复关闭。",
                            fontSize = 12.sp,
                            color = secondary
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        onAcknowledgeAssistNotice()
                        onToggleAssistForBatch(true)
                        showAssistNotice = false
                    }) { Text("明白，本批次开启") }
                },
                dismissButton = {
                    TextButton(onClick = { showAssistNotice = false }) { Text("保持关闭") }
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
                    state = listState,
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
                    items(batch.ordered, key = { intentIdentity(it) }) { candidate ->
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
                        items(batch.uncertain, key = { intentIdentity(it) }) { candidate ->
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
 * 文字辅助的 Key 管理对话框（**按服务**）。
 *
 * 行为对齐 #94 方案：可单独重填、更换、删除；删除后本地规则照常工作，
 * 不阻塞任何本地功能。输入框不回显已存内容（只显示「已配置」状态）。
 *
 * ## 多服务（本次新增）
 *
 * 两个服务各有独立 Key 文件，因此对话框**逐服务显示状态、逐服务写入/删除**：
 * 在这里删掉 Go 的 Key 不会影响 DeepSeek 的那份（反之亦然）。用户不需要先切服务
 * 才能管理另一家的 Key——但只有当前服务的 Key 会真正被用于外发。
 *
 * 参数保持向后兼容：不传 [services] / [onSaveForService] 时退化成单服务语义
 * （#95 的调用点行为不变）。
 */
@Composable
fun IntentAssistKeyDialog(
    currentState: String,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    /** 当前服务（决定标题与「正在编辑哪一家」）。 */
    service: IntentAssistService = IntentAssistService.DEFAULT,
    /** 每个服务的 Key 状态文案（`describeState()` 的结果），用于并列展示。 */
    serviceStates: Map<IntentAssistService, String> = emptyMap(),
    /** 逐服务保存；提供时优先于 [onSave]。 */
    onSaveForService: ((IntentAssistService, String) -> Unit)? = null,
    /** 逐服务删除；提供时优先于 [onDelete]。 */
    onDeleteForService: ((IntentAssistService) -> Unit)? = null
) {
    var input by remember { mutableStateOf("") }
    // 对话框内可切换编辑哪一家的 Key——不必先回页面切服务。
    var editing by remember(service) { mutableStateOf(service) }
    val isDark = LocalIsDarkTheme.current
    val textColor = if (isDark) Color.White else Color.Black
    val secondary = Color(0xFF8E8E93)
    val accent = Color(0xFF007AFF)
    val stateForEditing = serviceStates[editing] ?: currentState

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
                if (serviceStates.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IntentAssistService.entries.forEach { candidate ->
                            val isEditing = candidate == editing
                            Text(
                                candidate.displayName,
                                color = if (isEditing) accent else secondary,
                                fontSize = 12.sp,
                                fontWeight = if (isEditing) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier
                                    .padding(end = 10.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isEditing) accent.copy(alpha = 0.12f) else Color.Transparent
                                    )
                                    .clickable {
                                        editing = candidate
                                        input = ""
                                    }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    "${editing.displayName} 当前：$stateForEditing",
                    color = textColor,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("粘贴新的 Key（${editing.keyHint}，留空表示不修改）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "两个服务的 Key 分开存放：在这里更换或删除只影响 ${editing.displayName}。",
                    color = secondary,
                    fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (input.isNotBlank()) {
                    val saveForService = onSaveForService
                    if (saveForService != null) saveForService(editing, input) else onSave(input)
                }
                input = ""
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    val deleteForService = onDeleteForService
                    if (deleteForService != null) deleteForService(editing) else onDelete()
                    input = ""
                    onDismiss()
                }) { Text("删除 ${editing.displayName} 的 Key", color = Color(0xFFFF3B30)) }
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
