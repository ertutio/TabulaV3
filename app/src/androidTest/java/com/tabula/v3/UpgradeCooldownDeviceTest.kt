package com.tabula.v3

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.FullRecoveryStateContract.rebuildRow
import com.tabula.v3.FullRecoveryStateContract.stateFile
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.AlbumCleanupEngine
import com.tabula.v3.data.repository.LocalImageRepository
import com.tabula.v3.data.repository.RecycleBinManager
import com.tabula.v3.data.repository.RecommendationEngine
import com.tabula.v3.data.repository.mediaIdentityKey
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 任务 B（有效冷却 + 非空清理进度跨升级）——升级后验证（NEW 分支 instrumentation）。
 *
 * 宿主（tools/run-upgrade-probe-cooldown.sh）先行建立种子：安装 OLD(43a8da1)
 * app + androidTest → 运行 UpgradeCooldownSeedTest（140 张合成候选 > 2×batchSize，
 * 冷却目标经真实生产推荐入口 recordImagesPickedByIdentity 进入冷却；三组清理
 * 进度经生产写入口建立）→ root 直读 prefs 门 → `am start` OLD 真实生产 deck
 * 取批 → 冷却记录仍非空门 → `adb install -r` 覆盖安装 NEW → 安装 NEW
 * androidTest APK → 本测试。
 *
 * 两个用例由宿主分开驱动（之间宿主执行两次 am start/force-stop 冷重启）：
 * 1. verifyCooldownAndProgressSurvive：冷却记录保留（键一致、pickedAt/days 逐位）、
 *    目标继续受冷却约束（真实引擎入口不被推荐）、无关媒体仍可推荐；三组进度
 *    （部分处理续接 / 永久完成不复活 / 无关保留）按夹具逐项核对。
 * 2. verifyCooldownIdempotentAfterRestarts：快照逐字段复检（重启 ×2 后冷却目标
 *    条目、断点、acknowledged、永久标记零漂移）+ 续接语义复检。
 *
 * 断言纪律：预期全部来自种子固化的夹具 JSON（upgrade-cooldown-expected-state.json），
 * 不用被测算法重算；失败留证（withEvidence 落盘后重抛）；所有读取为同步生产
 * 读回（无裸 sleep；无"assertFalse 也通过"的空等待）。
 * 判据映射见 coord/notes-RB.md §3。
 */
@RunWith(AndroidJUnit4::class)
class UpgradeCooldownDeviceTest {

    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext

    companion object {
        const val FIXTURE_FILE = UpgradeCooldownSeedTest.FIXTURE_FILE
        const val SNAPSHOT_FILE = "upgrade-cooldown-snapshot.json"
        const val EVIDENCE_FILE = "upgrade-cooldown-evidence.json"

        /** 幂等比对字段（重启 ×2 后逐字段零漂移）。 */
        val IDEMPOTENT_FIELDS = listOf(
            "albumId", "checkpointGroupIds", "checkpointIndex",
            "group1Members", "group2Members", "group3Members",
            "acknowledgedMembers", "permanentlyProcessedGroups",
            "recycleCount", "recycleIdentityKeys",
            "cooldownTargetPresent", "cooldownTargetPickedAt", "cooldownTargetDays"
        )

        private fun snapshotFile(ctx: android.content.Context): File =
            File(stateFile(ctx).parentFile, SNAPSHOT_FILE)

        private fun evidenceFile(ctx: android.content.Context): File =
            File(stateFile(ctx).parentFile, EVIDENCE_FILE)

        private fun fixtureFile(ctx: android.content.Context): File =
            File(stateFile(ctx).parentFile, FIXTURE_FILE)

        private fun stringListOf(array: JSONArray): List<String> =
            (0 until array.length()).map { array.getString(it) }
    }

    // ===== 失败留证 =====

