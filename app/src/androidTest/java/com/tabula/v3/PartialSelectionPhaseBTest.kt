package com.tabula.v3

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.provider.MediaStore
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.FullRecoveryStateContract.rebuildRow
import com.tabula.v3.PartialSelectionStateContract.readExpectedState
import com.tabula.v3.PartialSelectionStateContract.visibleDisplayNames
import com.tabula.v3.permission.MediaPermissionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PARTIAL 选择集 A→B 两阶段验证——阶段B（宿主已 force-stop 阶段A进程，
 * 应用数据与 PARTIAL(A) 授权保留，不 pm clear）。
 *
 * 断言链（包B：B1）：
 * 1. 保留（切换前）：新进程（pid 不同）读回预期 JSON；清理组成员键=A 原样、
 *    断点 (组,1) 原样、未永久完成、无 acknowledged、onboarding 标记保留。
 * 2. 真实系统机制改选：诊断轮裁决 (c)——ACTION_PICK_IMAGES 确认不更新
 *    持久 user-selected 集（双 API verdict + S4 latest-selection 佐证）——
 *    改经系统设置页重选（App 权限页 → Photos and videos → Select photos →
 *    选择器像素驱动 {0,1} → 确认）；地面真值 = 应用内查询恰为 {B1, B2}。
 * 3. 级别仍 PARTIAL（FULL 未授）。
 * 4. 真实 UI 一致性：deck 当前卡 ∈ B、查看器 overlay 身份匹配、A 与哨兵
 *    不可见；月份专清经真实 sheet 重建（生产 buildMonthInfos 口径），
 *    成员 = B。
 * 5. 保留（切换后）：清理组成员键仍为 A（不损坏）、断点原样、未永久完成、
 *    无 acknowledged；哨兵行经 shell 查询确认仍存在（集合外媒体不受影响），
 *    应用内查询不含哨兵。
 * 6. phaseBPid 写回状态文件。
 *
 * 已知边界（如实登记）：ACTION_PICK_IMAGES 确认对持久 user-selected 集合的
 * 更新语义以地面真值断言裁决——若该机制不符，红灯留存证据（截图+辅助树
 * dump+逐格勾选态日志），回退路径（系统设置页驱动）登记于 coord 笔记。
 */
@RunWith(AndroidJUnit4::class)
class PartialSelectionPhaseBTest {
    private val TAG = "PartialSelectionB"
    private val ui get() = PartialSelectionUi
    private val ctx get() = ui.ctx

    @Before fun setup() {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        check(Build.VERSION.SDK_INT >= 34) { "PARTIAL selection semantics require API 34+" }
        assertEquals(
            "阶段B前置：PARTIAL 必须仍授权（宿主不得 pm clear）",
            PackageManager.PERMISSION_GRANTED,
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        )
        assertEquals(
            "阶段B前置：FULL 必须未授权（PARTIAL 语义）",
            PackageManager.PERMISSION_DENIED,
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
        )
    }

    /** 阶段B 是本套件最后阶段：按名+路径精确删除全部 5 个合成夹具并核对消失。 */
    @After fun cleanup() {
        val stateFile = PartialSelectionStateContract.stateFile(ctx)
        if (!stateFile.exists()) return
        runCatching { readExpectedState(ctx) }.onSuccess { expected ->
            val names = mutableListOf<String>()
            names += expected.getJSONArray("displayNames").let { a ->
                (0 until a.length()).map { a.getString(it) }
            }
            names += expected.getJSONArray("bDisplayNames").let { b ->
                (0 until b.length()).map { b.getString(it) }
            }
            names += expected.getString("sentinelName")
            for (name in names) {
                val gone = PartialSelectionStateContract.shellDeleteFixture(name) { command ->
                    ui.shellScript(command)
                }
                Log.i(TAG, "cleanup $name gone=$gone")
                assertTrue("夹具删除后必须消失：$name", gone)
            }
            // 无行残留文件（扫描失败情形）兜底清理
            ui.shellScript("rm -rf ${PartialSelectionStateContract.FIXTURE_ABS_DIR}")
        }.onFailure { Log.w(TAG, "cleanup skipped: state file unreadable", it) }
    }

