package com.tabula.v3

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.AlbumManager
import com.tabula.v3.data.repository.FileOperationManager
import com.tabula.v3.data.repository.LocalImageRepository
import com.tabula.v3.data.repository.RecycleBinManager
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.FullPartialFullStateContract.assertShellRowExists
import com.tabula.v3.FullPartialFullStateContract.shellDeleteFixture
import com.tabula.v3.FullPartialFullStateContract.visibleDisplayNameList
import com.tabula.v3.FullRecoveryStateContract.rebuildRow
import com.tabula.v3.permission.MediaPermissionPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * T07 FULL→PARTIAL→FULL 三阶段验证——Phase FULL 恢复（判据 8–10）。
 *
 * 前置（宿主编排，#52 口径）：pm grant READ_MEDIA_IMAGES（grant 不杀进程）→
 * am force-stop（显式重启）→ 本阶段新 instrumentation 进程；应用数据保留。
 *
 * 断言链（判据逐条）：
 * 8. 地面真值：可见恰 = {g1,g2,n1,b1,b2}+哨兵存在性（无重复：list==set；无缺失：
 *    shell 6 行核对）；级别=FULL。
 * 9. 已处理保持（驱动 UI 前的记录读回）：断点 (group,1) 原样（PARTIAL 期间只读
 *    未触碰）、acknowledged={g1键}、回收站=[g1键]、组键 [g1,g2] 无重复无丢失、未
 *    永久完成；引擎续接：真实月行（6 行）下 getNextBatch 批次恰=[g2]——g1 不重新
 *    进入待处理、g2 依进度恢复为待处理。
 * 10. 旧快照晚到不覆盖：以真实生产链 loadAndApplyMediaSnapshot 重演 PR50
 *     MediaSnapshotLoadRaceTest.lateReturningOldReadReturnsButNeverCommits 的晚到
 *     形态——旧扫描滞留在 PARTIAL 时代投影 {b1,b2}（底层读取 NonCancellable 闸门，
 *     晚返回形态成立）、新扫描走默认真实生产 load 并提交；断言：旧读取确实返回、
 *     提交只归新扫描、新扫描 apply 到的投影 = 当前真实全池投影（6 行 − 回收站 g1
 *     = 5 行，真实数据）、取消不落 error 态。
 * 9'. UI 续接：真实月份入口 → 生产安全重启路径（getCheckpointBatch 遇 acknowledged
 *     成员清旧断点→按组目录+acknowledged 重算）→ deck 当前卡 = g2、g1 不在 deck。
 * 10'. 最终一致性：可见集/acknowledged/回收站/组键/月份归属与第 9 条一致（最终
 *      UI/查询状态不受晚到旧快照影响）。
 *
 * 套件收尾（@After）：按名+路径 shell 精确删除全部 6 夹具并核对消失（#57 PhaseB
 * 收尾同款），不残留到后续 probe 段。
 */
@RunWith(AndroidJUnit4::class)
class FullPartialFullPhase3Test {
    private val TAG = "FullPartialFullP3"
    private val ui get() = PartialSelectionUi
    private val ctx get() = ui.ctx

