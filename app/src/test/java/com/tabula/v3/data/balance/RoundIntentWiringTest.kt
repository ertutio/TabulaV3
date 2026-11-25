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
 * 本轮整理意图的接线级测试（审计意见 #90 第三轮）。
 *
 * 按**接线后的调用顺序**串一遍 UI 与引擎之间的契约：接受只影响下一批、
 * 忽略不写意图、来源耗尽回落用户所选策略、冷却/屏蔽/当前批次都扣掉、
 * 切换模式清全部状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RoundIntentWiringTest {

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

    private val screenshots = "external_primary|DCIM/Screenshots/"
    private val camera = "external_primary|DCIM/Camera/"

    private fun sourceOf(image: ImageFile) = SourceBalanceRules.sourceKeyOf(image)

    private fun library() =
        (1L..20L).map { photo(it, "DCIM/Screenshots/") } +
            (100L..300L).map { photo(it, "DCIM/Camera/") }

    private fun session(images: List<ImageFile>) =
        images.fold(RoundSessionRules.Session()) { state, image ->
            RoundSessionRules.record(state, image, RoundSessionRules.HandledAction.DELETE)
        }

    // ============ 接受只影响下一批 ============

    @Test
    fun acceptedIntentAffectsExactlyOneBatchThenFallsBack() {
        val available = library()
        var intent = RoundIntentRules.accept(screenshots, 1L)

        val first = RoundIntentRules.sampleWithIntent(
            available, 6, intent, SourceBalanceRules.Strategy.UNIFORM, Random(1)
        )
        assertTrue(first.all { it.relativePath == "DCIM/Screenshots/" })

        intent = RoundIntentRules.consume(intent)
        assertFalse(intent.isActive)
        val second = RoundIntentRules.sampleWithIntent(
            available, 6, intent, SourceBalanceRules.Strategy.UNIFORM, Random(1)
        )
        assertFalse(second.all { it.relativePath == "DCIM/Screenshots/" })
    }

    @Test
    fun consumedIntentKeepsTheSourceKeyForAudit() {
        val consumed = RoundIntentRules.consume(RoundIntentRules.accept(screenshots, 1L))
        assertEquals(screenshots, consumed.sourceKey)
        assertFalse(consumed.isActive)
    }

    // ============ 忽略 ============

    @Test
    fun dismissedHintStopsAsking() {
        val handled = (1L..4L).map { photo(it, "DCIM/Screenshots/") }
        val eligible = listOf(photo(50, "DCIM/Screenshots/"))
        val s = session(handled)

        assertEquals(screenshots, RoundIntentRules.shouldSuggest(s, eligible, ::sourceOf))
        assertNull(RoundIntentRules.shouldSuggest(s, eligible, ::sourceOf, dismissed = true))
    }

    @Test
    fun dismissDoesNotAffectSampling() {
        val available = library()
        // 忽略只是不显示提示、不写意图；下一批仍按用户所选策略
        val batch = RoundIntentRules.sampleWithIntent(
            available, 6, RoundIntentRules.clear(),
            SourceBalanceRules.Strategy.UNIFORM, Random(1)
        )
        assertEquals(
            SourceBalanceRules.uniformSample(available, 6, Random(1)).map { it.mediaIdentityKey() },
            batch.map { it.mediaIdentityKey() }
        )
    }

    // ============ 撤销 ============

    @Test
    fun revokingIntentBeforeNextBatchRestoresOriginalStrategy() {
        val available = library()
        var intent = RoundIntentRules.accept(screenshots, 1L)
        assertTrue(intent.isActive)

        intent = RoundIntentRules.clear()
        val batch = RoundIntentRules.sampleWithIntent(
            available, 6, intent, SourceBalanceRules.Strategy.UNIFORM, Random(1)
        )
        assertEquals(
            SourceBalanceRules.uniformSample(available, 6, Random(1)).map { it.mediaIdentityKey() },
            batch.map { it.mediaIdentityKey() }
        )
    }

    // ============ 退出整理 ============

    @Test
    fun leavingTheSessionClearsIntent() {
        val cleared = RoundIntentRules.clear()
        assertNull(cleared.sourceKey)
        assertFalse(cleared.isActive)
        assertEquals(0L, cleared.acceptedAt)
    }

    // ============ 来源耗尽：回落用户所选策略 ============

    @Test
    fun exhaustedSourceSilentlyFallsBackToChosenStrategy() {
        val available = (100L..300L).map { photo(it, "DCIM/Camera/") } +
            (400L..402L).map { photo(it, "DCIM/Screens/") }
        val intent = RoundIntentRules.accept(screenshots, 1L)

        val batch = RoundIntentRules.sampleWithIntent(
            available, 6, intent, SourceBalanceRules.Strategy.UNIFORM, Random(5)
        )

        assertEquals(6, batch.size)
        assertEquals(
            "来源耗尽要按用户所选策略出批（原随机），不是固定均衡",
            SourceBalanceRules.uniformSample(available, 6, Random(5)).map { it.mediaIdentityKey() },
            batch.map { it.mediaIdentityKey() }
        )
    }

    @Test
    fun partiallyExhaustedSourceFillsFromOthers() {
        val available = (1L..2L).map { photo(it, "DCIM/Screenshots/") } +
            (100L..300L).map { photo(it, "DCIM/Camera/") }
        val intent = RoundIntentRules.accept(screenshots, 1L)

        val batch = RoundIntentRules.sampleWithIntent(
            available, 6, intent, SourceBalanceRules.Strategy.UNIFORM, Random(1)
        )
        assertEquals(6, batch.size)
        assertEquals(2, batch.count { it.relativePath == "DCIM/Screenshots/" })
    }

    // ============ 候选池口径 ============

    @Test
    fun cooldownPhotosAreNotEligibleCandidates() {
        val all = library()
        val cooledShot = photo(1, "DCIM/Screenshots/")
        val cooledCamera = photo(100, "DCIM/Camera/")

        val eligible = RoundCandidateRules.eligibleForNextBatch(
            allImages = all,
            cooldownIdentityKeys = setOf(
                cooledShot.mediaIdentityKey(),
                cooledCamera.mediaIdentityKey()
            )
        )

        assertTrue(
            "冷却中的照片不是下一批候选",
            eligible.none { it.mediaIdentityKey() == cooledShot.mediaIdentityKey() }
        )
        assertTrue(eligible.none { it.mediaIdentityKey() == cooledCamera.mediaIdentityKey() })
        assertEquals(all.size - 2, eligible.size)
    }

    @Test
    fun excludedAlbumPhotosAreNotEligibleCandidates() {
        val all = library()
        val eligible = RoundCandidateRules.eligibleForNextBatch(
            allImages = all,
            excludedAlbumIds = setOf("Screenshots")
        )
        assertTrue(
            "屏蔽图集里的照片不是下一批候选",
            eligible.none { it.bucketDisplayName == "Screenshots" }
        )
    }

    @Test
    fun currentBatchIsDeductedFromCandidates() {
        val all = library()
        val current = all.filter { it.mediaIdentityKey() == "external_primary|5" }
        require(current.size == 1)

        val eligible = RoundCandidateRules.eligibleForNextBatch(
            allImages = all,
            currentBatch = current
        )
        assertTrue(
            "当前批次里已有的身份不算下一批候选",
            eligible.none { it.mediaIdentityKey() == "external_primary|5" }
        )
    }

    @Test
    fun legacyBareIdCooldownIsAlsoDeducted() {
        val all = library()
        val eligible = RoundCandidateRules.eligibleForNextBatch(
            allImages = all,
            cooldownLegacyBareIds = setOf(7L)
        )
        assertTrue(
            "未迁移的旧裸 ID 冷却记录同样生效",
            eligible.none { it.id == 7L }
        )
    }

    @Test
    fun onlyCurrentBatchRemainderDoesNotSuggestButOutOfBatchDoes() {
        // 4 张截图正是当前批次，候选池里已把它们扣掉
        val batch = (1L..4L).map { photo(it, "DCIM/Screenshots/") }
        val all = batch + (100L..120L).map { photo(it, "DCIM/Camera/") }
        val s = session(batch)

        val eligibleNoOutOfBatch = RoundCandidateRules.eligibleForNextBatch(
            allImages = all, currentBatch = batch
        )
        assertNull(
            "只有当前批次内的同来源照片时不提示",
            RoundIntentRules.shouldSuggest(s, eligibleNoOutOfBatch, ::sourceOf)
        )

        // 批外补一张截图 → 可以提示
        val withOutOfBatch = all + photo(50, "DCIM/Screenshots/")
        val eligibleWithOutOfBatch = RoundCandidateRules.eligibleForNextBatch(
            allImages = withOutOfBatch, currentBatch = batch
        )
        assertEquals(
            "确有批外候选时可以提示",
            screenshots,
            RoundIntentRules.shouldSuggest(s, eligibleWithOutOfBatch, ::sourceOf)
        )
    }

    // ============ 模式隔离 ============

    @Test
    fun modeSwitchClearsStatsAndAcceptedMarker() {
        val s = RoundSessionRules.markAccepted(
            session((1L..4L).map { photo(it, "DCIM/Screenshots/") }),
            screenshots
        )
        assertEquals(screenshots, s.acceptedThisRound)

        val isolated = RoundSessionRules.isolateForModeSwitch(s)
        assertTrue("切换模式要清空统计", isolated.isEmpty())
        assertTrue("已完成批次也清空", isolated.completedBatches.isEmpty())
        assertNull("本轮已接受标记也清空", isolated.acceptedThisRound)
    }

    @Test
    fun endSessionClearsEverything() {
        val s = RoundSessionRules.markAccepted(
            session((1L..4L).map { photo(it, "DCIM/Screenshots/") }),
            screenshots
        )
        val ended = RoundSessionRules.endSession()
        assertTrue(ended.isEmpty())
        assertTrue(ended.completedBatches.isEmpty())
        assertNull(ended.acceptedThisRound)
        assertTrue("原来那份确实有内容", s.currentBatch.isNotEmpty())
    }

    // ============ 重启 ============

    @Test
    fun intentDoesNotSurviveProcessRestart() {
        val afterRestart = RoundIntentRules.clear()
        assertFalse(afterRestart.isActive)
        assertNull(afterRestart.sourceKey)

        val available = library()
        val batch = RoundIntentRules.sampleWithIntent(
            available, 6, afterRestart,
            SourceBalanceRules.Strategy.UNIFORM, Random(1)
        )
        assertEquals(
            SourceBalanceRules.uniformSample(available, 6, Random(1)).map { it.mediaIdentityKey() },
            batch.map { it.mediaIdentityKey() }
        )
    }

    // ============ 显示名 ============

    @Test
    fun displayNameUsesLastPathSegment() {
        assertEquals("Screenshots", StagedSourceDisplayName.of(screenshots))
        assertEquals("Camera", StagedSourceDisplayName.of(camera))
    }

    @Test
    fun displayNameFallsBackToVolumeWhenPathIsEmpty() {
        assertEquals("external_primary", StagedSourceDisplayName.of("external_primary|"))
    }

    @Test
    fun displayNameHandlesMalformedKeys() {
        assertEquals("weird", StagedSourceDisplayName.of("weird"))
        assertEquals("相册", StagedSourceDisplayName.of(""))
    }
}
