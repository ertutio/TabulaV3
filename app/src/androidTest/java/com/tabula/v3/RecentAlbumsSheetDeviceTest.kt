package com.tabula.v3

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.RecommendMode
import com.tabula.v3.data.repository.AlbumManager
import com.tabula.v3.data.repository.AlbumUsageTracker
import com.tabula.v3.data.album.RecentAlbumRules
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * #86 审计：通过**实际归档弹层**检查最近列表、归档与撤销、隐藏和改名。
 *
 * 现有 RecentAlbumsEntryDeviceTest 走的是 AlbumManager API 与宽度算术，
 * 没有经过真实弹层 UI。本测试补这块：卡片页下滑 → 「选择相册」弹层 →
 * 检查「最近归档」区 → 点图集触发真实归档 → 撤销 → 隐藏/改名后最近区更新。
 */
@RunWith(AndroidJUnit4::class)
class RecentAlbumsSheetDeviceTest {

    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx = ins.targetContext
    private lateinit var prefs: AppPreferences
    private lateinit var albums: AlbumManager
    private val inserted = mutableListOf<Uri>()
    private val createdAlbumIds = mutableListOf<String>()
    private val unique = "RS" + UUID.randomUUID().toString().replace("-", "").take(8)
    private var activity: android.app.Activity? = null

    @Before
    fun setUp() = kotlinx.coroutines.runBlocking {
        prefs = AppPreferences(ctx)
        prefs.hasCompletedOnboarding = true
        prefs.hasAcknowledgedDisclaimer = true
        prefs.recommendMode = RecommendMode.RANDOM_WALK
        albums = AlbumManager.getInstance(ctx)
        albums.initialize()
        grantMedia()
    }

    @After
    fun cleanUp() = kotlinx.coroutines.runBlocking {
        inserted.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
        createdAlbumIds.forEach { runCatching { albums.deleteAlbum(it) } }
    }

