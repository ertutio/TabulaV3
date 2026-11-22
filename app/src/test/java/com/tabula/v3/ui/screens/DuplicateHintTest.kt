package com.tabula.v3.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 回收站副本提示的措辞测试（路线图 #83 第 5 项）。
 *
 * 这条提示的职责很窄：告诉用户"你选中的里面，有几张其实还有一份相同副本"，
 * 从而在永久删除前给一次反悔机会。它**不阻止**删除，也不改变任何行为。
 * 因此测试只钉住三件事：什么时候不提示、什么时候提示、数字对不对。
 */
class DuplicateHintTest {

    @Test
    fun noSelectionMeansNoHint() {
        assertNull(buildDuplicateHint(emptySet(), mapOf("a|1" to 1)))
    }

    @Test
    fun noKnownCopiesMeansNoHint() {
        assertNull(buildDuplicateHint(setOf("a|1", "a|2"), emptyMap()))
    }

    @Test
    fun selectedPhotosWithoutCopiesProduceNoHint() {
        // 选中的两张都没有副本（计数为 0 或不在映射里）
        assertNull(buildDuplicateHint(setOf("a|1", "a|2"), mapOf("a|1" to 0)))
    }

    @Test
    fun singleSelectedPhotoWithCopyIsReportedInSingular() {
        assertEquals(
            "其中 1 张还留有字节相同的副本",
            buildDuplicateHint(setOf("a|1"), mapOf("a|1" to 1))
        )
    }

    @Test
    fun multipleSelectedPhotosWithCopiesAreCounted() {
        assertEquals(
            "其中 3 张还留有字节相同的副本",
            buildDuplicateHint(
                setOf("a|1", "a|2", "a|3", "a|4"),
                mapOf("a|1" to 1, "a|2" to 2, "a|3" to 1, "a|4" to 0)
            )
        )
    }

    @Test
    fun onlySelectedKeysAreCounted() {
        // a|9 有副本但没被选中，不应算进提示里的数字
        assertEquals(
            "其中 1 张还留有字节相同的副本",
            buildDuplicateHint(setOf("a|1"), mapOf("a|1" to 1, "a|9" to 5))
        )
    }

    @Test
    fun zeroCountsAreTreatedAsNoCopy() {
        assertNull(buildDuplicateHint(setOf("a|1", "a|2"), mapOf("a|1" to 0, "a|2" to 0)))
    }
}
