package com.tabula.v3

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.AlbumCleanupEngine
import com.tabula.v3.data.repository.SimilarGroup
import com.tabula.v3.data.repository.mediaIdentityKey
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * FULL 恢复两阶段设备验证的共享状态契约（审计轮：工作包 2）。
 *
 * 阶段一（FullRecoveryPhase1Test）建立真实清理组与断点并把预期状态持久化到
 * 应用 files 目录（跨进程死亡与权限变化持久；仅 `pm clear` 会清除）；
 * 宿主在阶段间执行 `pm revoke`（应用被系统强停）→ 缺席断言 → `pm grant`
 * （恢复 FULL）→ `am start`（重开）；阶段二（FullRecoveryContinuationTest）
 * 读取预期状态并断言续接。
 */
internal object FullRecoveryStateContract {
    const val ALBUM_ID = "fullrec-device"
    const val STATE_FILE = "full-recovery-expected-state.json"
    const val SEED_PREFIX = "fullrec-"

    fun stateFile(context: Context): File = File(
        context.getExternalFilesDir(null) ?: context.filesDir, STATE_FILE
    )

    fun seedPhoto(context: Context, index: Int): Uri {
        val name = "$SEED_PREFIX-${UUID.randomUUID()}-$index.jpg"
        val item = requireNotNull(context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/FullRecoveryFixtures/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor((index * 37) or -0x1000000)
            context.contentResolver.openOutputStream(item)!!.use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it))
            }
        } finally {
            bitmap.recycle()
        }
        check(context.contentResolver.update(item, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null) == 1)
        return item
    }

    /** 从 MediaStore 按显示名重建行对象（跨阶段的身份与最新元数据）。 */
    fun rebuildRow(context: Context, displayName: String): ImageFile {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.VOLUME_NAME
            ),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(displayName),
            null
        )!!.use { cursor ->
            check(cursor.moveToFirst()) { "seeded media missing: $displayName" }
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
            val dm = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED))
            val volume = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME))
                ?: "external_primary"
            val relativePath = cursor.getString(
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            ) ?: "Pictures/FullRecoveryFixtures/"
            return ImageFile(
                id = id,
                uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.getContentUri(volume), id
                ),
                displayName = displayName,
                dateModified = dm * 1000,
                size = 1L,
                width = 64,
                height = 64,
                bucketDisplayName = "FullRecoveryFixtures",
                volumeName = volume,
                relativePath = relativePath
            )
        }
    }

    /** 以单元测试同款写入口种子持久化清理组（新格式身份键成员）。 */
    fun seedCleanupGroup(context: Context, rows: List<ImageFile>, groupId: String = groupIdOf(rows)): String {
        val preferences = AppPreferences.getInstance(context)
        val groupId = groupIdOf(rows)
        preferences.saveAlbumAnalysisResult(ALBUM_ID, 1, listOf(groupId))
        preferences.saveGroupImageCounts(ALBUM_ID, mapOf(groupId to rows.size))
        preferences.saveAlbumTotalImages(ALBUM_ID, rows.size)
        preferences.saveGroupsImageIds(
            ALBUM_ID,
            mapOf(groupId to rows.map { it.mediaIdentityKey() })
        )
        return groupId
    }

    fun writeExpectedState(
        context: Context,
        groupId: String,
        rows: List<ImageFile>,
        checkpointIndex: Int
    ) {
        val calendar = java.util.Calendar.getInstance()
        val monthEntries = org.json.JSONArray()
        for (row in rows) {
            calendar.timeInMillis = row.dateModified
            monthEntries.put(org.json.JSONObject().apply {
                put("identityKey", row.mediaIdentityKey())
                put("year", calendar.get(java.util.Calendar.YEAR))
                put("month", calendar.get(java.util.Calendar.MONTH) + 1)
            })
        }
        val json = JSONObject().apply {
            put("albumId", ALBUM_ID)
            put("groupId", groupId)
            put("checkpointIndex", checkpointIndex)
            put("members", org.json.JSONArray(rows.map { it.mediaIdentityKey() }))
            put("displayNames", org.json.JSONArray(rows.map { it.displayName }))
            put("monthAttribution", monthEntries)
        }
        stateFile(context).writeText(json.toString())
    }

    fun readExpectedState(context: Context): JSONObject =
        JSONObject(stateFile(context).readText())

    /** 组 ID 计算（与单元测试同口径：成员身份派生）。 */
    fun groupIdOf(images: List<ImageFile>): String =
        SimilarGroup(
            images = images,
            startTime = images.minOf { it.dateModified },
            endTime = images.maxOf { it.dateModified }
        ).id

    /**
     * 明确完成信号：commit() 同步落盘 tabula_album_cleanup 文件的所有
     * pending apply() 写入。不手写任何生产键——仅添加一个测试哨兵键触发
     * 同步提交。
     *
     * 耐久性边界：仅保证本文件的写入落盘（覆盖组/成员/断点的全部生产键）；
     * 其他 prefs 文件与 files/ 目录的写入不在此信号范围。
     */
    fun flushCleanupPrefs(context: Context): Boolean {
        // commit() 返回值必须为 true（同步落盘成功）；false 表示落盘失败
        return context.getSharedPreferences("tabula_album_cleanup", Context.MODE_PRIVATE)
            .edit().putLong("_test_flush_signal", System.currentTimeMillis()).commit()
    }

    fun cleanupEngine(context: Context): AlbumCleanupEngine = AlbumCleanupEngine(context)
}
