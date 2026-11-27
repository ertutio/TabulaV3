package com.tabula.v3

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.RecommendMode
import com.tabula.v3.data.repository.AlbumManager
import com.tabula.v3.data.repository.LocalImageRepository
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerGlassPilotDeviceTest : ViewerGlassDeviceFixture() {

    @Test
    fun switchOffKeepsOriginalControlsAndBackgroundExit() {
        startDeck(glassEnabled = false, FixtureTone.BRIGHT)
        openCard()

        assertNull("Disabled pilot must not add a close control", control("关闭"))
        waitForControlAction("分享当前照片")
        val audit = waitForAudit { it["reason"] == "DISABLED" }
        assertEquals("fallback", audit["path"])
        assertEquals("false", audit["sampled"])
        assertEquals("false", audit["ready"])
        val screen = displayBounds()
        tap(screen.left + screen.width() / 100, screen.centerY())
        waitForViewerToClose()
    }

    @Test
    @SdkSuppress(minSdkVersion = 35)
    fun switchOnCloseWorksWithSampledAndSolidControls() {
        startDeck(glassEnabled = true, FixtureTone.HIGH_CONTRAST)
        openCard()
        waitForAudit { it["ready"] == "true" }
        assertViewerControlLayout()
        closeViewer(useAccessibilityAction = true)

        openCard()
        waitForAudit { it["ready"] == "true" }
        zoomIn()
        waitForAudit { it.scale >= 2.9f && it["reason"] == "VIEWER_TRANSFORMED" }
        assertViewerControlLayout()
        closeViewer()
    }

    private fun assertViewerControlLayout() {
        val closeBounds = bounds(waitForControlAction("关闭"))
        val shareBounds = bounds(waitForControlAction("分享当前照片"))
        val dp = ctx.resources.displayMetrics.density
        val touchSize = (48 * dp).roundToInt()
        val gap = (12 * dp).roundToInt()
        val statusInset = activity.window.decorView.rootWindowInsets
            ?.getInsets(WindowInsets.Type.statusBars())?.top ?: error("Missing window insets")

        for (button in listOf(closeBounds, shareBounds)) {
            assertTrue("48dp touch target required: $button", button.width() >= touchSize && button.height() >= touchSize)
            assertTrue("Controls must clear the status bar: $button", button.top >= statusInset + gap - 1)
        }
        assertEquals("Controls must share a top edge", shareBounds.top, closeBounds.top)
        val actualGap = maxOf(shareBounds.left - closeBounds.right, closeBounds.left - shareBounds.right)
        assertTrue("Controls must have a 12dp gap: $actualGap", abs(actualGap - gap) <= 1)
    }
}

/** MediaStore and input fixtures for the normal Deck activity. Run on a dedicated test device. */
abstract class ViewerGlassDeviceFixture {
    protected val ins = InstrumentationRegistry.getInstrumentation()
    protected val ctx = ins.targetContext
    private val prefs = AppPreferences(ctx)
    protected lateinit var activity: Activity
    private var restorePreferences: (() -> Unit)? = null
    private var restoreAlbumFilter: (() -> Unit)? = null
    private val fixtureAlbum = "Glass Fixture ${UUID.randomUUID()}"
    private val inserted = mutableListOf<PhotoFixture>()

    protected enum class FixtureTone(val aspectMultiplier: Float) {
        BRIGHT(0.90f),
        DARK(1.00f),
        HIGH_CONTRAST(1.10f)
    }

    protected data class PhotoFixture(
        val tone: FixtureTone, val name: String, val uri: Uri, val expectedAspect: Float
    ) {
        val identity = "${MediaStore.VOLUME_EXTERNAL_PRIMARY}|${uri.lastPathSegment}"
    }

    protected class Audit(private val line: String) {
        private val values = line.split(' ').drop(1).associate {
            it.substringBefore('=') to it.substringAfter('=')
        }
        operator fun get(key: String): String? = values[key]
        val scale get() = this["scale"]?.toFloatOrNull() ?: error("No scale in $line")
        val panX get() = this["pan"]?.substringAfter('(')?.substringBefore(',')?.toFloatOrNull()
            ?: error("No horizontal pan in $line")
        val panY get() = this["pan"]?.substringAfter(',')?.substringBefore(')')?.toFloatOrNull()
            ?: error("No vertical pan in $line")
        override fun toString() = line
    }

