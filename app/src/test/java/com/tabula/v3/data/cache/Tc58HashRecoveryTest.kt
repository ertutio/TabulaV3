package com.tabula.v3.data.cache

import android.content.Context
import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.AlbumCleanupEngine
import com.tabula.v3.data.repository.PerceptualHash
import com.tabula.v3.data.repository.SimilarGroup
import com.tabula.v3.data.repository.mediaIdentityKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * TC58（T03/T07）补面：同媒体 ID 改内容 → 旧哈希按内容版本键失效、按明确
 * 有界策略重算；一次临时读取失败后恢复；失效/重算/清理循环不触碰无关冷却
 * 与清理进度。
 *
 * 既有覆盖（复用不重做）：ImageHashCacheVersionTest 钉版本语义、跨卷隔离、
 * 失败条目同版本可查询性、清理按身份判定；本类补 TC58 规格的"恢复"半边
 * （失败 → 版本推进 → 重算成功 → 单向版本键不复活旧版）与"无关面保留"
 * 半边（生产入口种子：冷却 pick 记录 + 清理组/断点，经哈希失效/重算/清理
 * 全循环后逐项读回原样）。
 *
 * 夹具：真实 AppPreferences + 真实 AlbumCleanupEngine（组经 analyzeAlbum
 * 同一组写入口种子——AlbumCleanupForegroundRefreshTest 同款）+ 真实
 * ImageHashCache。Robolectric 本地；CI 门禁随 build job 单测轮执行。
 * 隔离纪律：不触碰 AppPreferences/RecycleBinManager 的 getInstance 单例
 * （本用例不需要回收站面；每用例 AppPreferences 新实例——见字段注释，
 * PR80 轮 1 的跨类单例顺序污染实证）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class Tc58HashRecoveryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val cache get() = ImageHashCache(context)
    // 每用例新实例（PickCooldownIdentityTest 同款）：不触碰跨类共享的
    // AppPreferences/RecycleBinManager 单例——Robolectric 沙箱复用下静态存活，
    // 多类共 JVM 时首个调用者捕获的 context 会使共享单例指向陈旧 filesDir
    // （PR80 轮 1 CI 实证：本类在 @Before 触碰 getInstance 后，后跑的
    // AlbumCleanupForegroundRefreshTest :177 断言读到空——单例写落陈旧目录）。
    private val preferences: AppPreferences get() = AppPreferences(context)
    private val albumId = "tc58-hash-recovery"

    private fun key(imageId: Long) = ImageHashCache.HashKey("external_primary", imageId)

    private fun row(id: Long, dateModified: Long = 1_000L + id) = ImageFile(
        id = id,
        uri = Uri.parse("content://media/external_primary/images/media/$id"),
        displayName = "tc58-$id.jpg",
        dateModified = dateModified,
        size = 100L,
        width = 64,
        height = 64,
        bucketDisplayName = "source",
        volumeName = "external_primary",
        relativePath = "Pictures/source/"
    )

    @Before
    fun setUp() {
        cache.clearAll()
        preferences.clearAllPickRecords()
        preferences.clearAllAlbumCleanupState()
    }

    @Test
    fun temporaryReadFailureRecoversAtNewContentVersion() {
        // 一次临时读取失败：版本 1000 落 FAILED 条目（失败不冒充成功）。
        cache.saveHash(key(9L), PerceptualHash.HashResult.Failed("io"), contentVersion = 1000L)
        val failed = cache.getHash(key(9L), contentVersion = 1000L)
        assertTrue("失败条目在同版本可查询（既有语义）", failed != null && failed.isFailed)

        // 内容编辑 → 版本推进：失败条目不得服务新版本（即触发重算的判定面）。
        assertNull("临时失败不得冒充新版本有效结果", cache.getHash(key(9L), contentVersion = 2000L))

        // 重算成功落盘 → 新版本恢复。
        cache.saveHash(key(9L), PerceptualHash.HashResult.Success(77L), contentVersion = 2000L)
        val recovered = cache.getHash(key(9L), contentVersion = 2000L)
        assertTrue("重算成功后新版本必须服务", recovered != null && recovered.isSuccess)
        assertEquals(77L, recovered!!.hash)

        // 版本键单向：恢复后旧版本不复活。
        assertNull("恢复后旧版本不得复活", cache.getHash(key(9L), contentVersion = 1000L))
    }

    @Test
    fun hashInvalidationAndRecomputeCyclePreservesUnrelatedCooldownAndProgress() = runBlocking {
        // 无关面种子（生产入口）：冷却 pick 记录 + 清理组/断点。
        val cooldownRow = row(301L)
        preferences.recordImagesPickedByIdentity(listOf(cooldownRow.mediaIdentityKey()))

        val members = listOf(row(311L), row(312L))
        val originalId = SimilarGroup(
            images = members,
            startTime = members.minOf { it.dateModified },
            endTime = members.maxOf { it.dateModified }
        ).id
        preferences.saveAlbumAnalysisResult(albumId, 1, listOf(originalId))
        preferences.saveGroupImageCounts(albumId, mapOf(originalId to members.size))
        preferences.saveAlbumTotalImages(albumId, members.size)
        preferences.saveGroupsImageIds(
            albumId,
            mapOf(originalId to members.map { it.mediaIdentityKey() })
        )
        val engine = AlbumCleanupEngine(context)
        engine.getNextBatch(albumId, members)
        engine.saveCheckpoint(listOf(originalId), 1)

        // 基线读取：冷却命中 + 断点可恢复。
        val cooldownHitBefore = preferences.getPickCooldownIndex()
            .byIdentityKey.containsKey(cooldownRow.mediaIdentityKey())
        assertTrue("种子自检：冷却记录必须命中", cooldownHitBefore)
        val checkpointBefore = AlbumCleanupEngine(context).getCheckpointBatch(albumId, members)!!
        assertEquals(listOf(originalId), checkpointBefore.first.groupIds)
        assertEquals(1, checkpointBefore.second)

        // TC58 目标操作：哈希失效（版本推进）→ 重算成功 → 有界清理。
        cache.saveHash(key(9L), PerceptualHash.HashResult.Failed("io"), contentVersion = 1000L)
        assertNull(cache.getHash(key(9L), contentVersion = 2000L))
        cache.saveHash(key(9L), PerceptualHash.HashResult.Success(77L), contentVersion = 2000L)
        cache.cleanupStaleHashes(validKeys = setOf(key(9L)))
        assertTrue("清理后有效键必须保留", cache.getHash(key(9L), contentVersion = 2000L) != null)

        // 无关面原样：冷却记录、组键、断点、已处理面逐项读回。
        assertTrue(
            "哈希循环不得清除无关冷却记录",
            preferences.getPickCooldownIndex().byIdentityKey.containsKey(cooldownRow.mediaIdentityKey())
        )
        assertEquals(
            "哈希循环不得清除清理组成员键",
            members.map { it.mediaIdentityKey() },
            preferences.getGroupImageIds(albumId, originalId)
        )
        val checkpointAfter = AlbumCleanupEngine(context).getCheckpointBatch(albumId, members)!!
        assertEquals("断点索引必须原样", checkpointBefore.second, checkpointAfter.second)
        assertEquals("断点组身份必须原样", checkpointBefore.first.groupIds, checkpointAfter.first.groupIds)
        assertTrue("哈希循环不得标记组永久完成", preferences.getAlbumPermanentlyProcessedGroups(albumId).isEmpty())
    }
}
