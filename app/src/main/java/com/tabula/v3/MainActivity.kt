package com.tabula.v3

import android.graphics.Color as AndroidColor
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.balance.RoundIntentRules
import com.tabula.v3.data.balance.StagedSourceDisplayName
import com.tabula.v3.data.balance.SourceBalanceRules
import com.tabula.v3.data.balance.RoundSessionRules
import com.tabula.v3.data.balance.ArchiveIdentityRules
import com.tabula.v3.data.balance.DeferredActionLedger
import com.tabula.v3.data.balance.PendingDelete
import com.tabula.v3.data.balance.SessionBatchIds
import com.tabula.v3.data.balance.Ticket
import com.tabula.v3.BuildConfig
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.CardStyleMode
import com.tabula.v3.data.preferences.RecommendMode
import com.tabula.v3.data.preferences.SourceImageDeletionStrategy
import com.tabula.v3.data.preferences.SwipeStyle
import com.tabula.v3.data.preferences.TagSelectionMode
import com.tabula.v3.data.preferences.ThemeMode
import com.tabula.v3.data.preferences.TopBarDisplayMode
import com.tabula.v3.data.repository.DeletionVerification
import com.tabula.v3.data.repository.FileOperationManager
import com.tabula.v3.data.repository.LocalImageRepository
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.data.repository.copyImagesResultMessage
import com.tabula.v3.data.repository.moveImagesResultMessage
import com.tabula.v3.data.repository.isSameRecycleRecord
import com.tabula.v3.data.repository.matchesSameMediaIdentity
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.data.repository.partitionByConfirmedIdentity
import com.tabula.v3.data.repository.projectSnapshot
import com.tabula.v3.data.repository.RecycleBinManager
import com.tabula.v3.data.repository.RecommendationEngine
import com.tabula.v3.data.repository.AlbumCleanupEngine
import com.tabula.v3.data.repository.AlbumCleanupInfo
import com.tabula.v3.permission.MediaPermissionPolicy
import com.tabula.v3.permission.DeletePermissionSession
import com.tabula.v3.data.repository.RecycleBinPersistenceException
import com.tabula.v3.data.repository.RecycleBinCommitException
import com.tabula.v3.data.repository.deleteRecycleBinSelection
import com.tabula.v3.data.repository.reconcileRecycleBinDeletion
import com.tabula.v3.ui.navigation.AppScreen
import com.tabula.v3.ui.navigation.PredictiveBackContainer
import com.tabula.v3.ui.screens.AboutScreen
import com.tabula.v3.ui.screens.DeckScreen
import com.tabula.v3.ui.screens.RecycleBinScreen
import com.tabula.v3.ui.screens.SettingsScreen
import com.tabula.v3.ui.screens.VibrationSoundScreen
import com.tabula.v3.ui.screens.LabScreen
import com.tabula.v3.ui.screens.DisplaySettingsScreen
import com.tabula.v3.ui.screens.ReminderSettingsScreen
import com.tabula.v3.ui.screens.StatisticsScreen
import com.tabula.v3.ui.screens.AlbumViewScreen
import com.tabula.v3.ui.screens.SystemAlbumViewScreen
import com.tabula.v3.ui.screens.OnboardingScreen
import com.tabula.v3.ui.screens.PersonalizationScreen
import com.tabula.v3.ui.screens.DisclaimerScreen
import com.tabula.v3.ui.screens.PrivacyPolicyScreen
import com.tabula.v3.ui.screens.TutorialScreen
import com.tabula.v3.ui.screens.OtherAlbumsScreen
import com.tabula.v3.ui.screens.RelayReviewScreen
import com.tabula.v3.data.relay.RelayMark
import com.tabula.v3.data.relay.RelayRecycleTransaction
import com.tabula.v3.data.relay.RelayReviewController
import com.tabula.v3.data.relay.RelayReviewRules
import com.tabula.v3.data.relay.ShareRelayStore
import com.tabula.v3.data.repository.AlbumManager
import com.tabula.v3.data.model.Album
import androidx.compose.runtime.collectAsState
import com.tabula.v3.ui.theme.LocalIsDarkTheme
import com.tabula.v3.ui.theme.TabulaColors
import com.tabula.v3.ui.screens.AlbumTagManagementScreen
import com.tabula.v3.ui.util.HapticFeedback
import com.tabula.v3.ui.theme.TabulaTheme
import com.tabula.v3.ui.components.UNDO_SNACKBAR_DURATION_MS
import com.tabula.v3.ui.components.UndoSnackbar
import com.tabula.v3.ui.components.LocalLiquidGlassEnabled
import com.tabula.v3.service.FluidCloudService
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import com.tabula.v3.ui.components.isBackdropLiquidGlassSupported
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.resume
import com.tabula.v3.data.repository.BatchDeleteResult
import com.tabula.v3.data.repository.deleteInBatches
import com.tabula.v3.data.repository.deleteMediaUrisBatch
import com.tabula.v3.data.repository.BatchDeleteSummary
import com.tabula.v3.data.repository.mediaDeletionMessage
import com.tabula.v3.data.repository.resolveImagesByIdentitySelection
import com.tabula.v3.data.duplicate.ContentDigestCalculator
import com.tabula.v3.data.duplicate.DuplicateReverify
import com.tabula.v3.data.duplicate.DuplicateRules
import com.tabula.v3.data.burst.BurstCommit
import com.tabula.v3.data.burst.BurstSelectionRules
import com.tabula.v3.data.repository.SimilarGroup
import com.tabula.v3.ui.screens.BurstSelectionScreen
import com.tabula.v3.data.cleanup.StagedCleanupRules
import com.tabula.v3.data.cleanup.StagedCleanupSelection
import com.tabula.v3.data.cleanup.StagedCleanupSource
import com.tabula.v3.data.cleanup.StagedSourceKey
import com.tabula.v3.data.cleanup.cleanupIdentityKey
import com.tabula.v3.ui.screens.StagedCleanupCandidateContent
import com.tabula.v3.ui.screens.StagedCleanupSourcePickerContent
import com.tabula.v3.ui.screens.IntentBatchContent
import com.tabula.v3.ui.screens.IntentAssistKeyDialog
import com.tabula.v3.data.intent.DeepSeekTextAssist
import com.tabula.v3.data.intent.IntentAssistKeyStore
import com.tabula.v3.data.intent.IntentAssistService
import com.tabula.v3.data.intent.IntentAssistServiceSwitch
import com.tabula.v3.data.intent.InvalidatableTextAssist
import com.tabula.v3.data.intent.OpenCodeGoTextAssist
import com.tabula.v3.data.intent.IntentBatch
import com.tabula.v3.data.intent.IntentBatchAnalyzer
import com.tabula.v3.data.intent.IntentTesseractFactory
import com.tabula.v3.data.intent.IntentVerdictCache
import com.tabula.v3.data.intent.IntentAssistAuthorization
import com.tabula.v3.data.intent.IntentCommitGuard
import com.tabula.v3.data.intent.allCandidates

/**
 * 首次进入暂存清理时预勾选的来源上限。
 *
 * 截图通常只有一两个目录，下载目录最多两三个；限制上限是为了避免某些设备
 * 上铺开的聊天软件保存目录被一次性全选，把用户没打算动的照片带进候选。
 */
private const val MAX_DEFAULT_STAGED_SOURCES = 3

/**
 * 「按整理目的凑一批」一次凑多少张。
 *
 * 与卡片的批次大小无关：这个数只用来决定「最多给多少张高置信候选」，
 * 高置信不够时不凑满（见 IntentBatch.shortOfLimit）。
 */
private const val MAX_INTENT_BATCH = 10

/** 候选的媒体身份键，与暂存清理同口径（卷|ID）。 */
private fun intentIdentityOf(candidate: com.tabula.v3.data.intent.IntentCandidate): String =
    cleanupIdentityKey(candidate.image)

/**
 * T02b 恢复核验决策：未知保留在回收站，缺失清除死记录且不入图库，仅 PRESENT 继续恢复。
 */
enum class RestoreDecision { PROCEED_RESTORE, KEEP_UNKNOWN, DROP_MISSING }

fun decideRestoreAction(uri: Uri, verification: DeletionVerification): RestoreDecision = when {
    uri in verification.unknown -> RestoreDecision.KEEP_UNKNOWN
    uri in verification.deleted -> RestoreDecision.DROP_MISSING
    else -> RestoreDecision.PROCEED_RESTORE
}

/**
 * MainActivity - Tabula 应用入口
 *
 * 核心功能：
 * 1. 沉浸式 Edge-to-Edge UI
 * 2. 权限管理（READ_MEDIA_IMAGES）
 * 3. 简单路由管理
 * 4. ColorOS 16 风格预测性返回
 */
/** T03 升级兼容：解析 URI 当前指向的卷。查询返回多行（跨卷同 ID 的聚合
 *  二义）或失败时返回 null → 分区判为未确认（保留记录并停止）。 */
private suspend fun resolveMediaVolumeName(context: Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.VOLUME_NAME), null, null, null
            )?.use { c ->
                when {
                    c.count != 1 -> null
                    c.moveToFirst() -> c.getString(0)?.takeIf { it.isNotBlank() }
                    else -> null
                }
            }
        }.getOrNull()
    }

/**
 * C4/T07：回前台权限刷新的加载-提交链（mediaAccessRevision LaunchedEffect 体
 * 的 verbatim 提取；审计轮为"旧扫描晚返回不覆盖新权限状态"的受控竞争测试
 * 提供真实接缝——测试阻塞注入的 load、按框架键控语义取消旧扫描、断言提交
 * 路径只归最新扫描且取消不落 error 态）。
 *
 * 取消纪律（既有语义，保持不变）：调用方取消在任意挂起点传播为
 * CancellationException 原样抛出——apply/onError 均不会为被取消的扫描执行；
 * onFinally 仅做 UI 收尾（isLoading），不承载提交。
 * load 默认为真实生产链（loadAllData + loadRecycleBin）；生产接线不注入。
 */
internal suspend fun loadAndApplyMediaSnapshot(
    context: Context,
    recycleBinManager: RecycleBinManager,
    albumManager: AlbumManager,
    fileOperationManager: FileOperationManager,
    deletePermissionInFlight: Boolean,
    onRecycleUnknown: () -> Unit,
    onError: (Exception) -> Unit,
    onFinally: () -> Unit,
    load: suspend () -> Pair<LocalImageRepository.LoadResult, List<ImageFile>> = {
        Pair(LocalImageRepository(context).loadAllData(), recycleBinManager.loadRecycleBin())
    },
    apply: (LocalImageRepository.LoadResult, List<ImageFile>) -> Unit
) {
    try {
        if (!deletePermissionInFlight) {
            val recovered = reconcileRecycleBinDeletion(
                recycleBinManager,
                resolveVolume = { resolveMediaVolumeName(context, it) },
                verify = fileOperationManager::verifyDeletionState
            )
            if (recovered.unknown.isNotEmpty()) {
                onRecycleUnknown()
            }
        }
        val loadedData = withContext(Dispatchers.IO) {
            val loadResult = load()
            // 浏览/推荐由"快照 − 软删除集合"派生，相册计数与封面同步重算。
            val projected = projectSnapshot(loadResult.first, loadResult.second)
            albumManager.initialize(preloadedBuckets = projected.systemBuckets)
            MediaLoadTestHook.parkBeforeCommit(projected.allImages.size)
            Pair(projected, loadResult.second)
        }
        apply(loadedData.first, loadedData.second)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("TabulaApp", "Could not load/reconcile recycle bin", e)
        onError(e)
    } finally {
        onFinally()
    }
}

class MainActivity : ComponentActivity() {

    private var hasPermission by mutableStateOf(false)

    // C4/T07：前台权限/快照刷新触发器——访问级别变化或带权限回前台时递增，
    // 作为 TabulaApp 加载 LaunchedEffect 的键（重启即取消在途扫描，晚到结果
    // 不覆盖新权限态）。
    private var mediaAccessRevision by mutableStateOf(0)
    private var lastAccessLevel: MediaPermissionPolicy.MediaAccessLevel? = null
    
    // 流体云：跟踪当前批次剩余数量
    private var currentBatchRemaining = 0
    private var isFluidCloudEnabled = false

    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResults ->
        val sdkInt = android.os.Build.VERSION.SDK_INT
        val level = MediaPermissionPolicy.mediaAccessLevel(sdkInt) { permission ->
            grantResults[permission] == true ||
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        applyAccessLevel(level, foregroundRefresh = false)

        if (!hasPermission) {
            val requestedPermissions = MediaPermissionPolicy.requiredPermissionsForSdk(sdkInt)
            val shouldShowRationale = requestedPermissions.any { permission ->
                shouldShowRequestPermissionRationale(permission)
            }
            if (!shouldShowRationale) {
                Log.w("MainActivity", "Permission permanently denied, user should enable it in settings")
            }
        }
    }

    private lateinit var deletePermissionLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var deletePermissionSession: DeletePermissionSession

    override fun onSaveInstanceState(outState: Bundle) {
        deletePermissionSession.save(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 使用完全透明的导航栏样式，实现沉浸式体验
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = AndroidColor.TRANSPARENT,
                darkScrim = AndroidColor.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = AndroidColor.TRANSPARENT,
                darkScrim = AndroidColor.TRANSPARENT
            )
        )
        super.onCreate(savedInstanceState)
        deletePermissionSession = DeletePermissionSession(savedInstanceState)

        checkPermission()

