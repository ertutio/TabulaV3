package com.tabula.v3.data.intent

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 提交边界复核单测（#94 第 2 轮审计，第 5 条）。
 *
 * 审计要求：点「移入回收站」时用**当前快照**按完整媒体身份复核
 * 可见性、内容版本、暂存范围与最新保护/长期保留状态；变化的项跳过并刷新提示。
 *
 * 每条用例对应一种「分析之后世界变了」的情形。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class IntentCommitGuardTest {

    private val now = 1_796_000_000_000L
    private val sources = setOf("external_primary|dcim/screenshots")

    private fun photo(
        id: Long,
        size: Long = 1000L,
        modified: Long = now,
        path: String = "DCIM/Screenshots/",
        volume: String = "external_primary"
    ) = ImageFile(
        id = id,
        uri = Uri.parse("content://media/$volume/images/media/$id"),
        displayName = "s$id.png",
        dateModified = modified,
        size = size,
        width = 720, height = 1280,
        bucketDisplayName = "Screenshots",
        volumeName = volume,
        relativePath = path,
        dateAdded = now - 10L * 86_400_000L
    )

    @Test
    fun unchangedSelectionIsEligible() {
        val a = photo(1)
        val report = IntentCommitGuard.review(
            selected = listOf(a),
            currentLibrary = listOf(a),
            retainedIdentityKeys = emptySet(),
            recycledIdentityKeys = emptySet(),
            stagedSourceKeys = sources
        )
        assertEquals(1, report.eligible.size)
        assertTrue(report.skipped.isEmpty())
    }

    @Test
    fun missingFromLibraryIsSkipped() {
        val report = IntentCommitGuard.review(
            selected = listOf(photo(1)),
            currentLibrary = emptyList(),     // 权限被收窄 / 已被删除
            retainedIdentityKeys = emptySet(),
            recycledIdentityKeys = emptySet(),
            stagedSourceKeys = sources
        )
        assertTrue(report.eligible.isEmpty())
        assertEquals(IntentCommitGuard.Outcome.MISSING, report.skipped.single().outcome)
    }

    @Test
    fun contentVersionChangeIsSkipped() {
        val before = photo(2, size = 1000L, modified = now)
        val after = photo(2, size = 2048L, modified = now + 5000L)  // 同一 ID 被重写
        val report = IntentCommitGuard.review(
            selected = listOf(before),
            currentLibrary = listOf(after),
            retainedIdentityKeys = emptySet(),
            recycledIdentityKeys = emptySet(),
            stagedSourceKeys = sources
        )
        assertEquals(IntentCommitGuard.Outcome.CONTENT_CHANGED, report.skipped.single().outcome)
    }

    @Test
    fun retainedAfterAnalysisIsSkipped() {
        val image = photo(3)
        val report = IntentCommitGuard.review(
            selected = listOf(image),
            currentLibrary = listOf(image),
            retainedIdentityKeys = setOf("external_primary|3"),   // 分析后被标长期保留
            recycledIdentityKeys = emptySet(),
            stagedSourceKeys = sources
        )
        assertEquals(IntentCommitGuard.Outcome.RETAINED, report.skipped.single().outcome)
    }

    @Test
    fun alreadyRecycledIsSkipped() {
        val image = photo(4)
        val report = IntentCommitGuard.review(
            selected = listOf(image),
            currentLibrary = listOf(image),
            retainedIdentityKeys = emptySet(),
            recycledIdentityKeys = setOf("external_primary|4"),
            stagedSourceKeys = sources
        )
        assertEquals(IntentCommitGuard.Outcome.ALREADY_RECYCLED, report.skipped.single().outcome)
    }

    @Test
    fun outOfStagedScopeIsSkipped() {
        val moved = photo(5, path = "Pictures/WeiXin/")   // 换了目录
        val report = IntentCommitGuard.review(
            selected = listOf(photo(5)),
            currentLibrary = listOf(moved),
            retainedIdentityKeys = emptySet(),
            recycledIdentityKeys = emptySet(),
            stagedSourceKeys = sources
        )
        assertEquals(IntentCommitGuard.Outcome.OUT_OF_SCOPE, report.skipped.single().outcome)
    }

    @Test
    fun protectedCandidateIsSkipped() {
        val image = photo(6)
        val report = IntentCommitGuard.review(
            selected = listOf(image),
            currentLibrary = listOf(image),
            retainedIdentityKeys = emptySet(),
            recycledIdentityKeys = emptySet(),
            stagedSourceKeys = sources,
            protectCandidate = { true }
        )
        assertEquals(IntentCommitGuard.Outcome.PROTECTED, report.skipped.single().outcome)
    }

    @Test
    fun partialSuccessReportsEachOutcomeSeparately() {
        val ok1 = photo(11)
        val ok2 = photo(12)
        val retained = photo(13)
        val gone = photo(14)
        val report = IntentCommitGuard.review(
            selected = listOf(ok1, ok2, retained, gone),
            currentLibrary = listOf(ok1, ok2, retained),    // 14 不见了
            retainedIdentityKeys = setOf("external_primary|13"),
            recycledIdentityKeys = emptySet(),
            stagedSourceKeys = sources
        )
        assertEquals(2, report.eligible.size)
        assertEquals(2, report.skipped.size)
        assertEquals(1, report.skippedByOutcome[IntentCommitGuard.Outcome.RETAINED])
        assertEquals(1, report.skippedByOutcome[IntentCommitGuard.Outcome.MISSING])
        // 提示必须分别说明，而不是笼统一句「成功」
        val text = report.describe()
        assertTrue("提示要提到长期保留：$text", text.contains("长期保留"))
        assertTrue("提示要提到已不在范围：$text", text.contains("已不在这批范围"))
    }

    @Test
    fun identityIsVolumeAwareNotJustId() {
        // 同数值 ID、不同卷 → 必须当成两张不同的图
        val volA = photo(100, volume = "external_primary")
        val volB = photo(100, volume = "1234-5678")
        assertFalse(
            IntentCommitGuard.mediaIdentity(volA) == IntentCommitGuard.mediaIdentity(volB)
        )
        val report = IntentCommitGuard.review(
            selected = listOf(volA),
            currentLibrary = listOf(volB),
            retainedIdentityKeys = emptySet(),
            recycledIdentityKeys = emptySet(),
            stagedSourceKeys = sources
        )
        assertEquals(IntentCommitGuard.Outcome.MISSING, report.skipped.single().outcome)
    }

    @Test
    fun nothingSkippedMeansEmptyDescription() {
        val a = photo(20)
        val report = IntentCommitGuard.review(
            selected = listOf(a),
            currentLibrary = listOf(a),
            retainedIdentityKeys = emptySet(),
            recycledIdentityKeys = emptySet(),
            stagedSourceKeys = sources
        )
        assertEquals("", report.describe())
    }
}
