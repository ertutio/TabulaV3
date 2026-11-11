package com.tabula.v3

import android.content.Context
import android.provider.MediaStore
import com.tabula.v3.data.repository.mediaIdentityKey
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * T07 FULL→PARTIAL→FULL 三阶段设备验证的共享状态契约（B2 收尾单元）。
 *
 * 结构与 [FullRecoveryStateContract] 同形态（albumId/groupId/checkpointIndex/pid/
 * members/displayNames/monthAttribution），扩展成员全集与已处理集合字段。
 *
 * 媒体集合（判据"媒体集合与持久进度设计"）：6 张 **shell 身份**合成 PNG（宿主
 * run-cloud-ui-probe.sh 生成 + adb push + scan_file 入库——字节传输只走宿主 adb，
 * CI run 42476083194/42477634711 实证经 UiAutomation 传字节被截断）：
 * - G 组成员 g1、g2（生产写入口 seedCleanupGroup 成组，g1 将被部分处理）
 * - 非组成员 n1（FULL 下可见、PARTIAL(B) 下不在 B 集合）
 * - B 集合专有 b1、b2（PARTIAL 阶段才可读）
 * - 哨兵 s1（任何阶段都不进任何集合/选择集；FULL 下仅作存在性核对，不计入集合断言）
 *
 * 推送顺序 = mtime 升序（S→N1→G1→G2→B1→B2，相邻 >1.1s）→ 选择器网格按时间倒序 =
 * [B2, B1, G2, G1, N1, S1] → B 集合 = 网格索引 {0, 1}（#57 像素驱动原语）。
 * 全部夹具同落 2026-11（buildMonthInfos 单月 → seedCleanupGroup 的 `.single()` 成立）。
 *
 * 跨进程边界（#52 口径）：`pm revoke` 由系统强停应用；`pm grant` 不杀进程 → 宿主
 * 显式 force-stop 后由下一 instrumentation 重开。阶段间 files/ + shared_prefs 保留，
 * 仅阶段首 `pm clear` 清除。
 */
internal object FullPartialFullStateContract {
    const val STATE_FILE = "full-partial-full-expected-state.json"
    const val FIXTURE_DIR = "Pictures/FullPartialFullFixtures/"
    const val FIXTURE_ABS_DIR = "/sdcard/Pictures/FullPartialFullFixtures"

    /** 固定夹具名（宿主预清理保证无残留后推送；宿主推送顺序 = 时间序）。 */
    const val SENTINEL_NAME = "fpf-s1.png"
    const val N1_NAME = "fpf-n1.png"
    const val G1_NAME = "fpf-g1.png"
    const val G2_NAME = "fpf-g2.png"
    const val B1_NAME = "fpf-b1.png"
    const val B2_NAME = "fpf-b2.png"

    /** G 组成员（持久键序 = 断点索引语义：索引 1 = g2 待处理）。 */
    val GROUP_NAMES = listOf(G1_NAME, G2_NAME)

    /** 集合内 5 成员（哨兵除外；判据"可见恰={g1,g2,n1,b1,b2}"）。 */
    val MEMBER_NAMES = listOf(G1_NAME, G2_NAME, N1_NAME, B1_NAME, B2_NAME)

    val ALL_NAMES = listOf(SENTINEL_NAME) + MEMBER_NAMES
    val B_NAMES = listOf(B1_NAME, B2_NAME)

    /** 夹具瓷片颜色（与宿主 PNG 生成一一对应；查看器/截屏诊断依据）。 */
    const val COLOR_SENTINEL = 0xFF888888.toInt()
    const val COLOR_N1 = 0xFF0000FF.toInt()
    const val COLOR_G1 = 0xFFFF0000.toInt()
    const val COLOR_G2 = 0xFFFF00FF.toInt()
    const val COLOR_B1 = 0xFFFFFF00.toInt()
    const val COLOR_B2 = 0xFF00FFFF.toInt()

    /** 选择器网格位置 → 瓷片颜色（= 宿主推送时间序的倒序 [B2,B1,G2,G1,N1,S1]）。 */
    val GRID_TILE_COLORS = listOf(
        COLOR_B2, COLOR_B1, COLOR_G2, COLOR_G1, COLOR_N1, COLOR_SENTINEL
    )

