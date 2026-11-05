package com.tabula.v3.ui.util

import android.content.Context
import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.di.cardLoadSize
import com.tabula.v3.di.cardMemoryCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T06 登记项（DeckScreen 清理预取裸键）：清理模式"下一批"预取请求必须与
 * 卡片显示/预载共用同一身份键与解码规格——cardMemoryCacheKey（卷|ID +
 * DATE_MODIFIED 内容版本）+ cardLoadSize（720px 上限）。
 *
 * 目标契约：预取写入的键正是下一批卡片显示请求读取的键，预取才有意义；
 * 键含内容版本（编辑后预取自然失效）与卷（跨卷同数值 ID 不串图）。
 * 基线行为（verbatim 提取保留）：裸 URI 字符串键无人读取、512×512 与
 * 卡片规格不符——规格断言在修复提交前为 FAIL（基线证据）。
 * 请求构造器的 data/键接线按规格结构体核对；尺寸契约钉在规格结构体上
 * （Coil 2.7 的 SizeResolver 仅暴露 suspend 解析，无法同步读回）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CleanupPrefetchRequestSpecTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun row(volume: String, id: Long, dateModifiedMs: Long, width: Int = 540, height: Int = 960) = ImageFile(
        id = id,
        uri = Uri.parse("content://media/$volume/images/media/$id"),
        displayName = "img-$volume-$id.jpg",
        dateModified = dateModifiedMs,
        size = 100L,
        width = width,
        height = height,
        bucketDisplayName = "Camera",
        volumeName = volume
    )

    @Test
    fun prefetchMemoryCacheKeyMatchesCardDisplayKey() {
        val image = row("external_primary", 10L, 1_000L)

        assertEquals(
            "预取键必须与卡片显示键（cardMemoryCacheKey）一致，否则预取结果不被命中",
            cardMemoryCacheKey(image), deckCleanupPrefetchSpec(image).memoryCacheKey
        )
    }

    @Test
    fun prefetchKeyChangesWithContentVersionAndVolume() {
        val before = deckCleanupPrefetchSpec(row("external_primary", 10L, 1_000L))
        val afterEdit = deckCleanupPrefetchSpec(row("external_primary", 10L, 9_999L))
        val otherVolume = deckCleanupPrefetchSpec(row("external_secondary", 10L, 1_000L))

        assertNotEquals(before.memoryCacheKey, afterEdit.memoryCacheKey)
        assertNotEquals(before.memoryCacheKey, otherVolume.memoryCacheKey)
    }

    @Test
    fun prefetchDiskCacheKeyMatchesCardKey() {
        val image = row("external_primary", 10L, 1_000L)

        assertEquals(cardMemoryCacheKey(image), deckCleanupPrefetchSpec(image).diskCacheKey)
    }

    @Test
    fun prefetchSizeMatchesCardLoadSize() {
        val image = row("external_primary", 10L, 1_000L)

        assertEquals(
            "预取解码规格必须与卡片共享键的既有规格一致（显式键命中不校验尺寸）",
            cardLoadSize(image), deckCleanupPrefetchSpec(image).size
        )
    }

    @Test
    fun requestBuilderWiresSpecKeysIntoRequest() {
        val image = row("external_primary", 10L, 1_000L)
        val spec = deckCleanupPrefetchSpec(image)

        val request = deckCleanupPrefetchRequest(context, image)

        assertEquals(spec.data, request.data)
        assertEquals(spec.memoryCacheKey, request.memoryCacheKey?.key)
        assertEquals(spec.diskCacheKey, request.diskCacheKey)
        // "card_" 键条目统一非硬件位图（PreloadingManager 同款规格）
        assertEquals(false, request.allowHardware)
    }
}
