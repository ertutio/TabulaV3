package com.tabula.v3.testsupport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住批次行解析的两条关键规则——这两条都曾经写错，且只有跑模拟器才暴露。
 *
 * 1. 取**完整 content uri**，不是 MediaStore 末段数字；
 * 2. 边界标记缺失时 fail-closed 返回 null，不退化成读整个 logcat。
 */
class BatchLogLineContractTest {

    private val uriA = "content://media/external/images/media/80"
    private val uriB = "content://media/external/images/media/81"

    private fun batchLine(site: String, vararg entries: Pair<String, Long>) =
        "V/DeckScreen: currentBatch<-[$site]: " +
            entries.joinToString(",") { (uri, date) -> "$uri:$date" }

    @Test
    fun parsesFullContentUrisNotTrailingRowIds() {
        val line = batchLine("batch-site-4", uriA to 1699999999L, uriB to 1699999998L)

        val uris = BatchLogLine.parseUris(line)

        assertEquals(setOf(uriA, uriB), uris)
        // 末段数字不能泄漏成"id"——之前就是拿它去 contains 文件名，必然失败
        assertTrue("不应把 MediaStore 行 id 当成照片标识", uris.none { it == "80" || it == "81" })
    }

    @Test
    fun parsesSiteFromEveryBatchSite() {
        (1..8).forEach { index ->
            val site = "batch-site-$index"
            assertEquals(site, BatchLogLine.siteOf(batchLine(site, uriA to 1L)))
        }
    }

    @Test
    fun nonBatchLinesAreIgnored() {
        assertNull(BatchLogLine.siteOf("D/DeckScreen: Restored from checkpoint: index=3"))
        assertTrue(BatchLogLine.parseUris("D/DeckScreen: Got new batch after mode change").isEmpty())
    }

    @Test
    fun lastAfterIgnoresLinesBeforeTheBoundary() {
        val stale = batchLine("batch-site-1", uriA to 111L)
        val fresh = batchLine("batch-site-1", uriB to 222L)
        val lines = listOf(stale, "I/WalkStrategyDevice: ws-start:1000", fresh)

        assertEquals(fresh, BatchLogLine.lastAfter(lines, "ws-start:1000"))
    }

    @Test
    fun lastAfterFailsClosedWhenBoundaryIsMissing() {
        // 边界行不在了（logcat 被截断 / TAG 过滤没带上）时必须返回 null，
        // 否则"批次变了"会拿上一次测试的残留行当证据。
        val lines = listOf(batchLine("batch-site-4", uriA to 111L))
        assertNull(BatchLogLine.lastAfter(lines, "ws-start:1000"))
    }

    @Test
    fun lastAfterCanRestrictToASingleSite() {
        val siteOne = batchLine("batch-site-1", uriA to 111L)
        val siteFour = batchLine("batch-site-4", uriB to 222L)
        val lines = listOf("I/WalkStrategyDevice: ws-boundary:2000", siteOne, siteFour)

        // 页面"当前持有的批次"认任意 site
        assertEquals(siteFour, BatchLogLine.lastAfter(lines, "ws-boundary:2000", site = null))
        // "切策略后必须由 site-4 重取"只认 site-4
        assertEquals(siteFour, BatchLogLine.lastAfter(lines, "ws-boundary:2000", site = "batch-site-4"))
    }

    @Test
    fun lastAfterReturnsNullWhenOnlyOtherSitesAppearAfterBoundary() {
        // 边界之后只有 site-1（启动首批），没有 site-4 → 不能算"页面重取过"
        val lines = listOf("I/WalkStrategyDevice: ws-boundary:2000", batchLine("batch-site-1", uriA to 111L))
        assertNull(BatchLogLine.lastAfter(lines, "ws-boundary:2000", site = "batch-site-4"))
    }

    @Test
    fun anEarlyBoundaryStillCannotSeeStaleLinesFromBeforeIt() {
        // 边界之前那一批（上一次测试残留）永远不能被读成这次的结果，
        // 无论边界之后有没有新行。
        val stale = batchLine("batch-site-4", uriA to 111L)
        val fresh = batchLine("batch-site-4", uriB to 222L)

        val linesWithFresh = listOf(stale, "I/WalkStrategyDevice: ws-boundary:1000", fresh)
        assertEquals(fresh, BatchLogLine.lastAfter(linesWithFresh, "ws-boundary:1000"))

        val linesWithoutFresh = listOf(stale, "I/WalkStrategyDevice: ws-boundary:1000")
        assertNull(BatchLogLine.lastAfter(linesWithoutFresh, "ws-boundary:1000"))
    }

    @Test
    fun latestBatchAfterBoundaryWins() {
        val first = batchLine("batch-site-4", uriA to 111L)
        val second = batchLine("batch-site-4", uriB to 222L)
        val lines = listOf(
            "I/WalkStrategyDevice: ws-boundary:2000",
            first,
            second,
        )
        // 页面可能连续重取两次，读最后一条才是"当前持有的批次"
        assertEquals(second, BatchLogLine.lastAfter(lines, "ws-boundary:2000"))
    }
}
