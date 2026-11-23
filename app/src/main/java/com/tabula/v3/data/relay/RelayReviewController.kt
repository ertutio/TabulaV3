package com.tabula.v3.data.relay

import com.tabula.v3.data.model.ImageFile

/**
 * 分享待清页面的状态机（#85 入口级 seam）。
 *
 * 页面的所有用户操作（撤销标记、手动指定原图、移入回收站）都走这里。
 * 测试通过"分享入口写入 → 状态机操作 → 存储读回"验证真实流程，
 * 而不是直接断言规则函数。
 */
class RelayReviewController(
    private val store: ShareRelayStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** 页面展示数据：只把当前图库里仍存在、且不在回收站的照片当原图。 */
    fun rows(library: List<ImageFile>): List<RelayReviewRules.ReviewRow> =
        RelayReviewRules.resolveRows(store.load(), library)

    /** 撤销标记：只删记录，不动照片。 */
    fun undo(mark: RelayMark) {
        RelayReviewRules.undoPlan(listOf(mark)).applyTo(store)
    }

    /** 手动确认待核对项：用户亲自指定一张图库照片，系统不猜。 */
    fun manualConfirm(mark: RelayMark, chosen: ImageFile) {
        // 先按原键（待核对项没有身份键时用 URI）移除旧记录，
        // 再写入带身份的已确认记录——否则一条分享会留下两条标记，
        // 页面里同一张图出现两次、撤销也只撤掉其中一条。
        store.removeByUri(listOf(mark.uriString))
        store.merge(listOf(RelayReviewRules.confirmManually(mark, chosen, clock())))
    }

    /**
     * 从待清标记移入回收站：返回应移入的照片身份键。
     * 真实写回收站由调用方（MainActivity）完成，成功后调 [onRecycled]。
     */
    fun recycle(mark: RelayMark): String? = mark.identityKey

    /** 回收站写入成功后清除对应标记，避免重复出现在待清里。 */
    fun onRecycled(identityKey: String) {
        store.remove(listOf(identityKey))
    }
}
