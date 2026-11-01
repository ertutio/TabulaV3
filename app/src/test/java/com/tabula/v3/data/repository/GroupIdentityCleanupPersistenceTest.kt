package com.tabula.v3.data.repository

import android.content.Context
import android.net.Uri
import com.tabula.v3.data.model.Album
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * C1/T03-GROUPID-CLEANUP：相似组身份 + 专清持久化/恢复闭环。
 *
 * 缺口（既有，非本轮引入）：
 * 1. SimilarGroup.id = "startTime_首张照片数字 ID"——不同卷的同数值 ID、
 *    相同时间的组共用冷却池与永久处理标记；成员重排（首张变化）产生无意义
 *    的组身份漂移。
 * 2. 专清成员持久化只存 Long ID（saveGroupsImageIds），恢复用
 *    associateBy(id)——跨卷同 ID 行折叠或恢复成错误照片。
 *
 * 夹具：真实 AlbumCleanupEngine + 真实 AppPreferences；图片为构造的
 * ImageFile（两卷、两档分辨率、同秒时间），哈希解码失败使检测走元数据
 * -only 分组（跨卷分辨率不同 → 不连接），组集合确定：{p10,p11} 与
 * {s10,s12}，两组的旧 ID 同为 "${T}_10"。
 * 旧格式组 ID（既有构建写入）= "${startTime 毫秒}_${首张数字 ID}"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GroupIdentityCleanupPersistenceTest {
    private val app: android.app.Application get() = RuntimeEnvironment.getApplication()
    private val context: Context get() = app
    private lateinit var preferences: AppPreferences

    private val T = 1_700_000_000_000L
    private val legacyGroupId = "${T}_10"
    private val album = Album(id = "source", name = "source")

    private val p10 = row("external_primary", 10L, T, width = 640, height = 480, size = 100L)
    private val p11 = row("external_primary", 11L, T + 2_000L, width = 640, height = 480, size = 100L)
    private val s10 = row("external_secondary", 10L, T, width = 800, height = 600, size = 200L)
    private val s12 = row("external_secondary", 12L, T + 2_000L, width = 800, height = 600, size = 200L)
    private val allImages = listOf(p10, p11, s10, s12)

    @Before
    fun setUp() {
        preferences = AppPreferences.getInstance(context)
        preferences.clearAllAlbumCleanupState()
        com.tabula.v3.data.cache.ImageHashCache.getInstance(context).clearAll()
    }

    private fun row(volume: String, id: Long, dateModifiedMs: Long, width: Int, height: Int, size: Long) =
        ImageFile(
            id = id,
            uri = Uri.parse("content://media/$volume/images/media/$id"),
            displayName = "img-$volume-$id.jpg",
            dateModified = dateModifiedMs,
            size = size,
            width = width,
            height = height,
            bucketDisplayName = "source",
            volumeName = volume,
            relativePath = "Pictures/source/"
        )

    /** 与生产检测一致的预期组：按 dateModified 排序的成员。 */
    private fun expectedGroupA() = SimilarGroup(images = listOf(p10, p11), startTime = T, endTime = T + 2_000L)
    private fun expectedGroupB() = SimilarGroup(images = listOf(s10, s12), startTime = T, endTime = T + 2_000L)

    private suspend fun analyzeWithFreshEngine(): AlbumCleanupEngine {
        val engine = AlbumCleanupEngine(context)
        engine.analyzeAlbum(album, allImages).toList()
        return engine
    }

    /** 注入既有构建写下的旧格式数据（组集合 + 逗号分隔裸 ID 成员 + 可选旧标记）。 */
    private fun seedLegacyGroup(albumId: String, legacyId: String, memberIds: List<Long>, withCheckpoint: Boolean = true, processedMarker: Boolean = false) {
        // 全部经由 AppPreferences 写入（与被测引擎同一读写层），落盘字节与
        // 既有构建的旧格式逐字相同（逗号分隔裸 ID）——AppPreferences 单例绑定
        // 首个沙箱 Application，raw SharedPreferences 跨方法不可见。
        preferences.saveAlbumAnalysisResult(albumId, 1, listOf(legacyId))
        preferences.saveGroupsImageIds(albumId, mapOf(legacyId to memberIds))
        if (withCheckpoint) {
            preferences.saveAlbumCleanupCheckpoint(albumId, listOf(legacyId), 0)
        }
        if (processedMarker) {
            preferences.markAlbumGroupPermanentlyProcessed(albumId, legacyId)
        }
    }

    // ========== 组身份语义 ==========

    @Test
    fun groupIdentityIsReorderStable() {
        // 同一成员集合重排不得产生无意义的组身份变化。
        val reordered = SimilarGroup(images = listOf(p11, p10), startTime = T, endTime = T + 2_000L)
        assertEquals(
            "成员重排后组身份必须一致",
            expectedGroupA().id, reordered.id
        )
    }

    @Test
    fun crossVolumeSameIdGroupsHaveDistinctIdentity() {
        // 不同卷的不同成员集合（旧 ID 同为 "${T}_10"）必须有可区分的组身份。
        assertNotEquals(
            "跨卷同数值 ID 的两组不得共用组身份",
            expectedGroupA().id, expectedGroupB().id
        )
    }

    // ========== 冷却消费者（真实 AppPreferences 读写） ==========

    @Test
    fun cooldownOfOneGroupDoesNotSuppressCrossVolumeGroup() = runBlocking {
        val engine = AlbumCleanupEngine(context)
        engine.analyzeAlbum(album, allImages).toList()
        val groupAId = expectedGroupA().id
        val groupBId = expectedGroupB().id

        engine.markGroupProcessed(groupAId)
        // 直接核对冷却池（与 RecommendationEngine 同一真实读写者）。
        assertTrue(
            "被处理的组必须进入冷却",
            preferences.isSimilarGroupInCooldown(groupAId)
        )
        assertFalse(
            "处理 A 组不得隐藏另一卷同数值 ID 的 B 组",
            preferences.isSimilarGroupInCooldown(groupBId)
        )
    }

    // ========== 永久处理标记 + 批次过滤（真实引擎全链） ==========

    @Test
    fun permanentMarkerOnOneGroupKeepsCrossVolumeGroupInBatch() = runBlocking {
        val engine = analyzeWithFreshEngine()
        val groupAId = expectedGroupA().id

        engine.markGroupProcessed(groupAId)

        val fresh = AlbumCleanupEngine(context)
        val batch = fresh.getNextBatch(album.id, allImages)
        assertNotNull("处理 A 组后必须仍有批次可清理", batch)
        val batchUris = batch!!.images.map { it.uri }.toSet()
        assertEquals(
            "剩余批次必须恰好是另一卷 B 组的成员（正确 URI，不串卷）",
            setOf(s10.uri, s12.uri), batchUris
        )
    }

    // ========== 专清成员持久化 + 重建引擎恢复（真实全链） ==========

    @Test
    fun restoreAfterEngineRebuildKeepsBothVolumeRows() = runBlocking {
        val engine = analyzeWithFreshEngine()
        val groupIds = preferences.getAlbumGroupIds(album.id).toList()
        engine.saveCheckpoint(groupIds, 0)

        // 重建引擎（cachedGroups 为空）→ 走持久化恢复路径。
        val fresh = AlbumCleanupEngine(context)
        val restored = fresh.getCheckpointBatch(album.id, allImages)
        assertNotNull("重建后必须能从持久化数据恢复批次", restored)
        val uris = restored!!.first.images.map { it.uri }
        assertEquals(
            "两卷照片都必须以正确 URI 恢复（不折叠、不串卷）",
            setOf(p10.uri, p11.uri, s10.uri, s12.uri), uris.toSet()
        )
        assertEquals(
            "每个成员恰好出现一次",
            4, uris.size
        )
    }

    // ========== 旧数据兼容（裸 ID 成员，raw 偏好注入） ==========

    @Test
    fun legacySingleVolumeMembersRestoreUniquely() = runBlocking {
        // 旧单卷数据：裸 ID 在当前库中唯一 → 必须照常恢复（兼容保持）。
        seedLegacyGroup("legacy1", legacyGroupId, listOf(10L, 11L))
        val raw = context.getSharedPreferences("tabula_album_cleanup", Context.MODE_PRIVATE)
        val singleVolume = listOf(p10, p11)
        val engine = AlbumCleanupEngine(context)
        val restored = engine.getCheckpointBatch("legacy1", singleVolume)
        assertNotNull("旧单卷数据必须能恢复", restored)
        assertEquals(
            setOf(p10.uri, p11.uri),
            restored!!.first.images.map { it.uri }.toSet()
        )
    }

    @Test
    fun legacyAmbiguousCrossVolumeMembersRedetectInsteadOfGuess() = runBlocking {
        // 旧跨卷歧义数据：裸 ID 10 在两卷各有同行——不得猜选另一卷，
        // 必须放弃恢复转重算（组集合完整，两卷都在）。
        seedLegacyGroup("legacy2", legacyGroupId, listOf(10L), withCheckpoint = false)
        val engine = AlbumCleanupEngine(context)
        val batch = engine.getNextBatch("legacy2", allImages)
        assertNotNull("歧义旧数据放弃恢复后必须重算出批次", batch)
        val uris = batch!!.images.map { it.uri }
        assertEquals(
            "重算结果必须包含两卷各自的同 ID 行（不折叠、不猜卷）",
            setOf(p10.uri, p11.uri, s10.uri, s12.uri), uris.toSet()
        )
    }

    @Test
    fun memberMissingSinceSaveDropsMemberKeepsGroup() = runBlocking {
        // 成员缺失（照片已被删除）：丢弃缺失成员、保留其余组（既有语义保持）。
        seedLegacyGroup("legacy3", legacyGroupId, listOf(10L, 11L))
        val onlyP10 = listOf(p10)
        val engine = AlbumCleanupEngine(context)
        val restored = engine.getCheckpointBatch("legacy3", onlyP10)
        assertNotNull(restored)
        assertEquals(
            setOf(p10.uri),
            restored!!.first.images.map { it.uri }.toSet()
        )
    }

    // ========== 永久处理标记的旧格式兼容（唯一映射迁移 / 歧义保留） ==========

    @Test
    fun legacyUniqueMarkerMigratesToNewGroupId() = runBlocking {
        // 单卷库中旧标记 (start, firstId) 能唯一映射到恢复/重检的组 → 迁移到
        // 新身份 ID，用户已处理意图保留（不得要求用户重复处理）。
        seedLegacyGroup(album.id, legacyGroupId, listOf(10L, 11L), withCheckpoint = false, processedMarker = true)
        val singleVolume = listOf(p10, p11)
        val engine = AlbumCleanupEngine(context)
        engine.getNextBatch(album.id, singleVolume)

        val processed = preferences.getAlbumPermanentlyProcessedGroups(album.id)
        assertTrue(
            "旧标记必须迁移到当前组的新身份 ID",
            processed.contains(expectedGroupA().id)
        )
        assertFalse(
            "已迁移的旧格式条目应被替换（幂等，不残留重复标记）",
            processed.contains(legacyGroupId)
        )
    }

    @Test
    fun legacyAmbiguousMarkerIsKeptNotGuessed() = runBlocking {
        // 跨卷库中旧标记 (start, firstId=10) 命中两个候选组 → 不得猜选；
        // 保留歧义记录原样（待解析），不扩大处理范围。
        seedLegacyGroup(album.id, legacyGroupId, listOf(10L), withCheckpoint = false, processedMarker = true)
        val engine = AlbumCleanupEngine(context)
        engine.getNextBatch(album.id, allImages)

        val processed = preferences.getAlbumPermanentlyProcessedGroups(album.id)
        assertTrue(
            "无法唯一映射的旧标记必须原样保留（不得清空用户标记）",
            processed.contains(legacyGroupId)
        )
        assertEquals(
            "歧义标记不得被猜测迁移到任一新组",
            setOf(legacyGroupId), processed
        )
    }

    @Test
    fun repeatedLoadAndMigrationIsIdempotent() = runBlocking {
        seedLegacyGroup(album.id, legacyGroupId, listOf(10L, 11L), withCheckpoint = false, processedMarker = true)
        val singleVolume = listOf(p10, p11)
        val engine = AlbumCleanupEngine(context)
        engine.getNextBatch(album.id, singleVolume)
        val afterFirst = preferences.getAlbumPermanentlyProcessedGroups(album.id)

        val engine2 = AlbumCleanupEngine(context)
        engine2.getNextBatch(album.id, singleVolume)
        val afterSecond = preferences.getAlbumPermanentlyProcessedGroups(album.id)

        assertEquals("重复加载/迁移必须幂等", afterFirst, afterSecond)
        assertTrue(afterFirst.contains(expectedGroupA().id))
        assertFalse(afterFirst.contains(legacyGroupId))
    }
}
