package com.tabula.v3.testsupport

/**
 * 解析生产代码 `DeckScreen.logBatchRows` 打出的批次行。
 *
 * ## 为什么放在 sharedTest
 *
 * 这段逻辑原先内联在 `WalkStrategySettingsDeviceTest` 里，结果写错了两处，
 * 而且**只在真机/模拟器上跑 5 分钟才暴露**：
 *
 * 1. 把 `content://media/external/images/media/80:1699...` 的末段数字 `80`
 *    当成"照片 id"，再拿它去 `contains` 夹具文件名 `ws-xxxxxxxx.jpg`——
 *    两者没有任何可比关系，断言必然失败；
 * 2. 只认 `batch-site-4`，而应用启动时的首批走 site-1/2/3，于是"切换前
 *    持有的批次"只能读到上一次测试的残留行，"批次变了"成了假证明。
 *
 * 放进 sharedTest 之后，这两条都能在 JVM 上秒级验证（见
 * `BatchLogLineContractTest`），不用等模拟器。
 *
 * ## 生产侧的格式
 *
 * ```
 * currentBatch<-[batch-site-4]: content://.../media/80:1699999999,content://.../media/81:1699999998
 * ```
 *
 * 每个条目是 `<content uri>:<dateModified>`。`dateModified` 是毫秒数，
 * 不含 `:`，因此 `substringBeforeLast(":")` 拿到的就是完整 uri。
 */
object BatchLogLine {

    /** 批次行前缀 + site 名。site 形如 `batch-site-1` … `batch-site-8`。 */
    val PATTERN = Regex("""currentBatch<-\[(batch-site-\d+)]:""")

    /** 行里的 site 名（`batch-site-4` 等）；不是批次行则 null。 */
    fun siteOf(line: String): String? = PATTERN.find(line)?.groupValues?.get(1)

    /**
     * 一行批次里的照片 uri 集合。
     *
     * 返回**完整 content uri**，因为那才是能和夹具 `inserted` 直接对账的
     * 标识；末段数字是 MediaStore 行 id，跨卷不唯一，也不对应文件名。
     */
    fun parseUris(line: String): Set<String> {
        val match = PATTERN.find(line) ?: return emptySet()
        return line.substring(match.range.last + 1)
            .split(",")
            .mapNotNull { entry ->
                val uri = entry.trim().substringBeforeLast(":", "").trim()
                uri.takeIf { it.startsWith("content://") }
            }
            .toSet()
    }

    /**
     * 边界之后、最后一条匹配的批次行。
     *
     * @param lines    logcat 行（按时间升序）
     * @param boundary 边界标记文本；在 `lines` 里找不到就返回 null
     *                 （**fail-closed**：绝不退化成"读整个缓冲区"，那会把
     *                 上一次测试的批次当成这次的结果）
     * @param site     `null` = 任意 site；给具体值则只认该 site
     */
    fun lastAfter(lines: List<String>, boundary: String, site: String? = null): String? {
        val markerIdx = lines.indexOfLast { it.contains(boundary) }
        if (markerIdx < 0) return null
        return lines.subList(markerIdx + 1, lines.size).lastOrNull { line ->
            val found = siteOf(line) ?: return@lastOrNull false
            site == null || found == site
        }
    }
}
