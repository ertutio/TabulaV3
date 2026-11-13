package com.tabula.v3.ui.util

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback

class StringDragSelectState<T>(
    private val items: () -> List<T>,
    private val itemKey: (T) -> String,
    private val selectedIds: () -> Set<String>,
    private val onSelectionChange: (Set<String>) -> Unit,
    private val onEnterSelectionMode: () -> Unit
) {
    internal val itemBounds = mutableMapOf<String, ItemBounds>()
    internal var gridPositionInWindow: Offset = Offset.Zero

    internal var isDragging by mutableStateOf(false)
    internal var dragStartId: String? = null
    internal var initialSelected: Boolean = false
    internal var lastHoveredId: String? = null
    internal var selectedDuringDrag = mutableSetOf<String>()

    fun registerGridPosition(position: Offset) {
        gridPositionInWindow = position
    }

    fun registerItemBounds(id: String, bounds: ItemBounds) {
        itemBounds[id] = bounds
    }

    /**
     * A4/T03 清理（2026-11-08）：将边界集修剪到当前数据列表的键——离场行
     * （删除/换批后不再存在的 id）的旧边界不再残留于命中路径。解析端对
     * 离场 id 本就天然兜底，此修剪消除极端时序命中与长会话的键积累。
     * 布局回调（onGloballyPositioned）与本方法同在主线程，无并发窗口。
     */
    fun pruneBoundsNotIn(validIds: Set<String>) {
        itemBounds.keys.retainAll(validIds)
    }

    fun onDragStart(startPosition: Offset, haptic: HapticFeedback) {
        val windowPosition = startPosition + gridPositionInWindow
        val hitItem = findItemAt(windowPosition)
        if (hitItem != null) {
            isDragging = true
            dragStartId = hitItem
            initialSelected = hitItem in selectedIds()
            lastHoveredId = hitItem
            selectedDuringDrag.clear()
            selectedDuringDrag.add(hitItem)

            onEnterSelectionMode()

            val newSelection = if (initialSelected) {
                selectedIds() - hitItem
            } else {
                selectedIds() + hitItem
            }
            onSelectionChange(newSelection)

            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    fun onDrag(position: Offset, haptic: HapticFeedback) {
        if (!isDragging) return
        val windowPosition = position + gridPositionInWindow
        val hitItem = findItemAt(windowPosition)
        if (hitItem != null && hitItem != lastHoveredId) {
            lastHoveredId = hitItem
            if (hitItem !in selectedDuringDrag) {
                selectedDuringDrag.add(hitItem)
                val newSelection = if (initialSelected) {
                    selectedIds() - hitItem
                } else {
                    selectedIds() + hitItem
                }
                onSelectionChange(newSelection)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    fun onDragEnd() {
        isDragging = false
        dragStartId = null
        lastHoveredId = null
        selectedDuringDrag.clear()
    }

    fun onDragCancel() {
        onDragEnd()
    }

    // internal 供边界修剪回归测试断言命中行为（原 private，无行为变化）
    internal fun findItemAt(position: Offset): String? {
        for ((id, bounds) in itemBounds) {
            if (bounds.contains(position)) {
                return id
            }
        }
        return null
    }
}

/**
 * 创建并记住 StringDragSelectState
 *
 * 使用 rememberUpdatedState 确保回调和数据始终指向最新值，
 * 而 State 对象本身只创建一次，不会因 selectedIds 变化而重建。
 * 这样 itemBounds 不会丢失，拖动手势也不会被中断。
 */
@Composable
fun <T> rememberStringDragSelectState(
    items: List<T>,
    itemKey: (T) -> String,
    selectedIds: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onEnterSelectionMode: () -> Unit
): StringDragSelectState<T> {
    val updatedItems = rememberUpdatedState(items)
    val updatedSelectedIds = rememberUpdatedState(selectedIds)
    val updatedOnSelectionChange = rememberUpdatedState(onSelectionChange)
    val updatedOnEnterSelectionMode = rememberUpdatedState(onEnterSelectionMode)

    val state = remember {
        StringDragSelectState(
            items = { updatedItems.value },
            itemKey = itemKey,
            selectedIds = { updatedSelectedIds.value },
            onSelectionChange = { updatedOnSelectionChange.value(it) },
            onEnterSelectionMode = { updatedOnEnterSelectionMode.value() }
        )
    }
    // A4/T03：数据列表变化时修剪离场行边界（可见行随布局回调重新登记）
    androidx.compose.runtime.LaunchedEffect(items) {
        state.pruneBoundsNotIn(items.mapTo(mutableSetOf(), itemKey))
    }
    return state
}

@Composable
fun <T> Modifier.gridDragHandlerString(
    dragSelectState: StringDragSelectState<T>,
    isSelectionMode: Boolean = false
): Modifier {
    val haptic = LocalHapticFeedback.current

    return if (isSelectionMode) {
        this
            .onGloballyPositioned { coordinates ->
                dragSelectState.registerGridPosition(coordinates.positionInWindow())
            }
            .pointerInput(dragSelectState, isSelectionMode) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragSelectState.onDragStart(offset, haptic)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragSelectState.onDrag(change.position, haptic)
                    },
                    onDragEnd = {
                        dragSelectState.onDragEnd()
                    },
                    onDragCancel = {
                        dragSelectState.onDragCancel()
                    }
                )
            }
    } else {
        this
    }
}

fun <T> Modifier.itemDragReceiverString(
    dragSelectState: StringDragSelectState<T>,
    itemId: String
): Modifier {
    return this.onGloballyPositioned { coordinates ->
        val position = coordinates.positionInWindow()
        val size = coordinates.size
        dragSelectState.registerItemBounds(
            itemId,
            ItemBounds(
                left = position.x,
                top = position.y,
                right = position.x + size.width,
                bottom = position.y + size.height
            )
        )
    }
}
