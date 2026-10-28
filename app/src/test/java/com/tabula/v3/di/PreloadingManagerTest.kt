package com.tabula.v3.di

import android.content.Context
import androidx.compose.ui.unit.IntSize
import coil.ImageLoader
import coil.request.Disposable
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.tabula.v3.data.model.ImageFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            setOf("card_1_1000", "card_2_1000", "card_3_1000"),
            fakeLoader.enqueued.map { it.memoryCacheKey!!.key }.toSet()
        )
        Unit
    }

    @Test
    fun sameIndexWithDifferentContentIsNotSkipped() = runBlocking {
        manager.preloadAround(images(3), 1, cardSize)
        awaitEnqueued(3)
        manager.preloadAround(listOf(image(9L), image(10L), image(11L)), 1, cardSize)
        awaitEnqueued(5)

        val keys = fakeLoader.enqueued.map { it.memoryCacheKey!!.key }
        assertTrue("card_9_1000" in keys)
        assertTrue("card_10_1000" in keys)
        assertTrue("card_11_1000" in keys)
        Unit
    }

    @Test
    fun failedPreloadIsRetriedOnLaterPreloadAround() = runBlocking {
        manager.preloadAround(images(5), 1, cardSize)
        awaitEnqueued(3)
        // 模拟该批结果为失败（生产路径由 Coil listener 调用 handlePreloadResult）
        fakeLoader.enqueued.forEach { manager.handlePreloadResult(it.memoryCacheKey!!.key, successful = false) }

        manager.preloadAround(images(5), 3, cardSize) // 中心移动：范围 2..4
        awaitEnqueued(6)

        assertEquals(6, fakeLoader.enqueued.size)
        Unit
    }

    @Test
    fun successfulPreloadIsNotRepeatedForSameContent() = runBlocking {
        manager.preloadAround(images(3), 1, cardSize)
        awaitEnqueued(3)
        fakeLoader.enqueued.forEach { manager.handlePreloadResult(it.memoryCacheKey!!.key, successful = true) }

        manager.preloadAround(images(3), 1, cardSize)
        Thread.sleep(300) // 防抖窗口过后不应有新请求

        assertEquals(3, fakeLoader.enqueued.size)
        Unit
    }

    @Test
    fun pauseAllowsSameCenterRetryAfterResume() = runBlocking {
        manager.preloadAround(images(3), 1, cardSize)
        awaitEnqueued(3)

        manager.pause()
        manager.preloadAround(images(3), 1, cardSize) // 暂停中：防抖任务取消，不排队
        manager.resume()
        manager.preloadAround(images(3), 1, cardSize) // 同中心可重试（T06b）
        awaitEnqueued(6)

        assertEquals(6, fakeLoader.enqueued.size)
        Unit
    }

    @Test
    fun outOfRangeDisposablesAreCancelledOnNewBatch() = runBlocking {
        manager.preloadAround(images(5), 2, cardSize) // 排队 1..3，保留范围 0..4
        awaitEnqueued(3)
        val firstBatch = fakeLoader.disposables.toList()

        manager.preloadAround(images(9), 6, cardSize) // 排队 5..7，保留范围 4..8
        awaitEnqueued(6)

        assertTrue("stale disposables must be disposed", firstBatch.all { it.isDisposed })
        Unit
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
    val enqueued = ConcurrentLinkedQueue<ImageRequest>()
    val disposables = ConcurrentLinkedQueue<FakeDisposable>()

    override fun enqueue(request: ImageRequest): Disposable {
        enqueued += request
        val disposable = FakeDisposable()
        disposables += disposable
        return disposable
    }
}
