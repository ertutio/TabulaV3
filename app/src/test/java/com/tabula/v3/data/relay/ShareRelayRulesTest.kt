package com.tabula.v3.data.relay

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 系统分享中转的判定规则测试（路线图第 2 项）。
 *
 * 对应路线图的两个重点：**临时 URI 授权** 与 **原图关联**。
 * - 授权：收到的 URI 无论能否定位，都必须出现在 forwardUris 里（授权随
 *   转发 Intent 一起交出去），不能因为"定位不到"就吞掉用户的东西。
 * - 关联：只有能解析出具体卷 + ID、且该身份确实在本地图库里的，才算 CONFIRMED；
 *   documents provider、聚合卷、临时缓存 URI 一律 PENDING_REVIEW。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ShareRelayRulesTest {

    private val mediaUri = "content://media/external_primary/images/media/42"
    private val otherVolumeUri = "content://media/1234-5678/images/media/42"
    private val documentsUri = "content://com.android.providers.media.documents/document/image%3A42"
    private val aggregateUri = "content://media/external/images/media/42"
    private val cacheUri = "content://com.android.shell/cache/screenshot_tmp.png"

    private fun item(
        uri: String,
        mime: String? = "image/png",
        name: String? = "screenshot.png"
    ) = RelayItem(uriString = uri, mimeType = mime, displayName = name)

    // ==================== 原图关联 ====================

    @Test
    fun mediaStoreUriThatExistsLocallyIsConfirmed() {
        val result = ShareRelayRules.resolve(
            items = listOf(item(mediaUri)),
            localIdentityKeys = setOf("external_primary|42"),
            nowMillis = 1_000L
        )

        assertEquals(1, result.confirmedCount)
        assertEquals(0, result.reviewCount)
        assertEquals(RelayMarkState.CONFIRMED, result.marks.single().state)
        assertEquals("external_primary|42", result.marks.single().identityKey)
        // 定位成功也要照常转发
        assertEquals(listOf(Uri.parse(mediaUri)), result.forwardUris)
    }

    @Test
    fun mediaStoreUriMissingFromLibraryIsPendingReviewButStillForwarded() {
        val result = ShareRelayRules.resolve(
            items = listOf(item(mediaUri)),
            // 图库里没有这张（例如刚截的图还没被 MediaStore 索引）
            localIdentityKeys = emptySet(),
            nowMillis = 1_000L
        )

        assertEquals(0, result.confirmedCount)
        assertEquals(1, result.reviewCount)
        assertEquals(RelayMarkState.PENDING_REVIEW, result.marks.single().state)
        assertNull(result.marks.single().identityKey)
        // 关键：定位失败不阻止转发
        assertTrue(result.hasForwardTargets)
        assertEquals(listOf(Uri.parse(mediaUri)), result.forwardUris)
    }

    @Test
    fun documentsProviderUriCannotBeResolvedToAConcreteVolume() {
        val result = ShareRelayRules.resolve(
            items = listOf(item(documentsUri)),
            localIdentityKeys = setOf("external_primary|42"),
            nowMillis = 1_000L
        )

        assertEquals(1, result.reviewCount)
        assertNull(result.marks.single().identityKey)
        assertTrue(result.hasForwardTargets)
    }

    @Test
    fun aggregateVolumeIsNotAnIdentity() {
        // content://media/external/... 的卷是聚合卷 "external"，不是真实卷
        val result = ShareRelayRules.resolve(
            items = listOf(item(aggregateUri)),
            localIdentityKeys = setOf("external|42", "external_primary|42"),
            nowMillis = 1_000L
        )

        assertEquals(0, result.confirmedCount)
        assertEquals(1, result.reviewCount)
    }

    @Test
    fun transientCacheUriIsPendingReview() {
        val result = ShareRelayRules.resolve(
            items = listOf(item(cacheUri, mime = "image/png")),
            localIdentityKeys = setOf("external_primary|42"),
            nowMillis = 1_000L
        )

        assertEquals(1, result.reviewCount)
        assertTrue(result.hasForwardTargets)
    }

    @Test
    fun sameNumericIdOnAnotherVolumeDoesNotConfirmTheWrongPhoto() {
        // 图库里只有 external_primary|42，分享来的是 1234-5678|42
        val result = ShareRelayRules.resolve(
            items = listOf(item(otherVolumeUri)),
            localIdentityKeys = setOf("external_primary|42"),
            nowMillis = 1_000L
        )

        assertEquals(0, result.confirmedCount)
        assertEquals(1, result.reviewCount)
    }

    // ==================== 拒收 ====================

    @Test
    fun nonImageMimeIsRejectedAndNotForwarded() {
        val result = ShareRelayRules.resolve(
            items = listOf(item("content://media/external_primary/video/media/7", mime = "video/mp4")),
            localIdentityKeys = emptySet(),
            nowMillis = 1_000L
        )

        assertTrue(result.marks.isEmpty())
        assertFalse(result.hasForwardTargets)
        assertEquals(1, result.rejections.size)
        assertTrue(result.rejections.single().startsWith("not an image"))
    }

    @Test
    fun missingMimeFallsBackToFileExtension() {
        val result = ShareRelayRules.resolve(
            items = listOf(item(cacheUri, mime = null, name = "Screenshot_2026.png")),
            localIdentityKeys = emptySet(),
            nowMillis = 1_000L
        )

        assertEquals(1, result.marks.size)
        assertTrue(result.hasForwardTargets)
    }

    @Test
    fun unknownExtensionWithoutMimeIsRejected() {
        val result = ShareRelayRules.resolve(
            items = listOf(item(cacheUri, mime = null, name = "download")),
            localIdentityKeys = emptySet(),
            nowMillis = 1_000L
        )

        assertTrue(result.marks.isEmpty())
        assertFalse(result.hasForwardTargets)
    }

    @Test
    fun nonContentSchemeIsRejected() {
        val result = ShareRelayRules.resolve(
            items = listOf(item("https://example.com/a.png")),
            localIdentityKeys = emptySet(),
            nowMillis = 1_000L
        )

        assertTrue(result.rejections.single().startsWith("unsupported scheme"))
        assertFalse(result.hasForwardTargets)
    }

    // ==================== 去重与混合 ====================

    @Test
    fun duplicateSharesOfTheSamePhotoProduceOneMarkAndOneForward() {
        val result = ShareRelayRules.resolve(
            items = listOf(item(mediaUri), item(mediaUri)),
            localIdentityKeys = setOf("external_primary|42"),
            nowMillis = 1_000L
        )

        assertEquals(1, result.marks.size)
        assertEquals(1, result.forwardUris.size)
    }

    @Test
    fun mixedBatchKeepsConfirmedAndReviewSeparate() {
        val result = ShareRelayRules.resolve(
            items = listOf(
                item(mediaUri),
                item(documentsUri),
                item("content://media/external_primary/video/media/9", mime = "video/mp4")
            ),
            localIdentityKeys = setOf("external_primary|42"),
            nowMillis = 1_000L
        )

        assertEquals(1, result.confirmedCount)
        assertEquals(1, result.reviewCount)
        assertEquals(1, result.rejections.size)
        // 只转发图片：两张图，视频被拒
        assertEquals(2, result.forwardUris.size)
    }

    @Test
    fun emptyShareProducesNothingToForward() {
        val result = ShareRelayRules.resolve(emptyList(), emptySet(), 1_000L)
        assertTrue(result.marks.isEmpty())
        assertFalse(result.hasForwardTargets)
    }

    @Test
    fun markedAtIsTheInjectedClock() {
        val result = ShareRelayRules.resolve(
            items = listOf(item(mediaUri)),
            localIdentityKeys = setOf("external_primary|42"),
            nowMillis = 1_234_567_890L
        )
        assertEquals(1_234_567_890L, result.marks.single().markedAt)
    }
}

