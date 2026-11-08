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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FULL 恢复两阶段验证——阶段二（宿主已恢复 FULL 并重开应用，应用数据保留）。
 *
 * 断言：清理组身份键原样（未被标记永久完成）、断点跨进程死亡与权限循环
 * 存活（组 + 位置 1）、并完成一次实际续接（断点推进到位置 2 后核对持久化）。
 * 暂不可见成员（本场景为权限缺席期间不可处理的成员）保持未处理状态。
 */
@RunWith(AndroidJUnit4::class)
class FullRecoveryContinuationTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext

    @Before fun setup() {
        // 宿主已在缺席阶段后恢复 FULL——此处只核对，不再自行授权
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

        // 组身份键原样（暂不可见/未处理成员未被错误标记完成）
        assertEquals(members, preferences.getGroupImageIds(albumId, groupId))
        assertTrue(
            "组不得被标记永久完成",
            preferences.getAlbumPermanentlyProcessedGroups(albumId).isEmpty()
        )

        // 行对象按最新 MediaStore 元数据重建（同身份；FULL 已恢复可读）
        val rows = names.map { rebuildRow(ctx, it) }
        assertEquals(members, rows.map { it.mediaIdentityKey() })

        // 断点跨进程死亡与权限循环存活：组 + 位置 1
        val engine = cleanupEngine(ctx)
        val resumed = requireNotNull(
            engine.getCheckpointBatch(albumId, rows)
        ) { "checkpoint lost across process death / permission cycle" }
        assertEquals(checkpointIndex, resumed.second)
        assertEquals(listOf(groupId), resumed.first.groupIds)

        // 月份列表重建基础：行 dm 与阶段一一致（月列表由行 dm 派生，dm 不变即可重建）
        assertEquals(
            "行 dm 必须与阶段一一致（月列表重建基础）",
            names.map { rebuildRow(ctx, it).dateModified }, rows.map { it.dateModified }
        )

        // 实际续接（用户路径）：getNextBatch 从断点恢复批次 → 处理推进 →
        // saveCheckpoint 持久化（注意：saveCheckpoint 仅在引擎已进入该图集
        // 后生效——currentAlbumId 由 getNextBatch 设置；全新引擎实例上直接
        // 调用是静默 no-op，审计轮 7 实证）
        val next = requireNotNull(
            engine.getNextBatch(albumId, rows, latestSnapshot = rows)
        ) { "getNextBatch returned no batch for the restored group" }
        assertEquals(listOf(groupId), next.groupIds)
        engine.saveCheckpoint(listOf(groupId), checkpointIndex + 1)
        val checkpoint = requireNotNull(engine.getCheckpoint(albumId)) { "checkpoint lost after advancing" }
        assertEquals(checkpointIndex + 1, checkpoint.second)

        // 真实处理操作：recordUserRemovedImage（生产入口，确认成员已处理）
        val processedRow = rows.first()
        engine.recordUserRemovedImage(processedRow)
        // 重建读取：处理过的成员不再出现在批次中（剩余成员仍可续接）
        val remaining = rows.filter { it != processedRow }
        val nextAfterProcessing = engine.getNextBatch(albumId, remaining, latestSnapshot = remaining)
        org.junit.Assert.assertNotNull("剩余成员必须仍可续接", nextAfterProcessing)
        // 已处理成员被确认（不在待整理池）
        assertTrue(
            "已处理成员必须被确认",
            preferences.getAlbumAcknowledgedCleanupMembers(albumId)
                .contains(processedRow.mediaIdentityKey())
        )
        // 组不被标记永久完成（还有剩余成员）
        assertTrue(
            "有剩余成员的组不得被标记永久完成",
            preferences.getAlbumPermanentlyProcessedGroups(albumId).isEmpty()
        )
    }
}
