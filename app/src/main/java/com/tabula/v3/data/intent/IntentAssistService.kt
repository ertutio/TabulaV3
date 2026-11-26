package com.tabula.v3.data.intent

import android.content.Context

/**
 * 文字辅助可选的**服务实现**（#95 之后新增的第二个可选服务：OpenCode Go）。
 *
 * ## 为什么是枚举而不是字符串开关
 *
 * 服务决定三件必须一致的东西：**用哪个 Key 文件**、**请求发到哪里/带什么头**、
 * **缓存里算出的结果属于谁**。三者一旦分叉，就会出现「用 Go 的 Key 打 DeepSeek 的端点」
 * 或「复用另一个服务算出的判定」——都是静默的错误。把三件事绑在同一个枚举常量上，
 * 新增服务时编译器会逼着实现者把三处都填上。
 *
 * ## Key 是**每服务独立**的
 *
 * - [DEEPSEEK] 沿用 #95 的文件名 `intent-assist.key`，**已有用户升级后不丢 Key**；
 * - [OPENCODE_GO] 用新文件 `intent-assist.opencode-go.key`。
 *
 * 两个文件都放在 `noBackupFilesDir`，互不影响：换服务只换读哪个文件，
 * 不会清空、覆盖或迁移另一个服务的 Key。
 */
enum class IntentAssistService(
    /** 界面上显示的名字（与官方文档一致，不做本地化改写）。 */
    val displayName: String,
    /** 该服务 Key 的文件名（相对 `noBackupFilesDir`）。 */
    val keyFileName: String,
    /** Key 形态提示，仅用于输入框 placeholder，**不**参与任何校验。 */
    val keyHint: String,
    /**
     * 分类结果缓存里的服务标签。
     *
     * 必须进缓存键：同一个批次换成另一个服务后，「证据不足」的那些图会被重新判断，
     * 旧服务的判定不能拿来当新服务的结果。
     */
    val cacheTag: String
) {
    /** DeepSeek 直连（#95 已有实现）。 */
    DEEPSEEK(
        displayName = "DeepSeek",
        keyFileName = IntentAssistKeyStore.FILE_NAME,
        keyHint = "sk-…",
        cacheTag = "deepseek"
    ),

    /** OpenCode Go 订阅套餐（OpenAI 兼容，必须带 `x-opencode-session`）。 */
    OPENCODE_GO(
        displayName = "OpenCode Go",
        keyFileName = IntentAssistKeyStore.OPENCODE_GO_FILE_NAME,
        keyHint = "oc_sk_…",
        cacheTag = "opencode-go"
    );

    /** 本服务独立的 Key 存储（同一服务的两次调用指向同一个文件）。 */
    fun keyStore(context: Context): IntentAssistKeyStore = IntentAssistKeyStore(context, this)

    companion object {
        /** 默认服务：保持 #95 的行为，不改变任何已有用户的现状。 */
        val DEFAULT: IntentAssistService
            get() = DEEPSEEK

        /**
         * 从偏好里存的枚举名还原。
         *
         * 存的是 `name`（不是序号）：将来调整枚举顺序不会把用户选的服务换掉；
         * 读到空/未知值一律回默认，不留「未知服务」这种中间态。
         */
        fun fromStoredName(name: String?): IntentAssistService =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 可作废的文字辅助。
 *
 * [TextAssist] 只描述「怎么问」，不描述「怎么停」；而两个服务实现都需要
 * `invalidate()` / `rearm()`（关闭开关、换批、离开页面时停掉在途请求）。
 * 上层（`MainActivity`）持有一个 [TextAssist] 变量、需要统一调用这两个方法，
 * 因此把「可作废」提成接口，而不是让上层对两个具体类各写一次 `when`。
 */
interface InvalidatableTextAssist : TextAssist {
    /** 作废在途与后续请求：之后 [TextAssist.classify] 一律返回空/过期建议。 */
    fun invalidate()

    /** 重新允许（用户在新批次里又打开了开关时调用）。 */
    fun rearm()
}

/**
 * 切换服务时的**授权语义**：服务变了就是换了这次外发的对象，必须吊销本批授权。
 *
 * 为什么不能沿用同一个授权：用户同意的是「把脱敏文字发给 DeepSeek」，不是
 * 「发给任何一家」。换服务后他必须在界面上重新打开一次，这与 #95 第 3 轮审计里
 * 「换批要吊销授权」是同一条推理。
 *
 * 抽成纯函数而不是写在 `MainActivity` 里，是为了让单测能直接命中这条规则
 * （见 `IntentOpenCodeGoAssistTest.serviceSwitchRevokesBatchAuthorization`），
 * 不必启动 Activity。
 */
object IntentAssistServiceSwitch {

    /**
     * 若服务确实发生变化，则调用 [onRebuild] 吊销授权并返回 true。
     *
     * 调用方拿到 true 之后应继续「按关闭态重跑本地分析」；拿到 false 表示
     * 用户点的是当前服务，**什么都不该发生**（尤其不得推进授权代际，否则
     * 他自己的在途请求会被误判过期）。
     */
    fun revokeForServiceChange(
        current: IntentAssistService,
        next: IntentAssistService,
        onRebuild: () -> Unit
    ): Boolean {
        if (current == next) return false
        onRebuild()
        return true
    }
}
