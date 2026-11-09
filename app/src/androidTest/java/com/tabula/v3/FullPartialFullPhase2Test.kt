package com.tabula.v3

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.RecycleBinManager
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.FullPartialFullStateContract.assertShellRowExists
import com.tabula.v3.FullPartialFullStateContract.visibleDisplayNameList
import com.tabula.v3.FullRecoveryStateContract.rebuildRow
import com.tabula.v3.permission.MediaPermissionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T07 FULL→PARTIAL→FULL 三阶段验证——Phase PARTIAL(B)（判据 4–7）。
 *
 * 前置（宿主编排）：pm revoke READ_MEDIA_IMAGES → 应用被系统强停（#52 实证口径）
 * → 本阶段新 instrumentation 进程；应用数据（files/shared_prefs/回收站）保留。
 *
 * 断言链（判据逐条）：
 * 4. 真实系统机制建立 PARTIAL(B)：级别=NONE 前置 → launch → 授权前应用查询空集 →
 *    onboarding 门控 → 系统权限对话框 "Select photos"（候选匹配+推进核验）→
 *    照片选择器 6 格（网格=[B2,B1,G2,G1,N1,S1]）→ 像素驱动只选 {0,1}=b1,b2 →
 *    确认提交回读 → 轮询 VISUAL_USER_SELECTED 授且 FULL 未授（级别=PARTIAL）。
 * 5. 地面真值：应用可见恰={b1,b2}；g1/g2/n1 不可读（应用查询不含）但可恢复
 *    （shell 三面核对行仍在）；b1/b2 rebuildRow 可读。
 * 6. 进度完整保留（跨进程读回与 Phase1 落盘一致）：组键 [g1,g2] 原样、断点
 *    (group,1) 原样、acknowledged={g1键}、回收站=[g1键]、未永久完成、月专清未
 *    完成、onboarding/免责标记保留。B 未成组（组目录仅 G 组）→ 按判据以"组记录
 *    保留+非永久完成"断言，不驱动月份 UI、不以月名/数量替代。**纪律**：本阶段
 *    绝不调用 getCheckpointBatch/getNextBatch——引擎 acknowledged 成员安全重启
 *    分支会清断点（coord/notes-B2.md §1.2），进度保留断言只走只读 prefs。
 * 7. 哨兵 s1：应用内不可见 + shell 行存在（全程行存在）。
 *
 * 结束即结束——宿主随后 pm grant + force-stop（#52 口径）→ Phase3。
 */
@RunWith(AndroidJUnit4::class)
class FullPartialFullPhase2Test {
    private val TAG = "FullPartialFullP2"
    private val ui get() = PartialSelectionUi
    private val ctx get() = ui.ctx

    @Before fun setup() {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        check(Build.VERSION.SDK_INT >= 34) { "PARTIAL selection semantics require API 34+" }
        assertEquals(
            "Phase PARTIAL 前置：FULL 必须已被宿主 revoke",
            PackageManager.PERMISSION_DENIED,
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
        )
        assertEquals(
            "Phase PARTIAL 前置：VISUAL_USER_SELECTED 必须未授（裸状态）",
            PackageManager.PERMISSION_DENIED,
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        )
    }