    protected fun startDeck(glassEnabled: Boolean, vararg tones: FixtureTone) {
        val onboarding = prefs.hasCompletedOnboarding
        val disclaimer = prefs.hasAcknowledgedDisclaimer
        val recommendation = prefs.recommendMode
        val glass = prefs.liquidGlassLabEnabled
        restorePreferences = {
            prefs.hasCompletedOnboarding = onboarding
            prefs.hasAcknowledgedDisclaimer = disclaimer
            prefs.recommendMode = recommendation
            prefs.liquidGlassLabEnabled = glass
        }
        prefs.hasCompletedOnboarding = true
        prefs.hasAcknowledgedDisclaimer = true
        prefs.recommendMode = RecommendMode.RANDOM_WALK
        prefs.liquidGlassLabEnabled = glassEnabled

        val repository = LocalImageRepository(ctx)
        val otherAlbums = runBlocking { repository.loadAllData().systemBuckets }
            .map { it.name }.distinct()
        val albumManager = AlbumManager.getInstance(ctx)
        val metadataFile = File(ctx.filesDir, "albums_metadata.json")
        val previousMetadata = if (metadataFile.exists()) metadataFile.readBytes() else null
        restoreAlbumFilter = {
            if (previousMetadata == null) {
                check(!metadataFile.exists() || metadataFile.delete())
            } else {
                metadataFile.writeBytes(previousMetadata)
            }
            runBlocking { albumManager.initialize() }
        }
        runBlocking {
            otherAlbums.forEach { albumManager.setAlbumExcludedFromRecommend(it, true) }
        }

        tones.forEach(::insertPhoto)
        await("fixtures to be indexed", 30_000) {
            val images = runBlocking { repository.loadAllData().allImages }
            inserted.all { fixture -> images.any { it.uri == fixture.uri } }
        }
        activity = ins.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("fixture cards", 45_000) { cardBounds() != null }
    }

    @After
    fun cleanUpViewerFixture() {
        if (::activity.isInitialized) {
            ins.runOnMainSync { activity.finish() }
            ins.waitForIdleSync()
        }
        try {
            inserted.forEach { ctx.contentResolver.delete(it.uri, null, null) }
        } finally {
            try {
                restoreAlbumFilter?.invoke()
            } finally {
                restorePreferences?.invoke()
            }
        }
    }

    protected fun openCard(): PhotoFixture {
        val card = cardBounds() ?: error("Fixture card is not visible")
        tap(card.centerX(), card.centerY())
        await("viewer to open") { viewerIdentity() != null }
        waitForAudit { it["pager"] == "true" }
        waitForControlAction("分享当前照片")
        val identity = viewerIdentity()
        return inserted.singleOrNull { it.identity == identity }
            ?: error("Viewer opened unrelated photo $identity on the test device")
    }

    protected fun advanceCard() {
        val card = cardBounds() ?: error("Fixture card is not visible")
        swipe(card.right - card.width() / 8, card.centerY(), card.left + card.width() / 8, card.centerY())
        // The card stack keeps its background cards in the accessibility tree during the swipe.
        SystemClock.sleep(1_000)
    }

    protected fun closeViewer(useAccessibilityAction: Boolean = false) {
        val close = waitForControlAction("关闭")
        if (useAccessibilityAction) {
            assertTrue("Close rejected its accessibility action", close.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        } else {
            val bounds = bounds(close)
            tap(bounds.centerX(), bounds.centerY())
        }
        waitForViewerToClose()
    }

    protected fun waitForViewerToClose() {
        await("viewer to close") {
            viewerIdentity() == null && control("关闭") == null &&
                descriptions().none { it.startsWith("AUDIT ") }
        }
    }

    protected fun zoomIn() {
        val screen = displayBounds()
        tap(screen.centerX(), screen.centerY())
        SystemClock.sleep(70)
        tap(screen.centerX(), screen.centerY())
    }

    protected fun panPhoto() {
        val screen = displayBounds()
        swipe(screen.centerX(), screen.centerY(), screen.centerX() + screen.width() / 6, screen.centerY())
    }

    protected fun restoreZoom() {
        // A single tap in a zoomed image resets its transform.
        // Let the viewer's post-drag tap guard expire first.
        SystemClock.sleep(350)
        val screen = displayBounds()
        tap(screen.centerX(), screen.centerY())
    }

    protected fun waitForAudit(condition: (Audit) -> Boolean): Audit {
        var last: Audit? = null
        await("viewer audit state", diagnostics = { "last=$last" }) {
            last = descriptions().firstOrNull { it.startsWith("AUDIT ") }?.let(::Audit)
            last?.let(condition) == true
        }
        return requireNotNull(last)
    }

    protected fun shot(name: String) {
        val directory = requireNotNull(ctx.getExternalFilesDir("viewer-glass-evidence"))
        val screenshot = requireNotNull(ins.uiAutomation.takeScreenshot())
        try {
            val file = File(directory, "$name.png")
            assertTrue("Screenshot recording failed: $file", file.outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            })
        } finally {
            screenshot.recycle()
        }
    }

    protected fun control(label: String): AccessibilityNodeInfo? =
        nodes().firstOrNull { it.contentDescription?.toString() == label && it.isVisibleToUser }

