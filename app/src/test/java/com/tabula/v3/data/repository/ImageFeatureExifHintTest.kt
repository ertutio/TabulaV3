package com.tabula.v3.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.tabula.v3.data.model.ImageFeatures
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.ui.util.ImageFeatureCacheKey
import com.tabula.v3.ui.util.ImageFeatureCacheStore
import com.tabula.v3.ui.util.preloadImageFeatures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.function.Supplier

/**
 * T12/TC52 定点回归：EXIF 提示是 HDR 判定的确定性证据面（T12"每个实际使用
 * 依赖单独核版本/解析并可回退；分别覆盖 EXIF/实况"）——androidx.exifinterface
 * 1.3.7 解析的 TAG_SOFTWARE / TAG_IMAGE_DESCRIPTION / TAG_USER_COMMENT 提示
 * 词（hdr/ultra hdr/pro xdr/hdr10）必须在重型检测之前生效并产出 definitive
 * 阳性（ImageFeatureDetector.detectHdr 第二步：XMP 之后、EXIF 提示、重型
 * 解码之前）。
 *
 * 对照：无 EXIF 提示的同构 JPEG 在 Robolectric 下重型 ImageDecoder 检测失败
 * → 不可信阴性（definitive=false）——这正是 T11a 拒绝入缓存的形态，证明
 * EXIF 提示命中与否是 definitive 边界的真实区分输入。
 *
 * 升级回退契约：若 exifinterface 升级改变这些 TAG 的解析行为，本组测试转红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ImageFeatureExifHintTest {
    private val app: android.app.Application get() = RuntimeEnvironment.getApplication()
    private val context: Context get() = app
    private val uri = Uri.parse("content://media/external/images/media/10")
    private val image = ImageFile(
        id = 10L,
        uri = uri,
        displayName = "exif-card.jpg",
        dateModified = 1000L,
        size = 4096L,
        width = 64,
        height = 64,
        bucketDisplayName = "Camera"
    )
    private lateinit var jpegFile: File

    @Before
    fun setUp() {
        jpegFile = File(context.cacheDir, "exif-hint-card.jpg")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)
        jpegFile.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        bitmap.recycle()
    }

    @After
    fun tearDown() {
        ImageFeatureCacheStore.clear()
        ImageFeatureDetector.setFastMode(false)
        jpegFile.delete()
    }

    /** 写入 EXIF 属性并注册为该 URI 的流。 */
    private fun registerJpegWithExif(vararg attributes: Pair<String, String>) {
        val exif = ExifInterface(jpegFile.absolutePath)
        attributes.forEach { (tag, value) -> exif.setAttribute(tag, value) }
        exif.saveAttributes()
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri, Supplier {
            FileInputStream(jpegFile)
        })
    }

    private fun detectHdrOnly(): ImageFeatures = runBlocking {
        ImageFeatureDetector.detect(
            context = context,
            uri = uri,
            checkHdr = true,
            checkMotion = false
        )
    }

    @Test
    fun exifSoftwareHdrHintYieldsDefinitivePositive() {
        registerJpegWithExif(ExifInterface.TAG_SOFTWARE to "HDR Photo Maker 1.0")

        val features = detectHdrOnly()

        assertTrue("EXIF software hint must yield HDR positive", features.isHdr)
        assertTrue("EXIF hint path must be definitive (pre-heavy-detection)", features.definitive)
    }

    @Test
    fun exifImageDescriptionUltraHdrHintYieldsDefinitivePositive() {
        registerJpegWithExif(ExifInterface.TAG_IMAGE_DESCRIPTION to "Ultra HDR capture")

        val features = detectHdrOnly()

        assertTrue(features.isHdr)
        assertTrue(features.definitive)
    }

    @Test
    fun exifUserCommentProXdrHintYieldsDefinitivePositive() {
        registerJpegWithExif(ExifInterface.TAG_USER_COMMENT to "PRO XDR")

        val features = detectHdrOnly()

        assertTrue(features.isHdr)
        assertTrue(features.definitive)
    }

    @Test
    fun exifHintResultIsCachedThroughReliablePath() = runBlocking {
        // 缓存发生在 preload 包装层（putIfReliable），不在 detect 内——
        // 经 preloadImageFeatures 走完整生产路径。
        registerJpegWithExif(ExifInterface.TAG_SOFTWARE to "hdr10 pipeline")

        preloadImageFeatures(context, image, enableHdr = true, enableMotion = false)

        val cached = ImageFeatureCacheStore.get(
            ImageFeatureCacheKey(10L, 1000L, enableHdr = true, enableMotion = false)
        )
        assertNotNull("definitive EXIF-hint positive must be cacheable (putIfReliable)", cached)
        assertEquals(true, cached?.isHdr)
    }

    @Test
    fun sameJpegWithoutExifHintFallsThroughAsUntrustedNegative() {
        // 对照：无提示词 → 重型检测在 Robolectric 失败 → 不可信阴性（不入缓存）。
        // 证明上面四例的 definitive 阳性确实由 EXIF 提示词区分，而非解码形态。
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri, Supplier {
            FileInputStream(jpegFile)
        })

        val features = detectHdrOnly()

        assertFalse("no-hint JPEG must not report HDR", features.isHdr)
        assertFalse("heavy detection failure under Robolectric must stay untrusted", features.definitive)
    }
}
