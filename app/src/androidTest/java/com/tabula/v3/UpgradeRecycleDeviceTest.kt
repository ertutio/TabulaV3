package com.tabula.v3

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.LocalImageRepository
import com.tabula.v3.data.repository.RecycleBinManager
import com.tabula.v3.data.repository.mediaIdentityKey
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

/**
 * 任务 A（升级回收记录）——NEW 侧升级后验证（判据 3/4/5）。
 *
 * 宿主（tools/run-upgrade-probe-recycle.sh）先行：
 * OLD(43a8da1) + OLD androidTest APK → 运行 UpgradeRecycleSeedTest（真实生产写入口
 * addAllToRecycleBin 建立 3 成员回收记录 + 集合外哨兵 s1 + 期望状态 JSON）→
 * force-stop → adb root 直读 recycle_bin.json（pre-launch 磁盘真值）并以 root 重定向
 * 注入 files/upgrade-recycle-disk-truth.json → `adb install -r` 覆盖安装 NEW（无 pm clear）
 * → NEW androidTest APK → 本测试两个用例分开驱动。
 *
 * 用例 1 verifyDiskTruthThenRestoreViaUi（判据 3+4）：
 *   任何 UI 启动前，三面一致（pre-launch 磁盘真值 == 进程内直读 recycle_bin.json ==
 *   生产 loadRecycleBin，逐字段逐序）→ 经真实回收站 UI（图库 → 回收站 → 项详情 →
 *   恢复，走生产 restoreImagesInOneTransaction）恢复 restoredCandidate →
 *   恢复项回图库（快照−软删除投影口径）+ 未恢复项恰在站 + 哨兵/无串项/无重复 +
 *   物理媒体身份完好 → 写 postrestore 快照供用例 2。
 * 用例 2 verifyRestorePersistsInNewProcess（判据 5）：宿主 force-stop + 冷重启后的
 *   新进程读回——恢复结果持久（恰 [m2,m3]）、两次 loadRecycleBin 幂等、
 *   postrestore 磁盘真值（宿主 pull 后注入）三面一致。
 *
 * 断言纪律：截止轮询 awaitUntil（250ms 步进、30s 截止，false 恒不通过）；失败留证
 * （withEvidence 落盘后重抛）；测试创建文件名无 `|`；UI 等待 20s 截止（沿用
 * CloudRestoreUiTest 口径）。
 */
@RunWith(AndroidJUnit4::class)
class UpgradeRecycleDeviceTest {

    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext

    companion object {
        /** OLD 种子写入的期望状态（外部 files，跨升级持久）。 */
        const val EXPECTED_STATE_FILE = "upgrade-recycle-expected-state.json"

        /** 宿主注入的 pre-launch 磁盘真值（NEW app 首次启动前 adb root 直读的副本）。 */
        const val DISK_TRUTH_FILE = "upgrade-recycle-disk-truth.json"

        /** 宿主注入的恢复后磁盘真值（用例 1 后 pull → 注入）。 */
        const val POSTRESTORE_DISK_TRUTH_FILE = "upgrade-recycle-postrestore-disk-truth.json"

        /** 用例 1 落盘的恢复后快照（用例 2 的新进程换代锚）。 */
        const val POSTRESTORE_SNAPSHOT_FILE = "upgrade-recycle-postrestore.json"

        /** 生产回收站持久文件（AtomicRecycleBinStorage baseFile）。 */
        const val BIN_FILE = "recycle_bin.json"

        /** 回收站 UI 的项计数文本锚（RecycleBinTopBar）。 */
        fun binCountText(count: Int) = "$count 项待处理"

        fun externalFile(context: android.content.Context, name: String): File =
            File(FullRecoveryStateContract.stateFile(context).parentFile, name)
    }

    // ===== 断言纪律基建（与 UpgradeOverlayDeviceTest 同口径） =====