/**
 * 中转标记的落库测试：重复分享不堆记录、跨实例仍在、可按身份或 URI 清除。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ShareRelayStoreTest {

    private class MemoryRelayStorage(var content: String? = null) : RelayStorage {
        override fun read(): String? = content
        override fun write(content: String) {
            this.content = content
        }
    }

    private fun mark(
        identity: String?,
        uri: String,
        state: RelayMarkState = RelayMarkState.CONFIRMED,
        at: Long = 1L
    ) = RelayMark(
        identityKey = identity,
        uriString = uri,
        displayName = "s.png",
        markedAt = at,
        state = state
    )

    @Test
    fun marksSurviveANewStoreInstance() {
        val storage = MemoryRelayStorage()
        ShareRelayStore(storage).merge(
            listOf(
                mark("external_primary|42", "content://media/external_primary/images/media/42"),
                mark(null, "content://com.android.shell/cache/tmp.png", RelayMarkState.PENDING_REVIEW)
            )
        )

        val reloaded = ShareRelayStore(storage).load()
        assertEquals(2, reloaded.size)
        assertEquals(1, reloaded.count { it.isConfirmed })
        assertEquals(1, reloaded.count { it.needsReview })
    }

    @Test
    fun sharingTheSamePhotoTwiceKeepsOneRecord() {
        val storage = MemoryRelayStorage()
        val store = ShareRelayStore(storage)
        val uri = "content://media/external_primary/images/media/42"

        store.merge(listOf(mark("external_primary|42", uri, at = 1L)))
        store.merge(listOf(mark("external_primary|42", uri, at = 2L)))

        val loaded = store.load()
        assertEquals(1, loaded.size)
        // 后写覆盖前写：时间戳是最新一次
        assertEquals(2L, loaded.single().markedAt)
    }

    @Test
    fun pendingReviewEntriesDedupeByUri() {
        val storage = MemoryRelayStorage()
        val store = ShareRelayStore(storage)
        val uri = "content://com.android.shell/cache/tmp.png"

        store.merge(listOf(mark(null, uri, RelayMarkState.PENDING_REVIEW)))
        store.merge(listOf(mark(null, uri, RelayMarkState.PENDING_REVIEW)))

        assertEquals(1, store.load().size)
    }

    @Test
    fun corruptStorageIsTreatedAsEmptyAndNotOverwritten() {
        val storage = MemoryRelayStorage(content = "{not valid json")
        val loaded = ShareRelayStore(storage).load()

        assertTrue(loaded.isEmpty())
        // 读取不覆写：用户数据原地保留，交给上层决定
        assertEquals("{not valid json", storage.content)
    }

    @Test
    fun removeByIdentityAndByUri() {
        val storage = MemoryRelayStorage()
        val store = ShareRelayStore(storage)
        val confirmed = "content://media/external_primary/images/media/42"
        val pending = "content://com.android.shell/cache/tmp.png"
        store.merge(
            listOf(
                mark("external_primary|42", confirmed),
                mark(null, pending, RelayMarkState.PENDING_REVIEW)
            )
        )

        store.remove(listOf("external_primary|42"))
        assertEquals(1, store.load().size)
        assertEquals(pending, store.load().single().uriString)

        store.removeByUri(listOf(pending))
        assertTrue(store.load().isEmpty())
    }
}

/**
 * Intent 解析测试：ACTION_SEND / SEND_MULTIPLE / ClipData 兜底。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ShareRelayIntentReaderTest {

    private val resolver: (Uri) -> String? = { uri ->
        if (uri.toString().contains("video")) "video/mp4" else "image/png"
    }

    @Test
    fun readsSingleSendExtraStream() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/external_primary/images/media/1"))
        }

        val items = ShareRelayIntentReader.read(intent, resolver)
        assertEquals(1, items.size)
        assertEquals("image/png", items.single().mimeType)
    }

    @Test
    fun readsMultipleSendExtraStream() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                arrayListOf(
                    Uri.parse("content://media/external_primary/images/media/1"),
                    Uri.parse("content://media/external_primary/images/media/2")
                )
            )
        }

        val items = ShareRelayIntentReader.read(intent, resolver)
        assertEquals(2, items.size)
    }

    @Test
    fun fallsBackToClipDataWhenExtraStreamIsAbsent() {
        val uri = Uri.parse("content://com.android.shell/cache/shot.png")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            clipData = android.content.ClipData.newRawUri("shot.png", uri)
        }

        val items = ShareRelayIntentReader.read(intent, resolver)
        assertEquals(1, items.size)
        assertEquals(uri.toString(), items.single().uriString)
    }

    @Test
    fun prefersExtraStreamOverClipDataToAvoidDoubleCounting() {
        val uri = Uri.parse("content://media/external_primary/images/media/1")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("same", uri)
        }

        assertEquals(1, ShareRelayIntentReader.read(intent, resolver).size)
    }

    @Test
    fun ignoresUnrelatedActions() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://media/external_primary/images/media/1")
        }
        assertTrue(ShareRelayIntentReader.read(intent, resolver).isEmpty())
        assertTrue(ShareRelayIntentReader.read(null, resolver).isEmpty())
    }
}