        // 初始化删除权限启动器
        deletePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            val success = result.resultCode == Activity.RESULT_OK
            deletePermissionSession.complete(success)
        }

        setContent {
            val context = LocalContext.current
            val preferences = remember { AppPreferences(context) }
            var currentTheme by remember { mutableStateOf(preferences.themeMode) }
            
            // 液态玻璃实验室功能（独立于主题）
            var liquidGlassLabEnabled by remember { mutableStateOf(preferences.liquidGlassLabEnabled) }

            // 根据设置决定深色模式
            val darkTheme = when (currentTheme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.LIQUID_GLASS -> false  // 兼容旧数据，降级为 SYSTEM 行为
            }
            
            // 是否启用液态玻璃效果（来自实验室设置，而非主题）
            val liquidGlassEnabled = liquidGlassLabEnabled && android.os.Build.VERSION.SDK_INT >= 35

            TabulaTheme(darkTheme = darkTheme, liquidGlassEnabled = liquidGlassEnabled) {
                if (hasPermission) {
                    TabulaApp(
                        preferences = preferences,
                        mediaAccessRevision = mediaAccessRevision,
                        onThemeChange = { theme -> currentTheme = theme },
                        onRequestDeletePermission = { intentSender, callback ->
                            deletePermissionSession.launch(callback) {
                                deletePermissionLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                            }
                        },
                        deletePermissionInFlight = deletePermissionSession.inFlight,
                        deletionRecoveryRevision = deletePermissionSession.recoveryRevision,
                        liquidGlassLabEnabled = liquidGlassLabEnabled,
                        onLiquidGlassLabEnabledChange = { enabled ->
                            liquidGlassLabEnabled = enabled
                            preferences.liquidGlassLabEnabled = enabled
                        }
                    )
                } else {
                    PermissionRequestScreen(
                        onRequestPermission = { requestPermission() }
                    )
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // 返回应用时重新核对媒体访问权限（用户可能在系统设置中撤销了权限、
        // 修改了允许访问的照片选择，或后台期间外部增删改了媒体）——级别变化
        // 或带权限回前台都会触发按现有快照契约的刷新。
        checkPermission(foregroundRefresh = true)
        // 返回应用时隐藏流体云
        FluidCloudService.hide(this)
    }
    
    override fun onStop() {
        super.onStop()
        // 离开应用时，如果有未完成的批次且流体云已启用，则显示流体云
        if (isFluidCloudEnabled && currentBatchRemaining > 0) {
            FluidCloudService.show(this, currentBatchRemaining)
        }
    }
    
    /**
     * 更新批次剩余数量（供 Composable 调用）
     */
    fun updateBatchRemaining(remaining: Int, fluidCloudEnabled: Boolean) {
        currentBatchRemaining = remaining
        isFluidCloudEnabled = fluidCloudEnabled
    }

    private fun checkPermission(foregroundRefresh: Boolean = false) {
        val sdkInt = android.os.Build.VERSION.SDK_INT
        val level = MediaPermissionPolicy.mediaAccessLevel(sdkInt) { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        applyAccessLevel(level, foregroundRefresh)
    }

    /**
     * C4/T07：应用新的访问级别并按需触发刷新。
     *
     * 状态转换约定：
     * - 级别变化（NONE/PARTIAL/FULL 互切，含系统设置中的撤销与部分选择集
     *   变更）：更新门控；新级别非 NONE 时递增 [mediaAccessRevision] 触发
     *   按现有快照契约（loadAllData + projectSnapshot）的整表刷新——失去访问
     *   权的旧条目随 MediaStore 重查自然消失，恢复/升级后重新加载。
     * - 级别不变且带权限回前台（[foregroundRefresh]）：同样递增——后台期间
     *   的外部删除/修改在返回时按快照契约刷新。
     * - NONE：不加载（权限门控屏接管；重新授权后经本约定刷新/全新加载）。
     * - 首次检查（previous == null，onCreate 路径）不触发：组合后的首次
     *   加载已覆盖。
     * - 在途扫描防护：revision 作为 LaunchedEffect 键，重启即取消旧协程，
     *   较早启动的扫描结果不会写入较新的权限状态。
     *
     * 失败约定：刷新走既有加载路径的失败处理（回收站读取失败对话框 + 重试），
     * 权限拒绝由门控屏呈现，本函数不新增失败通道。
     */
    private fun applyAccessLevel(
        level: MediaPermissionPolicy.MediaAccessLevel,
        foregroundRefresh: Boolean
    ) {
        val previous = lastAccessLevel
        lastAccessLevel = level
        hasPermission = level != MediaPermissionPolicy.MediaAccessLevel.NONE
        if (level == MediaPermissionPolicy.MediaAccessLevel.NONE) return
        if (previous == null) return
        if (level != previous || foregroundRefresh) {
            mediaAccessRevision++
        }
    }

    private fun requestPermission() {
        val permissions = MediaPermissionPolicy.requiredPermissionsForSdk(android.os.Build.VERSION.SDK_INT)
        permissionLauncher.launch(permissions)
    }
    
}

/**
 * Tabula 应用主体
 *
 * 包含路由管理和预测性返回动画
 */
private class BatchRecycleUndo(
    val records: List<ImageFile>,
    val source: AppScreen,
    val groupId: String? = null,
    val report: String = ""
) {
    fun canShowOn(screen: AppScreen): Boolean =
        screen == source || (source == AppScreen.BURST_SELECTION && screen == AppScreen.DECK)
}

@Composable
fun TabulaApp(
    preferences: AppPreferences,
    mediaAccessRevision: Int = 0,
    onThemeChange: (ThemeMode) -> Unit,
    onRequestDeletePermission: (android.content.IntentSender, (Boolean) -> Unit) -> Unit,
    deletePermissionInFlight: Boolean = false,
    deletionRecoveryRevision: Int = 0,
    liquidGlassLabEnabled: Boolean = false,
    onLiquidGlassLabEnabledChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ========== 使用前须知状态 ==========
    // 只在首次启动时显示，确认后不再弹出
    var showDisclaimer by rememberSaveable { mutableStateOf(!preferences.hasAcknowledgedDisclaimer) }

    // ========== 引导流程状态 ==========
    // 只在首次启动时显示引导流程
    // 使用 rememberSaveable 保存状态，避免配置变更时丢失
    var showOnboarding by rememberSaveable { mutableStateOf(!preferences.hasCompletedOnboarding) }
    
    // 监听 preferences.hasCompletedOnboarding 变化，保持状态同步
    LaunchedEffect(preferences.hasCompletedOnboarding) {
        if (preferences.hasCompletedOnboarding) {
            showOnboarding = false
        }
    }

    // ========== 设置 ==========
    var currentBatchSize by remember { mutableIntStateOf(preferences.batchSize) }
    var currentTopBarMode by remember { mutableStateOf(preferences.topBarDisplayMode) }
    var showDeleteConfirm by remember { mutableStateOf(preferences.showDeleteConfirm) }
    var showHdrBadges by remember { mutableStateOf(preferences.showHdrBadges) }
    var showMotionBadges by remember { mutableStateOf(preferences.showMotionBadges) }
    var cardStyleMode by remember { mutableStateOf(preferences.cardStyleMode) }
    var swipeStyle by remember { mutableStateOf(preferences.swipeStyle) }
    // 标签收纳设置
    var tagSelectionMode by remember { mutableStateOf(preferences.tagSelectionMode) }
    var tagSwitchSpeed by remember { mutableFloatStateOf(preferences.tagSwitchSpeed) }
    var tagsPerRow by remember { mutableIntStateOf(preferences.tagsPerRow) }
    var playMotionSound by remember { mutableStateOf(preferences.playMotionSound) }
    var motionSoundVolume by remember { mutableIntStateOf(preferences.motionSoundVolume) }
    var hapticEnabled by remember { mutableStateOf(preferences.hapticEnabled) }
    var hapticStrength by remember { mutableIntStateOf(preferences.hapticStrength) }
    var swipeHapticsEnabled by remember { mutableStateOf(preferences.swipeHapticsEnabled) }
    var fluidCloudEnabled by remember { mutableStateOf(preferences.fluidCloudEnabled) }
    
    // ========== 快捷操作按钮状态 ==========
    var quickActionEnabled by remember { mutableStateOf(preferences.quickActionButtonEnabled) }
    var quickActionButtonX by remember { mutableFloatStateOf(preferences.quickActionButtonX) }
    var quickActionButtonY by remember { mutableFloatStateOf(preferences.quickActionButtonY) }

    // ========== 路由状态 ==========
    // 启动顺序：引导页 -> 个性化设置 -> 使用前须知 -> 主界面
    var currentScreen by remember {
        mutableStateOf(
            when {
                showOnboarding -> AppScreen.ONBOARDING
                showDisclaimer -> AppScreen.DISCLAIMER
                else -> AppScreen.DECK
            }
        )
    }
    var selectedAlbumId by remember { mutableStateOf<String?>(null) }
    var selectedBucketName by remember { mutableStateOf<String?>(null) }
    var isAlbumMode by remember { mutableStateOf(false) }
    var showHiddenAlbums by remember { mutableStateOf(false) }
    var otherAlbumsForNavigation by remember { mutableStateOf<List<Album>>(emptyList()) }

    // ========== 副本提示状态（路线图第 5 项） ==========
    // 只对「回收站里待永久删除的照片」算全文摘要——这是最需要"还剩一份吗"
    // 这个信息的时刻。摘要放在后台算，算不出来就不提示（不猜）。
    var duplicateCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var duplicateDetails by remember { mutableStateOf<Map<String, List<ImageFile>>>(emptyMap()) }
    var duplicateScanRevision by remember { mutableIntStateOf(0) }

    // ========== 连拍选片状态（路线图第 4 项） ==========
    // 只在相似推荐批次"看起来是一个连拍组"时才进入；进入后本界面的决定
    // 不直接落库，确认才写回收站，取消/返回什么都不改。
    var pendingBatchUndos by remember { mutableStateOf<List<BatchRecycleUndo>>(emptyList()) }
    var batchUndoInFlight by remember { mutableStateOf(false) }
    var burstSelectionGroup by remember { mutableStateOf<SimilarGroup?>(null) }
    // 审计修复（#87 四轮）：**原组身份**，用于完成时登记冷却。
    //
    // SimilarGroup.id 是成员集合的内容寻址摘要，部分回收成功后从
    // burstSelectionGroup 里移除成功项会改变 id。若拿裁剪后的组去
    // markSimilarGroupProcessed，登记的就是一个从未存在过的组，
    // 原组永远不会进冷却。因此原组单独保存，提交完成时用它登记。
    var burstOriginalGroup by remember { mutableStateOf<SimilarGroup?>(null) }
    // 审计修复（#87）：推荐引擎这批实际用到的真实组。null = 这批不是相似组。
    var activeSimilarGroup by remember { mutableStateOf<SimilarGroup?>(null) }
    var burstKeptKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var burstCommitting by remember { mutableStateOf(false) }

    // ========== 分享待清记录（#85：用户可达的清理界面） ==========
    val shareRelayStore = remember { ShareRelayStore.getInstance(context) }
    var relayMarks by remember { mutableStateOf<List<RelayMark>>(emptyList()) }
    var relayShowReviewOnly by remember { mutableStateOf(false) }
    var relayRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(relayRevision) {
        relayMarks = withContext(Dispatchers.IO) { shareRelayStore.load() }
    }

    // ========== 暂存清理状态（路线图第 1 项） ==========
    // 只在进入暂存清理页时计算候选；离开不清空，返回时保留用户已勾选的选择。
    var stagedCleanupSources by remember { mutableStateOf<List<StagedCleanupSource>>(emptyList()) }
    var stagedCleanupSelection by remember { mutableStateOf<StagedCleanupSelection?>(null) }
    var stagedCleanupSelectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var stagedCleanupLoading by remember { mutableStateOf(false) }
    var stagedCleanupSaving by remember { mutableStateOf(false) }
    // 本次会话内"跳过"与"长期保留"的身份键（保留跨重启，跳过只在内存）
    var stagedCleanupRetainedKeys by remember {
        mutableStateOf(preferences.stagedCleanupRetainedIdentityKeys)
    }
    var stagedCleanupSkippedKeys by remember {
        mutableStateOf(preferences.stagedCleanupSkippedIdentityKeys)
    }
    // ========== 本轮整理意图（路线图第 7 项 / 审计 #90） ==========
    // 全部是会话级状态：进程重启即归零，符合"只在当前整理会话内有效"。
    var intentSuggestion by remember { mutableStateOf<String?>(null) }
    var intentDismissed by remember { mutableStateOf(false) }
    // #90：只统计成功处理的行为（保留/归档/删除），撤销与失败要回退
    var roundSession by remember { mutableStateOf(RoundSessionRules.Session()) }
    // #90：卡片页当前实际持有的批次（由 DeckScreen 回报），用于候选池与提示判定
    var currentDeckBatch by remember { mutableStateOf<List<ImageFile>>(emptyList()) }
    // #90 第四轮：会话与批次的稳定标识。延迟动作（删除撤销期满、归档待提交）
    // 在**发起时**绑定它，完成时再比对——旧批的动作不往新批记账。
    var sessionBatchIds by remember { mutableStateOf(SessionBatchIds()) }
    // 当前推荐模式（跟随设置切换；意图只在随机漫步的普通整理模式下生效）
    var currentRecommendMode by remember { mutableStateOf(preferences.recommendMode) }

    // ========== 按整理目的凑一批（路线图第 8 项 / #94） ==========
    // 范围完全复用上一段的暂存清理候选；这里只多一层「按值得清理的程度排序」。
    // 三个状态刻意分开：分析中、分析结果、以及「本批次是否启用了文字辅助」——
    // 最后一项默认 false，且只对当前这批生效，离开页面不复用。
    var intentBatch by remember { mutableStateOf<IntentBatch?>(null) }
    var intentBatchSelectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var intentBatchAnalyzing by remember { mutableStateOf(false) }
    var intentBatchSaving by remember { mutableStateOf(false) }
    var intentBatchEngineNote by remember { mutableStateOf<String?>(null) }
    var intentBatchStatus by remember { mutableStateOf("") }
    // 文字辅助的批次授权（纯状态机：区分「用户自己按开关」与「换批重建」）。
    // **只保留这一份状态**：界面与投递逻辑都读它。多存一份布尔量会在重建批次时
    // 出现「状态机已吊销、副本仍是 true」的窗口（第 3 轮页面测试抓到过）。
    val intentAssistAuth = remember { IntentAssistAuthorization() }
    // 仅用于触发重组：状态机内部是普通字段，Compose 不会自动观察到它的变化。
    var intentAssistRevision by remember { mutableIntStateOf(0) }
    var intentBatchAssistTried by remember { mutableStateOf(false) }
    var showIntentAssistKeyDialog by remember { mutableStateOf(false) }
    // 用户是否已在本批次看过「只发送脱敏文字」的说明（每次进新批次重置）

    // 批次代际号：每次开新批次 / 换批 / 关开关 / 离开页面都 +1。
    // 分析结果与该值不符时整体作废（IntentBatchAnalyzer.stale），因此
    // 「旧结果覆盖新批次」不可能发生——这一层不依赖协程取消是否及时。
    val intentBatchGeneration = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    // 在途分析任务：关开关/离开页面时取消它，尽快停掉原生 OCR 与网络 IO。
    var intentBatchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // 当前批次的文字辅助实例（默认 null = 关闭）。换批/关开关/离开页面时作废。
    // 类型用 [InvalidatableTextAssist]：两个服务（DeepSeek / OpenCode Go）都能装进来，
    // 上层只依赖「可作废」这一条契约，不必对每个具体类写一次 when。
    var intentBatchAssistant by remember { mutableStateOf<InvalidatableTextAssist?>(null) }
    // 选用的服务（跨批次保留：那是「我信任哪家」的决定；授权才逐批）。
    var intentAssistService by remember { mutableStateOf(preferences.intentAssistService) }
    // 每服务一份 Key 存储：两个文件互不影响，换服务不迁移、不删除另一个。
    val intentAssistKeyStores = remember(context) {
        IntentAssistService.entries.associateWith { it.keyStore(context) }
    }
    // Key 状态的快照 + 版本号。
    //
    // 为什么快照：`hasKey()` / `describeState()` 都是读文件，直接在组合里逐次调用就是
    // 「每帧几次主线程 IO」。为什么需要版本号：文件变化不在 Compose 的观察范围内，
    // 保存/删除后必须显式 +1，否则界面显示的是上一个服务的 Key 状态。
    var intentAssistKeyRevision by remember { mutableIntStateOf(0) }
    val intentAssistKeyStates = remember(intentAssistKeyRevision) {
        intentAssistKeyStores.mapValues { it.value.hasKey() }
    }
    val intentAssistKeyDescriptions = remember(intentAssistKeyRevision) {
        intentAssistKeyStores.mapValues { it.value.describeState() }
    }
    // 当前服务的 Key 存储（所有既有调用点继续用一个入口）。
    val intentAssistKeyStore = intentAssistKeyStores.getValue(intentAssistService)
    val intentVerdictCache = remember(context) {
        IntentVerdictCache(
            context.getSharedPreferences("tabula_intent_verdicts", android.content.Context.MODE_PRIVATE)
        )
    }
    val intentAnalyzer = remember(context) {
        IntentBatchAnalyzer(
            context = context,
            recognizer = IntentTesseractFactory.get(context),
            cache = intentVerdictCache,
            batchGeneration = { intentBatchGeneration.get() }
        )
    }

    // ========== 图片数据状态 ==========
    var allImages by remember { mutableStateOf<List<ImageFile>>(emptyList()) }
    // 用于主界面卡片整理的图片列表（排除已归类到 Tabula 图集的照片）
    // 这样已归类的照片不会再次出现在待整理列表中
    var imagesForSorting by remember { mutableStateOf<List<ImageFile>>(emptyList()) }
    var deletedImages by remember { mutableStateOf<List<ImageFile>>(emptyList()) }
    var isDeletingRecycleBin by remember { mutableStateOf(false) }
    var recycleBinLoadError by remember { mutableStateOf(false) }
    var recycleBinLoadRevision by remember { mutableIntStateOf(0) }
    var systemBuckets by remember { mutableStateOf<List<LocalImageRepository.SystemBucket>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // 管理器
    val fileOperationManager = remember { FileOperationManager(context) }
    val recycleBinManager = remember { RecycleBinManager.getInstance(context) }
    val albumManager = remember { AlbumManager.getInstance(context) }
    val recommendationEngine = remember { RecommendationEngine.getInstance(context) }
    val albumCleanupEngine = remember { AlbumCleanupEngine.getInstance(context) }

    /**
     * 重置本轮整理意图的全部状态（审计 #90）。
     *
     * 审计要求：进入或退出专清、切换推荐模式、离开卡片页时，**统计、提示、
     * 已接受来源与待消费意图**都要一起清掉。只清一半（例如只清提示）会让
     * 上一个模式的来源偏好漏进下一个模式。
     */
    fun resetRoundIntentState() {
        roundSession = RoundSessionRules.endSession()
        intentSuggestion = null
        intentDismissed = false
        recommendationEngine.clearRoundIntent()
        // 换会话：之前绑定的 ticket 全部作废，延迟动作不会记进新会话
        sessionBatchIds = sessionBatchIds.newSession()
    }

    /**
     * 真正换批（#90 第四轮审计）：手动「再来一组」与旧批全部失效后的自动重取
     * 走同一个边界——清掉上一批统计与旧提示，并换一个批次标识。
     *
     * 删单张卡片、撤销、同批快照刷新都**不走**这里（见 [BatchChange]）。
     */
    fun advanceBatchBoundary() {
        roundSession = RoundSessionRules.nextBatch(roundSession)
        intentSuggestion = null
        sessionBatchIds = sessionBatchIds.newBatch()
    }

    /**
     * 绑定**发起这一刻**的会话与批次标识（#90 第四轮审计）。
     *
     * 延迟动作（归档待提交、删除撤销期满）必须带着它一路走到提交点，
     * 而不是在提交点再读当前值——那时可能已经换批了。
     */
    fun capturedTicket(): Ticket = Ticket.None.capture(sessionBatchIds)

    val albums by albumManager.albums.collectAsState()
    val albumMappings by albumManager.mappings.collectAsState()
    val excludedAlbumCount by albumManager.excludedAlbumCount.collectAsState()
    val hiddenAlbumCount by albumManager.hiddenAlbumCount.collectAsState()
    
    // ========== 图集清理状态 ==========
    var albumCleanupInfos by remember { mutableStateOf<List<AlbumCleanupInfo>>(emptyList()) }
    
    // 图集清理模式状态（提升到 TabulaApp 级别，避免导航时丢失）
    var isAlbumCleanupMode by remember { mutableStateOf(false) }
    var selectedCleanupAlbum by remember { mutableStateOf<Album?>(null) }

    // #90：模式隔离的单一判据——只有随机漫步的**普通整理模式**才记录、
    // 展示、接受本轮意图。进入或退出专清、切到相似推荐时这套状态全部清掉。
    val tracksRoundIntent =
        currentRecommendMode == RecommendMode.RANDOM_WALK && !isAlbumCleanupMode

    
    // 刷新图集清理信息的回调
    fun refreshAlbumCleanupInfos() {
        albumCleanupInfos = albums.map { album ->
            albumCleanupEngine.getAlbumCleanupInfo(album)
        }
    }
    
    // 当 albums 变化时刷新清理信息
    LaunchedEffect(albums) {
        refreshAlbumCleanupInfos()
    }

    // ========== 同步状态 ==========
    var isSyncing by remember { mutableStateOf(false) }

    // ========== 推荐模式刷新触发器 ==========
    // 用于在设置中切换推荐模式后，返回主页时刷新批次
    var recommendModeRefreshKey by remember { mutableIntStateOf(0) }

    // #90：切换专清 / 相似推荐 / 换推荐模式 / 退出整理时，清理本轮的
    // 统计、提示、已接受来源与待消费意图。意图是会话级的：这些切换代表
    // "新一轮整理"，不能继承上一轮的来源偏好。
    //
    // 审计第三轮：这里必须清**全部**四项。之前只清统计和提示、漏了
    // `currentDeckBatch` 与待消费意图，切模式后再回到卡片页，旧批次仍被
    // 当作"当前批次"，候选池因此把该来源的可用照片算成批内照片。
    LaunchedEffect(recommendModeRefreshKey) {
        resetRoundIntentState()
        currentDeckBatch = emptyList()
        currentRecommendMode = preferences.recommendMode
    }
    // 进入/退出专清模式：同一个隔离动作（审计要求双向都要清）
    LaunchedEffect(isAlbumCleanupMode) {
        resetRoundIntentState()
        currentDeckBatch = emptyList()
    }
    LaunchedEffect(currentScreen) {
        if (currentScreen != AppScreen.DECK) {
            resetRoundIntentState()
            currentDeckBatch = emptyList()
        }
    }

    /**
     * 按**用户实际处理行为**评估是否提示本轮整理意图（审计 #90）。
     *
     * 触发条件（全部满足才提示）：
     * - 当前是随机漫步的普通整理模式（专清 / 相似推荐下不提示）
     * - 主来源至少 3 张成功处理记录，且 ≥60%
     * - 该来源在**下一批候选**里仍有批外照片——候选池与抽样同口径：
     *   扣屏蔽图集、扣冷却、扣当前批次（[RoundCandidateRules]）
     * - 本轮没有接受过同一来源、用户也没忽略过
     *
     * 候选池用 RecommendationEngine.eligibleNextBatchCandidates 求，不再
     * 自己拿 imagesForSorting 猜——"照片还在池子里"不等于"下一批抽得到"。
     */
    LaunchedEffect(
        roundSession,
        intentDismissed,
        imagesForSorting,
        currentDeckBatch,
        currentRecommendMode,
        isAlbumCleanupMode
    ) {
        // 模式隔离：只在随机漫步的普通整理模式判定与展示
        val inPlainRandomWalk =
            currentRecommendMode == RecommendMode.RANDOM_WALK && !isAlbumCleanupMode
        if (!inPlainRandomWalk || intentDismissed || roundSession.isEmpty()) {
            intentSuggestion = null
            return@LaunchedEffect
        }
        val eligible = recommendationEngine.eligibleNextBatchCandidates(
            allImages = imagesForSorting.filterNot { image ->
                deletedImages.any { it.mediaIdentityKey() == image.mediaIdentityKey() }
            },
            currentBatch = currentDeckBatch
        )
        intentSuggestion = RoundSessionRules.shouldSuggest(
            session = roundSession,
            sourceKeyOf = { SourceBalanceRules.sourceKeyOf(it) },
            eligibleImages = eligible,
            dismissed = intentDismissed
        )
    }
    
    // ========== 原图删除提醒状态 ==========
    var showSourceImageDeletionDialog by remember { mutableStateOf(false) }
    var pendingSourceImageUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var dialogSourceImageUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var unverifiedSourceImageCount by remember { mutableIntStateOf(0) }
    var sourceImageDeletionStrategy by remember { mutableStateOf(preferences.sourceImageDeletionStrategy) }
    
    // 监听图集模式切换，当切换到图集界面时检查是否需要提醒删除原图
    LaunchedEffect(isAlbumMode, sourceImageDeletionStrategy) {
        if (isAlbumMode) {
            val cleanableResult = albumManager.getCleanableUrisForAllAlbums()
            pendingSourceImageUris = cleanableResult.sourceUris
            unverifiedSourceImageCount = cleanableResult.unverifiedSourceCount
            if (cleanableResult.sourceUris.isNotEmpty() &&
                sourceImageDeletionStrategy == SourceImageDeletionStrategy.ASK_EVERY_TIME
            ) {
                dialogSourceImageUris = cleanableResult.sourceUris.toList()
                showSourceImageDeletionDialog = true
            } else if (cleanableResult.sourceUris.isEmpty()) {
                // 清理候选变化后不能留下过期列表或确认框。
                showSourceImageDeletionDialog = false
            }
        }
    }

    // ========== 各页面的滚动状态（在 TabulaApp 级别保存，导航返回时保持位置） ==========
    val settingsScrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }

    fun removeConfirmedFromState(uris: Set<android.net.Uri>) {
        deletedImages = deletedImages.filterNot { it.uri in uris }
        allImages = allImages.filterNot { it.uri in uris }
        imagesForSorting = imagesForSorting.filterNot { it.uri in uris }
    }

    // ========== 副本检测（路线图第 5 项 / 审计 #88） ==========

    /**
     * 删除前复核：把已经不再成立的副本提示从 `duplicateDetails` 里摘掉。
     *
     * 审计要求的三项检查全部在这里做，且**用真实 IO 而不是缓存的扫描结果**：
     * 1. 副本还在当前图库集合里（[allImages]）
     * 2. 副本此刻仍可读（打开流成功）
     * 3. 内容摘要与扫描时一致（重新算 SHA-256 比对）
     *
     * 为什么必须重算摘要：文件可能被外部编辑器改过（同名不同内容），
     * 也可能被换成另一张图。只看"文件存在"不足以证明字节相同。
     *
     * 复核不通过时**只修改提示**，不阻止删除 —— 用户有权删任何东西；
     * 我们的责任是不要在"还有一份"的假象下让他删。
     */
    /**
     * 审计意见（#88）：复核不通过时**停止本次删除**、更新提示，
     * 让用户看到变化后重新确认——而不是悄悄按旧提示继续删。
     *
     * @return true 表示全部复核通过可以继续；false 表示提示已更新、应中止本次删除
     */
    suspend fun reverifyDuplicatesBeforeDeletion(
        toDelete: List<ImageFile>,
        // 审计意见（#88 四轮）：**必须**传入点击删除那一刻冻结的证据快照。
        //
        // 缺陷：旧实现直接读实时 `duplicateDetails`。后台扫描（进站/恢复/删除
        // 都会触发）可以在"用户点删除 → 复核开始"之间把提示刷掉，于是
        // `details[key]` 变成 null，`verifyAll` 里 `copies ?: return@forEach`
        // 直接跳过这张照片 —— 复核判为通过，最后一份被删掉。
        // 也就是说：用户看到的"还剩副本"被后台悄悄抹掉，删除却照样执行。
        //
        // 快照同时固定两样东西：本次要删的目标、以及当时展示给用户的副本证据。
        // 后续摘要计算与复核都只用这份快照；界面仍可被后台扫描刷新，
        // 但本次删除核对的是**用户当时看到的那份证据**。
        evidence: Map<String, List<ImageFile>> = duplicateDetails
    ): Boolean {
        val libraryKeys = allImages.map { it.mediaIdentityKey() }.toSet()
        val calculator = ContentDigestCalculator(context.contentResolver)

        // #88 四轮：可控时序闸门（仅 DEBUG）。让测试在"已点删除、尚未读摘要"
        // 这个真实存在的窗口里插入后台扫描，验证复核确实核对的是快照。
        withContext(Dispatchers.IO) { DuplicateReverifyTestHook.gateBeforeDigest() }

        // 预先在 suspend 上下文里重算所有涉及照片的摘要（主图 + 提示里的副本），
        // 再把纯 map 交给同步 seam——避免在普通 lambda 里调 suspend 函数。
        val involved = buildSet {
            addAll(toDelete)
            toDelete.forEach { image ->
                evidence[image.mediaIdentityKey()]?.let { addAll(it) }
            }
        }
        val versions = mutableMapOf<String, DuplicateRules.ContentVersion?>()
        involved.forEach { image ->
            versions[image.mediaIdentityKey()] = when (val result = calculator.digest(image)) {
                is ContentDigestCalculator.Result.Success -> result.version
                is ContentDigestCalculator.Result.Failed -> null
            }
        }
        val digestOf: (ImageFile) -> DuplicateRules.ContentVersion? =
            { image -> versions[image.mediaIdentityKey()] }

        val verdict = DuplicateReverify.verifyAll(
            toDelete = toDelete,
            details = evidence,
            libraryKeys = libraryKeys,
            digestOf = digestOf
        )
        val updated = duplicateDetails.toMutableMap()
        verdict.invalidatedKeys.forEach { updated.remove(it) }
        duplicateDetails = updated
        duplicateCounts = updated.mapValues { it.value.size }
        // #88 三轮：把这次实际做出的复核决定记下来。
        // 用户可见反馈是 Toast，不在 AccessibilityNode 树里，设备用例无法
        // 用它断言"复核真的拦下了"；这里提供进程内观测点（Release 下不记录）。
        DuplicateReverifyTestHook.record(
            DuplicateReverifyTestHook.Verdict(
                targetKeys = toDelete.map { it.mediaIdentityKey() }.toSet(),
                passed = !verdict.shouldStop,
                invalidatedKeys = verdict.invalidatedKeys,
                remainingHintKeys = updated.size
            )
        )
        return !verdict.shouldStop
    }

    //
    // 时机：回收站里的照片变化时（进站/恢复/删除）重算一次。扫描**只对
    // 回收站内的照片**做全文摘要——它们是最可能被永久删除的一批，也是
    // "还剩哪个版本"最有价值的地方；对全库做摘要代价太大且没有必要。
    //
    // 失败即不提示：读不到的照片不进入结果，不猜、不弹错。
    LaunchedEffect(deletedImages, allImages, duplicateScanRevision) {
        if (deletedImages.isEmpty()) {
            duplicateCounts = emptyMap()
            duplicateDetails = emptyMap()
            return@LaunchedEffect
        }
        val calculator = ContentDigestCalculator(context.contentResolver)

        // 审计意见（#88）：要回答的是"图库里还剩哪一份"，因此候选必须包含
        // **当前可访问且在库里的照片**，而不是只比回收站内部。
        //
        // 两步走，先缩小范围再算摘要（全库逐张算 SHA-256 代价不可接受）：
        // 1. 按文件大小分组：只有大小相同的才可能字节相同
        // 2. 只对这些大小分组里的照片算全文摘要，且回收站里的这些照片本身
        //    也要参与（它们是被删除的一方，不参与就无法判断"还剩一份"）
        val recycleSizes = deletedImages.map { it.size }.filter { it > 0L }.toSet()
        val libraryCandidates = allImages.filter { image ->
            image.size > 0L && image.size in recycleSizes &&
                image.mediaIdentityKey() !in deletedImages.map { it.mediaIdentityKey() }.toSet()
        }
        if (libraryCandidates.isEmpty()) {
            // 图库里没有任何同尺寸候选 —— 不确定，但不声称"还留有副本"
            duplicateCounts = emptyMap()
            duplicateDetails = emptyMap()
            return@LaunchedEffect
        }

        val toDigest = deletedImages + libraryCandidates
        val digests = withContext(Dispatchers.IO) { calculator.digestAll(toDigest) }
        if (digests.isEmpty()) {
            duplicateCounts = emptyMap()
            duplicateDetails = emptyMap()
            return@LaunchedEffect
        }

        // 只为回收站里的照片找**库内**副本：回收站成员互相比不算证据。
        val recycleKeys = deletedImages.map { it.mediaIdentityKey() }.toSet()
        val findings = withContext(Dispatchers.Default) {
            DuplicateRules.findExactDuplicates(
                candidates = digests,
                recycledIdentityKeys = recycleKeys
            )
        }

        duplicateCounts = findings.associate {
            it.image.mediaIdentityKey() to it.duplicates.size
        }
        duplicateDetails = findings.associate {
            it.image.mediaIdentityKey() to it.duplicates
        }
    }


    // ========== 暂存清理动作（路线图第 1 项） ==========
    //
    // 候选来源 = 截图/下载目录 (StagedCleanupRules.availableSources)。
    // 候选照片 = 在选定来源内、扣除「长期保留」「本次跳过」「已在回收站」三组
    // 身份键后，按存入时间 (DATE_ADDED) 分旧/新/未知三段。纯规则在
    // StagedCleanupRules 里，JVM 单测覆盖；这里只负责把状态接起来。

    fun recomputeStagedCleanupSelection() {
        stagedCleanupLoading = true
        scope.launch {
            try {
                val sourceKeys = preferences.stagedCleanupSourceKeys
                val selectedSources = sourceKeys.mapNotNull { raw ->
                    val separator = raw.indexOf('|')
                    if (separator < 0) return@mapNotNull null
                    StagedSourceKey(
                        volumeName = raw.substring(0, separator),
                        relativePath = raw.substring(separator + 1)
                    )
                }
                stagedCleanupSelection = StagedCleanupRules.select(
                    allImages = allImages,
                    sources = selectedSources,
                    retainedIdentityKeys = stagedCleanupRetainedKeys,
                    skippedIdentityKeys = stagedCleanupSkippedKeys,
                    recycledIdentityKeys = deletedImages.map { cleanupIdentityKey(it) }.toSet(),
                    nowMillis = System.currentTimeMillis()
                )
                // 勾选集合与候选求交：来源换过、照片被恢复或删除后不留下幽灵勾选。
                val validKeys = stagedCleanupSelection?.allCandidateImages.orEmpty()
                    .map { cleanupIdentityKey(it) }
                    .toSet()
                stagedCleanupSelectedKeys = stagedCleanupSelectedKeys.intersect(validKeys)
            } finally {
                stagedCleanupLoading = false
            }
        }
    }

    fun openStagedCleanup() {
        // 审计修复（#84）：列出全部可访问来源，不再只列截图/下载。
        stagedCleanupSources = StagedCleanupRules.availableSources(allImages)
        stagedCleanupSelectedKeys = emptySet()
        currentScreen = AppScreen.STAGED_CLEANUP
        if (preferences.hasStagedCleanupSourceChoice) {
            recomputeStagedCleanupSelection()
        } else {
            // 首次进入：只预勾推荐来源（截图/下载），其它来源由用户自己决定。
            stagedCleanupSelectedKeys = StagedCleanupRules.defaultSelectedSourceKeys(
                stagedCleanupSources,
                limit = MAX_DEFAULT_STAGED_SOURCES
            )
            stagedCleanupSelection = null
        }
    }

    /**
     * 作废当前批次的一切在途结果。
     *
     * 调用点：关开关、换来源、离开页面、删除 Key。三件事同时做，缺一不可：
     * 1. 代际 +1 → 已经在算的分析即使跑完，`Analysis.stale` 为 true，不会被应用；
     * 2. 取消在途 Job → 尽快停掉原生 OCR 与网络 IO（省电、省流量）；
     * 3. 让共用的文字辅助失效 → 它的 classify 之后一律返回空/过期建议。
     */
    fun invalidateIntentBatch() {
        intentBatchGeneration.incrementAndGet()
        intentBatchJob?.cancel()
        intentBatchJob = null
        intentBatchAssistant?.invalidate()
        // 离开页面 = 这次授权结束
        intentAssistAuth.onRebuild()
        intentAssistRevision++
        intentBatchAnalyzing = false
    }

    /**
     * **唯一的**离开批次入口：撤销授权、取消在途分析、作废迟到结果。
     *
     * 页面返回按钮与系统返回手势都必须走这里，否则会出现「点按钮走了、
     * 划手势却还在发请求」的分叉。因此在 `PredictiveBackContainer` 的
     * 集中返回处理里也调用它（见渲染容器那一节）。
     */
    fun leaveIntentBatch() {
        invalidateIntentBatch()
        // 离开后不再保留任何一批的界面状态，避免下次进入看到失效的旧列表
        intentBatch = null
        intentBatchSelectedKeys = emptySet()
        intentBatchStatus = ""
        intentBatchEngineNote = null
        intentBatchAssistTried = false
    }

    /**
     * 系统返回 / 返回手势在凑一批页上要做的事。
     *
     * 与页面返回按钮调用**同一个** [leaveIntentBatch]；抽成函数是为了让
     * 设备测试能直接命中这条路径（`PredictiveBackHandler` 只消费手势事件流，
     * 程序化按键与 dispatcher 派发都进不去，见第 4 轮实测记录）。
     */
    fun handleIntentBatchSystemBack(): Boolean {
        if (currentScreen != AppScreen.INTENT_BATCH) return false
        leaveIntentBatch()
        currentScreen = AppScreen.STAGED_CLEANUP
        return true
    }

    /**
     * 跑一轮分析。**两个入口的语义必须分开**，这是「授权只对当前批有效」的关键：
     *
     * - [reanalyzeCurrentBatch]：用户在当前批**主动开启或关闭**文字辅助后的重算。
     *   授权（[intentAssistAuth]）在这一层保持不变——是他刚刚亲手设的。
     * - [rebuildIntentBatch]：批次内容变了（提交移入回收站、长期保留、来源范围变化、
     *   手动重新分析）。这**不是同一次授权**：旧授权作废、在途请求作废，
     *   按本地模式重建，新批默认关闭。
     *
     * 两个入口最终都走 [runIntentBatchAnalysis]；差别只在「进之前要不要吊销授权」。
     */
    /**
     * 真正的分析实现。
     *
     * @param revokeAssist true = 这是**重建批次**：先吊销文字辅助授权、作废在途请求，
     *                     再按本地模式分析；false = 用户在当前批手动改开关后的重算。
     */
    fun runIntentBatchAnalysis(revokeAssist: Boolean) {
        // 任何一轮开始都先作废上一轮的在途任务与辅助实例（旧结果不得覆盖新结果）
        intentBatchJob?.cancel()
        intentBatchGeneration.incrementAndGet()
        intentBatchAssistant?.invalidate()
        intentBatchAssistant = null
        if (revokeAssist) {
            // 重建批次：这次授权只属于旧批次，连同开关与「已确认说明」一起清掉，
            // 新批必须由用户重新主动开启（默认关闭）。
            intentAssistAuth.onRebuild()
            intentAssistRevision++
        }

        intentBatchAnalyzing = true
        intentBatchEngineNote = null
        val generationAtStart = intentBatchGeneration.get()

        // 文字辅助：默认关闭；只有用户在本批次主动打开且 Key 存在时才创建，
        // 并且创建即绑定当前代际，换批/关开关时作废。
        // 读状态机（`effective`），而不是任何界面副本
        val allowAssist = intentAssistAuth.effective && intentAssistKeyStore.hasKey()
        val assistant: InvalidatableTextAssist? = if (allowAssist) {
            // 服务在**创建实例这一刻**定下来，并绑定当前代际：
            // 换服务走 onSelectAssistService（吊销授权 → 下次重建自然换实现）。
            when (intentAssistService) {
                IntentAssistService.DEEPSEEK -> DeepSeekTextAssist(
                    keyStore = intentAssistKeyStore,
                    userEnabledForBatch = true,
                    sharedGeneration = intentBatchGeneration
                )
                IntentAssistService.OPENCODE_GO -> OpenCodeGoTextAssist(
                    keyStore = intentAssistKeyStore,
                    userEnabledForBatch = true,
                    sharedGeneration = intentBatchGeneration
                )
            }
        } else {
            null
        }
        intentBatchAssistant = assistant

        intentBatchJob = scope.launch {
            try {
                // 候选范围 = 暂存清理当前看到的全部照片（含未到期的，排序可以解释它们为什么排后面）
                val candidates = stagedCleanupSelection?.allCandidateImages.orEmpty()
                if (candidates.isEmpty()) {
                    if (intentBatchGeneration.get() == generationAtStart) {
                        intentBatch = IntentBatch(emptyList(), emptyList(), emptyList(), emptyList(), 0)
                        intentBatchStatus = "这些来源里没有候选"
                    }
                    return@launch
                }
                val analyzer = IntentBatchAnalyzer(
                    context = context,
                    recognizer = IntentTesseractFactory.get(context),
                    cache = intentVerdictCache,
                    textAssist = assistant,
                    batchGeneration = { intentBatchGeneration.get() },
                    // 服务进缓存键：换服务后「证据不足」的那些图必须重新判，
                    // 不能复用另一家算出的结论（assistCachePrefix 见该参数注释）。
                    assistCachePrefix = when (intentAssistService) {
                        IntentAssistService.DEEPSEEK -> "assist"
                        IntentAssistService.OPENCODE_GO -> "assist-opencode-go"
                    }
                )
                val analysis = analyzer.analyze(
                    images = candidates,
                    batchLimit = MAX_INTENT_BATCH,
                    nowMillis = System.currentTimeMillis(),
                    retainedIdentityKeys = preferences.stagedCleanupRetainedIdentityKeys,
                    allowTextAssist = allowAssist
                )
                // 代际不符 = 用户已经换了批/关了开关/离开了页面：**丢弃**，不写界面
                if (analysis.stale || intentBatchGeneration.get() != generationAtStart) {
                    return@launch
                }
                intentBatch = analysis.batch
                intentBatchEngineNote = analysis.engineNote
                intentBatchSelectedKeys = intentBatchSelectedKeys.intersect(
                    analysis.batch.allCandidates()
                        .filter { it.verdict.recommendation.isCleanable }
                        .map { intentIdentityOf(it) }
                        .toSet()
                )
                intentBatchStatus = buildString {
                    append("本地识别 ${analysis.ocrAttemptCount} 张")
                    if (analysis.usedCacheCount > 0) append("，复用缓存 ${analysis.usedCacheCount} 张")
                    if (analysis.assistRequestCount > 0) append("，文字辅助 ${analysis.assistRequestCount} 次")
                    append("，用时 ${analysis.elapsedMillis} ms")
                }
                intentBatchAssistTried = allowAssist
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("TabulaApp", "Intent batch analysis failed", e)
                if (intentBatchGeneration.get() == generationAtStart) {
                    intentBatchEngineNote = "本地识别没能完成，先不排序"
                }
            } finally {
                if (intentBatchGeneration.get() == generationAtStart) {
                    intentBatchAnalyzing = false
                }
            }
        }
    }

    /**
     * 用户在当前批**主动改开关**后的重算：授权保持不变（是他刚亲手设的）。
     *
     * 与 [rebuildIntentBatch] 的区别是这次调用的全部意义：混用会让「新批次」
     * 悄悄继承上一批的授权，或让「用户刚打开」被系统立刻关掉。
     */
    fun reanalyzeCurrentBatch() = runIntentBatchAnalysis(revokeAssist = false)

    /** 批次内容变了（提交 / 长期保留 / 范围变化 / 手动重来）：吊销授权后重建。 */
    fun rebuildIntentBatch() = runIntentBatchAnalysis(revokeAssist = true)

    /**
     * 用户切换文字辅助的**服务**（DeepSeek ↔ OpenCode Go）。
     *
     * 服务变了就是换了这次外发的对象：用户之前同意的是「发给那一家」，不是「发给任何一家」。
     * 因此与「换批」走同一条推理，只是触发条件换成服务变更：
     * 1. 先作废在途请求（[invalidateIntentBatch] 取消 Job、代际 +1、旧辅助实例 invalidate）；
     * 2. 用现有 [IntentAssistAuthorization.onRebuild] 吊销本批授权并推进 epoch；
     * 3. 按**关闭态**重跑本地分析——新服务要用户在本批重新打开一次。
     *
     * 选的是当前服务时什么都不做：[IntentAssistServiceSwitch.revokeForServiceChange]
     * 返回 false，**不会**推进 epoch（否则用户自己的在途请求会被误判过期）。
     *
     * 跨批次保留：服务本身写进偏好（[AppPreferences.intentAssistService]）；
     * 只有授权不保留。这两个决定刻意分开。
     */
    fun selectIntentAssistService(service: IntentAssistService) {
        val changed = IntentAssistServiceSwitch.revokeForServiceChange(
            current = intentAssistService,
            next = service,
            // 吊销**只做这一次**：走既有状态机 onRebuild（granted=false、epoch+1、
            // noticeAcknowledged 清掉）。代际号由下面的重算推进，两者分工不重叠，
            // 因此 epoch 每次切换恰好 +1（有测试钉住）。
            onRebuild = {
                intentAssistAuth.onRebuild()
                intentAssistRevision++
            }
        )
        intentAssistService = service
        preferences.intentAssistService = service
        if (!changed) return
        Toast.makeText(
            context,
            "已切换到 ${service.displayName}，本批授权已撤销；需要重新开启",
            Toast.LENGTH_SHORT
        ).show()
        // 用「不吊销」的重算入口：吊销已由上面完成，这里只负责作废在途请求
        // （取消 Job、代际 +1、旧实例 invalidate）并按**关闭态**重跑本地分析。
        reanalyzeCurrentBatch()
    }

    /**
     * 打开「按整理目的凑一批」（路线图第 8 项 / #94）。
     *
     * 入口在暂存清理页，因此候选范围直接复用上面的 [stagedCleanupSelection]：
     * 已勾选来源、已扣除长期保留/本次跳过/已在回收站。**不做全库扫描**。
     * 用户没有先选过来源时，退回来源选择页，避免拿空集合去分析。
     */
    fun openIntentBatch() {
        if (!preferences.hasStagedCleanupSourceChoice) {
            Toast.makeText(context, "先选好要清理的来源，再来凑这一批", Toast.LENGTH_SHORT).show()
            return
        }
        // 进入新批次 = 文字辅助恢复关闭（审计第 3 条：默认关闭、逐批开启、不跨批次记住）
        invalidateIntentBatch()
        intentBatch = null
        intentBatchSelectedKeys = emptySet()
        intentBatchEngineNote = null
        intentBatchStatus = ""
        intentBatchAssistTried = false
        currentScreen = AppScreen.INTENT_BATCH
        // 进新批次 = 重建：旧授权作废、按本地模式分析
        rebuildIntentBatch()
    }

    // A restored system request must finish before we classify its pending journal.
    // mediaAccessRevision（C4/T07）：回前台权限级别变化或带权限回前台时递增——
    // 作为键重启本 effect 取消在途扫描（晚到结果不覆盖新权限态），并按现有
    // 快照契约（loadAllData + projectSnapshot）整表刷新：失去访问权的旧条目
    // 随 MediaStore 重查消失，外部删除/修改与部分选择集变更在返回时更新。
    // 加载-提交链在 loadAndApplyMediaSnapshot（verbatim 提取；审计轮为受控
    // 竞争测试提供真实接缝），取消语义不变：键变化 → effect 取消 → 扫描体
    // 经 CancellationException 原样退出，apply/error 不为被取消扫描执行。
    LaunchedEffect(
        mediaAccessRevision,
        deletionRecoveryRevision,
        deletePermissionInFlight,
        recycleBinLoadRevision,
        isDeletingRecycleBin
    ) {
        if (isDeletingRecycleBin) return@LaunchedEffect
        loadAndApplyMediaSnapshot(
            context = context,
            recycleBinManager = recycleBinManager,
            albumManager = albumManager,
            fileOperationManager = fileOperationManager,
            deletePermissionInFlight = deletePermissionInFlight,
            onRecycleUnknown = {
                Toast.makeText(context, "上次删除尚有无法核对的照片，请检查照片访问权限或存储卷后重试", Toast.LENGTH_LONG).show()
            },
            onError = {
                recycleBinLoadError = true
                Toast.makeText(context, "回收站记录未能读取或核对，原记录已保留，请重试", Toast.LENGTH_LONG).show()
            },
            onFinally = { isLoading = false },
            apply = { projected, deleted ->
                allImages = projected.allImages
                imagesForSorting = projected.imagesExcludingTabula
                systemBuckets = projected.systemBuckets
                deletedImages = deleted
                recycleBinLoadError = false
            }
        )
    }

    if (recycleBinLoadError) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { recycleBinLoadError = false },
            title = { Text("回收站记录未能读取") },
            text = { Text("无法读取或保存回收站记录。原有记录未清空，请重试。") },
            confirmButton = {
                Button(onClick = { recycleBinLoadError = false; recycleBinLoadRevision++ }) { Text("重试") }
            }
        )
    }

    fun deleteRecycleBinImages(images: List<ImageFile>) {
        if (isDeletingRecycleBin || deletePermissionInFlight || images.isEmpty()) return
        val snapshot = images.toList()
        // 审计意见（#88 四轮）：在**用户点删除的这一刻**冻结副本证据快照。
        //
        // 之后后台扫描（进站/恢复/删除都会触发）可以继续刷新界面上的
        // duplicateDetails，但本次删除只核对这份快照——用户是看着这份
        // 证据点的删除。若快照里的副本已失效，就中止并让他重新确认。
        val evidenceSnapshot = duplicateDetails.toMap()
        isDeletingRecycleBin = true
        scope.launch {
            try {
                // 审计意见（#88）：确认前**实际调用复核**，而不是相信扫描时的旧结果。
                // 复核三件事：副本仍在图库里、仍可读、内容摘要与扫描时一致。
                // 任一不成立：更新提示（去掉已失效的"还有副本"）并**停止本次删除**，
                // 让用户看到变化后重新点删除——避免在"还有一份"的假象下删掉最后一份。
                val verified = reverifyDuplicatesBeforeDeletion(snapshot, evidenceSnapshot)
                if (!verified) {
                    Toast.makeText(
                        context,
                        "副本状态已变化，「还剩副本」的提示已更新，请确认后重新删除",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                var recoveredCount = 0
                val summary = deleteRecycleBinSelection(
                    snapshot.map { it.uri }, recycleBinManager,
                    request = fileOperationManager::deleteImages,
                    resolveVolume = { resolveMediaVolumeName(context, it) },
                    permission = { result ->
                        suspendCancellableCoroutine { continuation ->
                            onRequestDeletePermission(result.intentSender) { allowed ->
                                if (continuation.isActive) continuation.resume(allowed)
                            }
                        }
                    },
                    verify = fileOperationManager::verifyDeletionState,
                    onRecovered = { recovered ->
                        recoveredCount = recovered.deleted.size
                        removeConfirmedFromState(recovered.deleted)
                        if (recovered.unknown.isNotEmpty()) {
                            Toast.makeText(context, "上次删除尚有无法核对的照片，已保留；其余照片将继续处理", Toast.LENGTH_LONG).show()
                        }
                    },
                    onDeleted = { removeConfirmedFromState(it) }
                )
                val processed = summary.deleted.size + recoveredCount
                val message = when {
                    summary.cancelled -> "已取消，已处理 ${processed} 张，剩余 ${summary.remaining.size} 张"
                    summary.error != null -> "已处理 ${processed} 张，剩余 ${summary.remaining.size} 张；${summary.error}"
                    summary.remaining.isEmpty() -> "已处理 ${processed} 张照片"
                    else -> "已处理 ${processed} 张，${summary.remaining.size} 张未能删除，请重试"
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                throw e // Recovery queries the journal instead of re-deleting.
            } catch (e: RecycleBinCommitException) {
                allImages = allImages.filterNot { it.uri in e.physicallyDeleted }
                imagesForSorting = imagesForSorting.filterNot { it.uri in e.physicallyDeleted }
                Log.e("TabulaApp", "Deletion records require reconciliation", e)
                Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("TabulaApp", "Recycle bin deletion failed", e)
                Toast.makeText(context, "未能完成删除或保存记录，已停止；保留的记录将于重试时核对", Toast.LENGTH_LONG).show()
            } finally {
                isDeletingRecycleBin = false
            }
        }
    }

    /**
     * T02c：恢复（单条/批量共用）——单次对账、单次核验、逐项决策、共享记录事务。
     * UNKNOWN 保留在回收站，MISSING 清除死记录且不入图库，仅 PRESENT 恢复进图库。
     */
    fun restoreImagesInOneTransaction(images: List<ImageFile>, undo: BatchRecycleUndo? = null) {
        if (images.isEmpty() || isDeletingRecycleBin || deletePermissionInFlight) return
        if (undo != null) batchUndoInFlight = true
        scope.launch {
            try {
                val recovered = reconcileRecycleBinDeletion(
                    recycleBinManager,
                    resolveVolume = { resolveMediaVolumeName(context, it) },
                    verify = fileOperationManager::verifyDeletionState
                )
                removeConfirmedFromState(recovered.deleted)
                val candidates = images.filter { it.uri !in recovered.unknown && it.uri !in recovered.deleted }
                // 升级兼容：聚合 URI 无法唯一确认身份的候选（跨卷同 ID 混存）
                // 归入保留（UNKNOWN 语义），不参与缺失裁决。
                val (confirmedUris, unconfirmedUris) = partitionByConfirmedIdentity(
                    candidates.map { it.uri }, candidates
                ) { resolveMediaVolumeName(context, it) }
                val confirmedSet = confirmedUris.toSet()
                // 未确认身份的候选不参与核验与裁决：保留在回收站（UNKNOWN 语义）。
                val verifiable = candidates.filter { it.uri in confirmedSet }
                val verification = withContext(Dispatchers.IO) {
                    fileOperationManager.verifyDeletionState(verifiable.map { it.uri })
                }
                // The deletion probe also sees entries in Android's system trash. For
                // undo, require the exact photo to be visible in the normal media query.
                val visibleByIdentity = if (undo != null && verifiable.any {
                        decideRestoreAction(it.uri, verification) == RestoreDecision.PROCEED_RESTORE
                    }) {
                    LocalImageRepository(context).loadAllData().allImages.associateBy { it.mediaIdentityKey() }
                } else emptyMap()
                val restorable = mutableListOf<ImageFile>()
                val missing = mutableListOf<ImageFile>()
                var keptUnknown = images.count { it.uri in recovered.unknown } + unconfirmedUris.size
                candidates.forEach { image ->
                    if (image.uri !in confirmedSet) return@forEach
                    when (decideRestoreAction(image.uri, verification)) {
                        RestoreDecision.PROCEED_RESTORE -> {
                            if (undo == null || visibleByIdentity[image.mediaIdentityKey()]?.uri == image.uri) {
                                restorable += image
                            }
                        }
                        RestoreDecision.DROP_MISSING -> missing += image
                        RestoreDecision.KEEP_UNKNOWN -> keptUnknown++
                    }
                }
                // 单一记录事务：只移除核验通过的条目；未知项原样保留。
                val restored: List<ImageFile>
                val dropped: List<ImageFile>
                val alreadyRestored: Int
                if (undo != null) {
                    val result = recycleBinManager.removeBatchRecords(restorable, missing)
                    restored = result.restored
                    dropped = result.missing
                    alreadyRestored = result.alreadyRestored
                } else {
                    if (restorable.isNotEmpty()) recycleBinManager.removeAllFromRecycleBin(restorable)
                    if (missing.isNotEmpty()) recycleBinManager.removeAllFromRecycleBin(missing, permanentlyRemoved = true)
                    restored = restorable
                    dropped = missing
                    alreadyRestored = 0
                }
                // T03：内存态与持久层同口径——按媒体身份移除/回插，跨卷同数值
                // ID 记录互不影响。
                val removed = restored + dropped
                deletedImages = deletedImages.filterNot { row ->
                    removed.any {
                        if (undo != null) it.isSameRecycleRecord(row) else it.matchesSameMediaIdentity(row)
                    }
                }
                val restoredImages = if (undo != null) {
                    restored.map { visibleByIdentity.getValue(it.mediaIdentityKey()) }
                } else restored
                restoredImages.forEach { image ->
                    if (allImages.none { it.matchesSameMediaIdentity(image) }) allImages = allImages + image
                    val isInTabulaAlbum = albums.any { album ->
                        album.systemAlbumPath?.trimEnd('/')?.substringAfterLast('/') == image.bucketDisplayName
                    }
                    if (!isInTabulaAlbum && imagesForSorting.none { it.matchesSameMediaIdentity(image) }) {
                        imagesForSorting = imagesForSorting + image
                    }
                }
                // 恢复改变软删除集合，推荐派生集合同步失效（与移除路径对称）。
                if (restored.isNotEmpty()) recommendationEngine.invalidateSimilarGroupCache()
                // 重新查询媒体与持久回收站，统一刷新照片、相册数量和封面。
                if (restored.isNotEmpty() || (undo != null && dropped.isNotEmpty())) recycleBinLoadRevision++
                if (undo != null) {
                    if (restored.isNotEmpty()) {
                        when (currentScreen) {
                            AppScreen.INTENT_BATCH, AppScreen.STAGED_CLEANUP -> {
                                recomputeStagedCleanupSelection()
                                if (currentScreen == AppScreen.INTENT_BATCH) rebuildIntentBatch()
                            }
                            AppScreen.BURST_SELECTION -> {
                                val original = burstOriginalGroup
                                val active = burstSelectionGroup
                                if (original != null && original.id == undo.groupId && active != null) {
                                    val activeByIdentity = (active.images + restoredImages)
                                        .associateBy { it.mediaIdentityKey() }
                                    val merged = original.images.mapNotNull { activeByIdentity[it.mediaIdentityKey()] }
                                    burstKeptKeys = burstKeptKeys + restoredImages.map { it.mediaIdentityKey() }
                                    burstSelectionGroup = active.copy(
                                        images = merged,
                                        startTime = merged.minOf { it.dateModified },
                                        endTime = merged.maxOf { it.dateModified }
                                    )
                                }
                            }
                            else -> Unit
                        }
                    }
                    val failed = images.size - restored.size - alreadyRestored
                    val message = listOfNotNull(
                        restored.size.takeIf { it > 0 }?.let { "已恢复 $it 张" },
                        alreadyRestored.takeIf { it > 0 }?.let { "$it 张已在图库中" },
                        failed.takeIf { it > 0 }?.let { "$it 张未能恢复" }
                    ).joinToString("，")
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val message = buildString {
                    if (restorable.isNotEmpty()) append("已恢复 ${restorable.size} 张")
                    if (missing.isNotEmpty()) append(if (isEmpty()) "${missing.size} 张已不在媒体库" else "，${missing.size} 张已不在媒体库")
                    if (keptUnknown > 0) append(if (isEmpty()) "${keptUnknown} 张状态未核对已保留" else "，${keptUnknown} 张状态未核对已保留")
                }
                if (images.size > 1 && message.isNotEmpty()) {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                } else if (missing.isNotEmpty()) {
                    Toast.makeText(context, "照片已不在媒体库中，已清除对应回收站记录", Toast.LENGTH_LONG).show()
                } else if (keptUnknown > 0) {
                    Toast.makeText(context, "照片状态尚未核对，暂不能恢复，请检查照片访问权限或存储卷后重试", Toast.LENGTH_LONG).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("TabulaApp", "Could not restore recycle bin record", e)
                val message = if (undo != null) "未能撤销，可在回收站重试"
                    else "未能保存恢复记录，照片仍保留在回收站"
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            } finally {
                if (undo != null) batchUndoInFlight = false
            }
        }
    }


    /**
     * Batch delete
     */
    fun performDeleteBatch(images: List<ImageFile>, onResult: (BatchDeleteSummary<android.net.Uri>) -> Unit) {
        if (images.isEmpty()) {
            onResult(BatchDeleteSummary(emptySet(), emptySet(), false, null))
            return
        }
        
        scope.launch {
            // C2/T04：详情删除入口接入串行按项批处理（分批/取消不请求后续批/部分成功/UNKNOWN 隔离）。
            val summary = deleteInBatches(
                items = images.map { it.uri },
                deleteBatch = { batch ->
                    deleteMediaUrisBatch(
                        batch,
                        request = fileOperationManager::deleteImages,
                        permission = { result ->
                            suspendCancellableCoroutine { continuation ->
                                onRequestDeletePermission(result.intentSender) { allowed ->
                                    if (continuation.isActive) continuation.resume(allowed)
                                }
                            }
                        },
                        verify = fileOperationManager::verifyDeletionState
                    )
                },
                onDeleted = { confirmed ->
                    withContext(NonCancellable) {
                        val keys = confirmed.map { it.toString() }.toSet()
                        allImages = allImages.filter { it.uri.toString() !in keys }
                        imagesForSorting = imagesForSorting.filter { it.uri.toString() !in keys }
                    }
                }
            )
            val deletedUris = summary.deleted.map { it.toString() }.toSet()
            Log.d("TabulaApp", "Deleted batch: ${deletedUris.size}/${images.size}")
            onResult(summary)
        }
    }
    
    /**
     * 批量删除原图（清理功能）
     * 
     * @param uris 待删除的 URI 列表
     * @param onResult 回调，返回成功删除的 URI 列表
     */
    fun performDeleteUris(uris: List<android.net.Uri>, onResult: (BatchDeleteSummary<android.net.Uri>) -> Unit) {
        if (uris.isEmpty()) {
            onResult(BatchDeleteSummary(emptySet(), emptySet(), false, null))
            return
        }
        
        scope.launch {
            // C2/T04：原图清理入口接入串行按项批处理（分批/取消不请求后续批/部分成功/UNKNOWN 隔离）。
            val summary = deleteInBatches(
                items = uris,
                deleteBatch = { batch ->
                    deleteMediaUrisBatch(
                        batch,
                        request = fileOperationManager::deleteImages,
                        permission = { result ->
                            suspendCancellableCoroutine { continuation ->
                                onRequestDeletePermission(result.intentSender) { allowed ->
                                    if (continuation.isActive) continuation.resume(allowed)
                                }
                            }
                        },
                        verify = fileOperationManager::verifyDeletionState
                    )
                },
                onDeleted = { confirmed ->
                    withContext(NonCancellable) {
                        val keys = confirmed.map { it.toString() }.toSet()
                        albumManager.clearDeletedUris(keys)
                        allImages = allImages.filter { it.uri.toString() !in keys }
                        imagesForSorting = imagesForSorting.filter { it.uri.toString() !in keys }
                        pendingSourceImageUris = pendingSourceImageUris.filter { it !in confirmed }
                    }
                }
            )
            val deletedUris = summary.deleted.map { it.toString() }.toSet()
            Log.d("TabulaApp", "Cleanup deleted: ${deletedUris.size}/${uris.size}")
            onResult(summary)
        }
    }

    // ========== 屏幕内容定义 ==========
    LaunchedEffect(hapticEnabled, hapticStrength) {
        com.tabula.v3.ui.util.HapticFeedback.updateSettings(
            enabled = hapticEnabled,
            strength = hapticStrength
        )
    }

    /**
     * #90：提交归档并记录成功行为。
     *
     * 审计要求：不要把 actionId 当照片 ID。完整媒体身份来自 PendingArchive
     * 的 (imageUri 卷, imageId)，且**只在归档真实成功**（COMMITTED）时记账。
     *
     * #90 第四轮：记账还要看**发起时的会话与批次**（`action.intentTicket`）。
     * 归档是延迟提交的——换批之后才完成的旧动作仍然照原逻辑复制文件、更新
     * 图库，但**不往新批次记账**：那会让新批次的来源判断带上用户在这一批
     * 根本没碰过的相册。
     *
     * @param action 从 getLastPendingArchive() 取出的真实待归档项
     * @param snapshot 提交前的照片快照，用于按完整身份匹配真实 ImageFile
     */
    fun commitPendingArchiveWithFeedback(
        action: AlbumManager.PendingArchive,
        snapshot: List<ImageFile> = allImages + deletedImages
    ) {
        scope.launch {
            // 从 PendingArchive 的 (imageUri 卷, imageId) 解析完整身份
            val archivedImage = ArchiveIdentityRules.findInSnapshot(action, snapshot)

            val outcome = albumManager.commitPendingArchive(action.id)
            // 记账决策走 DeferredActionLedger（测试与生产同一条路径）：
            // 只有 COMMITTED 且发起时的会话/批次仍有效才计入
            if (tracksRoundIntent) {
                roundSession = DeferredActionLedger.onArchiveCommitted(
                    session = roundSession,
                    archivedImage = archivedImage,
                    action = action,
                    outcome = outcome,
                    currentIds = sessionBatchIds
                )
            }
            when (outcome) {
                AlbumManager.ArchiveCommitOutcome.COMMITTED -> Unit  // 记账已在上面处理
                AlbumManager.ArchiveCommitOutcome.COPY_FAILED ->
                    Toast.makeText(context, "归档失败，照片未复制", Toast.LENGTH_LONG).show()
                AlbumManager.ArchiveCommitOutcome.NOT_CLAIMED ->
                    android.util.Log.w("TabulaApp", "Archive action not claimed (already committed/cancelled): ${action.id}")
            }
        }
    }

    LaunchedEffect(currentScreen) {
        pendingBatchUndos = pendingBatchUndos.filter { it.canShowOn(currentScreen) }
    }
    val currentBatchUndo = pendingBatchUndos.firstOrNull { it.canShowOn(currentScreen) }
        ?.takeUnless { batchUndoInFlight }
    // Keep a receipt's timeout attached to that receipt when the burst picker closes.
    LaunchedEffect(currentBatchUndo) {
        if (currentBatchUndo != null) {
            kotlinx.coroutines.delay(UNDO_SNACKBAR_DURATION_MS)
            pendingBatchUndos = pendingBatchUndos - currentBatchUndo
        }
    }
    val batchUndoContent: @Composable (Modifier) -> Unit = { modifier ->
        val undo = currentBatchUndo
        if (undo != null) {
            key(undo) {
                UndoSnackbar(
                    visible = true,
                    message = buildString {
                        append("已移入回收站 ${undo.records.size} 张")
                        if (undo.report.isNotEmpty()) append("\n${undo.report}")
                    },
                    onUndo = {
                        if (undo in pendingBatchUndos && undo.canShowOn(currentScreen) &&
                            !batchUndoInFlight && !isDeletingRecycleBin && !deletePermissionInFlight &&
                            !intentBatchSaving && !burstCommitting
                        ) {
                            pendingBatchUndos = pendingBatchUndos - undo
                            restoreImagesInOneTransaction(undo.records, undo)
                        }
                    },
                    modifier = modifier,
                    animate = false
                )
            }
        }
    }

    val deckContent: @Composable () -> Unit = {
        DeckScreen(            // 使用 imagesForSorting（排除已归类到 Tabula 图集的照片）
            // 这样已归类的照片不会再次出现在待整理列表中
            batchUndo = { modifier ->
                if (currentScreen == AppScreen.DECK) batchUndoContent(modifier)
            },
            allImages = imagesForSorting,
            allImagesForCleanup = allImages,
            batchSize = currentBatchSize,
            isLoading = isLoading,
            topBarDisplayMode = currentTopBarMode,
            onKeep = { kept: ImageFile ->
                preferences.totalReviewedCount++
                // #90：成功保留也计入本轮统计（只在随机漫步普通整理模式）
                if (tracksRoundIntent) {
                    roundSession = RoundSessionRules.record(
                        roundSession, kept, RoundSessionRules.HandledAction.KEEP
                    )
                }
            },
            // #90：真正换批（手动「再来一组」/ 旧批全失效后自动重取）——
            // 清上一批统计与旧提示，并换批次标识
            onBatchAdvanced = {
                advanceBatchBoundary()
            },
            // #90：卡片页回报它实际持有的批次——候选池与提示判定都以它为准
            onBatchChanged = { batch -> currentDeckBatch = batch },
            // #90 第五轮：上滑发起删除时用于绑定凭证（当前会话/批次）
            currentIntentTicket = { capturedTicket() },
            onRemove = { pending ->
                // #90 第五轮：凭证由卡片的待删除记录带下来（上滑那一刻绑定的），
                // 这里**不再**重取当前批次——撤销超时/被顶掉时可能已经换批，
                // 重取会把旧批的动作记到新批统计上。
                val image = pending.image
                val deleteTicket = pending.ticket
                scope.launch {
                    try {
                        recycleBinManager.addToRecycleBin(image)
                        albumCleanupEngine.recordUserRemovedImage(image)
                        val persisted = recycleBinManager.loadRecycleBin()
                        // 写成功后才记账；只有发起时那批仍有效才计入
                        val beforeKeys = roundSession.currentBatchIdentityKeys
                        if (tracksRoundIntent) {
                            roundSession = DeferredActionLedger.onDeletePersisted(
                                session = roundSession,
                                pending = DeferredActionLedger.Pending(image, deleteTicket),
                                currentIds = sessionBatchIds
                            )
                        }
                        // #90 第五轮（仅 DEBUG）：登记本次记账决策，供设备用例
                        // 观察生产侧真实结果——规则函数本身测不出接线错误。
                        PendingDeleteTestHook.record(
                            imageKey = image.mediaIdentityKey(),
                            ticket = deleteTicket,
                            currentSession = sessionBatchIds.sessionId,
                            currentBatch = sessionBatchIds.batchId,
                            recorded = image.mediaIdentityKey() in roundSession.currentBatchIdentityKeys &&
                                image.mediaIdentityKey() !in beforeKeys
                        )
                        // 即时状态与刷新后的投影同口径：按媒体身份（卷, ID）过滤，
                        // 跨卷同数值 ID 的其他照片不受本次回收影响。
                        allImages = allImages.filterNot { row -> persisted.any { it.matchesSameMediaIdentity(row) } }
                        imagesForSorting =
                            imagesForSorting.filterNot { row -> persisted.any { it.matchesSameMediaIdentity(row) } }
                        deletedImages = persisted
                        preferences.totalReviewedCount++
                        preferences.totalDeletedCount++
                        recommendationEngine.invalidateSimilarGroupCache()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 失败回退：这张不计入本轮。同样只有发起时那批仍然有效
                        // 才回退——换批后撤销旧动作不能删掉新批次里的记录
                        roundSession = DeferredActionLedger.onDeleteFailed(
                            session = roundSession,
                            pending = DeferredActionLedger.Pending(image, deleteTicket),
                            currentIds = sessionBatchIds
                        )
                        Log.e("TabulaApp", "Could not mark photo for deletion", e)
                        Toast.makeText(context, "未能保存回收站记录，照片仍保留在待整理照片中", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onNavigateToTrash = {
                currentScreen = AppScreen.RECYCLE_BIN
            },
            onNavigateToSettings = {
                currentScreen = AppScreen.SETTINGS
            },
            onNavigateToAlbumDetail = { album ->
                selectedAlbumId = album.id
                currentScreen = AppScreen.ALBUM_VIEW
            },
            albums = albums,
            systemBuckets = systemBuckets,
            onAlbumSelect = { imageId, imageUri, albumId ->
                 scope.launch {
                     // 先提交之前的 pending action（如果有）
                     // 这样快速连续归档时，前一个会被自动提交
                     albumManager.getLastPendingArchive()?.let {
                         commitPendingArchiveWithFeedback(it)
                     }
                     
                     // 加入新的 pending action（不立即复制）。
                     // #90 第四轮：把**发起这一刻**的会话/批次标识绑进动作，
                     // 等 Snackbar 超时才提交时才有得比对——不能等到提交那一刻
                     // 再去读当前批次，那时可能已经换批了。
                     val album = albums.find { it.id == albumId }
                     albumManager.queueImageToAlbum(
                         imageId = imageId,
                         imageUri = imageUri,
                         albumId = albumId,
                         albumName = album?.name ?: albumId,
                         intentTicket = capturedTicket()
                     )
                 }
            },
            onCreateAlbum = { name, color, emoji ->
                scope.launch {
                    try {
                        albumManager.createAlbum(name, color, emoji)
                    } catch (e: Exception) {
                        Log.e("TabulaApp", "Failed to create album: $name", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "创建图集失败: ${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onCreateAlbumAndClassify = { name, color, emoji, image ->
                scope.launch {
                    try {
                        // 先提交之前的 pending action（如果有）
                        albumManager.getLastPendingArchive()?.let {
                            commitPendingArchiveWithFeedback(it)
                        }
                        
                        // 1. 创建图集
                        val newAlbum = albumManager.createAlbum(name, color, emoji)
                        // 2. 将图片加入待归档队列
                        albumManager.queueImageToAlbum(
                            imageId = image.id,
                            imageUri = image.uri.toString(),
                            albumId = newAlbum.id,
                            albumName = newAlbum.name,
                            intentTicket = capturedTicket()
                        )
                    } catch (e: Exception) {
                        Log.e("TabulaApp", "Failed to create album and classify: $name", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "创建图集失败: ${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onUndoAlbumAction = {
                scope.launch {
                    // 取消待执行的归档操作（图片还没被复制，所以无需删除）
                    albumManager.cancelLastPendingArchive()
                }
            },
            onCommitArchive = {
                scope.launch {
                    // Snackbar 消失时提交归档，执行真正的复制
                    albumManager.getLastPendingArchive()?.let {
                        commitPendingArchiveWithFeedback(it)
                    }
                }
            },
            onReorderAlbums = { newOrder ->
                scope.launch {
                    albumManager.reorderAlbums(newOrder)
                }
            },
            onSystemBucketClick = { bucketName ->
                selectedBucketName = bucketName
                currentScreen = AppScreen.SYSTEM_ALBUM_VIEW
            },
            // 推荐算法回调 - 使用 RecommendationEngine
            getRecommendedBatch = { images, size ->
                // 审计修复（#87）：用带组身份的入口，把推荐引擎实际用到的
                // SimilarGroup 记下来交给 DeckScreen；不再让 UI 猜组。
                val result = recommendationEngine.getRecommendedBatchWithGroup(
                    images, size,
                    identityCandidates = allImages + deletedImages
                )
                activeSimilarGroup = result.group
                result.images
            },
            // 推荐模式刷新触发器（切换算法时刷新批次）
            recommendModeRefreshKey = recommendModeRefreshKey,
            activeSimilarGroup = activeSimilarGroup,
            // 审计 #87：进入选片时排除已在回收站的成员。待归档的照片仍
            // 留在当前批次里、由 currentIndex 区分是否已处理，不在这里重复排除。
            excludedBurstKeys = deletedImages.map { it.mediaIdentityKey() }.toSet(),
            // 路线图 #83 第 4 项：进入连拍选片。签名是「交给我一个组」，
            // DeckScreen 只在当前批次被判定为连拍时才会调用。
            onOpenBurstSelection = { group ->
                burstSelectionGroup = group
                // 原组身份与显示组分开保存：显示组会随部分回收裁剪，原组不变。
                burstOriginalGroup = group
                burstKeptKeys = BurstSelectionRules.defaultKeptKeys(group)
                currentScreen = AppScreen.BURST_SELECTION
            },
            // 路线图 #83 第 7 项（审计 #90）：本轮整理意图。
            // 提示在批次装载后由 userHandledBatch 计算——只有用户真的在处理
            // 某个来源时才出现，而不是一进卡片页就弹。
            // 路线图 #83 第 7 项（审计 #90）：本轮整理意图。
            // 只在本轮确实在处理同一来源、且下一批抽得到时才展示。
            intentSourceName = if (tracksRoundIntent) {
                intentSuggestion?.let { key -> StagedSourceDisplayName.of(key) }
            } else {
                null
            },
            onAcceptIntent = {
                // 只在普通随机漫步模式接受；其它模式下这里根本不该出现提示
                intentSuggestion?.takeIf { tracksRoundIntent }?.let { key ->
                    recommendationEngine.acceptRoundIntent(key)
                    // 同一轮里不再对同一来源重复提示（避免接受后又弹一次）
                    roundSession = RoundSessionRules.markAccepted(roundSession, key)
                    intentSuggestion = null
                    intentDismissed = false
                    Toast.makeText(
                        context,
                        "下一批优先「${StagedSourceDisplayName.of(key)}」",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onDismissIntent = {
                // 忽略：本次会话不再提示（不写偏好、不写意图，重启后重新评估）
                intentDismissed = true
                intentSuggestion = null
            },
            // 从冷却池移除照片的回调（切换算法时移除未浏览的照片；
            // C1/T03-DECKCOOLDOWN：按完整媒体身份键移除）
            onRemoveFromCooldown = { unviewedImages ->
                recommendationEngine.removeFromCooldown(unviewedImages)
            },
            // 刷新按钮回调（系统相册集成后，不需要同步，只需刷新）
            onSyncClick = {
                scope.launch {
                    Toast.makeText(context, "正在刷新图库...", Toast.LENGTH_SHORT).show()
                    try {
                        // 刷新图集列表和图片列表
                        val repository = LocalImageRepository(context)
                        val refreshedData = withContext(Dispatchers.IO) {
                            val loadResult = repository.loadAllData()
                            val projected = projectSnapshot(loadResult, deletedImages)
                            albumManager.refreshAlbumsFromSystem(projected.systemBuckets)
                            projected
                        }
                        systemBuckets = refreshedData.systemBuckets
                        allImages = refreshedData.allImages
                        imagesForSorting = refreshedData.imagesExcludingTabula
                        Toast.makeText(context, "刷新完成", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e("TabulaApp", "Refresh failed", e)
                        Toast.makeText(context, "刷新失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            showHdrBadges = showHdrBadges,
            showMotionBadges = showMotionBadges,
            playMotionSound = playMotionSound,
            motionSoundVolume = motionSoundVolume,
            enableSwipeHaptics = swipeHapticsEnabled,
            isAdaptiveCardStyle = cardStyleMode == CardStyleMode.ADAPTIVE,
            swipeStyle = swipeStyle,
            tagSelectionMode = tagSelectionMode,
            tagsPerRow = tagsPerRow,
            tagSwitchSpeed = tagSwitchSpeed,
            isAlbumMode = isAlbumMode,
            onModeChange = { isAlbumMode = it },
            // 显示/隐藏 隐藏的图集
            showHiddenAlbums = showHiddenAlbums,
            onToggleShowHidden = { showHiddenAlbums = !showHiddenAlbums },
            // 图集操作回调
            onHideAlbum = { album ->
                scope.launch {
                    albumManager.hideAlbum(album.id)
                    Toast.makeText(context, "相册已隐藏", Toast.LENGTH_SHORT).show()
                }
            },
            onExcludeAlbum = { album, excluded ->
                scope.launch {
                    albumManager.setAlbumExcludedFromRecommend(album.id, excluded)
                    val message = if (excluded) "已屏蔽，不再推荐此图集的照片" else "已取消屏蔽"
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
            isAlbumExcluded = { album ->
                albumManager.isAlbumExcludedFromRecommend(album.id)
            },
            onBatchRemainingChange = { remaining ->
                // 更新流体云的批次剩余数量
                (context as? MainActivity)?.updateBatchRemaining(remaining, fluidCloudEnabled)
            },
            // 快捷操作按钮
            quickActionEnabled = quickActionEnabled,
            leftHandButtonX = quickActionButtonX,
            leftHandButtonY = quickActionButtonY,
            onQuickActionPositionChanged = { _, x, y ->
                quickActionButtonX = x
                quickActionButtonY = y
                preferences.quickActionButtonX = x
                preferences.quickActionButtonY = y
            },
            // 图集清理模式
            albumCleanupEngine = albumCleanupEngine,
            albumCleanupInfos = albumCleanupInfos,
            onRefreshAlbumCleanupInfos = { refreshAlbumCleanupInfos() },
            // 图集清理模式状态（提升到 TabulaApp 级别）
            isAlbumCleanupMode = isAlbumCleanupMode,
            onAlbumCleanupModeChange = { isAlbumCleanupMode = it },
            selectedCleanupAlbum = selectedCleanupAlbum,
            onSelectedCleanupAlbumChange = { selectedCleanupAlbum = it },
            onNavigateToOtherAlbums = { otherAlbumsList ->
                otherAlbumsForNavigation = otherAlbumsList
                currentScreen = AppScreen.OTHER_ALBUMS
            },
            // 原图清理
            pendingCleanupCount = pendingSourceImageUris.size + unverifiedSourceImageCount,
            onCleanupSourceImages = {
                scope.launch {
                    // 重新获取最新的待清理原图（用户可能又归类了新照片）
                    val cleanableResult = albumManager.getCleanableUrisForAllAlbums()
                    pendingSourceImageUris = cleanableResult.sourceUris
                    unverifiedSourceImageCount = cleanableResult.unverifiedSourceCount
                    if (cleanableResult.sourceUris.isNotEmpty()) {
                        dialogSourceImageUris = cleanableResult.sourceUris.toList()
                        showSourceImageDeletionDialog = true
                    } else {
                        showSourceImageDeletionDialog = false
                        if (cleanableResult.unverifiedSourceCount > 0) {
                            Toast.makeText(
                                context,
                                "副本尚未验证，已保留 ${cleanableResult.unverifiedSourceCount} 张原图",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(context, "没有需要清理的原图", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onOpenStagedCleanup = { openStagedCleanup() }
        )
    }

    val recycleBinContent: @Composable () -> Unit = {
        RecycleBinScreen(
            deletedImages = deletedImages,
            // 路线图 #83 第 5 项：选中时提示"这些里还有几张留着相同副本"。
            // 只做提示，不拦删除——用户仍可全部删掉。
            duplicateCounts = duplicateCounts,
            duplicateDetails = duplicateDetails,
            onRestore = { image ->
                restoreImagesInOneTransaction(listOf(image))
            },
            onRestoreBatch = { images ->
                restoreImagesInOneTransaction(images)
            },
            onPermanentDelete = { image ->
                deleteRecycleBinImages(listOf(image))
            },
            onPermanentDeleteBatch = { images ->
                deleteRecycleBinImages(images)
            },
            onClearAll = {
                deleteRecycleBinImages(deletedImages)
            },
            onNavigateBack = {
                currentScreen = AppScreen.DECK
            },
            showHdrBadges = showHdrBadges,
            showMotionBadges = showMotionBadges,
            playMotionSound = playMotionSound,
            motionSoundVolume = motionSoundVolume
        )
        if (isDeletingRecycleBin || deletePermissionInFlight) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text("正在处理回收站") },
                text = {
                    Column {
                        androidx.compose.material3.CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("请在系统提示中确认删除。取消系统提示可停止后续删除。")
                    }
                }
            )
        }
    }

    val settingsContent: @Composable () -> Unit = {
        SettingsScreen(
            preferences = preferences,
            imageCount = allImages.size,
            trashCount = deletedImages.size,
            onThemeChange = onThemeChange,
            onBatchSizeChange = { size -> currentBatchSize = size },
            onTopBarModeChange = { mode -> currentTopBarMode = mode },
            showHdrBadges = showHdrBadges,
            showMotionBadges = showMotionBadges,
            playMotionSound = playMotionSound,
            motionSoundVolume = motionSoundVolume,
            hapticEnabled = hapticEnabled,
            hapticStrength = hapticStrength,
            swipeHapticsEnabled = swipeHapticsEnabled,
            onShowHdrBadgesChange = { enabled ->
                showHdrBadges = enabled
                preferences.showHdrBadges = enabled
            },
            onShowMotionBadgesChange = { enabled ->
                showMotionBadges = enabled
                preferences.showMotionBadges = enabled
            },
            onPlayMotionSoundChange = { enabled ->
                playMotionSound = enabled
                preferences.playMotionSound = enabled
            },
            onMotionSoundVolumeChange = { volume ->
                motionSoundVolume = volume
                preferences.motionSoundVolume = volume
            },
            onHapticEnabledChange = { enabled ->
                hapticEnabled = enabled
                preferences.hapticEnabled = enabled
            },
            onHapticStrengthChange = { strength ->
                hapticStrength = strength
                preferences.hapticStrength = strength
            },
            onSwipeHapticsEnabledChange = { enabled ->
                swipeHapticsEnabled = enabled
                preferences.swipeHapticsEnabled = enabled
            },
            onNavigateToAbout = { currentScreen = AppScreen.ABOUT },
            onNavigateBack = { currentScreen = AppScreen.DECK },
            onNavigateToStatistics = { currentScreen = AppScreen.STATISTICS },
            onRecommendModeChange = { 
                // 切换推荐模式后，增加刷新触发器以刷新批次
                recommendModeRefreshKey++
            },
            fluidCloudEnabled = fluidCloudEnabled,
            onFluidCloudEnabledChange = { enabled ->
                fluidCloudEnabled = enabled
                preferences.fluidCloudEnabled = enabled
            },
            onNavigateToVibrationSound = { currentScreen = AppScreen.VIBRATION_SOUND },
            onNavigateToDisplaySettings = { currentScreen = AppScreen.DISPLAY_SETTINGS },
            onNavigateToLab = { currentScreen = AppScreen.LAB },
            onNavigateToReminderSettings = { currentScreen = AppScreen.REMINDER_SETTINGS },
            excludedAlbumCount = excludedAlbumCount,
            hiddenAlbumCount = hiddenAlbumCount,
            onNavigateToHiddenAlbums = { currentScreen = AppScreen.HIDDEN_ALBUMS },
            onNavigateToPrivacyPolicy = { currentScreen = AppScreen.PRIVACY_POLICY },
            onNavigateToTutorial = { currentScreen = AppScreen.TUTORIAL },
            onNavigateToStagedCleanup = { openStagedCleanup() },
            stagedCleanupSummary = if (preferences.hasStagedCleanupSourceChoice) {
                if (preferences.stagedCleanupSourceKeys.isEmpty()) "" else
                    "${preferences.stagedCleanupSourceKeys.size} 个来源"
            } else "",
            onNavigateToRelayReview = {
                relayRevision++
                currentScreen = AppScreen.RELAY_REVIEW
            },
            relayPendingCount = RelayReviewRules.pendingReviewRows(
                RelayReviewRules.resolveRows(relayMarks, allImages)
            ).size,
            scrollState = settingsScrollState
        )
    }
    
    // 系统分享产生的待清记录页面（#85）
    val relayReviewContent: @Composable () -> Unit = {
        // 只把当前图库里仍存在的照片作为定位来源；回收站中的不算"原图"
        val recycledKeys = deletedImages.map { it.mediaIdentityKey() }.toSet()
        val library = allImages.filterNot { it.mediaIdentityKey() in recycledKeys }
        val rows = RelayReviewRules.resolveRows(
            marks = relayMarks,
            library = library,
            recycleBinKeys = deletedImages.map { it.mediaIdentityKey() }.toSet()
        )
        RelayReviewScreen(
            rows = rows,
            library = library,
            showReviewOnly = relayShowReviewOnly,
            onToggleReviewOnly = { relayShowReviewOnly = !relayShowReviewOnly },
            onRecycle = { image, mark ->
                scope.launch {
                    // #85：先写回收站并读回确认持久化，再移除待清标记。
                    // 任一步失败都保留标记、如实提示；回收站按身份去重，
                    // 重试不会重复写入或丢失记录。
                    val transaction = RelayRecycleTransaction(
                        recycleWrite = { recycleBinManager.addToRecycleBin(it) },
                        recycleRead = { recycleBinManager.loadRecycleBin() },
                        markRemove = { key -> shareRelayStore.remove(listOf(key)) },
                        markRead = { shareRelayStore.loadResult() }
                    )
                    val outcome = withContext(Dispatchers.IO) {
                        transaction.execute(mark, image)
                    }
                    when (outcome) {
                        is RelayRecycleTransaction.Outcome.Done,
                        is RelayRecycleTransaction.Outcome.MarkRemovalFailed -> {
                            // 回收站确实写入成功：同步真实状态
                            val persisted = recycleBinManager.loadRecycleBin()
                            val moved = persisted.map { it.mediaIdentityKey() }.toSet()
                            allImages = allImages.filterNot { it.mediaIdentityKey() in moved }
                            imagesForSorting = imagesForSorting.filterNot { it.mediaIdentityKey() in moved }
                            deletedImages = persisted
                        }
                        else -> Unit
                    }
                    if (outcome is RelayRecycleTransaction.Outcome.RecycleFailed) {
                        Log.e("TabulaApp", "Relay recycle failed: ${outcome.reason}")
                    }
                    Toast.makeText(context, transaction.message(outcome), Toast.LENGTH_LONG).show()
                    relayRevision++
                }
            },
            onRemoveMark = { row ->
                // 审计 #85：撤销/重试失败时保留页面状态并提示，不当作已完成
                val result = RelayReviewController(shareRelayStore).undo(row.mark)
                when (result) {
                    is ShareRelayStore.WriteResult.Ok -> {
                        Toast.makeText(context, "标记已移除", Toast.LENGTH_SHORT).show()
                        relayRevision++
                    }
                    is ShareRelayStore.WriteResult.Failed -> {
                        Toast.makeText(
                            context,
                            "标记移除失败：${result.reason}，记录已保留，可重试",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            onChooseImage = { row, chosen ->
                val result = RelayReviewController(shareRelayStore).manualConfirm(row.mark, chosen)
                Toast.makeText(
                    context,
                    when (result) {
                        is ShareRelayStore.WriteResult.Ok -> {
                            // 确认后按身份去重：同一张只留一条
                            val total = shareRelayStore.load()
                            "已关联原图，共 ${total.size} 条记录"
                        }
                        is ShareRelayStore.WriteResult.Failed ->
                            "确认失败：${result.reason}，原记录保留"
                    },
                    Toast.LENGTH_LONG
                ).show()
                relayRevision++
            },
            onNavigateBack = { currentScreen = AppScreen.SETTINGS }
        )
    }

    // 使用教程页面
    val tutorialContent: @Composable () -> Unit = {
        TutorialScreen(
            onNavigateBack = { currentScreen = AppScreen.SETTINGS }
        )
    }

    val vibrationSoundContent: @Composable () -> Unit = {
        val isDark = LocalIsDarkTheme.current
        VibrationSoundScreen(
            backgroundColor = if (isDark) Color.Black else Color(0xFFF2F2F7),
            cardColor = if (isDark) Color(0xFF1C1C1E) else Color.White,
            textColor = if (isDark) Color.White else Color.Black,
            secondaryTextColor = Color(0xFF8E8E93),
            accentColor = TabulaColors.EyeGold,
            showMotionBadges = showMotionBadges,
            playMotionSound = playMotionSound,
            motionSoundVolume = motionSoundVolume,
            hapticEnabled = hapticEnabled,
            hapticStrength = hapticStrength,
            swipeHapticsEnabled = swipeHapticsEnabled,
            onPlayMotionSoundChange = { enabled ->
                playMotionSound = enabled
                preferences.playMotionSound = enabled
            },
            onMotionSoundVolumeChange = { volume ->
                motionSoundVolume = volume
                preferences.motionSoundVolume = volume
            },
            onHapticEnabledChange = { enabled ->
                hapticEnabled = enabled
                preferences.hapticEnabled = enabled
                HapticFeedback.updateSettings(enabled, hapticStrength)
            },
            onHapticStrengthChange = { strength ->
                hapticStrength = strength
                preferences.hapticStrength = strength
                HapticFeedback.updateSettings(hapticEnabled, strength)
            },
            onSwipeHapticsEnabledChange = { enabled ->
                swipeHapticsEnabled = enabled
                preferences.swipeHapticsEnabled = enabled
            },
            onNavigateBack = { currentScreen = AppScreen.SETTINGS }
        )
    }

    val labContent: @Composable () -> Unit = {
        val isDark = LocalIsDarkTheme.current
        LabScreen(
            backgroundColor = if (isDark) Color.Black else Color(0xFFF2F2F7),
            cardColor = if (isDark) Color(0xFF1C1C1E) else Color.White,
            textColor = if (isDark) Color.White else Color.Black,
            secondaryTextColor = Color(0xFF8E8E93),
            fluidCloudEnabled = fluidCloudEnabled,
            onFluidCloudEnabledChange = { enabled ->
                fluidCloudEnabled = enabled
                preferences.fluidCloudEnabled = enabled
            },
            liquidGlassLabEnabled = liquidGlassLabEnabled,
            onLiquidGlassLabEnabledChange = onLiquidGlassLabEnabledChange,
            // 标签收纳设置
            tagSelectionMode = tagSelectionMode,
            tagSwitchSpeed = tagSwitchSpeed,
            tagsPerRow = tagsPerRow,
            onTagSelectionModeChange = { mode ->
                tagSelectionMode = mode
                preferences.tagSelectionMode = mode
            },
            onTagSwitchSpeedChange = { speed ->
                tagSwitchSpeed = speed
                preferences.tagSwitchSpeed = speed
            },
            onTagsPerRowChange = { count ->
                tagsPerRow = count
                preferences.tagsPerRow = count
            },
            onNavigateBack = { currentScreen = AppScreen.SETTINGS }
        )
    }
    
    // 提醒设置页面
    val reminderSettingsContent: @Composable () -> Unit = {
        val isDark = LocalIsDarkTheme.current
        val context = LocalContext.current
        ReminderSettingsScreen(
            backgroundColor = if (isDark) Color.Black else Color(0xFFF2F2F7),
            cardColor = if (isDark) Color(0xFF1C1C1E) else Color.White,
            textColor = if (isDark) Color.White else Color.Black,
            secondaryTextColor = Color(0xFF8E8E93),
            accentColor = TabulaColors.EyeGold,
            showDeleteConfirm = showDeleteConfirm,
            onShowDeleteConfirmChange = { enabled ->
                showDeleteConfirm = enabled
                preferences.showDeleteConfirm = enabled
            },
            sourceImageDeletionStrategy = sourceImageDeletionStrategy,
            onSourceImageDeletionStrategyChange = { strategy ->
                sourceImageDeletionStrategy = strategy
                preferences.sourceImageDeletionStrategy = strategy
                val message = if (strategy == SourceImageDeletionStrategy.ASK_EVERY_TIME) "归档提醒已开启" else "归档提醒已关闭"
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            },
            onNavigateBack = { currentScreen = AppScreen.SETTINGS }
        )
    }
    
    // 隐藏与屏蔽图集管理页面
    // 隐藏与屏蔽图集管理页面 (实际上是图集标签管理)
    val hiddenAlbumsContent: @Composable () -> Unit = {
        // 使用 count 作为 key 触发列表刷新，实现响应式更新
        // 获取排除的相册 ID 集合
        val excludedIds = remember(excludedAlbumCount) {
             albumManager.getExcludedAlbumIds()
        }
        
        AlbumTagManagementScreen(
            albums = albums,
            allImages = allImages,
            excludedAlbumIds = excludedIds,
            preferences = preferences,
            onReorderAlbums = { newOrder ->
                scope.launch { albumManager.reorderAlbums(newOrder) }
            },
            onToggleAlbumHidden = { albumId, isHidden ->
                scope.launch {
                     if (isHidden) albumManager.hideAlbum(albumId) else albumManager.unhideAlbum(albumId)
                }
            },
            onToggleAlbumExcluded = { albumId, isExcluded ->
                scope.launch {
                    albumManager.setAlbumExcludedFromRecommend(albumId, isExcluded)
                }
            },
            onResetTagSorting = {
                // 如果需要重置排序，尝试传入空列表或待实现的方法
                // 目前暂时提示用户
                Toast.makeText(context, "排序已重置", Toast.LENGTH_SHORT).show()
                // scope.launch { albumManager.resetAlbumOrder() } // 方法不存在
            },
            onRefreshAlbums = {
                 scope.launch { albumManager.refreshAlbumsFromSystem() }
            },
            onNavigateBack = { currentScreen = AppScreen.SETTINGS }
        )
    }
    
    // 隐私政策页面
    val privacyPolicyContent: @Composable () -> Unit = {
        PrivacyPolicyScreen(
            onNavigateBack = { currentScreen = AppScreen.SETTINGS }
        )
    }

    // 显示设置页面
    val displaySettingsContent: @Composable () -> Unit = {
        val isDark = LocalIsDarkTheme.current
        DisplaySettingsScreen(
            backgroundColor = if (isDark) Color.Black else Color(0xFFF2F2F7),
            cardColor = if (isDark) Color(0xFF1C1C1E) else Color.White,
            textColor = if (isDark) Color.White else Color.Black,
            secondaryTextColor = Color(0xFF8E8E93),
            accentColor = TabulaColors.EyeGold,
            batchSize = currentBatchSize,
            onBatchSizeChange = { size ->
                currentBatchSize = size
                preferences.batchSize = size
            },
            showHdrBadges = showHdrBadges,
            showMotionBadges = showMotionBadges,
            cardStyleMode = cardStyleMode,
            swipeStyle = swipeStyle,
            onShowHdrBadgesChange = { enabled ->
                showHdrBadges = enabled
                preferences.showHdrBadges = enabled
            },
            onShowMotionBadgesChange = { enabled ->
                showMotionBadges = enabled
                preferences.showMotionBadges = enabled
            },
            onCardStyleModeChange = { mode ->
                cardStyleMode = mode
                preferences.cardStyleMode = mode
            },
            onSwipeStyleChange = { style ->
                swipeStyle = style
                preferences.swipeStyle = style
            },
            // 快捷操作按钮
            quickActionEnabled = quickActionEnabled,
            onQuickActionEnabledChange = { enabled ->
                quickActionEnabled = enabled
                preferences.quickActionButtonEnabled = enabled
            },
            onResetButtonPosition = {
                preferences.resetQuickActionButtonPosition()
                quickActionButtonX = preferences.quickActionButtonX
                quickActionButtonY = preferences.quickActionButtonY
            },
            onNavigateBack = { currentScreen = AppScreen.SETTINGS }
        )
    }

    val aboutContent: @Composable () -> Unit = {
        AboutScreen(
            onNavigateBack = { currentScreen = AppScreen.SETTINGS }
        )
    }

    val statisticsContent: @Composable () -> Unit = {
        StatisticsScreen(
            onBack = { currentScreen = AppScreen.SETTINGS }
        )
    }

    val albumViewContent: @Composable () -> Unit = {
        AlbumViewScreen(
            albums = albums,
            allImages = allImages,
            getImagesForAlbum = { albumId -> albumManager.getImageIdsForAlbum(albumId) },
            getAlbumImages = { albumId ->
                // C0-DETAIL-EMPTY-RETRY 探针接缝（仅 debug 构建）：强制详情加载失败。
                if (BuildConfig.DEBUG && preferences.debugForceAlbumLoadFailure) {
                    throw java.io.IOException("probe: forced album load failure")
                }
                val images = albumManager.getAlbumDetailImages(albumId)
                if (BuildConfig.DEBUG) {
                    com.tabula.v3.ui.util.DebugAlbumLoadGate.awaitIfArmed(albumId)
                    // Keep the existing cancellable slow-load probe compatible.
                    val delayMs = preferences.debugAlbumLoadDelayMs
                    if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
                }
                images
            },
            onCreateAlbum = { name, color, emoji ->
                scope.launch {
                    try {
                        albumManager.createAlbum(name, color, emoji)
                    } catch (e: Exception) {
                        Log.e("TabulaApp", "Failed to create album in AlbumView: $name", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "创建图集失败: ${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onUpdateAlbum = { album ->
                scope.launch { albumManager.updateAlbum(album) }
            },
            onSetCover = { albumName, image ->
                scope.launch { albumManager.setAlbumCover(albumName, image) }
            },
            onDeleteAlbum = { albumId ->
                scope.launch { albumManager.deleteAlbum(albumId) }
            },
            onHideAlbum = { albumId ->
                scope.launch { 
                    albumManager.hideAlbum(albumId)
                    Toast.makeText(context, "相册已隐藏", Toast.LENGTH_SHORT).show()
                }
            },
            onUnhideAlbum = { albumId ->
                scope.launch {
                    albumManager.unhideAlbum(albumId)
                    Toast.makeText(context, "相册已取消隐藏", Toast.LENGTH_SHORT).show()
                }
            },
            onDeleteEmptyAlbum = { albumId ->
                scope.launch {
                    val success = albumManager.deleteEmptyAlbum(albumId)
                    if (success) {
                        Toast.makeText(context, "相册已删除", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onExcludeFromRecommend = { albumId, excluded ->
                scope.launch {
                    albumManager.setAlbumExcludedFromRecommend(albumId, excluded)
                    val message = if (excluded) "已屏蔽，不再推荐此图集的照片" else "已取消屏蔽"
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
            isAlbumExcludedFromRecommend = { albumId ->
                albumManager.isAlbumExcludedFromRecommend(albumId)
            },
            onNavigateBack = { currentScreen = AppScreen.DECK },
            initialAlbumId = selectedAlbumId,
            showHdrBadges = showHdrBadges,
            showMotionBadges = showMotionBadges,
            playMotionSound = playMotionSound,
            motionSoundVolume = motionSoundVolume,
            onMoveToAlbum = { identityKeys, targetAlbumId ->
                scope.launch {
                    selectedAlbumId?.let { currentAlbumId ->
                        // C1/T03-MOVECOPY-IDENTITY：完整身份选择键直达消费端，
                        // AlbumManager 从当前媒体集合按身份重新解析。
                        val result = albumManager.moveImagesByIdentity(identityKeys, currentAlbumId, targetAlbumId)
                        // T07/TC56-RESULT：授权拒绝/失败/失效选择分项如实反馈
                        Toast.makeText(context, moveImagesResultMessage(result), Toast.LENGTH_LONG).show()
                    }
                }
            },
            onCopyToAlbum = { identityKeys, targetAlbumId ->
                scope.launch {
                    // T07/TC56-RESULT：复制同样按逐项结果如实反馈（此前无任何反馈）
                    val result = albumManager.copyImagesByIdentity(identityKeys, targetAlbumId)
                    Toast.makeText(context, copyImagesResultMessage(result), Toast.LENGTH_LONG).show()
                }
            },
            onDeleteImages = { identityKeys ->
                // O5③：按完整媒体身份（卷|ID）解析确认行——不同卷的同数值 ID
                // 只删确认选中的那一行，另一卷同 ID 行不进入删除请求。
                val imagesToDelete = resolveImagesByIdentitySelection(allImages, identityKeys)
                if (imagesToDelete.isNotEmpty()) {
                    // 执行永久删除操作
                    performDeleteBatch(imagesToDelete) { summary ->
                        if (summary.deleted.isNotEmpty()) {
                            scope.launch { albumManager.refreshAlbumsFromSystem() }
                        }
                        Toast.makeText(context, mediaDeletionMessage(summary, "图片"), Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    val systemAlbumViewContent: @Composable () -> Unit = {
        var bucketImages by remember(selectedBucketName) { mutableStateOf<List<ImageFile>>(emptyList()) }
        LaunchedEffect(selectedBucketName) {
            val bucketName = selectedBucketName
            if (bucketName.isNullOrBlank()) {
                bucketImages = emptyList()
                return@LaunchedEffect
            }

            bucketImages = runCatching {
                withContext(Dispatchers.IO) {
                    LocalImageRepository(context).getImagesByBucket(bucketName)
                }
            }.onFailure { error ->
                Log.e("TabulaApp", "Failed to load system bucket: $bucketName", error)
            }.getOrDefault(emptyList())
        }
        
        SystemAlbumViewScreen(
            albumName = selectedBucketName ?: "",
            images = bucketImages,
            onNavigateBack = { currentScreen = AppScreen.DECK },
            showHdrBadges = showHdrBadges,
            showMotionBadges = showMotionBadges,
            playMotionSound = playMotionSound,
            motionSoundVolume = motionSoundVolume
        )
    }
    
    val stagedCleanupContent: @Composable () -> Unit = {
        val selection = stagedCleanupSelection
        if (selection == null && !preferences.hasStagedCleanupSourceChoice) {
            StagedCleanupSourcePickerContent(
                sources = stagedCleanupSources,
                selectedSourceKeys = preferences.stagedCleanupSourceKeys.ifEmpty {
                    stagedCleanupSelectedKeys
                },
                isLoading = stagedCleanupLoading,
                onToggleSource = { key ->
                    val current = preferences.stagedCleanupSourceKeys.ifEmpty {
                        stagedCleanupSelectedKeys
                    }
                    val next = if (key in current) current - key else current + key
                    preferences.stagedCleanupSourceKeys = next
                    stagedCleanupSelectedKeys = next
                },
                onConfirm = {
                    // 审计修复：首次进入时预勾的推荐来源只存在内存
                    // （openStagedCleanup 写的是 stagedCleanupSelectedKeys），
                    // 偏好里还是空的。确认时必须把内存里的选择落到偏好，
                    // 否则 recomputeStagedCleanupSelection 读到空集合，
                    // 候选页会显示"本轮 0 个来源"（run 45175633833 的真实原因）。
                    val effective = preferences.stagedCleanupSourceKeys
                        .ifEmpty { stagedCleanupSelectedKeys }
                    preferences.stagedCleanupSourceKeys = effective
                    preferences.hasStagedCleanupSourceChoice = true
                    stagedCleanupSelectedKeys = effective
                    recomputeStagedCleanupSelection()
                },
                onNavigateBack = { currentScreen = AppScreen.DECK }
            )
        } else {
            StagedCleanupCandidateContent(
                selection = selection ?: StagedCleanupRules.select(
                    allImages = allImages,
                    sources = emptyList(),
                    nowMillis = System.currentTimeMillis()
                ),
                selectedIdentityKeys = stagedCleanupSelectedKeys,
                isSaving = stagedCleanupSaving,
                onToggleImage = { image ->
                    val key = cleanupIdentityKey(image)
                    stagedCleanupSelectedKeys = if (key in stagedCleanupSelectedKeys) {
                        stagedCleanupSelectedKeys - key
                    } else {
                        stagedCleanupSelectedKeys + key
                    }
                },
                onToggleGroup = { images, select ->
                    val keys = images.map { cleanupIdentityKey(it) }
                    stagedCleanupSelectedKeys = if (select) {
                        stagedCleanupSelectedKeys + keys
                    } else {
                        stagedCleanupSelectedKeys - keys.toSet()
                    }
                },
                onSelectAllOld = {
                    stagedCleanupSelectedKeys = selection?.oldImages.orEmpty()
                        .map { cleanupIdentityKey(it) }
                        .toSet()
                },
                onClearSelection = { stagedCleanupSelectedKeys = emptySet() },
                onSkip = {
                    // 跳过 = 这次先不看：只压住进程内候选，重启后照片重新出现。
                    val keys = selection?.allCandidateImages.orEmpty()
                        .map { cleanupIdentityKey(it) }
                    preferences.skipStagedCleanupImages(keys)
                    stagedCleanupSkippedKeys = preferences.stagedCleanupSkippedIdentityKeys
                    stagedCleanupSelectedKeys = emptySet()
                    recomputeStagedCleanupSelection()
                },
                onRetainSelected = {
                    // 长期保留 = 以后都别再提：写偏好，跨重启生效，只影响暂存清理范围。
                    val keys = selection?.allCandidateImages.orEmpty()
                        .filter { cleanupIdentityKey(it) in stagedCleanupSelectedKeys }
                        .map { cleanupIdentityKey(it) }
                    if (keys.isEmpty()) return@StagedCleanupCandidateContent
                    preferences.retainStagedCleanupImages(keys)
                    stagedCleanupRetainedKeys = preferences.stagedCleanupRetainedIdentityKeys
                    stagedCleanupSelectedKeys = emptySet()
                    recomputeStagedCleanupSelection()
                    Toast.makeText(
                        context,
                        "${keys.size} 张照片已长期保留，暂存清理不再提示",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onConfirm = {
                    val toMove = selection?.allCandidateImages.orEmpty()
                        .filter { cleanupIdentityKey(it) in stagedCleanupSelectedKeys }
                    if (toMove.isEmpty() || stagedCleanupSaving) return@StagedCleanupCandidateContent
                    stagedCleanupSaving = true
                    scope.launch {
                        try {
                            // 与卡片上滑删除同一条路径：写入现有回收站，可在回收站恢复。
                            recycleBinManager.addAllToRecycleBin(toMove)
                            val persisted = recycleBinManager.loadRecycleBin()
                            val movedKeys = persisted.map { cleanupIdentityKey(it) }.toSet()
                            allImages = allImages.filterNot { cleanupIdentityKey(it) in movedKeys }
                            imagesForSorting = imagesForSorting.filterNot { cleanupIdentityKey(it) in movedKeys }
                            deletedImages = persisted
                            preferences.totalReviewedCount += toMove.size
                            preferences.totalDeletedCount += toMove.size
                            recommendationEngine.invalidateSimilarGroupCache()
                            stagedCleanupSelectedKeys = emptySet()
                            recomputeStagedCleanupSelection()
                            Toast.makeText(
                                context,
                                "${toMove.size} 张照片已移入回收站",
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("TabulaApp", "Staged cleanup could not reach the recycle bin", e)
                            Toast.makeText(
                                context,
                                "未能保存回收站记录，照片未移入；请重试",
                                Toast.LENGTH_LONG
                            ).show()
                        } finally {
                            stagedCleanupSaving = false
                        }
                    }
                },
                onNavigateBack = { currentScreen = AppScreen.DECK },
                onChangeSources = {
                    preferences.stagedCleanupSourceKeys = emptySet()
                    preferences.hasStagedCleanupSourceChoice = false
                    stagedCleanupSelectedKeys = emptySet()
                    stagedCleanupSelection = null
                },
                onOpenIntentBatch = { openIntentBatch() }
            )
        }
    }

    // ========== 按整理目的凑一批（路线图第 8 项 / #94） ==========
    // 与暂存清理共用一个回收站入口：勾选后走 preferences 的「长期保留」或
    // recycleBinManager.addAllToRecycleBin，因此回收站恢复、撤销语义与其它入口一致。
    val intentBatchContent: @Composable () -> Unit = {
        val currentBatch: IntentBatch = intentBatch
            ?: IntentBatch(emptyList(), emptyList(), emptyList(), emptyList(), 0)
        IntentBatchContent(
            batchUndo = { modifier ->
                if (currentScreen == AppScreen.INTENT_BATCH) batchUndoContent(modifier)
            },
            title = "按整理目的凑一批",
            batch = currentBatch,
            selectedIdentityKeys = intentBatchSelectedKeys,
            isAnalyzing = intentBatchAnalyzing,
            isSaving = intentBatchSaving,
            engineNote = intentBatchEngineNote,
            statusLine = intentBatchStatus,
            assistAvailable = true,
            assistKeyConfigured = intentAssistKeyStates[intentAssistService] == true,
            // 两个服务各自的 Key 状态：界面据此显示「哪家已配 Key」，也让对话框并列展示。
            assistKeyConfiguredByService = intentAssistKeyStates,
            assistService = intentAssistService,
            onSelectAssistService = { service -> selectIntentAssistService(service) },
            // 单一事实来源：直接读授权状态机。intentAssistRevision 让重组能观察到变化。
            assistEnabledForBatch = intentAssistAuth.granted.also { intentAssistRevision },
            // NOTE: `intentAssistRevision` 只是读取以建立 Compose 依赖关系；值本身不参与渲染。
            assistNoticeAcknowledged = intentAssistAuth.noticeAcknowledged.also { intentAssistRevision },
            onAcknowledgeAssistNotice = { intentAssistAuth.acknowledgeNotice() },
            onToggleAssistForBatch = { enabled ->
                // 用户自己的动作：授权随他设置，不当成换批
                intentAssistAuth.onUserToggle(enabled)
                intentAssistRevision++
                if (enabled) {
                    // 用户在本批主动打开：这是他的授权，重算时保留
                    if (!intentAssistKeyStore.hasKey()) {
                        showIntentAssistKeyDialog = true
                    }
                    reanalyzeCurrentBatch()
                } else {
                    // 用户在本批主动关闭：停止后续请求 + 作废在途 + 用本地结果重算
                    reanalyzeCurrentBatch()
                }
            },
            onOpenAssistSettings = { showIntentAssistKeyDialog = true },
            onToggleImage = { candidate ->
                val key = intentIdentityOf(candidate)
                intentBatchSelectedKeys = if (key in intentBatchSelectedKeys) {
                    intentBatchSelectedKeys - key
                } else {
                    intentBatchSelectedKeys + key
                }
            },
            onRetainSelected = {
                val keys = currentBatch.allCandidates()
                    .filter { intentIdentityOf(it) in intentBatchSelectedKeys }
                    .map { intentIdentityOf(it) }
                if (keys.isNotEmpty()) {
                    preferences.retainStagedCleanupImages(keys)
                    stagedCleanupRetainedKeys = preferences.stagedCleanupRetainedIdentityKeys
                    intentBatchSelectedKeys = emptySet()
                    Toast.makeText(context, "${keys.size} 张已长期保留", Toast.LENGTH_SHORT).show()
                    recomputeStagedCleanupSelection()
                    // 批次内容变了（这些图不再属于候选）→ 重建，新批默认关闭
                    rebuildIntentBatch()
                }
            },
            onConfirm = {
                if (intentBatchSaving || batchUndoInFlight) return@IntentBatchContent
                // 选中项 = 当前批次里被勾上的那些（按完整媒体身份筛选）
                val selectedImages = currentBatch.allCandidates()
                    .filter { intentIdentityOf(it) in intentBatchSelectedKeys }
                    .map { it.image }
                if (selectedImages.isEmpty()) return@IntentBatchContent

                intentBatchSaving = true
                scope.launch {
                    try {
                        // ---------- 1. 提交前复核 ----------
                        // 分析是过去某一刻做的：这期间用户可能滚到别的 App 改了图、
                        // 在别处标了长期保留、或权限被收窄。因此**按当前快照重查一遍**，
                        // 不拿分析那一刻的列表直接写回收站。
                        val latest = LocalImageRepository(context).loadAllData().allImages
                        val latestRecycled = recycleBinManager.loadRecycleBin()
                        val retainedNow = preferences.stagedCleanupRetainedIdentityKeys
                        val recycledNow = latestRecycled.map { cleanupIdentityKey(it) }.toSet()
                        val sourceKeysNow = preferences.stagedCleanupSourceKeys

                        val report = IntentCommitGuard.review(
                            selected = selectedImages,
                            currentLibrary = latest,
                            retainedIdentityKeys = retainedNow,
                            recycledIdentityKeys = recycledNow,
                            stagedSourceKeys = sourceKeysNow,
                            // 保护规则再看一遍：图没变但用户可能刚把它归到保护类场景
                            protectCandidate = { candidate ->
                                val verdict = intentBatch?.allCandidates()
                                    ?.firstOrNull { intentIdentityOf(it) == cleanupIdentityKey(candidate) }
                                    ?.verdict
                                verdict?.category?.isProtected == true
                            }
                        )

                        if (report.eligible.isEmpty()) {
                            Toast.makeText(
                                context,
                                "没有可移入的照片：" + report.describe(),
                                Toast.LENGTH_LONG
                            ).show()
                            intentBatchSelectedKeys = emptySet()
                            // 提交（即使全部被跳过）也改变了批次内容 → 重建，新批默认关闭
                            rebuildIntentBatch()
                            return@launch
                        }

                        // ---------- 2. 只写通过复核的那些 ----------
                        val addition = recycleBinManager.addBatchToRecycleBin(report.eligible)

                        // ---------- 3. 按本次实际新增的记录更新状态 ----------
                        val confirmed = addition.added
                        val confirmedKeys = confirmed.map { cleanupIdentityKey(it) }.toSet()
                        val notConfirmed = report.eligible.count { cleanupIdentityKey(it) !in confirmedKeys }
                        allImages = allImages.filterNot { cleanupIdentityKey(it) in confirmedKeys }
                        imagesForSorting = imagesForSorting.filterNot { cleanupIdentityKey(it) in confirmedKeys }
                        deletedImages = addition.records
                        preferences.totalReviewedCount += confirmed.size
                        preferences.totalDeletedCount += confirmed.size
                        recommendationEngine.invalidateSimilarGroupCache()

                        intentBatchSelectedKeys = emptySet()
                        recomputeStagedCleanupSelection()
                        // 提交后批次内容变了 → 重建；旧授权与在途请求一并作废
                        rebuildIntentBatch()

                        // ---------- 4. 部分成功分别说明 ----------
                        val skippedText = report.describe()
                        val message = listOfNotNull(
                            notConfirmed.takeIf { it > 0 }?.let { "$it 张未能移入回收站" },
                            skippedText.takeIf { it.isNotEmpty() }?.let { "跳过 ${report.skipped.size} 张：$it" }
                        ).joinToString("，")
                        if (confirmed.isNotEmpty() && currentScreen == AppScreen.INTENT_BATCH) {
                            pendingBatchUndos = pendingBatchUndos + BatchRecycleUndo(confirmed, AppScreen.INTENT_BATCH, report = message)
                        } else if (message.isNotEmpty()) {
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("TabulaApp", "Intent batch could not reach the recycle bin", e)
                        Toast.makeText(context, "未能保存回收站记录，照片未移入；请重试", Toast.LENGTH_LONG).show()
                    } finally {
                        intentBatchSaving = false
                    }
                }
            },
            onAnalyze = { rebuildIntentBatch() },
            onNavigateBack = {
                // 与系统返回手势走**同一个**流程
                leaveIntentBatch()
                currentScreen = AppScreen.STAGED_CLEANUP
            }
        )
        if (showIntentAssistKeyDialog) {
            IntentAssistKeyDialog(
                currentState = intentAssistKeyDescriptions[intentAssistService] ?: "未配置",
                // 逐服务：对话框里可以切着看/改，两个 Key 文件互不影响。
                service = intentAssistService,
                serviceStates = intentAssistKeyDescriptions,
                onSave = { key ->
                    if (!intentAssistKeyStore.write(key)) {
                        Toast.makeText(context, "Key 未能保存", Toast.LENGTH_SHORT).show()
                    }
                    intentAssistKeyRevision++
                },
                onSaveForService = { service, key ->
                    val store = intentAssistKeyStores.getValue(service)
                    if (!store.write(key)) {
                        Toast.makeText(context, "Key 未能保存", Toast.LENGTH_SHORT).show()
                    } else if (service != intentAssistService) {
                        // 存的是另一家的 Key：本批不受影响（当前实例仍用当前服务的 Key）
                        Toast.makeText(context, "${service.displayName} 的 Key 已保存", Toast.LENGTH_SHORT).show()
                    }
                    // 文件变化不在 Compose 的观察范围内：显式让界面重读状态
                    intentAssistKeyRevision++
                },
                onDelete = {
                    intentAssistKeyStore.delete()
                    intentAssistKeyRevision++
                    // 删掉当前服务的 Key = 本批没有可用凭据：吊销授权、按本地模式重建
                    rebuildIntentBatch()
                },
                onDeleteForService = { service ->
                    intentAssistKeyStores.getValue(service).delete()
                    intentAssistKeyRevision++
                    // 只有删的是当前服务的 Key 才影响本批；另一家的 Key 删了不影响外发
                    if (service == intentAssistService) {
                        rebuildIntentBatch()
                    }
                },
                onDismiss = { showIntentAssistKeyDialog = false },
                // 「使用此服务」：这是**切换外发对象**的唯一界面入口。
                // 与「编辑 Key」分开：编辑只影响文件，这里才改这一批用谁。
                // 走 selectIntentAssistService（吊销本批授权 + 作废在途 + 关闭态重算），
                // 因此切换后必须由用户主动重新开启。
                activeService = intentAssistService,
                onUseService = { service -> selectIntentAssistService(service) }
            )
        }
    }

    val burstSelectionContent: @Composable () -> Unit = {
        val group = burstSelectionGroup
        if (group == null) {
            // 没有有效组（例如进程被回收后恢复）——直接回卡片页，不留空白界面。
            LaunchedEffect(Unit) { currentScreen = AppScreen.DECK }
        } else {
            BurstSelectionScreen(
                batchUndo = { modifier ->
                    if (currentScreen == AppScreen.BURST_SELECTION) batchUndoContent(modifier)
                },
                group = group,
                keptIdentityKeys = burstKeptKeys,
                isCommitting = burstCommitting,
                onToggleKeep = { image ->
                    val key = image.mediaIdentityKey()
                    burstKeptKeys = if (key in burstKeptKeys) burstKeptKeys - key else burstKeptKeys + key
                },
                onKeepAll = { burstKeptKeys = BurstSelectionRules.keepAllKeys(group) },
                onCancel = {
                    // 取消 = 什么都不提交；组进度保持原样，用户下次还能再进。
                    burstSelectionGroup = null
                    burstOriginalGroup = null
                    burstKeptKeys = emptySet()
                    currentScreen = AppScreen.DECK
                },
                onConfirm = {
                    if (burstCommitting || batchUndoInFlight) return@BurstSelectionScreen
                    val resolved = BurstSelectionRules.resolve(group, burstKeptKeys)
                    val decision = (resolved as? BurstSelectionRules.Result.Valid)?.decision
                    // 审计修复（#87 二轮）：这里**不能**再用
                    // `decision.discardCandidates.isEmpty()` 当拒绝条件。
                    // "全部保留"（discardCandidates 为空）是用户的**有效决定**，
                    // 必须走「标记该组已处理 + 回卡片页 + 不写回收站」这条路；
                    // 旧写法在提交前就 return 了，下面 keptEverything 分支永远
                    // 不可达，等于用户点「都保留，结束这组」毫无反应。
                    // 真正无效的只有 resolve 失败（一张没勾 / 勾了组外的照片）。
                    if (decision == null) return@BurstSelectionScreen
                    burstCommitting = true
                    scope.launch {
                        try {
                            // 审计 #87：提交前**再次**按完整媒体身份核对——
                            // 候选必须此刻仍在图库、且不在回收站里。
                            val currentKeys = allImages.map { it.mediaIdentityKey() }.toSet()
                            val recycleKeys = deletedImages.map { it.mediaIdentityKey() }.toSet()
                            val toDiscard = BurstCommit.reverify(
                                discard = decision.discardCandidates,
                                libraryKeys = currentKeys,
                                recycleKeys = recycleKeys
                            )
                            if (toDiscard.isEmpty()) {
                                // 分两种情形，不能混为一谈：
                                // 1) 用户主动"全部保留"——有效决定，把这组标记为已处理
                                //    并回到卡片页，不写回收站；
                                // 2) 候选已被外部处理（照片消失/已在回收站）——不重复写入。
                                val keptEverything = decision.keepsEverything
                                if (keptEverything) {
                                    // 审计修复：全部保留也必须正确结束该组。
                                    // 用**原组**登记——此时显示组尚未裁剪，但统一走同一口径。
                                    recommendationEngine.markSimilarGroupProcessed(
                                        burstOriginalGroup ?: group
                                    )
                                    recommendationEngine.invalidateSimilarGroupCache()
                                    Toast.makeText(
                                        context,
                                        "这组都保留了",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        "这些照片已不在待整理列表，未重复写入",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                burstSelectionGroup = null
                                burstOriginalGroup = null
                                activeSimilarGroup = null
                                burstKeptKeys = emptySet()
                                currentScreen = AppScreen.DECK
                                return@launch
                            }

                            // 与卡片上滑删除同一条路径：写现有回收站，可恢复。
                            //
                            // #87 四轮：DEBUG 下可由测试钩子限制本次真实写入条数，
                            // 用来稳定制造"部分写入成功"。生产构建下 consumeLimit()
                            // 恒为 null，行为与之前完全一致。
                            val writeLimit = BurstCommitTestHook.consumeLimit()
                            val toWrite = if (writeLimit != null) {
                                toDiscard.take(writeLimit.coerceAtLeast(0))
                            } else {
                                toDiscard
                            }
                            val addition = recycleBinManager.addBatchToRecycleBin(toWrite)
                            val movedKeys = addition.added.map { it.mediaIdentityKey() }.toSet()
                            val result = BurstCommit.result(toDiscard, movedKeys)

                            // 部分成功：同步真实回收站状态，不声称整组未提交，
                            // 也不推进原组进度（失败的留待下次处理）。
                            if (result.succeeded.isNotEmpty()) {
                                val okKeys = result.succeeded.map { it.mediaIdentityKey() }.toSet()
                                allImages = allImages.filterNot { it.mediaIdentityKey() in okKeys }
                                imagesForSorting = imagesForSorting.filterNot { it.mediaIdentityKey() in okKeys }
                                deletedImages = addition.records
                                preferences.totalReviewedCount += result.succeeded.size
                                preferences.totalDeletedCount += result.succeeded.size
                            }
                            if (result.isComplete) {
                                // 全部写入成功且状态一致，才推进该组进度。
                                // 用**原组身份**登记：显示组已按入站结果裁剪过，
                                // 它的 id 与检测器产出的组不同，拿它登记等于登记了一个
                                // 从未存在过的组，原组永远不会进冷却。
                                recommendationEngine.markSimilarGroupProcessed(
                                    burstOriginalGroup ?: group
                                )
                                recommendationEngine.invalidateSimilarGroupCache()
                            }

                            val failureMessage = if (result.isComplete) "" else
                                "${result.failed.size} 张未能移入回收站，可重试"
                            if (addition.added.isNotEmpty()) {
                                pendingBatchUndos = pendingBatchUndos + BatchRecycleUndo(
                                    addition.added,
                                    AppScreen.BURST_SELECTION,
                                    (burstOriginalGroup ?: group).id,
                                    report = failureMessage
                                )
                            } else if (failureMessage.isNotEmpty()) {
                                Toast.makeText(context, failureMessage, Toast.LENGTH_LONG).show()
                            }
                            // 失败的部分留在组里；全部完成才离开选片页。
                            if (result.isComplete) {
                                burstSelectionGroup = null
                                burstOriginalGroup = null
                                burstKeptKeys = emptySet()
                                activeSimilarGroup = null
                                currentScreen = AppScreen.DECK
                            } else {
                                // 审计修复（#87 四轮）：按**实际入站身份**裁剪当前选片列表。
                                //
                                // 只把 burstKeptKeys 里的成功项去掉是不够的——选片页渲染的是
                                // group.images，成功项若不从组里移除，会继续显示在列表上，
                                // 用户以为没提交、再点确认就是重复操作。
                                //
                                // 保留项与失败项都要留下：保留项本来就不入站，失败项待重试。
                                // 原组身份另存于 burstOriginalGroup，完成时用它登记冷却，
                                // 因此这里裁剪不会影响进度登记口径。
                                val succeededKeys = result.succeeded
                                    .map { it.mediaIdentityKey() }
                                    .toSet()
                                val current = burstSelectionGroup
                                if (current != null) {
                                    val remaining = current.images.filterNot {
                                        it.mediaIdentityKey() in succeededKeys
                                    }
                                    if (remaining.isNotEmpty()) {
                                        burstSelectionGroup = current.copy(
                                            images = remaining,
                                            startTime = remaining.minOf { it.dateModified },
                                            endTime = remaining.maxOf { it.dateModified }
                                        )
                                    }
                                }
                                // 待重试的候选不再处于勾选态，用户重新点选即可
                                burstKeptKeys = burstKeptKeys - succeededKeys
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("TabulaApp", "Burst selection could not reach the recycle bin", e)
                            Toast.makeText(
                                context,
                                "未能保存回收站记录，已写入的部分保持原样；可重试",
                                Toast.LENGTH_LONG
                            ).show()
                        } finally {
                            burstCommitting = false
                        }
                    }
                }
            )
        }
    }


    val otherAlbumsContent: @Composable () -> Unit = {
        OtherAlbumsScreen(
            albums = otherAlbumsForNavigation,
            allImages = allImages,
            onAlbumClick = { album ->
                selectedAlbumId = album.id
                currentScreen = AppScreen.ALBUM_VIEW
            },
            onPinAlbum = { albumId ->
                // TODO: Implement pin album
                Toast.makeText(context, "固定相册: $albumId", Toast.LENGTH_SHORT).show()
            },
            onNavigateBack = { currentScreen = AppScreen.DECK }
        )
    }

    // ========== 引导流程处理（全屏，独立于主导航） ==========
    if (currentScreen == AppScreen.ONBOARDING) {
        OnboardingScreen(
            onSkipToMain = {
                preferences.hasCompletedOnboarding = true
                showOnboarding = false
                // 跳过个性化，直接进入须知页
                currentScreen = AppScreen.DISCLAIMER
            },
            onPersonalize = {
                currentScreen = AppScreen.PERSONALIZATION
            }
        )
        return
    }
    
    if (currentScreen == AppScreen.PERSONALIZATION) {
        PersonalizationScreen(
            preferences = preferences,
            onComplete = {
                preferences.hasCompletedOnboarding = true
                showOnboarding = false
                // 从 preferences 刷新所有个性化设置到 UI 状态
                currentTopBarMode = preferences.topBarDisplayMode
                cardStyleMode = preferences.cardStyleMode
                swipeStyle = preferences.swipeStyle
                showHdrBadges = preferences.showHdrBadges
                showMotionBadges = preferences.showMotionBadges
                quickActionEnabled = preferences.quickActionButtonEnabled
                // 刷新推荐模式
                recommendModeRefreshKey++
                // 个性化完成后进入须知页
                currentScreen = AppScreen.DISCLAIMER
            },
            onSkip = {
                preferences.hasCompletedOnboarding = true
                showOnboarding = false
                // 从 preferences 刷新所有个性化设置到 UI 状态
                currentTopBarMode = preferences.topBarDisplayMode
                cardStyleMode = preferences.cardStyleMode
                swipeStyle = preferences.swipeStyle
                showHdrBadges = preferences.showHdrBadges
                showMotionBadges = preferences.showMotionBadges
                quickActionEnabled = preferences.quickActionButtonEnabled
                // 刷新推荐模式
                recommendModeRefreshKey++
                // 跳过个性化也进入须知页
                currentScreen = AppScreen.DISCLAIMER
            }
        )
        return
    }

    // ========== 使用前须知处理（全屏，个性化设置之后、进入主界面之前） ==========
    if (currentScreen == AppScreen.DISCLAIMER) {
        DisclaimerScreen(
            onAcknowledged = {
                preferences.hasAcknowledgedDisclaimer = true
                showDisclaimer = false
                currentScreen = AppScreen.DECK
            }
        )
        return
    }

    // 根据当前屏幕决定 前景 和 背景
    val (backgroundContent, foregroundContent) = when (currentScreen) {
        AppScreen.DISCLAIMER -> deckContent to null  // 不会到达这里，但需要完整的 when
        AppScreen.ONBOARDING -> deckContent to null  // 不会到达这里
        AppScreen.PERSONALIZATION -> deckContent to null  // 不会到达这里
        AppScreen.DECK -> deckContent to null
        AppScreen.RECYCLE_BIN -> deckContent to recycleBinContent
        AppScreen.SETTINGS -> deckContent to settingsContent
        AppScreen.ABOUT -> settingsContent to aboutContent
        AppScreen.STATISTICS -> settingsContent to statisticsContent
        AppScreen.ALBUM_VIEW -> deckContent to albumViewContent
        AppScreen.SYSTEM_ALBUM_VIEW -> deckContent to systemAlbumViewContent
        AppScreen.OTHER_ALBUMS -> deckContent to otherAlbumsContent
        AppScreen.BURST_SELECTION -> deckContent to burstSelectionContent
        AppScreen.STAGED_CLEANUP -> deckContent to stagedCleanupContent
        AppScreen.RELAY_REVIEW -> settingsContent to relayReviewContent
        AppScreen.INTENT_BATCH -> deckContent to intentBatchContent
        AppScreen.VIBRATION_SOUND -> settingsContent to vibrationSoundContent
        AppScreen.DISPLAY_SETTINGS -> settingsContent to displaySettingsContent
        AppScreen.LAB -> settingsContent to labContent
        AppScreen.REMINDER_SETTINGS -> settingsContent to reminderSettingsContent
        AppScreen.HIDDEN_ALBUMS -> settingsContent to hiddenAlbumsContent
        AppScreen.PRIVACY_POLICY -> settingsContent to privacyPolicyContent
        AppScreen.TUTORIAL -> settingsContent to tutorialContent
    }
    
    // ========== 渲染容器 ==========
    // 液态玻璃效果现在由 AGSL 着色器在各个组件内部处理
    // 不再需要全局的 Backdrop Provider
    Box(modifier = Modifier.fillMaxSize()) {
        PredictiveBackContainer(
            currentScreen = currentScreen,
            onNavigateBack = {
                // 系统返回 / 返回手势：离开凑一批时先撤销授权并作废在途结果，
                // 与页面返回按钮同一条路径（否则手势返回会留下仍在跑的请求）。
                if (handleIntentBatchSystemBack()) return@PredictiveBackContainer
                currentScreen = when (currentScreen) {
                    AppScreen.DISCLAIMER -> AppScreen.DISCLAIMER  // 须知页不支持返回
                    AppScreen.ONBOARDING -> AppScreen.ONBOARDING  // 引导页不支持返回
                    AppScreen.PERSONALIZATION -> AppScreen.ONBOARDING  // 个性化返回到引导页
                    AppScreen.RECYCLE_BIN -> AppScreen.DECK
                    AppScreen.SETTINGS -> AppScreen.DECK
                    AppScreen.ABOUT -> AppScreen.SETTINGS
                    AppScreen.STATISTICS -> AppScreen.SETTINGS
                    AppScreen.ALBUM_VIEW -> AppScreen.DECK
                    AppScreen.SYSTEM_ALBUM_VIEW -> AppScreen.DECK
                    AppScreen.OTHER_ALBUMS -> AppScreen.DECK
                    AppScreen.BURST_SELECTION -> AppScreen.DECK
                    AppScreen.STAGED_CLEANUP -> AppScreen.DECK
                    AppScreen.RELAY_REVIEW -> AppScreen.SETTINGS
                    // 返回暂存清理：这一批的范围就是从那里来的，用户要能一眼看到原列表
                    AppScreen.INTENT_BATCH -> AppScreen.STAGED_CLEANUP
                    AppScreen.VIBRATION_SOUND -> AppScreen.SETTINGS
                    AppScreen.DISPLAY_SETTINGS -> AppScreen.SETTINGS
                    AppScreen.LAB -> AppScreen.SETTINGS
                    AppScreen.REMINDER_SETTINGS -> AppScreen.SETTINGS
                    AppScreen.HIDDEN_ALBUMS -> AppScreen.SETTINGS
                    AppScreen.PRIVACY_POLICY -> AppScreen.SETTINGS
                    AppScreen.TUTORIAL -> AppScreen.SETTINGS
                    AppScreen.DECK -> AppScreen.DECK
                }
            },
            backgroundContent = backgroundContent,
            foregroundContent = foregroundContent ?: {}
        )
        
        // ========== 原图删除提醒对话框 ==========
        if (showSourceImageDeletionDialog) {
            SourceImageDeletionDialog(
                imageCount = dialogSourceImageUris.size,
                onConfirm = {
                    // 只允许删除用户在弹窗中确认的候选；新出现的候选留待下次确认。
                    val confirmedUris = dialogSourceImageUris.toSet()
                    showSourceImageDeletionDialog = false
                    scope.launch {
                        // 仍对候选逐项深度复核，并限制到弹窗确认时的集合。
                        val cleanableResult = albumManager.getReverifiedCleanableUrisForAllAlbums()
                        val urisToDelete = cleanableResult.sourceUris.filter { it in confirmedUris }
                        pendingSourceImageUris = urisToDelete
                        unverifiedSourceImageCount = cleanableResult.unverifiedSourceCount
                        if (urisToDelete.isEmpty()) {
                            if (cleanableResult.unverifiedSourceCount > 0) {
                                Toast.makeText(
                                    context,
                                    "副本尚未验证，已保留 ${cleanableResult.unverifiedSourceCount} 张原图",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(context, "没有需要清理的原图", Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }

                        pendingSourceImageUris = urisToDelete
                        Toast.makeText(context, "正在删除 ${urisToDelete.size} 张原图...", Toast.LENGTH_SHORT).show()
                        performDeleteUris(urisToDelete) { summary ->
                            pendingSourceImageUris = urisToDelete.filter { it in summary.remaining }
                            Toast.makeText(context, mediaDeletionMessage(summary, "原图"), Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onDismiss = {
                    showSourceImageDeletionDialog = false
                },
                isDarkTheme = LocalIsDarkTheme.current
            )
        }
    }
}

/**
 * 原图删除提醒对话框
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SourceImageDeletionDialog(
    imageCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDarkTheme: Boolean
) {
    val containerColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color.White
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val secondaryTextColor = if (isDarkTheme) Color(0xFFAEAEB2) else Color(0xFF3C3C43)
    val accentColor = Color(0xFF007AFF)
    val warningColor = Color(0xFFFF9F0A)
    val context = LocalContext.current
    
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = containerColor,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = warningColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "删除原位置的照片",
                    color = textColor,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column {
                Text(
                    text = "检测到有 $imageCount 张照片已复制到图集，原位置的照片可以删除了。",
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(warningColor.copy(alpha = 0.1f))
                        .padding(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = warningColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "只会删除已确认存在副本的原图；副本未验证的原图会保留。",
                            color = textColor.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    HapticFeedback.mediumTap(context)
                    onConfirm()
                }
            ) {
                Text(
                    text = "删除原图",
                    color = Color(0xFFFF3B30),  // 红色
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    HapticFeedback.lightTap(context)
                    onDismiss()
                }
            ) {
                Text(
                    text = "稍后处理",
                    color = secondaryTextColor
                )
            }
        }
    )
}

/**
 * 权限请求界面 - 黑猫风格
 */
@Composable
private fun PermissionRequestScreen(
    onRequestPermission: () -> Unit
) {
    val isDarkTheme = LocalIsDarkTheme.current
    val backgroundColor = if (isDarkTheme) TabulaColors.CatBlack else TabulaColors.WarmWhite
    val textColor = if (isDarkTheme) Color.White else TabulaColors.CatBlack
    val buttonBgColor = if (isDarkTheme) TabulaColors.EyeGold else TabulaColors.CatBlack
    val buttonTextColor = if (isDarkTheme) TabulaColors.CatBlack else Color.White

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            // Logo
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "Tabula Logo",
                modifier = Modifier.size(150.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "欢迎使用 Tabula",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "高效整理你的照片库",
                fontSize = 16.sp,
                color = textColor.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = onRequestPermission,
                modifier = Modifier
                    .size(width = 200.dp, height = 56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonBgColor,
                    contentColor = buttonTextColor
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = "授权访问照片",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}


