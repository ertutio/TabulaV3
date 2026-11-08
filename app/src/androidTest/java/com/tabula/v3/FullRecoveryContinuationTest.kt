package com.tabula.v3

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.FullRecoveryStateContract.readExpectedState
import com.tabula.v3.FullRecoveryStateContract.rebuildRow
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.ui.components.buildMonthInfos
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import com.tabula.v3.data.repository.RecycleBinManager
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FULL 恢复两阶段验证——阶段二（宿主已恢复 FULL 并重开应用，应用数据保留）。
 *
 * 断言：
 * 1. 清理组身份键原样（未被标记永久完成）
 * 2. 断点跨进程死亡与权限循环存活（组 + 位置 1）
 * 3. 月列表通过生产逻辑 buildMonthInfos 重建并核对月份/成员/数量；
 *    逐成员月份归属核对即为"月份错误但总数正确"的回归——生产分组错误
 *    导致成员落入错误 MonthInfo 时此断言失败
 * 4. 从真实月份 UI 续接，向上滑动第二张并等待撤销窗口结束。
 * 5. 宿主随后 force-stop。FullRecoveryReadbackTest 在另一个进程先读取
 *    持久状态，再通过真实媒体快照、生产投影和 UI 验证剩余成员续接。
 */
@RunWith(AndroidJUnit4::class)
class FullRecoveryContinuationTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext

    @Before fun setup() {
        assertEquals(
            "阶段二前置：FULL 必须已恢复",
            PackageManager.PERMISSION_GRANTED,
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
        )
    }

    @Test
    fun checkpointAndGroupSurviveAndContinueAfterFullRestore() = runBlocking {
        val expected = readExpectedState(ctx)
        assertNotEquals(expected.getInt("phase1Pid"), android.os.Process.myPid())
        assertNotEquals(expected.getInt("absencePid"), android.os.Process.myPid())
        val albumId = expected.getString("albumId")
        val groupId = expected.getString("groupId")
        val checkpointIndex = expected.getInt("checkpointIndex")
        val members = mutableListOf<String>()
        val names = mutableListOf<String>()
        val memberArray = expected.getJSONArray("members")
        val nameArray = expected.getJSONArray("displayNames")
        for (i in 0 until memberArray.length()) {
            members += memberArray.getString(i)
            names += nameArray.getString(i)
        }
        val preferences = AppPreferences.getInstance(ctx)

        // 1. 组身份键原样（暂不可见/未处理成员未被错误标记完成）
        assertEquals(members, preferences.getGroupImageIds(albumId, groupId))
        assertTrue(
            "组不得被标记永久完成",
            preferences.getAlbumPermanentlyProcessedGroups(albumId).isEmpty()
        )

        // 2. 行对象按最新 MediaStore 元数据重建（同身份；FULL 已恢复可读）
        val rows = names.map { rebuildRow(ctx, it) }
        assertEquals(members, rows.map { it.mediaIdentityKey() })

        // 3. 断点跨进程死亡与权限循环存活：组 + 位置 1
        assertEquals(
            listOf(groupId) to checkpointIndex,
            preferences.getAlbumCleanupCheckpoint(albumId)
        )

        // 4. 月列表通过生产逻辑 buildMonthInfos 重建并核对（审计轮：替换替代断言）
        val monthInfos = buildMonthInfos(rows)
        assertTrue("月列表不得为空", monthInfos.isNotEmpty())
        assertEquals(rows.size, monthInfos.sumOf { it.imageCount })
        // 逐成员月份归属核对（阶段一月归属 JSON vs buildMonthInfos 实际输出）——
        // 生产分组错误时（成员落入错误 MonthInfo），此断言失败
        val monthAttribution = expected.getJSONArray("monthAttribution")
        for (i in 0 until monthAttribution.length()) {
            val entry = monthAttribution.getJSONObject(i)
            val identityKey = entry.getString("identityKey")
            val expectedYear = entry.getInt("year")
            val expectedMonth = entry.getInt("month")
            val containingMonth = monthInfos.firstOrNull { mi ->
                mi.images.any { it.mediaIdentityKey() == identityKey }
            }
            assertNotNull("成员 $identityKey 必须出现在月列表中", containingMonth)
            assertEquals(
                "成员 $identityKey 月份归属必须一致",
                expectedMonth, containingMonth!!.month
            )
            assertEquals(
                "成员 $identityKey 年份必须一致",
                expectedYear, containingMonth.year
            )
        }
        // 月列表成员不重复不遗漏
        val monthMemberKeys = monthInfos.flatMap { it.images }.map { it.mediaIdentityKey() }
        assertEquals("月列表成员不得重复", monthMemberKeys.size, monthMemberKeys.toSet().size)
        assertEquals("月列表成员不得遗漏", members.toSet(), monthMemberKeys.toSet())

        // The test performs a gesture; only production paths may update state.
        val processedRow = rows[checkpointIndex]
        val trash = RecycleBinManager.getInstance(ctx)
        assertTrue(trash.loadRecycleBin().isEmpty())
        FullRecoveryUi.launch()
        FullRecoveryUi.startMonth(monthInfos.single())
        FullRecoveryUi.removeCard(processedRow.displayName)
        FullRecoveryUi.await("UI removal committed after undo timeout") {
            runBlocking { trash.loadRecycleBin().map { it.mediaIdentityKey() } } == listOf(processedRow.mediaIdentityKey()) &&
                preferences.getAlbumAcknowledgedCleanupMembers(albumId) == setOf(processedRow.mediaIdentityKey())
        }
        assertEquals(members, names.map { rebuildRow(ctx, it).mediaIdentityKey() })
        assertTrue(
            "有剩余成员的组不得被标记永久完成",
            preferences.getAlbumPermanentlyProcessedGroups(albumId).isEmpty()
        )
        // The initial fixture alone uses the flush sentinel. Here production apply()
        // must finish on its own before the host kills this process.
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(home)
        android.os.SystemClock.sleep(1000)
        expected.put("processingPid", android.os.Process.myPid())
        FullRecoveryStateContract.stateFile(ctx).writeText(expected.toString())
    }
}
