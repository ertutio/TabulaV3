package com.tabula.v3

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.PickCooldownEntry
import com.tabula.v3.data.preferences.RecommendMode
import com.tabula.v3.data.preferences.SwipeStyle
import com.tabula.v3.data.repository.AlbumCleanupEngine
import com.tabula.v3.data.repository.RecommendationEngine
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.FullRecoveryStateContract.flushCleanupPrefs
import com.tabula.v3.FullRecoveryStateContract.groupIdOf
import com.tabula.v3.FullRecoveryStateContract.rebuildRow
import com.tabula.v3.FullRecoveryStateContract.seedPhoto
import com.tabula.v3.FullRecoveryStateContract.stateFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 任务 B（升级冷却 + 非空清理进度跨升级）——OLD(43a8da1) 侧种子。
 *
 * 设计与判据：coord/notes-RB.md（T13 缺口之二：注入的冷却种子在
 * `allImages.size <= batchSize` 时被生产 `clearAllPickRecords()` 合法清空——
 * 模拟器仅 3 张照片）。本种子改为：
 * 1. 合成 **140 张**真实 MediaStore JPEG（> 2×batchSize），生产清理分支永不触发；
 * 2. 冷却目标经 **真实生产推荐入口** `RecommendationEngine.getRecommendedBatch`
 *    （MainActivity→DeckScreen 同款调用）进入 `recordImagesPickedByIdentity`——
 *    确定性论证：每批 take(15) 抽可用集，可用 ≤ 15 时全取，T 未抽中则 T 必在
 *    可用集 → 最迟第 ceil(140/15)=10 批内 T 必被记录（循环 bound 12）；
 * 3. 定形：`removeImagesFromCooldownByIdentity`（生产"切换算法移除未浏览"入口）
 *    把冷却定形为恰 {目标}，pickedAt/days 固化进夹具；
 * 4. 三组清理进度经生产写入口建立（acknowledged/永久完成/断点）：
 *    组 1 = 部分处理（m1 已确认，断点指向剩余 m2/m3）；组 2 = 全员确认 →
 *    引擎 getNextBatch 生产对账自动打永久标记（AlbumCleanupEngine
 *    fullyRecycledGroupIds 路径）；组 3 = 无关进度保留；
 * 5. 夹具 JSON（成员/顺序/状态/期望批次）写 getExternalFilesDir，预期全部来自
 *    OLD 实拍（不含被测算法重算），升级后由 UpgradeCooldownDeviceTest 逐项核对。
 *
 * 本文件同时随 NEW 分支构建（同一 API 面，43a8da1 与 head 对相关生产文件
 * diff 为空），workflow build job 把它拷入 43a8da1 worktree 并以 digest 门
 * 证明生产源码零修改；设备面只由 OLD androidTest APK 驱动本类做种子。
 */
