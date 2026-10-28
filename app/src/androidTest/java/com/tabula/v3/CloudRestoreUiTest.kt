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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.AlbumManager
import com.tabula.v3.data.repository.RecycleBinManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CloudRestoreUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private val photos = mutableListOf<ImageFile>()
    private val bucket = "CloudRestore" + UUID.randomUUID().toString().replace("-", "").take(10)
    private val albums get() = AlbumManager.getInstance(ctx)
    private val trash get() = RecycleBinManager.getInstance(ctx)
    private var activity: android.app.Activity? = null
    private fun shell(command: String): String = ins.uiAutomation.executeShellCommand(command).use { p ->
        FileInputStream(p.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }
    @Before fun setup() {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        shell("pm grant ${ctx.packageName} ${Manifest.permission.READ_MEDIA_IMAGES}")
        assertEquals(PackageManager.PERMISSION_GRANTED, ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES))
        AppPreferences(ctx).apply { hasCompletedOnboarding = true; hasAcknowledgedDisclaimer = true }
    }
    private fun photo(path: String): ImageFile {
        val name = "cloud-" + UUID.randomUUID() + ".jpg"
        val uri = requireNotNull(ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$path/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        val image = ImageFile(ContentUris.parseId(uri), uri, name, System.currentTimeMillis(), 1L, 32, 32, path)
        photos += image
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(name.hashCode() or -0x1000000)
            ctx.contentResolver.openOutputStream(uri)!!.use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally { bitmap.recycle() }
        check(ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        return image
    }
    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString()?.contains(text) == true || node.contentDescription?.toString()?.contains(text) == true) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
        return null
    }
    private fun waitFor(text: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < end) {
            find(ins.uiAutomation.rootInActiveWindow, text)?.let { return it }
            SystemClock.sleep(100)
        }
        error("UI element not found: $text")
    }
    private fun gesture(text: String, long: Boolean = false) {
        val bounds = Rect(); waitFor(text).getBoundsInScreen(bounds)
        check(!bounds.isEmpty)
        val x = bounds.centerX(); val y = bounds.centerY()
        if (long) shell("input swipe $x $y $x $y 1200") else shell("input tap $x $y")
    }
    private fun awaitAlbum(count: Int, expected: Set<Long>) {
        val end = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < end) {
            val album = albums.albums.value.firstOrNull { it.name == bucket }
            if (album?.imageCount == count && album.coverImageId in expected) return
            SystemClock.sleep(100)
        }
        error("Album not updated: $bucket, expected count=$count, actual=${albums.albums.value.firstOrNull { it.name == bucket }}")
    }
    private suspend fun prepareTrash(count: Int): List<ImageFile> {
        val removed = (1..count).map { photo(bucket) }
        photo("CloudAnchor" + UUID.randomUUID().toString().take(8))
        trash.addAllToRecycleBin(removed)
        activity = ins.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("图库")
        val end = SystemClock.uptimeMillis() + 20000
        while (albums.albums.value.none { it.name == bucket && it.imageCount == 0 } && SystemClock.uptimeMillis() < end) SystemClock.sleep(100)
        assertEquals(0, albums.albums.value.first { it.name == bucket }.imageCount)
        gesture("回收站")
        waitFor("${count} 项待处理")
        return removed
    }
    private fun assertUiAlbum(count: Int) {
        gesture("返回")
        gesture("图库")
        val nameNode = waitFor(bucket)
        var card: AccessibilityNodeInfo? = nameNode
        repeat(3) { if (card != null && find(card, "$count 张") == null) card = card?.parent }
        assertNotNull("Album card must show its own count", find(card, "$count 张"))
    }
    @Test fun singleRestoreUpdatesAlbumWithoutManualRefresh() = runBlocking {
        val removed = prepareTrash(1)
        gesture(removed[0].displayName)
        gesture("恢复")
        awaitAlbum(1, setOf(removed[0].id))
        assertUiAlbum(1)
    }
    @Test fun batchRestoreUpdatesAlbumWithoutManualRefresh() = runBlocking {
        val removed = prepareTrash(2)
        gesture(removed[0].displayName, long = true)
        gesture(removed[1].displayName)
        gesture("恢复所选")
        awaitAlbum(2, removed.map { it.id }.toSet())
        assertUiAlbum(2)
    }
    @After fun cleanup() = runBlocking {
        activity?.let { current ->
            ins.runOnMainSync { current.finish() }
            ins.waitForIdleSync()
            val end = SystemClock.uptimeMillis() + 10000
            while (!current.isDestroyed && SystemClock.uptimeMillis() < end) SystemClock.sleep(100)
            check(current.isDestroyed) { "Activity did not finish" }
        }
        trash.removeAllFromRecycleBin(photos)
        photos.forEach { try { ctx.contentResolver.delete(it.uri, null, null) } catch (_: Exception) {} }
    }
}
