package com.tabula.v3.data.intent

/**
 * **能力边界**：文字辅助与图片辅助是两种在授权、数据面、失败语义上都不同的能力。
 *
 * 本文件只声明契约，**不含任何实现**，本轮也不被任何生产路径调用。
 * 之所以先把它写下来：`TextAssist` 与将来的图片辅助长得很像（都是「给点东西、
 * 要一个分类建议」），最容易被顺手合并成一个接口——而那正是授权被悄悄放大的路径。
 * 把边界写在类型系统里，比写在文档里更难绕开。
 *
 * ## 两条能力的分界
 *
 * | | 文字辅助（本轮可用） | 图片辅助（本轮不可用） |
 * | --- | --- | --- |
 * | 数据面 | 已脱敏的 OCR 文字片段 | 图片字节或编码 |
 * | 授权单位 | **按批**（用户在当前批次主动开启） | **逐图**（用户单独确认发哪几张） |
 * | 授权能否继承 | — | **不能**继承文字授权、Key 或服务选择 |
 * | 本轮状态 | 默认关闭，可选开启 | **不实现**：无读取、无编码、无上传、无界面开关、无网络调用 |
 *
 * ## 对现有 `TextAssist` 的约束（已由代码保证，不由注释保证）
 *
 * - `TextAssist.classify` 只接受 [IntentTextSanitizer.SanitizedSnippet]；
 * - 该类型**结构上没有**图片字节、`Uri`、路径或文件名的槽位；
 * - `DeepSeekTextAssist.buildPayload` 只把 `snippet.lines` 拼进 `messages`，
 *   请求体字段白名单是 `model / temperature / max_tokens / messages`；
 * - 因此「文字客户端被塞进图片」不是靠 review 拦的，而是在类型与请求体构造处就写不出来。
 *
 * ## 图片能力将来启用时必须满足（本条不是本轮实现范围）
 *
 * 1. **独立授权**：用户必须逐图确认「发送这一张」与「发给哪个服务」；
 *    现有的按批文字授权、已保存的 Key、已选的服务**都不能**自动授予图片外发权限；
 * 2. **独立开关**：不能复用文字辅助那个「实验性」开关；
 * 3. **独立失败语义**：图片外发失败必须回到本地 OCR 结果，不得影响文字辅助路径；
 * 4. **范围与传输方式**留到图片能力的实现 PR 单独审计。
 */
object IntentAssistCapability {

    /** 本轮可用的能力。 */
    enum class Kind {
        /** 已脱敏 OCR 文字的按批辅助。本轮可用，默认关闭。 */
        TEXT_ONLY,

        /** 图片辅助。**本轮不可用**：没有实现、没有入口、没有网络路径。 */
        IMAGE
    }

    /**
     * 当前版本实际可用的能力集合。
     *
     * 刻意是常量而不是可配置项：如果它能被运行期打开，就等于给了一条绕过
     * 「图片需要独立授权」的后门。
     */
    val available: Set<Kind> = setOf(Kind.TEXT_ONLY)

    /** 图片能力本轮不可用。UI 与调用方都读这里，不要各自硬编码。 */
    val imageAssistAvailable: Boolean get() = Kind.IMAGE in available

    /**
     * 图片能力的授权契约（**尚未实现**，仅作为将来实现必须遵守的签名）。
     *
     * 这里给出接口形状而不是空实现，是为了让「图片授权必须逐图、且独立于文字授权」
     * 这件事在类型上就成立：入参是**明确的单张图选择**，不是批次、不是布尔开关。
     *
     * 将来实现时：
     * - 实现方自行负责把用户的选择落成一次明确确认；
     * - 不得从 [IntentAssistAuthorization] 或任何 Key 推导出授权；
     * - 失败必须回到本地结果。
     */
    interface ImageAssistGrant {
        /** 用户是否已对**这一张**图、**这一个服务**做出过明确的外发确认。 */
        fun isGrantedFor(imageIdentity: String, serviceId: String): Boolean

        /**
         * 请求授权。返回 false 表示用户拒绝或未确认。
         *
         * 实现必须能被用户随时撤销；撤销后不得继续任何在途外发。
         */
        suspend fun requestGrant(imageIdentity: String, serviceId: String): Boolean
    }

    /** 本轮没有图片授权实现——任何调用方拿到 null 就必须按「不可用」处理。 */
    val imageGrant: ImageAssistGrant? = null
}
