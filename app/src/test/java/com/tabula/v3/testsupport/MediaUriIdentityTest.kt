package com.tabula.v3.testsupport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 `MediaUriIdentity` 的归一规则。
 *
 * 规则来自 run 45372299838 的真实失败：夹具写 `external`，生产代码按
 * `VOLUME_NAME` 重建 `external_primary`，直接比字符串永远不等。
 */
class MediaUriIdentityTest {

    private val aggregate = "content://media/external/images/media/84"
    private val primary = "content://media/external_primary/images/media/84"

    @Test
    fun aggregateExternalAndPrimaryExternalAreTheSameRow() {
        // 单外部卷设备上这就是同一行；断言不能因为它们写法不同就判失败。
        assertEquals(MediaUriIdentity.canonical(aggregate), MediaUriIdentity.canonical(primary))
        assertEquals("external_primary#84", MediaUriIdentity.canonical(aggregate))
    }

    @Test
    fun differentRowIdsStayDifferent() {
        assertNotEquals(
            MediaUriIdentity.canonical(primary),
            MediaUriIdentity.canonical("content://media/external_primary/images/media/85")
        )
    }

    @Test
    fun differentVolumesWithTheSameIdStayDifferent() {
        // T03 的跨卷身份规则：同数值 id 不同卷是不同照片，归一不能把它们抹平。
        val sdCard = "content://media/1a2b-3c4d/images/media/84"
        assertNotEquals(MediaUriIdentity.canonical(primary), MediaUriIdentity.canonical(sdCard))
        assertEquals("1a2b-3c4d#84", MediaUriIdentity.canonical(sdCard))
    }

    @Test
    fun nonImageUrisAreNotSilentlyMatched() {
        assertNull(MediaUriIdentity.canonical("content://media/external/video/media/84"))
        assertNull(MediaUriIdentity.canonical("file:///sdcard/Pictures/ws-1234.jpg"))
        assertNull(MediaUriIdentity.canonical(""))
    }

    @Test
    fun canonicalSetDropsUnparsableEntriesButReportsThem() {
        val uris = listOf(aggregate, "file:///nope.jpg", primary)
        assertEquals(setOf("external_primary#84"), MediaUriIdentity.canonicalSet(uris))
        assertEquals(listOf("file:///nope.jpg"), MediaUriIdentity.unparsable(uris))
        assertTrue(MediaUriIdentity.unparsable(listOf(aggregate)).isEmpty())
    }
}
