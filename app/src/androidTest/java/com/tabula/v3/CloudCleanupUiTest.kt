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
    private val photos = mutableListOf<Uri>()
    private val sources = mutableSetOf<String>()
    private val albums get() = AlbumManager.getInstance(ctx)
    private var activity: android.app.Activity? = null
    private val albumName = "CloudProbe" + UUID.randomUUID().toString().replace("-", "").take(10)

    private fun shell(command: String): String = ins.uiAutomation.executeShellCommand(command).use { p ->
        FileInputStream(p.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }
    @Before fun setup() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
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
    private fun photo(path: String = "CloudSafetyFixtures"): Uri {
        val name = "cloud-" + UUID.randomUUID() + ".jpg"
        val item = requireNotNull(ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$path/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        photos += item
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
        val action = albums.queueImageToAlbum(ContentUris.parseId(source), source.toString(), albumName, albumName)
        assertEquals(AlbumManager.ArchiveCommitOutcome.COMMITTED, albums.commitPendingArchive(action.id))
        val destination = Uri.parse(requireNotNull(CopyEvidenceStore(ctx).get(source.toString())).destinationUri)
        photos += destination
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
        while (SystemClock.uptimeMillis() < end) {
            val root = ins.uiAutomation.rootInActiveWindow
            if (find(root, "删除原位置的照片") != null) { ready = true; break }
            val button = find(root, "清理原图")
            if (button != null) { tapNode(button, "清理原图"); ready = true; break }
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
        activity?.let { current ->
            ins.runOnMainSync { current.finish() }
            ins.waitForIdleSync()
            awaitCondition("activity destruction") { current.isDestroyed }
        }
        albums.clearDeletedUris(sources)
        photos.forEach { uri ->
            if (present(uri)) {
                logMediaIdentity(uri, "cleanup-before")
                assertEquals("fixture delete must succeed: $uri", 1, ctx.contentResolver.delete(primaryItem(uri), null, null))
                assertFalse("fixture must be gone: $uri", present(uri))
            }
        }
    }
}
