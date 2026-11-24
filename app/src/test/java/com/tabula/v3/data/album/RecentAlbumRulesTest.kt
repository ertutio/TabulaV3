package com.tabula.v3.data.album

import com.tabula.v3.data.model.Album
import com.tabula.v3.data.model.AlbumUsageStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「最近图集」规则测试（路线图 #83 第 3 项）。
 *
 * 路线图给的验收序列是 A、B、A，期望顺序为 A、B；再验证改名、隐藏和重启。
 * 下面是逐条对应。
 */
class RecentAlbumRulesTest {

    private fun album(id: String, name: String = id, hidden: Boolean = false) = Album(
        id = id,
        name = name,
        isHidden = hidden
    )

    private fun stats(id: String, lastUsedAt: Long, total: Int = 1) =
        id to AlbumUsageStats(
            albumId = id,
            weightedScore = 1.0,
            lastUsedAt = lastUsedAt,
            totalCount = total
        )

    @Test
    fun sequenceABAyieldsAThenB() {
        // A、B、A：A 的最近使用时间最新，B 次之
        val albums = listOf(album("A"), album("B"))
        val map = mapOf(stats("A", lastUsedAt = 300L), stats("B", lastUsedAt = 200L))

        val recent = RecentAlbumRules.recentAlbums(albums, map)

        assertEquals(listOf("A", "B"), recent.map { it.id })
    }

    @Test
    fun atMostThreeAreReturned() {
        val albums = (1..5).map { album("a$it") }
        val map = albums.mapIndexed { index, a -> stats(a.id, lastUsedAt = 100L + index) }.toMap()

        val recent = RecentAlbumRules.recentAlbums(albums, map)

        assertEquals(3, recent.size)
        // 最新的三个：a5, a4, a3
        assertEquals(listOf("a5", "a4", "a3"), recent.map { it.id })
    }

    @Test
    fun fewerThanThreeIsShownAsIs() {
        val albums = listOf(album("A"))
        val recent = RecentAlbumRules.recentAlbums(albums, mapOf(stats("A", 10L)))
        assertEquals(listOf("A"), recent.map { it.id })
    }

    @Test
    fun deletedAlbumIsExcluded() {
        val albums = listOf(album("A"))
        // 使用记录里还有 B，但 B 已不在当前图集列表（被删除）
        val map = mapOf(stats("A", 10L), stats("B", 99L))

        val recent = RecentAlbumRules.recentAlbums(albums, map)

        assertEquals(listOf("A"), recent.map { it.id })
    }

    @Test
    fun hiddenAlbumIsExcludedBecauseCallerFiltersThemOut() {
        // 调用方传入的是已过滤隐藏项的列表；这里确认规则不会把不在列表里的捞回来
        val visible = listOf(album("A"))
        val map = mapOf(stats("A", 10L), stats("hidden", 99L))

        assertEquals(listOf("A"), RecentAlbumRules.recentAlbums(visible, map).map { it.id })
    }

    @Test
    fun albumWithNoUsageNeverAppears() {
        val albums = listOf(album("A"), album("B"))
        val map = mapOf(stats("A", 10L))

        val recent = RecentAlbumRules.recentAlbums(albums, map)

        assertEquals(listOf("A"), recent.map { it.id })
        assertTrue(recent.none { it.id == "B" })
    }

    @Test
    fun zeroTimestampIsNotTreatedAsRecentlyUsed() {
        val albums = listOf(album("A"), album("B"))
        val map = mapOf(
            stats("A", lastUsedAt = 0L),
            stats("B", lastUsedAt = 50L)
        )

        assertEquals(listOf("B"), RecentAlbumRules.recentAlbums(albums, map).map { it.id })
    }

    @Test
    fun renamingKeepsTheSameAlbumInPlace() {
        // 图集 ID 不变、名称改变：最近列表跟着 ID 走，显示新名字
        val before = listOf(album("A", name = "旧名字"))
        val after = listOf(album("A", name = "新名字"))
        val map = mapOf(stats("A", 10L))

        assertEquals("旧名字", RecentAlbumRules.recentAlbums(before, map).single().name)
        assertEquals("新名字", RecentAlbumRules.recentAlbums(after, map).single().name)
    }

    @Test
    fun emptyInputsProduceEmptyResult() {
        assertTrue(RecentAlbumRules.recentAlbums(emptyList(), emptyMap()).isEmpty())
        assertTrue(RecentAlbumRules.recentAlbums(listOf(album("A")), emptyMap()).isEmpty())
        assertTrue(RecentAlbumRules.recentAlbums(listOf(album("A")), mapOf(stats("A", 1L)), limit = 0).isEmpty())
    }

    @Test
    fun tiesAreBrokenDeterministically() {
        // 同一时间戳：按总次数降序，再按 albumId 升序，保证多次运行结果一致
        val albums = listOf(album("B"), album("A"), album("C"))
        val map = mapOf(
            stats("A", lastUsedAt = 100L, total = 1),
            stats("B", lastUsedAt = 100L, total = 5),
            stats("C", lastUsedAt = 100L, total = 1)
        )

        val first = RecentAlbumRules.recentAlbums(albums, map).map { it.id }
        val second = RecentAlbumRules.recentAlbums(albums.reversed(), map).map { it.id }

        assertEquals(first, second)
        // B 次数最多排第一；A、C 次数相同按 id 升序
        assertEquals(listOf("B", "A", "C"), first)
    }

    @Test
    fun recentAlbumIdsDedupesAndSortsByLatestUse() {
        val existing = setOf("A", "B", "C")
        val usages = listOf(
            "A" to 100L,
            "B" to 200L,
            "A" to 300L,   // A 后来又用过一次
            "gone" to 999L  // 已不存在的图集
        )

        val ids = RecentAlbumRules.recentAlbumIds(usages, existing)

        assertEquals(listOf("A", "B"), ids)
    }

    @Test
    fun recentAlbumIdsRespectsLimit() {
        val existing = setOf("A", "B", "C", "D")
        val usages = listOf("A" to 1L, "B" to 2L, "C" to 3L, "D" to 4L)

        assertEquals(listOf("D", "C"), RecentAlbumRules.recentAlbumIds(usages, existing, limit = 2))
    }
}
