package com.tabula.v3.ui.components

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 包D / T06 两种卡片模式生命周期覆盖核对：DRAW 模式回牌状态机（DrawCardState
 * reducer）的动画生命周期断言补齐。
 *
 * 覆盖核对结论（2026-11-08，基于 master 43a8da1）：DrawCardRecallIdentityTest
 * 钉住发牌身份捕获与 revalidateDealtStack 重校验/收牌预览，但 reducer 的
 * Recall / AnimationComplete / Reset 动作转移本身此前无直接断言——"动画结束
 * 清除过渡状态、不回写位置"是 T06 验收行"动画/退出正确释放"在 DRAW 模式
 * 状态机层的语义。本测试补齐（纯函数、确定性，无行为反例——生产行为已正确，
 * 仅缺断言，按"补断言并如实登记"口径执行，无生产改动）。
 *
 * 证据边界：只钉状态机转移；Compose 动画执行（executeRecallAnimation 的
 * dispatch 时序、finally 复位）无单测基建，由源码审读核验，不在此宣称。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DrawCardStateLifecycleTest {

    private fun row(volume: String, id: Long) = ImageFile(
        id = id,
        uri = Uri.parse("content://media/$volume/images/media/$id"),
        displayName = "img-$volume-$id.jpg",
        dateModified = 1_000L,
        size = 100L,
        width = 64,
        height = 64,
        bucketDisplayName = "Camera",
        volumeName = volume
    )

    private val deck = listOf(
        row("external_primary", 1L),
        row("external_primary", 2L),
        row("external_primary", 3L),
        row("external_primary", 4L),
        row("external_primary", 5L)
    )

    @Test
    fun drawOnLastCardIsRejectedAndStateUnchanged() {
        // 边界：最后一张是常驻中卡，不可发——发牌动作不得推进索引或入栈
        val dealt = listOf(DealtCard(0, 1L, "external_primary"))
        val state = DrawCardState(currentIndex = deck.lastIndex, dealtStack = dealt)

        val after = drawCardReducer(state, DrawCardAction.Draw, deck)

        assertEquals(state, after)
    }

    @Test
    fun drawRecordsFullIdentityAndClearsTransientAnimationState() {
        // 带残留过渡态（上一轮回牌未完成 AnimationComplete）时发牌：
        // 新 DealtCard 记录 (originalIndex, cardId, cardVolumeName) 完整身份，
        // 残留 returningCard/exitingRightCard 一并清除（不跨轮回写）
        val lingering = DrawCardState(
            currentIndex = 0,
            dealtStack = emptyList(),
            returningCard = DealtCard(3, 9L, "external_primary"),
            exitingRightCard = 9L
        )

        val after = drawCardReducer(lingering, DrawCardAction.Draw, deck)

        assertEquals(1, after.currentIndex)
        assertEquals(1, after.dealtStack.size)
        assertEquals(0, after.dealtStack[0].originalIndex)
        assertEquals(1L, after.dealtStack[0].cardId)
        assertEquals("external_primary", after.dealtStack[0].cardVolumeName)
        assertNull("发牌必须清除残留的回牌过渡态", after.returningCard)
        assertNull("发牌必须清除残留的右卡淡出标记", after.exitingRightCard)
    }

    @Test
    fun recallReturnsLastDealtCardAndCapturesCurrentRightCardId() {
        // 发两张（currentIndex=2）后收牌：栈顶（index 1 的牌）进入回牌过渡，
        // exitingRightCard 记录收牌前右卡 deck[currentIndex+2]=deck[4] 的 ID
        var state = DrawCardState(currentIndex = 0)
        repeat(2) { state = drawCardReducer(state, DrawCardAction.Draw, deck) }
        assertEquals(2, state.currentIndex)

        val after = drawCardReducer(state, DrawCardAction.Recall, deck)

        assertEquals(1, after.currentIndex)
        assertEquals(1, after.dealtStack.size)
        assertEquals(1, after.dealtStack[0].cardId)
        assertEquals(2L, after.returningCard!!.cardId)
        assertEquals(5L, after.exitingRightCard)
        assertEquals("回牌不得改动保留条目的 originalIndex", 0, after.dealtStack[0].originalIndex)
    }

    @Test
    fun recallWithoutRightCardSetsExitingRightCardNull() {
        // deck 只有 3 张、已发 2 张（currentIndex=2）：右侧无 deck[4]，
        // exitingRightCard 必须为 null（不得越界或记错行）
        val smallDeck = deck.take(3)
        var state = DrawCardState(currentIndex = 0)
        repeat(2) { state = drawCardReducer(state, DrawCardAction.Draw, smallDeck) }
        assertEquals(2, state.currentIndex)

        val after = drawCardReducer(state, DrawCardAction.Recall, smallDeck)

        assertEquals(1, after.currentIndex)
        assertEquals(2L, after.returningCard!!.cardId)
        assertNull(after.exitingRightCard)
    }

    @Test
    fun recallWithEmptyStackOrTopOfDeckIsNoOp() {
        val fresh = DrawCardState(currentIndex = 0)
        assertEquals(fresh, drawCardReducer(fresh, DrawCardAction.Recall, deck))

        // currentIndex=0 且栈空（canRecall false）：不产生过渡态
        val after = drawCardReducer(fresh, DrawCardAction.Recall, deck)
        assertNull(after.returningCard)
        assertNull(after.exitingRightCard)
    }

    @Test
    fun animationCompleteClearsTransientStateButKeepsPositionAndStack() {
        // 动画结束：只清除过渡态（returningCard/exitingRightCard），
        // 当前位置与发牌栈原样保留（旧结果不回写到位置状态）
        val midRecall = DrawCardState(
            currentIndex = 1,
            dealtStack = listOf(DealtCard(0, 1L, "external_primary")),
            returningCard = DealtCard(1, 2L, "external_primary"),
            exitingRightCard = 5L
        )

        val after = drawCardReducer(midRecall, DrawCardAction.AnimationComplete, deck)

        assertNull(after.returningCard)
        assertNull(after.exitingRightCard)
        assertEquals(1, after.currentIndex)
        assertEquals(midRecall.dealtStack, after.dealtStack)
    }

    @Test
    fun resetReturnsToFreshState() {
        var state = DrawCardState(currentIndex = 0)
        repeat(3) { state = drawCardReducer(state, DrawCardAction.Draw, deck) }
        state = drawCardReducer(state, DrawCardAction.Recall, deck)
        assertTrue(state.returningCard != null || state.exitingRightCard != null)

        val after = drawCardReducer(state, DrawCardAction.Reset, deck)

        assertEquals(DrawCardState(), after)
    }

    @Test
    fun dealtStackIdentitySurvivesRepeatedDrawRecallCycles() {
        // 往返多轮：发 2 → 收 1（收回 deck[1]，发牌窗口回退）→ 再发 2（deck[1]
        // 再次发牌后推进到 deck[2]）。栈内 (卷,ID) 与 originalIndex 始终与发牌
        // 时的 deck 行一致（回牌后重发同位置照片是设计语义，身份记录不得漂移）
        var state = DrawCardState(currentIndex = 0)
        repeat(2) { state = drawCardReducer(state, DrawCardAction.Draw, deck) }
        state = drawCardReducer(state, DrawCardAction.Recall, deck)
        state = drawCardReducer(state, DrawCardAction.AnimationComplete, deck)
        repeat(2) { state = drawCardReducer(state, DrawCardAction.Draw, deck) }

        assertEquals(3, state.currentIndex)
        assertEquals(3, state.dealtStack.size)
        assertEquals(listOf(1L, 2L, 3L), state.dealtStack.map { it.cardId })
        assertEquals(listOf(0, 1, 2), state.dealtStack.map { it.originalIndex })
        assertSame(deck[2], deck[state.dealtStack[2].originalIndex])
    }
}