    fun stateFile(context: Context): File = File(
        context.getExternalFilesDir(null) ?: context.filesDir, STATE_FILE
    )

    /**
     * 应用内可见 displayNames **列表**（查询序，不去重）——地面真值 + "无重复"
     * 核对来源（集合语义由调用方与去重集合对比落实）。
     */
    fun visibleDisplayNameList(context: Context): List<String> {
        val rows = mutableListOf<String>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null, null, null
        )!!.use { cursor ->
            while (cursor.moveToNext()) rows += cursor.getString(0)
        }
        return rows
    }

    /**
     * shell 身份行存在核对（双面核对之 shell 面；name + 相对路径精确匹配）。
     * 返回查询原文，由调用方断言（留证进入 INSTRUMENTATION 失败栈）。
     */
    fun shellRowQuery(name: String, shellScript: (String) -> String): String =
        shellScript(
            "content query --uri content://media/external/images/media " +
                "--projection _display_name:relative_path " +
                "--where \"_display_name='$name'\" 2>&1"
        )

    fun assertShellRowExists(name: String, shellScript: (String) -> String) {
        val output = shellRowQuery(name, shellScript)
        assertTrue(
            "shell 行核对失败：$name 必须在库（相对路径=$FIXTURE_DIR）：$output",
            output.contains("Row: 0") &&
                output.contains("_display_name=$name") &&
                output.contains("relative_path=${FIXTURE_DIR.trimEnd('/')}")
        )
    }

    /**
     * 写入预期状态（Phase1 末尾；FullRecoveryStateContract 形态 + 扩展字段；
     * 跨进程死亡持久，仅 pm clear 清除）。后续阶段经 put 增写 phase2Pid 等。
     */
    fun writeExpectedState(
        context: Context,
        albumId: String,
        groupId: String,
        checkpointIndex: Int,
        visibleFullSet: Set<String>
    ) {
        val groupRows = GROUP_NAMES.map { FullRecoveryStateContract.rebuildRow(context, it) }
        val calendar = Calendar.getInstance()
        val monthEntries = JSONArray()
        for (name in MEMBER_NAMES) {
            val row = FullRecoveryStateContract.rebuildRow(context, name)
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
            put("phase1Pid", android.os.Process.myPid())
            put("members", JSONArray(groupRows.map { it.mediaIdentityKey() }))
            put("displayNames", JSONArray(GROUP_NAMES))
            put("memberDisplayNames", JSONArray(MEMBER_NAMES))
            put("bDisplayNames", JSONArray(B_NAMES))
            put("sentinelName", SENTINEL_NAME)
            put("fixtureDir", FIXTURE_DIR)
            put("processedDisplayNames", JSONArray(listOf(G1_NAME)))
            put(
                "processedMembers",
                JSONArray(listOf(FullRecoveryStateContract.rebuildRow(context, G1_NAME).mediaIdentityKey()))
            )
            put("visibleFullSet", JSONArray(visibleFullSet.sorted()))
            put("monthAttribution", monthEntries)
        }
        stateFile(context).writeText(json.toString())
        check(stateFile(context).length() > 0)
    }

    fun readExpectedState(context: Context): JSONObject =
        JSONObject(stateFile(context).readText())

    /**
     * 显式依赖门（依赖纪律，coord/notes-B2.md §7.3）：上游阶段必须已成功落盘期望
     * 状态且记录了进程键，否则本阶段拒绝执行——把"依赖级联失败"（上游未落盘导致
     * 下游首行读文件即炸）与下游独立断言红分离，避免误诊。probe 侧配合依赖跳过
     * （上游红 → 跳过本阶段 instrumentation，"dependency-unmet" 日志，fpf_status
     * 保持 1）。
     */
    fun requireDependency(context: Context, pidKey: String, upstream: String) {
        val file = stateFile(context)
        check(file.exists() && file.length() > 0) {
            "依赖未满足：$upstream 未完成——期望状态文件不存在（本阶段拒绝执行）"
        }
        val pid = runCatching { readExpectedState(context).optInt(pidKey, -1) }
            .getOrElse { -1 }
        check(pid > 0) {
            "依赖未满足：$upstream 未完成——期望状态缺少有效 $pidKey（本阶段拒绝执行）"
        }
    }

    /** shell 精确删除夹具（按名+本套件路径），返回行是否已消失。 */
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
}
