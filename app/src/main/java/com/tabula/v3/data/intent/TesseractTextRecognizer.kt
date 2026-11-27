package com.tabula.v3.data.intent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Tesseract4Android（Apache-2.0）+ `tessdata_fast/chi_sim` 的本地中文 OCR 实现。
 *
 * 为什么选它（详见 docs/intent-ocr-evaluation-2026-11-25.md）：
 * - **完全离线、零遥测**：没有网络代码路径，也不需要 Google Play services；
 *   这与项目 LICENSE 里「不上传任何照片或个人信息」的承诺一致。
 * - 模型随 APK 打包（assets），首次使用只做一次本地解压，不联网下载。
 * - 代价是体积：每个 ABI 约 7MB 原生库 + 2.4MB 模型，详见评估文档的实测数字。
 *
 * 失败语义：模型解压失败、初始化失败都返回 [OcrResult.Unavailable]，
 * 上层整体降级为「证据不足」，不阻塞暂存清理的其它功能。
 */
class TesseractTextRecognizer(
    private val context: Context,
    private val language: String = "chi_sim"
) : TextRecognizer {

    override val engineId: String get() = "tesseract4android-4.8.0/$language"

    @Volatile
    private var engine: TessBaseAPI? = null

    @Volatile
    private var initFailure: String? = null

    override val isAvailable: Boolean
        get() = initFailure == null && ensureEngine() != null

    /**
     * 懒初始化：解压模型 + init。第一次调用会有一次磁盘 IO（约 2.4MB），
     * 之后复用同一个 [TessBaseAPI] 实例（Tesseract 的初始化成本远高于识别单张图）。
     */
    private fun ensureEngine(): TessBaseAPI? {
        engine?.let { return it }
        initFailure?.let { return null }
        synchronized(this) {
            engine?.let { return it }
            initFailure?.let { return null }
            return try {
                val dataDir = prepareTrainedData()
                val api = TessBaseAPI()
                val ok = api.init(dataDir.absolutePath, language, TessBaseAPI.OEM_LSTM_ONLY)
                if (!ok) {
                    api.recycle()
                    initFailure = "Tesseract init 返回 false（模型或语言包不可用）"
                    null
                } else {
                    api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                    engine = api
                    api
                }
            } catch (e: Throwable) {
                initFailure = e.javaClass.simpleName
                null
            }
        }
    }

    /** 把 assets/ocr/<lang>.traineddata 解压到 `filesDir/ocr/tessdata/`（幂等）。 */
    private fun prepareTrainedData(): File {
        val dataDir = File(context.filesDir, "ocr")
        val tessDir = File(dataDir, "tessdata").apply { mkdirs() }
        val target = File(tessDir, "$language.traineddata")
        if (target.exists() && target.length() > MIN_MODEL_BYTES) return dataDir
        val assetName = "ocr/$language.traineddata"
        context.assets.open(assetName).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }
        return dataDir
    }

    override suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        synchronized(this@TesseractTextRecognizer) {
            // A cancelled caller may have been waiting for a previous native call to finish.
            ensureActive()
            recognizeLocked(bitmap)
        }
    }

    private fun recognizeLocked(bitmap: Bitmap): OcrResult {
        val api = ensureEngine() ?: return OcrResult.Unavailable(
            initFailure ?: "OCR 引擎不可用"
        )
        return try {
            api.setImage(bitmap)
            val raw = api.getUTF8Text().orEmpty()
            val confidence = (api.meanConfidence().coerceIn(0, 100)) / 100f
            val lines = raw.split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (lines.isEmpty()) OcrResult.Empty
            else OcrResult.Success(lines = lines, meanConfidence = confidence)
        } catch (e: Throwable) {
            OcrResult.Failed(e.javaClass.simpleName)
        } finally {
            // 显式释放上一张图的内部状态，避免大图累积
            runCatching { api.clear() }
        }
    }

    override fun close() {
        synchronized(this) {
            runCatching { engine?.recycle() }
            engine = null
        }
    }

    companion object {
        /** 小于这个字节数的模型文件视为解压不完整，重新解压。 */
        private const val MIN_MODEL_BYTES = 1_000_000L

        /**
         * 解码一张图给 OCR 用。
         *
         * 缩到长边 [maxSide] 像素：Tesseract 对超大图没有额外收益，却会显著变慢。
         * 用 inSampleSize 逐级减半，避免一次分配整张大图。
         */
        fun decodeForOcr(context: Context, uri: Uri, maxSide: Int = 1600): Bitmap? {
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            } catch (e: Throwable) {
                null
            }
        }
    }
}
