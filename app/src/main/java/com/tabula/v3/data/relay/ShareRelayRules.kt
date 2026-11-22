package com.tabula.v3.data.relay

import android.net.Uri
import com.tabula.v3.data.model.MediaItemKey
import com.tabula.v3.data.model.mediaItemKeyFromUri

/**
 * 系统分享中转的收到项（路线图第 2 项）。
 *
 * 语义：用户在截图预览或图库里点分享、在系统面板里选了 Tabula，这一项就是
 * Tabula 收到的那张图。Tabula 顺手把它记成"待清"，再把同一个 URI 转交给
 * 用户真正要发的应用（微信等）。**标记表示"经过中转"，不表示对方收到了。**
 */
data class RelayItem(
    /** 收到的原始内容 URI 字符串（原样保留，用于转交与回溯） */
    val uriString: String,
    /** 系统报告的 MIME 类型；null 或非图片类型视为不合格 */
    val mimeType: String?,
    /** 系统给的可读名；null 表示没给 */
    val displayName: String?
) {
    val uri: Uri get() = Uri.parse(uriString)
}

/**
 * 一张收到项的落库状态。
 *
 * - [CONFIRMED]：能唯一定位到本地图库里的原图，标记与照片身份一起保存。
 * - [PENDING_REVIEW]：定位不到唯一原图（临时 URI、documents provider、
 *   聚合卷、被裁剪过的副本……）。**照样转发**，标记留待用户核对。
 * - [REJECTED]：不是图片、URI 不可读、指向 Tabula 自己——不标记、不转发。
 */
enum class RelayMarkState { CONFIRMED, PENDING_REVIEW, REJECTED }

/**
 * 一条落库的中转标记。
 *
 * @param identityKey 照片身份键 `卷|ID`；仅 [RelayMarkState.CONFIRMED] 非空
 * @param uriString 收到时的 URI（待核对项靠它再找回原图）
 * @param displayName 系统给的可读名，用于核对界面展示
 * @param markedAt 标记时间（毫秒）
 */
data class RelayMark(
    val identityKey: String?,
    val uriString: String,
    val displayName: String?,
    val markedAt: Long,
    val state: RelayMarkState
) {
    val isConfirmed: Boolean get() = state == RelayMarkState.CONFIRMED
    val needsReview: Boolean get() = state == RelayMarkState.PENDING_REVIEW
}

/** 一次分享里所有收到项的判定结果，可直接喂给存储与转交。 */
data class RelayResolution(
    val marks: List<RelayMark>,
    /** 真正要转交给下游应用的 URI（已去重、已排除不合格项） */
    val forwardUris: List<Uri>,
    /** 被拒项的说明，用于日志/调试，不进界面主流程 */
    val rejections: List<String>
) {
    val confirmedCount: Int get() = marks.count { it.state == RelayMarkState.CONFIRMED }
    val reviewCount: Int get() = marks.count { it.state == RelayMarkState.PENDING_REVIEW }

    /** 没有任何可转发项时，调用方应当直接结束，不要弹空的选择器。 */
    val hasForwardTargets: Boolean get() = forwardUris.isNotEmpty()
}

/**
 * 系统分享中转的判定规则（纯逻辑，无 Android 运行时依赖，可 JVM 单测）。
 *
 * 判定顺序：
 * 1. MIME 必须是 `image/` 开头（或缺失时按扩展名推断）；否则拒收。
 *    这里不猜"可能是图片"，因为猜错会把视频/文档塞进照片清理流程。
 * 2. URI 必须能被 [mediaItemKeyFromUri] 解析出**具体卷 + ID**，且该身份
 *    确实在本地图库里存在 → CONFIRMED。聚合卷 `external`、documents
 *    provider、临时缓存 URI 都过不了这一关，落入下一步。
 * 3. 其余合格但定位不到的 → PENDING_REVIEW。仍转发，标记留待核对。
 * 4. 指向 Tabula 自己（包名相同或路径在 `Pictures/Tabula/` 下）→ 拒收，
 *    避免用户把已经归档的照片又送回中转口形成循环。
 *
 * 去重口径：同一 `卷|ID` 只保留一条（重复分享同一张不产生两条待清）；
 * 待核对项按 URI 字符串去重。
 */
