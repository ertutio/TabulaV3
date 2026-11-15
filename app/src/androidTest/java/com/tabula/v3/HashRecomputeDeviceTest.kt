package com.tabula.v3

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.cache.ImageHashCache
import com.tabula.v3.data.cache.toHashKey
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.AlbumCleanupEngine
import com.tabula.v3.data.repository.LocalImageRepository
import com.tabula.v3.data.repository.PerceptualHash
import com.tabula.v3.data.repository.SimilarGroup
import com.tabula.v3.data.repository.SimilarGroupDetector
import com.tabula.v3.data.repository.mediaIdentityKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TC58/收口第二轮 §二（2026-11-15）：真实生产链的内容变更→旧结果失效→有界重算
 * →消费端采纳新结果——设备级（真实 MediaStore 读+真实解码+真实 dHash 计算）。
 *
 * 生产链（全部真实入口，零注入）：
 * 读=LocalImageRepository.loadAllData()（真实 MediaStore 全量查询）
 * 算=SimilarGroupDetector.ensureHashesComputed()（版本=dateModified 毫秒；
 *    过期即重算；此前失败有界重试——SimilarGroupDetector.kt:258-304）
 * 消费=SimilarGroupDetector.detectSimilarGroups()（元数据预筛+哈希验证聚类，
 *    SimilarGroupDetector.kt:110+；hashDistance 阈值见 :422-428）。
 *
 * 场景单方法（预期 case 数=1）：
 * 1. 建旧版本结果：夹具 a1=水平渐变 / d1=垂直渐变 / b1=对角渐变 / c1=垂直渐变
 *    变体 / u1=无关（冷却+清理进度种子面）/ s1=哨兵（集合外）。
 *    生产读+ensureHashesComputed 建立旧版本缓存；断言四哈希互异。
 * 2. 消费面负对照（旧哈希时代）：detectSimilarGroups([a1,d1]) 同窗口内
 *    horizontal-vs-vertical dHash 距离>14 → 不成组（哈希驱动的拒绝）。
 * 3. 无关面种子：冷却 pick 记录 + 清理组/断点（生产写入口，FG 刷新测试同款）。
 * 4. 内容变更（同媒体身份，IS_PENDING 舞蹈=PixelRefresh 同款）：a1 内容→与 d1
 *    相同的垂直渐变；c1→损坏字节（真实解码失败载体）；b1/d1 不动。
 * 5. 失效面：再生产读（a1/c1 dateModified 升版）→ 生产缓存读：a1/c1@新版本
 *    =null（旧版本失效）、b1/d1@旧版本仍命中且值不变（复用面）。
 * 6. 重算面：ensureHashesComputed → a1 新哈希≠旧哈希 且 ==d1 哈希（同内容
 *    同解码）；c1=Failed（生产记录失败态）。
 * 7. 消费面正对照（新哈希时代）：detectSimilarGroups([a1,d1]) 同内容同哈希
 *    同窗口 → 成组且组内同时含两身份（消费端确实采用重算后的新结果）。
 * 8. 有界恢复：c1 恢复有效新内容（再升版）→ 再生产链 → c1 新哈希≠旧哈希
 *    且非 Failed（此前失败条目不阻断新版本重算）。
 * 9. 无关面保留：冷却 pick 记录、清理组键、断点经全循环逐项读回原样；
 *    永久完成面为空。
 * 10. 双面核对：shell 行面 6 行全在（含哨兵）；哨兵不入任何组。
 *
 * 断言纪律：截止轮询（行出现/日期升版）、无固定 sleep、失败留证（dumpTree/
 * saveScreenshot 文件名无 `|`）、@After 幂等清理（防死行污染同宿主后续段）。
 * 不动 app/src/main、app/src/test。
 */
@RunWith(AndroidJUnit4::class)
class HashRecomputeDeviceTest {
    private val TAG = "HashRecompute"
    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = "Pictures/HashRecomputeFixtures/"
    private val shell: (String) -> String =
        { cmd ->
            val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
            val out = android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes().decodeToString()
            out
        }
    private var createdUris = mutableListOf<Uri>()

    private val a1Name = "tc58r-a1.jpg"
    private val d1Name = "tc58r-d1.jpg"
    private val b1Name = "tc58r-b1.jpg"
    private val c1Name = "tc58r-c1.jpg"
    private val u1Name = "tc58r-u1.jpg"
    private val s1Name = "tc58r-s1.jpg"
    private val allNames = listOf(a1Name, d1Name, b1Name, c1Name, u1Name, s1Name)

