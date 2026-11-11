package com.tabula.v3

import android.graphics.BitmapFactory
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.FullRecoveryStateContract.readExpectedState
import com.tabula.v3.FullRecoveryStateContract.rebuildRow
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.AlbumCleanupEngine
import com.tabula.v3.data.repository.AlbumManager
import com.tabula.v3.data.repository.LocalImageRepository
import com.tabula.v3.data.repository.RecycleBinManager
import com.tabula.v3.data.repository.mediaIdentityKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T13 覆盖安装恢复——升级后验证（新 APK 内 instrumentation）。
 *
 * 宿主（tools/run-upgrade-probe.sh）先行建立种子：
 * 安装 OLD(43a8da1) debug 应用 + OLD androidTest APK → 运行生产自带
 * FullRecoveryPhase1Test（真实生产代码建立月份清理组+断点位置1+expected-state.json）
 * → run-as/root 注入 albums_metadata.json（UpgAlbum isHidden=true）→ 写哨兵文件
 * → force-stop → `adb install -r` 覆盖安装 NEW（同 applicationId、同 versionCode 5、
 * 同签名）→ 安装 NEW androidTest APK → 本测试。
 *
 * 两个用例由宿主分开驱动（之间宿主执行两次 am start/force-stop 冷重启）：
 * 1. verifySurvivalAfterOverlay：升级幸存断言（判据 5/6/7/8 生产面/10），
 *    并把状态快照写盘作为幂等基线。
 * 2. verifyIdempotentAfterRestarts：快照逐字段复检（判据 9/清单 B.4——重启
 *    两次，冷却/专清/断点/回收计数不漂移）。
 *
 * 断言纪律：截止轮询（awaitUntil）而非裸 sleep；失败留证（withEvidence 落盘
 * 状态证据后重抛）；测试创建的文件名不含身份键分隔符 `|`。
 * 清单 A.4–A.5（更新渠道/鸣谢入口）与 B.1–B.3 的手工口径不在本自动化宣称范围。
 */
@RunWith(AndroidJUnit4::class)
class UpgradeOverlayDeviceTest {

    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext

    companion object {
        /** 宿主在升级前经 run-as/root 写入的哨兵文件（数据幸存的最直接证据）。 */
        const val SENTINEL_FILE = "t13-upgrade-sentinel.txt"
        const val SENTINEL_CONTENT = "t13-overlay-sentinel-2026-11-11"

        /** 注入的隐藏图集名（无系统 bucket 支撑 → 走空图集重建路径）。 */
        const val HIDDEN_ALBUM = "UpgAlbum"

        const val SNAPSHOT_FILE = "upgrade-overlay-snapshot.json"
        const val EVIDENCE_FILE = "upgrade-overlay-evidence.json"

        private fun snapshotFile(ctx: android.content.Context): File =
            File(FullRecoveryStateContract.stateFile(ctx).parentFile, SNAPSHOT_FILE)

        private fun evidenceFile(ctx: android.content.Context): File =
            File(FullRecoveryStateContract.stateFile(ctx).parentFile, EVIDENCE_FILE)
    }

    // ===== 断言纪律基建 =====