    @Test
    fun selectionChangeAToBViaPhotoPickerReselectAndAppFollows() = runBlocking<Unit> {
        val expected = readExpectedState(ctx)
        assertNotEquals(
            "阶段B必须运行在阶段A之后的新进程（宿主 force-stop）",
            expected.getInt("phaseAPid"), android.os.Process.myPid()
        )
        val albumId = expected.getString("albumId")
        val groupId = expected.getString("groupId")
        val checkpointIndex = expected.getInt("checkpointIndex")
        val aMembers = expected.getJSONArray("members").let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        val aNames = expected.getJSONArray("displayNames").let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        val bNames = expected.getJSONArray("bDisplayNames").let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        val sentinelName = expected.getString("sentinelName")
        val preferences = AppPreferences.getInstance(ctx)

        fun assertRetention(stage: String) {
            assertEquals("[$stage] 清理组成员键必须保留", aMembers, preferences.getGroupImageIds(albumId, groupId))
            assertEquals(
                "[$stage] 断点必须保留（组+位置）",
                listOf(groupId) to checkpointIndex,
                preferences.getAlbumCleanupCheckpoint(albumId)
            )
            assertTrue(
                "[$stage] 组不得被标记永久完成",
                preferences.getAlbumPermanentlyProcessedGroups(albumId).isEmpty()
            )
            assertTrue(
                "[$stage] 不得出现 acknowledged 成员（无真实处理操作）",
                preferences.getAlbumAcknowledgedCleanupMembers(albumId).isEmpty()
            )
            assertTrue(
                "[$stage] 月份专清不得被标记完成",
                albumId !in preferences.getCompletedCleanupAlbumIds()
            )
        }

        // ---- 1. 保留（切换前；跨进程死亡持久）----
        assertRetention("pre-switch")
        val appPrefs = AppPreferences(ctx)
        assertTrue("onboarding 标记必须保留", appPrefs.hasCompletedOnboarding)
        assertTrue("免责声明标记必须保留", appPrefs.hasAcknowledgedDisclaimer)
        assertEquals(aNames.toSet(), visibleDisplayNames(ctx))

        // ---- 2. 真实系统机制改选 A→B（系统设置页重选）----
        // 诊断轮裁决（双 API verdict=(c) + S4 佐证）：ACTION_PICK_IMAGES 确认
        // 不更新持久 user-selected 集（该机制语义不成立）——改走系统设置
        // 管理路径（官方文档背书）：App 权限页 → Photos and videos →
        // Select photos → 选择器（当前选中集预勾选）→ 像素驱动改选 → 确认。
        ui.launchSettingsPhotosPage()
        ui.tapAny(listOf("Permissions", "权限"), tag = "app-permissions-entry")
        ui.tapAny(ui.SETTINGS_PHOTOS_ENTRY_CANDIDATES, tag = "photos-permission-entry")
        // 行点击仅切模式不改选择集（R11 树 dump 实证）——改选经行内 gear 入口
        ui.tapSettingsSelectionGear()
        // 网格顺序 = [B2, B1, A2, A1, S]；B = 索引 0、1（最新两张）
        ui.driveCheckedCells(expected = setOf(0, 1), tileColors = PartialSelectionStateContract.GRID_TILE_COLORS)
        val confirmLabel = ui.confirmSelection()
        Log.i(TAG, "reselect confirmed via $confirmLabel; awaiting ground truth B")

        val expectedB = bNames.toSet()
        val truth = awaitDeadline("ground-truth-B") { visibleDisplayNames(ctx) == expectedB }
        if (!truth) {
            // 裁决 S3 主判据行（assert 消息随 INSTRUMENTATION stack 进入 job log）：
            // picker 仍开 ⇒ (b) Done 未提交；picker 已关 ⇒ (c) PICK_IMAGES 提交但授权集未变。
            val rootPkg = ui.ins.uiAutomation.rootInActiveWindow?.packageName?.toString() ?: "unknown"
            val pickerOpen = rootPkg.contains("media.module") || rootPkg.contains("photopicker")
            val verdictLine = if (pickerOpen) {
                "verdict=(b) Done tap not committed (picker still open: $rootPkg)"
            } else {
                "verdict=(c) PICK_IMAGES committed but user-selected set unchanged (picker closed: $rootPkg)"
            }
            Log.i(TAG, verdictLine)
            // 裁决 S4 佐证（best-effort）：最近一次选择查询 vs 普通查询
            val latest = runCatching { latestSelectionQuery() }.getOrNull()
            Log.i(TAG, "S4 latestSelectionOnly=$latest normal=${visibleDisplayNames(ctx).sorted()}")
            assertTrue(
                "重选确认后应用可见集合必须恰为 B（实际=${visibleDisplayNames(ctx)} 预期=$expectedB）$verdictLine S4=$latest",
                truth
            )
        }
        assertEquals(
            MediaPermissionPolicy.MediaAccessLevel.PARTIAL,
            MediaPermissionPolicy.mediaAccessLevel(Build.VERSION.SDK_INT) { permission ->
                ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
        )

        // ---- 4. 真实 UI 一致性：deck ∈ B、查看器身份、A/哨兵不可见 ----
        val rowsB = bNames.map { rebuildRow(ctx, it) }
        assertEquals(expectedB, rowsB.map { it.displayName }.toSet())
        ui.launch()
        ui.waitFor(ui.DECK_MARKER)
        val cardName = ui.openViewerWithRetry(bNames) { name ->
            rebuildRow(ctx, name).mediaIdentityKey()
        }
        ui.backToDeck()
        aNames.forEach { ui.awaitGone(it, timeoutMs = 5000) }
        ui.awaitGone(sentinelName, timeoutMs = 5000)

        // ---- 月份专清经真实 sheet：生产 buildMonthInfos 口径的月份成员 = B ----
        // （月份清理视图无 BACK 出口——本流程到此结束，不再返回 deck）
        val monthInfos = com.tabula.v3.ui.components.buildMonthInfos(rowsB)
        val monthName = monthInfos.single().displayName
        assertEquals(
            "月列表成员必须与 B 快照逐成员一致（不重复不遗漏）",
            rowsB.map { it.mediaIdentityKey() }.toSet(),
            monthInfos.single().images.map { it.mediaIdentityKey() }.toSet()
        )
        ui.tap("选择图集")
        ui.tap("月份")
        ui.waitFor(monthName)
        ui.tap(monthName)
        ui.tap("开始整理")
        bNames.forEach { ui.waitFor(it) }
        aNames.forEach { ui.awaitGone(it, timeoutMs = 5000) }

        // ---- 5. 保留（切换后）+ 哨兵不受影响 ----
        assertRetention("post-switch")
        assertTrue(
            "哨兵必须仍在应用可见集合之外",
            sentinelName !in visibleDisplayNames(ctx)
        )
        assertSentinelRowExists(sentinelName)

        // ---- 6. 记录阶段B进程 ----
        expected.put("phaseBPid", android.os.Process.myPid())
        PartialSelectionStateContract.stateFile(ctx).writeText(expected.toString())
        Log.i(TAG, "phase B complete: selection changed A->B, app follows, retention verified")
    }

    /** shell 身份查询哨兵行存在且元数据未变（集合外媒体不被应用触碰）。 */
    private fun assertSentinelRowExists(sentinelName: String) {
        val output = ui.shellScript(
            "content query --uri content://media/external/images/media " +
                "--projection _display_name:relative_path " +
                "--where \"_display_name='$sentinelName'\""
        )
        Log.i(TAG, "sentinel shell query: $output")
        assertTrue(
            "哨兵行必须经 shell 查询存在（name=$sentinelName）：$output",
            output.contains("Row: 0") && output.contains("_display_name=$sentinelName") &&
                output.contains("relative_path=${PartialSelectionStateContract.FIXTURE_DIR.trimEnd('/')}")
        )
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

    /** 裁决 S4（best-effort）：QUERY_ARG_LATEST_SELECTION_ONLY 查询最近一次用户选择（扩展版本守卫）。 */
    private fun latestSelectionQuery(): String? {
        val ext = try {
            SdkExtensions.getExtensionVersion(android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        } catch (e: Throwable) {
            0
        }
        if (ext < 12) return "extension<12"
        val bundle = Bundle().apply { putBoolean(MediaStore.QUERY_ARG_LATEST_SELECTION_ONLY, true) }
        val names = mutableListOf<String>()
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            bundle, null
        )!!.use { c ->
            while (c.moveToNext()) names += c.getString(0)
        }
        return names.sorted().toString()
    }
}
