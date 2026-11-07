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

        // 实际续接：处理意图推进到位置 2 并持久化
        engine.saveCheckpoint(listOf(groupId), checkpointIndex + 1)
        val advanced = requireNotNull(engine.getCheckpointBatch(albumId, rows))
        assertEquals(checkpointIndex + 1, advanced.second)
        assertEquals(listOf(groupId), advanced.first.groupIds)
    }
}
