package com.tabula.v3

import com.tabula.v3.BuildConfig

/**
 * 连拍选片提交的受控写入结果（#87 四轮，仅 DEBUG）。
 *
 * ## 为什么需要
 *
 * 「部分回收后成功项从选片列表消失、失败项可重试、页面不提前退出」这条行为
 * 发生在**接线层**：规则函数与 `BurstCommit` 本身都是对的，缺陷在
 * `MainActivity` 如何把提交结果反馈给页面。只看纯逻辑永远测不出来，
 * 必须在真实选片页上制造一次"部分写入成功"。
 *
 * ## 怎么制造
 *
 * 真实 `RecycleBinManager.addAllToRecycleBin` 要么全成、要么抛错，
 * 很难稳定制造"只成功一半"。这里给一个**只影响写入条数**的开关：
 * 打开后，提交前把待回收列表截断到 `keepOnly` 张——于是生产代码自己
 * 走完"写一部分 → 读回对账 → 判定 isPartial"的完整真实路径，
 * 而不是绕过它伪造一个 CommitResult。
 *
 * 隔离口径与 [PendingDeleteTestHook] 相同：Release 构建下 [limitFor] 恒为
 * null，生产行为零变化。
 */
object BurstCommitTestHook {
    private const val TAG = "BurstCommitHook"

    @Volatile
    private var limit: Int? = null

    /** 仅 DEBUG 生效：让下一次提交只真正写入前 [count] 张待回收。 */
    fun armPartialWrite(count: Int) {
        if (!BuildConfig.DEBUG) return
        limit = count
        android.util.Log.i(TAG, "armed: next commit writes at most $count")
    }

    /** 取用一次（取完即清），返回 null 表示不干预。 */
    fun consumeLimit(): Int? {
        if (!BuildConfig.DEBUG) return null
        val current = limit ?: return null
        limit = null
        android.util.Log.i(TAG, "consumed: limiting to $current")
        return current
    }

    fun reset() {
        limit = null
    }
}
