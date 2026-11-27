package com.tabula.v3

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.tabula.v3.data.relay.ShareRelayStore
import com.tabula.v3.data.preferences.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharePendingStagedCleanupDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val ui = PartialSelectionUi

    @Test
    fun systemShareRefreshesAlreadyOpenStagedCleanup() = verifySystemShare(warm = true)

    @Test
    fun systemShareRecordAppearsWhenOpeningStagedCleanupAfterward() = verifySystemShare(warm = false)

    private fun verifySystemShare(warm: Boolean) {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        if (context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PERMISSION_GRANTED) {
            automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        }
        val store = ShareRelayStore.getInstance(context)
        val original = store.load()
        val photo = FullRecoveryStateContract.seedPhoto(context, 97)
        val prefs = AppPreferences(context)
        val oldOnboarding = prefs.hasCompletedOnboarding
        val oldDisclaimer = prefs.hasAcknowledgedDisclaimer
        try {
            prefs.hasCompletedOnboarding = true
            prefs.hasAcknowledgedDisclaimer = true
            finishMainActivities()
            val mainBeforeShare = if (warm) openStagedCleanup() else null
            val source = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                setPackage(context.packageName)
                putExtra(Intent.EXTRA_STREAM, photo)
                clipData = ClipData.newUri(context.contentResolver, "分享待清测试照片", photo)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(source, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val firstChooser = awaitResumed("incoming Android sharesheet") { it.isChooser }
            val relayLabel = context.packageManager.getActivityInfo(
                android.content.ComponentName(context, ShareRelayActivity::class.java),
                0
            ).loadLabel(context.packageManager).toString()
            click(relayLabel, packageName = firstChooser.packageName)
            val secondChooser = awaitResumed("outgoing Android sharesheet") {
                it.isChooser && it.token != firstChooser.token &&
                    store.load().any { mark -> mark.uriString == photo.toString() }
            }
            val mark = store.load().single { it.uriString == photo.toString() }
            ui.shell("input keyevent 4")
            ui.await("outgoing Android sharesheet dismissed", {
                val tasks = taskState()
                !tasks.containsHistory(secondChooser.token) && tasks.resumed?.isChooser != true
            })
            if (mainBeforeShare == null) {
                openStagedCleanup()
            } else {
                if (taskState().resumed?.token != mainBeforeShare.token) launchMain()
                awaitResumed("same warm MainActivity after dismissing sharesheet") { it == mainBeforeShare }
                awaitLabel("暂存清理")
            }
            val count = original.size + 1
            awaitLabel("分享待清 · ${count} 张")
            ui.saveScreenshot("staged-share-pending-${if (warm) "warm" else "cold"}")
            click("分享待清 · ${count} 张", packageName = context.packageName)
            awaitLabel(requireNotNull(mark.displayName))
            ui.saveScreenshot("share-pending-review-${if (warm) "warm" else "cold"}")
            if (original.isEmpty()) {
                click("撤销标记", packageName = context.packageName)
                ui.await("only fixture mark removed", { store.load().isEmpty() })
            }
            ui.shell("input keyevent 4")
            awaitLabel("暂存清理")
            if (original.isEmpty()) ui.awaitGone("分享待清 · 1 张")
        } finally {
            finishMainActivities()
            prefs.hasCompletedOnboarding = oldOnboarding
            prefs.hasAcknowledgedDisclaimer = oldDisclaimer
            store.removeByUri(listOf(photo.toString()))
            check(context.getSharedPreferences("tabula_share_relay", 0).edit().commit())
            context.contentResolver.delete(photo, null, null)
            assertEquals(original, store.load())
        }
    }

    private fun openStagedCleanup(): ActivityRecord {
        launchMain()
        val activity = awaitResumed("MainActivity on Deck", ::isOurMainActivity)
        ui.waitFor("回收站", timeoutMs = 45000)
        click("设置", packageName = context.packageName)
        awaitLabel("外观")
        click("暂存清理", packageName = context.packageName)
        awaitLabel("暂存清理")
        return activity
    }

    private fun launchMain() {
        ui.shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
            "-f 0x10200000 -n ${context.packageName}/${MainActivity::class.java.name}")
    }

    private fun finishMainActivities() {
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().flatMap { monitor.getActivitiesInStage(it) }
                .filterIsInstance<MainActivity>().distinct()
                .filterNot { it.isFinishing || it.isDestroyed }
                .forEach { it.finish() }
        }
        ui.await("no existing MainActivity", { taskState().history.none(::isOurMainActivity) })
    }

    private data class ActivityRecord(val token: String, val packageName: String, val className: String) {
        val isChooser: Boolean get() = className.substringAfterLast('.') in
            setOf("ChooserActivity", "ChooserActivityLauncher", "ResolverActivity")
    }

    private fun isOurMainActivity(record: ActivityRecord): Boolean =
        record.packageName == context.packageName && record.className.substringAfterLast('.') == "MainActivity"

    private data class TaskState(val resumed: ActivityRecord?, val history: List<ActivityRecord>) {
        fun containsHistory(token: String) = history.any { it.token == token } || resumed?.token == token
    }

    private val recordPattern = Regex("ActivityRecord\\{(\\S+)\\s+u\\d+\\s+([^/\\s]+)/([^\\s}]+)")
    private val historyPattern = Regex("\\bHist\\s+#\\d+:")

    private fun taskState(): TaskState {
        val lines = ui.shell("dumpsys activity activities").lines()
        fun String.record() = recordPattern.find(this)?.destructured?.let { (token, pkg, name) ->
            ActivityRecord(token, pkg, name)
        }
        val resumed = lines.firstNotNullOfOrNull { line ->
            if (line.contains("topResumedActivity") || line.contains("mResumedActivity") ||
                line.contains("ResumedActivity:")) line.record() else null
        }
        val history = lines.mapNotNull { line -> if (historyPattern.containsMatchIn(line)) line.record() else null }
        return TaskState(resumed, history)
    }

    private fun awaitResumed(description: String, matches: (ActivityRecord) -> Boolean): ActivityRecord {
        var result: ActivityRecord? = null
        ui.await(description, {
            result = taskState().resumed?.takeIf { matches(it) && activeRoot(it.packageName) != null }
            result != null
        })
        return requireNotNull(result)
    }

    private fun activeRoot(packageName: String): AccessibilityNodeInfo? =
        automation.rootInActiveWindow?.takeIf { it.packageName?.toString() == packageName }

    private fun awaitLabel(label: String) {
        ui.await(label, { ui.find(activeRoot(context.packageName), label) != null })
    }

    private fun click(label: String, packageName: String) {
        var target: AccessibilityNodeInfo? = null
        ui.await(label, {
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            ui.findAll(activeRoot(packageName), label, candidates)
            target = candidates.asSequence()
                .filter { it.text?.toString() == label || it.contentDescription?.toString() == label }
                .mapNotNull { node ->
                    generateSequence(node) { it.parent }
                        .firstOrNull { it.isClickable }
                        ?.takeIf { it.isEnabled && it.isVisibleToUser }
                }
                .firstOrNull()
            target != null
        })
        val bounds = Rect().also { requireNotNull(target).getBoundsInScreen(it) }
        check(!bounds.isEmpty)
        ui.shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }
}
