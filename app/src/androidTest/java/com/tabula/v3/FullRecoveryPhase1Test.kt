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
 * 建立：真实 MediaStore 媒体 3 张 → 持久化月份清理组 →
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
            swipeStyle = com.tabula.v3.data.preferences.SwipeStyle.SHUFFLE
        }
        AppPreferences.getInstance(ctx).clearAllAlbumCleanupState()
        Unit
    }

    @Test
    fun buildCleanupGroupMonthAndCheckpointState() = runBlocking {
        val rows = (1..3).map { seedPhoto(ctx, it) }
            .map { FullRecoveryStateContract.rebuildRow(ctx, displayNameOf(it)) }
        val albumId = com.tabula.v3.ui.components.buildMonthInfos(rows).single().cleanupAlbumId
        val groupId = seedCleanupGroup(ctx, rows)

        val engine = FullRecoveryStateContract.cleanupEngine(ctx)
        val batch = requireNotNull(engine.getNextBatch(albumId, rows)) {
            "cleanup engine returned no batch for the seeded group"
        }
        assertEquals(listOf(groupId), batch.groupIds)
        assertEquals(rows, batch.images)

        // 位置 1 只表示浏览位置，尚未确认任何成员。UI 应从第二张继续。
        engine.saveCheckpoint(listOf(groupId), 1)
        val resumed = requireNotNull(
            AlbumCleanupEngine(ctx).getCheckpointBatch(albumId, rows)
        ) { "checkpoint did not survive an in-process engine recreation" }
        assertEquals(1, resumed.second)
        assertEquals(listOf(groupId), resumed.first.groupIds)

        // 明确完成信号：commit() 同步落盘 tabula_album_cleanup 的所有
        // pending apply() 写入（含组/成员/断点全部生产键）。耐久性边界：
        // 仅本文件；其他存储由各自机制的写入保证覆盖。
        assertTrue(
            "flush sentinel commit() 必须成功（同步落盘完成信号）",
            FullRecoveryStateContract.flushCleanupPrefs(ctx)
        )

        writeExpectedState(ctx, groupId, rows, checkpointIndex = 1)
        val expected = FullRecoveryStateContract.readExpectedState(ctx)
        val members = rows.map { it.mediaIdentityKey() }.toSet()
        val raw = com.tabula.v3.data.repository.LocalImageRepository(ctx).loadAllData()
        expected.put("outsideMembers", org.json.JSONArray(raw.allImages.map { it.mediaIdentityKey() }.filter { it !in members }))
        FullRecoveryStateContract.stateFile(ctx).writeText(expected.toString())
        assertTrue(FullRecoveryStateContract.stateFile(ctx).length() > 0)
    }

    private fun displayNameOf(uri: android.net.Uri): String = requireNotNull(
        ctx.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
    ) { "MediaStore query returned null" }.use {
        check(it.moveToFirst()) { "No row for $uri" }
        it.getString(0)
    }
}
