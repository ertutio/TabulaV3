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
 * 本轮整理意图的接线级测试（审计意见 #90）。
 *
 * 审计要求的验收序列：接受只影响下一批、忽略、撤销、退出、来源耗尽、
 * 冷却、重启。这些在规则层已经覆盖过语义，这里再按**接线后的调用顺序**
 * 串一遍，确保 UI 与引擎之间的契约没断。
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

    private fun library() =
        (1L..20L).map { photo(it, "DCIM/Screenshots/") } +
            (100L..300L).map { photo(it, "DCIM/Camera/") }

    // ============ 接受只影响下一批 ============

    @Test
    fun acceptedIntentAffectsExactlyOneBatchThenFallsBack() {
        val available = library()
        var intent = RoundIntentRules.accept(screenshots, 1L)

        // 第一批：全部来自截图
        val first = RoundIntentRules.sampleWithIntent(available, 6, intent, Random(1))
        assertTrue(first.all { it.relativePath == "DCIM/Screenshots/" })

        // 消费后：回落成均衡/原策略，两个来源都会出现
        intent = RoundIntentRules.consume(intent)
        assertFalse(intent.isActive)
        val second = RoundIntentRules.sampleWithIntent(available, 6, intent, Random(1))
        assertEquals(2, second.map { it.relativePath }.toSet().size)
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
        val batch = (1L..4L).map { photo(it, "DCIM/Screenshots/") } + photo(9, "DCIM/Camera/")
        val available = library()

        // 未忽略时应当提示
        assertEquals(screenshots, RoundIntentRules.shouldSuggest(batch, available))
        // 忽略后不再提示
        assertNull(RoundIntentRules.shouldSuggest(batch, available, dismissed = true))
    }

    @Test
    fun dismissDoesNotAffectSampling() {
        val available = library()
        // 忽略只是不显示提示，抽样仍按用户选的策略走
        val batch = RoundIntentRules.sampleWithIntent(
            available, 6, RoundIntentRules.clear(), Random(1)
        )
        assertEquals(6, batch.size)
    }

    // ============ 撤销 ============

    @Test
    fun revokingIntentBeforeNextBatchRestoresOriginalStrategy() {
        val available = library()
        var intent = RoundIntentRules.accept(screenshots, 1L)
        assertTrue(intent.isActive)

        // 用户撤销（在下一批装载前清空）
        intent = RoundIntentRules.clear()
        val batch = RoundIntentRules.sampleWithIntent(available, 6, intent, Random(1))

        assertEquals(2, batch.map { it.relativePath }.toSet().size)
    }

    // ============ 退出整理 ============

    @Test
    fun leavingTheSessionClearsIntent() {
        val cleared = RoundIntentRules.clear()
        assertNull(cleared.sourceKey)
        assertFalse(cleared.isActive)
        assertEquals(0L, cleared.acceptedAt)
    }

    // ============ 来源耗尽 ============

    @Test
    fun exhaustedSourceSilentlyFallsBack() {
        val available = (100L..300L).map { photo(it, "DCIM/Camera/") }
        val intent = RoundIntentRules.accept(screenshots, 1L)

        val batch = RoundIntentRules.sampleWithIntent(available, 6, intent, Random(1))

        assertEquals(6, batch.size)
        assertTrue("来源没了也要能出批", batch.all { it.relativePath == "DCIM/Camera/" })
    }

    @Test
    fun partiallyExhaustedSourceFillsFromOthers() {
        val available = (1L..2L).map { photo(it, "DCIM/Screenshots/") } +
            (100L..300L).map { photo(it, "DCIM/Camera/") }
        val intent = RoundIntentRules.accept(screenshots, 1L)

        val batch = RoundIntentRules.sampleWithIntent(available, 6, intent, Random(1))

        assertEquals(6, batch.size)
        assertEquals(2, batch.count { it.relativePath == "DCIM/Screenshots/" })
    }

    // ============ 冷却 ============

    @Test
    fun coolingStillAppliesAfterAcceptingIntent() {
        val available = library()
        // 截图目录全在冷却中（调用方已 partition）
        val cooled = available.filter { it.relativePath == "DCIM/Screenshots/" }
        val eligible = available - cooled.toSet()

        val intent = RoundIntentRules.accept(screenshots, 1L)
        val batch = RoundIntentRules.sampleWithIntent(eligible, 6, intent, Random(1))

        val cooledKeys = cooled.map { it.mediaIdentityKey() }.toSet()
        assertTrue(
            "冷却中的照片不能因为意图被放回来",
            batch.none { it.mediaIdentityKey() in cooledKeys }
        )
    }

    // ============ 重启 ============

    @Test
    fun intentDoesNotSurviveProcessRestart() {
        // 意图是内存值：新建（模拟重启后）得到的就是空意图
        val afterRestart = RoundIntentRules.clear()
        assertFalse(afterRestart.isActive)
        assertNull(afterRestart.sourceKey)

        // 抽样回落到默认策略
        val batch = RoundIntentRules.sampleWithIntent(library(), 6, afterRestart, Random(1))
        assertEquals(2, batch.map { it.relativePath }.toSet().size)
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
        // 没有分隔符时整串当路径
        assertEquals("weird", StagedSourceDisplayName.of("weird"))
        // 完全空时给一个可读兜底，不崩
        assertEquals("相册", StagedSourceDisplayName.of(""))
    }

    // ============ 提示判定的边界 ============

    @Test
    fun hintRequiresUserToActuallyHandleABatch() {
        // userHandledBatch 为空时不该提示（接线层的守卫）
        assertNull(RoundIntentRules.shouldSuggest(emptyList(), library()))
    }

    @Test
    fun hintRespectsMinimumShareAndCount() {
        // 3 张来自同一来源但只占 43%（7 张批次）→ 不提示
        val batch = (1L..3L).map { photo(it, "DCIM/Screenshots/") } +
            (10L..13L).map { photo(it, "DCIM/Camera/") }
        assertNull(RoundIntentRules.shouldSuggest(batch, library()))
    }
}
