package com.tabula.v3.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.tabula.v3.data.cache.ImageHashCache
import com.tabula.v3.data.model.ImageFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.util.function.Supplier

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RecommendationEngineContentInvalidationTest {
    private val app: android.app.Application get() = RuntimeEnvironment.getApplication()
    private val context: Context get() = app
    private lateinit var uri10: Uri
    private lateinit var uri11: Uri
    private lateinit var file10: File
    private lateinit var file11: File

    @Before
    fun setUp() {
        file10 = File(context.cacheDir, "rec-cache-a.jpg")
        file11 = File(context.cacheDir, "rec-cache-b.jpg")
        uri10 = Uri.parse("content://media/external/images/media/10")
        uri11 = Uri.parse("content://media/external/images/media/11")
        writeGradientJpeg(file10, leftToRight = true)
        writeGradientJpeg(file11, leftToRight = true)
        // 流注册走 ShadowContentResolver supplier（与 EvidenceTestProvider 同机制）：
        // 每次打开新建 FileInputStream，重写文件后重算能读到新字节。
        val streamResolver = shadowOf(context.contentResolver)
        streamResolver.registerInputStreamSupplier(uri10, Supplier { FileInputStream(file10) })
        streamResolver.registerInputStreamSupplier(uri11, Supplier { FileInputStream(file11) })
        ImageHashCache.getInstance(context).clearAll()
    }

    /** 灰度横向渐变；leftToRight=false 时方向反转，dHash 与正向近似全位不同。 */
    private fun writeGradientJpeg(target: File, leftToRight: Boolean) {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (x in 0 until 64) {
            for (y in 0 until 64) {
                val v = (if (leftToRight) x else 63 - x) * 4
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) { "JPEG compress failed" } }
        bitmap.recycle()
    }

    private fun initialImages() = listOf(
        ImageFile(
            id = 10L, uri = uri10, displayName = "a.jpg", dateModified = 1000L,
            size = 4096L, width = 64, height = 64, bucketDisplayName = "Camera"
        ),
        ImageFile(
            id = 11L, uri = uri11, displayName = "b.jpg", dateModified = 2000L,
            size = 4096L, width = 64, height = 64, bucketDisplayName = "Camera"
        )
    )

    @Test
    fun staleGroupIsNotServedAfterContentEditWithinTtl() = runBlocking {
        val engine = RecommendationEngine(context)
        val first = engine.getNextSimilarGroup(initialImages())
        assertNotNull("two identical images must form one group", first)
        assertEquals(2, first!!.images.size)

        // TC58：同媒体 ID 改内容——字节换成反向渐变，DATE_MODIFIED 提升。
        writeGradientJpeg(file11, leftToRight = false)
        val edited = initialImages().map { if (it.id == 11L) it.copy(dateModified = 3000L) else it }

        // 旧行为（仅 5 分钟 TTL）：TTL 内返回仍按旧内容分组的缓存组；
        // 新行为：内容指纹失效 → 重算（新版本哈希与图 10 不再相似）→ 无相似组。
        assertNull("stale cached group must not be served after content edit", engine.getNextSimilarGroup(edited))
    }

    @Test
    fun unchangedInputWithinTtlStillHitsCache() = runBlocking {
        val engine = RecommendationEngine(context)
        val images = initialImages()
        val first = engine.getNextSimilarGroup(images)
        assertNotNull(first)
        val second = engine.getNextSimilarGroup(images)
        assertNotNull(second)
        assertSame("unchanged input must reuse the cached group instance", first, second)
    }
}
