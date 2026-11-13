package com.tabula.v3

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.FullRecoveryStateContract.rebuildRow
import com.tabula.v3.FullRecoveryStateContract.seedPhoto
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.RecycleBinManager
import com.tabula.v3.data.repository.mediaIdentityKey
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 任务 A（升级回收记录）——OLD(43a8da1) 侧种子测试。
 *
 * 经该版本**真实生产写入口** `RecycleBinManager.addAllToRecycleBin`（生产调用链
 * MainActivity.onRemove → addToRecycleBin 即委托本入口）建立非空回收记录：
 * 3 个可区分成员（UUID displayName + 互异颜色媒体）+ 1 个集合外哨兵 s1（不入站）。
 *
 * 本文件随 workflow 构建期拷入 43a8da1 worktree 的 androidTest 源构建 OLD test APK；
 * 只引用 OLD/NEW 逐字节一致的类（RecycleBinManager/ImageFile/FullRecoveryStateContract/
 * LocalImageRepository），因此同时编入 NEW test APK（NEW probe 不驱动本类）。
 * OLD 生产源码零触碰由 workflow 的 app/src/main digest 前后相等门核验。
 *
 * 判据 2 的 app 查询面在本测试内自证后才写期望状态：
 * files/upgrade-recycle-expected-state.json（members 全字段 + restoredCandidate +
 * outsideSentinel + seedPid），供宿主探针 pull 与 NEW 侧 UpgradeRecycleDeviceTest
 * 跨升级读取。recycle_bin.json 的落盘由生产 AtomicRecycleBinStorage 自证
 * （fd.sync + finishWrite + 读回不等即抛 IOException），种子侧不做额外 flush。
 */
