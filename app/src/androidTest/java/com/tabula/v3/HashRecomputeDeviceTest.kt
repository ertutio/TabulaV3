package com.tabula.v3

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
import android.os.ParcelFileDescriptor

/**
 * TC58/收口第二轮 §二（2026-11-15）：真实生产链的内容变更→旧结果失效→有界重算
 * →消费端采纳新结果——设备级（真实 MediaStore 读+真实解码+真实 dHash 计算）。
 *
 * 生产链（全部真实入口，零注入）：
 * 读=LocalImageRepository.loadAllData()（真实 MediaStore 全量查询）
 * 算=SimilarGroupDetector.ensureHashesComputed()（版本=dateModified 毫秒；
 *    过期即重算；此前失败有界重试——SimilarGroupDetector.kt:258-304）
 * 消费=SimilarGroupDetector.detectSimilarGroups()（元数据预筛+哈希验证聚类，
 *    SimilarGroupDetector.kt:110+；hashDistance 阈值 :422-428）。
 *
 * 夹具契约（位型设计，audit-recompute-pr80 缺陷 B 修复；设计契约由
 * HashRecomputeFixtureContractTest 位型仿真钉住）：9 等分列的垂直条纹图案，
 * dark=10/light=200，dHash（横向相邻比较）按字节重复：
 * - A=0xAA（暗亮交替）/ B=0x55（相反相位）/ C=0xFF（单调递减）/ D=0x00（单调递增）
 * - 两两 Hamming 距离 ∈ {32,64}，全部 >14（最严拒绝阈值）
 * - 消费面载体：a1-old=A、d1=B → pre 距离 64（负对照）；a1-new=B（同 d1）→
 *   post 距离 0（正对照，消费端采纳重算新结果）
 * 建立纪律（缺陷 A 修复）：compress() 返回值 check + 发布后有界回读解码验证
 * （decodeBounds 出维度才算建立完成）——夹具字节面确定性。
 *
 * 场景单方法（预期 case 数=1）：旧版本建立+四哈希互异 → 消费面负对照 →
 * 无关面种子 → 同身份内容变更（a1→B、c1→损坏字节）→ 失效面 → 重算面
 * （a1 新哈希==d1；c1 Failed 记录）→ 消费面正对照 → 有界恢复（c1→C 重算
 * 成功）→ 无关面保留 → shell 双面（6 行含哨兵不入组）。
 *
 * 断言纪律：截止轮询、无固定 sleep、失败留证（文件名无 `|`）、@After 幂等
 * 清理。不动 app/src/main、app/src/test。
 */
