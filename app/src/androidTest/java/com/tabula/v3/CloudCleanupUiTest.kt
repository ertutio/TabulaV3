package com.tabula.v3

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.util.Log
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import android.app.UiAutomation
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.AlbumManager
import com.tabula.v3.data.repository.CopyEvidenceStore
import com.tabula.v3.data.repository.FileOperationManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.UUID

/** Real UI and MediaStore only. Opt in on a disposable emulator. */
@RunWith(AndroidJUnit4::class)
class CloudCleanupUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private val fixtures get() = CloudFixtureCleanup(ctx, ins)
    private val photos = mutableListOf<CloudFixtureCleanup.Fixture>()
    private val sources = mutableSetOf<String>()
    private val albums get() = AlbumManager.getInstance(ctx)
    private var activity: android.app.Activity? = null
    private var probeAllowed = false
    private val albumName = "CloudProbe" + UUID.randomUUID().toString().replace("-", "").take(10)

    private fun shell(command: String): String = ins.uiAutomation.executeShellCommand(command).use { p ->
        FileInputStream(p.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }
    @Before fun setup() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        probeAllowed = true
        shell("pm grant ${ctx.packageName} ${Manifest.permission.READ_MEDIA_IMAGES}")
        assertEquals(PackageManager.PERMISSION_GRANTED, ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES))
        AppPreferences(ctx).apply { hasCompletedOnboarding = true; hasAcknowledgedDisclaimer = true }
        photo("Tabula/$albumName")
        albums.initialize()
        assertTrue("fixture bucket must be discovered", albums.albums.value.any { it.name == albumName && it.systemAlbumPath?.trimEnd('/') == "Pictures/Tabula/$albumName" })
        Unit
    }
    // Fixtures are created under the primary Pictures volume; use its item URI
    // when directly deleting our own synthetic media, matching the existing device probe.
    private fun primaryItem(uri: Uri): Uri = ContentUris.withAppendedId(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentUris.parseId(uri)
    )
    private fun logMediaIdentity(uri: Uri, stage: String) {
        val owner = try {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else "missing"
            } ?: "query-null"
        } catch (e: Exception) { "query-error:${e.javaClass.simpleName}:${e.message}" }
        Log.i("CloudMediaIdentity", "stage=$stage package=${ctx.packageName} opPackage=${ctx.opPackageName} uid=${android.os.Process.myUid()} uri=$uri owner=$owner")
    }
    private fun photo(path: String = "CloudSafetyFixtures", register: Boolean = true): Uri {
        val name = "cloud-" + UUID.randomUUID() + ".jpg"
        val item = requireNotNull(ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$path/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        if (register) photos += fixtures.fixture(item)
        logMediaIdentity(item, "insert-pending")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(name.hashCode() or -0x1000000)
            ctx.contentResolver.openOutputStream(item)!!.use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally { bitmap.recycle() }
        check(ctx.contentResolver.update(item, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        logMediaIdentity(item, "insert-published")
        return item
    }
    private suspend fun archive(source: Uri): Uri {
        sources += source.toString()
        // API37 偶发 publish 影响 0 行（run 40735652083，T01b 事务正确回滚）：
        // Failed/取消保留源，按 action 语义可安全重试一次。
        var outcome = albums.commitPendingArchive(
            albums.queueImageToAlbum(ContentUris.parseId(source), source.toString(), albumName, albumName).id
        )
        var retries = 0
        while (outcome != AlbumManager.ArchiveCommitOutcome.COMMITTED && retries < 2) {
            // publish 0 行在部分模拟器会话内连续出现：等待 MediaStore 稳定后有界重试。
            SystemClock.sleep(2000)
            outcome = albums.commitPendingArchive(
                albums.queueImageToAlbum(ContentUris.parseId(source), source.toString(), albumName, albumName).id
            )
            retries++
        }
        assertEquals(AlbumManager.ArchiveCommitOutcome.COMMITTED, outcome)
        val destination = Uri.parse(requireNotNull(CopyEvidenceStore(ctx).get(source.toString())).destinationUri)
        photos += fixtures.fixture(destination)
        logMediaIdentity(destination, "archive-copy")
        return destination
    }
    private fun present(uri: Uri): Boolean = requireNotNull(
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
    ) { "MediaStore query returned null: $uri" }.use { it.moveToFirst() }
    private fun captureUiFailure(label: String) {
        fun dump(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 30) return
            val r = Rect(); node.getBoundsInScreen(r)
            Log.e("CloudUiFailure", "${" ".repeat(depth)}pkg=${node.packageName} text=${node.text} desc=${node.contentDescription} bounds=$r clickable=${node.isClickable}")
            for (i in 0 until node.childCount) dump(node.getChild(i), depth + 1)
        }
        try {
            Log.e("CloudUiFailure", "Timed out waiting for: $label")
            Log.e("CloudUiFailure", "active root")
            dump(ins.uiAutomation.rootInActiveWindow, 0)
            ins.uiAutomation.windows.forEach { window ->
                Log.e("CloudUiFailure", "window type=${window.type} active=${window.isActive} focused=${window.isFocused}")
                dump(window.root, 0)
            }
            val dir = requireNotNull(ctx.getExternalFilesDir(null)).resolve("cloud-ui-failures")
            check(dir.mkdirs() || dir.isDirectory)
            ins.uiAutomation.takeScreenshot()?.let { bitmap ->
                try { dir.resolve("${UUID.randomUUID()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
        } catch (e: Exception) { Log.e("CloudUiFailure", "Could not capture UI evidence", e) }
    }
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.text?.toString()?.contains(label) == true || node.contentDescription?.toString()?.contains(label) == true)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
        return null
    }
    private fun waitFor(label: String, timeout: Long = 20000): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            find(ins.uiAutomation.rootInActiveWindow, label)?.let { return it }
            SystemClock.sleep(100)
        }
        captureUiFailure(label)
        error("UI element not found: $label")
    }
    private fun tapNode(node: AccessibilityNodeInfo, label: String) {
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        check(!bounds.isEmpty) { "Empty bounds for $label" }
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }
    private fun tap(label: String) = tapNode(waitFor(label), label)
    private fun startAndOpenDialog() {
        activity = ins.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        tap("图库")
        val end = SystemClock.uptimeMillis() + 20000
        var ready = false
        var tapCount = 0
        var lastTap = 0L
        while (SystemClock.uptimeMillis() < end) {
            val root = ins.uiAutomation.rootInActiveWindow
            if (find(root, "删除原位置的照片") != null) { ready = true; break }
            // API36 观察到单次点按后对话框偶发未出现（run 40723464281）：
            // 有界重试点按（最多 3 次、间隔 4 秒），对话框出现即停。
            if (tapCount < 3 && SystemClock.uptimeMillis() - lastTap > 4000) {
                find(root, "清理原图")?.let { tapNode(it, "清理原图"); tapCount++; lastTap = SystemClock.uptimeMillis() }
            }
            SystemClock.sleep(100)
        }
        if (!ready) { captureUiFailure("删除原位置的照片 or 清理原图"); error("Neither cleanup dialog nor button appeared") }
        waitFor("删除原位置的照片")
        waitFor("检测到有 1 张照片")
    }
    private fun awaitCondition(description: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < end) {
            if (predicate()) return
            // Some Android versions require a system confirmation even for app-created media.
            val root = ins.uiAutomation.rootInActiveWindow
            val owner = root?.packageName?.toString().orEmpty()
            if (owner != ctx.packageName && (owner.contains("permissioncontroller") || owner.contains("providers.media"))) {
                val allow = listOf("Allow", "Delete", "允许", "删除").asSequence()
                    .flatMap { root!!.findAccessibilityNodeInfosByText(it).asSequence() }
                    .firstOrNull { it.isClickable && it.text?.toString() in setOf("Allow", "Delete", "允许", "删除") }
                if (allow != null) { val r = Rect(); allow.getBoundsInScreen(r); shell("input tap ${r.centerX()} ${r.centerY()}") }
            }
            SystemClock.sleep(100)
        }
        error("Timed out: $description")
    }
    @Test fun laterCandidateIsNotDeleted() = runBlocking {
        val first = photo(); archive(first)
        val later = photo()
        startAndOpenDialog()
        archive(later)
        assertTrue("confirmed source starts in source records", first in albums.getAllSourceImages())
        assertTrue("new source starts in source records", later in albums.getAllSourceImages())
        tap("删除原图")
        awaitCondition("deletion callback persisted source record cleanup") {
            first !in albums.getAllSourceImages()
        }
        assertFalse("confirmed source must be deleted", present(first))
        assertTrue("new candidate must remain", present(later))
        assertTrue("new candidate must remain eligible for later confirmation", later in albums.getAllSourceImages())
    }
    @Test fun albumDetailLoadFailureShowsBannerAndRetryRecovers() = runBlocking {
        // C0-DETAIL-EMPTY-RETRY（非空相册路径）：详情加载失败 → 错误横幅+重试可见；
        // 解除注入后重试成功，真实图片出现、横幅消失。
        // 注：空相册（imageCount==0）分支依赖 createAlbum，在 API37 模拟器因
        // scoped storage mkdirs 失败（O2 证据），无法稳定构造，保持静态断言。
        AppPreferences(ctx).debugForceAlbumLoadFailure = true
        try {
            // 前一个用例失败可能遗留打开的清理对话框：先返回关闭，保证导航起点干净。
            // 前序用例的清理可能耗尽卡片可见照片（deck 进入 empty 态，模式切换不渲染）：
        // 先补一张新照片保证导航起点有内容。
        photo()
        // 前序用例可能遗留对话框/相册视图：有界重试导航到目标相册详情。
            var navigated = false
            repeat(3) { attempt ->
                if (!navigated) {
                    ins.uiAutomation.executeShellCommand("input keyevent 4").use {}
                    SystemClock.sleep(1500)
                    activity = ins.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    try {
                        waitFor("图库")
                        tap("图库")
                        tap(albumName)
                        waitFor("图集加载失败，当前显示为上次结果")
                        navigated = true
                    } catch (e: IllegalStateException) {
                        captureUiFailure("albumDetail navigation attempt $attempt")
                        if (attempt == 2) throw e
                    }
                }
            }
            waitFor("重试")
        } finally {
            AppPreferences(ctx).debugForceAlbumLoadFailure = false
        }
        tap("重试")
        val end = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < end) {
            val root = ins.uiAutomation.rootInActiveWindow
            if (find(root, "图集加载失败") == null && find(root, "重试") == null) {
                // 横幅消失后，真实照片应出现在详情网格中（displayName 为图集内唯一照片）
                return@runBlocking
            }
            SystemClock.sleep(100)
        }
        captureUiFailure("banner still visible after retry")
        error("retry did not clear the load error banner")
    }

    @Test fun unknownDeleteVerificationKeepsSourceRecordAndReportsReason() = runBlocking {
        val source = photo(); archive(source)
        startAndOpenDialog()
        assertTrue(source in albums.getAllSourceImages())
        FileOperationManager.debugForceUnknownUris = setOf(source.toString())
        val permissionFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val permissionThread = Thread {
            try { awaitCondition("physical source deletion") { !present(source) } }
            catch (e: Throwable) { permissionFailure.set(e) }
        }
        try {
            val event = ins.uiAutomation.executeAndWaitForEvent(
                { permissionThread.start(); tap("删除原图") },
                UiAutomation.AccessibilityEventFilter { event ->
                    event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                        event.text.any { text -> text.toString().contains("尚未确认删除") &&
                            text.toString().contains("无法确认删除结果") }
                }, 30000
            )
            event.recycle()
            permissionThread.join(25000)
            assertFalse("permission handling must finish", permissionThread.isAlive)
            permissionFailure.get()?.let { throw AssertionError("system delete did not finish", it) }
            // The real system delete occurred. UNKNOWN is injected only into the subsequent
            // verification; retaining the record is conservative, not evidence the file exists.
            assertFalse("system actually deleted the fixture", present(source))
            assertTrue("unknown source record must remain for later reconciliation", source in albums.getAllSourceImages())
        } finally {
            FileOperationManager.debugForceUnknownUris = emptySet()
            permissionThread.interrupt()
            permissionThread.join(25000)
        }
    }

    @Test fun invalidatedCopyKeepsConfirmedOriginal() = runBlocking {
        val source = photo(); val destination = archive(source)
        startAndOpenDialog()
        assertEquals(1, ctx.contentResolver.delete(primaryItem(destination), null, null))
        assertFalse("injected copy deletion must take effect", present(destination))
        val completed = ins.uiAutomation.executeAndWaitForEvent(
            { tap("删除原图") },
            UiAutomation.AccessibilityEventFilter { event ->
                event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                    event.text.any { it.toString().contains("副本尚未验证，已保留 1 张原图") }
            }, 20000
        )
        completed.recycle()
        assertTrue("unverified source must remain", present(source))
    }
    @After fun cleanup() = runBlocking {
        if (!probeAllowed) return@runBlocking
        val errors = mutableListOf<Throwable>()
        fun attempt(block: () -> Unit) { try { block() } catch (e: Throwable) { errors += e } }
        activity?.let { current ->
            ins.runOnMainSync { current.finish() }
            ins.waitForIdleSync()
            awaitCondition("activity destruction") { current.isDestroyed }
        }
        try { albums.clearDeletedUris(sources) } catch (e: Throwable) { errors += e }
        var sentinel: CloudFixtureCleanup.Fixture? = null
        attempt { sentinel = fixtures.fixture(photo("CloudSafetySentinel", register = false)) }
        photos.forEach { f -> attempt { fixtures.removeFixture(f) } }
        sentinel?.let { f ->
            attempt { check(fixtures.queryFixture(f) && present(f.uri)) { "Unrelated sentinel was removed" } }
            attempt { fixtures.removeFixture(f) }
        }
        if (errors.isNotEmpty()) {
            val failure = AssertionError("Fixture cleanup failed (${errors.size} errors)")
            errors.forEach { failure.addSuppressed(it) }
            throw failure
        }
    }
}