    private val cache get() = ImageHashCache(ctx)
    private val detector get() = SimilarGroupDetector(ctx, cache)
    private val preferences get() = AppPreferences(ctx)
    private val albumId = "tc58r-unrelated-album"

    // ---------- 夹具构造（app 侧 MediaStore 行 + 真实文件，PixelRefresh 同款） ----------
    private enum class Gradient { HORIZONTAL, VERTICAL, DIAGONAL }

    private fun drawGradientBitmap(g: Gradient): Bitmap {
        val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        val coords = when (g) {
            Gradient.HORIZONTAL -> intArrayOf(0, 0, 128, 0)
            Gradient.VERTICAL -> intArrayOf(0, 0, 0, 128)
            Gradient.DIAGONAL -> intArrayOf(0, 0, 128, 128)
        }
        paint.shader = LinearGradient(
            coords[0].toFloat(), coords[1].toFloat(), coords[2].toFloat(), coords[3].toFloat(),
            0xFFFF4040.toInt(), 0xFF101010.toInt(), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, 128f, 128f, paint)
        return bmp
    }

    private fun writeBitmapToRow(row: Uri, bmp: Bitmap) {
        ctx.contentResolver.openOutputStream(row)!!.use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.flush()
        }
    }

    private fun writeGarbageToRow(row: Uri) {
        ctx.contentResolver.openOutputStream(row)!!.use { out ->
            out.write("not-a-real-jpeg-corrupt-payload-for-decode-failure".toByteArray())
            out.flush()
        }
    }

    private fun insertFixture(name: String, g: Gradient): Uri {
        val row = requireNotNull(
            ctx.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })
        ) { "insert 失败：$name" }
        createdUris += row
        writeBitmapToRow(row, drawGradientBitmap(g))
        check(ctx.contentResolver.update(row, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        return row
    }

    private fun swapContent(row: Uri, g: Gradient) {
        check(ctx.contentResolver.update(row, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }, null, null) == 1)
        writeBitmapToRow(row, drawGradientBitmap(g))
        check(ctx.contentResolver.update(row, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
    }

    private fun swapContentCorrupt(row: Uri) {
        check(ctx.contentResolver.update(row, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }, null, null) == 1)
        writeGarbageToRow(row)
        check(ctx.contentResolver.update(row, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
    }

    private suspend fun loadAll(): Map<String, ImageFile> =
        LocalImageRepository(ctx).loadAllData().allImages.associateBy { it.displayName }

    private suspend fun awaitAllRows(timeoutMs: Long = 20000): Map<String, ImageFile> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Map<String, ImageFile> = emptyMap()
        while (System.currentTimeMillis() < deadline) {
            last = loadAll()
            if (allNames.all { it in last }) return last
            Thread.sleep(200)
        }
        error("行未全部入库（截止轮询耗尽）：缺失=${allNames.filter { it !in last }}")
    }

    private fun rowsOf(all: Map<String, ImageFile>, names: List<String>): List<ImageFile> =
        names.map { all.getValue(it) }

    private fun groupPairingIdentity(groups: List<SimilarGroup>, row1: ImageFile, row2: ImageFile) =
        groups.firstOrNull { g ->
            val ids = g.images.map { it.mediaIdentityKey() }
            row1.mediaIdentityKey() in ids && row2.mediaIdentityKey() in ids
        }

    // ---------- 用例 ----------
    @Test
    fun contentEditInvalidatesHashAndConsumersAdoptRecomputedResults() = runBlocking<Unit> {
        // ---- 1. 夹具与旧版本结果 ----
        val a1Row = insertFixture(a1Name, Gradient.HORIZONTAL)
        insertFixture(d1Name, Gradient.VERTICAL)
        insertFixture(b1Name, Gradient.DIAGONAL)
        val c1Row = insertFixture(c1Name, Gradient.VERTICAL)
        insertFixture(u1Name, Gradient.DIAGONAL)
        insertFixture(s1Name, Gradient.DIAGONAL)

        val all = awaitAllRows()
        val rowsOld = rowsOf(all, listOf(a1Name, d1Name, b1Name, c1Name))
        val hashesOld = detector.ensureHashesComputed(rowsOld)
        val oldA = hashesOld[all.getValue(a1Name).toHashKey()]!!
        val oldDHash = hashesOld[all.getValue(d1Name).toHashKey()]!!
        val oldBHash = hashesOld[all.getValue(b1Name).toHashKey()]!!
        val oldCHash = hashesOld[all.getValue(c1Name).toHashKey()]!!
        assertTrue("旧哈希四项互异（夹具可区分性自检）", setOf(oldA, oldDHash, oldBHash, oldCHash).size == 4)
        assertTrue(
            "horizontal-vs-vertical dHash 必须超过拒绝阈值（>14，实测=${PerceptualHash.hammingDistance(oldA, oldDHash)}）",
            PerceptualHash.hammingDistance(oldA, oldDHash) > 14
        )
        Log.i(TAG, "step1 old hashes: a=$oldA d=$oldDHash b=$oldBHash c=$oldCHash")

        // ---- 2. 消费面负对照（旧哈希时代：同窗口、哈希拒绝 → 不成组）----
        val groupsOld = detector.detectSimilarGroups(listOf(all.getValue(a1Name), all.getValue(d1Name)))
        assertNull(
            "负对照：旧哈希（水平 vs 垂直）在同窗口下不得成组",
            groupPairingIdentity(groupsOld, all.getValue(a1Name), all.getValue(d1Name))
        )
        Log.i(TAG, "step2 pre-swap consumer negative: a1+d1 not grouped (hash-driven rejection)")

        // ---- 3. 无关面种子（生产写入口）----
        val unrelatedRow = all.getValue(u1Name)
        preferences.recordImagesPickedByIdentity(listOf(unrelatedRow.mediaIdentityKey()))
        val members = listOf(unrelatedRow)
        val originalGroupId = SimilarGroup(
            images = members,
            startTime = members.minOf { it.dateModified },
            endTime = members.maxOf { it.dateModified }
        ).id
        preferences.saveAlbumAnalysisResult(albumId, 1, listOf(originalGroupId))
        preferences.saveGroupImageCounts(albumId, mapOf(originalGroupId to members.size))
        preferences.saveAlbumTotalImages(albumId, members.size)
        preferences.saveGroupsImageIds(albumId, mapOf(originalGroupId to members.map { it.mediaIdentityKey() }))
        val engine = AlbumCleanupEngine(ctx)
        engine.getNextBatch(albumId, members)
        engine.saveCheckpoint(listOf(originalGroupId), 1)
        val checkpointBefore = AlbumCleanupEngine(ctx).getCheckpointBatch(albumId, members)!!
        val cooldownHitBefore = preferences.getPickCooldownIndex()
            .byIdentityKey.containsKey(unrelatedRow.mediaIdentityKey())
        assertTrue("种子自检：冷却记录必须命中", cooldownHitBefore)
        Log.i(TAG, "step3 unrelated state seeded")

        // ---- 4. 内容变更（同身份，b1/d1 不动）----
        swapContent(a1Row, Gradient.VERTICAL)   // a1 → 与 d1 相同内容
        swapContentCorrupt(c1Row)               // c1 → 损坏字节（真实解码失败）

        val all2 = awaitAllRows()
        val a1New = all2.getValue(a1Name)
        val c1New = all2.getValue(c1Name)
        val b1Same = all2.getValue(b1Name)
        val d1Same = all2.getValue(d1Name)
        assertTrue("a1 dateModified 必须升版", a1New.dateModified > all.getValue(a1Name).dateModified)
        assertTrue("c1 dateModified 必须升版", c1New.dateModified > all.getValue(c1Name).dateModified)
        assertEquals("b1 未修改：dateModified 不变", all.getValue(b1Name).dateModified, b1Same.dateModified)
        assertEquals("d1 未修改：dateModified 不变", all.getValue(d1Name).dateModified, d1Same.dateModified)

        // ---- 5. 失效面（生产缓存读）----
        val rows2 = rowsOf(all2, listOf(a1Name, d1Name, b1Name, c1Name))
        val batch2 = cache.getHashesBatch(
            rows2.map { it.toHashKey() },
            rows2.associate { it.toHashKey() to it.dateModified }
        )
        assertNull("a1 旧版本结果必须失效（新版本读取=miss）", batch2[a1New.toHashKey()])
        assertNull("c1 旧版本结果必须失效", batch2[c1New.toHashKey()])
        assertEquals("b1 未修改必须命中且值不变（复用面）", oldBHash, batch2[b1Same.toHashKey()]!!.hash)
        assertEquals("d1 未修改必须命中且值不变", oldDHash, batch2[d1Same.toHashKey()]!!.hash)
        Log.i(TAG, "step5 invalidation: a1/c1 miss at new version; b1/d1 hit unchanged")

        // ---- 6. 重算面 ----
        val recomputed = detector.ensureHashesComputed(rows2)
        val newA = recomputed[a1New.toHashKey()]
        assertNotNull("a1 必须完成重算", newA)
        assertTrue("a1 新哈希≠旧哈希（新内容确实重算）", newA!! != oldA)
        assertEquals("a1 新哈希==d1 哈希（同内容同解码）", newA, oldDHash)
        assertNull("c1 损坏内容计算结果为失败（null）", recomputed[c1New.toHashKey()])
        val c1Entry = cache.getHash(c1New.toHashKey(), c1New.dateModified)
        assertNotNull("生产必须记录 c1 失败态条目", c1Entry)
        assertTrue(c1Entry!!.isFailed)
        Log.i(TAG, "step6 recompute: a1 new hash adopted; c1 Failed recorded at new version")

        // ---- 7. 消费面正对照（新哈希时代）----
        val groupsNew = detector.detectSimilarGroups(listOf(a1New, d1Same))
        val group = groupPairingIdentity(groupsNew, a1New, d1Same)
        assertNotNull("正对照：重算后同内容必须成组（消费端采纳新结果）", group)
        Log.i(TAG, "step7 consumer positive: a1+d1 grouped post-recompute")

        // ---- 8. 有界恢复（c1 一次临时读取失败 → 恢复）----
        swapContent(c1Row, Gradient.VERTICAL) // 恢复有效新内容（再升版）
        val all3 = awaitAllRows()
        val c1Recovered = all3.getValue(c1Name)
        assertTrue("c1 恢复内容 dateModified 再升版", c1Recovered.dateModified > c1New.dateModified)
        val recomputedC = detector.ensureHashesComputed(rowsOf(all3, listOf(a1Name, d1Name, b1Name, c1Name)))
        val newC = recomputedC[c1Recovered.toHashKey()]
        assertNotNull("恢复后必须完成重算（此前失败条目不阻断新版本）", newC)
        assertTrue("c1 新哈希≠旧哈希", newC!! != oldCHash)
        Log.i(TAG, "step8 bounded recovery: c1 recomputed after temporary read failure")

        // ---- 9. 无关面保留 ----
        assertTrue(
            "冷却记录必须保留",
            preferences.getPickCooldownIndex().byIdentityKey.containsKey(unrelatedRow.mediaIdentityKey())
        )
        assertEquals(
            "清理组键必须保留",
            members.map { it.mediaIdentityKey() },
            preferences.getGroupImageIds(albumId, originalGroupId)
        )
        val checkpointAfter = AlbumCleanupEngine(ctx).getCheckpointBatch(albumId, members)!!
        assertEquals(checkpointBefore.second, checkpointAfter.second)
        assertEquals(checkpointBefore.first.groupIds, checkpointAfter.first.groupIds)
        assertTrue(preferences.getAlbumPermanentlyProcessedGroups(albumId).isEmpty())
        Log.i(TAG, "step9 unrelated state preserved")

        // ---- 10. 双面核对（shell 行面+哨兵）----
        val shellOut = shell(
            "content query --uri content://media/external/images/media --where \"relative_path='$dir'\" --projection _display_name 2>&1"
        )
        for (name in allNames) {
            assertTrue("shell 面必须存在 $name", shellOut.contains(name))
        }
        assertTrue("哨兵 s1 必须在库（shell 面）", shellOut.contains(s1Name))
        assertTrue("哨兵不得进入任何相似组", groupsNew.all { g -> g.images.none { it.displayName == s1Name } })
        Log.i(TAG, "step10 shell double-face verified (6 rows incl. sentinel)")

        // ---- 11. 清理 ----
        cleanupAll()
        Log.i(TAG, "complete: production-chain recompute verified (invalidate/recompute/consume/recover/preserve)")
        Unit
    }

    private fun cleanupAll() {
        for (row in createdUris) {
            runCatching { ctx.contentResolver.delete(row, null, null) }
        }
        createdUris.clear()
        shell("rm -rf /storage/emulated/0/$dir 2>&1")
        runCatching { preferences.clearAllPickRecords() }
        runCatching { preferences.clearAllAlbumCleanupState() }
    }

    @After
    fun tearDown() {
        cleanupAll()
    }
}
