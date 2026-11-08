package com.tabula.v3

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.tabula.v3.data.repository.mediaIdentityKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.util.UUID

/**
 * PARTIAL 选择集 A→B 两阶段设备验证的共享状态契约（包B：B1）。
 *
 * 真实系统机制（无 pm 权限夹具、无生产改动）：
 * - 阶段A（PartialSelectionPhaseATest）：应用无任何授权时经 **shell 身份**
 *   写入+扫描合成媒体（关键设计：夹具**非应用自有**——scoped storage 下
 *   应用自有媒体无需任何权限即可见，若夹具为应用自有，选择集 A→B 对应用
 *   查询不可观测；shell 持有使"应用可见集合 = 严格用户选中集合"，哨兵
 *   成为真正的集合外媒体）→ 应用 onboarding 门控 → 系统权限对话框
 *   "Select photos"/"Allow limited access" → 照片选择器（grant 模式）点选
 *   A 两格 → 确认 → READ_MEDIA_VISUAL_USER_SELECTED 真实授权，地面真值 =
 *   应用内 MediaStore 查询恰等于选择集合（授权前必须为空集）。
 * - 宿主 force-stop（保留应用数据，不 pm clear）。
 * - 阶段B（PartialSelectionPhaseBTest）：新进程读回预期状态并断言保留，
 *   经 ACTION_PICK_IMAGES 重选（取消 A 勾选、勾选 B 两格、确认）后再次以
 *   地面真值断言集合恰为 B，并核对 UI（卡片/查看器/月份列表）与持久
 *   清理组/断点跟随与保留、集合外哨兵不受影响。
 *
 * 网格确定性：shell 写入时以 EXIF DateTimeOriginal 显式写入拍摄时间（每张
 * 间隔 ≥500s，显示分钟互异；EXIF 写入失败回退 mtime 单调——写入间隔 >1s
 * 保证秒级互异），选择器按拍摄时间倒序 = 网格 [B2, B1, A2, A1, S]；一切
 * 集合断言以地面真值收口：映射错误必然红灯，不静默通过。
 */
internal object PartialSelectionStateContract {
    const val STATE_FILE = "partial-selection-expected-state.json"
    const val FIXTURE_DIR = "Pictures/PartialSelectionFixtures/"
    const val FIXTURE_ABS_DIR = "/storage/emulated/0/Pictures/PartialSelectionFixtures"
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
     * shell 身份种子：JPEG 字节（应用进程生成，EXIF 拍摄时间显式写入）经
     * base64 分块 shell 脚本落到公共存储后 scan_file 入库——行所有权 = shell，
     * 应用无授权且未选中时不可见（scoped storage 语义）。
     *
     * CI run 42476083194 实证：单命令携带 ~5KB base64 时 shell 输出为空
     * （命令截断/吞没）——改为 1.2KB 分块追加到 /data/local/tmp 暂存后一次
     * 解码拷贝；每步带标记与错误回显。96x96 JPEG 足够网格/查看器渲染。
     *
     * 返回 display name（生成时已知；行未选中前应用内查询不可读回）。
     * 相邻种子 shell 写入间隔 >1.1s 仅保证 mtime 派生也单调（夹具卫生，
     * 不属断言轮询）。
     */
    fun seedShellPhoto(
        context: Context,
        tag: String,
        color: Int,
        dateTakenMs: Long,
        shell: (String) -> String
    ): String {
        val name = "$NAME_PREFIX$tag-${UUID.randomUUID()}.jpg"
        val bytes = jpegBytesWithExifDateTimeOriginal(context, color, dateTakenMs)
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val staging = "/data/local/tmp/partial-seed-${System.nanoTime()}.b64"
        // 分块追加（1.2KB/命令），规避单命令长度截断
        val chunkSize = 1200
        var offset = 0
        var chunk = 0
        while (offset < base64.length) {
            val end = minOf(offset + chunkSize, base64.length)
            val part = base64.substring(offset, end)
            val op = if (chunk == 0) ">" else ">>"
            val out = shell(
                "printf %s $part $op $staging; echo __CHUNK_$chunk=\$?"
            )
            check("__CHUNK_${chunk}=0" in out) {
                "shell chunk $chunk write failed (len=${part.length}): $out"
            }
            offset = end
            chunk++
        }
        val decode = shell(
            "base64 -d $staging > /data/local/tmp/partial-seed.jpg && " +
                "mkdir -p $FIXTURE_ABS_DIR && " +
                "cp /data/local/tmp/partial-seed.jpg $FIXTURE_ABS_DIR/$name && " +
                "rm -f $staging /data/local/tmp/partial-seed.jpg 2>/dev/null; " +
                "ls -l $FIXTURE_ABS_DIR/$name 2>&1; echo __COPY=\$?"
        )
        check("__COPY=0" in decode && "__COPY" in decode) {
            "shell copy/decode failed: $decode"
        }
        val scan = shell(
            "content call --uri content://media/ --method scan_file " +
                "--arg $FIXTURE_ABS_DIR/$name 2>&1; echo __SCAN_DONE"
        )
        check("__SCAN_DONE" in scan) { "scan_file failed: $scan" }
        val row = shell(
            "content query --uri content://media/external/images/media " +
                "--projection _id --where \"_display_name='$name'\" 2>&1"
        )
        check("Row: 0" in row) { "scanned fixture row missing: $name ($row)" }
        return name
    }

    private fun jpegBytesWithExifDateTimeOriginal(
        context: Context,
        color: Int,
        dateTakenMs: Long
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val plain = ByteArrayOutputStream()
        try {
            bitmap.eraseColor(color)
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, plain))
        } finally {
            bitmap.recycle()
        }
        // EXIF 拍摄时间（yyyy:MM:dd HH:mm:ss）：选择器排序与 desc 的确定性来源。
        // saveAttributes 失败不致命（回退 mtime 单调，见 seedShellPhoto 注释）。
        val temp = File(context.cacheDir, "partial-seed-${System.nanoTime()}.jpg")
        try {
            temp.writeBytes(plain.toByteArray())
            val calendar = Calendar.getInstance().apply { timeInMillis = dateTakenMs }
            val exif = ExifInterface(temp.path)
            exif.setAttribute(
                ExifInterface.TAG_DATETIME_ORIGINAL,
                "%04d:%02d:%02d %02d:%02d:%02d".format(
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH) + 1,
                    calendar.get(Calendar.DAY_OF_MONTH),
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    calendar.get(Calendar.SECOND)
                )
            )
            exif.saveAttributes()
            return temp.readBytes()
        } catch (e: Exception) {
            return plain.toByteArray()
        } finally {
            temp.delete()
        }
    }

    /** shell 精确删除夹具（按名+路径），返回是否删除成功且行已消失。 */
    fun shellDeleteFixture(name: String, shell: (String) -> String): Boolean {
        val path = FIXTURE_DIR
        val query = shell(
            "content query --uri content://media/external/images/media " +
                "--projection _id --where \"_display_name='$name'\""
        )
        if ("Row: 0" !in query) return true
        shell(
            "content delete --uri content://media/external/images/media " +
                "--where \"_display_name='$name' AND relative_path='$path'\""
        )
        val after = shell(
            "content query --uri content://media/external/images/media " +
                "--projection _id --where \"_display_name='$name'\""
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

    /** 阶段A 排序辅助：EXIF 拍摄时间（毫秒，过去时刻，互异分钟）。 */
    fun dateTakenNowMinus(offsetSec: Long): Long =
        System.currentTimeMillis() - java.util.concurrent.TimeUnit.SECONDS.toMillis(offsetSec)
}
