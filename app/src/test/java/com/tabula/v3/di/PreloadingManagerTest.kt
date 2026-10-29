package com.tabula.v3.di

import android.content.Context
import androidx.compose.ui.unit.IntSize
import coil.ImageLoader
import coil.request.Disposable
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.decode.DataSource
import android.graphics.drawable.ColorDrawable
import com.tabula.v3.data.model.ImageFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PreloadingManagerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var fakeLoader: FakeImageLoader
    private lateinit var manager: PreloadingManager
    private val cardSize = IntSize(1080, 1440)

    @Before
    fun setUp() {
        fakeLoader = FakeImageLoader(context)
        manager = PreloadingManager(context, preloadRange = 1, providedLoader = fakeLoader)
    }

    @After
    fun tearDown() {
        manager.dispose()
        fakeLoader.shutdown()
    }

    private fun image(id: Long, dateModified: Long = 1000L) = ImageFile(
        id = id,
        uri = android.net.Uri.parse("content://media/external/images/media/$id"),
        displayName = "img$id",
        dateModified = dateModified,
        size = 1L,
        width = 32,
        height = 32,
        bucketDisplayName = "Camera"
    )

    private fun images(count: Int) = (1..count).map { image(it.toLong()) }

    /** preloadAround 在自有 IO scope 里防抖执行：轮询直到入队数量稳定。 */
    private fun awaitEnqueued(minCount: Int) {
        val end = System.currentTimeMillis() + 5000
        var last = -1
        while (System.currentTimeMillis() < end) {
            if (fakeLoader.enqueued.size >= minCount && fakeLoader.enqueued.size == last) return
            last = fakeLoader.enqueued.size
            Thread.sleep(50)
        }
        assertEquals("expected at least $minCount enqueued requests", minCount, fakeLoader.enqueued.size)
    }

    @Test
    fun requestKeyIncludesContentVersion() = runBlocking {
        manager.preloadAround(images(3), currentIndex = 1, cardSize = cardSize)
        awaitEnqueued(3)

        assertEquals(
            images(3).map(::cardMemoryCacheKey).toSet(),
            fakeLoader.enqueued.map { it.memoryCacheKey!!.key }.toSet()
        )
        Unit
    }

    @Test
    fun sameIndexWithDifferentContentIsNotSkipped() = runBlocking {
        manager.preloadAround(images(3), 1, cardSize)
        awaitEnqueued(3)
        manager.preloadAround(listOf(image(9L), image(10L), image(11L)), 1, cardSize)
        awaitEnqueued(6)

        val keys = fakeLoader.enqueued.map { it.memoryCacheKey!!.key }
        assertTrue(cardMemoryCacheKey(image(9L)) in keys)
        assertTrue(cardMemoryCacheKey(image(10L)) in keys)
        assertTrue(cardMemoryCacheKey(image(11L)) in keys)
        Unit
    }

    @Test
    fun failedPreloadIsRetriedOnLaterPreloadAround() = runBlocking {
        manager.preloadAround(images(5), 1, cardSize)
        awaitEnqueued(3)
        // 模拟 Coil listener 的失败回调。
        fakeLoader.enqueued.forEach { fakeLoader.fail(it) }

        manager.preloadAround(images(5), 3, cardSize) // 中心移动：范围 2..4
        awaitEnqueued(6)

        assertEquals(6, fakeLoader.enqueued.size)
        Unit
    }

    @Test
    fun successfulPreloadIsNotRepeatedForSameContent() = runBlocking {
        manager.preloadAround(images(3), 1, cardSize)
        awaitEnqueued(3)
        fakeLoader.enqueued.forEach { fakeLoader.succeed(it) }

        manager.preloadAround(images(3), 1, cardSize)
        Thread.sleep(300) // 防抖窗口过后不应有新请求

        assertEquals(3, fakeLoader.enqueued.size)
        Unit
    }

    @Test
    fun pauseAllowsSameCenterRetryAfterResume() {
        manager.preloadAround(images(3), 1, cardSize)
        awaitEnqueued(3)
        fakeLoader.enqueued.toList().forEach { fakeLoader.fail(it) }
        manager.pause()
        manager.preloadAround(images(3), 1, cardSize)
        Thread.sleep(250)
        assertEquals(3, fakeLoader.enqueued.size)
        manager.resume()
        manager.preloadAround(images(3), 1, cardSize)
        awaitEnqueued(6)
        assertEquals(6, fakeLoader.enqueued.size)
    }

    @Test
    fun resetIgnoresLateCallbackWhenSameMediaIsReentered() {
        manager.preloadAround(images(1), 0, cardSize)
        awaitEnqueued(1)
        val old = fakeLoader.enqueued.first()
        manager.reset()
        manager.preloadAround(images(1), 0, cardSize)
        awaitEnqueued(2)
        val current = fakeLoader.enqueued.last()
        fakeLoader.succeed(old)
        fakeLoader.fail(current)
        manager.preloadAround(images(1), 0, cardSize)
        awaitEnqueued(3)
    }

    @Test
    fun disposeIgnoresLateCallbackAndPreventsReentry() {
        manager.preloadAround(images(1), 0, cardSize)
        awaitEnqueued(1)
        val old = fakeLoader.enqueued.first()
        manager.dispose()
        fakeLoader.succeed(old)
        manager.preloadAround(images(1), 0, cardSize)
        Thread.sleep(250)
        assertEquals(1, fakeLoader.enqueued.size)
        assertTrue(fakeLoader.disposables.first().isDisposed)
    }

    @Test
    fun outOfRangeDisposablesAreCancelledOnNewBatch() = runBlocking {
        manager.preloadAround(images(5), 2, cardSize) // 排队并保留窗口 1..3
        awaitEnqueued(3)
        val firstBatch = fakeLoader.disposables.toList()

        manager.preloadAround(images(9), 6, cardSize) // 排队并保留窗口 5..7
        awaitEnqueued(6)

        assertTrue("stale disposables must be disposed", firstBatch.all { it.isDisposed })
        Unit
    }

    @Test
    fun sameCenterDetectsChangedNeighborAndVersion() {
        manager.preloadAround(images(3), 1, cardSize)
        awaitEnqueued(3)
        manager.preloadAround(listOf(image(9), image(2, 2000), image(3)), 1, cardSize)
        awaitEnqueued(5)
        val keys = fakeLoader.enqueued.map { it.memoryCacheKey!!.key }
        assertTrue(cardMemoryCacheKey(image(9)) in keys)
        assertTrue(cardMemoryCacheKey(image(2, 2000)) in keys)
    }

    @Test
    fun sameIdDifferentUriOrDecodeSizeIsNewRequest() {
        val first = image(1)
        manager.preloadAround(listOf(first), 0, cardSize)
        awaitEnqueued(1)
        fakeLoader.succeed(fakeLoader.enqueued.last())
        val otherUri = first.copy(uri = android.net.Uri.parse("content://media/other/images/media/1"))
        manager.preloadAround(listOf(otherUri), 0, cardSize)
        awaitEnqueued(2)
        fakeLoader.succeed(fakeLoader.enqueued.last())
        manager.preloadAround(listOf(otherUri.copy(width = 16)), 0, cardSize)
        awaitEnqueued(3)
        assertEquals(3, fakeLoader.enqueued.size)
        assertTrue(cardMemoryCacheKey(first) != cardMemoryCacheKey(otherUri))
    }

    @Test
    fun failureRetriesWithoutMovingCenter() {
        manager.preloadAround(images(3), 1, cardSize)
        awaitEnqueued(3)
        val failed = fakeLoader.enqueued.first()
        fakeLoader.fail(failed)
        manager.preloadAround(images(3), 1, cardSize)
        awaitEnqueued(4)
        assertEquals(2, fakeLoader.enqueued.count { it.memoryCacheKey == failed.memoryCacheKey })
    }

    @Test
    fun successEvictedOutsideWindowAndLateCallbackIgnored() {
        manager.preloadAround(images(9), 1, cardSize)
        awaitEnqueued(3)
        val first = fakeLoader.enqueued.first()
        fakeLoader.succeed(first)
        val stale = fakeLoader.enqueued.elementAt(1)
        manager.preloadAround(images(9), 6, cardSize)
        awaitEnqueued(6)
        fakeLoader.succeed(stale)
        manager.preloadAround(images(9), 1, cardSize)
        awaitEnqueued(9)
        assertEquals(2, fakeLoader.enqueued.count { it.memoryCacheKey == first.memoryCacheKey })
        assertEquals(2, fakeLoader.enqueued.count { it.memoryCacheKey == stale.memoryCacheKey })
    }

    @Test
    fun synchronousCompletionDuringEnqueueIsRecorded() {
        fakeLoader.synchronousSuccess = true
        manager.preloadAround(images(1), 0, cardSize)
        awaitEnqueued(1)
        manager.preloadAround(images(1), 0, cardSize)
        Thread.sleep(300)
        assertEquals(1, fakeLoader.enqueued.size)
        assertTrue(fakeLoader.disposables.first().isDisposed)
    }

    @Test
    fun oldCallbackCannotCompleteReplacementAtSameIndex() {
        manager.preloadAround(listOf(image(1)), 0, cardSize)
        awaitEnqueued(1)
        val old = fakeLoader.enqueued.first()
        manager.preloadAround(listOf(image(2)), 0, cardSize)
        awaitEnqueued(2)
        val replacement = fakeLoader.enqueued.last()
        assertTrue(fakeLoader.disposables.first().isDisposed)
        fakeLoader.succeed(old)
        fakeLoader.fail(replacement)
        manager.preloadAround(listOf(image(2)), 0, cardSize)
        awaitEnqueued(3)
        assertEquals(2, fakeLoader.enqueued.count { it.memoryCacheKey == replacement.memoryCacheKey })
    }

    @Test
    fun manyWindowsDoNotPermanentlyRememberOldSuccesses() {
        val many = images(30)
        manager.preloadAround(many, 1, cardSize)
        awaitEnqueued(3)
        fakeLoader.enqueued.toList().forEach { fakeLoader.succeed(it) }
        for (center in listOf(5, 9, 13, 17, 21, 25)) {
            val oldCount = fakeLoader.enqueued.size
            manager.preloadAround(many, center, cardSize)
            awaitEnqueued(oldCount + 3)
            fakeLoader.enqueued.toList().drop(oldCount).forEach { fakeLoader.succeed(it) }
        }
        val oldCount = fakeLoader.enqueued.size
        manager.preloadAround(many, 1, cardSize)
        awaitEnqueued(oldCount + 3)
        assertEquals(2, fakeLoader.enqueued.count { it.memoryCacheKey!!.key == cardMemoryCacheKey(many[0]) })
    }

    @Test
    fun disposeCancelsAllRequests() = runBlocking {
        manager.preloadAround(images(3), 1, cardSize)
        awaitEnqueued(3)

        manager.dispose()

        assertTrue(fakeLoader.disposables.isNotEmpty())
        assertTrue(fakeLoader.disposables.all { it.isDisposed })
        Unit
    }
}

private class FakeDisposable : Disposable {
    @Volatile var disposed = false
    override fun dispose() { disposed = true }
    override val isDisposed: Boolean get() = disposed
    override val job: kotlinx.coroutines.Deferred<coil.request.ImageResult> =
        kotlinx.coroutines.CompletableDeferred()
}

/** 捕获 enqueue 的假 loader：其余行为委托真实实现（Robolectric 下不解码真实像素）。 */
private class FakeImageLoader(context: Context) : ImageLoader by ImageLoader.Builder(context).build() {
    @Volatile var synchronousSuccess = false
    val enqueued = ConcurrentLinkedQueue<ImageRequest>()
    val disposables = ConcurrentLinkedQueue<FakeDisposable>()

    fun fail(request: ImageRequest) {
        request.listener?.onError(request, ErrorResult(null, request, IllegalStateException("test")))
    }

    fun succeed(request: ImageRequest) {
        request.listener?.onSuccess(request, SuccessResult(ColorDrawable(), request, DataSource.MEMORY_CACHE, null, null, false, false))
    }

    override fun enqueue(request: ImageRequest): Disposable {
        enqueued += request
        val disposable = FakeDisposable()
        disposables += disposable
        if (synchronousSuccess) succeed(request)
        return disposable
    }
}