    @Before fun setup() {
        // 依赖门（coord/notes-B2.md §7.3）：Phase2 未成功落盘（含 phase2Pid）则本阶段
        // 拒绝执行——probe 侧配合依赖跳过，不再把级联失败误判为本阶段独立红
        FullPartialFullStateContract.requireDependency(ctx, "phase2Pid", "Phase 2")
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        assertEquals(
            "Phase 恢复前置：FULL 必须已恢复",
            PackageManager.PERMISSION_GRANTED,
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
        )
        assertEquals(
            MediaPermissionPolicy.MediaAccessLevel.FULL,
            MediaPermissionPolicy.mediaAccessLevel(Build.VERSION.SDK_INT) { permission ->
                ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    /** 套件最后阶段：按名+路径精确删除全部 6 个合成夹具并核对消失。 */
    @After fun cleanup() {
        val stateFile = FullPartialFullStateContract.stateFile(ctx)
        if (!stateFile.exists()) return
        for (name in FullPartialFullStateContract.ALL_NAMES) {
            val gone = shellDeleteFixture(name) { command -> ui.shellScript(command) }
            Log.i(TAG, "cleanup $name gone=$gone")
            assertTrue("夹具删除后必须消失：$name", gone)
        }
        ui.shellScript("rm -rf ${FullPartialFullStateContract.FIXTURE_ABS_DIR}")
    }

    @Test
    fun fullRestoreKeepsProcessedMemberResumesCheckpointAndIgnoresLateSnapshot() = runBlocking<Unit> {
        val expected = FullPartialFullStateContract.readExpectedState(ctx)
        assertNotEquals(
            "Phase3 必须运行在 Phase2 之后的新进程（宿主 force-stop）",
            expected.getInt("phase2Pid"), android.os.Process.myPid()
        )
        val albumId = expected.getString("albumId")
        val groupId = expected.getString("groupId")
        val checkpointIndex = expected.getInt("checkpointIndex")
        val groupMembers = expected.getJSONArray("members").let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        val memberNames = expected.getJSONArray("memberDisplayNames").let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        val sentinelName = expected.getString("sentinelName")
        val g1Key = expected.getJSONArray("processedMembers").getString(0)
        val g2Key = groupMembers[1]
        val preferences = AppPreferences.getInstance(ctx)

        // ---- 判据 8：地面真值（无重复无缺失 + 哨兵存在性）+ shell 双面核对 ----
        val visibleList = visibleDisplayNameList(ctx)
        val visibleSet = visibleList.toSet()
        assertTrue(
            "恢复后可见集合不得有重复行（list=${visibleList.size} set=${visibleSet.size}）",
            visibleList.size == visibleSet.size
        )
        assertEquals(
            "FULL 恢复地面真值：可见集合恰为 5 成员（无重复无缺失）；实际=$visibleSet",
            FullPartialFullStateContract.MEMBER_NAMES.toSet(), visibleSet - sentinelName
        )
        assertTrue("哨兵存在性核对", sentinelName in visibleSet)
        for (name in FullPartialFullStateContract.ALL_NAMES) {
            assertShellRowExists(name) { command -> ui.shellScript(command) }
        }

        // ---- 判据 9（记录读回，驱动 UI 前）：已处理保持、断点原样、无重复无丢失 ----
        assertEquals(
            "断点必须原样（PARTIAL 期间只读未触碰）",
            listOf(groupId) to checkpointIndex,
            preferences.getAlbumCleanupCheckpoint(albumId)
        )
        assertEquals(
            "g1 已处理记录必须保持", setOf(g1Key),
            preferences.getAlbumAcknowledgedCleanupMembers(albumId)
        )
        val trash = RecycleBinManager.getInstance(ctx)
        assertEquals(
            "回收站记录必须保持（g1）",
            listOf(g1Key), runBlocking { trash.loadRecycleBin().map { it.mediaIdentityKey() } }
        )
        assertEquals("组键必须原样（无重复无丢失）", groupMembers,
            preferences.getGroupImageIds(albumId, groupId))
        assertTrue(
            "组不得被标记永久完成",
            preferences.getAlbumPermanentlyProcessedGroups(albumId).isEmpty()
        )
        assertTrue(albumId !in preferences.getCompletedCleanupAlbumIds())

        // ---- 判据 9（引擎续接）：g1 不重新进入待处理；g2 恢复为待处理 ----
        // 输入 = 真实月份专辑行（与生产 deck 同口径：可见全集 6 行）
        val allVisibleRows = visibleList.map { rebuildRow(ctx, it) }
        val monthInfos = com.tabula.v3.ui.components.buildMonthInfos(allVisibleRows)
        assertEquals("全部夹具同月（生产月专辑唯一）", 1, monthInfos.size)
        val month = monthInfos.single()
        val batch = requireNotNull(
            FullRecoveryStateContract.cleanupEngine(ctx)
                .getNextBatch(albumId, month.images, latestSnapshot = allVisibleRows)
        ) { "engine must resume with the pending member after FULL restore" }
        assertEquals(
            "恢复批次必须恰为 [g2]（g1 经 acknowledged 过滤不交付、g2 待处理）",
            listOf(g2Key), batch.images.map { it.mediaIdentityKey() }
        )
        assertEquals(listOf(groupId), batch.groupIds)
        assertTrue(
            "引擎取批后组仍不得被标记永久完成",
            preferences.getAlbumPermanentlyProcessedGroups(albumId).isEmpty()
        )

        // ---- 判据 10：旧快照晚到不覆盖（真实生产链受控晚到，PR50 同形态）----
        // 旧扫描 = 滞留在 PARTIAL 时代投影 {b1,b2}（结果在手上、底层读取不可中断）；
        // 新扫描 = 默认真实生产 load（loadAllData + 回收站）；取消语义 = 键控重启。
        val recycleBinManager = RecycleBinManager.getInstance(ctx)
        val albumManager = AlbumManager(ctx)
        val fileOperationManager = FileOperationManager(ctx)
        val startedOld = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val oldReadReturned = AtomicBoolean(false)
        val committedBy = AtomicReference<String?>(null)
        val errors = AtomicReference<String?>(null)
        val appliedKeys = AtomicReference<List<String>>(emptyList())

        val bRows = FullPartialFullStateContract.B_NAMES.map { rebuildRow(ctx, it) }
        val stalePartialSnapshot = LocalImageRepository.LoadResult(
            allImages = bRows,
            imagesExcludingTabula = bRows,
            systemBuckets = emptyList()
        )
        val oldScan = launch {
            loadAndApplyMediaSnapshot(
                context = ctx,
                recycleBinManager = recycleBinManager,
                albumManager = albumManager,
                fileOperationManager = fileOperationManager,
                deletePermissionInFlight = false,
                onRecycleUnknown = {},
                onError = { errors.set("old") },
                onFinally = {},
                load = {
                    startedOld.complete(Unit)
                    withContext(NonCancellable) { releaseOld.await() }
                    oldReadReturned.set(true)
                    Pair(stalePartialSnapshot, emptyList())
                },
                apply = { _, _ -> committedBy.set("old") }
            )
        }
        kotlinx.coroutines.withTimeout(10_000) { startedOld.await() }
        // 新权限状态生效：键控重启取消在途旧扫描（其底层读取暂不响应）
        oldScan.cancel()

        // 新扫描完成并提交（真实生产 load——恢复后的 FULL 全池）
        val newScan = launch {
            loadAndApplyMediaSnapshot(
                context = ctx,
                recycleBinManager = recycleBinManager,
                albumManager = albumManager,
                fileOperationManager = fileOperationManager,
                deletePermissionInFlight = false,
                onRecycleUnknown = {},
                onError = { errors.set("new") },
                onFinally = {},
                apply = { projected, _ ->
                    committedBy.set("new")
                    appliedKeys.set(projected.allImages.map { it.mediaIdentityKey() }.sorted())
                }
            )
        }
        newScan.join()

        // 释放旧读取——它确实返回（晚到形态成立），但提交必须被取消检查拦截
        releaseOld.complete(Unit)
        oldScan.join()

        assertTrue("旧读取确实返回了（晚到形态成立）", oldReadReturned.get())
        assertEquals("提交只归新扫描", "new", committedBy.get())
        assertTrue("取消不得落 error 态", errors.get() == null)
        // 新扫描 apply 到的投影 = 当前真实全池投影：6 行 − 回收站 g1 = 5 行（真实数据）
        val expectedProjectedKeys = allVisibleRows.map { it.mediaIdentityKey() }
            .filter { it != g1Key }.toSet()
        assertEquals(
            "新扫描投影必须等于当前真实快照−软删除集合（旧快照内容不得混入）",
            expectedProjectedKeys, appliedKeys.get().toSet()
        )
        Log.i(TAG, "late-arrival race: old returned but never committed; new committed real projection")

        // ---- 判据 9'：UI 续接（生产安全重启路径）→ deck 当前卡 = g2 ----
        FullPartialFullUi.launchApp()
        ui.waitFor(ui.DECK_MARKER)
        FullPartialFullUi.startMonth(month.displayName)
        FullPartialFullUi.waitForExact(FullPartialFullStateContract.G2_NAME)
        FullPartialFullUi.awaitGone(
            FullPartialFullStateContract.G1_NAME, tag = "g1-not-reentered"
        )
        Log.i(TAG, "deck resumed at g2 via production safe-restart path")

        // ---- 判据 10'：最终一致性（最终 UI/查询状态与第 9 条一致）----
        val finalVisible = visibleDisplayNameList(ctx).toSet()
        assertEquals(
            "最终可见集合必须与恢复地面真值一致（晚到旧快照不得覆盖）",
            FullPartialFullStateContract.MEMBER_NAMES.toSet() + sentinelName, finalVisible
        )
        assertEquals(
            "最终已处理记录保持", setOf(g1Key),
            preferences.getAlbumAcknowledgedCleanupMembers(albumId)
        )
        assertEquals(
            "最终回收站保持", listOf(g1Key),
            runBlocking { trash.loadRecycleBin().map { it.mediaIdentityKey() } }
        )
        assertEquals("最终组键保持（无重复无丢失）", groupMembers,
            preferences.getGroupImageIds(albumId, groupId))
        assertTrue(
            "最终仍不得被标记永久完成",
            preferences.getAlbumPermanentlyProcessedGroups(albumId).isEmpty()
        )
        // 月份归属核对（FullRecoveryStateContract 形态字段；生产分组错误即红灯）
        val finalMonthInfos = com.tabula.v3.ui.components.buildMonthInfos(
            memberNames.map { rebuildRow(ctx, it) }
        )
        val monthAttribution = expected.getJSONArray("monthAttribution")
        for (i in 0 until monthAttribution.length()) {
            val entry = monthAttribution.getJSONObject(i)
            val identityKey = entry.getString("identityKey")
            val containingMonth = finalMonthInfos.firstOrNull { mi ->
                mi.images.any { it.mediaIdentityKey() == identityKey }
            }
            assertTrue("成员 $identityKey 必须仍在月列表中（无丢失）", containingMonth != null)
            assertEquals(
                "成员 $identityKey 月份归属必须一致",
                entry.getInt("month"), containingMonth!!.month
            )
            assertEquals(
                "成员 $identityKey 年份必须一致",
                entry.getInt("year"), containingMonth.year
            )
        }
        Log.i(TAG, "phase FULL-recovery complete: processed kept, resume at g2, race guarded")
    }
}