@RunWith(AndroidJUnit4::class)
class UpgradeCooldownSeedTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext

    companion object {
        /** 夹具文件名（与 T13 的 full-recovery-expected-state.json 不同名，零共享）。 */
        const val FIXTURE_FILE = "upgrade-cooldown-expected-state.json"

        /** 库量：10 清理组成员 + 冷却目标 + 129 陪跑（> 2×batchSize，清理分支永不触发）。 */
        const val LIBRARY_SIZE = 140

        /** 组成员数：g1=3（1 确认 2 剩余）、g2=3（全员确认→永久完成）、g3=4（无关保留）。 */
        const val G1_SIZE = 3
        const val G2_SIZE = 3
        const val G3_SIZE = 4

        /** T13 flushCleanupPrefs 同款：pick prefs 无公开 flush 通道，生产常量私有时同值重写触发 commit。 */
        const val PICK_PREFS_NAME = "tabula_pick_timestamps"
    }

    @Before fun setup() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        org.junit.Assert.assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ctx.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
        )
        AppPreferences(ctx).apply {
            hasCompletedOnboarding = true
            hasAcknowledgedDisclaimer = true
            swipeStyle = SwipeStyle.SHUFFLE
        }
        val prefs = AppPreferences.getInstance(ctx)
        // 种子卫生：清理状态归零（生产重置入口）；冷却池清空（生产小库清理入口，
        // 语义等价于引擎在库 ≤ batchSize 时的自愈——此处仅作测试前置复位）。
        prefs.clearAllAlbumCleanupState()
        prefs.clearAllPickRecords()
        Unit
    }

    @Test
    fun seedCooldownAndProgressFixture() = runBlocking {
        val prefs = AppPreferences.getInstance(ctx)

        // 生产推荐面基线断言（固化进夹具，升级后不得漂移）
        assertEquals("推荐模式必须为随机漫步（生产默认）", RecommendMode.RANDOM_WALK, prefs.recommendMode)
        val batchSize = prefs.batchSize
        assertEquals("batchSize 必须为生产默认 15", 15, batchSize)

        // ===== 1. 合成 140 张真实 MediaStore JPEG 并按显示名重建行 =====
        val uris = (1..LIBRARY_SIZE).map { seedPhoto(ctx, it) }
        val names = uris.map { uri ->
            requireNotNull(
                ctx.contentResolver.query(
                    uri,
                    arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
                    null, null, null
                )
            ) { "MediaStore query returned null" }.use { c ->
                check(c.moveToFirst()) { "No row for $uri" }
                c.getString(0)
            }
        }
        val allRows = names.map { rebuildRow(ctx, it) }
        assertEquals(LIBRARY_SIZE, allRows.size)
        val poolKeys = allRows.map { it.mediaIdentityKey() }
        assertEquals("身份键必须唯一（跨卷同 ID 不允许出现在夹具）", poolKeys.toSet(), poolKeys.toList().toSet())

        // ===== 2. 三组清理进度（生产写入口，Phase1 seedCleanupGroup 模式扩为 3 组） =====
        val g1Rows = allRows.subList(0, G1_SIZE)
        val g2Rows = allRows.subList(G1_SIZE, G1_SIZE + G2_SIZE)
        val g3Rows = allRows.subList(G1_SIZE + G2_SIZE, G1_SIZE + G2_SIZE + G3_SIZE)
        val targetRow = allRows[G1_SIZE + G2_SIZE + G3_SIZE]
        val targetKey = targetRow.mediaIdentityKey()

        val albumId = com.tabula.v3.ui.components.buildMonthInfos(g1Rows + g2Rows + g3Rows)
            .single().cleanupAlbumId
        val g1Id = groupIdOf(g1Rows)
        val g2Id = groupIdOf(g2Rows)
        val g3Id = groupIdOf(g3Rows)
        check(setOf(g1Id, g2Id, g3Id).size == 3) { "组 ID 必须两两可区分" }

        prefs.saveAlbumAnalysisResult(albumId, 3, listOf(g1Id, g2Id, g3Id))
        prefs.saveGroupImageCounts(
            albumId,
            mapOf(g1Id to g1Rows.size, g2Id to g2Rows.size, g3Id to g3Rows.size)
        )
        prefs.saveAlbumTotalImages(albumId, G1_SIZE + G2_SIZE + G3_SIZE)
        prefs.saveGroupsImageIds(
            albumId,
            mapOf(
                g1Id to g1Rows.map { it.mediaIdentityKey() },
                g2Id to g2Rows.map { it.mediaIdentityKey() },
                g3Id to g3Rows.map { it.mediaIdentityKey() }
            )
        )

        // 生产确认入口（commit()）：组 1 的 m1 + 组 2 全员
        val g1Ack = g1Rows.first().mediaIdentityKey()
        prefs.acknowledgeCleanupMembers(
            (setOf(g1Ack) + g2Rows.map { it.mediaIdentityKey() }.toSet())
        )

        // ===== 3. 引擎一次取批：生产对账把全员已确认的 g2 打上永久标记 =====
        val engine = AlbumCleanupEngine(ctx)
        val batch = requireNotNull(engine.getNextBatch(albumId, allRows)) {
            "cleanup engine returned no batch for the seeded groups"
        }
        // g3(4 成员) 按恢复排序先于 g1(3 成员)，g2 已被永久标记排除；组 1 交付行 = 成员 − m1
        val expectedBatchIds = listOf(g3Id, g1Id)
        val expectedBatchKeys = g3Rows.map { it.mediaIdentityKey() } +
            g1Rows.drop(1).map { it.mediaIdentityKey() }
        assertEquals("取批组顺序必须确定（按成员数降序，永久完成组排除）",
            expectedBatchIds, batch.groupIds)
        assertEquals("取批行必须为 g3 全员 + 组 1 剩余成员（已确认的 m1 排除）",
            expectedBatchKeys, batch.images.map { it.mediaIdentityKey() })
        assertEquals("引擎生产对账必须已把全员确认的 g2 标记永久完成",
            setOf(g2Id), prefs.getAlbumPermanentlyProcessedGroups(albumId))

        // 断点：指向组 1 剩余成员（位置 1 = m2 起始）
        engine.saveCheckpoint(listOf(g1Id), 1)
        assertEquals(
            "断点必须可读回（组+位置）",
            listOf(g1Id) to 1,
            prefs.getAlbumCleanupCheckpoint(albumId)
        )

        // ===== 4. 冷却目标经真实生产推荐入口进入冷却（确定性循环） =====
        val recEngine = RecommendationEngine.getInstance(ctx)
        var attempts = 0
        var targetEntry: PickCooldownEntry? = null
        while (attempts < 12) {
            attempts++
            recEngine.getRecommendedBatch(allRows, batchSize, identityCandidates = allRows)
            targetEntry = prefs.getPickCooldownIndex().byIdentityKey[targetKey]
            if (targetEntry != null) break
        }
        assertTrue(
            "目标照片必须已经生产推荐流程真实进入冷却（attempts=$attempts，" +
                "数学下界：可用 ≤ batchSize 时 take 全取，最迟第 ${LIBRARY_SIZE / batchSize + 1} 批命中）",
            targetEntry != null
        )
        val picked = requireNotNull(targetEntry)
        assertTrue("冷却天数必须为生产轮转值 7/12/24 之一", picked.days in listOf(7, 12, 24))

        // ===== 5. 定形：冷却恰 = {目标}（生产"移除未浏览"入口），无关媒体全部可用 =====
        val idx = prefs.getPickCooldownIndex()
        val freeKeys = (idx.byIdentityKey.keys - targetKey) +
            idx.byLegacyBareId.keys.map { it.toString() }
        prefs.removeImagesFromCooldownByIdentity(freeKeys)
        val shaped = prefs.getPickCooldownIndex()
        assertEquals("定形后冷却必须恰含目标一个身份键", setOf(targetKey), shaped.byIdentityKey.keys)
        assertTrue("不得残留旧裸 ID 记录", shaped.byLegacyBareId.isEmpty())

        // 同值重写触发 commit 同步落盘（含此前 apply() 的批记录与 days 写入）
        assertTrue(
            "pick prefs 必须同步落盘",
            ctx.getSharedPreferences(PICK_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong("time_$targetKey", picked.pickedAt).commit()
        )
        assertTrue("cleanup prefs 必须同步落盘（组/成员/断点全部生产键）", flushCleanupPrefs(ctx))

        // ===== 6. 夹具 JSON：预期全部来自 OLD 实拍（升级后逐项核对，不重算） =====
        fun groupJson(groupId: String, rows: List<com.tabula.v3.data.model.ImageFile>, ack: List<String>) =
            org.json.JSONObject().apply {
                put("groupId", groupId)
                put("members", org.json.JSONArray(rows.map { it.mediaIdentityKey() }))
                put("displayNames", org.json.JSONArray(rows.map { it.displayName }))
                put("acknowledged", org.json.JSONArray(ack))
            }

        val fixture = org.json.JSONObject().apply {
            put("fixture", "upgrade-cooldown-task-b")
            put("seedPid", Process.myPid())
            put("batchSize", batchSize)
            put("recommendMode", prefs.recommendMode.name)
            put("librarySize", LIBRARY_SIZE)
            put("albumId", albumId)
            put("checkpoint", org.json.JSONObject().apply {
                put("groupIds", org.json.JSONArray(listOf(g1Id)))
                put("index", 1)
            })
            put("groups", org.json.JSONArray().apply {
                put(groupJson(g1Id, g1Rows, listOf(g1Ack)))
                put(groupJson(g2Id, g2Rows, g2Rows.map { it.mediaIdentityKey() }))
                put(groupJson(g3Id, g3Rows, emptyList()))
            })
            put("permanentlyProcessedGroups", org.json.JSONArray(listOf(g2Id)))
            put("expectedBatch", org.json.JSONObject().apply {
                put("groupIds", org.json.JSONArray(expectedBatchIds))
                put("images", org.json.JSONArray(expectedBatchKeys))
            })
            put("cooldownTarget", org.json.JSONObject().apply {
                put("identityKey", targetKey)
                put("pickedAt", picked.pickedAt)
                put("days", picked.days)
                put("displayName", targetRow.displayName)
            })
            put("recommendationPool", org.json.JSONArray(poolKeys))
            put("mediaNames", org.json.JSONArray(names))
        }
        val fixtureFile = File(stateFile(ctx).parentFile, FIXTURE_FILE)
        fixtureFile.writeText(fixture.toString())
        assertTrue("夹具必须非空落盘", fixtureFile.length() > 0)
    }
}
