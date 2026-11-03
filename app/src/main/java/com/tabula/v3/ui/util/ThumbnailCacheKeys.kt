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

/**
 * deck 查看器（ViewerOverlay）底层缩略图请求构造——240×240 INEXACT
 * RGB_565、非硬件位图，作为查看器打开瞬间的即时占位层（上层高清请求
 * 加载完成后淡入覆盖）。
 *
 * 本函数提取自 ViewerOverlay 内联构建，行为保持。键语义为现状
 * （C3/T06-VIEWERKEY 登记）：deck 两种卡片模式的卡片位图由 cardMemoryCacheKey
 * （卷限定 URI + DATE_MODIFIED）生产/消费，而本请求键为裸 ID 前缀
 * album_grid_——与网格现行的 grid_ 身份键及 deck 的 card_ 键均不匹配
 * （无生产者、温命中失效），且键不含卷与内容版本（查看器自写自读下
 * 跨卷同 ID 串图、同 ID 编辑后陈旧）。修复提交另行接线。
 */
internal fun deckViewerThumbnailRequest(
    context: Context,
    image: ImageFile
): ImageRequest = ImageRequest.Builder(context)
    .data(image.uri)
    .size(coil.size.Size(240, 240))
    .precision(Precision.INEXACT)
    .bitmapConfig(Bitmap.Config.RGB_565)
    .memoryCacheKey("album_grid_${image.id}")
    .diskCacheKey("album_grid_${image.id}")
    .memoryCachePolicy(CachePolicy.ENABLED)
    .diskCachePolicy(CachePolicy.ENABLED)
    .crossfade(false)
    .allowHardware(false)
    .build()
