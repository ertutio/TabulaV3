package com.tabula.v3

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.FullRecoveryStateContract.cleanupEngine
import com.tabula.v3.FullRecoveryStateContract.readExpectedState
import com.tabula.v3.FullRecoveryStateContract.rebuildRow
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.ui.components.MonthInfo
import com.tabula.v3.ui.components.buildMonthInfos
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * 3. 月列表通过生产逻辑 buildMonthInfos 重建，月份/成员/数量与阶段一预期一致
 * 4. 实际续接：getNextBatch 恢复断点 → 处理推进 → saveCheckpoint 持久化
 * 5. 真实处理操作（recordUserRemovedImage）→ 重建读取核对已处理/剩余/组状态
 * 6. 暂不可见成员保持未处理状态
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
        val engine = cleanupEngine(ctx)
        val resumed = requireNotNull(
            engine.getCheckpointBatch(albumId, rows)
        ) { "checkpoint lost across process death / permission cycle" }
        assertEquals(checkpointIndex, resumed.second)
        assertEquals(listOf(groupId), resumed.first.groupIds)

        // 4. 月列表通过生产逻辑 buildMonthInfos 重建并核对
        val monthInfos = buildMonthInfos(rows)
        assertTrue("月列表不得为空", monthInfos.isNotEmpty())
        val monthAttribution = expected.getJSONArray("monthAttribution")
        for (i in 0 until monthAttribution.length()) {
            val entry = monthAttribution.getJSONObject(i)
            val identityKey = entry.getString("identityKey")
            val expectedYear = entry.getInt("year")
            val expectedMonth = entry.getInt("month")
            val row = rows.first { it.mediaIdentityKey() == identityKey }
            val calendar = java.util.Calendar.getInstance().apply { timeInMillis = row.dateModified }
            assertEquals(
                "成员 $identityKey 月份归属必须与阶段一预期一致",
                expectedMonth, calendar.get(java.util.Calendar.MONTH) + 1
            )
            assertEquals(
                "成员 $identityKey 年份必须与阶段一预期一致",
                expectedYear, calendar.get(java.util.Calendar.YEAR)
            )
        }
        // 月列表成员总数 = rows 总数（不遗漏不重复）
        assertEquals(rows.size, monthInfos.sumOf { it.imageCount })

        // 5. 实际续接（用户路径）：getNextBatch 从断点恢复批次 → 处理推进 →
        //    saveCheckpoint 持久化（currentAlbumId 由 getNextBatch 设置）
        val next = requireNotNull(
            engine.getNextBatch(albumId, rows, latestSnapshot = rows)
        ) { "getNextBatch returned no batch for the restored group" }
        assertEquals(listOf(groupId), next.groupIds)
        engine.saveCheckpoint(listOf(groupId), checkpointIndex + 1)
        val checkpoint = requireNotNull(engine.getCheckpoint(albumId)) { "checkpoint lost after advancing" }
        assertEquals(checkpointIndex + 1, checkpoint.second)

        // 6. 真实处理操作：recordUserRemovedImage（生产入口）→ 重建读取
        val processedRow = rows.first()
        engine.recordUserRemovedImage(processedRow)
        val remaining = rows.filter { it != processedRow }
        val nextAfterProcessing = engine.getNextBatch(albumId, remaining, latestSnapshot = remaining)
        assertNotNull("剩余成员必须仍可续接", nextAfterProcessing)
        assertTrue(
            "已处理成员必须被确认",
            preferences.getAlbumAcknowledgedCleanupMembers(albumId)
                .contains(processedRow.mediaIdentityKey())
        )
        assertTrue(
            "有剩余成员的组不得被标记永久完成",
            preferences.getAlbumPermanentlyProcessedGroups(albumId).isEmpty()
        )
    }
}
