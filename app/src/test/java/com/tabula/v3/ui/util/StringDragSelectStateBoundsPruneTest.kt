package com.tabula.v3.ui.util

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A4/T03 清理项回归（2026-11-08 登记→实施）：itemBounds 只增不减——离场行
 * （删除/换批后不再存在的 id）的旧边界残留，极端时序下 findItemAt 会命中
 * 离场键。解析端虽天然兜底（离场 id 不在候选列表，不会产生有效选择），但
 * 残留键仍会占据命中路径并随长会话增长。pruneBoundsNotIn 将边界集修剪到
 * 当前数据列表，删除该类残留。
 */
class StringDragSelectStateBoundsPruneTest {
    private fun bounds(x: Double): ItemBounds =
        ItemBounds(left = x.toFloat(), top = 0f, right = (x + 100.0).toFloat(), bottom = 100f)

    private fun stateWithBounds(ids: List<String>): StringDragSelectState<String> {
        val state = StringDragSelectState<String>(
            items = { ids },
            itemKey = { it },
            selectedIds = { emptySet() },
            onSelectionChange = {},
            onEnterSelectionMode = {}
        )
        ids.forEachIndexed { index, id -> state.registerItemBounds(id, bounds(index * 150.0)) }
        return state
    }

    @Test
    fun pruneRemovesBoundsOfDepartedRowsAndKeepsCurrentOnes() {
        val state = stateWithBounds(listOf("a", "b", "c"))

        state.pruneBoundsNotIn(setOf("a", "b"))

        assertEquals(setOf("a", "b"), state.itemBounds.keys)
    }

    @Test
    fun findItemAtNoLongerHitsDepartedRowRegion() {
        val state = stateWithBounds(listOf("a", "b", "c"))
        // c 的边界区域中心（此例中 3 个元素横排：0-100, 150-250, 300-400）
        val cCenter = Offset(350f, 50f)
        assertEquals("c", state.findItemAt(cCenter))

        state.pruneBoundsNotIn(setOf("a", "b"))

        assertNull("departed row's stale bounds must not be hit", state.findItemAt(cCenter))
        assertEquals("a", state.findItemAt(Offset(50f, 50f)))
    }

    @Test
    fun pruneIsIdempotentAndSafeForUnknownIds() {
        val state = stateWithBounds(listOf("a", "b"))
        state.pruneBoundsNotIn(setOf("a", "b"))
        state.pruneBoundsNotIn(setOf("a", "b", "ghost"))
        assertTrue(state.itemBounds.keys.containsAll(setOf("a", "b")))
        assertFalse(state.itemBounds.keys.contains("ghost"))
    }
}
