package com.tabula.v3.data.repository

import android.content.Context
import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * C1/T03-DECKCOOLDOWN 基线（红）：随机抽取冷却池现状为裸数值媒体 ID 键
 * （time_<id>/days_<id>）。跨卷同数值 ID 场景：
 * ① 卷 A 照片被看过进冷却后，卷 B 同 ID 的未看照片被错误抑制；
 * ② 未看照片回填按裸 ID 移除冷却，清掉他卷同 ID 已看照片的记录。
 * 两个缺陷用例在现状生产代码上必须 FAIL；控制组 PASS。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PickCooldownIdentityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences: AppPreferences get() = AppPreferences(context)
    private val engine: RecommendationEngine get() = RecommendationEngine(context)

    private fun row(volume: String, id: Long) = ImageFile(
        id = id,
        uri = Uri.parse("content://media/$volume/images/media/$id"),
        displayName = "img-$volume-$id.jpg",
        dateModified = 1_000L,
        size = 100L,
        width = 64,
        height = 64,
        bucketDisplayName = "Camera",
        volumeName = volume
    )

    @Before
    fun setUp() {
        preferences.clearAllPickRecords()
        preferences.recommendMode = com.tabula.v3.data.preferences.RecommendMode.RANDOM_WALK
    }

    @Test
    fun crossVolumeSameIdViewedOnOneVolumeDoesNotSuppressOtherVolume() = runBlocking {
        // 卷 A 的 42 已看（现状以裸 ID 记录冷却）；卷 B 同 ID 的 42 从未看过，
        // 不得被 A 的冷却抑制（batchSize=2 恰好取走全部可用行，结果确定）。
        val a = row("external_primary", 42L)
        val b = row("external_secondary", 42L)
        val c = row("external_primary", 43L)
        preferences.recordImagesPicked(listOf(42L))

        val batch = engine.getRecommendedBatch(listOf(a, b, c), batchSize = 2)

        assertTrue("同卷未看照片保持可选", c in batch)
        assertTrue("另一卷同 ID 未看照片不得被抑制", b in batch)
        assertTrue("已看照片仍在冷却", a !in batch)
    }

    @Test
    fun unviewedRemovalDoesNotEraseOtherVolumeViewedRecord() {
        // DeckScreen 未看回填（现状传裸 ID）：移除 B 行的冷却不得清除
        // 他卷同 ID（A 行）的已看记录。
        val a = row("external_primary", 42L)
        val b = row("external_secondary", 42L)
        preferences.recordImagesPicked(listOf(42L))

        engine.removeFromCooldown(listOf(b.id))

        assertTrue("他卷已看记录必须幸存", preferences.getCooldownImageIds().contains(42L))
    }

    @Test
    fun legacyBareRecordStillSuppressesSingleVolumeRow() = runBlocking {
        // 控制组：旧裸 ID 记录对单卷行仍然有效（兼容语义不回退）。
        val a = row("external_primary", 42L)
        val b = row("external_primary", 43L)
        preferences.recordImagesPicked(listOf(42L))

        val batch = engine.getRecommendedBatch(listOf(a, b), batchSize = 1)

        assertTrue("冷却中的照片不进批次", a !in batch)
        assertEquals(listOf(b), batch)
    }

    @Test
    fun singleVolumeCooldownFlowUnchanged() = runBlocking {
        // 控制组：单卷常规流——抽取后进冷却，下一批不再出现（batchSize=1）。
        val a = row("external_primary", 42L)
        val b = row("external_primary", 43L)

        val first = engine.getRecommendedBatch(listOf(a, b), batchSize = 1)
        assertEquals(1, first.size)
        val picked = first.single()

        val second = engine.getRecommendedBatch(listOf(a, b), batchSize = 1)
        assertEquals("刚抽取的照片进冷却，另一张可选", listOf(if (picked == a) b else a), second)
    }
}
