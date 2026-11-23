package com.tabula.v3.data.burst

import com.tabula.v3.data.model.ImageFile

/**
 * 连拍选片的提交 seam（#87 审计）。
 *
 * 生产（MainActivity）与入口级测试共用同一判定：
 * 进入时按 currentIndex 与当前图库状态裁剪真实组；
 * 提交前再次按完整身份核对；写入后分类部分成功。
 */
object BurstCommit {

    /**
     * 从真实组筛出可进入选片的成员。
     * @param currentIndex 当前正在看的索引，之前的视为已滑过
     */
    fun eligible(
        group: com.tabula.v3.data.repository.SimilarGroup,
        currentBatch: List<ImageFile>,
        currentIndex: Int,
        libraryKeys: Set<String>,
        recycleKeys: Set<String>,
        archivedKeys: Set<String> = emptySet()
    ) = BurstSelectionRules.effectiveGroup(
        group = group,
        currentBatch = currentBatch,
        currentIndex = currentIndex,
        stillPresent = libraryKeys - recycleKeys - archivedKeys,
        excludedIdentityKeys = recycleKeys + archivedKeys
    )

    /** 提交前再次核对：只保留此刻仍在图库且未进回收站的照片。 */
    fun reverify(
        discard: List<ImageFile>,
        libraryKeys: Set<String>,
        recycleKeys: Set<String>
    ) = BurstSelectionRules.reverifyBeforeCommit(
        candidates = discard,
        stillPresent = libraryKeys - recycleKeys
    )

    /** 依据回收站真实读回分类：成功 / 失败 / 部分。 */
    fun result(expected: List<ImageFile>, movedKeys: Set<String>) =
        BurstSelectionRules.classifyCommit(expected, movedKeys)
}
