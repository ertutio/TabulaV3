package com.tabula.v3

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import com.tabula.v3.data.repository.mediaIdentityKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * PARTIAL 选择集 A→B 两阶段设备验证的共享状态契约（包B：B1）。
 *
 * 真实系统机制（无 pm 权限夹具、无生产改动）：
 * - 阶段A（PartialSelectionPhaseATest）：应用无任何授权时先种子合成媒体
 *   （API 29+ 自有媒体贡献不需要存储权限，官方文档），经应用 onboarding
 *   门控按钮 → 系统权限对话框 "Select photos" → 照片选择器（grant 模式）
 *   点选 A 两格 → 确认 → READ_MEDIA_VISUAL_USER_SELECTED 真实授权，
 *   地面真值 = 应用内 MediaStore 查询恰等于选择集合。
 * - 宿主 force-stop（保留应用数据，不 pm clear）。
 * - 阶段B（PartialSelectionPhaseBTest）：新进程读回预期状态并断言保留，
 *   经 ACTION_PICK_IMAGES 重选（取消 A 勾选、勾选 B 两格、确认）后再次
 *   以地面真值断言集合恰为 B，并核对 UI（卡片/查看器/月份列表）与
 *   持久清理组/断点跟随与保留、集合外哨兵不受影响。
 *
 * 网格确定性：种子按 DATE_TAKEN 显式单调递增且发布顺序同向（真机探针实证
 * 选择器按时间倒序、最新在前）→ 网格顺序 [B2, B1, A2, A1, S]。一切集合
 * 断言以地面真值收口：映射错误必然红灯，不静默通过。
 */
internal object PartialSelectionStateContract {
    const val STATE_FILE = "partial-selection-expected-state.json"
    const val FIXTURE_DIR = "Pictures/PartialSelectionFixtures/"
    const val NAME_PREFIX = "partial-"
    const val PICKER_MAX_LIMIT = 8

    val COLOR_SENTINEL = 0xFF888888.toInt()
    val COLOR_A1 = 0xFFFF0000.toInt()
    val COLOR_A2 = 0xFF00FF00.toInt()
    val COLOR_B1 = 0xFFFFFF00.toInt()
    val COLOR_B2 = 0xFF00FFFF.toInt()

    fun stateFile(context: Context): File = File(
        context.getExternalFilesDir(null) ?: context.filesDir, STATE_FILE
    )

    /**
     * 无授权种子。DATE_TAKEN 显式写入（毫秒，过去时刻）→ 选择器排序确定性；
     * 相邻种子间 20ms 间隔仅保证 DATE_ADDED 秒级派生也单调（夹具卫生，
     * 不属断言轮询）。IS_PENDING 发布舞蹈与既有套件同款。
     *
     * 返回 (uri, displayName)：发布后未选中的自有行在无授权/部分授权下
     * 不可经应用内查询读回（选择集过滤器），display name 必须在种子时由
     * 本函数返回，不得事后查询。
     */
    fun seedPhoto(context: Context, tag: String, color: Int, dateTakenMs: Long): Pair<Uri, String> {
        val name = "$NAME_PREFIX$tag-${UUID.randomUUID()}.jpg"
        val item = requireNotNull(context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, FIXTURE_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.MediaColumns.DATE_TAKEN, dateTakenMs)
            })) { "own-media insert rejected before any grant (prerequisite broken)" }
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            context.contentResolver.openOutputStream(item)!!.use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it))
            }
        } finally {
            bitmap.recycle()
        }
        check(context.contentResolver.update(item, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null) == 1)
        return item to name
    }

    /** 应用内（选择集过滤后）查询全部可见 displayNames——地面真值来源。 */
    fun visibleDisplayNames(context: Context): Set<String> {
        val rows = mutableListOf<String>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null, null, null
        )!!.use { cursor ->
            while (cursor.moveToNext()) rows += cursor.getString(0)
        }
        return rows.toSet()
    }

    /** 写入预期状态（阶段A末尾；跨进程死亡持久，仅 pm clear 清除）。 */
    fun writeExpectedState(
        context: Context,
        albumId: String,
        groupId: String,
        aDisplayNames: List<String>,
        aMembers: List<String>,
        bDisplayNames: List<String>,
        sentinelName: String,
        checkpointIndex: Int
    ) {
        val rows = aDisplayNames.map { FullRecoveryStateContract.rebuildRow(context, it) }
        val monthEntries = org.json.JSONArray()
        val calendar = java.util.Calendar.getInstance()
        for (row in rows) {
            calendar.timeInMillis = row.dateModified
            monthEntries.put(JSONObject().apply {
                put("identityKey", row.mediaIdentityKey())
                put("year", calendar.get(java.util.Calendar.YEAR))
                put("month", calendar.get(java.util.Calendar.MONTH) + 1)
            })
        }
        val json = JSONObject().apply {
            put("albumId", albumId)
            put("groupId", groupId)
            put("checkpointIndex", checkpointIndex)
            put("phaseAPid", android.os.Process.myPid())
            put("displayNames", JSONArray(aDisplayNames))
            put("members", JSONArray(aMembers))
            put("bDisplayNames", JSONArray(bDisplayNames))
            put("sentinelName", sentinelName)
            put("fixtureDir", FIXTURE_DIR)
            put("monthAttribution", monthEntries)
        }
        stateFile(context).writeText(json.toString())
        check(stateFile(context).length() > 0)
    }

    fun readExpectedState(context: Context): JSONObject =
        JSONObject(stateFile(context).readText())
}
