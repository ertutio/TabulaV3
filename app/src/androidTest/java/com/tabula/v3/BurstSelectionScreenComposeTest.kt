package com.tabula.v3

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.burst.BurstSelectionRules
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.SimilarGroup
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.ui.screens.BurstSelectionScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #87 审计：用**真实选片界面**（`BurstSelectionScreen`，DeckScreen 调用的
 * 同一个 composable）验证：
 *
 * - 全部保留时确认按钮**可用**（这是本次修复的缺陷）
 * - 点击确认触发真实 [onConfirm] 回调
 * - 部分保留时按钮文案给出正确的回收张数
 * - 取消（返回）不触发 onConfirm
 */
@RunWith(AndroidJUnit4::class)
class BurstSelectionScreenComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun photo(id: Long) = ImageFile(
        id = id,
        uri = android.net.Uri.parse("content://media/external_primary/images/media/$id"),
        displayName = "p$id.jpg",
        dateModified = 1_700_000_000_000L + id * 1000,
        size = 1000L,
        width = 1080,
        height = 1440,
        bucketDisplayName = "Camera",
        volumeName = "external_primary"
    )

    private fun group(n: Int = 3): SimilarGroup {
        val images = (1L..n.toLong()).map { photo(it) }
        return SimilarGroup(
            images = images,
            startTime = images.minOf { it.dateModified },
            endTime = images.maxOf { it.dateModified }
        )
    }

    private fun allKeys(g: SimilarGroup) = BurstSelectionRules.keepAllKeys(g)

    private fun setScreen(
        g: SimilarGroup,
        kept: Set<String>,
        onConfirm: () -> Unit = {},
        onBack: () -> Unit = {},
        committing: Boolean = false
    ) {
        composeRule.setContent {
            BurstSelectionScreen(
                group = g,
                keptIdentityKeys = kept,
                isCommitting = committing,
                onToggleKeep = {},
                onKeepAll = {},
                onCancel = onBack,
                onConfirm = onConfirm
            )
        }
    }

    // ==================== 全部保留（本次修复） ====================

    @Test
    fun keepAllConfirmButtonIsEnabledAndClickable() {
        val g = group(3)
        var confirmed = false
        setScreen(g, allKeys(g), onConfirm = { confirmed = true })

        // 按钮文案应为"都保留，结束这组"，且必须可点
        composeRule.onNodeWithText("都保留，结束这组").assertIsDisplayed()
        composeRule.onNodeWithText("都保留，结束这组").assertIsEnabled()
        composeRule.onNodeWithText("都保留，结束这组").performClick()
        composeRule.waitUntil(3_000) { confirmed }
        assertTrue("全部保留时点击确认必须触发回调", confirmed)
    }

    @Test
    fun keepAllDoesNotDiscardAnything() {
        val g = group(3)
        val decision = BurstSelectionRules.resolve(g, allKeys(g))
            as BurstSelectionRules.Result.Valid
        assertTrue("全部保留时 keepsEverything 为真", decision.decision.keepsEverything)
        assertEquals("全部保留时待回收为 0", 0, decision.decision.discardCount)
        assertEquals("全部保留时保留数为全组", 3, decision.decision.keepCount)
    }

    // ==================== 部分回收 ====================

    @Test
    fun partialKeepShowsCorrectDiscardCountAndIsEnabled() {
        val g = group(3)
        val kept = setOf(g.images.first().mediaIdentityKey())
        var confirmed = false
        setScreen(g, kept, onConfirm = { confirmed = true })

        composeRule.onNodeWithText("确认：回收 2 张").assertIsDisplayed()
        composeRule.onNodeWithText("确认：回收 2 张").assertIsEnabled()
        composeRule.onNodeWithText("确认：回收 2 张").performClick()
        composeRule.waitUntil(3_000) { confirmed }
        assertTrue(confirmed)
    }

    // ==================== 无效状态 ====================

    @Test
    fun noSelectionDisablesConfirm() {
        val g = group(3)
        var confirmed = false
        setScreen(g, emptySet(), onConfirm = { confirmed = true })

        // 一张都不勾：决策无效，按钮禁用且提示"先勾选要保留的"
        composeRule.onNodeWithText("先勾选要保留的").assertIsNotEnabled()
        assertFalse(confirmed)
    }

    @Test
    fun committingDisablesConfirm() {
        val g = group(3)
        setScreen(g, allKeys(g), committing = true)
        // 提交中不允许重复点击（按钮内容换成进度指示，文案消失）
        composeRule.onNodeWithText("都保留，结束这组").assertDoesNotExist()
    }

    // ==================== 取消 ====================

    @Test
    fun backDoesNotConfirm() {
        val g = group(3)
        var confirmed = false
        var backed = false
        setScreen(g, allKeys(g), onConfirm = { confirmed = true }, onBack = { backed = true })

        // 真实返回按钮（contentDescription="返回（不提交）"）
        composeRule.onNodeWithContentDescription("返回（不提交）").performClick()
        composeRule.waitUntil(3_000) { backed }
        assertTrue("返回应触发 onNavigateBack", backed)
        assertFalse("返回绝不能触发确认", confirmed)
    }
}
