package com.tabula.v3

import android.content.Context
import android.provider.MediaStore
import com.tabula.v3.data.repository.mediaIdentityKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * PARTIAL 选择集 A→B 两阶段设备验证的共享状态契约（包B：B1）。
 *
 * 真实系统机制（无 pm 权限夹具、无生产改动）：
 * - 夹具由**宿主编排**（run-cloud-ui-probe.sh）以纯 python 生成 PNG → adb
 *   push → scan_file 入库（CI run 42476083194/42477634711 实证：经
 *   UiAutomation.executeShellCommand 传字节（单命令/分块）均被设备 shell
 *   截断或吞没——宿主 adb push 是唯一可靠通道）。夹具**非应用自有**
 *   （行所有权 = shell）：scoped storage 下应用自有媒体无需任何权限即可见，
 *   会使选择集语义不可观测；shell 持有使"应用可见集合 = 严格用户选中集合"，
 *   哨兵成为真正的集合外媒体。
 * - 阶段A（PartialSelectionPhaseATest）：应用无授权（查询必须为空集）→
 *   onboarding 门控 → 系统权限对话框（"Select photos"/"Allow limited
 *   access"）→ 照片选择器点选 A 两格 → 确认 → READ_MEDIA_VISUAL_USER_
 *   SELECTED 真实授权，地面真值 = 应用内查询恰等于选择集合。
 * - 宿主 force-stop（保留应用数据，不 pm clear）。
 * - 阶段B（PartialSelectionPhaseBTest）：新进程读回预期状态并断言保留，
 *   经 ACTION_PICK_IMAGES 重选（取消 A 勾选、勾选 B 两格、确认）后再次以
 *   地面真值断言集合恰为 B，并核对 UI 与持久清理组/断点跟随与保留。
 *
 * 网格确定性：宿主按 S→A1→A2→B1→B2 顺序以 >1s 间隔推送（mtime 派生
 * date_taken 秒级互异；无 EXIF 时扫描以 mtime 派生拍摄时间——CI 轮2/4 的
 * desc "10:10 AM" 实证），选择器按时间倒序 = 网格 [B2, B1, A2, A1, S]；
 * 一切集合断言以地面真值收口：映射错误必然红灯，不静默通过。
 */
internal object PartialSelectionStateContract {
    const val STATE_FILE = "partial-selection-expected-state.json"
    const val FIXTURE_DIR = "Pictures/PartialSelectionFixtures/"
    const val FIXTURE_ABS_DIR = "/sdcard/Pictures/PartialSelectionFixtures"
    const val PICKER_MAX_LIMIT = 8

    /** 固定夹具名（宿主预清理保证无残留后推送；宿主生成顺序 = 时间序）。 */
    const val SENTINEL_NAME = "partial-sentinel.png"
    const val A1_NAME = "partial-a1.png"
    const val A2_NAME = "partial-a2.png"
    const val B1_NAME = "partial-b1.png"
    const val B2_NAME = "partial-b2.png"

    /** 夹具瓷片颜色（与宿主 PNG 生成一一对应；查看器/截屏诊断依据）。 */
    const val COLOR_SENTINEL = 0xFF888888.toInt()
    const val COLOR_A1 = 0xFFFF0000.toInt()
    const val COLOR_A2 = 0xFF00FF00.toInt()
    const val COLOR_B1 = 0xFFFFFF00.toInt()
    const val COLOR_B2 = 0xFF00FFFF.toInt()

    /** 网格位置 → 瓷片颜色（顺序 [B2,B1,A2,A1,S] = 宿主推送时间序的倒序）。 */
    val GRID_TILE_COLORS = listOf(COLOR_B2, COLOR_B1, COLOR_A2, COLOR_A1, COLOR_SENTINEL)

    fun stateFile(context: Context): File = File(
        context.getExternalFilesDir(null) ?: context.filesDir, STATE_FILE
    )

    /** shell 精确删除夹具（按名+路径），返回行是否已消失。 */
    fun shellDeleteFixture(name: String, shell: (String) -> String): Boolean {
        val query = shell(
            "content query --uri content://media/external/images/media " +
                "--projection _id --where \"_display_name='$name'\" 2>&1"
        )
        if ("Row: 0" !in query) return true
        shell(
            "content delete --uri content://media/external/images/media " +
                "--where \"_display_name='$name' AND relative_path='$FIXTURE_DIR'\" 2>&1"
        )
        val after = shell(
            "content query --uri content://media/external/images/media " +
                "--projection _id --where \"_display_name='$name'\" 2>&1"
        )
        return "Row: 0" !in after
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
        val calendar = Calendar.getInstance()
        for (row in rows) {
            calendar.timeInMillis = row.dateModified
            monthEntries.put(JSONObject().apply {
                put("identityKey", row.mediaIdentityKey())
                put("year", calendar.get(Calendar.YEAR))
                put("month", calendar.get(Calendar.MONTH) + 1)
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
