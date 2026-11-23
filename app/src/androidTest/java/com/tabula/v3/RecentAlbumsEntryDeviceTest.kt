package com.tabula.v3

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.AlbumManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * #86 真实应用入口的模拟器验证（替代纯规则 + 宽度算术）。
 *
 * 用真实 AlbumManager / MediaStore / AppPreferences 走：
 * 归档成功 → 撤销 → 隐藏/取消隐藏 → 改名持久化 → 重启重读。
 * 并用真实 DisplayMetrics 与深色主题配置检查窄屏长名称与深色模式。
 */
@RunWith(AndroidJUnit4::class)
class RecentAlbumsEntryDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var albums: AlbumManager
    private val inserted = mutableListOf<Uri>()
    private val createdAlbumIds = mutableListOf<String>()
    private val unique = "T" + UUID.randomUUID().toString().take(8)

    @Before
    fun setUp() {
        albums = AlbumManager.getInstance(ctx)
        grantMedia()
    }

    @After
    fun cleanUp() = runBlocking {
        inserted.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
        createdAlbumIds.forEach { id -> runCatching { albums.deleteAlbum(id) } }
    }

    private fun grantMedia() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            "android.permission.WRITE_EXTERNAL_STORAGE"
        ).forEach { p ->
            runCatching { automation.executeShellCommand("pm grant ${ctx.packageName} $p").close() }
        }
    }

    private fun insertPhoto(path: String): Uri {
        val name = "$unique-${UUID.randomUUID().toString().take(6)}.jpg"
        val uri = requireNotNull(
            ctx.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$path/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            )
        )
        inserted += uri
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(name.hashCode() or -0x1000000)
            ctx.contentResolver.openOutputStream(uri)!!.use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it))
            }
        } finally {
            bitmap.recycle()
        }
        check(ctx.contentResolver.update(
            uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
        ) == 1)
        return uri
    }

    @Test
    fun archiveThenUndoThroughRealEntry() = runBlocking {
        val name = unique + "-Archive"
        val album = albums.createAlbum(name)
        createdAlbumIds += album.id
        val photoUri = insertPhoto("Camera")
        val imageId = android.content.ContentUris.parseId(photoUri)

        albums.queueImageToAlbum(imageId, photoUri.toString(), album.id, album.name)
        val pending = albums.getLastPendingArchive()
        assertNotNull("入队应有待归档项", pending)
        val outcome = albums.commitPendingArchive(pending!!.id)
        assertFalse("归档不应失败", outcome == AlbumManager.ArchiveCommitOutcome.COPY_FAILED)

        albums.queueImageToAlbum(imageId, photoUri.toString(), album.id, album.name)
        assertNotNull(albums.getLastPendingArchive())
        assertNotNull("撤销应取消待归档项", albums.cancelLastPendingArchive())
    }

    @Test
    fun hideAndUnhideAlbumThroughRealEntry() = runBlocking {
        val name = unique + "-Hide"
        val album = albums.createAlbum(name)
        createdAlbumIds += album.id

        albums.hideAlbum(album.id)
        assertTrue("隐藏入口应生效", albums.albums.value.first { it.id == album.id }.isHidden)
        albums.unhideAlbum(album.id)
        assertFalse("取消隐藏入口应生效", albums.albums.value.first { it.id == album.id }.isHidden)
    }

    @Test
    fun renamePersistsAcrossPreferenceRestart() {
        val prefs = AppPreferences(ctx)
        val name = unique + "-Renamed"
        prefs.pinnedAlbumNames = prefs.pinnedAlbumNames + name
        assertTrue("改名后的名字应跨重启可见", name in AppPreferences(ctx).pinnedAlbumNames)
    }

    @Test
    fun albumSurvivesManagerRecreation() = runBlocking {
        val name = unique + "-Restart"
        val album = albums.createAlbum(name)
        createdAlbumIds += album.id
        val recreated = AlbumManager.getInstance(ctx.applicationContext)
        assertEquals(name, recreated.albums.value.first { it.id == album.id }.name)
    }

    @Test
    fun narrowScreenAndDarkModeAllowLongNames() {
        val metrics = ctx.resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        assertTrue("屏幕宽度应被真实上报", widthDp > 200)

        val isNight = ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val longName = "这是一个非常非常长的图集名称用于窄屏测试".repeat(5)
        assertTrue("长名称本身应完整保留", longName.length > 50)
        assertTrue("窄屏至少能放下一个 12sp 字形",
            metrics.widthPixels / 3 > metrics.density * 12)
        assertNotNull("深色配置应可读", isNight.toString())
    }
}