@RunWith(AndroidJUnit4::class)
class UpgradeRecycleSeedTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext

    companion object {
        /** 期望状态文件名（与 T13 的 full-recovery-expected-state.json 区分）。 */
        const val EXPECTED_STATE_FILE = "upgrade-recycle-expected-state.json"

        /** 回收成员数（m1..m3，均经生产写入口入站）。 */
        const val MEMBER_COUNT = 3

        /** 集合外哨兵（不入站的第 4 张合成媒体）。 */
        const val SENTINEL_INDEX = 4

        /** 期望状态文件句柄（外部 files 目录，跨升级持久、宿主可 pull）。 */
        fun expectedStateFile(context: android.content.Context) = java.io.File(
            FullRecoveryStateContract.stateFile(context).parentFile, EXPECTED_STATE_FILE
        )
    }

    @Before fun setup() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        org.junit.Assert.assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
        )
        AppPreferences(ctx).apply {
            hasCompletedOnboarding = true
            hasAcknowledgedDisclaimer = true
        }
        // 重跑确定性：先经生产入口清站（fresh emulator 本为空，重试时清残留）。
        RecycleBinManager.getInstance(ctx).clearRecycleBin()
        Unit
    }

    @Test
    fun seedRecycleRecordsViaProductionEntry() = runBlocking {
        val trash = RecycleBinManager.getInstance(ctx)

        // 合成媒体：3 成员 + 1 哨兵（UUID 名互异、颜色互异的真实 MediaStore JPEG）。
        val uris = (1..SENTINEL_INDEX).map { seedPhoto(ctx, it) }
        val names = uris.map { uri ->
            ctx.contentResolver.query(
                uri,
                arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
                null, null, null
            )!!.use { check(it.moveToFirst()) { "no row for $uri" }; it.getString(0) }
        }
        val memberRows = names.take(MEMBER_COUNT).map { rebuildRow(ctx, it) }
        val sentinelRow = rebuildRow(ctx, names[SENTINEL_INDEX - 1])

        // 判据 1：真实生产写入口入站（与 MainActivity.onRemove 同一事务路径）。
        trash.addAllToRecycleBin(memberRows)

        // ===== 判据 2 的 app 查询面自证（写期望状态前，失败即种子红） =====
        val bin = trash.loadRecycleBin()
        assertEquals("回收记录必须非空且恰为 3 个成员", MEMBER_COUNT, bin.size)
        assertEquals(
            "回收记录身份键必须有序且逐项一致（无串项/重复）",
            memberRows.map { it.mediaIdentityKey() },
            bin.map { it.mediaIdentityKey() }
        )
        assertTrue("每条回收记录必须带 deletedAt 盖戳", bin.all { it.deletedAt > 0L })
        for ((index, record) in bin.withIndex()) {
            assertEquals("记录 displayName 必须与成员一致", memberRows[index].displayName, record.displayName)
            assertEquals("记录 uri 必须为卷限定身份", memberRows[index].uri, record.uri)
            assertEquals("记录 volumeName 必须一致", memberRows[index].volumeName, record.volumeName)
        }
        assertTrue(
            "集合外哨兵 s1 不得出现在回收记录中",
            bin.none { it.mediaIdentityKey() == sentinelRow.mediaIdentityKey() }
        )

        // 对应媒体行仍存在（app 面）：逐名按最新 MediaStore 元数据重建，身份键一致。
        for (row in memberRows + sentinelRow) {
            val rebuilt = rebuildRow(ctx, row.displayName)
            assertEquals("回收成员的媒体行必须仍在 MediaStore（${row.displayName}）", row.mediaIdentityKey(), rebuilt.mediaIdentityKey())
            ctx.contentResolver.openInputStream(rebuilt.uri)!!.use { stream ->
                val decoded = BitmapFactory.decodeStream(stream)
                assertTrue("合成媒体必须可完整解码（${row.displayName}）", decoded != null && decoded.width > 0)
                decoded?.recycle()
            }
        }

        // ===== 期望状态落盘（跨升级契约：全字段记录 + 恢复候选 + 哨兵） =====
        fun recordJson(row: com.tabula.v3.data.model.ImageFile): JSONObject = JSONObject().apply {
            put("identityKey", row.mediaIdentityKey())
            put("displayName", row.displayName)
            put("id", row.id)
            put("uri", row.uri.toString())
            put("dateModified", row.dateModified)
            put("size", row.size)
            put("width", row.width)
            put("height", row.height)
            put("bucketDisplayName", row.bucketDisplayName ?: "")
            put("orientation", row.orientation)
            put("deletedAt", row.deletedAt)
            put("volumeName", row.volumeName ?: "")
            put("relativePath", row.relativePath ?: "")
        }
        val json = JSONObject().apply {
            put("seedPid", android.os.Process.myPid())
            put("members", JSONArray(memberRows.map { recordJson(it) }))
            put("restoredCandidate", JSONObject().apply {
                put("identityKey", memberRows[0].mediaIdentityKey())
                put("displayName", memberRows[0].displayName)
            })
            put("outsideSentinel", recordJson(sentinelRow))
        }
        val file = expectedStateFile(ctx)
        file.writeText(json.toString())
        assertTrue("期望状态文件必须非空", file.length() > 0)

        // 期望状态回读自证：成员键有序一致、恢复候选=首成员、哨兵键不同于全部成员。
        val reread = JSONObject(file.readText())
        val rereadKeys = (0 until reread.getJSONArray("members").length())
            .map { reread.getJSONArray("members").getJSONObject(it).getString("identityKey") }
        assertEquals(memberRows.map { it.mediaIdentityKey() }, rereadKeys)
        assertEquals(
            memberRows[0].mediaIdentityKey(),
            reread.getJSONObject("restoredCandidate").getString("identityKey")
        )
        val sentinelKey = reread.getJSONObject("outsideSentinel").getString("identityKey")
        assertNotEquals(sentinelKey, reread.getJSONObject("restoredCandidate").getString("identityKey"))
        assertTrue("哨兵身份键不得与任何成员重合", rereadKeys.none { it == sentinelKey })
    }
}
