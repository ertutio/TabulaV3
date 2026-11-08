package com.tabula.v3.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.ImageFeatureDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * T11b 测量入口（TC48–TC51 前置，无生产改动）：量化 ImageFeatureCacheStore
 * 当前"无 single-flight、无界保留"的两个基线事实，供 T11b 实施前后对比：
 *
 * 1. 同键并发重复检测：N 个并发 preloadImageFeatures 同 (imageId, 版本, 开关)
 *    ——屏障夹具要求 N 个调用方全部到达检测流才放行；当前实现无 single-flight，
 *    N 个调用方各自运行 detect（arrivals>=N 可确定性判定）。若未来引入
 *    single-flight，本测试将按新行为改写（先跑单飞计数再对比）——本文件是
 *    基线测量，不是终态规格。
 * 2. 无界保留：N 个不同键 putIfReliable 后全部可取（无淘汰）——有界缓存的
 *    必要性证据。
 * 3. 不可信阴性重试：HDR 重型检测在 Robolectric 下失败 → definitive=false
 *    不入缓存 → 第二次调用再次运行 detect（流打开计数增长）。
 *
 * 流打开次数 = 检测工作的可观察代理（readXmp 对 checkHdr||checkMotion 无条件
 * 打开一次流；后续读取按需追加）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ImageFeatureCacheConcurrencyTest {
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
    private val cacheKey = ImageFeatureCacheKey(10L, 1000L, enableHdr = false, enableMotion = true)
    private lateinit var jpegFile: File
    private val scopes = mutableListOf<CoroutineScope>()
    private val openCounter = AtomicInteger(0)

    @Before
    fun setUp() {
        ImageFeatureCacheStore.clear()
        jpegFile = File(context.cacheDir, "feature-concurrency-card.jpg")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)
        jpegFile.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        bitmap.recycle()
    }

    @After
    fun tearDown() {
        ImageFeatureCacheStore.clear()
        ImageFeatureDetector.setFastMode(false)
        scopes.forEach { it.cancel() }
    }

    /** 计数每次流打开；barrierLimit>0 时前 barrierLimit 次打开返回阻塞流（联动 barrier），其余返回真实流。 */
    private fun registerCountingStreams(
        barrierLimit: Int = 0,
        barrier: CountDownLatch? = null,
        blocked: CountDownLatch? = null
    ) {
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri, Supplier<InputStream?> {
            openCounter.incrementAndGet()
            if (barrierLimit > 0 && openCounter.get() <= barrierLimit) {
                barrier?.countDown()
                blocked?.await(15, TimeUnit.SECONDS)
                throw java.io.IOException("barrier stream aborted")
            }
            FileInputStream(jpegFile)
        })
    }

    @Test
    fun concurrentSameKeyPreloadsShareSingleDetectionFlight() = runBlocking {
        // T11b single-flight（PR56 基线的演化点）：N 个并发同键 preload 只运行
        // 一次检测——所有调用方共享同一次流打开；结果照常入缓存。
        val callers = 8
        registerCountingStreams()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        val jobs = (1..callers).map {
            scope.async { preloadImageFeatures(context, image, enableHdr = false, enableMotion = true) }
        }
        jobs.forEach { runBlocking { kotlinx.coroutines.withTimeout(20_000) { it.await() } } }
        assertTrue(
            "single-flight must collapse concurrent same-key detections to one stream open, got ${openCounter.get()}",
            openCounter.get() == 1
        )
        assertNotNull("result must be cached after the shared flight", ImageFeatureCacheStore.get(cacheKey))
        println("T11B-SINGLE-FLIGHT callers=$callers totalStreamOpens=${openCounter.get()}")
    }

    @Test
    fun waiterRerunsDetectionWhenInitiatorIsCancelled() = runBlocking {
        // 发起者被取消（T11a：取消不落缓存）→ 在途条目移除 → 等待者按失败
        // 重试语义自行重跑：总流打开次数 >= 2，等待者的结果入缓存。
        // 注意：流 IOException 会被吞成 definitive 阴性（T11a 语义，非失败），
        // 所以发起侧用取消而非 IO 异常构造"检测未完成"。
        val initiatorArrived = CountDownLatch(1)
        val releaseStream = CountDownLatch(1)
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri, Supplier<InputStream?> {
            openCounter.incrementAndGet()
            if (openCounter.get() == 1) {
                initiatorArrived.countDown()
                releaseStream.await(15, TimeUnit.SECONDS)
                throw java.io.IOException("stream aborted after cancel")
            }
            FileInputStream(jpegFile)
        })
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        val initiator = scope.launch {
            preloadImageFeatures(context, image, enableHdr = false, enableMotion = true)
        }
        assertTrue(initiatorArrived.await(10, TimeUnit.SECONDS))
        val waiter = scope.async {
            preloadImageFeatures(context, image, enableHdr = false, enableMotion = true)
        }
        // 给等待者时间进入在途等待（避免它晚于发起者落定才启动而直接自跑）
        Thread.sleep(500)
        initiator.cancel()
        releaseStream.countDown()
        runBlocking { kotlinx.coroutines.withTimeout(20_000) { waiter.await() } }
        kotlinx.coroutines.delay(200)
        assertTrue(
            "cancelled initiator must let the waiter rerun detection, opens=${openCounter.get()}",
            openCounter.get() >= 2
        )
        assertNotNull("waiter's own detection must cache the result", ImageFeatureCacheStore.get(cacheKey))
        println("T11B-WAITER-RETRY opens=${openCounter.get()}")
    }

    @Test
    fun cacheEvictsLeastRecentlyUsedBeyondBound() {
        // 有界缓存：写入 MAX_ENTRIES+100 个可靠条目后，最早写入者被淘汰、
        // 最新保留——2000+ 键无界保留（PR56 基线）不再成立。
        val overflow = 100
        repeat(ImageFeatureCacheStore.MAX_ENTRIES + overflow) { i ->
            ImageFeatureCacheStore.putIfReliable(
                ImageFeatureCacheKey(i.toLong(), 1000L + i, enableHdr = true, enableMotion = false),
                com.tabula.v3.data.model.ImageFeatures(isHdr = false, motionPhotoInfo = null, definitive = true)
            )
        }
        val eldest = ImageFeatureCacheStore.get(ImageFeatureCacheKey(0L, 1000L, true, false))
        val newest = ImageFeatureCacheStore.get(
            ImageFeatureCacheKey(
                (ImageFeatureCacheStore.MAX_ENTRIES + overflow - 1).toLong(),
                1000L + (ImageFeatureCacheStore.MAX_ENTRIES + overflow - 1),
                true, false
            )
        )
        assertNull("eldest entry must be evicted beyond the bound", eldest)
        assertNotNull("newest entry must be retained", newest)
        println("T11B-BOUNDED bound=${ImageFeatureCacheStore.MAX_ENTRIES} evictedEldest=${eldest == null}")
    }

    @Test
    fun singlePreloadStreamOpenBaseline() = runBlocking {
        registerCountingStreams()
        ImageFeatureDetector.setFastMode(false)
        preloadImageFeatures(context, image, enableHdr = false, enableMotion = true)
        val opens = openCounter.get()
        assertTrue("single detect must open its XMP stream", opens >= 1)
        assertNotNull(ImageFeatureCacheStore.get(cacheKey))
        println("T11B-SINGLE-PRELOAD streamOpens=$opens")
    }

    @Test
    fun untrustedNegativeIsRetriedOnNextCall() = runBlocking {
        // HDR 重型检测在 Robolectric 下失败 → definitive=false 不入缓存 → 重试
        registerCountingStreams()
        ImageFeatureDetector.setFastMode(false)
        preloadImageFeatures(context, image, enableHdr = true, enableMotion = false)
        val opensAfterFirst = openCounter.get()
        val hdrKey = ImageFeatureCacheKey(10L, 1000L, enableHdr = true, enableMotion = false)
        kotlinx.coroutines.delay(100)
        preloadImageFeatures(context, image, enableHdr = true, enableMotion = false)
        val opensAfterSecond = openCounter.get()
        assertTrue("untrusted negative must not be cached", ImageFeatureCacheStore.get(hdrKey) == null)
        assertTrue(
            "second call must re-run detection (opens $opensAfterFirst -> $opensAfterSecond)",
            opensAfterSecond > opensAfterFirst
        )
    }
}
