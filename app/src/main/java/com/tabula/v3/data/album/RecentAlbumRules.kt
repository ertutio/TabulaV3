package com.tabula.v3.data.album

import com.tabula.v3.data.model.Album
import com.tabula.v3.data.model.AlbumUsageStats

/**
 * 归档时的「最近图集」规则（路线图 #83 第 3 项）。
 *
 * 目标：下滑归档时，把最近**成功**归档过的三个不同目标放到列表顶部，
 * 一键沿用手势原有的归档操作，不用每次在长列表里找。完整列表保持原样，
 * 手动排序仍然有效。
 *
 * 纯逻辑，不碰 SharedPreferences / Compose，便于逐条钉住这些边界：
 * - 只统计**成功**归档：打开弹层、失败、撤销都不算（调用方只在这些点
 *   之外调用 recordUsage，本文件按传入的记录计算）。
 * - 同名/重复目标去重：A、B、A 的序列给出 A、B。
 * - 已删除、已隐藏的图集不进最近列表。
 * - 最近列表里不足 [MAX_RECENT] 个时不补齐、不显示空位。
 */
object RecentAlbumRules {

    /** 顶部最多显示几个最近图集（路线图写死三个）。 */
    const val MAX_RECENT = 3

    /**
     * 从使用统计里挑出最近归档过的图集。
     *
     * @param albums 当前可见图集（调用方已按需过滤隐藏）
     * @param stats 每个图集的使用统计（来自 [com.tabula.v3.data.repository.AlbumUsageTracker]）
     * @param limit 最多几个，默认 [MAX_RECENT]
     */
    fun recentAlbums(
        albums: List<Album>,
        stats: Map<String, AlbumUsageStats>,
        limit: Int = MAX_RECENT
    ): List<Album> {
        if (limit <= 0) return emptyList()
        val byId = albums.associateBy { it.id }
        return stats.values
            // 只认还在当前列表里的图集：删除的、隐藏的自然被排除
            .filter { it.lastUsedAt > 0L && it.totalCount > 0 && it.albumId in byId }
            // 最近用过的在前；同一时刻按总次数、再按 id 保证顺序稳定
            .sortedWith(
                compareByDescending<AlbumUsageStats> { it.lastUsedAt }
                    .thenByDescending { it.totalCount }
                    .thenBy { it.albumId }
            )
            // 同一图集只出现一次
            .distinctBy { it.albumId }
            .take(limit)
            .mapNotNull { byId[it.albumId] }
    }

    /**
     * 从原始使用记录里挑最近目标（供没有现成 [AlbumUsageStats] 的调用点使用）。
     *
     * 只保留每个图集的最后一条时间戳，再按时间倒序取前 [limit] 个。
     * 记录里出现但当前已不存在的图集 ID 直接丢弃。
     *
     * @param recentUsages 形如 `albumId to timestamp` 的记录（顺序无关，本函数会排序）
     */
    fun recentAlbumIds(
        recentUsages: List<Pair<String, Long>>,
        existingAlbumIds: Set<String>,
        limit: Int = MAX_RECENT
    ): List<String> {
        if (limit <= 0) return emptyList()
        return recentUsages
            .filter { it.first in existingAlbumIds && it.second > 0L }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, stamps) -> stamps.max() }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }
            )
            .take(limit)
            .map { it.key }
    }
}
