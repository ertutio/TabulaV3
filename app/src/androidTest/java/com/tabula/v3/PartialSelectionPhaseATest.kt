package com.tabula.v3

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.SwipeStyle
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.FullRecoveryStateContract.flushCleanupPrefs
import com.tabula.v3.FullRecoveryStateContract.rebuildRow
import com.tabula.v3.FullRecoveryStateContract.seedCleanupGroup
import com.tabula.v3.PartialSelectionStateContract.COLOR_A1
import com.tabula.v3.PartialSelectionStateContract.COLOR_A2
import com.tabula.v3.PartialSelectionStateContract.COLOR_B1
import com.tabula.v3.PartialSelectionStateContract.COLOR_B2
import com.tabula.v3.PartialSelectionStateContract.COLOR_SENTINEL
import com.tabula.v3.PartialSelectionStateContract.seedPhoto
import com.tabula.v3.PartialSelectionStateContract.visibleDisplayNames
import com.tabula.v3.PartialSelectionStateContract.writeExpectedState
import com.tabula.v3.permission.MediaPermissionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * PARTIAL 选择集 A→B 两阶段验证——阶段A（包B：B1）。
 *
 * 真实系统机制建立 PARTIAL 选择集 A（无 pm 权限夹具、无生产改动）：
 * 1. 无授权状态先种子 5 张合成媒体（API 29+ 自有媒体贡献不需要存储权限，
 *    官方文档载明）：哨兵 S（最旧）、A1/A2、B1/B2（最新）；DATE_TAKEN 显式
 *    单调递增且发布顺序同向，选择器网格按时间倒序 = [B2, B1, A2, A1, S]。
 * 2. 应用 onboarding 门控按钮 → 系统权限对话框 "Select photos" → 照片选择器
 *    点选 A 两格（网格索引 2、3）→ 确认 → READ_MEDIA_VISUAL_USER_SELECTED
 *    真实授权。
 * 3. 级别断言：PARTIAL 已授、FULL（READ_MEDIA_IMAGES）未授（策略三态）。
 * 4. 地面真值：应用内 MediaStore 查询恰等于 {A1, A2}（哨兵/B 在集合外）。
 * 5. 持久化清理组（A 成员）+ 断点 1 + flush（复用 FULL 恢复设施同款写入口）。
 * 6. 真实 UI 一致性：deck 当前卡 ∈ A、查看器 overlay 身份匹配、集合外
 *    （哨兵/B1）不可见；月份专清经真实 sheet 进入，成员 = A。
 * 7. 预期状态写 files/（阶段B 新进程读回断言）。
 *
 * 已知边界（如实登记）：授权广播时序经截止时间轮询吸收；对话框/选择器文案
 * 按候选列表匹配（API 差异容忍），失败留存截图+辅助树 dump（CI artifact）。
 * 应用进程在本阶段保持存活（对话框流程）；夹具与授权状态由阶段B 继承，
 * 宿主编排负责跨阶段 force-stop（不 pm clear）。
 */
@RunWith(AndroidJUnit4::class)
class PartialSelectionPhaseATest {
    private val TAG = "PartialSelectionA"
    private val ui get() = PartialSelectionUi
    private val ctx get() = ui.ctx

    private data class Seed(val uri: Uri, val name: String)

    private lateinit var sentinel: Seed
    private lateinit var a1: Seed
    private lateinit var a2: Seed
    private lateinit var b1: Seed
    private lateinit var b2: Seed

