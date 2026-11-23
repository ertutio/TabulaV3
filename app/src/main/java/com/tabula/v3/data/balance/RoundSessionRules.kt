package com.tabula.v3.data.balance

import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.mediaIdentityKey

/**
 * 真实整理会话的行为统计（#90 审计）。
 *
 * 只统计**成功**处理的行为：保留 / 归档 / 删除。撤销或写入失败要回退，
 * 否则 "这轮主要在清哪个相册" 的判断会被没做成的事污染。
 *
 * 纯逻辑、会话级（不持久化），进程重启即归零。
 */
object RoundSessionRules {

    enum class HandledAction { KEEP, ARCHIVE, DELETE }

    data class Handled(
        val image: ImageFile,
        val action: HandledAction
    ) {
        val key: String get() = image.mediaIdentityKey()
    }

    /**
     * 会话状态：当前批次的行为 + 最近完成的批次。
     * 当前批次用于触发意图提示；批次切换时清空当前、保留最近（供审计）。
     */
    data class Session(
        val currentBatch: List<Handled> = emptyList(),
        val completedBatches: List<List<Handled>> = emptyList()
    ) {
        /** 当前批次按来源分组，供 shouldSuggest 判定。 */
        fun handledImages(): List<ImageFile> = currentBatch.map { it.image }

        fun isEmpty(): Boolean = currentBatch.isEmpty()
    }

    /** 成功处理一张照片后记账（同身份只记最新一次动作）。 */
    fun record(session: Session, image: ImageFile, action: HandledAction): Session {
        val entry = Handled(image, action)
        val updated = session.currentBatch.filterNot { it.key == entry.key } + entry
        return session.copy(currentBatch = updated)
    }

    /** 撤销/失败回退：移除该身份的记录，绝不影响其它照片。 */
    fun undo(session: Session, image: ImageFile): Session =
        session.copy(currentBatch = session.currentBatch.filterNot { it.key == image.mediaIdentityKey() })

    /** 批次切换：当前批次归档进 completedBatches，当前清空。 */
    fun nextBatch(session: Session): Session {
        if (session.currentBatch.isEmpty()) return session
        return Session(
            currentBatch = emptyList(),
            completedBatches = session.completedBatches + listOf(session.currentBatch)
        )
    }

    /** 退出整理会话：清空提示、意图与全部统计。 */
    fun endSession(): Session = Session()

    /** 切换专清/相似推荐时，隔离本轮统计——另一个模式不继承这套来源意图。 */
    fun isolateForModeSwitch(session: Session): Session =
        session.copy(currentBatch = emptyList())

    /** 是否足以触发意图提示：至少 3 张成功处理、且 ≥60% 来自同一来源。 */
    fun shouldSuggest(
        session: Session,
        sourceKeyOf: (ImageFile) -> String,
        availableImages: List<ImageFile>
    ): String? {
        val handled = session.handledImages()
        if (handled.size < 3) return null
        val bySource = handled.groupBy(sourceKeyOf)
        val (source, rows) = bySource.maxByOrNull { it.value.size } ?: return null
        if (rows.size.toDouble() / handled.size < 0.6) return null
        if (availableImages.none { sourceKeyOf(it) == source && session.currentBatch.none { handled -> handled.key == it.mediaIdentityKey() } }) return null
        return source
    }
}
