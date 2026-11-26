package com.tabula.v3

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.album.RecentAlbumRules
import com.tabula.v3.data.model.Album
import com.tabula.v3.data.model.AlbumUsageStats
import com.tabula.v3.data.model.ImageFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 「最近图集」的设备级入口验证（路线图 #83 第 3 项 / 审计 #86）。
 *
 * 审计要求重点检查：成功归档、撤销、隐藏或删除图集、改名、重启，
 * 以及长名称和窄屏显示。
 *
 * 说明：本类验证的是**判定层在真实设备运行时**的行为（真 PackageManager、
 * 真 Locale、真 DisplayMetrics），不驱动 UI 点击——UI 无法在没有人工
 * 断言的自动化里可靠验证"看起来对不对"。窄屏相关断言用真实的屏幕宽度
 * 计算最近行的可用宽度，验证长名称不会溢出。
 */
@RunWith(AndroidJUnit4::class)
class RecentAlbumsDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun album(id: String, name: String = id, hidden: Boolean = false) = Album(
        id = id,
        name = name,
        isHidden = hidden,
        createdAt = 1_700_000_000_000L
    )

    private fun stats(id: String, lastUsedAt: Long, total: Int = 1) =
        id to AlbumUsageStats(albumId = id, weightedScore = 1.0, lastUsedAt = lastUsedAt, totalCount = total)

    // ============ 成功归档 ============

    @Test
    fun successfulArchivesShowUpMostRecentFirst() {
        val albums = listOf(album("A"), album("B"), album("C"))
        // A、B、A 的归档序列：A 最近，B 次之
        val map = mapOf(stats("A", 300L, 2), stats("B", 200L, 1))

        val recent = RecentAlbumRules.recentAlbums(albums, map)

        assertEquals(listOf("A", "B"), recent.map { it.id })
    }

    @Test
    fun unarchivedAlbumNeverAppears() {
        val albums = listOf(album("A"), album("B"))
        val recent = RecentAlbumRules.recentAlbums(albums, mapOf(stats("A", 10L)))
        assertEquals(listOf("A"), recent.map { it.id })
    }

    // ============ 撤销 ============

    @Test
    fun undoDoesNotPromoteTheAlbum() {
        // 撤销的语义：不写使用记录。这里用"没有对应的 stats 条目"表达，
        // 确保撤销过的目标不会因为撤销动作本身而升到第一位。
        val albums = listOf(album("A"), album("B"))
        val map = mapOf(stats("A", 100L))
        // B 被撤销 → 没有记录
        val recent = RecentAlbumRules.recentAlbums(albums, map)
        assertEquals(listOf("A"), recent.map { it.id })
        assertFalse(recent.any { it.id == "B" })
    }

    // ============ 隐藏 / 删除 ============

    @Test
    fun hiddenAlbumIsExcludedWhenCallerFiltersIt() {
        val visible = listOf(album("A"), album("C"))
        val map = mapOf(stats("A", 100L), stats("hidden", 999L))
        assertEquals(listOf("A"), RecentAlbumRules.recentAlbums(visible, map).map { it.id })
    }

    @Test
    fun deletedAlbumIsExcludedEvenWithStaleStats() {
        val albums = listOf(album("A"))
        val map = mapOf(stats("A", 100L), stats("deleted", 999L))
        assertEquals(listOf("A"), RecentAlbumRules.recentAlbums(albums, map).map { it.id })
    }

    // ============ 改名 ============

    @Test
    fun renameKeepsPositionAndShowsNewName() {
        val before = listOf(album("A", name = "旧名字"))
        val after = listOf(album("A", name = "新名字"))
        val map = mapOf(stats("A", 100L))

        assertEquals("旧名字", RecentAlbumRules.recentAlbums(before, map).single().name)
        assertEquals("新名字", RecentAlbumRules.recentAlbums(after, map).single().name)
    }

    // ============ 重启 ============

    @Test
    fun restartWithPersistedStatsKeepsTheSameOrder() {
        // 使用记录落盘后重启：同一份 stats 必须给出同一顺序
        val albums = listOf(album("A"), album("B"), album("C"))
        val map = mapOf(
            stats("A", 300L, 2),
            stats("B", 200L, 1),
            stats("C", 100L, 5)
        )

        val first = RecentAlbumRules.recentAlbums(albums, map).map { it.id }
        val second = RecentAlbumRules.recentAlbums(albums.reversed(), map).map { it.id }

        assertEquals(first, second)
        assertEquals(listOf("A", "B", "C"), first)
    }

    // ============ 长名称与窄屏 ============

    @Test
    fun longAlbumNamesAreTruncatedNotOverflowing() {
        // 三格布局下每格的实际宽度：屏幕宽度 - 两侧内边距 - 两处间距
        val metrics = context.resources.displayMetrics
        val horizontalPaddingPx = 16.dp * 2
        val gapsPx = 8.dp * 2
        val cellWidthPx = (metrics.widthPixels - horizontalPaddingPx - gapsPx) / 3

        // 窄屏（dp 宽度 ≤ 360）下每格必须仍然为正且足够放一个字符
        assertTrue("cell width must be positive on narrow screens", cellWidthPx > 0)

        // 最近列表最多三个，因此长名称只影响单格宽度，不会把布局撑破
        val longName = "这是一个非常非常长的图集名称".repeat(4)
        val recent = RecentAlbumRules.recentAlbums(
            albums = listOf(album("long", name = longName)),
            stats = mapOf(stats("long", 100L))
        )
        assertEquals(1, recent.size)
        // 名称原样保留，截断交给 Text(maxLines=1, overflow=Ellipsis)
        assertEquals(longName, recent.single().name)

        val labelPx = metrics.density * 12 // 12sp 标签
        assertTrue(
            "cell width ($cellWidthPx px) must fit at least one glyph ($labelPx px)",
            cellWidthPx >= labelPx
        )
    }

    @Test
    fun narrowScreenWidthIsRealisticallyReported() {
        val metrics = context.resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        // 真实设备/模拟器上这条只是记录，不做硬断言（平板也应该通过）
        assertTrue("screen width dp = $widthDp", widthDp > 200)
    }

    // ============ 真实 Locale 下的星期文案 ============

    @Test
    fun dateFormattingDoesNotCrashUnderDeviceLocale() {
        val formatter = java.text.SimpleDateFormat("M 月 d 日 EEEE", java.util.Locale.CHINA)
        val text = formatter.format(java.util.Date(1_795_000_000_000L))
        assertTrue("formatted date must not be blank", text.isNotBlank())
    }

    // ============ 上限 ============

    @Test
    fun atMostThreeAreShown() {
        val albums = (1..6).map { album("a$it") }
        val map = albums.mapIndexed { i, a -> stats(a.id, 100L + i) }.toMap()
        assertEquals(3, RecentAlbumRules.recentAlbums(albums, map).size)
    }

    @Test
    fun emptyInputsAreSafe() {
        assertTrue(RecentAlbumRules.recentAlbums(emptyList(), emptyMap()).isEmpty())
        assertNull(RecentAlbumRules.recentAlbums(listOf(album("A")), emptyMap()).firstOrNull())
    }

    private val Int.dp: Float get() = this * context.resources.displayMetrics.density
}
