package com.tabula.v3.data.balance

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
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
 * 走生产 seam RoundSessionRules + RoundIntentRules + 候选池口径，
 * 验证"接受只影响下一批"以及与其它模式的隔离。
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

    private fun sourceOf(image: ImageFile) = SourceBalanceRules.sourceKeyOf(image)

    private fun sessionWithSuccess(): RoundSessionRules.Session {
        var s = RoundSessionRules.Session()
        // 连续成功处理 4 张截图（真实会话里来自保留/归档/删除的累积）
        listOf(1L, 2L, 3L, 4L).forEach { id ->
            s = RoundSessionRules.record(
                s, shots.first { it.id == id }, RoundSessionRules.HandledAction.DELETE,
                ::sourceOf
            )
        }
        return s
    }

    private fun nextBatch(
        intent: RoundIntentRules.Intent,
        strategy: SourceBalanceRules.Strategy = SourceBalanceRules.Strategy.UNIFORM
    ) = RoundIntentRules.sampleWithIntent(
        available = library,
        batchSize = 6,
        intent = intent,
        strategy = strategy,
        random = Random(1)
    )

    @Test
    fun deletedHandledPhotosStillSuggestWhenOtherPhotosRemainInSameSource() {
        // 处理掉 4 张截图；这些照片已不在候选池（删除会离开图库），
        // 但会话记录仍带着它们的来源。
        val handled = shots.take(4)
        val session = handled.fold(RoundSessionRules.Session()) { state, image ->
            RoundSessionRules.record(
                state, image, RoundSessionRules.HandledAction.DELETE, ::sourceOf
            )
        }
        val remainingSameSource = shots.drop(4).take(2)
        val eligible = remainingSameSource + camera

        assertEquals(
            "已处理照片离开图库后仍能识别来源",
            "external_primary|DCIM/Screenshots/",
            RoundSessionRules.shouldSuggest(session, ::sourceOf, eligible)
        )

        // 同来源彻底没有批外候选 → 不提示
        assertNull(
            RoundSessionRules.shouldSuggest(session, ::sourceOf, camera)
        )
    }

    // ============ 提示触发 ============

    @Test
    fun hintAppearsAfterRealSuccessHandling() {
        val suggestion = RoundSessionRules.shouldSuggest(
            session = sessionWithSuccess(),
            sourceKeyOf = ::sourceOf,
            eligibleImages = shots.drop(4) + camera
        )
        assertEquals("external_primary|DCIM/Screenshots/", suggestion)
    }

    @Test
    fun failedActionsDoNotTriggerHint() {
        var s = RoundSessionRules.Session()
        // 三次尝试但都失败后撤销 → 会话为空
        repeat(3) { i ->
            val p = shots[i]
            s = RoundSessionRules.record(s, p, RoundSessionRules.HandledAction.DELETE, ::sourceOf)
            s = RoundSessionRules.undo(s, p)
        }
        assertNull(
            RoundSessionRules.shouldSuggest(s, ::sourceOf, shots.drop(4) + camera)
        )
    }

    @Test
    fun keepArchiveDeleteAllCountAsSuccess() {
        var s = RoundSessionRules.Session()
        s = RoundSessionRules.record(s, shots[0], RoundSessionRules.HandledAction.KEEP, ::sourceOf)
        s = RoundSessionRules.record(s, shots[1], RoundSessionRules.HandledAction.ARCHIVE, ::sourceOf)
        s = RoundSessionRules.record(s, shots[2], RoundSessionRules.HandledAction.DELETE, ::sourceOf)
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
        assertFalse("第二批恢复所选策略（原随机）",
            second.all { it.relativePath == "DCIM/Screenshots/" })
        assertEquals(6, second.size)
    }

    @Test
    fun ignoringDoesNotConsumeAnything() {
        val afterIgnore = RoundIntentRules.clear()
        assertFalse(afterIgnore.isActive)
        val batch = nextBatch(afterIgnore)
        assertEquals(
            "忽略不改变下一批：仍是用户所选策略的结果",
            SourceBalanceRules.uniformSample(library, 6, Random(1)).map { it.mediaIdentityKey() },
            batch.map { it.mediaIdentityKey() }
        )
    }

    @Test
    fun revokeIntentBeforeNextBatchRestoresOriginalStrategy() {
        var intent = RoundIntentRules.accept("external_primary|DCIM/Screenshots/", 1L)
        intent = RoundIntentRules.clear()
        val batch = nextBatch(intent)
        assertEquals(
            SourceBalanceRules.uniformSample(library, 6, Random(1)).map { it.mediaIdentityKey() },
            batch.map { it.mediaIdentityKey() }
        )
    }

    @Test
    fun exhaustedSourceFallsBackToChosenStrategy() {
        val onlyCamera = library.filter { it.relativePath == "DCIM/Camera/" }
        val strategyResult = SourceBalanceRules.uniformSample(onlyCamera, 6, Random(1))
        val batch = RoundIntentRules.sampleWithIntent(
            available = onlyCamera,
            batchSize = 6,
            intent = RoundIntentRules.accept("external_primary|DCIM/Screenshots/", 1L),
            strategy = SourceBalanceRules.Strategy.UNIFORM,
            random = Random(1)
        )
        assertEquals(6, batch.size)
        assertEquals(
            "来源耗尽按用户所选策略回退",
            strategyResult.map { it.mediaIdentityKey() },
            batch.map { it.mediaIdentityKey() }
        )
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
        assertNull(ended.acceptedThisRound)
    }

    @Test
    fun modeSwitchIsolatesFromCleanupMode() {
        val accepted = RoundSessionRules.markAccepted(
            sessionWithSuccess(), "external_primary|DCIM/Screenshots/"
        )
        val isolated = RoundSessionRules.isolateForModeSwitch(accepted)
        assertTrue(isolated.isEmpty())
        assertNull(isolated.acceptedThisRound)
        assertNull(
            RoundSessionRules.shouldSuggest(
                isolated, ::sourceOf, shots.drop(4) + camera
            )
        )
    }

    @Test
    fun restartResetsSession() {
        assertTrue(RoundSessionRules.Session().isEmpty())
        assertFalse(RoundIntentRules.clear().isActive)
    }

    @Test
    fun acceptedMarkerSuppressesRepeatedHintInSameRound() {
        val accepted = RoundSessionRules.markAccepted(
            sessionWithSuccess(), "external_primary|DCIM/Screenshots/"
        )
        assertNull(
            "接受后同一轮不再弹同一提示",
            RoundSessionRules.shouldSuggest(accepted, ::sourceOf, shots.drop(4) + camera)
        )
    }
}
