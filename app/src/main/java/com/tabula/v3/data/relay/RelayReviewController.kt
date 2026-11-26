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

    /**
     * 手动确认待核对项（审计 #85：一次原子性的存储更新）。
     *
     * 在**同一次读取**得到的记录集合里移除待核对项并加入已确认项，
     * 然后只写一次。不再使用先 removeByUri、再 merge 的两步——
     * 两步流程在第二步失败时会丢失待核对项。
     *
     * 失败时存储原样保留（原记录和其他记录均可恢复），返回
     * [ShareRelayStore.WriteResult.Failed]；成功返回 Ok（携带写后的
     * 完整集合，即确认写入结果）。
     */
    fun manualConfirm(mark: RelayMark, chosen: ImageFile): ShareRelayStore.WriteResult {
        val updatedMark = RelayReviewRules.confirmManually(mark, chosen, clock())
        return store.replace(
            removeKey = mark.identityKey,
            removeUri = mark.uriString,
            add = updatedMark
        )
    }

    /** 回收站写入成功后清除对应标记，避免重复出现在待清里。 */
    fun onRecycled(identityKey: String): ShareRelayStore.WriteResult =
        store.remove(listOf(identityKey))
}