object ShareRelayRules {

    /**
     * @param items 系统交过来的收到项
     * @param localIdentityKeys 当前图库里所有照片的身份键（`卷|ID`）
     * @param nowMillis 标记时间
     */
    fun resolve(
        items: List<RelayItem>,
        localIdentityKeys: Set<String>,
        nowMillis: Long
    ): RelayResolution {
        val marks = mutableListOf<RelayMark>()
        val forward = mutableListOf<Uri>()
        val rejections = mutableListOf<String>()
        val seenForward = mutableSetOf<String>()
        val seenIdentity = mutableSetOf<String>()

        items.forEach { item ->
            val rejection = rejectionReason(item)
            if (rejection != null) {
                rejections += "$rejection: ${item.uriString}"
                return@forEach
            }

            val key = mediaItemKeyFromUri(item.uri)
            val identityKey = key?.let { identityKeyOf(it) }
            val localized = identityKey != null && identityKey in localIdentityKeys

            if (localized && identityKey != null) {
                // 同一张照片多次分享只留一条标记，避免待清列表出现重复行
                if (seenIdentity.add(identityKey)) {
                    marks += RelayMark(
                        identityKey = identityKey,
                        uriString = item.uriString,
                        displayName = item.displayName,
                        markedAt = nowMillis,
                        state = RelayMarkState.CONFIRMED
                    )
                }
            } else {
                val dedupeKey = "unresolved|${item.uriString}"
                if (seenIdentity.add(dedupeKey)) {
                    marks += RelayMark(
                        identityKey = null,
                        uriString = item.uriString,
                        displayName = item.displayName,
                        markedAt = nowMillis,
                        state = RelayMarkState.PENDING_REVIEW
                    )
                }
            }

            // 转发不看定位成功与否：定位失败也要把用户想发的东西送出去。
            if (seenForward.add(item.uriString)) {
                forward += item.uri
            }
        }

        return RelayResolution(
            marks = marks,
            forwardUris = forward,
            rejections = rejections
        )
    }

    /** `卷|ID` 身份键，与暂存清理、回收站同口径。 */
    fun identityKeyOf(key: MediaItemKey): String = "${key.volumeName}|${key.id}"

    /**
     * 拒收理由；合格返回 null。
     *
     * MIME 缺失时按扩展名兜底——有些分享方只给文件名不给类型，直接拒掉
     * 会让正常的截图分享失败。
     */
    fun rejectionReason(item: RelayItem): String? {
        val scheme = item.uri.scheme
        if (scheme != "content" && scheme != "file") return "unsupported scheme"
        if (!isImageMime(item.mimeType, item.displayName)) return "not an image"
        return null
    }

    fun isImageMime(mimeType: String?, displayName: String?): Boolean {
        if (mimeType != null && mimeType.startsWith("image/", ignoreCase = true)) return true
        // 明确给了非图片类型（video/*、application/pdf）就信它，不靠扩展名翻案
        if (mimeType != null && mimeType.contains('/')) return false
        val extension = displayName?.substringAfterLast('.', "")?.lowercase() ?: return false
        return extension in IMAGE_EXTENSIONS
    }

    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "avif", "dng"
    )
}

/**
 * 把多个收到项拼成一次转发用的 ClipData（纯逻辑，便于断言"授权是否随
 * ClipData 一起转交"）。
 *
 * 每个条目都带上 `content://` URI 本身；下游应用读到的权限来自转发
 * Intent 上的 `FLAG_GRANT_READ_URI_PERMISSION`。
 */
fun buildRelayClipData(uris: List<Uri>, labels: List<String?>): android.content.ClipData? {
    if (uris.isEmpty()) return null
    val first = uris.first()
    val clip = android.content.ClipData.newRawUri(labels.firstOrNull() ?: "image", first)
    uris.drop(1).forEachIndexed { index, uri ->
        clip.addItem(android.content.ClipData.Item(uri))
        // labels 仅用于可读性，不足时忽略
        labels.getOrNull(index + 1)
    }
    return clip
}