    /** 失败留证：断言块抛错时把当前状态证据落盘再重抛（T13 同款纪律）。 */
    private suspend fun withEvidence(section: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            runCatching {
                val evidence = JSONObject()
                evidence.put("section", section)
                evidence.put("failure", failure.message ?: failure.javaClass.name)
                evidence.put("pid", Process.myPid())
                evidence.put("state", stateSnapshotJson(readFixture()))
                evidenceFile(ctx).writeText(evidence.toString())
            }
            throw failure
        }
    }

    // ===== 夹具与快照 =====

    private fun readFixture(): JSONObject =
        JSONObject(fixtureFile(ctx).readText())

    private fun groupById(fixture: JSONObject, index: Int): JSONObject =
        fixture.getJSONArray("groups").getJSONObject(index)

    /** 状态快照（幂等基线与复检共用口径；全部经生产读回）。 */
    private fun stateSnapshotJson(fixture: JSONObject): JSONObject {
        val albumId = fixture.getString("albumId")
        val prefs = AppPreferences.getInstance(ctx)
        val checkpoint = prefs.getAlbumCleanupCheckpoint(albumId)
        val cooldown = prefs.getPickCooldownIndex()
        val target = fixture.getJSONObject("cooldownTarget")
        val bin = kotlinx.coroutines.runBlocking {
            RecycleBinManager.getInstance(ctx).loadRecycleBin()
        }
        return JSONObject().apply {
            put("albumId", albumId)
            put("checkpointGroupIds", JSONArray(checkpoint?.first ?: emptyList<String>()))
            put("checkpointIndex", checkpoint?.second ?: -1)
            for ((slot, index) in listOf("group1Members" to 0, "group2Members" to 1, "group3Members" to 2)) {
                val group = groupById(fixture, index)
                put(
                    slot,
                    JSONArray(
                        prefs.getGroupImageIds(albumId, group.getString("groupId"))
                            ?: emptyList<String>()
                    )
                )
            }
            put("acknowledgedMembers", JSONArray(prefs.getAlbumAcknowledgedCleanupMembers(albumId).sorted()))
            put("permanentlyProcessedGroups", JSONArray(prefs.getAlbumPermanentlyProcessedGroups(albumId).sorted()))
            put("recycleCount", bin.size)
            put("recycleIdentityKeys", JSONArray(bin.map { it.mediaIdentityKey() }.sorted()))
            val entry = cooldown.byIdentityKey[target.getString("identityKey")]
            put("cooldownTargetPresent", entry != null)
            put("cooldownTargetPickedAt", entry?.pickedAt ?: -1L)
            put("cooldownTargetDays", entry?.days ?: -1)
            put("cooldownIdentityCount", cooldown.byIdentityKey.size)
            put("cooldownKeys", JSONArray(cooldown.byIdentityKey.keys.sorted()))
        }
    }

    // ===== 用例 1：冷却 + 进度跨升级幸存（判据 2/3/4/5） =====

    @Test
    fun verifyCooldownAndProgressSurvive() = kotlinx.coroutines.runBlocking {
        withEvidence("verifyCooldownAndProgressSurvive") {
            val fixture = readFixture()

            // 进程换代锚点：种子进程（升级前）≠ 本进程
            assertNotEquals(fixture.getInt("seedPid"), Process.myPid())
            val albumId = fixture.getString("albumId")
            val target = fixture.getJSONObject("cooldownTarget")
            val targetKey = target.getString("identityKey")
            val poolKeys = stringListOf(fixture.getJSONArray("recommendationPool"))
            val names = stringListOf(fixture.getJSONArray("mediaNames"))
            assertEquals("夹具自洽：池与名单必须一一对应", poolKeys.size, names.size)

            // 媒体真值（生产面）：每个种子名按最新 MediaStore 元数据重建，身份键与夹具一致
            val rows = names.map { rebuildRow(ctx, it) }
            assertEquals("种子媒体身份键必须与夹具逐一一致", poolKeys, rows.map { it.mediaIdentityKey() })
            val productionKeys = LocalImageRepository(ctx).loadAllData().allImages
                .map { it.mediaIdentityKey() }.toSet()
            assertTrue(
                "应用全域媒体读回必须包含全部夹具成员（缺：${poolKeys.filterNot { it in productionKeys }}）",
                poolKeys.all { it in productionKeys }
            )

            val prefs = AppPreferences.getInstance(ctx)
            assertEquals("batchSize 不得漂移", fixture.getInt("batchSize"), prefs.batchSize)
            assertEquals("推荐模式不得漂移", fixture.getString("recommendMode"), prefs.recommendMode.name)

            // 判据 3：冷却记录保留——NEW 生产读回非空、目标键一致、pickedAt/days 逐位、未过期
            val cooldown = prefs.getPickCooldownIndex()
            assertTrue("升级后冷却记录必须非空", cooldown.byIdentityKey.isNotEmpty())
            val entry = requireNotNull(cooldown.byIdentityKey[targetKey]) {
                "冷却目标 $targetKey 必须在升级后仍存在（含目标身份键）"
            }
            assertEquals("冷却目标 pickedAt 必须逐位一致", target.getLong("pickedAt"), entry.pickedAt)
            assertEquals("冷却目标 days 必须逐位一致", target.getInt("days"), entry.days)
            val ttlMillis = entry.days * 24L * 60 * 60 * 1000
            assertTrue(
                "按 TTL 语义目标必须仍在冷却期内（now-pickedAt=${System.currentTimeMillis() - entry.pickedAt} vs $ttlMillis）",
                System.currentTimeMillis() - entry.pickedAt < ttlMillis
            )
            assertTrue("不得出现旧裸 ID 视图记录", cooldown.byLegacyBareId.isEmpty())

            // 判据 3：目标继续受冷却约束（真实引擎入口）+ 无关媒体仍可推荐
            val recEngine = RecommendationEngine.getInstance(ctx)
            val batch = recEngine.getRecommendedBatch(rows, prefs.batchSize, identityCandidates = rows)
            assertTrue("推荐批次必须非空", batch.isNotEmpty())
            assertEquals("可用池充足时批次应满 batchSize（无关媒体仍可推荐）",
                fixture.getInt("batchSize"), batch.size)
            val batchKeys = batch.map { it.mediaIdentityKey() }
            assertTrue("被冷却的照片不得出现在推荐批次：$targetKey", targetKey !in batchKeys)
            assertTrue(
                "推荐批次必须全部来自夹具池（越池：${batchKeys.filterNot { it in poolKeys }}）",
                batchKeys.all { it in poolKeys }
            )

            // 判据 4：非空 acknowledged 与永久完成——按夹具逐项核对
            val g1 = groupById(fixture, 0)
            val g2 = groupById(fixture, 1)
            val g3 = groupById(fixture, 2)
            val expectedAck = (stringListOf(g1.getJSONArray("acknowledged")) +
                stringListOf(g2.getJSONArray("acknowledged"))).sorted()
            assertEquals(
                "已确认成员必须与夹具一致（组 1 部分 + 组 2 全员）",
                expectedAck, prefs.getAlbumAcknowledgedCleanupMembers(albumId).sorted()
            )
            assertEquals(
                "永久完成标记必须与夹具一致（恰组 2）",
                stringListOf(fixture.getJSONArray("permanentlyProcessedGroups")),
                prefs.getAlbumPermanentlyProcessedGroups(albumId).sorted()
            )

            // 判据 4：磁盘断点幸存——组 1 断点指向剩余成员
            assertEquals(
                "断点必须跨版本续接（组 1 + 位置 1）",
                stringListOf(fixture.getJSONObject("checkpoint").getJSONArray("groupIds")) to
                    fixture.getJSONObject("checkpoint").getInt("index"),
                prefs.getAlbumCleanupCheckpoint(albumId)
            )

            // 判据 4：续接/不复活/保留——引擎交付必须与夹具期望批次逐项一致
            val engine = AlbumCleanupEngine(ctx)
            val next = requireNotNull(engine.getNextBatch(albumId, rows)) {
                "升级后引擎必须仍能取批（进度不得丢失）"
            }
            val expectedBatch = fixture.getJSONObject("expectedBatch")
            assertEquals(
                "取批组顺序必须与夹具一致（组 3 保留 + 组 1 剩余续接；组 2 永久完成不复活）",
                stringListOf(expectedBatch.getJSONArray("groupIds")),
                next.groupIds
            )
            assertEquals(
                "取批行必须与夹具一致（组 1 已确认成员排除、组 2 不出现）",
                stringListOf(expectedBatch.getJSONArray("images")),
                next.images.map { it.mediaIdentityKey() }
            )
            assertTrue(
                "永久完成的组 2 不得复活进批次",
                next.groupIds.none { it == g2.getString("groupId") }
            )

            // 组进度未被复活成回收条目
            val trash = RecycleBinManager.getInstance(ctx)
            assertEquals("回收站必须保持空（组 2 处理不得被复活为待恢复记录）",
                emptyList<com.tabula.v3.data.model.ImageFile>(), trash.loadRecycleBin())

            // 快照落盘为幂等基线
            val snapshot = stateSnapshotJson(fixture)
            snapshot.put("phase2Pid", Process.myPid())
            snapshotFile(ctx).writeText(snapshot.toString())
        }
    }

    // ===== 用例 2：重启 ×2 后幂等 + 续接语义复检（判据 3 末句 / 4） =====

    @Test
    fun verifyCooldownIdempotentAfterRestarts() = kotlinx.coroutines.runBlocking {
        withEvidence("verifyCooldownIdempotentAfterRestarts") {
            val fixture = readFixture()
            val baseline = JSONObject(snapshotFile(ctx).readText())
            val current = stateSnapshotJson(fixture)
            val drifted = IDEMPOTENT_FIELDS.filter {
                baseline.opt(it)?.toString() != current.opt(it)?.toString()
            }
            assertTrue(
                "重启两次后状态不得漂移（漂移字段：$drifted；基线=$baseline；当前=$current）",
                drifted.isEmpty()
            )
            // 冷却总量允许合法增长（重启 deck 取批），但目标条目必须在
            assertTrue("重启后冷却记录必须仍非空", current.getInt("cooldownIdentityCount") >= 1)
            assertTrue("冷却目标必须仍在冷却池", current.getBoolean("cooldownTargetPresent"))

            val albumId = fixture.getString("albumId")
            val rows = stringListOf(fixture.getJSONArray("mediaNames")).map { rebuildRow(ctx, it) }
            val prefs = AppPreferences.getInstance(ctx)

            // 生产续接语义复检（AlbumCleanupEngine 795-802 规则跨版本一致）：
            // 断点组内含已确认成员 → getCheckpointBatch 安全放弃（清断点、返回 null），
            // 由正常批次入口从剩余行重开。先按夹具断言磁盘断点仍在，再驱动该规则。
            val checkpointFixture = fixture.getJSONObject("checkpoint")
            assertEquals(
                "重启后磁盘断点必须仍在",
                stringListOf(checkpointFixture.getJSONArray("groupIds")) to
                    checkpointFixture.getInt("index"),
                prefs.getAlbumCleanupCheckpoint(albumId)
            )
            val engine = AlbumCleanupEngine(ctx)
            assertTrue(
                "断点组含已确认成员时生产规则必须安全放弃旧索引（返回 null）",
                engine.getCheckpointBatch(albumId, rows) == null
            )

            // 续接复检：getNextBatch 仍交付夹具期望批次（组 1 剩余成员 + 组 3；组 2 不复活）
            val next = requireNotNull(engine.getNextBatch(albumId, rows)) {
                "重启后引擎必须仍能取批"
            }
            val expectedBatch = fixture.getJSONObject("expectedBatch")
            assertEquals(
                "重启后取批组顺序必须与夹具一致",
                stringListOf(expectedBatch.getJSONArray("groupIds")),
                next.groupIds
            )
            assertEquals(
                "重启后取批行必须与夹具一致",
                stringListOf(expectedBatch.getJSONArray("images")),
                next.images.map { it.mediaIdentityKey() }
            )

            // 冷却约束复检（真实引擎入口）：目标仍不被推荐，无关媒体照常
            val targetKey = fixture.getJSONObject("cooldownTarget").getString("identityKey")
            val poolKeys = stringListOf(fixture.getJSONArray("recommendationPool"))
            val batch = RecommendationEngine.getInstance(ctx)
                .getRecommendedBatch(rows, prefs.batchSize, identityCandidates = rows)
            assertTrue("重启后推荐批次必须非空（空批会使后续包含断言空转通过）", batch.isNotEmpty())
            val batchKeys = batch.map { it.mediaIdentityKey() }
            assertTrue("重启后被冷却照片仍不得出现在推荐批次：$targetKey", targetKey !in batchKeys)
            assertTrue("重启后推荐批次必须全部来自夹具池", batchKeys.all { it in poolKeys })
        }
    }
}
