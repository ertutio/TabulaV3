package com.tabula.v3.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    onNavigateBack: () -> Unit,
    batchUndo: @Composable (Modifier) -> Unit = {}
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

        // ---------- 文字辅助（压成一行，实验性，默认关闭，只在本批次生效） ----------
        //
        // 四条必须让用户看见的事：
        // 1. 标「实验性」——它不是默认路径，本地 OCR + 规则才是；
        // 2. 打开前明确说明「只发送必要的脱敏 OCR 文字」，用户确认后才允许打开；
        // 3. 每次进入新批次都恢复关闭（由调用方保证），界面这里只反映当前状态；
        // 4. **服务可选**（DeepSeek / OpenCode Go），各用各的 Key；换服务 = 换外发对象，
        //    本批授权必须回收。
        //
        // 布局：一行为主体（标签 + 服务名 + 状态 + 开关），服务选择与两套 Key 的
        // 管理都收进同一个配置入口（现有弹层）。这样列表能往上提，照片是页面主体。
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 「文字辅助 · 实验性」：分成两段是为了让「实验性」保留警示色
                Text("文字辅助", color = textColor, fontSize = 13.sp)
                Text(" · ", color = secondary, fontSize = 13.sp)
                Text(
                    "实验性",
                    color = warn,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                // 当前服务：点这一处进配置（服务选择 + 两套 Key）
                Text(
                    text = assistService.displayName,
                    color = if (serviceKeyConfigured) secondary else warn,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = assistAvailable) {
                            HapticFeedback.lightTap(context)
                            onOpenAssistSettings()
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
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

            // 状态/配置入口：缺 Key 时这里就是明确的配置入口（整行可点）
            Text(
                text = when {
                    !assistAvailable -> "本地 OCR 与规则照常可用；此处未提供文字辅助实现"
                    !serviceKeyConfigured ->
                        // 保留「未配置 Key」这个连续短语：既有页面流程测试按它断言
                        // （IntentBatchPageFlowTest.assistSwitchIsOffByDefaultAndBlockedWithoutKey）。
                        "未配置 Key（当前服务 ${assistService.displayName}）· 点此配置"
                    assistEnabledForBatch -> "本批次已启用：只发送脱敏后的文字片段，不发原图"
                    else -> "默认关闭 · 只向 ${assistService.displayName} 发送脱敏文字"
                },
                color = if (!assistAvailable || serviceKeyConfigured) secondary else warn,
                fontSize = 11.sp,
                modifier = if (assistAvailable && !serviceKeyConfigured) {
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            HapticFeedback.lightTap(context)
                            onOpenAssistSettings()
                        }
                        .padding(vertical = 2.dp)
                } else {
                    Modifier.padding(vertical = 2.dp)
                }
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
                                "必要的脱敏 OCR 文字片段（已遮盖验证码、提取码、链接、手机号、订单号）。",
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

        // ---------- 主体：加载 / 空 / 失败 三种状态共用同一布局 ----------
        //
        // 之前三种状态各自实现（有的 32dp 内边距、有的没有；有的居中 Box、有的列内），
        // 切换时会看到文字上下跳。现在统一走同一个占位块：同样的居中方式、
        // 同样的 32dp 边距、同样的文字层级（标题 14sp / 说明 12sp / 可选操作按钮）。
        // 照片列表是页面主体，只在真正有候选时才换成列表。
        val settleState: IntentBatchSettleState? = when {
            engineNote != null -> IntentBatchSettleState.Failure(engineNote)
            isAnalyzing -> IntentBatchSettleState.Loading
            batch == null || batch.isEmpty -> IntentBatchSettleState.Empty
            else -> null
        }

        if (settleState != null) {
            if (statusLine.isNotBlank()) {
                Text(
                    statusLine,
                    color = secondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
            IntentBatchSettlePlaceholder(
                state = settleState,
                accent = accent,
                secondary = secondary,
                onAnalyze = onAnalyze
            )
            batchUndo(Modifier.navigationBarsPadding())
        }

        if (settleState == null) {
            // 走到这里说明 batch 非空（settleState 的判定里已经排除了 null / isEmpty）
            val visibleBatch = requireNotNull(batch) { "settleState == null 时 batch 必须非空" }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (statusLine.isNotBlank()) {
                    item(key = "status") {
                        Text(statusLine, color = secondary, fontSize = 11.sp)
                    }
                }
                item(key = "hint") {
                    Text(
                        "按「为什么值得清」排序：有可核对截止时间的在前，越新越靠前。",
                        color = secondary,
                        fontSize = 11.sp
                    )
                }
                items(visibleBatch.ordered, key = { intentIdentity(it) }) { candidate ->
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
                    if (visibleBatch.uncertain.isNotEmpty()) {
                        item(key = "uncertain_header") {
                            Text(
                                "证据不足，需要你自己看（默认不勾选）",
                                color = secondary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        items(visibleBatch.uncertain, key = { intentIdentity(it) }) { candidate ->
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
                                "本轮：推荐 ${visibleBatch.recommended.size} 张 · 低优先级 ${visibleBatch.lowerPriority.size} 张" +
                                    " · 待定 ${visibleBatch.uncertain.size} 张",
                                color = secondary,
                                fontSize = 11.sp
                            )
                            if (visibleBatch.protectedCount > 0 || visibleBatch.evergreenCount > 0) {
                                Text(
                                    "已排除 ${visibleBatch.protectedCount} 张保护项（票务/订单/凭证/课件/长期保留）" +
                                        if (visibleBatch.evergreenCount > 0) "，以及 ${visibleBatch.evergreenCount} 张长文资料" else "",
                                    color = secondary,
                                    fontSize = 11.sp
                                )
                            }
                            if (visibleBatch.shortOfLimit) {
                                Text(
                                    "只找到 ${visibleBatch.ordered.size} 张足够明确的候选，没有为了凑满批次硬塞。",
                                    color = secondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                batchUndo(Modifier)

                // ---------- 底部操作条（与暂存清理同一条回收站链路） ----------
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardColor)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    // 第一行：选中数量（左，作为状态）＋ 长期保留（右，次要操作）。
                    // 数量用主文本色而不是次要灰：它是这一屏最需要一眼看到的当前状态。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (selectedIdentityKeys.isEmpty()) "未选择照片"
                            else "已选 ${selectedIdentityKeys.size} 张",
                            color = if (selectedIdentityKeys.isEmpty()) secondary else textColor,
                            fontSize = 13.sp,
                            fontWeight = if (selectedIdentityKeys.isEmpty()) FontWeight.Normal
                            else FontWeight.Medium
                        )
                        OutlinedButton(
                            onClick = { HapticFeedback.lightTap(context); onRetainSelected() },
                            enabled = selectedIdentityKeys.isNotEmpty() && !isSaving,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("长期保留", color = secondary, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 主操作：以结果命名（「移入回收站」），不用「提交」这类抽象词。
                    // 未选择时按钮禁用并说明原因；保存中显示进度且不显示数量（避免看似可点）。
                    Button(
                        onClick = { HapticFeedback.mediumTap(context); onConfirm() },
                        enabled = selectedIdentityKeys.isNotEmpty() && !isSaving,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = warn,
                            disabledContainerColor = warn.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        when {
                            isSaving -> CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            selectedIdentityKeys.isEmpty() -> Text(
                                "选择照片后可移入回收站",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 15.sp
                            )
                            else -> Text(
                                "移入回收站（${selectedIdentityKeys.size} 张）",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Text(
                        "可在回收站恢复",
                        color = secondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
    }
}

/**
 * 凑一批页的三种「没有照片可看」状态。
 *
 * 抽成一个 sealed 类而不是三个 `when` 分支，是为了让**布局只有一份**：
 * 加载、空结果、失败共用同一个占位块（相同的居中方式、边距、文字层级），
 * 切换状态时不会看到文字上下跳。
 */
private sealed interface IntentBatchSettleState {
    /** 正在本地识别文字。 */
    data object Loading : IntentBatchSettleState

    /** 这批范围里没有足够明确的候选。 */
    data object Empty : IntentBatchSettleState

    /** 分析失败（引擎不可用等），带一句说明。 */
    data class Failure(val note: String) : IntentBatchSettleState
}

/**
 * 三种状态共用的占位块。
 *
 * 文字层级固定：主文案 14sp、说明 12sp、可选操作按钮。加载态用同样大小的
 * 圆形进度替换主文案位置，因此高度变化只在「有/无按钮」这一处。
 */
@Composable
private fun ColumnScope.IntentBatchSettlePlaceholder(
    state: IntentBatchSettleState,
    accent: Color,
    secondary: Color,
    onAnalyze: () -> Unit
) {
    val title: String
    val detail: String
    val showAnalyze: Boolean
    when (state) {
        IntentBatchSettleState.Loading -> {
            title = "正在本地识别文字…"
            detail = "只在本机识别，不上传照片"
            showAnalyze = false
        }
        IntentBatchSettleState.Empty -> {
            title = "这批范围里没有找到足够明确的候选。"
            detail = "换个来源，或过几天再试。"
            showAnalyze = true
        }
        is IntentBatchSettleState.Failure -> {
            title = state.note
            detail = "本地规则仍可用；可以重试，或换一批来源"
            showAnalyze = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state is IntentBatchSettleState.Loading) {
                CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp))
            }
            Text(title, color = secondary, fontSize = 14.sp)
            Text(detail, color = secondary, fontSize = 12.sp)
            if (showAnalyze) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onAnalyze) { Text("重新分析") }
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
// FlowRow 仍是 experimental（compose-foundation 1.7.x）。
// 用它是因为服务名在 1.5× 字体下必须能换行——Row 会把两个服务名挤在一起。
@OptIn(ExperimentalLayoutApi::class)
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
    onDeleteForService: ((IntentAssistService) -> Unit)? = null,
    /**
     * 当前批次**实际在使用**的服务（不是正在编辑哪一个）。
     *
     * 与 [service] / `editing` 分工：
     * - [service]：打开弹层时选中的编辑对象；
     * - `editing`：弹层内正在编辑哪套 Key；
     * - [activeService]：当前批真正会外发的服务。
     *
     * 三者必须分开，否则「看 Key」这个动作会顺带改外发对象——那是一次授权变更。
     */
    activeService: IntentAssistService = service,
    /**
     * 用户确认「使用此服务」。
     *
     * 传 null 时弹层退化为「只管理 Key」的旧语义（没有切换服务的入口）。
     * 实现方负责吊销本批授权并按关闭态重跑本地分析（见 `MainActivity`）。
     */
    onUseService: ((IntentAssistService) -> Unit)? = null
) {
    var input by remember { mutableStateOf("") }
    // 对话框内可切换编辑哪一家的 Key——不必先回页面切服务。
    var editing by remember(service) { mutableStateOf(service) }
    // 输入框聚焦态：键盘弹出后正文可滚，底栏固定；这里只用来选标题文案。
    val focusRequester = remember { FocusRequester() }
    val isDark = LocalIsDarkTheme.current
    val textColor = if (isDark) Color.White else Color.Black
    val secondary = Color(0xFF8E8E93)
    val accent = Color(0xFF007AFF)
    val danger = Color(0xFFFF3B30)
    val cardColor = if (isDark) Color(0xFF1C1C1E) else Color.White
    val stateForEditing = serviceStates[editing] ?: currentState

    /**
     * 弹层用自绘 `Dialog` 而不是 `AlertDialog`。
     *
     * `AlertDialog` 把 `confirmButton` / `dismissButton` 排成一行 `FlowRow`，
     * 字体 1.5× 时「删除 DeepSeek 的 Key」与「关闭」同一行放不下、换行后
     * 「关闭」被挤出弹层（本轮复现的直接原因）。它也不给正文加滚动，
     * 键盘弹出后可用高度再缩一截，正文和按钮一起被顶出去。
     *
     * 这里固定三段结构：
     *   标题（不滚） / 正文（可滚，`LazyColumn`） / 底栏（不滚，永远可见）
     * 底栏只有「保存」和「关闭」两个短动作；删除属于**当前服务的配置**，
     * 放进正文的服务区，长文案不再和关闭按钮争同一行。
     */
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // 自绘宽度，才能在窄屏/大字体下自己决定内边距
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    top = 24.dp,
                    bottom = if (keyboardVisible) 48.dp else 24.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardColor)
            ) {
                // ===== 标题：不滚动 =====
                Text(
                    text = "文字辅助 API Key",
                    color = textColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp)
                )
                Text(
                    "Key 仅保存在此设备，不进入备份、迁移、日志或导出。未配置或失效时继续用本地规则。",
                    color = secondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                // ===== 正文：可滚动 =====
                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    // ---- 服务选择 ----
                    if (serviceStates.isNotEmpty()) {
                        item(key = "service_label") {
                            // 两种含义必须分开：
                            //   点服务名 = 编辑它的 Key（不影响外发对象）
                            //   「使用此服务」= 真的把这一批切到它（会吊销本批授权）
                            Text("选择要配置的服务", color = secondary, fontSize = 11.sp)
                            Spacer(Modifier.height(6.dp))
                            // 用 FlowRow：1.5× 字体下两个服务名放不下就换行，
                            // 不会再互相挤压或被裁掉
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IntentAssistService.entries.forEach { candidate ->
                                    val isEditing = candidate == editing
                                    val hasKey = serviceStates[candidate]?.let { it != "未配置" } ?: false
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                if (isEditing) accent.copy(alpha = 0.12f)
                                                else Color.Transparent
                                            )
                                            .clickable {
                                                editing = candidate
                                                input = ""
                                            }
                                            .heightIn(min = 48.dp)
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            candidate.displayName,
                                            color = if (isEditing) accent else secondary,
                                            fontSize = 12.sp,
                                            fontWeight = if (isEditing) FontWeight.SemiBold
                                            else FontWeight.Normal
                                        )
                                        if (hasKey) {
                                            Text(" ·", color = accent, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                    }

                    // ---- 当前服务的配置区（含删除） ----
                    item(key = "service_config") {
                        Text(
                            "${editing.displayName} 当前：$stateForEditing",
                            color = textColor,
                            fontSize = 12.sp
                        )
                        // 「使用此服务」：明确的**切换外发对象**操作
                        if (onUseService != null && editing != activeService) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(accent.copy(alpha = 0.10f))
                                    .clickable {
                                        onUseService(editing)
                                        input = ""
                                        onDismiss()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "使用 ${editing.displayName}",
                                        color = accent,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        "切换会撤销本批授权，需要重新开启",
                                        color = secondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        } else if (onUseService != null && editing == activeService) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "本批正在使用 ${activeService.displayName}",
                                color = secondary,
                                fontSize = 11.sp
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text("粘贴新的 Key（${editing.keyHint}，留空表示不修改）") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = textColor,
                                unfocusedTextColor = textColor
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "两个服务的 Key 分开存放：在这里更换或删除只影响 ${editing.displayName}。",
                            color = secondary,
                            fontSize = 11.sp
                        )

                        // 「删除」放在**当前服务**这一块：它是一次针对该服务的破坏性动作，
                        // 与「保存/关闭」不是同一层。放底栏会和关闭争同一行（1.5× 时被挤走）。
                        if (stateForEditing != "未配置") {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "删除 ${editing.displayName} 的 Key",
                                color = danger,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        val deleteForService = onDeleteForService
                                        if (deleteForService != null) deleteForService(editing)
                                        else onDelete()
                                        input = ""
                                    }
                                    .padding(vertical = 6.dp, horizontal = 2.dp)
                            )
                        }
                    }
                }

                // ===== 底栏：不滚动，永远可见 =====
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("关闭", color = secondary, fontSize = 14.sp)
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        if (input.isNotBlank()) {
                            val saveForService = onSaveForService
                            if (saveForService != null) saveForService(editing, input) else onSave(input)
                        }
                        input = ""
                        onDismiss()
                    }) {
                        Text("保存", color = accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
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
