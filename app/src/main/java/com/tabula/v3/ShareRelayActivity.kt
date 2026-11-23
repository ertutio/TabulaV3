package com.tabula.v3

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabula.v3.data.relay.RelayItem
import com.tabula.v3.data.relay.RelayMark
import com.tabula.v3.data.relay.RelayResolution
import com.tabula.v3.data.relay.ShareRelayIntentReader
import com.tabula.v3.data.relay.ShareRelayEntry
import com.tabula.v3.data.relay.ShareRelayRules
import com.tabula.v3.data.relay.ShareRelayStore
import com.tabula.v3.data.repository.LocalImageRepository
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.ui.theme.LocalIsDarkTheme
import com.tabula.v3.ui.theme.TabulaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 系统分享中转的接收入口（路线图第 2 项）。
 *
 * 流程：用户截图/选图 → 系统分享面板选 Tabula → 这里收到 URI →
 * 判定能否唯一定位本地原图（[ShareRelayRules.resolve]）→ 落库标记 →
 * **立刻**把同一批 URI 转交给用户真正要发的应用（排除本包名，避免自循环）。
 *
 * 两条硬约束：
 * 1. **转发优先**：即使一张都定位不到，也要把用户的东西送出去。标记只是
 *    副产品，"待核对"不等于失败。
 * 2. **不吞授权**：转交时把 `FLAG_GRANT_READ_URI_PERMISSION` 与 ClipData
 *    一起传下去。临时 URI 的读授权来自上游对我们的一次性授予，必须在
 *    这个 Activity 还活着的时候交出去；本类不做任何复制/落盘。
 *
 * 本 Activity 不读写照片内容，只搬运 URI；因此不需要额外权限。
 */
class ShareRelayActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val incoming = intent
        setContent {
            TabulaTheme {
                RelayScreen(
                    incomingIntent = incoming,
                    onContinue = { finishAndRemoveTaskSafely() }
                )
            }
        }
    }

    private fun finishAndRemoveTaskSafely() {
        if (isFinishing) return
        finishAndRemoveTask()
    }
}

