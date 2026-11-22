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
 * 本轮整理意图测试（路线图 #83 第 7 项）。
 *
 * 路线图的验收：连续处理截图后接受、忽略、撤销和结束会话；
 * 接受只影响下一批；该来源不足时从其他合格候选补齐；下次打开恢复所选漫步模式。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RoundIntentRulesTest {

    private fun photo(id: Long, path: String, volume: String = "external_primary") = ImageFile(
        id = id,
        uri = Uri.parse("content://media/$volume/images/media/$id"),
        displayName = "p$id.jpg",
        dateModified = 1_700_000_000_000L + id,
        size = 1000L,
        width = 1080,
        height = 1440,
        bucketDisplayName = path.substringAfterLast('/').ifBlank { path },
        volumeName = volume,
        relativePath = path
    )

    private val screenshots = "external_primary|DCIM/Screenshots/"
    private val camera = "external_primary|DCIM/Camera/"

    // ==================== 提示判定 ====================

    @Test
    fun suggestsWhenBatchIsMostlyOneSource() {
        val batch = listOf(
            photo(1, "DCIM/Screenshots/"),
            photo(2, "DCIM/Screenshots/"),
            photo(3, "DCIM/Screenshots/"),
            photo(4, "DCIM/Camera/")
        )
        val available = batch + (10L..20L).map { photo(it, "DCIM/Screenshots/") }

        assertEquals(screenshots, RoundIntentRules.shouldSuggest(batch, available))
    }

    @Test
    fun doesNotSuggestForEvenlyMixedBatch() {
        val batch = listOf(
            photo(1, "DCIM/Screenshots/"),
            photo(2, "DCIM/Screenshots/"),
            photo(3, "DCIM/Camera/"),
            photo(4, "DCIM/Camera/")
        )
        val available = batch + (10L..20L).map { photo(it, "DCIM/Screenshots/") }

        assertNull(RoundIntentRules.shouldSuggest(batch, available))
    }

    @Test
    fun doesNotSuggestWhenTooFewFromOneSource() {
        // 只有 2 张来自同一来源，未达 MIN_SHARE_COUNT
        val batch = listOf(
            photo(1, "DCIM/Screenshots/"),
            photo(2, "DCIM/Screenshots/"),
            photo(3, "DCIM/Camera/"),
            photo(4, "DCIM/Camera/"),
            photo(5, "DCIM/Camera/")
        )
        val available = batch + (10L..20L).map { photo(it, "DCIM/Screenshots/") }

        assertNull(RoundIntentRules.shouldSuggest(batch, available))
    }

    @Test
    fun doesNotSuggestWhenSourceHasNoRemainingPhotos() {
        val batch = listOf(
            photo(1, "DCIM/Screenshots/"),
            photo(2, "DCIM/Screenshots/"),
            photo(3, "DCIM/Screenshots/")
        )
        // 候选池里就这三张，没有剩余
        assertNull(RoundIntentRules.shouldSuggest(batch, batch))
    }

    @Test
    fun dismissedSuppressesTheSuggestion() {
        val batch = listOf(
            photo(1, "DCIM/Screenshots/"),
            photo(2, "DCIM/Screenshots/"),
            photo(3, "DCIM/Screenshots/")
        )
        val available = batch + (10L..20L).map { photo(it, "DCIM/Screenshots/") }

        assertNull(RoundIntentRules.shouldSuggest(batch, available, dismissed = true))
    }

    @Test
    fun emptyBatchNeverSuggests() {
        assertNull(RoundIntentRules.shouldSuggest(emptyList(), emptyList()))
    }

    // ==================== 抽样 ====================

    @Test
    fun acceptedIntentFillsBatchFromThatSource() {
        val available = (1L..20L).map { photo(it, "DCIM/Screenshots/") } +
            (100L..200L).map { photo(it, "DCIM/Camera/") }

        val intent = RoundIntentRules.accept(screenshots, nowMillis = 1L)
        val batch = RoundIntentRules.sampleWithIntent(available, 6, intent, Random(1))

        assertEquals(6, batch.size)
        assertTrue(batch.all { it.relativePath == "DCIM/Screenshots/" })
    }

    @Test
    fun intentFallsBackToBalancedWhenSourceIsExhausted() {
        val available = (1L..2L).map { photo(it, "DCIM/Screenshots/") } +
            (100L..200L).map { photo(it, "DCIM/Camera/") }

        val intent = RoundIntentRules.accept(screenshots, nowMillis = 1L)
        val batch = RoundIntentRules.sampleWithIntent(available, 6, intent, Random(1))

        // 截图只有 2 张，其余从相机补齐，批次仍然满
        assertEquals(6, batch.size)
        assertEquals(2, batch.count { it.relativePath == "DCIM/Screenshots/" })
        assertTrue(batch.any { it.relativePath == "DCIM/Camera/" })
    }

    @Test
    fun intentOnMissingSourceFallsBackSilently() {
        val available = (100L..200L).map { photo(it, "DCIM/Camera/") }
        val intent = RoundIntentRules.accept(screenshots, nowMillis = 1L)

        val batch = RoundIntentRules.sampleWithIntent(available, 6, intent, Random(1))
        assertEquals(6, batch.size)
        assertTrue(batch.all { it.relativePath == "DCIM/Camera/" })
    }

    @Test
    fun noIntentUsesBalancedSampling() {
        val available = (1L..20L).map { photo(it, "DCIM/Screenshots/") } +
            (100L..200L).map { photo(it, "DCIM/Camera/") }

        val batch = RoundIntentRules.sampleWithIntent(
            available, 6, RoundIntentRules.clear(), Random(1)
        )

        // 均衡抽样会覆盖两个来源
        assertEquals(2, batch.map { it.relativePath }.toSet().size)
    }

    @Test
    fun intentBatchNeverRepeatsAnIdentity() {
        val available = (1L..10L).map { photo(it, "DCIM/Screenshots/") } +
            (1L..10L).map { photo(it, "DCIM/Screenshots/", volume = "1234-5678") }

        val intent = RoundIntentRules.accept(
            "external_primary|DCIM/Screenshots/", nowMillis = 1L
        )
        val batch = RoundIntentRules.sampleWithIntent(available, 10, intent, Random(7))

        val keys = batch.map { it.mediaIdentityKey() }
        assertEquals(keys.size, keys.toSet().size)
    }

    // ==================== 生命周期 ====================

    @Test
    fun intentIsActiveUntilConsumed() {
        val intent = RoundIntentRules.accept(screenshots, 1L)
        assertTrue(intent.isActive)

        val consumed = RoundIntentRules.consume(intent)
        assertFalse("只影响下一批", consumed.isActive)
        assertEquals(screenshots, consumed.sourceKey)
    }

    @Test
    fun consumedIntentNextSampleFallsBackToBalanced() {
        val available = (1L..20L).map { photo(it, "DCIM/Screenshots/") } +
            (100L..200L).map { photo(it, "DCIM/Camera/") }

        val consumed = RoundIntentRules.consume(
            RoundIntentRules.accept(screenshots, 1L)
        )
        val next = RoundIntentRules.sampleWithIntent(available, 6, consumed, Random(1))

        // 回落成均衡抽样：两个来源都出现
        assertEquals(2, next.map { it.relativePath }.toSet().size)
    }

    @Test
    fun clearReturnsToNoIntent() {
        val cleared = RoundIntentRules.clear()
        assertNull(cleared.sourceKey)
        assertFalse(cleared.isActive)
        assertEquals(0L, cleared.acceptedAt)
    }

    @Test
    fun intentIsSessionOnlyByConstruction() {
        // 本类型不带任何持久化字段；clear/consume 都在内存里产生新值。
        // 这条断言的意义是：如果有人给 Intent 加了持久化字段，这里会先炸。
        val intent = RoundIntentRules.accept(screenshots, 1L)
        assertEquals(
            listOf("sourceKey", "acceptedAt", "consumed"),
            intent.javaClass.declaredFields.map { it.name }.filterNot { it.contains('$') }
        )
    }
}
