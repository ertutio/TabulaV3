package com.tabula.v3.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 感知哈希计算器
 * 
 * 使用差分哈希（dHash）算法计算图片的感知哈希值。
 * dHash 对连拍、轻微移动、亮度变化具有良好的鲁棒性。
 * 
 * 算法原理：
 * 1. 缩小图片到 9x8 像素
 * 2. 转换为灰度
 * 3. 比较相邻像素：左 > 右 → 1，否则 → 0
 * 4. 生成 64-bit 哈希值
 */
object PerceptualHash {
    
    /**
     * 哈希计算结果
     */
    sealed class HashResult {
        /**
         * 计算成功
         * @param hash 64-bit 哈希值（注意：0L 是合法的哈希值）
         */
        data class Success(val hash: Long) : HashResult()
        
        /**
         * 计算失败
         * @param reason 失败原因
         */
        data class Failed(val reason: String) : HashResult()
    }
    
    /**
     * 计算图片的 dHash
     * 
     * @param context Android Context
     * @param uri 图片 URI
     * @return HashResult 计算结果
     */
    suspend fun computeDHash(context: Context, uri: Uri): HashResult = 
        withContext(Dispatchers.Default) {
            try {
                // Step 1: 读取 EXIF 方向
                val orientation = readExifOrientation(context, uri)
                
                // Step 2: 获取图片尺寸（不加载到内存）
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                    // bounds-only 解码按 SDK 契约返回 null（维度写入 options）——
                    // use 的返回值必须为 Unit，否则 elvis 恒触发，把成功的 open
                    // 误判为失败（HashRecomputeDeviceTest 设备实证：修复前任何
                    // 图片恒 Failed("Cannot open input stream")，哈希链从未成功）。
                    Unit
                } ?: return@withContext HashResult.Failed("Cannot open input stream")
                
                if (options.outWidth <= 0 || options.outHeight <= 0) {
                    return@withContext HashResult.Failed("Invalid image dimensions")
                }
                
                // Step 3: 计算合适的 inSampleSize（目标：宽或高接近 64px）
                val targetSize = 64
                val sampleSize = calculateInSampleSize(
                    options.outWidth, 
                    options.outHeight, 
                    targetSize
                )
                
                // Step 4: 实际解码
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                
                var bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, decodeOptions)
                } ?: return@withContext HashResult.Failed("Decode failed")
                
                // Step 5: 应用 EXIF 旋转
                val rotatedBitmap = applyExifOrientation(bitmap, orientation)
                if (rotatedBitmap == null) {
                    bitmap.recycle()
                    return@withContext HashResult.Failed("EXIF rotation failed")
                }
                if (rotatedBitmap != bitmap) {
                    bitmap.recycle()
                    bitmap = rotatedBitmap
                }
                
                // Step 6: 精确缩放到 9x8（用于 dHash）
                val scaled = try {
                    Bitmap.createScaledBitmap(bitmap, 9, 8, true)
                } catch (e: Exception) {
                    bitmap.recycle()
                    return@withContext HashResult.Failed("Scale failed: ${e.message}")
                }
                
                if (scaled != bitmap) {
                    bitmap.recycle()
                }
                
                // Step 7: 计算差分哈希
                val hash = computeDHashFromBitmap(scaled)
                scaled.recycle()
                
                HashResult.Success(hash)
                
            } catch (e: OutOfMemoryError) {
                HashResult.Failed("OOM")
            } catch (e: CancellationException) {
                // 协程取消异常必须重新抛出，不能被捕获
                throw e
            } catch (e: Exception) {
                HashResult.Failed(e.message ?: "Unknown error")
            }
        }
    
    /**
     * 读取 EXIF 方向信息
     */
    private fun readExifOrientation(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }
    
    /**
     * 应用 EXIF 旋转
     * 
     * @return 旋转后的 Bitmap，如果不需要旋转则返回原 Bitmap
     */
    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap? {
        if (orientation == ExifInterface.ORIENTATION_NORMAL || 
            orientation == ExifInterface.ORIENTATION_UNDEFINED) {
            return bitmap
        }
        
        val matrix = Matrix()
        
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        
        return try {
            Bitmap.createBitmap(
                bitmap, 0, 0, 
                bitmap.width, bitmap.height, 
                matrix, true
            )
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 计算合适的 inSampleSize
     * 
     * 使用 2 的幂次来快速缩小图片，节省内存
     */
    private fun calculateInSampleSize(
        width: Int, 
        height: Int, 
        targetSize: Int
    ): Int {
        var sampleSize = 1
        val minDimension = minOf(width, height)
        
        // 每次翻倍直到接近目标大小
        while (minDimension / sampleSize > targetSize * 2) {
            sampleSize *= 2
        }
        
        return sampleSize
    }
    
    /**
     * 从 9x8 位图计算 dHash
     * 
     * 比较每行中相邻像素的灰度值，生成 64-bit 哈希
     */
    private fun computeDHashFromBitmap(bitmap: Bitmap): Long {
        require(bitmap.width == 9 && bitmap.height == 8) {
            "Bitmap must be 9x8 for dHash, got ${bitmap.width}x${bitmap.height}"
        }
        
        var hash = 0L
        var bit = 0
        
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val left = getGrayValue(bitmap, x, y)
                val right = getGrayValue(bitmap, x + 1, y)
                
                if (left > right) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        
        return hash
    }
    
    /**
     * 获取像素的灰度值
     * 
     * 使用 ITU-R BT.601 标准灰度公式
     */
    private fun getGrayValue(bitmap: Bitmap, x: Int, y: Int): Int {
        val pixel = bitmap.getPixel(x, y)
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
    
    /**
     * 计算两个哈希值的 Hamming 距离
     * 
     * Hamming 距离表示两个哈希值中不同位的数量。
     * 距离越小，图片越相似。
     * 
     * 经验阈值：
     * - 距离 ≤ 6：高度相似（同景/轻微抖动）
     * - 距离 7-10：可能相似（角度变化/轻裁剪）
     * - 距离 11-14：边缘相似
     * - 距离 > 14：大概率不相似
     * 
     * @param hash1 第一个哈希值
     * @param hash2 第二个哈希值
     * @return Hamming 距离（0-64）
     */
    fun hammingDistance(hash1: Long, hash2: Long): Int {
        return java.lang.Long.bitCount(hash1 xor hash2)
    }
}
