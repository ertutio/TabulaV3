package com.tabula.v3.ui.components

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C1/T03-DECKRECALL：回牌（收牌）定位按完整媒体身份（卷|ID）。
 *
 * 发牌栈 DealtCard 记录的 cardId 是裸 ImageFile.id；deck 变化（如删除图片）
 * 后的重校验按裸 ID indexOfFirst 定位——多卷 deck 中不同卷存在相同数值 ID
 * 时，回牌会飞回另一卷的同 ID 照片（错行）。本测试驱动真实生成路径
 * （drawCardReducer Draw）+ deck 变化后的 revalidateDealtStack 重校验，
 * 断言回牌消费路径（dealtStack.last → images[originalIndex]）命中发牌时
 * 那一卷的行。
 *
 * 场景：deck = [X100(A卷), A5(A卷), B5(B卷), C6(A卷)]。发三张后删除 X100
 * （位于 currentIndex 之前的照片），images 收缩且外部 currentIndex 回退，
 * 触发重校验。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DrawCardRecallIdentityTest {

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

    @Test
    fun recallRelocatesCrossVolumeSameIdCardsByFullIdentity() {
        val x100 = row("external_primary", 100L)
        val a5 = row("external_primary", 5L)
        val b5 = row("external_secondary", 5L)
        val c6 = row("external_primary", 6L)
        val deck = listOf(x100, a5, b5, c6)

        var state = DrawCardState(currentIndex = 0)
        repeat(3) { state = drawCardReducer(state, DrawCardAction.Draw, deck) }
        // 已发 [X100, A5, B5]，currentIndex = 3
        assertEquals(3, state.dealtStack.size)

        // 外部删除 X100：images 收缩、currentIndex 回退到 2 → 触发重校验
        val imagesAfter = listOf(a5, b5, c6)
        val revalidated = revalidateDealtStack(state.dealtStack, imagesAfter, currentIndex = 2)

        // X100 已删按缺失丢弃；A5/B5 保留
        assertEquals(2, revalidated.size)
        // 回牌消费路径：dealtStack.last（B5 的条目）→ images[originalIndex]
        // 必须命中 B 卷那一行，而非裸 ID indexOfFirst 命中的 A 卷同 ID 行
        val recallTarget = imagesAfter[revalidated.last().originalIndex]
        assertEquals(b5.uri, recallTarget.uri)
        assertEquals("external_secondary", recallTarget.volumeName)
        // A 卷条目同样定位准确
        assertEquals(a5.uri, imagesAfter[revalidated.first().originalIndex].uri)
    }

    @Test
    fun singleVolumeRecallKeepsPositionsAfterDeckShrink() {
        // 控制组：单卷无身份歧义，重校验后条目索引保持，普通回牌行为不变
        val deck = listOf(
            row("external_primary", 1L),
            row("external_primary", 2L),
            row("external_primary", 3L),
            row("external_primary", 4L)
        )
        var state = DrawCardState(currentIndex = 0)
        repeat(2) { state = drawCardReducer(state, DrawCardAction.Draw, deck) }
        assertEquals(2, state.dealtStack.size)

        // 删除 currentIndex 之后的 4 号照片；currentIndex 不变
        val imagesAfter = deck.take(3)
        val revalidated = revalidateDealtStack(state.dealtStack, imagesAfter, currentIndex = 2)

        assertEquals(2, revalidated.size)
        assertEquals(0, revalidated[0].originalIndex)
        assertEquals(1, revalidated[1].originalIndex)
        // 回牌消费路径命中最后发出的那张
        assertEquals(deck[1].uri, imagesAfter[revalidated.last().originalIndex].uri)
    }
}
