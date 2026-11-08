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
 * T11b 行为规格测试（源自 PR56 基线测量，TC48–TC51 已按 single-flight/有界
 * 语义改写；PR60 收尾补充两修复的确定性回归）：
 *
 * 1. single-flight：同键并发 preloadImageFeatures 只运行一次检测——所有调用方
 *    共享同一次流打开，结果照常入缓存。
 * 2. 有界缓存：写入 MAX_ENTRIES+100 个可靠条目后，最早写入者被淘汰、最新保留。
 * 3. 不可信阴性重试（单人路径）：HDR 重型检测在 Robolectric 下失败 →
 *    definitive=false 不入缓存 → 第二次调用再次运行 detect（流打开计数增长）。
 * 4. PR60 修复一：等待者共享在途轮次结果（含不可信阴性）——同键 3 并发只有
 *    一轮检测的流打开；缓存仍拒绝该不可信阴性；随后新一轮调用重跑检测。
 * 5. PR60 修复二：所有权后复查缓存——B 的初始 get 发生在 A 入库前、B 领取
 *    所有权发生在 A 完成入库后 → B 经复查直接命中缓存，不再重跑检测
 *    （preOwnershipGateForTest 可控注入把 B 拦在初始 get 与 putIfAbsent 之间）。
 * 6. 失败/取消语义保持：发起者取消（不产结果）→ 等待者按失败重试语义重跑；
 *    等待者自身取消向上传播。
 *
 * 流打开次数 = 检测工作的可观察代理（readXmp 对 checkHdr||checkMotion 无条件
 * 打开一次流；后续读取按需追加）。fastMode+checkHdr 路径每轮检测固定开 2 次
 * 流（readXmp + EXIF 提示读取），测试用"先标定单人轮次再断言并发轮次"的方式
 * 保持对每轮打开数的鲁棒性。
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
        ImageFeatureCacheStore.preOwnershipGateForTest = null
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

    @Test
    fun waitersShareUntrustedNegativeFromSameFlight() = runBlocking {
        // PR60 修复一：同键 3 并发时等待者直接共享在途轮次结果（含不可信阴性），
        // 不再各自重跑；不可信阴性照旧不入可靠缓存；随后新一轮调用重跑检测。
        // 不可信阴性用 fastMode 路径（checkHdr=true + setFastMode(true) →
        // definitive=false），对 Robolectric 完全确定。
        ImageFeatureDetector.setFastMode(true)
        val hdrKey = ImageFeatureCacheKey(10L, 1000L, enableHdr = true, enableMotion = false)
        val detectBlock: suspend () -> com.tabula.v3.data.model.ImageFeatures = {
            ImageFeatureDetector.detect(context, uri, checkHdr = true, checkMotion = false)
        }
        // 标定单人一轮检测的流打开数 K（readXmp + EXIF 提示读取）
        registerCountingStreams()
        val solo = ImageFeatureCacheStore.getOrDetect(hdrKey, detectBlock)
        val perFlightOpens = openCounter.get()
        assertTrue(
            "solo fast-mode detect must yield an untrusted negative",
            !solo.definitive && !solo.isHdr
        )
        assertNull(
            "untrusted negative must not enter the reliable cache",
            ImageFeatureCacheStore.get(hdrKey)
        )

        // 阻断共享轮次的首个流打开：发起者阻塞在 detect 中间，等待者全部进入
        // 在途等待后才放行 → 三方共享同一轮（时序确定性，沿用既有屏障原语）。
        val flightArrived = CountDownLatch(1)
        val releaseFlight = CountDownLatch(1)
        registerCountingStreams(
            barrierLimit = perFlightOpens + 1,
            barrier = flightArrived,
            blocked = releaseFlight
        )
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        val callers = (1..3).map {
            scope.async { ImageFeatureCacheStore.getOrDetect(hdrKey, detectBlock) }
        }
        assertTrue(flightArrived.await(10, TimeUnit.SECONDS))
        // 给两个等待者时间进入在途等待（沿用既有测试时序原语）
        Thread.sleep(500)
        releaseFlight.countDown()
        val shared = callers.map {
            runBlocking { kotlinx.coroutines.withTimeout(20_000) { it.await() } }
        }
        assertEquals(
            "3 concurrent callers must share exactly one detection flight " +
                "(one flight's worth of stream opens)",
            perFlightOpens * 2, openCounter.get()
        )
        assertTrue(
            "all callers must receive the untrusted negative",
            shared.all { !it.definitive && !it.isHdr }
        )
        assertEquals(
            "all three callers must share one identical result",
            1, shared.toSet().size
        )
        assertNull(
            "putIfReliable must still reject the shared untrusted negative",
            ImageFeatureCacheStore.get(hdrKey)
        )
        // 后续新一轮调用：不可信阴性不在可靠缓存 → 从头重跑检测
        ImageFeatureCacheStore.getOrDetect(hdrKey, detectBlock)
        assertTrue(
            "a fresh round after the shared flight must rerun detection",
            openCounter.get() > perFlightOpens * 2
        )
        println("T11B-SHARED-UNTRUSTED-NEGATIVE perFlightOpens=$perFlightOpens totalOpens=${openCounter.get()}")
    }

    @Test
    fun postOwnershipRecheckAvoidsDuplicateDetectionAfterCacheFill() = runBlocking {
        // PR60 修复二：B 的初始 get 发生在 A 入库前（未命中），B 领取所有权发生
        // 在 A 完成入库之后 → B 经所有权后复查直接命中缓存，不再重跑检测。
        // 可控注入 preOwnershipGateForTest 把 B 拦在初始 get 与 putIfAbsent 之间，
        // 精确复现"初始 get 检查"与"领取所有权"之间的入库竞态窗口。
        val aArrived = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        registerCountingStreams(barrierLimit = 1, barrier = aArrived, blocked = releaseA)
        val hookCalls = AtomicInteger(0)
        val bParked = CountDownLatch(1)
        ImageFeatureCacheStore.preOwnershipGateForTest = {
            // A（第 1 次调用）直接放行；B（第 2 次调用）停在所有权领取之前
            if (hookCalls.incrementAndGet() == 2) bParked.await(15, TimeUnit.SECONDS)
        }
        val detectBlock: suspend () -> com.tabula.v3.data.model.ImageFeatures = {
            ImageFeatureDetector.detect(context, uri, checkHdr = false, checkMotion = true)
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        val a = scope.async { ImageFeatureCacheStore.getOrDetect(cacheKey, detectBlock) }
        assertTrue(aArrived.await(10, TimeUnit.SECONDS))
        val b = scope.async { ImageFeatureCacheStore.getOrDetect(cacheKey, detectBlock) }
        // 等 B 抵达所有权闩锁（其初始 get 已在 A 入库前完成且未命中）
        val bDeadline = System.currentTimeMillis() + 10_000
        while (hookCalls.get() < 2 && System.currentTimeMillis() < bDeadline) Thread.sleep(20)
        assertEquals(
            "B must be parked between its initial get and ownership acquisition",
            2, hookCalls.get()
        )
        assertNull(
            "cache must still be empty while B is parked (B's initial get missed)",
            ImageFeatureCacheStore.get(cacheKey)
        )
        // A 完成：可靠阴性入库 + 在途条目移除
        releaseA.countDown()
        val aResult = runBlocking { kotlinx.coroutines.withTimeout(20_000) { a.await() } }
        assertNotNull("A's reliable negative must be cached", ImageFeatureCacheStore.get(cacheKey))
        // 释放 B：此时它才领取所有权 → 复查命中缓存，不再重跑
        bParked.countDown()
        val bResult = runBlocking { kotlinx.coroutines.withTimeout(20_000) { b.await() } }
        assertEquals(
            "B must share A's cached result via the post-ownership re-check",
            aResult, bResult
        )
        assertEquals(
            "B must not rerun detection (no stream opens beyond A's flight)",
            1, openCounter.get()
        )
        println("T11B-POST-OWNERSHIP-RECHECK opens=${openCounter.get()}")
    }
}
