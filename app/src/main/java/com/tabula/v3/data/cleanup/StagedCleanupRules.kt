package com.tabula.v3.data.cleanup

import com.tabula.v3.data.model.ImageFile
import java.util.Calendar
import java.util.TimeZone

/**
 * 暂存清理候选规则（路线图第 1 项：先清已经堆起来的截图和下载图）。
 *
 * 分工：
 * - 本文件只做"哪些照片进候选、怎么分组、按什么顺序"的判定，不碰 MediaStore、
 *   不碰回收站、不碰 UI——这样规则本身可以被 JVM 单测逐条钉住。
 * - 年龄比较一律用**存入时间**（DATE_ADDED），不用修改时间：截图和下载图
 *   存进来之后基本不再被改写，修改时间反而会被系统或编辑器刷新；而没有
 *   DATE_ADDED 的行（0）**不参与年龄门限**，单列"时间未知"供手动查看。
 * - 一切分组与门限都以"用户本地的自然日"为准（默认时区，可注入以便测试）。
 */

/** 暂存清理的来源身份：卷 + 相对路径（T03 口径）。显示名只用于文案。 */
data class StagedSourceKey(
    val volumeName: String,
    val relativePath: String
)

/**
 * 一个可选来源（截图、下载、某个聊天软件保存目录……）。
 *
 * @param key 身份（卷 + 相对路径），跨卷同名目录不会合并
 * @param displayName 用户可读名称（取路径末段，空白回退整条路径）
 * @param imageCount 该来源下**当前可清理**的照片数（已扣除保留/已回收）
 * @param oldestAddedAt 该来源下最早一张的存入时间（毫秒），0 表示全部未知
 */
data class StagedCleanupSource(
    val key: StagedSourceKey,
    val displayName: String,
    val imageCount: Int,
    val oldestAddedAt: Long
)

/**
 * 一天的候选分组。按存入日期倒序排列，最近的在前。
 *
 * @param dayStartMillis 当天 00:00:00.000（本地时区）
 * @param images 当天存入的照片，按存入时间倒序
 */
data class StagedCleanupDayGroup(
    val dayStartMillis: Long,
    val images: List<ImageFile>
)

/**
 * 暂存清理候选集合。
 *
 * @param selectedSources 用户本次勾选的来源（按路径排序，仅用于展示）
 * @param recentGroups 未超过保留天数的分组（默认界面折叠，可展开）
 * @param oldGroups 已超过保留天数的分组（默认要清理的目标）
 * @param unknownTimeImages 存入时间未知的照片——不参与年龄判定，单列
 */
data class StagedCleanupSelection(
    val selectedSources: List<StagedCleanupSource>,
    val recentGroups: List<StagedCleanupDayGroup>,
    val oldGroups: List<StagedCleanupDayGroup>,
    val unknownTimeImages: List<ImageFile>
) {
    /** 超过保留天数、可直接进入确认页的照片（按分组顺序摊平）。 */
    val oldImages: List<ImageFile> get() = oldGroups.flatMap { it.images }

    /** 本次候选里全部照片（含未到期与时间未知），用于"全选可见项"。 */
    val allCandidateImages: List<ImageFile>
        get() = oldGroups.flatMap { it.images } + recentGroups.flatMap { it.images } + unknownTimeImages

    val isEmpty: Boolean get() = allCandidateImages.isEmpty()
}

object StagedCleanupRules {

    /** 默认保留天数：存入超过七天才进入"待清"（路线图写死的门槛）。 */
    const val DEFAULT_RETENTION_DAYS = 7