@Composable
private fun RelayScreen(
    incomingIntent: Intent?,
    onContinue: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isDarkTheme = LocalIsDarkTheme.current
    val backgroundColor = if (isDarkTheme) Color.Black else Color(0xFFF2F2F7)
    val cardColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color.White
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val secondaryTextColor = Color(0xFF8E8E93)
    val accentColor = Color(0xFF007AFF)

    var resolution by remember { mutableStateOf<RelayResolution?>(null) }
    var forwardStarted by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(incomingIntent) {
        val intent = incomingIntent
        if (intent == null) {
            failure = "没有收到分享内容"
            return@LaunchedEffect
        }

        val outcome = withContext(Dispatchers.IO) {
            // 本地身份集只用于"能不能唯一定位"，不读照片内容。
            val localKeys = runCatching {
                LocalImageRepository(context).loadAllData().allImages
                    .map { it.mediaIdentityKey() }
                    .toSet()
            }.getOrElse { emptySet() }

            runCatching {
                ShareRelayEntry.handle(
                    context = context,
                    store = ShareRelayStore.getInstance(context),
                    intent = intent,
                    localIdentityKeys = localKeys,
                    nowMillis = System.currentTimeMillis()
                )
            }.onFailure { Log.e("ShareRelay", "Could not persist relay marks", it) }.getOrNull()
        }

        if (outcome == null) {
            failure = "这次分享里没有可用的图片"
            return@LaunchedEffect
        }

        resolution = outcome.resolution

        // 立刻转交：不等待用户操作，也不因为定位失败而中止。
        forwardStarted = true
        forwardToTarget(context, outcome.resolution)
    }

    val res = resolution
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            failure != null -> {
                Text(
                    text = failure ?: "",
                    color = textColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "照片没有被标记，也没有被转发。",
                    color = secondaryTextColor,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onContinue,
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("返回", color = Color.White, fontSize = 16.sp)
                }
            }

            else -> {
                Text(
                    text = if (forwardStarted) "已转交" else "正在准备…",
                    color = textColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (res == null) {
                        "正在确认能否定位到本地原图…"
                    } else {
                        "在系统面板里选择要发送的应用。"
                    },
                    color = secondaryTextColor,
                    fontSize = 13.sp
                )

                if (res != null) {
                    Spacer(Modifier.height(20.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(cardColor, RoundedCornerShape(12.dp))
                            .padding(16.dp)
                    ) {
                        Column {
                            Text(
                                text = "${res.forwardUris.size} 张图已转交",
                                color = textColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            if (res.confirmedCount > 0) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "${res.confirmedCount} 张已标记为待清",
                                    color = secondaryTextColor,
                                    fontSize = 12.sp
                                )
                            }
                            if (res.reviewCount > 0) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "${res.reviewCount} 张无法唯一定位原图，标记待核对。" +
                                        "不影响转发。",
                                    color = Color(0xFFFF9500),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                TextButton(onClick = onContinue) {
                    Text("完成", color = accentColor, fontSize = 15.sp)
                }
            }
        }
    }
}

/**
 * 把收到的 URI 转交给下游应用。
 *
 * 关键点：
 * - **自循环防护（审计修复）**：旧实现排除的是 `MainActivity`，但真正注册
 *   `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 的是 `ShareRelayActivity`——于是
 *   二次面板里仍然能再次选中 Tabula，用户可以把图无限转发给自己。现在改为
 *   用 PackageManager **动态查出本包实际声明了这两个 action 的组件**并全部
 *   排除，不靠硬编码类名。
 * - 保留 `FLAG_GRANT_READ_URI_PERMISSION`，并把 URI 放进 ClipData，
 *   让下游拿到读授权。
 * - 单图走 ACTION_SEND，多图走 ACTION_SEND_MULTIPLE。
 */
private fun forwardToTarget(context: android.content.Context, resolution: RelayResolution) {
    if (!resolution.hasForwardTargets) return
    val uris = resolution.forwardUris
    val mime = resolution.mimeType
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uris.first())
            clipData = ClipData.newRawUri("image", uris.first())
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = ClipData.newRawUri("image", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val chooser = Intent.createChooser(intent, "发送到").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(
            Intent.EXTRA_EXCLUDE_COMPONENTS,
            relayComponentsToExclude(context)
        )
    }
    runCatching { context.startActivity(chooser) }
        .onFailure {
            Log.e("ShareRelay", "Could not hand the images to a target app", it)
            Toast.makeText(context, "没有可用的接收应用", Toast.LENGTH_LONG).show()
        }
}

/**
 * 查出本包**实际**注册了分享接收能力的组件，用于二次面板的排除列表。
 *
 * 为什么不硬编码类名：审计发现旧实现排除的是 `MainActivity`，而真正声明
 * `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 的是 `ShareRelayActivity`，导致
 * 二次面板里 Tabula 仍然可选，用户可以把图无限转发给自己。
 *
 * 这里同时查两个 action，任一命中的本包组件都排除——将来若新增别的接收
 * Activity，也不需要再改这里。
 *
 * @return 本包所有分享接收组件；查不到时至少退回排除 ShareRelayActivity 本身
 */
internal fun relayComponentsToExclude(context: android.content.Context): Array<android.content.ComponentName> {
    val packageName = context.packageName
    val found = linkedSetOf<android.content.ComponentName>()
    listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE).forEach { action ->
        val probe = Intent(action).apply { type = "image/*" }
        runCatching {
            context.packageManager.queryIntentActivities(probe, 0).forEach { info ->
                if (info.activityInfo.packageName == packageName) {
                    found += android.content.ComponentName(packageName, info.activityInfo.name)
                }
            }
        }
    }
    // 兜底：即使 PackageManager 查不到（部分 ROM 限制），也必须排除自己
    found += android.content.ComponentName(packageName, ShareRelayActivity::class.java.name)
    return found.toTypedArray()
}
