package com.tabula.v3.data.duplicate

import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.mediaIdentityKey

/**
 * 副本扫描的候选筛选（#105 性能项）。
 *
 * 从 `MainActivity` 的 `LaunchedEffect` 里抽出来的纯逻辑，动机有两个：
 *
 * 1. **可测量**：候选筛选、摘要读取、结果分组三段原本混在一个协程体里，
 *    只能整体计时。#105 要求分别记录候选数、摘要读取数和各阶段耗时，
 *    抽成纯函数后 JVM 单测能直接对同一份固定合成图库跑前后对照。
 * 2. **修一处热点**：原实现在 `filter` 的 lambda 里重建"回收站身份集合"，
 *    即对全库每张照片都执行一次
 *    `deletedImages.map { it.mediaIdentityKey() }.toSet()`。
 *    库 2 万张、回收站 48 张时就是 96 万次 `mediaIdentityKey()` 字符串
 *    拼接加 2 万个临时 Set——而这份集合在一次扫描里完全不变。
 *    现在提到循环外构建一次复用。
 *
 * 语义必须与抽出前逐字一致，因此保留原有的三条判定：
 * - 大小为 0 的照片不参与（0 字节的"相同"没有意义）
 * - 只有大小与回收站里某张相同的才可能字节相同（先按大小缩小范围）
 * - 回收站里的照片本身不是库内候选（它们是被查对象，不是"还剩一份"的证据）
 *
 * 身份键是 `volumeName|id`，所以跨卷同数值 ID 是两张不同的照片——这一点
 * 由 [mediaIdentityKey] 保证，本文件不另做判断。
 */
object DuplicateScanCandidates {

    /** 回收站里出现过的、非 0 的文件大小。空集表示没有任何可比的候选。 */
    fun recycleSizes(deletedImages: List<ImageFile>): Set<Long> =
        deletedImages.map { it.size }.filter { it > 0L }.toSet()

    /**
     * 库内候选：大小与回收站某张相同、非 0 字节、且自己不在回收站里。
     *
     * 返回顺序沿用 [allImages] 的顺序——上层只用来算摘要，顺序不影响结果，
     * 但保持输入顺序让前后对照与既有行为一致。
     */
    fun selectLibraryCandidates(
        allImages: List<ImageFile>,
        deletedImages: List<ImageFile>
    ): List<ImageFile> {
        val recycleSizes = recycleSizes(deletedImages)
        if (recycleSizes.isEmpty()) return emptyList()

        // #105：身份集合在筛选循环外构建一次。原实现放在 lambda 里，
        // 对全库每张照片都重建一遍。
        val recycledKeys = HashSet<String>(deletedImages.size * 2)
        deletedImages.forEach { recycledKeys += it.mediaIdentityKey() }

        return allImages.filter { image ->
            image.size > 0L && image.size in recycleSizes &&
                image.mediaIdentityKey() !in recycledKeys
        }
    }
}
