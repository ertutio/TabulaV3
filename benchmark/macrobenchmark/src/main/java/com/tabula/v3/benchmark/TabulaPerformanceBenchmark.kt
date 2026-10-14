package com.tabula.v3.benchmark

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkRule
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import android.graphics.Rect
import androidx.test.uiautomator.StaleObjectException
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TabulaPerformanceBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val packageName = "com.tabula.v3"
    private val args = InstrumentationRegistry.getArguments()
    private val output = File(args.getString("additionalTestOutputDir")!!).apply { mkdirs() }
    private var currentStyle = "SHUFFLE"
    private val events = File(output, "operations.jsonl")

    private fun event(data: JSONObject) {
        data.put("style", currentStyle)
        data.put("uptimeMs", SystemClock.uptimeMillis())
        events.appendText(data.toString() + "\n")
    }

    private fun reset(style: String) {
        currentStyle = style
        val result = device.executeShellCommand("su 0 sh /data/local/tmp/tabula-perf/reset-app.sh " + style)
        check(result.contains("RESET_OK")) { "App reset failed: " + result }
    }

    private data class PhotoSnapshot(val contentDescription: String, val visibleBounds: Rect)
    private fun photo(): PhotoSnapshot? = try {
        device.findObjects(By.descStartsWith("perf_fixture_")).mapNotNull { node ->
            val rect = node.visibleBounds
            val description = node.contentDescription
            if (description != null && rect.width() > device.displayWidth * 0.60 && rect.height() > 200)
                PhotoSnapshot(description, rect) else null
        }.lastOrNull()
    } catch (_: StaleObjectException) { null }

    private fun waitPhoto(): PhotoSnapshot {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < deadline) {
            photo()?.let { return it }
            SystemClock.sleep(50)
        }
        device.dumpWindowHierarchy(File(output, "no-photo.xml"))
        error("Expected a synthetic photo card, not a permission/onboarding/empty screen")
    }

    private fun waitViewer(expected: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            val node = photo()
            if (node != null && (node.visibleBounds.width() > device.displayWidth * 0.94) == expected) return
            SystemClock.sleep(50)
        }
        device.dumpWindowHierarchy(File(output, "viewer-failed.xml"))
        error("Viewer state did not become " + expected)
    }

    private fun launch() {
        val result = device.executeShellCommand("am start -W -n " + packageName + "/.MainActivity")
        check(!result.contains("Error")) { result }
        waitPhoto()
        SystemClock.sleep(500)
    }

    private fun advance(count: Int) {
        repeat(count) {
            val snapshot = waitPhoto()
            val old = snapshot.contentDescription
            val rect = snapshot.visibleBounds
            val start = SystemClock.uptimeMillis()
            check(device.swipe(rect.left + rect.width() / 5, rect.centerY(),
                rect.right - rect.width() / 5, rect.centerY(), 18))
            val deadline = SystemClock.uptimeMillis() + 15000
            while (SystemClock.uptimeMillis() < deadline) {
                val current = photo()
                if (current != null && current.contentDescription != old) break
                SystemClock.sleep(50)
            }
            val next = waitPhoto().contentDescription
            check(next != old) { "Gesture did not advance a photo" }
            SystemClock.sleep(250)
            event(JSONObject().put("action", "rightSwipe").put("from", old).put("to", next)
                .put("automationWallMs", SystemClock.uptimeMillis() - start))
        }
    }

    private fun openCloseViewer() {
        val rect = waitPhoto().visibleBounds
        device.click(rect.centerX(), rect.centerY())
        waitViewer(true)
        SystemClock.sleep(250)
        device.pressBack()
        waitViewer(false)
        SystemClock.sleep(250)
        event(JSONObject().put("action", "viewerOpenClose"))
    }

    @Test fun a0_fixtureSmoke() {
        reset("SHUFFLE")
        launch()
        SystemClock.sleep(2000)
        val rect = waitPhoto().visibleBounds
        val screenshot = File(output, "fixture-smoke.png")
        assertTrue(device.takeScreenshot(screenshot))
        val bitmap = BitmapFactory.decodeFile(screenshot.path)
        var colorful = 0
        for (x in 1..4) for (y in 1..4) {
            val color = bitmap.getPixel(rect.left + rect.width() * x / 5, rect.top + rect.height() * y / 5)
            val channels = listOf(android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))
            if (channels.max() - channels.min() > 30) colorful++
        }
        bitmap.recycle()
        assertTrue("Must observe decoded fixture pixels, not just an AsyncImage node", colorful >= 4)
        event(JSONObject().put("action", "decodedFixtureSmoke").put("colorfulSamples", colorful))
        openCloseViewer()
    }

    @Test fun a_coldStartup() = benchmark.measureRepeated(
        packageName = packageName, metrics = listOf(StartupTimingMetric()), iterations = 10,
        compilationMode = CompilationMode.Full(), startupMode = StartupMode.COLD,
        setupBlock = { reset("SHUFFLE"); pressHome() }
    ) { startActivityAndWait(); waitPhoto() }

    @Test fun b_warmStartup() = benchmark.measureRepeated(
        packageName = packageName, metrics = listOf(StartupTimingMetric()), iterations = 10,
        compilationMode = CompilationMode.Full(), startupMode = StartupMode.WARM,
        setupBlock = { reset("SHUFFLE"); startActivityAndWait(); waitPhoto(); pressHome() }
    ) { startActivityAndWait(); waitPhoto() }

    private fun cards(style: String) = benchmark.measureRepeated(
        packageName = packageName, metrics = listOf(FrameTimingMetric()), iterations = 5,
        compilationMode = CompilationMode.Full(), startupMode = null,
        setupBlock = { reset(style); startActivityAndWait(); waitPhoto(); SystemClock.sleep(1000) }
    ) { advance(12) }

    @Test fun c_shuffleCards() = cards("SHUFFLE")
    @Test fun d_drawCards() = cards("DRAW")

    @Test fun e_viewer() = benchmark.measureRepeated(
        packageName = packageName, metrics = listOf(FrameTimingMetric()), iterations = 5,
        compilationMode = CompilationMode.Full(), startupMode = null,
        setupBlock = { reset("SHUFFLE"); startActivityAndWait(); waitPhoto(); SystemClock.sleep(1000) }
    ) { repeat(6) { openCloseViewer() } }

    @Test fun f_memorySnapshots() {
        val rows = JSONArray()
        fun snapshot(iteration: Int, stage: String) {
            SystemClock.sleep(1000)
            rows.put(JSONObject().put("iteration", iteration).put("stage", stage)
                .put("meminfo", device.executeShellCommand("dumpsys meminfo -s " + packageName)))
        }
        repeat(5) { i ->
            reset("SHUFFLE")
            launch()
            snapshot(i, "startup")
            advance(12)
            snapshot(i, "after12Swipes")
            val rect = waitPhoto().visibleBounds
            device.click(rect.centerX(), rect.centerY())
            waitViewer(true)
            snapshot(i, "viewer")
            device.pressBack()
            waitViewer(false)
            snapshot(i, "afterViewerClose")
        }
        File(output, "memory.json").writeText(JSONObject().put("rows", rows)
            .put("meaning", "post-action snapshots; maximum is not a continuous peak").toString(2))
    }
}
