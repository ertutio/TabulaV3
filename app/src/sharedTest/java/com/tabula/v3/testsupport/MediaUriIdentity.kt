package com.tabula.v3.testsupport

/**
 * 把 MediaStore 的 content uri 归一成"卷 + 行 id"身份，用于**测试夹具对账**。
 *
 * ## 为什么需要归一
 *
 * run 45372299838 上 `deckReloadsBatchAfterStrategySwitch` 红在
 * "切换前页面持有的批次必须全部是本测试造的照片"，多余项清一色是
 * `content://media/external_primary/images/media/84` 这种形式。
 *
 * 原因是**同一个卷有两个合法的 uri 写法**：
 *
 * - 夹具写入用 `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`
 *   → 拿到 `content://media/external/images/media/84`（聚合外部卷）；
 * - 生产代码读 `VOLUME_NAME` 列后按卷限定地址重建
 *   （`LocalImageRepository.imageItemUri`）
 *   → 拿到 `content://media/external_primary/images/media/84`（主外部卷）。
 *
 * `external` 是"所有外部卷的聚合"，`external_primary` 是其中唯一的那个主卷。
 * 单外部卷设备（模拟器、绝大多数手机）上这两条 uri 指向**同一行**，直接比
 * 字符串必然不相等——那是断言自己写错，不是实现缺陷。
 *
 * 所以对账比的是 `(卷, id)`，而不是 uri 字符串。归一之后：
 *
 * - `external` 与 `external_primary` 视为同一卷；
 * - 不同卷的同数值 id 仍然**不相等**（T03 的跨卷身份规则不能被这里抹掉）。
 *
 * 这套规则放在 sharedTest，由 JVM 单测 `MediaUriIdentityTest` 钉住——不用
 * 等 5 分钟模拟器才知道自己写错了。
 */
object MediaUriIdentity {

    /** 聚合外部卷：`MediaStore.VOLUME_EXTERNAL`。 */
    private const val AGGREGATE_EXTERNAL = "external"

    /** 主外部卷：`MediaStore.VOLUME_EXTERNAL_PRIMARY`。 */
    private const val PRIMARY_EXTERNAL = "external_primary"

    /** `content://media/<volume>/images/media/<id>`；只认图片卷，别的都算不相关。 */
    private val IMAGE_URI = Regex("""^content://media/([^/]+)/images/media/(\d+)$""")

    /**
     * 归一身份 `"<卷>#<id>"`；不是图片 content uri 时返回 null。
     *
     * 返回 null 的项在对账时**不能**被当成"匹配"——调用方要么排除，要么
     * 判失败。这里只负责给出可比的身份，不做任何宽容。
     */
    fun canonical(uri: String): String? {
        val match = IMAGE_URI.find(uri.trim()) ?: return null
        val volume = match.groupValues[1].let {
            if (it == AGGREGATE_EXTERNAL) PRIMARY_EXTERNAL else it
        }
        return "$volume#${match.groupValues[2]}"
    }

    /** 一组 uri 的归一身份；无法解析的项被丢弃（调用方另判非空）。 */
    fun canonicalSet(uris: Iterable<String>): Set<String> =
        uris.mapNotNull { canonical(it) }.toSet()

    /** 一组 uri 里无法解析成图片 content uri 的原始串，用于失败信息。 */
    fun unparsable(uris: Iterable<String>): List<String> =
        uris.filter { canonical(it) == null }
}