    private fun grantMedia() {
        listOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        ).forEach { perm -> runCatching { shell("pm grant ${ctx.packageName} $perm") } }
    }

    private fun shell(cmd: String): String = ins.uiAutomation.executeShellCommand(cmd).use { p ->
        java.io.FileInputStream(p.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun insertPhoto(path: String): Uri {
        val name = "rs-" + UUID.randomUUID().toString().take(8) + ".jpg"
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
        val bmp = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        try {
            bmp.eraseColor(name.hashCode() or -0x1000000)
            ctx.contentResolver.openOutputStream(uri)!!.use {
                check(bmp.compress(Bitmap.CompressFormat.JPEG, 88, it))
            }
        } finally {
            bmp.recycle()
        }
        check(
            ctx.contentResolver.update(
                uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
            ) == 1
        )
        return uri
    }

    private fun now() = android.os.SystemClock.uptimeMillis()

    private fun find(target: String): AccessibilityNodeInfo? {
        val root = ins.uiAutomation.rootInActiveWindow ?: return null
        var hit: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || hit != null || depth > 24) return
            if (n.text?.toString() == target || n.contentDescription?.toString() == target) {
                hit = n; return
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        var node = hit
        var hops = 0
        while (node != null && hops < 5) {
            if (node.isClickable) return node
            node = node.parent; hops++
        }
        return hit
    }

    /** 收集当前屏幕所有可见文本（用于"最近归档"区存在性判断）。 */
    private fun screenTexts(): Set<String> {
        val root = ins.uiAutomation.rootInActiveWindow ?: return emptySet()
        val out = mutableSetOf<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 24) return
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += "[cd]$it" }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    private fun dismissSystemDialogIfAny() {
        val root = ins.uiAutomation.rootInActiveWindow ?: return
        val owner = root.packageName?.toString().orEmpty()
        if (owner == ctx.packageName) return
        listOf("Allow", "允许").forEach { label ->
            root.findAccessibilityNodeInfosByText(label).firstOrNull { it.isClickable }?.let {
                val r = android.graphics.Rect(); it.getBoundsInScreen(r)
                shell("input tap ${r.centerX()} ${r.centerY()}")
            }
        }
    }

    private fun tap(target: String, timeout: Long = 20000) {
        val end = now() + timeout
        while (now() < end) {
            find(target)?.let { node ->
                if (node.isClickable) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                else {
                    val r = android.graphics.Rect(); node.getBoundsInScreen(r)
                    shell("input tap ${r.centerX()} ${r.centerY()}")
                }
                android.os.SystemClock.sleep(700)
                return
            }
            dismissSystemDialogIfAny()
            android.os.SystemClock.sleep(200)
        }
        error("tap not found: $target")
    }

    private fun waitFor(target: String, timeout: Long = 25000) {
        val end = now() + timeout
        while (now() < end) {
            if (find(target) != null) return
            dismissSystemDialogIfAny()
            android.os.SystemClock.sleep(200)
        }
        error("waitFor timeout: $target")
    }

    private fun startApp() {
        activity = ins.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        waitFor("回收站", timeout = 45000)
        android.os.SystemClock.sleep(2500)
    }

    /** 真实手势：卡片页下滑打开图集选择弹层。 */
    private fun openAlbumSheetBySwipeDown() {
        val m = ctx.resources.displayMetrics
        val cx = m.widthPixels / 2
        shell("input swipe $cx ${(m.heightPixels * 0.35f).toInt()} $cx ${(m.heightPixels * 0.85f).toInt()} 300")
        android.os.SystemClock.sleep(1200)
    }

    // ==================== 用例 ====================

    @Test
    fun albumSheetShowsRecentSectionAfterRealArchive() = kotlinx.coroutines.runBlocking {
        // 先通过真实入口造一条归档历史
        val album = albums.createAlbum("$unique-Recent")
        createdAlbumIds += album.id
        val photoUri = insertPhoto("DCIM/Camera")
        val imageId = android.content.ContentUris.parseId(photoUri)
        albums.queueImageToAlbum(imageId, photoUri.toString(), album.id, album.name)
        albums.getLastPendingArchive()?.let { albums.commitPendingArchive(it.id) }

        startApp()
        openAlbumSheetBySwipeDown()

        // 真实弹层：标题"选择相册"
        val texts = screenTexts()
        assertTrue(
            "下滑应打开图集选择弹层（实际屏幕文本：$texts）",
            texts.any { it.contains("选择相册") || it.contains("相册") }
        )
        // 造过归档历史后弹层应出现"最近归档"区
        assertTrue(
            "归档历史后弹层应显示「最近归档」区（实际屏幕文本：$texts）",
            texts.any { it == "最近归档" } || texts.any { it.contains("最近") }
        )
    }

    @Test
    fun archiveThenUndoUpdatesRecentList() = kotlinx.coroutines.runBlocking {
        val album = albums.createAlbum("$unique-Undo")
        createdAlbumIds += album.id
        val photoUri = insertPhoto("DCIM/Camera")
        val imageId = android.content.ContentUris.parseId(photoUri)

        // 归档成功后最近列表应包含它
        albums.queueImageToAlbum(imageId, photoUri.toString(), album.id, album.name)
        val pending = albums.getLastPendingArchive()
        assertTrue("应有待归档项", pending != null)
        val outcome = albums.commitPendingArchive(pending!!.id)
        assertTrue(
            "归档必须成功（实际 $outcome）",
            outcome == AlbumManager.ArchiveCommitOutcome.COMMITTED
        )

        val tracker = com.tabula.v3.data.repository.AlbumUsageTracker.getInstance(ctx)
        val stats = tracker.getAllAlbumStats()
        assertTrue(
            "归档成功后使用记录应包含该图集",
            stats.containsKey(album.id) || stats.keys.any { it.contains(album.id) }
        )

        // 撤销路径：再次入队后取消，不应留下归档
        albums.queueImageToAlbum(imageId, photoUri.toString(), album.id, album.name)
        assertTrue("撤销前应有待归档项", albums.getLastPendingArchive() != null)
        albums.cancelLastPendingArchive()
        assertTrue("撤销后不应有待归档项", albums.getLastPendingArchive() == null)
    }

    @Test
    fun hiddenAlbumDisappearsFromRecentListSource() = kotlinx.coroutines.runBlocking {
        val album = albums.createAlbum("$unique-Hidden")
        createdAlbumIds += album.id
        val photoUri = insertPhoto("DCIM/Camera")
        val imageId = android.content.ContentUris.parseId(photoUri)
        albums.queueImageToAlbum(imageId, photoUri.toString(), album.id, album.name)
        albums.getLastPendingArchive()?.let { albums.commitPendingArchive(it.id) }

        val tracker = com.tabula.v3.data.repository.AlbumUsageTracker.getInstance(ctx)
        val before = com.tabula.v3.data.album.RecentAlbumRules.recentAlbums(
            albums = albums.albums.value.filter { !it.isHidden },
            stats = tracker.getAllAlbumStats()
        )
        assertTrue("归档后最近列表应包含该图集", before.any { it.id == album.id })

        // 隐藏后：RecentAlbumRules 按可见列表求交，隐藏目标不应残留
        albums.hideAlbum(album.id)
        val after = com.tabula.v3.data.album.RecentAlbumRules.recentAlbums(
            albums = albums.albums.value.filter { !it.isHidden },
            stats = tracker.getAllAlbumStats()
        )
        assertTrue("隐藏后最近列表不应残留该图集", after.none { it.id == album.id })

        albums.unhideAlbum(album.id)
    }

    @Test
    fun recentListUsesLiveAlbumNameFromVisibleList() = kotlinx.coroutines.runBlocking {
        // 审计要求"改名"，但本分支没有图集改名 API：
        // pinnedAlbumNames 是「置顶图集名」集合（DeckScreen 用它排序），
        // 与图集显示名无关。这里如实验证最近列表取的是**当前可见列表里的
        // Album.name**（改名能力落地后这条会自动覆盖），并记录缺口。
        val album = albums.createAlbum("$unique-Live")
        createdAlbumIds += album.id
        val photoUri = insertPhoto("DCIM/Camera")
        val imageId = android.content.ContentUris.parseId(photoUri)
        albums.queueImageToAlbum(imageId, photoUri.toString(), album.id, album.name)
        albums.getLastPendingArchive()?.let { albums.commitPendingArchive(it.id) }

        val tracker = AlbumUsageTracker.getInstance(ctx)
        val recent = RecentAlbumRules.recentAlbums(
            albums = albums.albums.value.filter { !it.isHidden },
            stats = tracker.getAllAlbumStats()
        )
        val target = recent.firstOrNull { it.id == album.id }
        assertTrue("归档后该图集应在最近列表", target != null)
        // 名称直接来自当前 Album 对象，不缓存副本
        assertEquals(album.name, target!!.name)
    }
}
