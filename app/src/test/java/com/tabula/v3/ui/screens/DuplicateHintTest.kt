package com.tabula.v3.ui.screens

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.mediaIdentityKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回收站副本提示的测试（路线图 #83 第 5 项 / 审计 #88）。
 *
 * 审计要求的核心口径：**提示必须排除本次选中的照片**。
 * 如果两份内容相同的照片都被选中，"还剩一份"不成立，就不能提示。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DuplicateHintTest {

    private fun photo(id: Long, volume: String = "external_primary") = ImageFile(
        id = id,
        uri = Uri.parse("content://media/$volume/images/media/$id"),
        displayName = "p$id.jpg",
        dateModified = 1_700_000_000_000L,
        size = 1000L,
        width = 1080,
        height = 1440,
        bucketDisplayName = "Camera",
        volumeName = volume
    )

    private fun keyOf(id: Long, volume: String = "external_primary") = "$volume|$id"

    @Test
    fun noSelectionMeansNoHint() {
        assertNull(buildDuplicateHint(emptySet(), mapOf(keyOf(1) to listOf(photo(2)))))
    }

    @Test
    fun noKnownCopiesMeansNoHint() {
        assertNull(buildDuplicateHint(setOf(keyOf(1), keyOf(2)), emptyMap()))
    }

    @Test
    fun singleSelectedPhotoWithSurvivingCopyIsReported() {
        assertEquals(
            "其中 1 张还留有字节相同的副本",
            buildDuplicateHint(setOf(keyOf(1)), mapOf(keyOf(1) to listOf(photo(2))))
        )
    }

    @Test
    fun copyThatIsAlsoSelectedDoesNotCount() {
        // 审计核心场景：两份都被选中 → 删了就一份不剩 → 不能提示
        assertNull(
            buildDuplicateHint(
                selectedIdentityKeys = setOf(keyOf(1), keyOf(2)),
                duplicateDetails = mapOf(
                    keyOf(1) to listOf(photo(2)),
                    keyOf(2) to listOf(photo(1))
                )
            )
        )
    }

    @Test
    fun mixedSelectionCountsOnlySurvivingCopies() {
        // 1 的副本是 2（也选中，不算）；3 的副本是 4（未选中，算）
        assertEquals(
            "其中 1 张还留有字节相同的副本",
            buildDuplicateHint(
                selectedIdentityKeys = setOf(keyOf(1), keyOf(2), keyOf(3)),
                duplicateDetails = mapOf(
                    keyOf(1) to listOf(photo(2)),
                    keyOf(3) to listOf(photo(4))
                )
            )
        )
    }

    @Test
    fun multipleSurvivingCopiesAreCounted() {
        assertEquals(
            "其中 3 张还留有字节相同的副本",
            buildDuplicateHint(
                selectedIdentityKeys = setOf(keyOf(1), keyOf(2), keyOf(3)),
                duplicateDetails = mapOf(
                    keyOf(1) to listOf(photo(10)),
                    keyOf(2) to listOf(photo(11)),
                    keyOf(3) to listOf(photo(12))
                )
            )
        )
    }

    @Test
    fun onlySelectedKeysAreCounted() {
        assertEquals(
            "其中 1 张还留有字节相同的副本",
            buildDuplicateHint(
                selectedIdentityKeys = setOf(keyOf(1)),
                duplicateDetails = mapOf(
                    keyOf(1) to listOf(photo(2)),
                    keyOf(9) to listOf(photo(5), photo(6))
                )
            )
        )
    }

    @Test
    fun emptyCopyListMeansNoHint() {
        assertNull(buildDuplicateHint(setOf(keyOf(1)), mapOf(keyOf(1) to emptyList())))
    }

    @Test
    fun crossVolumeCopyIsNotTheSamePhoto() {
        // 副本在另一卷、同数值 ID：身份不同，是真实副本
        assertEquals(
            "其中 1 张还留有字节相同的副本",
            buildDuplicateHint(
                selectedIdentityKeys = setOf(keyOf(1)),
                duplicateDetails = mapOf(keyOf(1) to listOf(photo(1, volume = "1234-5678")))
            )
        )
    }
}
