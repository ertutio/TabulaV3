package com.tabula.v3.data.intent

import com.tabula.v3.data.model.ImageFile

/**
 * 提交「移入回收站」前的**资格复核**。
 *
 * ## 为什么需要单独一层
 *
 * 分析是一段时间之前做的：用户在这期间可能滚到了别的 App 改了图、把某张放进
 * 回收站、在别处把某张标成长期保留，或者权限被收窄导致某张已经看不见。
 * 如果提交时直接拿「分析那一刻的列表」去写回收站，就会出现：
 * - 把已经不该清的（刚标了长期保留）清掉；
 * - 把已经不在候选范围的写进去；
 * - 把已经被替换/编辑过的同一 ID 当成原图处理。
 *
 * 因此提交前逐项复核四件事，**任何一项不成立就跳过该项**，并把原因分类回报给界面，
 * 由界面如实说明「N 张已移入，M 张因 … 跳过」，而不是笼统地说成功。
 *
 * ## 复核用的身份
 *
 * 「完整媒体身份」= 卷 + ID + 内容版本（size + dateModified）+ 相对路径。
 * 只比较 ID 不够：同一 ID 可能被重写（size/dateModified 变），也可能因为
 * 存储卷重挂导致 ID 复用。
 */
object IntentCommitGuard {

    /** 一项复核结论。 */
    enum class Outcome {
        /** 可以提交。 */
        ELIGIBLE,

        /** 当前图库里看不到这张（权限撤回 / 已删除 / 卷不可访问）。 */
        MISSING,

        /** 同一 ID 但内容已变（size 或 dateModified 不同）。 */
        CONTENT_CHANGED,

        /** 已经不在暂存清理范围内（来源被取消勾选、路径变了）。 */
        OUT_OF_SCOPE,

        /** 当前已被标记长期保留。 */
        RETAINED,

        /** 已在回收站记录里。 */
        ALREADY_RECYCLED,

        /** 当前判定为保护类（保护规则先于一切）。 */
        PROTECTED
    }

    /** 单张的复核结果。 */
    data class Verdict(
        val image: ImageFile,
        val outcome: Outcome
    ) {
        val eligible: Boolean get() = outcome == Outcome.ELIGIBLE
    }

    /** 整批复核结果。 */
    data class Report(
        val eligible: List<ImageFile>,
        val skipped: List<Verdict>
    ) {
        val skippedByOutcome: Map<Outcome, Int>
            get() = skipped.groupingBy { it.outcome }.eachCount()

        /** 给用户的简短说明（不含任何图片内容）。 */
        fun describe(): String {
            if (skipped.isEmpty()) return ""
            val parts = skippedByOutcome.entries
                .sortedBy { it.key.ordinal }
                .map { (outcome, count) -> "$count 张${label(outcome)}" }
            return parts.joinToString("、")
        }

        private fun label(outcome: Outcome): String = when (outcome) {
            Outcome.MISSING -> "已不在这批范围（可能权限变了或已被删除）"
            Outcome.CONTENT_CHANGED -> "内容已变化"
            Outcome.OUT_OF_SCOPE -> "已不在暂存清理范围"
            Outcome.RETAINED -> "已被标记长期保留"
            Outcome.ALREADY_RECYCLED -> "已在回收站"
            Outcome.PROTECTED -> "属于应保留内容"
            Outcome.ELIGIBLE -> ""
        }
    }

    /**
     * 复核一批选中项。
     *
     * @param selected 用户点提交时选中的图（来自**当前界面批次**）
     * @param currentLibrary 此刻图库里的全部图（最新快照）
     * @param retainedIdentityKeys 最新的长期保留集合
     * @param recycledIdentityKeys 最新的回收站身份集合
     * @param stagedSourceKeys 本次暂存清理选定的来源（`卷|路径`，与规则层同口径）
     * @param protectCandidate 额外的保护判定（通常是规则层再看一遍当前 OCR 结论）
     */
    fun review(
        selected: List<ImageFile>,
        currentLibrary: List<ImageFile>,
        retainedIdentityKeys: Set<String>,
        recycledIdentityKeys: Set<String>,
        stagedSourceKeys: Set<String>,
        protectCandidate: (ImageFile) -> Boolean = { false }
    ): Report {
        val libraryByIdentity = currentLibrary.associateBy { mediaIdentity(it) }
        val eligible = mutableListOf<ImageFile>()
        val skipped = mutableListOf<Verdict>()

        selected.forEach { original ->
            val identity = mediaIdentity(original)
            val current = libraryByIdentity[identity]
            val outcome = when {
                identity in recycledIdentityKeys -> Outcome.ALREADY_RECYCLED
                identity in retainedIdentityKeys -> Outcome.RETAINED
                current == null -> Outcome.MISSING
                contentDigest(current) != contentDigest(original) -> Outcome.CONTENT_CHANGED
                !inStagedScope(current, stagedSourceKeys) -> Outcome.OUT_OF_SCOPE
                protectCandidate(current) -> Outcome.PROTECTED
                else -> Outcome.ELIGIBLE
            }
            if (outcome == Outcome.ELIGIBLE && current != null) {
                eligible += current
            } else {
                skipped += Verdict(original, outcome)
            }
        }
        return Report(eligible = eligible, skipped = skipped)
    }

    /** 完整媒体身份：卷 + ID。用于跨来源对账（与 `cleanupIdentityKey` 同口径）。 */
    fun mediaIdentity(image: ImageFile): String = "${image.volumeName ?: ""}|${image.id}"

    /** 内容版本：尺寸 + 修改时间。同一 ID 被重写时一定变化。 */
    fun contentDigest(image: ImageFile): String = "${image.size}|${image.dateModified}"

    /** 是否仍落在本次选定的暂存来源里（卷 + 相对路径，大小写与斜杠归一）。 */
    fun inStagedScope(image: ImageFile, stagedSourceKeys: Set<String>): Boolean {
        val path = image.relativePath?.takeIf { it.isNotBlank() } ?: return false
        val key = "${(image.volumeName ?: "").trim().lowercase()}|${normalizePath(path)}"
        return key in stagedSourceKeys
    }

    private fun normalizePath(path: String): String =
        path.replace('\\', '/').trim('/').lowercase()
}
