package com.tabula.v3.data.balance

import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.AlbumManager

/**
 * 延迟动作的记账决策（#90 第四轮审计）。
 *
 * ## 为什么单独抽出来
 *
 * 删除与归档都是"发起 → 延迟 → 成功/失败"三段式：
 *
 * - 删除：`onRemove` 启动协程 → `addToRecycleBin` 落库 → 成功后记账；
 * - 归档：`queueImageToAlbum` 排队 → Snackbar 超时（或下一次归档顶掉）
 *   → `commitPendingArchive` 真正复制 → COMMITTED 才记账。
 *
 * 记账必须用**发起时**绑定的 [Ticket] 与**提交这一刻**的 [SessionBatchIds]
 * 比对。这段决策原先内联在 `MainActivity` 的两个 lambda 里，没有可注入的
 * 观察点——测试只能复制一遍逻辑，等于没测生产代码。
 *
 * 现在 `MainActivity` 调用本对象，测试也调用本对象：接线本身就是被测对象。
 *
 * 纯逻辑，无 Android / Compose 依赖。
 */
object DeferredActionLedger {

    /** 一次延迟动作的完整身份：发起时的 ticket + 对应照片。 */
    data class Pending(
        val image: ImageFile,
        val ticket: Ticket
    )

    /**
     * 归档提交完成后的记账决策。
     *
     * 只有**真正提交成功**（[AlbumManager.ArchiveCommitOutcome.COMMITTED]）
     * 且**发起时的会话/批次仍有效**时才记账。旧批动作照样由调用方复制文件、
     * 更新图库，只是不往新批次记账。
     *
     * @param currentIds 提交这一刻的当前标识
     * @return 记账后的会话
     */
    fun onArchiveCommitted(
        session: RoundSessionRules.Session,
        archivedImage: ImageFile?,
        action: AlbumManager.PendingArchive,
        outcome: AlbumManager.ArchiveCommitOutcome,
        currentIds: SessionBatchIds,
        sourceKeyOf: (ImageFile) -> String = { SourceBalanceRules.sourceKeyOf(it) }
    ): RoundSessionRules.Session {
        if (!ArchiveIdentityRules.shouldRecord(outcome)) return session
        val image = archivedImage ?: return session
        return RoundSessionRules.recordIfStillCurrent(
            session = session,
            image = image,
            action = RoundSessionRules.HandledAction.ARCHIVE,
            ticket = action.intentTicket,
            currentIds = currentIds,
            sourceKeyOf = sourceKeyOf
        )
    }

    /**
     * 删除落库成功后的记账决策。
     *
     * @param pending 发起时绑定的 ticket 与照片
     * @param currentIds 落库这一刻的当前标识
     */
    fun onDeletePersisted(
        session: RoundSessionRules.Session,
        pending: Pending,
        currentIds: SessionBatchIds,
        sourceKeyOf: (ImageFile) -> String = { SourceBalanceRules.sourceKeyOf(it) }
    ): RoundSessionRules.Session =
        RoundSessionRules.recordIfStillCurrent(
            session = session,
            image = pending.image,
            action = RoundSessionRules.HandledAction.DELETE,
            ticket = pending.ticket,
            currentIds = currentIds,
            sourceKeyOf = sourceKeyOf
        )

    /**
     * 删除落库失败后的回退决策。
     *
     * 只有发起时那批仍然有效才回退：换批后旧动作的失败绝不能删掉新批次里
     * 同身份的那条成功记录——那不是它记的。
     */
    fun onDeleteFailed(
        session: RoundSessionRules.Session,
        pending: Pending,
        currentIds: SessionBatchIds
    ): RoundSessionRules.Session =
        RoundSessionRules.undoIfStillCurrent(
            session = session,
            image = pending.image,
            ticket = pending.ticket,
            currentIds = currentIds
        )
}