    @Test
    fun revokeHoldsProgressAndPartialReselectEstablishesB() = runBlocking<Unit> {
        val expected = FullPartialFullStateContract.readExpectedState(ctx)
        assertNotEquals(
            "Phase2 必须运行在 Phase1 之后的新进程（宿主 revoke 系统强停）",
            expected.getInt("phase1Pid"), android.os.Process.myPid()
        )
        val albumId = expected.getString("albumId")
        val groupId = expected.getString("groupId")
        val checkpointIndex = expected.getInt("checkpointIndex")
        val groupMembers = expected.getJSONArray("members").let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        val bNames = expected.getJSONArray("bDisplayNames").let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        val sentinelName = expected.getString("sentinelName")
        val g1Key = expected.getJSONArray("processedMembers").getString(0)
        val preferences = AppPreferences.getInstance(ctx)

        // ---- 判据 6 前置核验：应用数据跨进程死亡保留 ----
        assertTrue("onboarding 标记必须保留", AppPreferences(ctx).hasCompletedOnboarding)
        assertTrue("免责声明标记必须保留", AppPreferences(ctx).hasAcknowledgedDisclaimer)

        // ---- 判据 4 前置：授权前应用查询空集（选择集过滤语义）----
        assertEquals(
            MediaPermissionPolicy.MediaAccessLevel.NONE,
            MediaPermissionPolicy.mediaAccessLevel(Build.VERSION.SDK_INT) { permission ->
                ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
        )
        assertEquals(
            "授权前应用可见集合必须为空（夹具非应用自有且未选中）",
            emptySet<String>(), visibleDisplayNameList(ctx).toSet()
        )

        // ---- 判据 5：g1/g2/n1 不可读但可恢复——shell 三面核对行仍在 + 哨兵行在 ----
        for (name in listOf(
            FullPartialFullStateContract.G1_NAME, FullPartialFullStateContract.G2_NAME,
            FullPartialFullStateContract.N1_NAME
        )) {
            assertShellRowExists(name) { command -> ui.shellScript(command) }
        }
        assertShellRowExists(sentinelName) { command -> ui.shellScript(command) }

        // ---- 判据 4：真实系统机制建立 PARTIAL(B)（#57 原语）----
        FullPartialFullUi.launchApp()
        ui.waitFor("授权访问照片")
        ui.tap("授权访问照片")
        ui.awaitAny(ui.DIALOG_CANDIDATES, tag = "permission-dialog", timeoutMs = 20000)
        ui.tapDialogActionAndAwaitPicker(ui.SELECT_PHOTOS_CANDIDATES, tag = "select-photos")

        ui.collectGridCells(minCount = FullPartialFullStateContract.GRID_TILE_COLORS.size)
        // 网格顺序 = [B2,B1,G2,G1,N1,S1]（宿主推送时间序倒序）；B = 索引 0、1
        ui.driveCheckedCells(
            expected = setOf(0, 1),
            tileColors = FullPartialFullStateContract.GRID_TILE_COLORS
        )
        val confirmLabel = ui.confirmSelection()
        Log.i(TAG, "picker confirmed via $confirmLabel; awaiting PARTIAL(B) grant + ground truth")

        val granted = awaitDeadline("partial-grant") {
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
                PackageManager.PERMISSION_GRANTED &&
                ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_DENIED
        }
        assertTrue("确认后必须授予 VISUAL_USER_SELECTED 且不得授予 FULL", granted)
        assertEquals(
            MediaPermissionPolicy.MediaAccessLevel.PARTIAL,
            MediaPermissionPolicy.mediaAccessLevel(Build.VERSION.SDK_INT) { permission ->
                ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
        )

        // ---- 判据 5：地面真值恰 = {b1,b2}；集合外不可见 ----
        val expectedB = bNames.toSet()
        val truth = awaitDeadline("ground-truth-B") {
            visibleDisplayNameList(ctx).toSet() == expectedB
        }
        assertTrue(
            "PARTIAL(B) 地面真值：应用可见集合必须恰为 B（实际=" +
                "${visibleDisplayNameList(ctx).toSet()} 预期=$expectedB）",
            truth
        )
        // B 集合专有可读性：PARTIAL 阶段 rebuildRow 必须成功
        assertEquals(expectedB, bNames.map { rebuildRow(ctx, it).displayName }.toSet())

        // ---- 判据 6：进度完整保留（跨进程读回与 Phase1 落盘一致；只读 prefs）----
        assertEquals(
            "清理组键必须原样（g1,g2 持久键序）",
            groupMembers, preferences.getGroupImageIds(albumId, groupId)
        )
        assertEquals(
            "断点必须原样（组+位置 1）",
            listOf(groupId) to checkpointIndex,
            preferences.getAlbumCleanupCheckpoint(albumId)
        )
        assertEquals(
            "已处理记录必须原样（g1）",
            setOf(g1Key), preferences.getAlbumAcknowledgedCleanupMembers(albumId)
        )
        assertEquals(
            "回收站记录必须原样（g1）",
            listOf(g1Key),
            runBlocking { RecycleBinManager.getInstance(ctx).loadRecycleBin().map { it.mediaIdentityKey() } }
        )
        assertEquals(
            "组目录必须仅含 G 组（B 未成组——判据 6 引擎语义前提）",
            setOf(groupId), preferences.getAlbumGroupIds(albumId)
        )
        assertTrue(
            "组不得被标记永久完成",
            preferences.getAlbumPermanentlyProcessedGroups(albumId).isEmpty()
        )
        assertTrue("月份专清不得被标记完成", albumId !in preferences.getCompletedCleanupAlbumIds())
        Log.i(TAG, "progress preserved across revoke: group/checkpoint/processed intact")

        // ---- 判据 7：哨兵集合外不可见 + 行存在（全程行存在之 PARTIAL 面）----
        assertTrue(
            "哨兵必须在应用可见集合之外",
            sentinelName !in visibleDisplayNameList(ctx).toSet()
        )
        assertShellRowExists(sentinelName) { command -> ui.shellScript(command) }

        // ---- 记录 Phase2 进程 ----
        expected.put("phase2Pid", android.os.Process.myPid())
        FullPartialFullStateContract.stateFile(ctx).writeText(expected.toString())
        Log.i(TAG, "phase PARTIAL complete: visible=B, progress held, sentinel outside")
    }

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
