package com.tabula.v3.data.intent

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import java.io.File
import java.util.TimeZone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 合成样图上的规则级评估（#94）。
 *
 * 这一组**不跑 OCR**：它把 `golden.json` 里的 `golden_lines` 当作「OCR 已经完全读对」
 * 的输入，量的是**规则与排序本身**的质量上限。真实 OCR 的误差由设备测试
 * （`IntentSampleOcrDeviceTest`）量，两者分开报告，避免把 OCR 错误算到规则头上。
 *
 * 这样分工的好处：规则回归（改一个词表会不会把订单误判成优惠）可以在毫秒级跑完，
 * 不需要模拟器，也不会因为机器没装模型而整组跳过。
 *
 * 样图本身全部是合成数据，见 tools/intent-samples/README.md。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class IntentSampleGoldenTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** 与样图同时生成的「现在」：2026-11-25 12:00 UTC。 */
    private val now = 1_796_000_000_000L

    private data class Sample(
        val file: String,
        val category: String,
        val expectation: String,
        val mustKeepProtected: Boolean,
        val goldenLines: List<String>,
        val expectedSignals: List<String>,
        val deadlineDate: String,
        val savedDaysAgo: Int,
        val sensitiveTokens: List<String>
    )

    private fun loadSamples(): List<Sample> {
        val file = locateGolden()
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val array = root.getJSONArray("samples")
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            fun stringList(key: String): List<String> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return (0 until a.length()).map { a.getString(it) }
            }
            Sample(
                file = o.getString("file"),
                category = o.getString("category"),
                expectation = o.getString("expectation"),
                mustKeepProtected = o.getBoolean("must_keep_protected"),
                goldenLines = stringList("golden_lines"),
                expectedSignals = stringList("expected_signals"),
                deadlineDate = o.optString("deadline_date", ""),
                savedDaysAgo = o.optInt("screenshot_saved_days_ago", 10),
                sensitiveTokens = stringList("sensitive_tokens")
            )
        }
    }

    private fun locateGolden(): File {
        val candidates = listOf(
            File("src/test/resources/intent_samples/golden.json"),
            File("app/src/test/resources/intent_samples/golden.json")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("golden.json 未找到，检查 app/src/test/resources/intent_samples/")
    }

    private fun photo(id: Long, sample: Sample): ImageFile {
        val savedAt = now - sample.savedDaysAgo * 86_400_000L
        return ImageFile(
            id = id,
            uri = Uri.parse("content://media/external_primary/images/media/$id"),
            displayName = sample.file,
            dateModified = savedAt,
            size = 200_000L + id,
            width = 720,
            height = 1280,
            bucketDisplayName = "Screenshots",
            volumeName = "external_primary",
            relativePath = "DCIM/Screenshots/",
            dateAdded = savedAt
        )
    }

    /**
     * 样图在设计上就让 OCR 读不清的那几张，规则测试里必须**按设计意图**喂低置信度，
     * 否则等于用「完美 OCR」去测一张故意糊掉的图，指标会虚高。
     *
     * 注意 `hard-rotate-01` 是「文字倾斜但仍可读」，它期望被正常推荐；
     * 只有 `hard-blurry-*` / `hard-nowords-*` 是刻意读不清的。
     */
    private fun confidenceFor(sample: Sample): Float = when {
        sample.file.startsWith("hard-blurry") || sample.file.startsWith("hard-nowords") -> 0.25f
        else -> 0.92f
    }

    private fun verdictOf(sample: Sample, id: Long): IntentVerdict = IntentRules.classify(
        lines = sample.goldenLines,
        savedAtMillis = now - sample.savedDaysAgo * 86_400_000L,
        nowMillis = now,
        ocrConfidence = confidenceFor(sample),
        timeZone = utc
    )

    // ==================== 逐条硬断言 ====================

    @Test
    fun sampleSetInvariants() {
        val samples = loadSamples()
        assertEquals("样图应为 50 张", 50, samples.size)
        assertEquals("文件名不能重复", samples.size, samples.map { it.file }.distinct().size)
        val counts = samples.groupingBy { it.category }.eachCount()
        assertEquals(6, counts["expired_coupon"])
        assertEquals(6, counts["temp_code"])
        assertEquals(6, counts["event"])
        assertEquals(6, counts["share"])
        assertEquals(13, counts["keep"])
        // 每张都要给出「图上实际写了什么」，否则规则测试没有输入
        assertTrue(samples.all { it.goldenLines.isNotEmpty() || it.category == "hard" })
    }

    @Test
    fun protectedSamplesAreAlwaysExcluded() {
        val samples = loadSamples().filter { it.mustKeepProtected }
        assertTrue("应有保护类样图", samples.isNotEmpty())
        samples.forEachIndexed { index, sample ->
            val verdict = verdictOf(sample, 1000L + index)
            assertEquals(
                "${sample.file} 必须被保护规则排除",
                IntentRecommendation.KEEP,
                verdict.recommendation
            )
            assertEquals(
                "${sample.file} 应归入被排除集合",
                true,
                verdict.recommendation == IntentRecommendation.KEEP
            )
        }
    }

    @Test
    fun keepSamplesAreNeverRecommended() {
        val samples = loadSamples().filter { it.expectation == "keep" }
        assertTrue(samples.isNotEmpty())
        samples.forEachIndexed { index, sample ->
            val verdict = verdictOf(sample, 2000L + index)
            assertTrue(
                "${sample.file} 不该出现在清理推荐里（实际 ${verdict.recommendation}/${verdict.category}）",
                verdict.recommendation != IntentRecommendation.RECOMMEND
            )
        }
    }

    @Test
    fun uncertainSamplesStayUncertain() {
        val samples = loadSamples().filter { it.expectation == "uncertain" }
        samples.forEachIndexed { index, sample ->
            val verdict = verdictOf(sample, 3000L + index)
            assertEquals(
                "${sample.file} 应保持不确定（实际 ${verdict.category}/${verdict.recommendation}）",
                IntentRecommendation.UNCERTAIN,
                verdict.recommendation
            )
        }
    }

    @Test
    fun recommendSamplesActuallyGetRecommended() {
        val samples = loadSamples().filter { it.expectation == "recommend" }
        val mismatched = samples.mapIndexed { index, sample -> sample to verdictOf(sample, 4000L + index) }
            .filter { (_, verdict) -> verdict.recommendation != IntentRecommendation.RECOMMEND }
        assertTrue(
            "以下样图期望推荐但没被推荐：" +
                mismatched.joinToString("; ") { (s, v) -> "${s.file}→${v.category}/${v.recommendation}(${v.reason})" },
            mismatched.isEmpty()
        )
    }

    @Test
    fun lowerPrioritySamplesNeverClaimExpired() {
        val samples = loadSamples().filter { it.category == "old_price" }
        samples.forEachIndexed { index, sample ->
            val verdict = verdictOf(sample, 5000L + index)
            assertEquals("旧比价不应标已过期", ExpiryState.UNKNOWN, verdict.expiry)
            assertTrue(
                "旧比价的理由里不能出现「已失效」",
                !verdict.reason.contains("已失效") && !verdict.reason.contains("已过期")
            )
        }
    }

    @Test
    fun sensitiveTokensNeverAppearInReasons() {
        val samples = loadSamples()
        samples.forEachIndexed { index, sample ->
            val verdict = verdictOf(sample, 6000L + index)
            sample.sensitiveTokens.forEach { token ->
                assertTrue(
                    "${sample.file} 的理由里出现了敏感片段 $token：${verdict.reason}",
                    !verdict.reason.contains(token)
                )
            }
        }
    }

    // ==================== 组批与试点指标 ====================

    /**
     * 三组对照（规则级，不含真实 OCR）。
     *
     * A. 按存入时间：只按存入时间倒序取前 10；
     * B. 本地规则：金标准文字（=完美 OCR）喂 [IntentRules]，取批次；
     * C. B + 文字辅助：这里用「辅助只把不确定项降级、不做任何升级」的**保守下限**模拟，
     *    代表「模型没带来额外信息」时 C 组不会比 B 更差。真实 API 的用量与效果
     *    由 `tools/intent-samples/run_deepseek_pilot.py` 单独量（见报告）。
     *
     * 报告口径按类别分开，不只看总体平均。所有数字都是**合成样图**上的，
     * 不代表真实用户库的分布。
     */
    @Test
    fun threeWayComparisonOnSyntheticSamples() {
        val samples = loadSamples()
        val batchLimit = 10

        val candidates = samples.mapIndexed { index, sample ->
            IntentCandidateInput(
                image = photo(7000L + index, sample),
                savedAtMillis = now - sample.savedDaysAgo * 86_400_000L,
                ocrLines = sample.goldenLines,
                ocrConfidence = confidenceFor(sample)
            ) to sample
        }

        fun shouldClean(sample: Sample) = sample.expectation == "recommend"
        fun shouldKeep(sample: Sample) = sample.expectation == "keep"

        // ---------- A. 按存入时间 ----------
        val byTime = candidates.sortedByDescending { it.first.savedAtMillis }.take(batchLimit)
        val aClean = byTime.count { shouldClean(it.second) }
        val aKeep = byTime.count { shouldKeep(it.second) }

        // ---------- B. 本地规则 ----------
        val classified = IntentBatchBuilder.classifyAll(candidates.map { it.first }, now, utc)
        val batch = IntentBatchBuilder.assemble(classified, batchLimit)
        val byRule = batch.ordered.map { candidate ->
            candidates.first { it.first.image.id == candidate.image.id }
        }
        val bClean = byRule.count { shouldClean(it.second) }
        val bKeep = byRule.count { shouldKeep(it.second) }

        // ---------- 全量覆盖（不受批次截断影响） ----------
        val fullVerdicts = classified.map { item ->
            candidates.first { it.first.image.id == item.input.image.id }.second to item.verdict
        }
        val fullKeepFalsePositive = fullVerdicts.count { (sample, verdict) ->
            shouldKeep(sample) && verdict.recommendation.isCleanable
        }
        val fullCleanRecall = fullVerdicts.count { (sample, verdict) ->
            shouldClean(sample) && verdict.recommendation == IntentRecommendation.RECOMMEND
        }
        val fullUndecided = fullVerdicts.count { (_, verdict) ->
            verdict.recommendation == IntentRecommendation.UNCERTAIN
        }
        val fullExcluded = fullVerdicts.count { (_, verdict) ->
            verdict.recommendation == IntentRecommendation.KEEP
        }

        // ---------- 逐类别 ----------
        val perCategory = fullVerdicts.groupBy { it.first.category }.toSortedMap()
            .map { (category, rows) ->
                val clean = rows.count { it.first.expectation == "recommend" }
                val recommended = rows.count { it.second.recommendation == IntentRecommendation.RECOMMEND }
                val kept = rows.count { it.second.recommendation == IntentRecommendation.KEEP }
                val uncertainRows = rows.count { it.second.recommendation == IntentRecommendation.UNCERTAIN }
                "$category: n=${rows.size} 应清=$clean 实际推荐=$recommended 排除=$kept 不确定=$uncertainRows"
            }

        // ---------- 硬指标 ----------
        assertEquals("规则组不应把应留图排进批次", 0, bKeep)
        assertEquals("全量判定里也不能把应留图当成可清理", 0, fullKeepFalsePositive)
        val recommendedTotal = samples.count { it.expectation == "recommend" }
        println("[pilot] 应清项总数 $recommendedTotal，实际推荐 $fullCleanRecall")
        assertTrue("规则组前 10 张里应有可清理项（实际 $bClean）", bClean >= 8)

        val aOldest = byTime.maxOf { it.second.savedDaysAgo }
        val bOldest = byRule.maxOf { it.second.savedDaysAgo }

        println("[pilot] A 按存入时间: 前10 可清 $aClean/10, 应留误入 $aKeep, 最老 $aOldest 天")
        println("[pilot] B 本地规则:   前10 可清 $bClean/10, 应留误入 $bKeep, 最老 $bOldest 天")
        println("[pilot] B 全量: 应清召回 $fullCleanRecall/$recommendedTotal, 应留误入 $fullKeepFalsePositive, " +
            "不确定 $fullUndecided/${samples.size}, 排除 $fullExcluded/${samples.size}")
        println("[pilot] 类别分布 A: ${byTime.groupingBy { it.second.category }.eachCount()}")
        println("[pilot] 类别分布 B: ${byRule.groupingBy { it.second.category }.eachCount()}")
        perCategory.forEach { println("[pilot] 分类别 $it") }
    }

    /**
     * 与「按整理目的凑一批图」直接相关的一条：批次里高置信项不够时，
     * 结果必须**短**（不硬凑），而不是拿低置信项填满。
     */
    @Test
    fun batchStaysShortWhenEvidenceIsThin() {
        val samples = loadSamples().filter { it.category == "hard" }
        val batch = IntentBatchBuilder.build(
            candidates = samples.mapIndexed { index, sample ->
                IntentCandidateInput(
                    image = photo(9000L + index, sample),
                    savedAtMillis = now - sample.savedDaysAgo * 86_400_000L,
                    // 模拟 OCR 读不清：置信度低于门槛
                    ocrLines = sample.goldenLines,
                    ocrConfidence = 0.2f
                )
            },
            nowMillis = now,
            batchLimit = 10,
            timeZone = utc
        )
        assertTrue("读不清时不应凑满批次", batch.ordered.isEmpty())
        assertTrue(batch.shortOfLimit)
    }
}