    protected fun waitForControlAction(label: String): AccessibilityNodeInfo {
        var action: AccessibilityNodeInfo? = null
        var candidates = emptyList<AccessibilityNodeInfo>()
        await("enabled viewer control $label", diagnostics = {
            "candidates=" + candidates.joinToString { node ->
                "${node.contentDescription} ${bounds(node)} enabled=${node.isEnabled} " +
                    "visible=${node.isVisibleToUser} clickable=${node.isClickable}"
            }
        }) {
            candidates = nodes()
                .filter { it.contentDescription?.toString() == label && it.isVisibleToUser }
                .map { generateSequence(it) { node -> node.parent }.toList() }
                // The Deck has a distinct action with the same Share label.
                .filter { chain ->
                    label != "分享当前照片" ||
                        chain.any { it.contentDescription?.toString()?.startsWith("viewer_overlay:") == true }
                }
                .mapNotNull { chain -> chain.firstOrNull { it.isClickable } }
            action = candidates.firstOrNull { node ->
                node.isEnabled && node.isVisibleToUser &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            }
            action != null
        }
        return requireNotNull(action)
    }

    protected fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    protected fun displayBounds(): Rect = activity.windowManager.currentWindowMetrics.bounds

    protected fun tap(x: Int, y: Int) {
        val down = SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_DOWN, x, y)
        SystemClock.sleep(32)
        touch(down, MotionEvent.ACTION_UP, x, y)
    }

    private fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int) {
        val down = SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_DOWN, fromX, fromY)
        val steps = 12
        for (step in 1..steps) {
            SystemClock.sleep(20)
            val x = fromX + (toX - fromX) * step / steps
            val y = fromY + (toY - fromY) * step / steps
            touch(down, MotionEvent.ACTION_MOVE, x, y)
        }
        touch(down, MotionEvent.ACTION_UP, toX, toY)
    }

    private fun touch(down: Long, action: Int, x: Int, y: Int) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
        try {
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue("Input injection failed: $action ($x,$y)", ins.uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    private fun cardBounds(): Rect? = nodes().asSequence()
        .filter { node -> inserted.any { it.name == node.contentDescription?.toString() } }
        .filter { it.isVisibleToUser }
        .map(::bounds)
        .maxByOrNull { it.width() * it.height() }

    private fun viewerIdentity(): String? = descriptions()
        .firstOrNull { it.startsWith("viewer_overlay:") }
        ?.removePrefix("viewer_overlay:")

    private fun descriptions(): List<String> = nodes().mapNotNull { it.contentDescription?.toString() }

    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo) {
            add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        ins.uiAutomation.rootInActiveWindow?.let(::visit)
    }

    private fun await(
        description: String,
        timeoutMs: Long = 15_000,
        diagnostics: () -> String = { "" },
        predicate: () -> Boolean
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(120)
        }
        error("Timed out waiting for $description. ${diagnostics()} UI=${descriptions()}")
    }

    private fun insertPhoto(tone: FixtureTone) {
        val name = "glass-device-${tone.name.lowercase()}-${UUID.randomUUID()}.png"
        val uri = requireNotNull(ctx.contentResolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/$fixtureAlbum/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        ))
        val screen = ctx.resources.displayMetrics
        val height = 1440
        val width = (height * tone.aspectMultiplier * screen.widthPixels / screen.heightPixels).roundToInt()
        inserted += PhotoFixture(tone, name, uri, width.toFloat() / height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            when (tone) {
                FixtureTone.BRIGHT -> {
                    canvas.drawColor(Color.rgb(242, 235, 202))
                    paint.color = Color.rgb(205, 174, 89)
                    for (x in 0 until width step 80) canvas.drawRect(x.toFloat(), 0f, x + 12f, height.toFloat(), paint)
                }
                FixtureTone.DARK -> {
                    canvas.drawColor(Color.rgb(12, 20, 46))
                    paint.color = Color.rgb(40, 53, 82)
                    for (y in 0 until height step 64) canvas.drawRect(0f, y.toFloat(), width.toFloat(), y + 12f, paint)
                }
                FixtureTone.HIGH_CONTRAST -> {
                    canvas.drawColor(Color.WHITE)
                    paint.color = Color.BLACK
                    for (y in 0 until height step 40) {
                        for (x in ((y / 40) % 2) * 40 until width step 80) {
                            canvas.drawRect(x.toFloat(), y.toFloat(), x + 40f, y + 40f, paint)
                        }
                    }
                }
            }
            paint.color = Color.rgb(225, 61, 65)
            paint.textSize = 52f
            canvas.drawText(tone.name, 24f, height * 0.6f, paint)
            check(requireNotNull(ctx.contentResolver.openOutputStream(uri)).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            })
        } finally {
            bitmap.recycle()
        }
        check(ctx.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null
        ) == 1)
    }
}