    /**
     * 从全部照片里挑出属于 [sources] 的候选，并按存入日期分组。
     *
     * 判定顺序（每一步都可以单独解释给用户）：
     * 1. 来源匹配：卷 + 相对路径完全一致才算同一个来源；卷未知的行只在
     *    该来源也未知卷时才匹配（不做跨卷猜测）。
     * 2. 排除已长期保留（[retainedIdentityKeys]）、本次跳过（[skippedIdentityKeys]）
     *    和已在回收站（[recycledIdentityKeys]）的行——身份键为 `卷|ID`。
     * 3. 存入时间 > 0 且早于 (今天 00:00 - retentionDays) 的进 [oldGroups]，
     *    其余已知时间的进 [recentGroups]，0 的进 [unknownTimeImages]。
     * 4. 两个分组都按日期倒序；组内按存入时间倒序、同时间按 ID 倒序。
     *
     * @param nowMillis "现在"（毫秒），用于计算今天与保留边界；测试可注入
     * @param timeZone 用于切自然日的时区
     * @param retentionDays 保留天数，默认 [DEFAULT_RETENTION_DAYS]
     */
    fun select(
        allImages: List<ImageFile>,
        sources: List<StagedSourceKey>,
        retainedIdentityKeys: Set<String> = emptySet(),
        skippedIdentityKeys: Set<String> = emptySet(),
        recycledIdentityKeys: Set<String> = emptySet(),
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
        retentionDays: Int = DEFAULT_RETENTION_DAYS
    ): StagedCleanupSelection {
        val sourceSet = sources.map { normalize(it) }.toSet()
        val cutoff = retentionCutoffMillis(nowMillis, retentionDays, timeZone)

        val matched = allImages.filter { image ->
            val key = stagedSourceKeyOf(image) ?: return@filter false
            if (normalize(key) !in sourceSet) return@filter false
            val identity = cleanupIdentityKey(image)
            identity !in retainedIdentityKeys &&
                identity !in skippedIdentityKeys &&
                identity !in recycledIdentityKeys
        }

        val (known, unknown) = matched.partition { it.dateAdded > 0L }
        val (old, recent) = known.partition { it.dateAdded < cutoff }

        return StagedCleanupSelection(
            selectedSources = summarizeSources(matched, sources),
            recentGroups = groupByDay(recent, timeZone),
            oldGroups = groupByDay(old, timeZone),
            unknownTimeImages = unknown.sortedWith(IMAGE_ORDER)
        )
    }

    /**
     * 从全部照片里列出可选来源，供用户首次进入时勾选。
     *
     * 只列"看起来像暂存"的来源：截图目录、下载目录，以及调用方显式给出的
     * 额外路径（如聊天软件的保存目录）。**不**把相机、Tabula 图集等列进来，
     * 避免用户误把日常照片当垃圾清掉。
     *
     * 排序：照片多的在前，同数量按路径升序，保证跨设备结果稳定。
     */
    fun availableSources(allImages: List<ImageFile>): List<StagedCleanupSource> {
        val buckets = linkedMapOf<StagedSourceKey, MutableList<ImageFile>>()
        allImages.forEach { image ->
            val key = stagedSourceKeyOf(image) ?: return@forEach
            if (!isStagedSourcePath(key.relativePath)) return@forEach
            buckets.getOrPut(normalize(key)) { mutableListOf() }.add(image)
        }
        return summarizeSources(buckets.values.flatten(), buckets.keys.toList())
    }

    /**
     * 判断一个相对路径是否属于"暂存"类来源。
     *
     * 认三种布局：
     * - 顶层下载目录：`Download/`、`Downloads/`（Android 的下载目录就在存储根）
     * - `Pictures/` 或 `DCIM/` 下任一层出现 screenshots / download / downloads，
     *   覆盖 `DCIM/Screenshots/`、`Pictures/Screenshots/` 这类各家 ROM 的习惯
     *
     * 大小写不敏感，尾部斜杠归一。相机、Tabula 图集等一律不认。
     */
    fun isStagedSourcePath(relativePath: String): Boolean {
        val normalized = normalizePath(relativePath)
        if (normalized.isEmpty()) return false
        val segments = normalized.split('/')
        val top = segments.first()
        if (top == "download" || top == "downloads") return true
        if (top != "pictures" && top != "dcim") return false
        return segments.drop(1).any { it == "screenshots" || it == "download" || it == "downloads" }
    }

