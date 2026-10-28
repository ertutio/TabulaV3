package com.tabula.v3

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
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
        albums.initialize()
        albums.createAlbum(albumName)
        Unit
    }
    private fun photo(): Uri {
        val name = "cloud-" + UUID.randomUUID() + ".jpg"
        val item = requireNotNull(ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CloudSafetyFixtures/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        photos += item
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(name.hashCode() or -0x1000000)
            ctx.contentResolver.openOutputStream(item)!!.use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally { bitmap.recycle() }
        check(ctx.contentResolver.update(item, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        return item
    }
    private suspend fun archive(source: Uri): Uri {
        sources += source.toString()
        val action = albums.queueImageToAlbum(ContentUris.parseId(source), source.toString(), albumName, albumName)
        assertEquals(AlbumManager.ArchiveCommitOutcome.COMMITTED, albums.commitPendingArchive(action.id))
        val destination = Uri.parse(requireNotNull(CopyEvidenceStore(ctx).get(source.toString())).destinationUri)
        photos += destination
        return destination
    }
    private fun present(uri: Uri): Boolean = try {
        ctx.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } ?: false
    } catch (_: Exception) { false }
    private fun waitFor(label: String, timeout: Long = 20000): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            val root = ins.uiAutomation.rootInActiveWindow
            val node = root?.findAccessibilityNodeInfosByText(label)?.firstOrNull()
            if (node != null) return node
            SystemClock.sleep(100)
        }
        error("UI element not found: $label")
    }
    private fun tap(label: String) {
        val node = waitFor(label)
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        check(!bounds.isEmpty) { "Empty bounds for $label" }
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }
    private fun startAndOpenDialog() {
        activity = ins.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        tap("图库")
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
        assertEquals(1, ctx.contentResolver.delete(destination, null, null))
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
        photos.forEach { try { ctx.contentResolver.delete(it, null, null) } catch (_: Exception) {} }
    }
}