    private suspend fun awaitUntil(desc: String, timeoutMs: Long = 30_000L, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var probes = 0
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            probes++
            try {
                if (condition()) return
            } catch (e: Throwable) {
                lastError = e
            }
            android.os.SystemClock.sleep(250)
        }
        throw AssertionError(
            "截止轮询超时（${timeoutMs}ms，probes=$probes，条件持续为 false）：" +
                if (lastError != null) "$desc（末次异常：$lastError）" else desc,
            lastError
        )
    }

    private suspend fun withEvidence(section: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            runCatching {
                val evidence = JSONObject()
                evidence.put("section", section)
                evidence.put("failure", failure.message ?: failure.javaClass.name)
                evidence.put("pid", Process.myPid())
                evidence.put("bin", binNowJson())
                externalFile(ctx, "upgrade-recycle-evidence.json").writeText(evidence.toString())
            }
            throw failure
        }
    }

    // ===== 回收站读面（三面共用口径） =====

    private suspend fun binNow(): List<ImageFile> = RecycleBinManager.getInstance(ctx).loadRecycleBin()

    private suspend fun binNowJson(): JSONArray = JSONArray(binNow().map { it.mediaIdentityKey() })

    /**
     * 按生产 loadUnlocked 的同一变换把 recycle_bin.json 原始 JSON 解析为 ImageFile
     * （volumeName/relativePath/bucketDisplayName 缺省/空串退化为 null 的口径一致）。
     */
    private fun parseBinRecords(raw: String): List<ImageFile> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            ImageFile(
                id = obj.getLong("id"),
                uri = android.net.Uri.parse(obj.getString("uri")),
                displayName = obj.getString("displayName"),
                dateModified = obj.getLong("dateModified"),
                size = obj.getLong("size"),
                width = obj.getInt("width"),
                height = obj.getInt("height"),
                bucketDisplayName = obj.optString("bucketDisplayName", "").ifBlank { null },
                orientation = obj.optInt("orientation", 0),
                deletedAt = obj.optLong("deletedAt", 0L),
                volumeName = obj.optString("volumeName", "").ifBlank { null },
                relativePath = obj.optString("relativePath", "").ifBlank { null }
            )
        }
    }

    private fun readPrivateBinFile(): List<ImageFile> {
        val file = File(ctx.filesDir, BIN_FILE)
        assertTrue("回收站持久文件必须存在：${file.absolutePath}", file.exists() && file.length() > 0)
        return parseBinRecords(file.readText())
    }

    private fun assertSameRecords(desc: String, expected: List<ImageFile>, actual: List<ImageFile>) {
        assertEquals(
            "$desc（记录数 expected=${expected.size} actual=${actual.size}；" +
                "expectedKeys=${expected.map { it.mediaIdentityKey() }}；" +
                "actualKeys=${actual.map { it.mediaIdentityKey() }}）",
            expected, actual
        )
    }

    private fun memberRowsOf(expected: JSONObject): List<ImageFile> {
        val members = expected.getJSONArray("members")
        return (0 until members.length()).map { i ->
            val m = members.getJSONObject(i)
            ImageFile(
                id = m.getLong("id"),
                uri = android.net.Uri.parse(m.getString("uri")),
                displayName = m.getString("displayName"),
                dateModified = m.getLong("dateModified"),
                size = m.getLong("size"),
                width = m.getInt("width"),
                height = m.getInt("height"),
                bucketDisplayName = m.optString("bucketDisplayName", "").ifBlank { null },
                orientation = m.optInt("orientation", 0),
                deletedAt = m.getLong("deletedAt"),
                volumeName = m.optString("volumeName", "").ifBlank { null },
                relativePath = m.optString("relativePath", "").ifBlank { null }
            )
        }
    }

    private fun sentinelRowOf(expected: JSONObject): ImageFile {
        val s = expected.getJSONObject("outsideSentinel")
        return ImageFile(
            id = s.getLong("id"),
            uri = android.net.Uri.parse(s.getString("uri")),
            displayName = s.getString("displayName"),
            dateModified = s.getLong("dateModified"),
            size = s.getLong("size"),
            width = s.getInt("width"),
            height = s.getInt("height"),
            bucketDisplayName = s.optString("bucketDisplayName", "").ifBlank { null },
            orientation = s.optInt("orientation", 0),
            deletedAt = s.getLong("deletedAt"),
            volumeName = s.optString("volumeName", "").ifBlank { null },
            relativePath = s.optString("relativePath", "").ifBlank { null }
        )
    }

    /** 物理媒体身份面：行存在（身份键一致）+ JPEG 字节可解码。 */
    private fun assertMediaRowIntact(row: ImageFile) {
        val rebuilt = FullRecoveryStateContract.rebuildRow(ctx, row.displayName)
        assertEquals(
            "媒体行身份必须完好（${row.displayName}）",
            row.mediaIdentityKey(), rebuilt.mediaIdentityKey()
        )
        ctx.contentResolver.openInputStream(rebuilt.uri)!!.use { stream ->
            val decoded = BitmapFactory.decodeStream(stream)
            assertTrue("媒体字节必须可完整解码（${row.displayName}）", decoded != null && decoded.width > 0)
            decoded?.recycle()
        }
    }

    // ===== UI 驱动（CloudRestoreUiTest 同款可达性口径） =====

    private fun shell(command: String): String = ins.uiAutomation.executeShellCommand(command).use { p ->
        FileInputStream(p.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.text?.toString()?.contains(text) == true || node.contentDescription?.toString()?.contains(text) == true)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
        return null
    }

    private fun waitFor(text: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < end) {
            find(ins.uiAutomation.rootInActiveWindow, text)?.let { return it }
            SystemClock.sleep(100)
        }
        error("UI element not found: $text")
    }

    private fun gesture(text: String) {
        val bounds = Rect(); waitFor(text).getBoundsInScreen(bounds)
        check(!bounds.isEmpty)
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }

    private fun startActivity(): Activity =
        ins.startActivitySync(Intent(ctx, com.tabula.v3.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun finishActivity(activity: Activity) {
        ins.runOnMainSync { activity.finish() }
        ins.waitForIdleSync()
        val end = SystemClock.uptimeMillis() + 10000
        while (!activity.isDestroyed && SystemClock.uptimeMillis() < end) SystemClock.sleep(100)
        check(activity.isDestroyed) { "Activity did not finish" }
    }

    // ===== 用例 1：磁盘读回三面一致 + 真实 UI 恢复（判据 3+4） =====

    @Test
    fun verifyDiskTruthThenRestoreViaUi() = kotlinx.coroutines.runBlocking {
        withEvidence("verifyDiskTruthThenRestoreViaUi") {
            // 前置锚：OLD 种子期望状态跨升级幸存 + 进程换代。
            val expected = JSONObject(externalFile(ctx, EXPECTED_STATE_FILE).readText())
            val members = memberRowsOf(expected)
            val restored = expected.getJSONObject("restoredCandidate")
            val restoredKey = restored.getString("identityKey")
            val restoredName = restored.getString("displayName")
            val sentinel = sentinelRowOf(expected)
            assertEquals("恢复候选必须为首成员", members[0].mediaIdentityKey(), restoredKey)
            assertNotEquals("必须为 NEW 侧新进程（非种子进程）", expected.getInt("seedPid"), Process.myPid())

            // ===== 判据 3：任何修复/重算前的磁盘读回三面一致 =====
            // (a) pre-launch 磁盘真值：宿主在 NEW app 首次启动前 adb root 直读的副本。
            val diskTruthFile = File(ctx.filesDir, DISK_TRUTH_FILE)
            assertTrue("宿主必须已注入 pre-launch 磁盘真值：${diskTruthFile.absolutePath}", diskTruthFile.exists() && diskTruthFile.length() > 0)
            val diskTruth = parseBinRecords(diskTruthFile.readText())
            assertTrue("pre-launch 磁盘真值必须非空（恰 3 成员）", diskTruth.size == members.size)
            assertTrue("pre-launch 磁盘真值每条必须带 deletedAt 盖戳", diskTruth.all { it.deletedAt > 0L })
            // (b) 进程内直读持久文件（NEW 启动链未重写时与 pre-launch 逐字节同源）。
            val liveDisk = readPrivateBinFile()
            // (c) 正常生产加载结果。
            val loaded = binNow()
            assertSameRecords("pre-launch 磁盘真值 == 进程内直读 recycle_bin.json", diskTruth, liveDisk)
            assertSameRecords("pre-launch 磁盘真值 == 生产 loadRecycleBin", diskTruth, loaded)
            assertEquals(
                "回收记录身份键必须有序且恰为种子成员（无串项/重复/缺失）",
                members.map { it.mediaIdentityKey() },
                loaded.map { it.mediaIdentityKey() }
            )

            // ===== 判据 4：真实回收站 UI 恢复 restoredCandidate =====
            val activity = startActivity()
            try {
                waitFor("图库")
                gesture("回收站")
                waitFor(binCountText(members.size))

                // 列表项 contentDescription=displayName（RecycleBinItem AsyncImage）。
                gesture(restoredName)
                waitFor("恢复") // 查看器页的恢复按钮（text/contentDescription=恢复）
                gesture("恢复")

                // 生产面：恢复事务完成后恰余未恢复两项（有序、无重复、恢复项离站）。
                val remainingKeys = members.drop(1).map { it.mediaIdentityKey() }
                awaitUntil("恢复后回收站必须恰为未恢复两项 $remainingKeys") {
                    binNow().map { it.mediaIdentityKey() } == remainingKeys
                }
                val afterRestore = binNow()
                assertTrue("恢复后记录必须无重复", afterRestore.map { it.mediaIdentityKey() }.distinct().size == afterRestore.size)
                assertTrue(
                    "恢复项必须已离站（$restoredKey）",
                    afterRestore.none { it.mediaIdentityKey() == restoredKey }
                )
                assertTrue(
                    "集合外哨兵 s1 不得在任何时刻入站",
                    afterRestore.none { it.mediaIdentityKey() == sentinel.mediaIdentityKey() }
                )

                // 恢复项回图库（生产投影口径：快照 loadAllData ∖ 软删除集合 含恢复项）。
                val allData = LocalImageRepository(ctx).loadAllData()
                val galleryKeys = allData.allImages.map { it.mediaIdentityKey() }.toSet()
                assertTrue(
                    "恢复项必须回到图库投影（快照−回收站）",
                    restoredKey in galleryKeys && afterRestore.none { it.mediaIdentityKey() == restoredKey }
                )

                // UI 面：退出查看器 → 回收站列表恰 2 项，未恢复两项仍在列表。
                shell("input keyevent 4")
                waitFor(binCountText(members.size - 1))
                for (row in members.drop(1)) {
                    waitFor(row.displayName)
                }

                // 物理媒体身份（app 面；shell 面由宿主探针复查）：全部成员 + 哨兵行在可解码。
                for (row in members + sentinel) assertMediaRowIntact(row)
            } finally {
                runCatching { finishActivity(activity) }
            }

            // ===== 恢复后快照（用例 2 的换代锚与持久基线） =====
            val snapshot = JSONObject().apply {
                put("postRestorePid", Process.myPid())
                put("remainingKeys", JSONArray(afterRestoreKeys()))
                put("restoredKey", restoredKey)
            }
            externalFile(ctx, POSTRESTORE_SNAPSHOT_FILE).writeText(snapshot.toString())
        }
    }

    private suspend fun afterRestoreKeys(): List<String> = binNow().map { it.mediaIdentityKey() }

    // ===== 用例 2：force-stop → 新进程读回持久 + 幂等（判据 5） =====

    @Test
    fun verifyRestorePersistsInNewProcess() = kotlinx.coroutines.runBlocking {
        withEvidence("verifyRestorePersistsInNewProcess") {
            val snapshot = JSONObject(externalFile(ctx, POSTRESTORE_SNAPSHOT_FILE).readText())
            val restoredKey = snapshot.getString("restoredKey")
            val remainingKeys = (0 until snapshot.getJSONArray("remainingKeys").length())
                .map { snapshot.getJSONArray("remainingKeys").getString(it) }
            assertNotEquals("必须为恢复后又一次新进程", snapshot.getInt("postRestorePid"), Process.myPid())

            val expected = JSONObject(externalFile(ctx, EXPECTED_STATE_FILE).readText())
            val members = memberRowsOf(expected)
            val sentinel = sentinelRowOf(expected)

            // 新进程生产加载：恢复结果持久。
            val first = binNow()
            assertEquals(
                "新进程读回必须持久恢复结果（恰未恢复两项）",
                remainingKeys, first.map { it.mediaIdentityKey() }
            )
            assertTrue("恢复项不得随新进程回站", first.none { it.mediaIdentityKey() == restoredKey })
            assertTrue("哨兵不得入站", first.none { it.mediaIdentityKey() == sentinel.mediaIdentityKey() })

            // 幂等：两次 loadRecycleBin 一致（逐字段）。
            val second = binNow()
            assertSameRecords("两次 loadRecycleBin 必须幂等一致", first, second)

            // 磁盘三面一致：宿主 pull 注入的 postrestore 真值 == 进程内直读 == 生产读。
            val postDiskTruthFile = File(ctx.filesDir, POSTRESTORE_DISK_TRUTH_FILE)
            assertTrue("宿主必须已注入 postrestore 磁盘真值：${postDiskTruthFile.absolutePath}", postDiskTruthFile.exists() && postDiskTruthFile.length() > 0)
            val postDiskTruth = parseBinRecords(postDiskTruthFile.readText())
            val liveDisk = readPrivateBinFile()
            assertSameRecords("postrestore 磁盘真值 == 进程内直读 recycle_bin.json", postDiskTruth, liveDisk)
            assertSameRecords("postrestore 磁盘真值 == 生产 loadRecycleBin", postDiskTruth, first)

            // 物理媒体身份：全部成员 + 哨兵行在可解码（恢复未损物理媒体）。
            for (row in members + sentinel) assertMediaRowIntact(row)
        }
    }
}
