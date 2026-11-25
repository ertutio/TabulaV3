package com.tabula.v3.data.balance

import android.content.Context
import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.WalkSamplingStrategy
import com.tabula.v3.data.repository.mediaIdentityKey
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #89 入口级测试：设置页策略选择 → 偏好持久化 → 下一批实际生效。
 *
 * 从真实的 AppPreferences（SharedPreferences）读写走，不是只测枚举。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SamplingStrategyEntryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tabula_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

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

    private fun library() =
        (1L..500L).map { photo(it, "DCIM/Camera/") } +
            (1000L..1019L).map { photo(it, "DCIM/Screenshots/") }

    // ============ 默认值与升级行为 ============

    @Test
    fun defaultStrategyIsUniformForUpgradedUsers() {
        // 全新/升级用户：没有这条键 → 默认 UNIFORM（改动前行为）
        val prefs = AppPreferences(context)
        assertEquals(WalkSamplingStrategy.UNIFORM, prefs.walkSamplingStrategy)
    }

    @Test
    fun strategyChoicePersistsAcrossPreferenceInstances() {
        val prefs = AppPreferences(context)
        prefs.walkSamplingStrategy = WalkSamplingStrategy.SOURCE_BALANCED

        // 新建实例 = 进程重启后重读
        val afterRestart = AppPreferences(context)
        assertEquals(WalkSamplingStrategy.SOURCE_BALANCED, afterRestart.walkSamplingStrategy)
    }

    @Test
    fun selectingUniformAgainPersists() {
        val prefs = AppPreferences(context)
        prefs.walkSamplingStrategy = WalkSamplingStrategy.SOURCE_BALANCED
        prefs.walkSamplingStrategy = WalkSamplingStrategy.UNIFORM
        assertEquals(WalkSamplingStrategy.UNIFORM, AppPreferences(context).walkSamplingStrategy)
    }

    // ============ 下一批实际生效 ============

    private fun sampleFor(strategy: WalkSamplingStrategy) = SourceBalanceRules.sampleByStrategy(
        available = library(),
        batchSize = 6,
        strategy = when (strategy) {
            WalkSamplingStrategy.UNIFORM -> SourceBalanceRules.Strategy.UNIFORM
            WalkSamplingStrategy.SOURCE_BALANCED -> SourceBalanceRules.Strategy.SOURCE_BALANCED
        },
        random = Random(11)
    )

    @Test
    fun uniformPreferenceProducesOriginalRandomBatch() {
        val prefs = AppPreferences(context)
        prefs.walkSamplingStrategy = WalkSamplingStrategy.UNIFORM
        val original = library().shuffled(Random(11)).take(6)

        val batch = sampleFor(prefs.walkSamplingStrategy)

        assertEquals(
            original.map { it.mediaIdentityKey() },
            batch.map { it.mediaIdentityKey() }
        )
    }

    @Test
    fun balancedPreferenceProducesWiderSourceCoverage() {
        val prefs = AppPreferences(context)
        prefs.walkSamplingStrategy = WalkSamplingStrategy.SOURCE_BALANCED
        val batch = sampleFor(prefs.walkSamplingStrategy)

        // 均衡策略在悬殊相册下应覆盖到小相册
        assertTrue(
            batch.map { it.relativePath }.joinToString(),
            batch.any { it.relativePath == "DCIM/Screenshots/" }
        )
    }

    // ============ 两种策略的冷却、去重、回填 ============

    @Test
    fun bothStrategiesHonourCooling() {
        val cooled = library().filter { it.relativePath == "DCIM/Screenshots/" }
        val available = library() - cooled.toSet()

        listOf(WalkSamplingStrategy.UNIFORM, WalkSamplingStrategy.SOURCE_BALANCED).forEach { s ->
            val batch = SourceBalanceRules.sampleByStrategy(
                available, 6,
                when (s) {
                    WalkSamplingStrategy.UNIFORM -> SourceBalanceRules.Strategy.UNIFORM
                    else -> SourceBalanceRules.Strategy.SOURCE_BALANCED
                },
                Random(3)
            )
            val cooledKeys = cooled.map { it.mediaIdentityKey() }.toSet()
            assertTrue("$s 不能抽到冷却中的照片", batch.none { it.mediaIdentityKey() in cooledKeys })
        }
    }

    @Test
    fun bothStrategiesDedupeByIdentity() {
        val library = (1L..30L).map { photo(it, "A/") } +
            (1L..30L).map { photo(it, "A/", volume = "1234-5678") }

        listOf(WalkSamplingStrategy.UNIFORM, WalkSamplingStrategy.SOURCE_BALANCED).forEach { s ->
            val batch = SourceBalanceRules.sampleByStrategy(
                library, 20,
                when (s) {
                    WalkSamplingStrategy.UNIFORM -> SourceBalanceRules.Strategy.UNIFORM
                    else -> SourceBalanceRules.Strategy.SOURCE_BALANCED
                },
                Random(5)
            )
            val keys = batch.map { it.mediaIdentityKey() }
            assertEquals("$s", keys.size, keys.toSet().size)
        }
    }

    @Test
    fun bothStrategiesBackfillWhenCandidatesAreScarce() {
        val available = (1L..3L).map { photo(it, "A/") }
        val cooled = (10L..20L).map { photo(it, "B/") }

        listOf(WalkSamplingStrategy.UNIFORM, WalkSamplingStrategy.SOURCE_BALANCED).forEach { s ->
            val primary = SourceBalanceRules.sampleByStrategy(
                available, 6,
                when (s) {
                    WalkSamplingStrategy.UNIFORM -> SourceBalanceRules.Strategy.UNIFORM
                    else -> SourceBalanceRules.Strategy.SOURCE_BALANCED
                },
                Random(1)
            )
            val fill = SourceBalanceRules.backfillFromCooldown(
                cooldownImages = cooled,
                alreadyPicked = primary,
                batchSize = 6,
                pickedAt = { it.id }
            )
            assertEquals("$s 回填后应满批", 6, primary.size + fill.size)
            assertFalse(fill.isEmpty())
        }
    }
}
