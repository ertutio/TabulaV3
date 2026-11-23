package com.tabula.v3.data.balance

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.SimilarGroup
import com.tabula.v3.data.repository.mediaIdentityKey
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #90 入口级测试：真实会话行为 → 提示 → 接受/忽略/撤销 → 下一批。
 *
 * 走生产 seam RoundSessionRules + RoundIntentRules + RecommendationEngine
 * 的抽样口径，验证"接受只影响下一批"以及与其它模式的隔离。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RoundSessionEntryTest {

    private fun photo(id: Long, path: String, volume: String = "external_primary") = ImageFile(
        id = id,
        uri = Uri.parse("content://media/$volume/images/media/$id"),
        displayName = "p$id.jpg",
        dateModified = 1_700_000_000_000L + id,
        size = 1000L,
        width = 1080,
        height = 1440,
        bucketDisplayName = path.trimEnd('/').substringAfterLast('/'),
        volumeName = volume,
        relativePath = path
    )

    private val shots = (1L..20L).map { photo(it, "DCIM/Screenshots/") }
    private val camera = (100L..300L).map { photo(it, "DCIM/Camera/") }
    private val library = shots + camera

    private fun sessionWithSuccess(): RoundSessionRules.Session {
        var s = RoundSessionRules.Session()
        // 连续成功处理 4 张截图（真实会话里来自保留/归档/删除的累积）
        listOf(1L, 2L, 3L, 4L).forEach { id ->
            s = RoundSessionRules.record(
                s, shots.first { it.id == id }, RoundSessionRules.HandledAction.DELETE
            )
        }
        return s
    }

    private fun nextBatch(intent: RoundIntentRules.Intent) = RoundIntentRules.sampleWithIntent(
        available = library,
        batchSize = 6,
        intent = intent,
        random = Random(1)
    )

    // ============ 提示触发 ============

    @Test
    fun hintAppearsAfterRealSuccessHandling() {
        val suggestion = RoundSessionRules.shouldSuggest(
            session = sessionWithSuccess(),
            sourceKeyOf = { SourceBalanceRules.sourceKeyOf(it) },
            availableKeys = library.map { it.mediaIdentityKey() }.toSet()
        )
        assertEquals("external_primary|DCIM/Screenshots/", suggestion)
    }

    @Test
    fun failedActionsDoNotTriggerHint() {
        var s = RoundSessionRules.Session()
        // 三次尝试但都失败后撤销 → 会话为空
        repeat(3) { i ->
            val p = shots[i]
            s = RoundSessionRules.record(s, p, RoundSessionRules.HandledAction.DELETE)
            s = RoundSessionRules.undo(s, p)
        }
        assertNull(
            RoundSessionRules.shouldSuggest(
                s, { SourceBalanceRules.sourceKeyOf(it) },
                library.map { it.mediaIdentityKey() }.toSet()
            )
        )
    }

    @Test
    fun keepArchiveDeleteAllCountAsSuccess() {
        var s = RoundSessionRules.Session()
        s = RoundSessionRules.record(s, shots[0], RoundSessionRules.HandledAction.KEEP)
        s = RoundSessionRules.record(s, shots[1], RoundSessionRules.HandledAction.ARCHIVE)
        s = RoundSessionRules.record(s, shots[2], RoundSessionRules.HandledAction.DELETE)
        assertEquals(3, s.currentBatch.size)
        assertEquals(
            setOf(
                RoundSessionRules.HandledAction.KEEP,
                RoundSessionRules.HandledAction.ARCHIVE,
                RoundSessionRules.HandledAction.DELETE
            ),
            s.currentBatch.map { it.action }.toSet()
        )
    }

    // ============ 接受 / 忽略 / 撤销 ============

    @Test
    fun acceptingAffectsOnlyTheNextBatch() {
        var intent = RoundIntentRules.accept("external_primary|DCIM/Screenshots/", 1L)
        val first = nextBatch(intent)
        assertTrue(first.all { it.relativePath == "DCIM/Screenshots/" })

        intent = RoundIntentRules.consume(intent)
        val second = nextBatch(intent)
        assertEquals(2, second.map { it.relativePath }.toSet().size)
    }

    @Test
    fun ignoringDoesNotConsumeAnything() {
        // 忽略只清提示，不写 intent
        val afterIgnore = RoundIntentRules.clear()
        assertFalse(afterIgnore.isActive)
        val batch = nextBatch(afterIgnore)
        assertEquals(2, batch.map { it.relativePath }.toSet().size)
    }

    @Test
    fun revokeIntentBeforeNextBatchRestoresOriginalStrategy() {
        var intent = RoundIntentRules.accept("external_primary|DCIM/Screenshots/", 1L)
        // 下一批装载前撤销
        intent = RoundIntentRules.clear()
        val batch = nextBatch(intent)
        assertEquals(2, batch.map { it.relativePath }.toSet().size)
    }

    @Test
    fun exhaustedSourceFallsBackSilently() {
        val onlyCamera = library.filter { it.relativePath == "DCIM/Camera/" }
        val batch = RoundIntentRules.sampleWithIntent(
            available = onlyCamera,
            batchSize = 6,
            intent = RoundIntentRules.accept("external_primary|DCIM/Screenshots/", 1L),
            random = Random(1)
        )
        assertEquals(6, batch.size)
        assertTrue(batch.all { it.relativePath == "DCIM/Camera/" })
    }

    // ============ 会话清理与隔离 ============

    @Test
    fun batchSwitchClearsCurrentStats() {
        val after = RoundSessionRules.nextBatch(sessionWithSuccess())
        assertTrue(after.isEmpty())
        assertEquals(1, after.completedBatches.size)
    }

    @Test
    fun leavingSessionClearsEverything() {
        val ended = RoundSessionRules.endSession()
        assertTrue(ended.isEmpty())
        assertTrue(ended.completedBatches.isEmpty())
    }

    @Test
    fun modeSwitchIsolatesFromCleanupMode() {
        // 切到专清 / 相似推荐时，本轮统计被清空，不继承来源偏好
        val isolated = RoundSessionRules.isolateForModeSwitch(sessionWithSuccess())
        assertTrue(isolated.isEmpty())
        assertNull(
            RoundSessionRules.shouldSuggest(
                isolated, { SourceBalanceRules.sourceKeyOf(it) },
                library.map { it.mediaIdentityKey() }.toSet()
            )
        )
    }

    @Test
    fun restartResetsSession() {
        // 会话不持久化：重启后是全新 Session
        assertTrue(RoundSessionRules.Session().isEmpty())
        assertFalse(RoundIntentRules.clear().isActive)
    }

    @Test
    fun coolingStillAppliesWithRoundIntent() {
        // 截图全在冷却中，接受意图也不能把它们放回来
        val cooled = shots
        val eligible = camera
        val batch = RoundIntentRules.sampleWithIntent(
            available = eligible,
            batchSize = 6,
            intent = RoundIntentRules.accept("external_primary|DCIM/Screenshots/", 1L),
            random = Random(1)
        )
        val cooledKeys = cooled.map { it.mediaIdentityKey() }.toSet()
        assertTrue(batch.none { it.mediaIdentityKey() in cooledKeys })
    }
}
