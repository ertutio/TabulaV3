package com.tabula.v3.ui.util

import android.content.Context
import android.graphics.Bitmap
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.mediaIdentityKey

/**
 * 图集/系统图集网格缩略图的 Coil memory/disk 缓存键。
 *
 * C1/T03-THUMBKEY：键必须携带完整媒体身份（卷|ID）与内容版本
 * （DATE_MODIFIED 毫秒）——跨卷同数值 ID 行不得共享缩略图条目（串图），
 * 同一照片编辑后（内容版本变化）必须重新加载。prefix 区分使用面
 * （album_grid_ / sys_grid_），避免不同网格互相挤占缓存。
 */
internal fun gridThumbnailCacheKey(prefix: String, image: ImageFile): String =
    // C1/T03-THUMBKEY：完整媒体身份键（卷|ID，跨卷同数值 ID 行不串图）+
    // 内容版本（DATE_MODIFIED 毫秒，编辑后重新加载；与 ImageHashCache
    // ver_ 的内容版本币制一致）。
    "$prefix${image.mediaIdentityKey()}_${image.dateModified}"

/**
 * C1/T03-COVERKEY：图集封面请求构造器（各封面展示面的唯一生产入口）。
 * 缓存键 = [gridThumbnailCacheKey]（完整媒体身份 + 内容版本）——同一照片
 * 编辑内容（dateModified 变化）后请求与缓存身份随之变化，跨卷同数值 ID
 * 行彼此隔离。视觉规格（size/crossfade/allowHardware/rgb565/inexact）由
 * 调用面按既有展示决定，默认保持各站点原值。
 */
fun coverImageRequest(
    context: Context,
    coverImage: ImageFile,
    prefix: String,
    size: Size? = null,
    crossfade: Boolean = false,
    allowHardware: Boolean = true,
    rgb565: Boolean = false,
    inexact: Boolean = false
): ImageRequest {
    // C1/T03-COVERKEY：显式缓存键 = 完整媒体身份 + 内容版本——同一照片
    // 编辑内容（dateModified 变化）后请求与缓存身份随之变化；跨卷同数值
    // ID 行彼此隔离。
    val key = gridThumbnailCacheKey(prefix, coverImage)
    val builder = ImageRequest.Builder(context)
        .data(coverImage.uri)
    val sized = if (size != null) builder.size(size) else builder
    val precise = if (inexact) sized.precision(Precision.INEXACT) else sized
    val configed = if (rgb565) precise.bitmapConfig(Bitmap.Config.RGB_565) else precise
    return configed
        .memoryCacheKey(key)
        .diskCacheKey(key)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .allowHardware(allowHardware)
        .crossfade(crossfade)
        .build()
}