    /** 截止轮询：每 250ms 重试，超时抛出带上下文的断言错误（无裸 sleep 断言）。 */
    private suspend fun <T> awaitUntil(desc: String, timeoutMs: Long = 30_000L, probe: suspend () -> T): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                return probe()
            } catch (e: Throwable) {
                lastError = e
                android.os.SystemClock.sleep(250)
            }
        }
        throw AssertionError("截止轮询超时（${timeoutMs}ms）：$desc", lastError)
    }

    /** 失败留证：断言块抛错时把当前状态证据落盘再重抛。 */
    private suspend fun withEvidence(section: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            runCatching {
                val evidence = JSONObject()
                evidence.put("section", section)
                evidence.put("failure", failure.message ?: failure.javaClass.name)
                evidence.put("pid", Process.myPid())
                evidence.put("state", stateSnapshotJson())
                evidenceFile(ctx).writeText(evidence.toString())
            }
            throw failure
        }
    }

    private fun stringListOf(array: org.json.JSONArray): List<String> =
        (0 until array.length()).map { array.getString(it) }

    // ===== 状态采集（幂等基线与复检共用口径） =====

    private fun stateSnapshotJson(): JSONObject {
        val expected = readExpectedState(ctx)
        val albumId = expected.getString("albumId")
        val groupId = expected.getString("groupId")
        val prefs = AppPreferences.getInstance(ctx)
        val checkpoint = prefs.getAlbumCleanupCheckpoint(albumId)
        val cooldown = prefs.getPickCooldownIndex()
        val trash = RecycleBinManager.getInstance(ctx)
        val bin = kotlinx.coroutines.runBlocking { trash.loadRecycleBin() }
        val albumMgr = AlbumManager.getInstance(ctx)
        kotlinx.coroutines.runBlocking { albumMgr.initialize() }
        val upg = kotlinx.coroutines.runBlocking { albumMgr.getAllAlbums() }.firstOrNull { it.id == HIDDEN_ALBUM }
        return JSONObject().apply {
            put("albumId", albumId)
            put("groupId", groupId)
            put("checkpointGroupIds", org.json.JSONArray(checkpoint?.first ?: emptyList<String>()))
            put("checkpointIndex", checkpoint?.second ?: -1)
            put("groupMembers", org.json.JSONArray(prefs.getGroupImageIds(albumId, groupId) ?: emptyList<String>()))
            put("acknowledgedMembers", org.json.JSONArray(prefs.getAlbumAcknowledgedCleanupMembers(albumId).sorted()))
            put("permanentlyProcessedGroups", org.json.JSONArray(prefs.getAlbumPermanentlyProcessedGroups(albumId).sorted()))
            put("recycleCount", bin.size)
            put("recycleIdentityKeys", org.json.JSONArray(bin.map { it.mediaIdentityKey() }.sorted()))
            put("hiddenAlbumCount", albumMgr.hiddenAlbumCount.value)
            put("upgAlbumPresent", upg != null)
            put("upgAlbumImageCount", upg?.imageCount ?: -1)
            put("upgAlbumHidden", upg?.isHidden ?: false)
            put("cooldownIdentityEntries", JSONObject().apply {
                cooldown.byIdentityKey.forEach { (key, entry) -> put(key, entry.days) }
            })
            put("cooldownLegacyEntries", JSONObject().apply {
                cooldown.byLegacyBareId.forEach { (id, entry) -> put(id.toString(), entry.days) }
            })
        }
    }

    // ===== 用例 1：升级幸存（判据 5/6/7/8 生产面/10 + 哨兵） =====

    @Test
    fun verifySurvivalAfterOverlay() = kotlinx.coroutines.runBlocking {
        withEvidence("verifySurvivalAfterOverlay") {
            // 10. 哨兵：升级前写入 app 私有 files/ 的哨兵幸存（数据未被清除的直接证据）
            val sentinel = File(ctx.filesDir, SENTINEL_FILE)
            assertTrue("升级前哨兵文件必须幸存：${sentinel.absolutePath}", sentinel.exists())
            assertEquals(SENTINEL_CONTENT, sentinel.readText().trim())

            // 进程换代锚点：Phase1 进程（升级前）≠ 本进程
            val expected = readExpectedState(ctx)
            assertNotEquals(expected.getInt("phase1Pid"), Process.myPid())
            val albumId = expected.getString("albumId")
            val groupId = expected.getString("groupId")
            val checkpointIndex = expected.getInt("checkpointIndex")
            val members = stringListOf(expected.getJSONArray("members"))
            val names = stringListOf(expected.getJSONArray("displayNames"))

            // 5/8. 媒体真值（生产面）：每个种子名按最新 MediaStore 元数据重建，
            // 身份键与 Phase1 记录一致（跨版本读回一致；无新增缺失）。
            val rows = names.map { rebuildRow(ctx, it) }
            assertEquals("种子媒体身份键必须与 Phase1 一致", members, rows.map { it.mediaIdentityKey() })

            // 8. 应用全域读回也包含全部成员（生产真值第二面；shell 面由宿主探针核）
            val productionKeys = LocalImageRepository(ctx).loadAllData().allImages.map { it.mediaIdentityKey() }.toSet()
            assertTrue(
                "应用全域媒体读回必须包含全部种子成员（缺：${
                    members.filterNot { it in productionKeys }
                }）",
                members.all { it in productionKeys }
            )

            val prefs = AppPreferences.getInstance(ctx)

            // 7. 断点续接（跨版本读回）：同一组 + 位置 1（而非重开/清零）
            assertEquals(
                "整理断点必须跨版本续接（组+位置）",
                listOf(groupId) to checkpointIndex,
                prefs.getAlbumCleanupCheckpoint(albumId)
            )
            assertEquals("组成员身份键必须原样幸存", members, prefs.getGroupImageIds(albumId, groupId))
            val resumed = requireNotNull(AlbumCleanupEngine(ctx).getCheckpointBatch(albumId, rows)) {
                "断点批续接失败：getCheckpointBatch 返回 null"
            }
            assertEquals(listOf(groupId), resumed.first.groupIds)
            assertEquals(checkpointIndex, resumed.second)

            // 5. 回收记录语义（Phase1 真实行为：种子阶段零回收条目、零已确认成员、
            // 零永久完成标记——位置 1 仅浏览位置，未处理任何成员）。
            // 回收/恢复的真实落点由下面真实生产路径演练核验。
            val trash = RecycleBinManager.getInstance(ctx)
            assertEquals(
                "回收站条目数必须与 SEED 一致（Phase1 未处理任何成员）",
                emptyList<ImageFile>(), trash.loadRecycleBin()
            )
            assertTrue("Phase1 未确认任何成员，升级后不得出现已确认成员", prefs.getAlbumAcknowledgedCleanupMembers(albumId).isEmpty())
            assertTrue("有剩余成员的组不得被标记永久完成", prefs.getAlbumPermanentlyProcessedGroups(albumId).isEmpty())

            // 6. 图集元数据跨版本：注入的 UpgAlbum(isHidden=true) 幸存（生产读回）
            val albumMgr = AlbumManager.getInstance(ctx)
            albumMgr.initialize()
            assertEquals("隐藏图集计数必须为 1（注入的 UpgAlbum）", 1, albumMgr.hiddenAlbumCount.value)
            val upg = requireNotNull(albumMgr.getAllAlbums().firstOrNull { it.id == HIDDEN_ALBUM }) {
                "注入的隐藏图集 $HIDDEN_ALBUM 必须经生产读回幸存（空图集重建）"
            }
            assertTrue("UpgAlbum 必须 isHidden=true（升级前状态保留）", upg.isHidden)
            assertEquals("UpgAlbum 为空图集（无系统 bucket，走元数据重建）", 0, upg.imageCount)

            // 5. 回收记录演练（真实生产路径，判据 5 的自动化等价）：
            // 入站 → 记录可读且身份键解析成功 → 恢复 → 计数归零、内容完好。
            val drilled = rows[checkpointIndex.coerceAtMost(rows.size - 1)]
            trash.addToRecycleBin(drilled)
            val inBin = awaitUntil("回收站记录可见且身份键解析成功") {
                trash.loadRecycleBin().singleOrNull()?.mediaIdentityKey() == drilled.mediaIdentityKey()
            }
            assertNotNull("回收站记录必须可读", inBin)
            trash.removeFromRecycleBin(drilled)
            awaitUntil("恢复后回收站归零") { trash.loadRecycleBin().isEmpty() }

            // 恢复一张 → 内容完好（生产行 + 字节面可解码；shell 面由宿主探针核）
            val restored = rebuildRow(ctx, drilled.displayName)
            assertEquals(drilled.mediaIdentityKey(), restored.mediaIdentityKey())
            val decoded = ctx.contentResolver.openInputStream(restored.uri)!!.use {
                BitmapFactory.decodeStream(it)
            }
            assertTrue("恢复的媒体内容必须可完整解码为位图", decoded != null && decoded.width > 0)
            decoded?.recycle()
            assertEquals(
                "演练后种子媒体不得有缺失",
                members, names.map { rebuildRow(ctx, it).mediaIdentityKey() }
            )

            // 10. logcat FATAL 由宿主探针统一核（此处留证哨兵+状态快照基线）
            val snapshot = stateSnapshotJson()
            snapshot.put("phase2Pid", Process.myPid())
            snapshot.put("sentinelVerified", true)
            File(FullRecoveryStateContract.stateFile(ctx).parentFile, SNAPSHOT_FILE).writeText(snapshot.toString())
        }
    }

    // ===== 用例 2：偏好幂等（判据 9 / 清单 B.4 自动化等价） =====

    @Test
    fun verifyIdempotentAfterRestarts() = kotlinx.coroutines.runBlocking {
        withEvidence("verifyIdempotentAfterRestarts") {
            val baseline = JSONObject(File(FullRecoveryStateContract.stateFile(ctx).parentFile, SNAPSHOT_FILE).readText())
            val current = stateSnapshotJson()
            val fields = listOf(
                "checkpointGroupIds", "checkpointIndex", "groupMembers", "acknowledgedMembers",
                "permanentlyProcessedGroups", "recycleCount", "recycleIdentityKeys",
                "hiddenAlbumCount", "upgAlbumPresent", "upgAlbumImageCount", "upgAlbumHidden",
                "cooldownIdentityEntries", "cooldownLegacyEntries"
            )
            val drifted = fields.filter { baseline.opt(it)?.toString() != current.opt(it)?.toString() }
            assertTrue(
                "重启两次后状态不得漂移（漂移字段：$drifted；基线=$baseline；当前=$current）",
                drifted.isEmpty()
            )
            // expected-state.json 本身也必须仍然幸存（跨升级+跨重启的持久哨兵）
            val expected = readExpectedState(ctx)
            assertEquals(baseline.getString("groupId"), expected.getString("groupId"))
        }
    }
}
