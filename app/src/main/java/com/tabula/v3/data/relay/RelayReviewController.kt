package com.tabula.v3.data.relay

import com.tabula.v3.data.model.ImageFile

/**
 * 分享待清页面的状态机（#85 入口级 seam）。
 *
 * 页面的所有用户操作（撤销标记、手动指定原图、移入回收站）都走这里。
 * 测试通过"分享入口写入 → 状态机操作 → 存储读回"验证真实流程。
 *
 * 审计 #85：所有读后写返回 [ShareRelayStore.WriteResult]；读或写失败时
 * 调用方必须如实提示，且存储原样保留（不丢其他标记）。
 */
class RelayReviewController(
    private val store: ShareRelayStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** 页面展示数据：只把当前图库里仍存在、且不在回收站的照片当原图。 */
    fun rows(library: List<ImageFile>): List<RelayReviewRules.ReviewRow> =
        RelayReviewRules.resolveRows(store.load(), library)

    /** 撤销标记：只删记录，不动照片。 */
    fun undo(mark: RelayMark): ShareRelayStore.WriteResult =
        RelayReviewRules.undoPlan(listOf(mark)).applyTo(store)

    /** 手动确认待核对项：用户亲自指定一张图库照片，系统不猜。 */
    fun manualConfirm(mark: RelayMark, chosen: ImageFile): ShareRelayStore.WriteResult {
        // 先按原键（待核对项没有身份键时用 URI）移除旧记录，
        // 再写入带身份的已确认记录——否则一条分享会留下两条标记。
        val removal = store.removeByUri(listOf(mark.uriString))
        if (removal is ShareRelayStore.WriteResult.Failed) return removal
        return store.merge(listOf(RelayReviewRules.confirmManually(mark, chosen, clock())))
    }

    /** 回收站写入成功后清除对应标记，避免重复出现在待清里。 */
    fun onRecycled(identityKey: String): ShareRelayStore.WriteResult =
        store.remove(listOf(identityKey))
}