    @Before fun setup() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        check(Build.VERSION.SDK_INT >= 34) { "PARTIAL selection semantics require API 34+" }
        assertEquals(
            "阶段A前置：宿主必须未授予任何媒体权限（pm clear 后裸状态）",
            PackageManager.PERMISSION_DENIED,
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
        )
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        )
        AppPreferences(ctx).apply {
            hasCompletedOnboarding = true
            hasAcknowledgedDisclaimer = true
            swipeStyle = SwipeStyle.SHUFFLE
        }
        AppPreferences.getInstance(ctx).clearAllAlbumCleanupState()
        Unit
    }

    @Test
    fun partialSelectionAEstablishedViaSystemDialogAndPicker() = runBlocking<Unit> {
        // ---- 种子（无授权；DATE_TAKEN 单调，发布顺序同向 → 网格确定性）----
        // 夹具卫生：相邻种子间隔 >1s，使 DATE_ADDED 秒级派生也单调——选择器
        // 无论按 DATE_TAKEN 还是 DATE_ADDED 排序，网格顺序都确定
        // （= 插入序的倒序 = [B2, B1, A2, A1, S]）。
        val now = System.currentTimeMillis()
        fun take(offsetSec: Long) = now - TimeUnit.SECONDS.toMillis(offsetSec)
        sentinel = seed(seedPhoto(ctx, "sentinel", COLOR_SENTINEL, take(2000)))
        SystemClock.sleep(1100)
        a1 = seed(seedPhoto(ctx, "a1", COLOR_A1, take(1500)))
        SystemClock.sleep(1100)
        a2 = seed(seedPhoto(ctx, "a2", COLOR_A2, take(1000)))
        SystemClock.sleep(1100)
        b1 = seed(seedPhoto(ctx, "b1", COLOR_B1, take(500)))
        SystemClock.sleep(1100)
        b2 = seed(seedPhoto(ctx, "b2", COLOR_B2, take(100)))
        Log.i(TAG, "seeded sentinel=${sentinel.name} a1=${a1.name} a2=${a2.name} " +
            "b1=${b1.name} b2=${b2.name}")

        // ---- 无授权级别断言（策略三态）----
        assertEquals(
            MediaPermissionPolicy.MediaAccessLevel.NONE,
            MediaPermissionPolicy.mediaAccessLevel(Build.VERSION.SDK_INT) { permission ->
                ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
        )

        // ---- 真实系统机制：门控 → 对话框 Select photos → 选择器选 A ----
        ui.launch()
        ui.waitFor("授权访问照片")
        ui.tap("授权访问照片")
        ui.awaitAny(ui.DIALOG_CANDIDATES, tag = "permission-dialog", timeoutMs = 20000)
        ui.tapAny(ui.SELECT_PHOTOS_CANDIDATES, tag = "select-photos")

        ui.collectGridCells(minCount = 5)
        // 网格顺序 = [B2, B1, A2, A1, S]（DATE_TAKEN 倒序）；A = 索引 2、3
        ui.driveCheckedCells(expected = setOf(2, 3))
        val confirmLabel = ui.confirmSelection()
        Log.i(TAG, "picker confirmed via $confirmLabel; awaiting grant + ground truth")

        // ---- 授权轮询：PARTIAL 已授、FULL 未授 ----
        val granted = awaitDeadline("partial-grant") {
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
                PackageManager.PERMISSION_GRANTED &&
                ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_DENIED
        }
        assertTrue("确认后必须授予 READ_MEDIA_VISUAL_USER_SELECTED 且不得授予 FULL", granted)
        assertEquals(
            MediaPermissionPolicy.MediaAccessLevel.PARTIAL,
            MediaPermissionPolicy.mediaAccessLevel(Build.VERSION.SDK_INT) { permission ->
                ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
        )

        // ---- 地面真值：应用可见集合恰为 {A1, A2}（哨兵/B 集合外）----
        val expectedA = setOf(a1.name, a2.name)
        val truth = awaitDeadline("ground-truth-A") { visibleDisplayNames(ctx) == expectedA }
        assertTrue(
            "应用可见集合必须恰为选择集 A（实际=${visibleDisplayNames(ctx)} 预期=$expectedA）",
            truth
        )

        // ---- 持久清理组 + 断点（A 成员；复用 FULL 恢复设施写入口）----
        val rows = listOf(a1, a2).map { rebuildRow(ctx, it.name) }
        assertEquals(expectedA, rows.map { it.displayName }.toSet())
        val albumId = com.tabula.v3.ui.components.buildMonthInfos(rows).single().cleanupAlbumId
        val groupId = seedCleanupGroup(ctx, rows)
        val engine = FullRecoveryStateContract.cleanupEngine(ctx)
        val batch = requireNotNull(engine.getNextBatch(albumId, rows)) {
            "cleanup engine returned no batch for the seeded group"
        }
        assertEquals(listOf(groupId), batch.groupIds)
        engine.saveCheckpoint(listOf(groupId), 1)
        val resumed = requireNotNull(
            FullRecoveryStateContract.cleanupEngine(ctx).getCheckpointBatch(albumId, rows)
        ) { "checkpoint did not survive an in-process engine recreation" }
        assertEquals(1, resumed.second)
        assertTrue(
            "flush sentinel commit() 必须成功（同步落盘完成信号）",
            flushCleanupPrefs(ctx)
        )

        // ---- 真实 UI 一致性：deck 当前卡 ∈ A；查看器 overlay 身份匹配 ----
        ui.launch()
        ui.waitFor(ui.DECK_MARKER)
        val cardName = ui.openViewerFromLargest(listOf(a1.name, a2.name))
        val cardRow = rebuildRow(ctx, cardName)
        ui.awaitViewerOverlay(cardRow.mediaIdentityKey())
        ui.backToDeck()
        // 集合外媒体不得出现在 deck（哨兵 + 未选中的 B）
        ui.awaitGone(sentinel.name, timeoutMs = 5000)
        ui.awaitGone(b1.name, timeoutMs = 5000)

        // ---- 月份专清（真实 sheet）：月份成员 = A ----
        // （月份清理视图无 BACK 出口——经"切换回全局整理"；本流程到此结束，
        //   不再返回 deck，宿主随后 force-stop）
        val monthName = com.tabula.v3.ui.components.buildMonthInfos(rows).single().displayName
        ui.tap("选择图集")
        ui.tap("月份")
        ui.waitFor(monthName)
        ui.tap(monthName)
        ui.tap("开始整理")
        ui.waitFor(a1.name)
        ui.waitFor(a2.name)
        ui.awaitGone(sentinel.name, timeoutMs = 5000)

        // ---- 预期状态持久化（阶段B 新进程读回）----
        writeExpectedState(
            ctx,
            albumId = albumId,
            groupId = groupId,
            aDisplayNames = listOf(a1.name, a2.name),
            aMembers = rows.map { it.mediaIdentityKey() },
            bDisplayNames = listOf(b1.name, b2.name),
            sentinelName = sentinel.name,
            checkpointIndex = 1
        )
        Log.i(TAG, "phase A complete: PARTIAL(A) established, group=$groupId checkpoint=1")
    }

    private fun seed(pair: Pair<Uri, String>): Seed = Seed(pair.first, pair.second)

    private fun awaitDeadline(label: String, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(150)
        }
        ui.dumpTree("deadline-$label")
        ui.saveScreenshot("deadline-$label")
        return false
    }
}
