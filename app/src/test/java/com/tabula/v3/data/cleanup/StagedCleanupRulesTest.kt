package com.tabula.v3.data.cleanup

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 暂存清理候选规则的 JVM 单测（路线图第 1 项）。
 *
 * 每条用例对应路线图里写的一个验收场景：先清已经堆起来的截图和下载图、
 * 按存入日期分组、超过七天是门槛、长期保留跨重启、仅本次跳过会再次出现、
 * 时间未知单列。全部在纯数据层判定，不碰 MediaStore / 权限 / 回收站。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class StagedCleanupRulesTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** 2026-11-23 12:00 UTC 作为"现在"。 */
    private val now = 1_795_435_200_000L

    private fun photo(
        id: Long,
        relativePath: String?,
        dateAdded: Long,
        volume: String? = "external_primary",
        bucket: String? = null
    ) = ImageFile(
        id = id,
        uri = Uri.parse("content://media/$volume/images/media/$id"),
        displayName = "photo$id.jpg",
        dateModified = dateAdded,
        size = 1024L,
        width = 1080,
        height = 1920,
        bucketDisplayName = bucket,
        volumeName = volume,
        relativePath = relativePath,
        dateAdded = dateAdded
    )

    private fun daysAgo(days: Int) = now - days * 86_400_000L

    private val screenshots = StagedSourceKey("external_primary", "DCIM/Screenshots/")
    private val downloads = StagedSourceKey("external_primary", "Download/")

    // ==================== 来源判定 ====================

    @Test
    fun onlyScreenshotAndDownloadPathsAreStagedSources() {
        assertTrue(StagedCleanupRules.isStagedSourcePath("DCIM/Screenshots/"))
        assertTrue(StagedCleanupRules.isStagedSourcePath("Pictures/Screenshots"))
        assertTrue(StagedCleanupRules.isStagedSourcePath("Download/"))
        assertTrue(StagedCleanupRules.isStagedSourcePath("DCIM/Downloads/"))
        // 大小写与斜杠归一无影响
        assertTrue(StagedCleanupRules.isStagedSourcePath("dcim/screenshots"))

        // 相机、Tabula 图集、聊天软件目录本身都不在默认暂存范围
        assertFalse(StagedCleanupRules.isStagedSourcePath("DCIM/Camera/"))
        assertFalse(StagedCleanupRules.isStagedSourcePath("Pictures/Tabula/风景/"))
        assertFalse(StagedCleanupRules.isStagedSourcePath("Pictures/WeiXin/"))
        // 必须落在 Pictures/ 或 DCIM/ 之下
        assertFalse(StagedCleanupRules.isStagedSourcePath("Screenshots/"))
        assertFalse(StagedCleanupRules.isStagedSourcePath(""))
    }

    @Test
    fun availableSourcesGroupsByVolumeAndPathNotByDisplayName() {
        val rows = listOf(
            photo(1, "DCIM/Screenshots/", daysAgo(10)),
            photo(2, "DCIM/Screenshots/", daysAgo(3)),
            photo(3, "Download/", daysAgo(20)),
            photo(4, "DCIM/Camera/", daysAgo(20)),
            // 另一卷上的同路径必须独立成来源
            photo(5, "DCIM/Screenshots/", daysAgo(9), volume = "1234-5678"),
            // 没有相对路径的行不参与任何来源
            photo(6, null, daysAgo(5))
        )

        val sources = StagedCleanupRules.availableSources(rows)

        // 审计修复（#84）：现在列出全部来源（含 Camera），共 4 个
        assertEquals(4, sources.size)
        val screenshotSources = sources.filter { it.displayName == "Screenshots" }
        assertEquals(2, screenshotSources.size)
        assertEquals(setOf("external_primary", "1234-5678"), screenshotSources.map { it.key.volumeName }.toSet())
        assertEquals(1, sources.first { it.key.volumeName == "1234-5678" }.imageCount)
        // 推荐项（截图/下载）在前，同组内照片多的在前
        assertEquals("Screenshots", sources.first().displayName)
        assertEquals(2, sources.first().imageCount)
        // Camera 也列出，但不算推荐
        assertTrue(sources.any { it.displayName == "Camera" && !it.isRecommended })
    }

    // ==================== 分组与门限 ====================

    @Test
    fun sevenDaysIsTheThresholdAndRecentPhotosStaySeparate() {
        val rows = listOf(
            photo(1, "DCIM/Screenshots/", daysAgo(30)),
            photo(2, "DCIM/Screenshots/", daysAgo(8)),
            photo(3, "DCIM/Screenshots/", daysAgo(7)),
            photo(4, "DCIM/Screenshots/", daysAgo(1)),
            photo(5, "Download/", daysAgo(15))
        )

        val selection = StagedCleanupRules.select(
            allImages = rows,
            sources = listOf(screenshots, downloads),
            nowMillis = now,
            timeZone = utc
        )

        // 超过七天的：30 天、15 天、8 天（严格小于 7 天前的 00:00 才算旧）
        assertEquals(setOf(1L, 2L, 5L), selection.oldImages.map { it.id }.toSet())
        // 刚好 7 天与 1 天的进"最近"
        assertEquals(setOf(3L, 4L), selection.recentGroups.flatMap { it.images }.map { it.id }.toSet())
        assertTrue(selection.unknownTimeImages.isEmpty())
    }

    @Test
    fun dayGroupsUseLocalCalendarDaysNotElapsedHours() {
        // 同一自然日、不同时刻的两张：必须落在同一组
        val morning = 1_795_435_200_000L - (12 * 3_600_000L) // 当日 00:00 后 0 点
        val rows = listOf(
            photo(1, "DCIM/Screenshots/", morning + 1_000L),
            photo(2, "DCIM/Screenshots/", morning + 3_600_000L),
            photo(3, "DCIM/Screenshots/", morning - 60_000L) // 前一日最后一分钟
        )

        val selection = StagedCleanupRules.select(
            allImages = rows,
            sources = listOf(screenshots),
            nowMillis = now,
            timeZone = utc
        )

        val groups = selection.recentGroups
        assertEquals(2, groups.size)
        // 分组按日期倒序，组内按存入时间倒序
        assertEquals(listOf(2L, 1L), groups.first().images.map { it.id })
        assertEquals(listOf(3L), groups.last().images.map { it.id })
    }

    @Test
    fun unknownDateAddedIsListedSeparatelyNeverTreatedAsOld() {
        val rows = listOf(
            photo(1, "DCIM/Screenshots/", dateAdded = 0L),
            photo(2, "DCIM/Screenshots/", daysAgo(30))
        )

        val selection = StagedCleanupRules.select(
            allImages = rows,
            sources = listOf(screenshots),
            nowMillis = now,
            timeZone = utc
        )

        // 时间未知的不能因为"看起来 1970 很旧"就进待清清单
        assertEquals(listOf(2L), selection.oldImages.map { it.id })
        assertEquals(listOf(1L), selection.unknownTimeImages.map { it.id })
    }

    // ==================== 保留 / 跳过 / 已回收 ====================

    @Test
    fun retainedImagesLeaveTheCandidateSetButSkippedOnesComeBackAfterRestart() {
        val rows = listOf(
            photo(1, "DCIM/Screenshots/", daysAgo(30)),
            photo(2, "DCIM/Screenshots/", daysAgo(30))
        )

        val retained = StagedCleanupRules.select(
            allImages = rows,
            sources = listOf(screenshots),
            retainedIdentityKeys = setOf(cleanupIdentityKey(rows[0])),
            nowMillis = now,
            timeZone = utc
        )
        assertEquals(listOf(2L), retained.oldImages.map { it.id })

        // 跳过只影响本次：同样的输入加回跳过集合就空，换回空集合又完整
        val skipped = StagedCleanupRules.select(
            allImages = rows,
            sources = listOf(screenshots),
            skippedIdentityKeys = rows.map { cleanupIdentityKey(it) }.toSet(),
            nowMillis = now,
            timeZone = utc
        )
        assertTrue(skipped.isEmpty)

        val afterRestart = StagedCleanupRules.select(
            allImages = rows,
            sources = listOf(screenshots),
            nowMillis = now,
            timeZone = utc
        )
        assertEquals(2, afterRestart.oldImages.size)
    }

    @Test
    fun alreadyRecycledImagesAreNotOfferedAgain() {
        val rows = listOf(
            photo(1, "DCIM/Screenshots/", daysAgo(30)),
            photo(2, "DCIM/Screenshots/", daysAgo(30))
        )

        val selection = StagedCleanupRules.select(
            allImages = rows,
            sources = listOf(screenshots),
            recycledIdentityKeys = setOf(cleanupIdentityKey(rows[0])),
            nowMillis = now,
            timeZone = utc
        )

        assertEquals(listOf(2L), selection.oldImages.map { it.id })
    }

    @Test
    fun sameNumericIdOnAnotherVolumeIsNotTheSamePhoto() {
        val rows = listOf(
            photo(7, "DCIM/Screenshots/", daysAgo(30), volume = "external_primary"),
            photo(7, "DCIM/Screenshots/", daysAgo(30), volume = "1234-5678")
        )

        // 只保留其中一卷，另一卷的同 ID 照片必须仍在候选里
        val selection = StagedCleanupRules.select(
            allImages = rows,
            sources = listOf(
                StagedSourceKey("external_primary", "DCIM/Screenshots/"),
                StagedSourceKey("1234-5678", "DCIM/Screenshots/")
            ),
            retainedIdentityKeys = setOf("external_primary|7"),
            nowMillis = now,
            timeZone = utc
        )

        assertEquals(1, selection.oldImages.size)
        assertEquals("1234-5678", selection.oldImages.single().volumeName)
    }

    @Test
    fun sourcesWithNoMatchingPhotosStillReportZeroCount() {
        // 用户勾了下载目录但这台设备的下载目录已空：来源要出现在摘要里，
        // 计数为 0，界面据此显示"这些来源里没有待清理的照片"。
        val rows = listOf(photo(1, "DCIM/Screenshots/", daysAgo(30)))

        val selection = StagedCleanupRules.select(
            allImages = rows,
            sources = listOf(screenshots, downloads),
            nowMillis = now,
            timeZone = utc
        )

        assertEquals(2, selection.selectedSources.size)
        assertEquals(0, selection.selectedSources.first { it.displayName == "Download" }.imageCount)
    }

    @Test
    fun unknownVolumeMatchesOnlyUnknownVolumeSource() {
        val row = photo(1, "DCIM/Screenshots/", daysAgo(30), volume = null)
        assertNotNull(StagedCleanupRules.stagedSourceKeyOf(row))
        assertEquals("", StagedCleanupRules.stagedSourceKeyOf(row)!!.volumeName)

        // 显式勾 external_primary 时，卷未知的行不该被算进去
        val selection = StagedCleanupRules.select(
            allImages = listOf(row),
            sources = listOf(screenshots),
            nowMillis = now,
            timeZone = utc
        )
        assertTrue(selection.isEmpty)
    }

    @Test
    fun photoWithoutRelativePathBelongsToNoSource() {
        assertNull(StagedCleanupRules.stagedSourceKeyOf(photo(1, null, daysAgo(30))))
        assertNull(StagedCleanupRules.stagedSourceKeyOf(photo(2, "  ", daysAgo(30))))
    }

    @Test
    fun sourceDisplayNameFallsBackToLastSegmentOrVolume() {
        // 显示名保留设备上的原始大小写，归一过程不参与文案
        assertEquals("Screenshots", StagedCleanupRules.displayNameOf(screenshots))
        assertEquals("Download", StagedCleanupRules.displayNameOf(downloads))
        assertEquals("external_primary", StagedCleanupRules.displayNameOf(StagedSourceKey("external_primary", "")))
    }

    @Test
    fun retentionCutoffIsMidnightBasedNotHourBased() {
        // 现在 = 2026-11-23 12:00 UTC；七天前 = 2026-11-16 00:00 UTC
        val cutoff = StagedCleanupRules.retentionCutoffMillis(now, 7, utc)
        val calendar = java.util.Calendar.getInstance(utc).apply { timeInMillis = cutoff }
        assertEquals(2026, calendar.get(java.util.Calendar.YEAR))
        assertEquals(java.util.Calendar.NOVEMBER, calendar.get(java.util.Calendar.MONTH))
        assertEquals(16, calendar.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(0, calendar.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(0, calendar.get(java.util.Calendar.MINUTE))
    }
}

/**
 * 审计修复（#84）补充的入口级测试。
 *
 * 对应审计要求：来源选择器列出全部图片来源、聊天软件保存目录可选、
 * 保留卷+路径身份、同名跨卷目录、空选择、重新选择、重启、部分照片权限。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class StagedCleanupSourceSelectionTest {

    private fun photo(
        id: Long,
        relativePath: String,
        volume: String? = "external_primary",
        dateAdded: Long = 1_795_000_000_000L
    ) = ImageFile(
        id = id,
        uri = android.net.Uri.parse("content://media/$volume/images/media/$id"),
        displayName = "p$id.jpg",
        dateModified = dateAdded,
        size = 1024L,
        width = 1080,
        height = 1920,
        bucketDisplayName = relativePath.trimEnd('/').substringAfterLast('/'),
        volumeName = volume,
        relativePath = relativePath,
        dateAdded = dateAdded
    )

    @Test
    fun allImageSourcesAreListedNotJustScreenshotsAndDownloads() {
        val library = listOf(
            photo(1, "DCIM/Screenshots/"),
            photo(2, "Download/"),
            photo(3, "DCIM/Camera/"),
            photo(4, "Pictures/WeiXin/"),
            photo(5, "Pictures/Tencent/QQImages/")
        )

        val sources = StagedCleanupRules.availableSources(library)

        // 五个来源一个不少
        assertEquals(5, sources.size)
        val names = sources.map { it.displayName }.toSet()
        assertEquals(
            setOf("Screenshots", "Download", "Camera", "WeiXin", "QQImages"),
            names
        )
    }

    @Test
    fun chatAppDirectoriesAreSelectable() {
        val library = listOf(
            photo(1, "Pictures/WeiXin/"),
            photo(2, "Pictures/WeiXin/"),
            photo(3, "DCIM/Screenshots/")
        )

        val sources = StagedCleanupRules.availableSources(library)
        val weixin = sources.first { it.displayName == "WeiXin" }

        // 可选，但不算推荐（推荐只给截图/下载）
        assertEquals(2, weixin.imageCount)
        assertFalse(weixin.isRecommended)

        // 用户勾上之后，确实能筛出这个目录的照片
        val selection = StagedCleanupRules.select(
            allImages = library,
            sources = listOf(weixin.key),
            nowMillis = 1_800_000_000_000L,
            timeZone = TimeZone.getTimeZone("UTC")
        )
        assertEquals(2, selection.allCandidateImages.size)
        assertTrue(selection.allCandidateImages.all { it.relativePath == "Pictures/WeiXin/" })
    }

    @Test
    fun recommendedSourcesAreScreenshotsAndDownloads() {
        val library = listOf(
            photo(1, "DCIM/Screenshots/"),
            photo(2, "Download/"),
            photo(3, "DCIM/Camera/"),
            photo(4, "Pictures/WeiXin/")
        )

        val sources = StagedCleanupRules.availableSources(library)

        assertEquals(
            setOf("Screenshots", "Download"),
            sources.filter { it.isRecommended }.map { it.displayName }.toSet()
        )
        assertEquals(
            setOf("Camera", "WeiXin"),
            sources.filterNot { it.isRecommended }.map { it.displayName }.toSet()
        )
    }

    @Test
    fun recommendedSourcesComeFirstThenByCount() {
        val library = listOf(
            photo(1, "Pictures/WeiXin/"), photo(2, "Pictures/WeiXin/"), photo(3, "Pictures/WeiXin/"),
            photo(4, "DCIM/Camera/"),
            photo(5, "DCIM/Screenshots/")
        )

        val sources = StagedCleanupRules.availableSources(library)

        // 推荐项（截图）即使只有 1 张也排第一；其余按数量降序
        assertEquals("Screenshots", sources.first().displayName)
        assertEquals(listOf("WeiXin", "Camera"), sources.drop(1).map { it.displayName })
    }

    @Test
    fun sameNameDirectoryOnTwoVolumesStaysSeparate() {
        val library = listOf(
            photo(1, "DCIM/Screenshots/", volume = "external_primary"),
            photo(2, "DCIM/Screenshots/", volume = "1234-5678"),
            photo(3, "DCIM/Screenshots/", volume = "1234-5678")
        )

        val sources = StagedCleanupRules.availableSources(library)
        val screenshots = sources.filter { it.displayName == "Screenshots" }

        assertEquals(2, screenshots.size)
        assertEquals(
            mapOf("external_primary" to 1, "1234-5678" to 2),
            screenshots.associate { it.key.volumeName to it.imageCount }
        )
    }

    @Test
    fun selectingOneVolumeDoesNotPullTheOtherVolumesPhotos() {
        val library = listOf(
            photo(1, "DCIM/Screenshots/", volume = "external_primary"),
            photo(2, "DCIM/Screenshots/", volume = "1234-5678")
        )
        val sources = StagedCleanupRules.availableSources(library)
        val firstVolume = sources.first { it.key.volumeName == "external_primary" }

        val selection = StagedCleanupRules.select(
            allImages = library,
            sources = listOf(firstVolume.key),
            nowMillis = 1_800_000_000_000L,
            timeZone = TimeZone.getTimeZone("UTC")
        )

        assertEquals(1, selection.allCandidateImages.size)
        assertEquals("external_primary", selection.allCandidateImages.single().volumeName)
    }

    @Test
    fun emptySelectionYieldsNoCandidates() {
        val library = listOf(photo(1, "DCIM/Screenshots/"))

        val selection = StagedCleanupRules.select(
            allImages = library,
            sources = emptyList(),
            nowMillis = 1_800_000_000_000L,
            timeZone = TimeZone.getTimeZone("UTC")
        )

        assertTrue(selection.isEmpty)
        assertTrue(selection.selectedSources.isEmpty())
    }

    @Test
    fun reselectingSourcesReplacesTheScope() {
        val library = listOf(
            photo(1, "DCIM/Screenshots/"),
            photo(2, "Pictures/WeiXin/")
        )
        val sources = StagedCleanupRules.availableSources(library)
        val screenshots = sources.first { it.displayName == "Screenshots" }
        val weixin = sources.first { it.displayName == "WeiXin" }

        val first = StagedCleanupRules.select(
            library, listOf(screenshots.key), nowMillis = 1_800_000_000_000L,
            timeZone = TimeZone.getTimeZone("UTC")
        )
        assertEquals(1, first.allCandidateImages.single().id)

        // 重新选择：换成微信目录，结果必须只剩它
        val second = StagedCleanupRules.select(
            library, listOf(weixin.key), nowMillis = 1_800_000_000_000L,
            timeZone = TimeZone.getTimeZone("UTC")
        )
        // 换成微信目录后，候选里只剩微信目录那 1 张（id=2），截图不再出现
        assertEquals(1, second.allCandidateImages.size)
        assertEquals(2L, second.allCandidateImages.single().id)
    }

    @Test
    fun defaultSelectionOnlyPicksRecommendedSources() {
        val library = listOf(
            photo(1, "DCIM/Screenshots/"),
            photo(2, "Download/"),
            photo(3, "Pictures/WeiXin/"),
            photo(4, "DCIM/Camera/")
        )
        val sources = StagedCleanupRules.availableSources(library)

        val defaults = StagedCleanupRules.defaultSelectedSourceKeys(sources)

        assertEquals(2, defaults.size)
        // 键是归一化后的 "卷|小写路径"，尾部斜杠已被 trim
        assertTrue(defaults.any { it.endsWith("|dcim/screenshots") })
        assertTrue(defaults.any { it.endsWith("|download") })
        assertTrue(defaults.none { it.contains("weixin") })
    }

    @Test
    fun defaultSelectionRespectsLimit() {
        val library = listOf(
            photo(1, "DCIM/Screenshots/"),
            photo(2, "Download/"),
            photo(3, "Pictures/Screenshots/"),
            photo(4, "Pictures/Download/")
        )
        val sources = StagedCleanupRules.availableSources(library)

        assertEquals(2, StagedCleanupRules.defaultSelectedSourceKeys(sources, limit = 2).size)
    }

    @Test
    fun partialPermissionOnlySeesAccessibleSources() {
        // 部分照片权限下，图库只返回授权范围内的照片；未授权的目录自然不出现，
        // 界面不应列出用户不可达的来源（否则勾了也没有候选）。
        val visible = listOf(
            photo(1, "DCIM/Screenshots/"),
            photo(2, "Pictures/WeiXin/")
        )
        val sources = StagedCleanupRules.availableSources(visible)

        assertEquals(2, sources.size)
        // 如果之后权限变化、某个目录不再可见，重新计算就不会再列出它
        val afterRevoke = StagedCleanupRules.availableSources(listOf(photo(1, "DCIM/Screenshots/")))
        assertEquals(1, afterRevoke.size)
        assertEquals("Screenshots", afterRevoke.single().displayName)
    }

    @Test
    fun photoWithoutPathIsNotOfferedAsARow() {
        val library = listOf(
            photo(1, "DCIM/Screenshots/"),
            photo(2, "").copy(relativePath = null)
        )
        val sources = StagedCleanupRules.availableSources(library)

        assertEquals(1, sources.size)
        assertEquals("Screenshots", sources.single().displayName)
    }
}
