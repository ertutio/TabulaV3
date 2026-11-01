package com.tabula.v3.ui.util

import com.tabula.v3.data.model.ImageFile

/**
 * 图集/系统图集网格缩略图的 Coil memory/disk 缓存键。
 *
 * C1/T03-THUMBKEY：键必须携带完整媒体身份（卷|ID）与内容版本
 * （DATE_MODIFIED 毫秒）——跨卷同数值 ID 行不得共享缩略图条目（串图），
 * 同一照片编辑后（内容版本变化）必须重新加载。prefix 区分使用面
 * （album_grid_ / sys_grid_），避免不同网格互相挤占缓存。
 */
internal fun gridThumbnailCacheKey(prefix: String, image: ImageFile): String =
    // 旧口径基线（原 "prefix_${image.id}" 的忠实形状）：键只含数值 ID——
    // 跨卷同 ID 行共享条目（串图），内容编辑后键不变（陈旧缩略图）。
    // 身份与内容版本接入在下一步修复中完成。
    "$prefix${image.id}"
