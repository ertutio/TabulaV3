package com.tabula.v3

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.AlbumCleanupEngine
import com.tabula.v3.data.repository.SimilarGroup
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.FullRecoveryStateContract.seedCleanupGroup
import com.tabula.v3.FullRecoveryStateContract.seedPhoto
import com.tabula.v3.FullRecoveryStateContract.writeExpectedState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FULL 恢复两阶段验证——阶段一（审计轮：工作包 2）。
 *
 * 建立：真实 MediaStore 媒体 2 张 → 持久化清理组（单元测试同款写入口）→
 * 引擎取批 → saveCheckpoint(index=1) → 进程内往返核对 → 预期状态写入
 * files/（跨进程死亡与权限变化持久）。结束即结束——宿主随后执行
 * pm revoke（应用被系统强停）→ 缺席断言 → pm grant（恢复 FULL）→ 重开 →
 * 阶段二续接断言。
 */
@RunWith(AndroidJUnit4::class)
class FullRecoveryPhase1Test {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext

    @Before fun setup() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        org.junit.Assert.assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ctx.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
        )
        AppPreferences(ctx).apply {
            hasCompletedOnboarding = true
            hasAcknowledgedDisclaimer = true
        }
        AppPreferences.getInstance(ctx).clearAllAlbumCleanupState()
        Unit
    }

    @Test
    fun buildCleanupGroupMonthAndCheckpointState() = runBlocking {
        val photo1 = seedPhoto(ctx, 1)
        val photo2 = seedPhoto(ctx, 2)
        val rows = listOf(photo1, photo2).map { FullRecoveryStateContract.rebuildRow(ctx, displayNameOf(it)) }
        val groupId = FullRecoveryStateContract.groupIdOf(rows)
        seedCleanupGroup(ctx, rows, groupId)

        val engine = FullRecoveryStateContract.cleanupEngine(ctx)
        val batch = requireNotNull(engine.getNextBatch(FullRecoveryStateContract.ALBUM_ID, rows)) {
            "cleanup engine returned no batch for the seeded group"
        }
        assertEquals(listOf(groupId), batch.groupIds)
        assertEquals(rows, batch.images)

        // 断点：位置 1（一张已入处理意图）
        engine.saveCheckpoint(listOf(groupId), 1)
        val resumed = requireNotNull(
            AlbumCleanupEngine(ctx).getCheckpointBatch(FullRecoveryStateContract.ALBUM_ID, rows)
        ) { "checkpoint did not survive an in-process engine recreation" }
        assertEquals(1, resumed.second)
        assertEquals(listOf(groupId), resumed.first.groupIds)

        // 明确完成信号：commit() 同步落盘 tabula_album_cleanup 的所有
        // pending apply() 写入（含组/成员/断点全部生产键）。耐久性边界：
        // 仅本文件；其他存储由各自机制的写入保证覆盖。
        FullRecoveryStateContract.flushCleanupPrefs(ctx)

        writeExpectedState(ctx, groupId, rows, checkpointIndex = 1)
        assertTrue(FullRecoveryStateContract.stateFile(ctx).length() > 0)
    }

    private fun displayNameOf(uri: android.net.Uri): String = requireNotNull(
        ctx.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
    ) { "MediaStore query returned null" }.use {
        check(it.moveToFirst()) { "No row for $uri" }
        it.getString(0)
    }
}
