package com.tabula.v3

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.SwipeStyle
import com.tabula.v3.data.repository.RecycleBinManager
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.FullPartialFullStateContract.assertShellRowExists
import com.tabula.v3.FullPartialFullStateContract.visibleDisplayNameList
import com.tabula.v3.FullRecoveryStateContract.flushCleanupPrefs
import com.tabula.v3.FullRecoveryStateContract.rebuildRow
import com.tabula.v3.FullRecoveryStateContract.seedCleanupGroup
import com.tabula.v3.permission.MediaPermissionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T07 FULL→PARTIAL→FULL 三阶段验证——Phase FULL 建立（判据 1–3）。
 *
 * 前置（宿主编排）：pm clear → pm grant READ_MEDIA_IMAGES（VISUAL_USER_SELECTED
 * 无授权，pm clear 天然清理 + 宿主兜底 revoke）→ 6 张 shell 身份夹具已 push+扫描
 * （fpf-s1/n1/g1/g2/b1/b2，全部 2026-11）。
 *
 * 断言链（判据逐条）：
 * 1. 地面真值：应用可见恰 = {g1,g2,n1,b1,b2}（无重复无缺失），s1 仅作哨兵存在性
 *    核对（FULL 下应用可见全部媒体，判据括号"s1 也在"；不计入集合断言——集合差
 *    断言 (可见−成员)=={s1} + s1 在场）；shell 双面核对 6 行逐行在库。
 * 2. 真实入口建立 G 组清理：生产写入口 seedCleanupGroup（g1,g2 成组，持久键序
 *    [g1,g2]）→ 引擎批次 = 组 [g1,g2]。
 * 3. 真实 UI 处理 g1（部分处理）：月份专清真实入口 → deck 当前卡 g1 → 上滑 →
 *    截止轮询生产撤销窗口提交（回收站=[g1键] + acknowledged={g1键}）→ deck
 *    当前卡 = g2、g1 节点消失。
 * 4. 断点=组内指向 g2 待处理：经真实 sheet 退出清理模式（onSwitchToGlobal 生产
 *    路径）后，生产入口 engine.saveCheckpoint([groupId],1) 钉住语义（deck 退出
 *    落 (group,0) 是变异批次帧的索引歧义——引擎 acknowledged 成员安全重启分支
 *    即为此设计，见 coord/notes-B2.md §1.2）→ flush commit() 信号 → prefs 读回
 *    核对（判据 3：visible 全集、组键、断点索引、已处理集合）。
 * 5. 期望状态落盘 FullRecoveryStateContract 形态（扩展成员全集/已处理集合字段）。
 *
 * 结束即结束——宿主随后 pm revoke（系统强停）→ Phase2。
 */
@RunWith(AndroidJUnit4::class)
class FullPartialFullPhase1Test {
    private val TAG = "FullPartialFullP1"
    private val ui get() = PartialSelectionUi
    private val ctx get() = ui.ctx