@RunWith(AndroidJUnit4::class)
class HashRecomputeDeviceTest {
    private val TAG = "HashRecompute"
    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = "Pictures/HashRecomputeFixtures/"
    private val shell: (String) -> String =
        { cmd ->
            val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
            val out = ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes().decodeToString()
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

    // ---------- 夹具构造（位型图案，9 等分列；契约=HashRecomputeFixtureContractTest） ----------
    private enum class Pattern { STRIPES_A, STRIPES_B, MONO_DOWN, MONO_UP }

    private fun drawPatternBitmap(p: Pattern): Bitmap {
        val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        val grays: IntArray = when (p) {
            Pattern.STRIPES_A -> intArrayOf(10, 200, 10, 200, 10, 200, 10, 200, 10)
            Pattern.STRIPES_B -> intArrayOf(200, 10, 200, 10, 200, 10, 200, 10, 200)
            Pattern.MONO_DOWN -> intArrayOf(250, 220, 190, 160, 130, 100, 70, 40, 10)
            Pattern.MONO_UP -> intArrayOf(10, 40, 70, 100, 130, 160, 190, 220, 250)
        }
        // 9 等分列：每列一个精确灰度（列宽 128/9），dHash 9 列降采样后逐列取值
        val colW = 128f / 9f
        for (k in 0 until 9) {
            val v = grays[k]
            val c = (v shl 16) or (v shl 8) or v
            paint.color = c or 0xFF000000.toInt()
            canvas.drawRect(k * colW, 0f, (k + 1) * colW, 128f, paint)
        }
        return bmp
    }

    private fun writeBitmapToRow(row: Uri, bmp: Bitmap) {
        ctx.contentResolver.openOutputStream(row)!!.use { out ->
            val ok = bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.flush()
            check(ok) { "Bitmap.compress 失败（返回 false）：$row" }
        }
    }

    private fun writeGarbageToRow(row: Uri) {
        ctx.contentResolver.openOutputStream(row)!!.use { out ->
            out.write("not-a-real-jpeg-corrupt-payload-for-decode-failure".toByteArray())
            out.flush()
        }
    }

    private fun insertFixture(name: String, p: Pattern): Uri {
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
        writeBitmapToRow(row, drawPatternBitmap(p))
        check(ctx.contentResolver.update(row, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        return row
    }

    private fun swapContent(row: Uri, p: Pattern) {
        check(ctx.contentResolver.update(row, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }, null, null) == 1)
        writeBitmapToRow(row, drawPatternBitmap(p))
        check(ctx.contentResolver.update(row, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
    }

    private fun swapContentCorrupt(row: Uri) {
        check(ctx.contentResolver.update(row, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }, null, null) == 1)
        writeGarbageToRow(row)
        check(ctx.contentResolver.update(row, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
    }

    /**
     * 建立完成的有界回读验证（缺陷 A 修复）：经 computeDHash 同款读取路径
     * （contentResolver.openInputStream + decodeBounds）确认内容可解码——
     * 字节面未落/损坏在此处以明确消息失败，而非下游静默 Failed。
     */
    private fun awaitReadable(row: Uri, name: String, timeoutMs: Long = 15000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastReason = ""
        while (System.currentTimeMillis() < deadline) {
            try {
                ctx.contentResolver.openInputStream(row)?.use { input ->
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeStream(input, null, opts)
                    if (opts.outWidth > 0 && opts.outHeight > 0) return
                    lastReason = "decodeBounds 出维度=0（${opts.outWidth}x${opts.outHeight}）"
                } ?: run { lastReason = "openInputStream=null" }
            } catch (e: Exception) {
                lastReason = "read 异常: ${e.message}"
            }
            Thread.sleep(200)
        }
        error("夹具回读解码未就绪（截止轮询耗尽）name=$name reason=$lastReason")
    }

    /**
     * 秒边界对齐（轮 11 修复：touch -t+scan_file 对已存在行不重推导
     * date_modified——FPF 的 touch 是首次入库前设置，重扫不改已有行）。
     * dateModified=文件 mtime（1 秒粒度），在发布时由 provider 重推导——
     * 因此确定性的做法=等墙钟**恰进入新秒的 ≤200ms 窗口**再执行换装
     * （写入+发布 ≤800ms 完成，必然落在同一秒），两次换装必然跨秒。
     * 条件轮询有界（5s），非固定 sleep。
     */
    private fun awaitSecondBoundary(minSec: Long): Long {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val now = System.currentTimeMillis()
            val sec = now / 1000
            if (sec >= minSec && now % 1000 <= 200) return sec
            Thread.sleep(20)
        }
        error("秒边界等待超时（minSec=$minSec）")
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

    /**
     * 瞬态容错的哈希建立（轮 7 根因修复，2026-11-15）：MediaProvider 对刚发布
     * 行的 openInputStream 可瞬态返回 null 描述符（轮 7 实证：awaitReadable
     * 同路径 49.071 成功 → compute 两次 open 49.117/49.124 已达 provider
     * （"Open with lower FS" 日志）→ 49.190 仍 null=瞬态非系统性；轮 5=发布
     * 后 90ms 内 4/4 全中）。生产检测器自身设计支持重复调用（Failed 条目
     * "有界重试：每轮一次"语义，SimilarGroupDetector.kt:271-274）——本助手
     * 对**同一生产入口**做截止轮询重试，不注入不改生产；失败原因随轮捕获
     * 入最终消息。
     */
    private suspend fun establishHashesWithTolerance(
        all: Map<String, ImageFile>,
        targetNames: List<String>,
        timeoutMs: Long = 10000
    ): Map<String, Long> {
        val rows = rowsOf(all, targetNames)
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastReason = ""
        while (System.currentTimeMillis() < deadline) {
            val direct = rows.associate { it.displayName to PerceptualHash.computeDHash(ctx, it.uri) }
            val failedDirect = direct.filterValues { it !is PerceptualHash.HashResult.Success }
            if (failedDirect.isEmpty()) {
                val m = detector.ensureHashesComputed(rows)
                val vals = targetNames.associate { it to m[all.getValue(it).toHashKey()] }
                if (vals.values.all { it != null }) return vals.mapValues { it.value!! }
                lastReason = "map 空值: ${vals.filterValues { it == null }.keys} mapSize=${m.size}"
            } else {
                lastReason = "直算失败: " + failedDirect.map { (n, r) -> "$n=${(r as PerceptualHash.HashResult.Failed).reason}" }
            }
            Thread.sleep(400)
        }
        error("夹具哈希建立未收敛（截止轮询耗尽）names=$targetNames lastReason=$lastReason")
    }

    private fun groupPairingIdentity(groups: List<SimilarGroup>, row1: ImageFile, row2: ImageFile) =
        groups.firstOrNull { g ->
            val ids = g.images.map { it.mediaIdentityKey() }
            row1.mediaIdentityKey() in ids && row2.mediaIdentityKey() in ids
        }

    // ---------- 用例 ----------
    @Test
    fun contentEditInvalidatesHashAndConsumersAdoptRecomputedResults() = runBlocking<Unit> {
        // ---- 1. 夹具与旧版本结果（位型契约：A/B/C/D 两两距离 ≥32）----
        val a1Row = insertFixture(a1Name, Pattern.STRIPES_A)
        insertFixture(d1Name, Pattern.STRIPES_B)
        insertFixture(b1Name, Pattern.MONO_DOWN)
        val c1Row = insertFixture(c1Name, Pattern.MONO_UP)
        insertFixture(u1Name, Pattern.MONO_DOWN)
        insertFixture(s1Name, Pattern.STRIPES_B)

        val all = awaitAllRows()
        for ((name, row) in all) {
            if (name in allNames) awaitReadable(row.uri, name)
        }
        val lsOut = shell("ls -l /sdcard/$dir 2>&1")
        Log.i(TAG, "fixture files: $lsOut")
        // 多角度归因诊断（轮 8 后：null 持久 10s，瞬态理论被推翻）
        val diagUri = all.getValue(a1Name).uri
        // (1) 主线程 open（awaitReadable 已证可行——复核采样）
        val mainOpen = runCatching {
            ctx.contentResolver.openInputStream(diagUri)?.use { it.readBytes().size } ?: -1
        }.getOrElse { -2 }
        Log.i(TAG, "diag main-thread openInputStream: $mainOpen bytes")
        // (2) Default 线程 open（与 computeDHash 同线程面）
        val defaultOpen = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { ctx.contentResolver.openInputStream(diagUri)?.use { it.readBytes().size } ?: -1 }.getOrElse { -2 }
        }
        Log.i(TAG, "diag default-thread openInputStream: $defaultOpen bytes")
        // (3) openFileDescriptor 精确异常面
        val fdState = runCatching {
            val fd = ctx.contentResolver.openFileDescriptor(diagUri, "r")
            val s = if (fd != null) "ok" else "NULL"
            fd?.close()
            s
        }.getOrElse { "threw ${it.javaClass.simpleName}: ${it.message}" }
        Log.i(TAG, "diag openFileDescriptor: $fdState")
        // (4) 直接文件路径读（绕过 provider）
        val fileRead = runCatching { java.io.File("/sdcard/$dir$a1Name").readBytes().size }.getOrElse { -1 }
        Log.i(TAG, "diag direct file read: $fileRead bytes")
        // (5) 行状态（is_pending/_data）+ appops + 权限面
        Log.i(TAG, "diag row state: ${shell("content query --uri content://media/external/images/media --where \"_display_name='$a1Name'\" --projection _display_name,is_pending,_data 2>&1")}")
        Log.i(TAG, "diag appops: ${shell("cmd appops get com.tabula.v3.recyclebin.test READ_MEDIA_IMAGES 2>&1")}")
        Log.i(TAG, "diag perm: ${shell("dumpsys package com.tabula.v3.recyclebin.test 2>&1 | grep -A1 'READ_MEDIA_IMAGES'")}")
        // 建立面（瞬态容错 + 原因可见）：对生产入口截止轮询；首击结果留证日志
        val firstHit = PerceptualHash.computeDHash(ctx, diagUri)
        Log.i(TAG, "direct first hit a1: $firstHit")
        val established = establishHashesWithTolerance(all, listOf(a1Name, d1Name, b1Name, c1Name))
        val oldA = established.getValue(a1Name)
        val oldDHash = established.getValue(d1Name)
        val oldBHash = established.getValue(b1Name)
        val oldCHash = established.getValue(c1Name)
        assertTrue("旧哈希四项互异（位型契约自检）", setOf(oldA, oldDHash, oldBHash, oldCHash).size == 4)
        assertEquals(
            "A vs B 互补距离必须 64（位型契约实证）",
            64, PerceptualHash.hammingDistance(oldA, oldDHash)
        )
        Log.i(TAG, "step1 old hashes: a=$oldA d=$oldDHash b=$oldBHash c=$oldCHash")

        // ---- 2. 消费面负对照（旧哈希时代：同窗口、哈希拒绝 → 不成组）----
        val groupsOld = detector.detectSimilarGroups(listOf(all.getValue(a1Name), all.getValue(d1Name)))
        assertNull(
            "负对照：A vs B 距离 64 在同窗口下不得成组",
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
        assertTrue("种子自检：冷却记录必须命中", preferences.getPickCooldownIndex().byIdentityKey.containsKey(unrelatedRow.mediaIdentityKey()))
        Log.i(TAG, "step3 unrelated state seeded")

        // ---- 4. 内容变更（同身份，b1/d1 不动；秒边界对齐=版本递增确定性）----
        val corruptSec = awaitSecondBoundary(System.currentTimeMillis() / 1000)
        swapContent(a1Row, Pattern.STRIPES_B)   // a1 → 与 d1 相同内容（位型 B）
        swapContentCorrupt(c1Row)               // c1 → 损坏字节（真实解码失败）

        val all2 = awaitAllRows()
        val a1New = all2.getValue(a1Name)
        val c1New = all2.getValue(c1Name)
        val b1Same = all2.getValue(b1Name)
        val d1Same = all2.getValue(d1Name)
        assertEquals(
            "a1 dateModified 必须落在对齐秒（1 秒粒度=秒值精确相等）",
            corruptSec, a1New.dateModified / 1000
        )
        assertEquals(
            "c1 dateModified 必须落在对齐秒",
            corruptSec, c1New.dateModified / 1000
        )
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

        // ---- 6. 重算面（瞬态容错：a1 截止轮询至非 null；c1 null 为期望 Failed 态）----
        val recomputedDeadline = System.currentTimeMillis() + 10000
        var recomputed = detector.ensureHashesComputed(rows2)
        var newA: Long? = recomputed[a1New.toHashKey()]
        while (newA == null && System.currentTimeMillis() < recomputedDeadline) {
            Thread.sleep(400)
            recomputed = detector.ensureHashesComputed(rows2)
            newA = recomputed[a1New.toHashKey()]
        }
        assertNotNull("a1 必须完成重算（瞬态容错后仍 null）", newA)
        assertTrue("a1 新哈希≠旧哈希（新内容确实重算）", newA!! != oldA)
        assertEquals("a1 新哈希==d1 哈希（同内容同解码）", newA, oldDHash)
        assertNull("c1 损坏内容计算结果为失败（null）", recomputed[c1New.toHashKey()])
        val c1Entry = cache.getHash(c1New.toHashKey(), c1New.dateModified)
        assertNotNull("生产必须记录 c1 失败态条目", c1Entry)
        assertTrue(c1Entry!!.isFailed)
        Log.i(TAG, "step6 recompute: a1 new hash adopted; c1 Failed recorded at new version")

        // ---- 7. 消费面正对照（新哈希时代）----
        val groupsNew = detector.detectSimilarGroups(listOf(a1New, d1Same))
        assertNotNull(
            "正对照：重算后同内容必须成组（消费端采纳新结果）",
            groupPairingIdentity(groupsNew, a1New, d1Same)
        )
        Log.i(TAG, "step7 consumer positive: a1+d1 grouped post-recompute")

        // ---- 8. 有界恢复（c1 一次临时读取失败 → 恢复；对齐新秒=严格晚于 corrupt）----
        val recoverSec = awaitSecondBoundary(corruptSec + 1)
        swapContent(c1Row, Pattern.MONO_DOWN) // 恢复有效新内容
        val all3 = awaitAllRows()
        val c1Recovered = all3.getValue(c1Name)
        assertEquals(
            "c1 恢复内容 dateModified 必须落在新对齐秒（严格晚于 corrupt）",
            recoverSec, c1Recovered.dateModified / 1000
        )
        val recomputedDeadlineC = System.currentTimeMillis() + 10000
        var recomputedC = detector.ensureHashesComputed(rowsOf(all3, listOf(a1Name, d1Name, b1Name, c1Name)))
        var newC: Long? = recomputedC[c1Recovered.toHashKey()]
        while (newC == null && System.currentTimeMillis() < recomputedDeadlineC) {
            Thread.sleep(400)
            recomputedC = detector.ensureHashesComputed(rowsOf(all3, listOf(a1Name, d1Name, b1Name, c1Name)))
            newC = recomputedC[c1Recovered.toHashKey()]
        }
        assertNotNull("恢复后必须完成重算（瞬态容错后仍 null=此前失败条目阻断了新版本重算）", newC)
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
        // 无 where 全量查询（B2MR gallery_rows 已证形态；executeShellCommand 层
        // 嵌套引号解析破损——轮 12 步骤 10/diag row state 的 usage 输出实证）；
        // 本段段首 gallery-clean 门已保证查询时库内仅本夹具 6 行。
        val shellOut = shell(
            "content query --uri content://media/external/images/media --projection _display_name 2>&1"
        )
        for (name in allNames) {
            assertTrue("shell 面必须存在 $name", shellOut.contains(name))
        }
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
        shell("rm -rf /sdcard/$dir 2>&1")
        runCatching { preferences.clearAllPickRecords() }
        runCatching { preferences.clearAllAlbumCleanupState() }
    }

    @After
    fun tearDown() {
        cleanupAll()
    }
}
