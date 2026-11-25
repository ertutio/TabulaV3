package com.tabula.v3.data.balance

import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.mediaIdentityKey

/**
 * 真实整理会话的行为统计（#90 审计）。
 *
 * 只统计**成功**处理的行为：保留 / 归档 / 删除。撤销或写入失败要回退，
 * 否则 "这轮主要在清哪个相册" 的判断会被没做成的事污染。
 *
 * 审计 #90 修正（第三轮）：
 * - 每条记录在**入账时**就把来源键与身份键固定下来（[Handled.sourceKey] /
 *   [Handled.key]）。已处理的照片可能已经离开图库（删除）或被归入图集，
 *   事后无法再从 `availableImages` 反查它的来源——识别来源只能靠会话记录
 *   里的快照，不能靠"照片还在不在池子里"。
 * - [Session.acceptedThisRound] 记住本轮已接受过的来源，避免在同一轮处理
 *   过程中反复弹同一条提示。
 * - [isolateForModeSwitch] 与 [endSession] 清空全部内容（统计、已接受来源），
 *   供专清 / 相似推荐 / 退出整理时隔离。
 *
 * 纯逻辑、会话级（不持久化），进程重启即归零。
 */
object RoundSessionRules {

    enum class HandledAction { KEEP, ARCHIVE, DELETE }

    /**
     * 一条成功处理记录。
     *
     * @param image 当时的照片行（保留 uri/displayName 供展示）
     * @param action 成功执行的动作
     * @param sourceKey 入账时的来源键快照（`卷|相对路径`）
     * @param key 入账时的完整媒体身份键快照（`卷|ID`）
     */
    data class Handled(
        val image: ImageFile,
        val action: HandledAction,
        val sourceKey: String,
        val key: String
    )

    /**
     * 会话状态：当前批次的行为 + 最近完成的批次 + 本轮已接受来源。
     */
    data class Session(
        val currentBatch: List<Handled> = emptyList(),
        val completedBatches: List<List<Handled>> = emptyList(),
        /** 本轮已经接受过的来源键；同一来源不再重复提示。 */
        val acceptedThisRound: String? = null
    ) {
        /** 当前批次的身份键集合（判断"批外候选"用）。 */
        val currentBatchIdentityKeys: Set<String>
            get() = currentBatch.map { it.key }.toSet()

        fun isEmpty(): Boolean = currentBatch.isEmpty()
    }

    /**
     * 成功处理一张照片后记账（同身份只记最新一次动作）。
     *
     * @param sourceKeyOf 来源键解析函数；**必须**在入账时求值并保存，
     *   否则照片离开图库后无法再识别来源
     */
    fun record(
        session: Session,
        image: ImageFile,
        action: HandledAction,
        sourceKeyOf: (ImageFile) -> String = { SourceBalanceRules.sourceKeyOf(it) }
    ): Session {
        val entry = Handled(
            image = image,
            action = action,
            sourceKey = sourceKeyOf(image),
            key = image.mediaIdentityKey()
        )
        val updated = session.currentBatch.filterNot { it.key == entry.key } + entry
        return session.copy(currentBatch = updated)
    }

    /** 撤销/失败回退：移除该身份的记录，绝不影响其它照片。 */
    fun undo(session: Session, image: ImageFile): Session =
        session.copy(currentBatch = session.currentBatch.filterNot { it.key == image.mediaIdentityKey() })

    /**
     * 带批次边界的记账（#90 第四轮审计）。
     *
     * 只在**发起动作时的会话与批次仍然是当前那一个**时才记账：
     * 旧批次上发起的删除/归档如果等到换批之后才完成，绝不能计进新批次。
     *
     * 失败与撤销不走这里（它们本来就不记账）。调用方仍负责把照片从图库/UI
     * 里移除——本函数只决定"要不要计入本轮来源统计"。
     *
     * @param ticket 动作**发起时**绑定下来的标识（[Ticket.capture]）
     * @param currentIds 提交这一刻的当前标识
     * @return 记账后的会话；过期动作返回原会话，一个字段都不动
     */
    fun recordIfStillCurrent(
        session: Session,
        image: ImageFile,
        action: HandledAction,
        ticket: Ticket,
        currentIds: SessionBatchIds,
        sourceKeyOf: (ImageFile) -> String = { SourceBalanceRules.sourceKeyOf(it) }
    ): Session {
        if (!ticket.isStillCurrent(currentIds)) return session
        return record(session, image, action, sourceKeyOf)
    }

    /**
     * 带批次边界的撤销回退（#90 第四轮审计）。
     *
     * 撤销同样只能动**发起时那批**的记录。若期间已经换批（统计已清空重来），
     * 撤销旧批次的动作绝不能把新批次里同身份的那条记录删掉——那不是它记的。
     */
    fun undoIfStillCurrent(
        session: Session,
        image: ImageFile,
        ticket: Ticket,
        currentIds: SessionBatchIds
    ): Session {
        if (!ticket.isStillCurrent(currentIds)) return session
        return undo(session, image)
    }

    /** 标记本轮已接受该来源：同一来源本轮不再提示。 */
    fun markAccepted(session: Session, sourceKey: String): Session =
        session.copy(acceptedThisRound = sourceKey)

    /** 批次切换：当前批次归档进 completedBatches，当前清空。 */
    fun nextBatch(session: Session): Session {
        if (session.currentBatch.isEmpty()) return session
        return session.copy(
            currentBatch = emptyList(),
            completedBatches = session.completedBatches + listOf(session.currentBatch)
        )
    }

    /** 退出整理会话：清空提示、意图与全部统计。 */
    fun endSession(): Session = Session()

    /**
     * 切换专清 / 相似推荐 / 进出专清时隔离本轮统计。
     *
     * 审计 #90：这里必须清空**全部**内容——统计、已完成批次与"本轮已接受"
     * 标记。留下任何一个，切换回来后旧来源的偏好或抑制状态都会串场。
     */
    fun isolateForModeSwitch(session: Session): Session = Session()

    /**
     * 是否足以触发意图提示：主来源至少 3 张成功处理、且占成功处理总数
     * 至少 60%，并且下一批候选中确有批外同来源照片。
     *
     * 判定本身在 [RoundIntentRules.shouldSuggest]（唯一实现），这里只是把
     * 入口级调用点固定在同一组参数上，避免两处阈值各写一份。
     *
     * @param eligibleImages 下一批候选（已扣冷却、屏蔽图集与当前批次）
     */
    fun shouldSuggest(
        session: Session,
        sourceKeyOf: (ImageFile) -> String,
        eligibleImages: List<ImageFile>,
        dismissed: Boolean = false
    ): String? = RoundIntentRules.shouldSuggest(
        session = session,
        eligibleImages = eligibleImages,
        sourceKeyOf = sourceKeyOf,
        dismissed = dismissed
    )
}
