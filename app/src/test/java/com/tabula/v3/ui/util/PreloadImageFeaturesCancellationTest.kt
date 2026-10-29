package com.tabula.v3.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.tabula.v3.data.cache.ImageHashCache
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.ImageFeatureDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PreloadImageFeaturesCancellationTest {
    private val app: android.app.Application get() = RuntimeEnvironment.getApplication()
    private val context: Context get() = app
    private val uri = Uri.parse("content://media/external/images/media/10")
    private val image = ImageFile(
        id = 10L,
        uri = uri,
        displayName = "card.jpg",
        dateModified = 1000L,
        size = 4096L,
        width = 64,
        height = 64,
        bucketDisplayName = "Camera"
    )
    // 对照用例走 motion 检测路径：其阴性不依赖 ImageDecoder（Robolectric 下 HDR
    // 重型检测抛异常 → 正确地产生不可信阴性），可稳定得到 definitive 结果。
    private val cacheKey = ImageFeatureCacheKey(10L, 1000L, enableHdr = false, enableMotion = true)
    private lateinit var jpegFile: File
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        ImageFeatureCacheStore.clear()
        ImageHashCache.getInstance(context).clearAll()
        jpegFile = File(context.cacheDir, "feature-card.jpg")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)
        jpegFile.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        bitmap.recycle()
    }

    @After
    fun tearDown() {
        ImageFeatureCacheStore.clear()
        scopes.forEach { it.cancel() }
    }

    /** 首次 read() 阻塞到 released，随后抛 IOException，模拟取消时检测仍在途中。 */
    private class BlockingStream(private val released: CountDownLatch) : InputStream() {
        override fun read(): Int {
            released.await(10, TimeUnit.SECONDS)
            throw IOException("test stream aborted")
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            released.await(10, TimeUnit.SECONDS)
            throw IOException("test stream aborted")
        }
    }

    private fun registerStreams(primary: Supplier<InputStream?>, fallback: Supplier<InputStream?>? = null) {
        val used = arrayOf(false)
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri, Supplier {
            if (!used[0]) { used[0] = true; primary.get() } else fallback?.get()
        })
    }

    @Test
    fun cancelledPreloadDoesNotCache() {
        val opened = CountDownLatch(1)
        val released = CountDownLatch(1)
        registerStreams(Supplier<InputStream?> {
            opened.countDown()
            BlockingStream(released)
        })
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes += it }
        val job = scope.launch {
            preloadImageFeatures(context, image, enableHdr = false, enableMotion = true)
        }
        assertTrue("detect must reach the stream before cancellation", opened.await(5, TimeUnit.SECONDS))
        job.cancel()
        released.countDown()
        runBlocking { withTimeout(15_000) { job.join() } }
        // 取消中的检测结果不得入缓存（T11a：取消不落缓存，卡片重入时才有机会重试）。
        assertNull("cancelled detection must not be cached", ImageFeatureCacheStore.get(cacheKey))
    }

    @Test
    fun completedPreloadCachesDefinitiveResult() {
        // 正常完成（未取消）：真实 JPEG 解码出确定性阴性，才允许入缓存。
        ImageFeatureDetector.setFastMode(false)
        registerStreams(Supplier { FileInputStream(jpegFile) })
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes += it }
        scope.launch {
            preloadImageFeatures(context, image, enableHdr = false, enableMotion = true)
        }
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && ImageFeatureCacheStore.get(cacheKey) == null) {
            Thread.sleep(50)
        }
        val cached = ImageFeatureCacheStore.get(cacheKey)
        assertNotNull("uncancelled definitive result must be cached", cached)
    }
}