    /**
     * 保留边界：今天 00:00 再往前 [retentionDays] 天的 00:00。
     *
     * 用自然日而不是 now - 7×24h：用户理解的"七天前"是日历上的七天，
     * 不是被时区和夏令时改过长度的一段时间。
     */
    fun retentionCutoffMillis(
        nowMillis: Long,
        retentionDays: Int = DEFAULT_RETENTION_DAYS,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long {
        val calendar = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -retentionDays)
        }
        return calendar.timeInMillis
    }

    /** 分组用的一天起点（本地时区 00:00）。 */
    fun dayStartMillis(timestamp: Long, timeZone: TimeZone): Long {
        val calendar = Calendar.getInstance(timeZone).apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun groupByDay(
        images: List<ImageFile>,
        timeZone: TimeZone
    ): List<StagedCleanupDayGroup> =
        images
            .groupBy { dayStartMillis(it.dateAdded, timeZone) }
            .toSortedMap(reverseOrder())
            .map { (day, rows) -> StagedCleanupDayGroup(day, rows.sortedWith(IMAGE_ORDER)) }

    private fun summarizeSources(
        matched: List<ImageFile>,
        declared: List<StagedSourceKey>
    ): List<StagedCleanupSource> {
        // 归一键只用于匹配与去重；显示名一律取用户设备上的**原始**大小写
        // （"Screenshots" 而不是 "screenshots"），否则界面文案会被归一过程改写。
        // 先收 matched 行的原始键，再补 declare 里没出现的（计数为 0 的来源），
        // 这样已归一的小写键不会反过来覆盖真实路径。
        val originalByNormalized = linkedMapOf<StagedSourceKey, StagedSourceKey>()
        matched.mapNotNull { stagedSourceKeyOf(it) }.forEach { raw ->
            originalByNormalized.putIfAbsent(normalize(raw), raw)
        }
        declared.forEach { raw -> originalByNormalized.putIfAbsent(normalize(raw), raw) }

        val countsByNormalized = matched
            .mapNotNull { row -> stagedSourceKeyOf(row)?.let { normalize(it) } }
            .groupingBy { it }
            .eachCount()
        val oldestByNormalized = matched
            .mapNotNull { row ->
                val key = stagedSourceKeyOf(row)?.let { normalize(it) } ?: return@mapNotNull null
                if (row.dateAdded > 0L) key to row.dateAdded else null
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, stamps) -> stamps.min() }

        return originalByNormalized.map { (normalized, original) ->
            StagedCleanupSource(
                key = normalized,
                displayName = displayNameOf(original),
                imageCount = countsByNormalized[normalized] ?: 0,
                oldestAddedAt = oldestByNormalized[normalized] ?: 0L
            )
        }.sortedWith(compareByDescending<StagedCleanupSource> { it.imageCount }.thenBy { it.key.relativePath })
    }

    /** 来源显示名：路径末段；末段为空时回退整条路径，再空回退卷名。 */
    fun displayNameOf(key: StagedSourceKey): String {
        val trimmed = key.relativePath.trimEnd('/')
        val last = trimmed.substringAfterLast('/').ifBlank { trimmed }
        return last.ifBlank { key.volumeName }
    }

    /**
     * 照片的来源身份。相对路径缺失即视为不属于任何可选来源（返回 null）——
     * 拿不到路径就不该猜它是截图目录里的图。
     */
    fun stagedSourceKeyOf(image: ImageFile): StagedSourceKey? {
        val path = image.relativePath?.takeIf { it.isNotBlank() } ?: return null
        return StagedSourceKey(volumeName = image.volumeName.orEmpty(), relativePath = path)
    }

    fun normalize(key: StagedSourceKey): StagedSourceKey =
        StagedSourceKey(
            volumeName = key.volumeName.trim().lowercase(),
            relativePath = normalizePath(key.relativePath)
        )

    private fun normalizePath(path: String): String =
        path.replace('\\', '/').trim('/').lowercase()

    private val IMAGE_ORDER: Comparator<ImageFile> =
        compareByDescending<ImageFile> { it.dateAdded }.thenByDescending { it.id }
}

/**
 * 暂存清理的媒体身份键：`卷|ID`，与 UI 列表 key 同口径（[com.tabula.v3.data.repository.mediaIdentityKey]）。
 * 保留/跳过/已回收三个集合都按这个键存，避免跨卷同数值 ID 互相顶掉。
 */
fun cleanupIdentityKey(image: ImageFile): String = "${image.volumeName ?: ""}|${image.id}"
