package com.tabula.v3.data.duplicate

import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.mediaIdentityKey

/**
 * 删除前副本复核的生产 seam（#88 审计）。
 *
 * MainActivity 与真实删除入口测试共用；测试注入的 digestOf 直接反映
 * ContentResolver 的读取结果与文件摘要，独立事实来源。
 */
object DuplicateReverify {

    /** 复核某个副本是否仍可作"图库还剩一份"的证据。 */
    fun copyStillValid(
        main: ImageFile,
        copy: ImageFile,
        libraryKeys: Set<String>,
        selectedKeys: Set<String>,
        digestOf: (ImageFile) -> DuplicateRules.ContentVersion?
    ): Boolean {
        val copyKey = copy.mediaIdentityKey()
        if (copyKey !in libraryKeys) return false
        if (copyKey in selectedKeys) return false
        val mainVersion = digestOf(main) ?: return false
        val copyVersion = digestOf(copy) ?: return false
        return mainVersion.isMeaningful &&
            copyVersion.isMeaningful &&
            mainVersion.sha256.equals(copyVersion.sha256, ignoreCase = true) &&
            mainVersion.size == copyVersion.size
    }

    data class Verdict(
        val shouldStop: Boolean,
        val invalidatedKeys: Set<String>
    )

    fun verifyAll(
        toDelete: List<ImageFile>,
        details: Map<String, List<ImageFile>>,
        libraryKeys: Set<String>,
        digestOf: (ImageFile) -> DuplicateRules.ContentVersion?
    ): Verdict {
        val selected = toDelete.map { it.mediaIdentityKey() }.toSet()
        val invalidated = mutableSetOf<String>()
        toDelete.forEach { image ->
            val key = image.mediaIdentityKey()
            val copies = details[key] ?: return@forEach
            val valid = copies.any { copy ->
                copyStillValid(image, copy, libraryKeys, selected, digestOf)
            }
            if (copies.isNotEmpty() && !valid) invalidated += key
        }
        return Verdict(shouldStop = invalidated.isNotEmpty(), invalidatedKeys = invalidated)
    }
}
