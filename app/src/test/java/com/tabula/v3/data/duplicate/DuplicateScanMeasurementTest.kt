package com.tabula.v3.data.duplicate

import android.content.Context
import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.mediaIdentityKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.function.Supplier

/**
 * #105：副本扫描三段（候选筛选 → 摘要读取 → 结果分组）的固定合成图库测量。
 *
 * 为什么要有这个文件：`MainActivity` 的副本扫描是一个 `LaunchedEffect` 协程体，
 * 候选筛选、`digestAll`、`findExactDuplicates` 混在一起，只能整体计时。
 * 要判断"重复构建身份集合"是不是热点，必须先把它拆成可分别计时的段，
 * 且用**同一份输入**跑前后两版实现。
 *
 * 口径（写死在用例里，不手改数字）：
 * - 图库、回收站、文件字节全部由本文件按固定参数合成，不读真实相册、
 *   不依赖设备、不受磁盘缓存影响。
 * - 摘要走生产代码 [ContentDigestCalculator]，分组走生产代码
 *   [DuplicateRules.findExactDuplicates]；只有"候选筛选"的两版实现分别来自
 *   生产代码 [DuplicateScanCandidates] 与 master 上 `MainActivity` 的原 lambda。
 * - 每段单独计时；候选筛选取 20 次中位数（JVM 上单次抖动太大）。
 * - 每轮都断言两版筛选结果**逐项相同**——性能对照不允许改变语义。
 *
 * 输出行以 `DUP-SCAN` 开头，CI 产物里可直接 grep 出来贴进 PR。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DuplicateScanMeasurementTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    // ==================== 合成图库 ====================

    /**
     * 一份合成图库：库内照片 + 回收站照片 + 每张照片的字节内容。
     * 两张照片用同一段字节就是"字节完全相同"——这正是要判定的关系。
     */
    private class Fixture(
        val library: List<ImageFile>,
        val recycled: List<ImageFile>
    ) {
        val allImages: List<ImageFile> get() = library + recycled
    }

    private fun photo(id: Long, volume: String, size: Long): ImageFile = ImageFile(
        id = id,
        uri = Uri.parse("content://media/$volume/images/media/$id"),
        displayName = "p$volume-$id.jpg",
        dateModified = 1_700_000_000_000L + id,
        size = size,
        width = 1080,
        height = 1440,
        bucketDisplayName = "Camera",
        volumeName = volume
    )

    /** 生成 [size] 字节、内容由 [contentId] 决定的确定性字节串。 */
    private fun payload(contentId: Int, size: Int): ByteArray {
        val out = ByteArray(size)
        var x = contentId * 2654435761L + 12345L
        for (i in 0 until size) {
            x = x * 6364136223846793005L + 1442695040888963407L
            out[i] = (x ushr 33).toByte()
        }
        return out
    }

    /**
     * 合成图库。
     *
     * @param libraryCount 库内照片总数
     * @param recycledCount 回收站照片数
     * @param sharedSizeLibraryCount 库内有多少张与回收站照片**同尺寸**
     *   （其余照片尺寸互不相同 → 用来验证"按大小缩小范围"确实把范围缩掉了）
     * @param identicalCount 其中有多少张与回收站照片**字节完全相同**
     */
    private fun fixture(
        libraryCount: Int,
        recycledCount: Int,
        sharedSizeLibraryCount: Int,
        identicalCount: Int,
        registerBytes: Boolean = true
    ): Fixture {
        require(sharedSizeLibraryCount <= libraryCount) {
            "同尺寸候选数不能超过库内总数"
        }
        require(identicalCount <= minOf(sharedSizeLibraryCount, recycledCount)) {
            "真副本数不能超过同尺寸候选数与回收站数"
        }

        val bytes = LinkedHashMap<Uri, ByteArray>()
        val library = ArrayList<ImageFile>(libraryCount)
        val recycled = ArrayList<ImageFile>(recycledCount)

        // 回收站：内容 id = 0..recycledCount-1
        val recycledPayload = (0 until recycledCount).map { payload(it, RECYCLE_SIZE) }
        for (i in 0 until recycledCount) {
            val p = photo(900_000L + i, "external_primary", RECYCLE_SIZE.toLong())
            recycled += p
            bytes[p.uri] = recycledPayload[i]
        }

        var identicalCursor = 0
        for (i in 0 until libraryCount) {
            val isShared = i < sharedSizeLibraryCount
            val size = if (isShared) RECYCLE_SIZE else OTHER_BASE_SIZE + i
            val p = photo(1000L + i, "external_primary", size.toLong())
            library += p
            bytes[p.uri] = if (identicalCursor < identicalCount) {
                // 复用回收站内容 → 字节相同，是真副本
                recycledPayload[identicalCursor++]
            } else if (isShared) {
                // 同尺寸但内容不同 → 不是副本
                payload(500_000 + i, RECYCLE_SIZE)
            } else {
                // 不同尺寸的照片永远不会被摘要（筛选已排除），字节内容无关，
                // 用小样本避免大图库把测试内存撑爆。
                payload(600_000 + i, UNREAD_PAYLOAD_SIZE)
            }
        }

        if (registerBytes) {
            bytes.forEach { (uri, data) ->
                shadowOf(context.contentResolver).registerInputStreamSupplier(uri, Supplier<InputStream?> {
                    ByteArrayInputStream(data)
                })
            }
        }
        return Fixture(library, recycled)
    }

    // ==================== 候选筛选：两版实现 ====================

    /** 生产实现（身份集合在筛选循环外构建一次）。 */
    private fun newSelect(f: Fixture): List<ImageFile> =
        DuplicateScanCandidates.selectLibraryCandidates(f.allImages, f.recycled)

    /**
     * master 上的原实现，逐字复刻 `MainActivity.kt` 的 `LaunchedEffect`：
     * 身份集合在 `filter` 的 lambda 里重建，对全库每张照片执行一次。
     */
    private fun legacySelect(f: Fixture): List<ImageFile> {
        val recycleSizes = f.recycled.map { it.size }.filter { it > 0L }.toSet()
        return f.allImages.filter { image ->
            image.size > 0L && image.size in recycleSizes &&
                image.mediaIdentityKey() !in f.recycled.map { it.mediaIdentityKey() }.toSet()
        }
    }

    /** 取 [rounds] 次中位数（纳秒）。 */
    private inline fun medianNanos(rounds: Int = 20, block: () -> Unit): Long {
        val samples = LongArray(rounds)
        for (i in 0 until rounds) {
            val start = System.nanoTime()
            block()
            samples[i] = System.nanoTime() - start
        }
        samples.sort()
        return samples[rounds / 2]
    }

    private fun ms(nanos: Long): String = "%.2f".format(nanos / 1_000_000.0)

    // ==================== 一轮完整测量 ====================

    private data class Round(
        val scenario: String,
        val libraryTotal: Int,
        val recycledTotal: Int,
        val candidates: Int,
        val digested: Int,
        val filterLegacyMs: String,
        val filterNewMs: String,
        val digestMs: String,
        val groupMs: String,
        val findings: Int,
        val copyLinks: Int,
        val fingerprint: String
    )

    private fun runRound(scenario: String, f: Fixture): Round = runBlocking {
        val legacy = legacySelect(f)
        val current = newSelect(f)
        assertEquals(
            "$scenario: 候选筛选两版结果必须逐项相同",
            legacy.map { it.mediaIdentityKey() },
            current.map { it.mediaIdentityKey() }
        )

        val filterLegacy = medianNanos { legacySelect(f) }
        val filterNew = medianNanos { newSelect(f) }

        // 摘要：与生产一致——候选为空时直接返回，不算任何摘要
        var digestNanos = 0L
        val digests = if (current.isEmpty()) {
            emptyMap()
        } else {
            val toDigest = f.recycled + current
            val digestStart = System.nanoTime()
            val result = withContext(Dispatchers.IO) {
                ContentDigestCalculator(context.contentResolver).digestAll(toDigest)
            }
            digestNanos = System.nanoTime() - digestStart
            result
        }

        // 分组：生产代码
        val recycleKeys = f.recycled.map { it.mediaIdentityKey() }.toSet()
        val groupStart = System.nanoTime()
        val findings = if (digests.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                DuplicateRules.findExactDuplicates(digests, recycleKeys)
            }
        }
        val groupNanos = System.nanoTime() - groupStart

        // 结果指纹：排序后的「回收站照片 → 副本数」，用于前后对照逐字比较
        val fingerprint = findings
            .map { it.image.mediaIdentityKey() + "=" + it.duplicates.size }
            .sorted()
            .joinToString(",")

        Round(
            scenario = scenario,
            libraryTotal = f.library.size,
            recycledTotal = f.recycled.size,
            candidates = current.size,
            digested = digests.size,
            filterLegacyMs = ms(filterLegacy),
            filterNewMs = ms(filterNew),
            digestMs = ms(digestNanos),
            groupMs = ms(groupNanos),
            findings = findings.size,
            copyLinks = findings.sumOf { it.duplicates.size },
            fingerprint = fingerprint
        )
    }

    private fun report(rounds: List<Round>) {
        println(
            "DUP-SCAN header | library | recycled | candidates | digested | " +
                "filter_legacy_ms | filter_new_ms | digest_ms | group_ms | findings | copy_links"
        )
        rounds.forEach {
            println(
                "DUP-SCAN ${it.scenario} | ${it.libraryTotal} | ${it.recycledTotal} | " +
                    "${it.candidates} | ${it.digested} | ${it.filterLegacyMs} | ${it.filterNewMs} | " +
                    "${it.digestMs} | ${it.groupMs} | ${it.findings} | ${it.copyLinks}"
            )
        }
        rounds.forEach { println("DUP-SCAN-FINGERPRINT ${it.scenario} ${it.fingerprint}") }
    }

    // ==================== 用例 ====================

    /**
     * 同尺寸候选**少**：库 1200 张，只有 12 张与回收站同尺寸，其中 4 张字节相同。
     * 日常情况——大部分照片尺寸互不相同，筛选后候选极少。
     */
    @Test
    fun fewSameSizeCandidates() {
        val f = fixture(
            libraryCount = 1200,
            recycledCount = 8,
            sharedSizeLibraryCount = 12,
            identicalCount = 4
        )
        val round = runRound("few_same_size", f)

        assertEquals("候选只应包含同尺寸的库内照片", 12, round.candidates)
        assertEquals("摘要数 = 回收站 8 + 候选 12", 20, round.digested)
        assertEquals("4 张真副本 → 4 条提示", 4, round.findings)
        assertEquals(4, round.copyLinks)
        report(listOf(round))
    }

    /**
     * 同尺寸候选**多**：库 1200 张，480 张与回收站同尺寸（全挤在同一个大小分组里），
     * 其中 40 张字节相同。最坏情况——大小预筛几乎筛不掉东西，摘要数量随库增长，
     * 分组时的成员表也最大。
     */
    @Test
    fun manySameSizeCandidates() {
        val f = fixture(
            libraryCount = 1200,
            recycledCount = 48,
            sharedSizeLibraryCount = 480,
            identicalCount = 40
        )
        val round = runRound("many_same_size", f)

        assertEquals(480, round.candidates)
        assertEquals("摘要数 = 回收站 48 + 候选 480", 528, round.digested)
        assertEquals(40, round.findings)
        assertEquals(40, round.copyLinks)
        report(listOf(round))
    }

    /** 同尺寸候选为 0：筛选后立即返回，不产生任何摘要读取。 */
    @Test
    fun noSameSizeCandidateShortCircuits() {
        val f = fixture(
            libraryCount = 600,
            recycledCount = 6,
            sharedSizeLibraryCount = 0,
            identicalCount = 0
        )
        val round = runRound("no_same_size", f)

        assertEquals(0, round.candidates)
        assertEquals("候选为空时不算摘要（与生产短路一致）", 0, round.digested)
        assertEquals(0, round.findings)
        report(listOf(round))
    }

    /**
     * 回收站里也有多张同尺寸照片：验证"回收站成员互相比不算证据"，
     * 副本数只等于库内真副本数，不多算回收站里的其他照片。
     */
    @Test
    fun recycledMembersAreNotCountedAsCopies() {
        val f = fixture(
            libraryCount = 900,
            recycledCount = 32,
            sharedSizeLibraryCount = 300,
            identicalCount = 20
        )
        val round = runRound("recycled_not_evidence", f)

        // 32 张回收站照片里前 20 张各在库内有 1 张真副本；其余 12 张内容独有
        assertEquals(20, round.findings)
        assertEquals(20, round.copyLinks)
        report(listOf(round))
    }

    /** 候选越多，摘要读取数越多——钉住测量本身没算错。 */
    @Test
    fun digestCountTracksCandidateCount() {
        val few = fixture(400, 4, 4, 2)
        val many = fixture(400, 4, 160, 2)
        assertEquals(4, newSelect(few).size)
        assertEquals(160, newSelect(many).size)
        assertTrue(
            "候选多的场景摘要数必须更多",
            newSelect(many).size + many.recycled.size > newSelect(few).size + few.recycled.size
        )
    }

    /**
     * 只测候选筛选一段，随库规模放大。
     *
     * 这一段决定了"重复构建身份集合"到底是不是热点：原实现的代价是
     * `库内张数 × 回收站张数`（每张照片重建一次回收站身份集合），新实现
     * 是 `库内张数 + 回收站张数`。两者都随库增长，但系数差一个回收站大小。
     *
     * **必须让库内每张照片都通过大小预筛**，否则 `&&` 会在第三项之前短路，
     * 身份集合根本不会被构建——那样测出来的不是热点，是短路。所以这里让
     * 库内所有照片与回收站同尺寸（最坏情况），但**只调用筛选、不算摘要**，
     * 也不注册任何字节流。
     */
    @Test
    fun filterOnlyScalesWithLibrarySize() {
        val rounds = mutableListOf<String>()
        listOf(2_000, 10_000, 20_000, 50_000).forEach { librarySize ->
            val f = fixture(
                libraryCount = librarySize,
                recycledCount = 48,
                // 全部同尺寸 → 每张照片都会走到身份键判断
                sharedSizeLibraryCount = librarySize,
                identicalCount = 0,
                registerBytes = false
            )
            assertEquals("全部同尺寸时候选 = 库内总数", librarySize, newSelect(f).size)
            // 预跑让 JIT 与集合实现类热起来，再取 50 次中位数
            repeat(5) { legacySelect(f); newSelect(f) }
            val legacy = medianNanos(50) { legacySelect(f) }
            val current = medianNanos(50) { newSelect(f) }
            rounds += "DUP-SCAN-FILTER library=$librarySize recycled=48 same_size=true " +
                "legacy_ms=${ms(legacy)} new_ms=${ms(current)} " +
                "speedup=${"%.1f".format(legacy.toDouble() / current.coerceAtLeast(1))}x"
        }
        rounds.forEach(::println)
    }
}

/** 合成图库的固定参数：改这里等于换一套输入，PR 里必须重跑并重贴数字。 */
private const val RECYCLE_SIZE = 4096
private const val OTHER_BASE_SIZE = 8192

/**
 * 不会被读取的照片的字节长度。这些照片尺寸互不相同，候选筛选阶段就被排除，
 * 永远不会进 `digestAll`——给它们真实大小只会白白占内存。
 */
private const val UNREAD_PAYLOAD_SIZE = 16