    @Before fun setup() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        assertEquals(
            "Phase FULL 前置：READ_MEDIA_IMAGES 必须已授（宿主 pm grant）",
            PackageManager.PERMISSION_GRANTED,
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
        )
        assertEquals(
            "Phase FULL 前置：VISUAL_USER_SELECTED 必须无授权（pm clear 已清理）",
            PackageManager.PERMISSION_DENIED,
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        )
        assertEquals(
            MediaPermissionPolicy.MediaAccessLevel.FULL,
            MediaPermissionPolicy.mediaAccessLevel(Build.VERSION.SDK_INT) { permission ->
                ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
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
    fun fullAccessEstablishesGroupProcessesFirstMemberAndPersistsProgress() = runBlocking<Unit> {
        val memberSet = FullPartialFullStateContract.MEMBER_NAMES.toSet()
        val sentinel = FullPartialFullStateContract.SENTINEL_NAME

        // ---- 判据 1：地面真值（无重复无缺失 + 哨兵存在性核对）+ shell 双面核对 ----
        val visibleList = visibleDisplayNameList(ctx)
        val visibleSet = visibleList.toSet()
        assertTrue(
            "FULL 下应用可见集合不得有重复行（list=${visibleList.size} set=${visibleSet.size}）",
            visibleList.size == visibleSet.size
        )
        assertEquals(
            "FULL 地面真值：可见集合恰为 5 成员（无多余无缺失）；实际=$visibleSet",
            memberSet, visibleSet - sentinel
        )
        assertTrue("哨兵 s1 必须在库可见（存在性核对）", sentinel in visibleSet)
        for (name in FullPartialFullStateContract.ALL_NAMES) {
            assertShellRowExists(name) { command -> ui.shellScript(command) }
        }
        Log.i(TAG, "ground truth FULL verified: visible=$visibleSet")

        // ---- 判据 2：真实入口建立 G 组清理（生产写入口种子，g1,g2 成组）----
        val rows = FullPartialFullStateContract.GROUP_NAMES.map { rebuildRow(ctx, it) }
        assertEquals(
            FullPartialFullStateContract.GROUP_NAMES, rows.map { it.displayName }
        )
        val groupKeys = rows.map { it.mediaIdentityKey() }
        val albumId = com.tabula.v3.ui.components.buildMonthInfos(rows).single().cleanupAlbumId
        val monthName = com.tabula.v3.ui.components.buildMonthInfos(rows).single().displayName
        val groupId = seedCleanupGroup(ctx, rows)
        val preferences = AppPreferences.getInstance(ctx)
        assertEquals(
            "组目录必须恰含 G 组", setOf(groupId), preferences.getAlbumGroupIds(albumId)
        )
        assertEquals(
            "组成员键必须为持久键序 [g1,g2]", groupKeys,
            preferences.getGroupImageIds(albumId, groupId)
        )

        // 引擎批次断言：批次 = 组 [g1,g2]（顺序 = 持久键序 → 断点索引语义确定）
        val engine = FullRecoveryStateContract.cleanupEngine(ctx)
        val batch = requireNotNull(engine.getNextBatch(albumId, rows)) {
            "cleanup engine returned no batch for the seeded group"
        }
        assertEquals(listOf(groupId), batch.groupIds)
        assertEquals(rows, batch.images)

        // ---- 判据 2/3：真实 UI 处理 g1（月份专清入口 → 上滑 → 撤销窗口提交）----
        FullPartialFullUi.launchApp()
        ui.waitFor(ui.DECK_MARKER)
        FullPartialFullUi.startMonth(monthName)
        FullPartialFullUi.waitForExact(FullPartialFullStateContract.G1_NAME)
        Log.i(TAG, "deck current card = g1, swiping to process")
        FullPartialFullUi.removeCard(FullPartialFullStateContract.G1_NAME)

        // deck 移除是同步的：下一张（g2）滑入当前位，g1 节点消失（上滑动画期内
        // 合法可见——推进语义 awaitDismissed 允许有界过渡，消失后 1.5s 观察窗）
        FullPartialFullUi.waitForExact(FullPartialFullStateContract.G2_NAME)
        FullPartialFullUi.awaitDismissed(
            FullPartialFullStateContract.G1_NAME, tag = "g1-removed-from-deck"
        )

        // 生产撤销窗口提交（截止轮询，无固定 sleep）：回收站 + acknowledged 记录"已处理 g1"
        val trash = RecycleBinManager.getInstance(ctx)
        val g1Key = rows.first { it.displayName == FullPartialFullStateContract.G1_NAME }
            .mediaIdentityKey()
        FullPartialFullUi.await("g1-removal-committed") {
            runBlocking { trash.loadRecycleBin().map { it.mediaIdentityKey() } } ==
                listOf(g1Key) &&
                preferences.getAlbumAcknowledgedCleanupMembers(albumId) == setOf(g1Key)
        }
        Log.i(TAG, "g1 processed: recycle bin + acknowledged committed")

        // ---- 判据 2：断点=组内指向 g2 待处理 ----
        // 先经真实 sheet 退出清理模式（onSwitchToGlobal 生产路径；deck 退出自保存
        // 落 (group,0) = 变异批次帧索引歧义），再以生产入口 saveCheckpoint 钉住
        // "索引 1 = g2 待处理"（持久键序），并 flush 同步落盘。
        FullPartialFullUi.exitCleanupViaSheet()
        engine.saveCheckpoint(listOf(groupId), 1)
        assertTrue(
            "flush sentinel commit() 必须成功（同步落盘完成信号）",
            flushCleanupPrefs(ctx)
        )
        assertEquals(
            "断点必须为 (G组, 1)（组内指向 g2 待处理）",
            listOf(groupId) to 1,
            preferences.getAlbumCleanupCheckpoint(albumId)
        )
        assertEquals("已处理记录必须恰为 g1", setOf(g1Key),
            preferences.getAlbumAcknowledgedCleanupMembers(albumId))
        assertEquals(
            "回收站必须恰含 g1",
            listOf(g1Key), runBlocking { trash.loadRecycleBin().map { it.mediaIdentityKey() } }
        )
        assertEquals("组键必须原样", groupKeys, preferences.getGroupImageIds(albumId, groupId))
        assertTrue(
            "组不得被标记永久完成（g2 未处理）",
            preferences.getAlbumPermanentlyProcessedGroups(albumId).isEmpty()
        )
        assertTrue("月份专清不得被标记完成", albumId !in preferences.getCompletedCleanupAlbumIds())

        // ---- 阶段末双面核对（app 查询 + shell）+ 哨兵存活 ----
        val visibleAfter = visibleDisplayNameList(ctx).toSet()
        assertEquals("阶段末可见集合不变", memberSet + sentinel, visibleAfter)
        for (name in FullPartialFullStateContract.ALL_NAMES) {
            assertShellRowExists(name) { command -> ui.shellScript(command) }
        }

        // ---- 判据 3：期望状态落盘（FullRecoveryStateContract 形态 + 扩展）----
        FullPartialFullStateContract.writeExpectedState(
            ctx, albumId = albumId, groupId = groupId, checkpointIndex = 1,
            visibleFullSet = visibleAfter
        )
        assertTrue(FullPartialFullStateContract.stateFile(ctx).length() > 0)
        Log.i(TAG, "phase FULL complete: group=$groupId checkpoint=1 processed=[g1]")
    }
}
