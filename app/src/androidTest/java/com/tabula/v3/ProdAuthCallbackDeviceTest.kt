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
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.FileOperationManager
import com.tabula.v3.data.repository.RecycleBinManager
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 生产授权回调入口测试（包C2 收尾，覆盖 PR#65 审计登记缺口）。
 *
 * ## 缺口
 *
 * PR#65 的 OtherAppMediaAuthDeviceTest 用**测试包身份**经
 * `activity.startIntentSenderForResult(intentSender, 40501, …)` 驱动系统对话框，
 * 且其 KDoc 明言"结果码投递至 MainActivity **无消费者**（androidx registry 对未映射
 * requestCode 不分发）"——即系统对话框**确认/取消结果的生产回调消费路径**未被
 * 任何设备测试覆盖。本套件补上该缺口：全程 UI 驱动**应用真实入口**，由生产
 * launcher 发起并消费授权结果。
 *
 * ## 生产状态面（实读代码，非臆测；调用点 grep 结论：
 * createDeleteRequest/startIntentSenderForResult/IntentSenderRequest 仅存在于
 * MainActivity 与 FileOperationManager，SystemAlbumSyncManager/AlbumCleanupEngine 无）
 *
 * - 发起：MainActivity.kt L320-324 `onRequestDeletePermission` →
 *   `deletePermissionSession.launch(callback) { deletePermissionLauncher.launch(
 *   IntentSenderRequest.Builder(intentSender).build()) }`；launcher 以
 *   `ActivityResultContracts.StartIntentSenderForResult()` 注册（L288-293，带结果
 *   记录的启动——API36 Mainline MediaProvider 依赖该形态解析 getCallingPackage）。
 * - 等待/恢复标志：`DeletePermissionSession.inFlight`（DeletePermissionSession.kt
 *   L11，launch 置位 L22 / complete 释放 L36）；删除流侧以
 *   `suspendCancellableCoroutine` 挂起等待授权结果（MainActivity.kt L638-643
 *   回收站路径、L776-781 批删路径、L820-825 原图清理路径），由
 *   `onRequestDeletePermission(intentSender) { allowed -> … }` 恢复。
 * - 回调消费：launcher 回调 `result.resultCode == RESULT_OK →
 *   deletePermissionSession.complete(success)`（MainActivity.kt L290-293）；
 *   孤儿结果（无 callback，如进程恢复）走 `recoveryRevision++`（本套件不覆盖）。
 * - in-flight 的 UI 可观测面：`isDeletingRecycleBin || deletePermissionInFlight`
 *   时回收站界面渲染「正在处理回收站」进度对话框（MainActivity.kt L1128-1141）
 *   ——生产状态面在 UI 上的直接投影，本套件以其出现/消失断言 in-flight 进入与释放。
 * - 消费后推进：授权 → verify → `finishDeletion(deleted, covered)`（记录消费、
 *   journal 关闭）；拒绝 → `finishDeletion(emptySet, covered)`（RecycleBinDeletion.kt
 *   L119-122，BatchDeleteResult.Cancelled——记录保留、journal 关闭、源保留）。
 *
 * ## 驱动路径（应用真实入口，UiAutomation 点按；PR65/CloudCleanupUiTest 已证路径）
 *
 * Deck 顶栏「回收站」→ 回收站条目（contentDescription=displayName）→ 全屏查看器
 * 「彻底删除」→ 应用确认对话框「删除」→ 生产 deleteRecycleBinImages →
 * beginDeletion（journal 登记）→ createDeleteRequest → 系统对话框 →
 * 【确认 Allow】生产 launcher 回调消费 RESULT_OK → 记录消费 + 行/文件三面删除 +
 * in-flight 释放；【取消 Cancel/Back】生产 launcher 回调消费 RESULT_CANCELED →
 * 源保留三面 + 记录保留 + journal 关闭 + in-flight 释放 + UI 条目仍在（如实反馈）。
 *
 * ## 纪律（沿用 PR65）
 *
 * 仅使用本测试创建的合成媒体；shell 绑定所有权夹具（owner=androidTest APK，真实
 * 他人归属）+ 集合外哨兵（自有媒体，不进任何选择集）；断言均为截止时间轮询 +
 * 失败留证（截图 + 全窗口节点 dump），无固定等待；@After 集合级清理并双面核对，
 * 真实回收站单例（RecycleBinManager.getInstance）仅操作本套件登记的记录并在
 * @After 还原（记录移除 + 残留 journal 释放）。取消分支的 Toast 反馈为瞬时窗口，
 * 观察到则校验其内容、未观察到以记录保留 + 条目仍在等持久断言兜底（KDoc 如实
 * 登记，不作为弱化：诚实反馈的持久面即记录/条目保留）。
 *
 * 启动方式：allowSyntheticMediaProbe=true 且 ranchu/goldfish 模拟器门禁
 * （真机不可误跑）。本机无模拟器（ANDROID_HOME 无 emulator/system-images），
 * 设备执行依赖 CI probe（tools/run-cloud-ui-probe.sh 已登记本类）。
 */
@RunWith(AndroidJUnit4::class)
class ProdAuthCallbackDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private val foreignOwnerPackage: String get() = ins.context.packageName
    private var activity: android.app.Activity? = null
    private val createdNames = mutableListOf<String>()
    private val createdPrimaryUris = mutableListOf<Uri>()
    private val seededRecords = mutableListOf<ImageFile>()

    /** 有清单的合成媒体行（shell 侧权威归属 + 应用侧可见性）。 */
    private data class Row(
        val primary: Uri,
        val merged: Uri,
        val id: Long,
        val name: String,
        val path: String,
        val dataPath: String,
        val ownerShell: String?
    )

    private fun fileOps() = FileOperationManager(ctx)

    /** 应用真实回收站单例（与被测 UI 同一实例/同一存储）。 */
    private fun realManager(): RecycleBinManager = RecycleBinManager.getInstance(ctx)

    private fun shell(command: String): String =
        ins.uiAutomation.executeShellCommand(command).use { p ->
            FileInputStream(p.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        }

    private fun shellScript(script: String): String {
        val encoded = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val command = "/system/bin/sh -c printf\${IFS}%s\${IFS}$encoded|base64\${IFS}-d|/system/bin/sh"
        return shell(command)
    }

    private fun content(command: String): String {
        val output = shellScript("$command 2>&1; code=\$?; printf '\\n__TABULA_EXIT=%s\\n' \$code")
        val match = Regex("(?s)^(.*)\\n__TABULA_EXIT=(\\d+)\\s*$").matchEntire(output)
            ?: error("Missing shell exit status: $output")
        val result = match.groupValues[1].trim()
        check(match.groupValues[2] == "0" && !Regex("(?i)exception|\\berror\\b|permission denial").containsMatchIn(result)) {
            "Shell content failed: $output"
        }
        return result
    }

    @Before fun prepare() {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "Emulator-only probe" }
        shell("pm grant ${ctx.packageName} ${Manifest.permission.READ_MEDIA_IMAGES}")
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
        )
        AppPreferences(ctx).apply {
            hasCompletedOnboarding = true
            hasAcknowledgedDisclaimer = true
        }
        Log.i(TAG, "identity appPkg=${ctx.packageName} appUid=${Process.myUid()} " +
            "foreignOwnerPackage=$foreignOwnerPackage (androidTest APK, distinct uid)")
    }

    // ---------- 夹具创建（shell 绑定所有权 + 集合外哨兵，PR65 口径） ----------

    private fun writeSeedJpeg(target: File) {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(target.name.hashCode() or -0x1000000)
            target.parentFile?.mkdirs()
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally { bitmap.recycle() }
    }

    private fun seedFile(name: String): File {
        val dir = File(requireNotNull(ctx.getExternalFilesDir(null)), "prodauth-seeds")
        val target = File(dir, name)
        writeSeedJpeg(target)
        return target
    }

    /** shell 插入他人归属媒体：真实 JPEG 字节 + 显式 owner 绑定，断言归属生效。 */
    private fun foreignOwnedFixture(): Row {
        val name = "foreign-" + UUID.randomUUID().toString().replace("-", "").take(12) + ".jpg"
        val seed = seedFile(name)
        content(
            "content insert --uri content://media/external/images/media" +
                " --bind _display_name:s:$name" +
                " --bind mime_type:s:image/jpeg" +
                " --bind relative_path:s:$FOREIGN_DIR/" +
                " --bind owner_package_name:s:$foreignOwnerPackage"
        )
        createdNames += name
        val row = shellManifestRow(name)
        createdPrimaryUris += row.primary
        check(row.ownerShell == foreignOwnerPackage) {
            "Owner binding was not honored by MediaProvider (expected $foreignOwnerPackage): $row"
        }
        content("cat '${seed.absolutePath}' > '${row.dataPath}'")
        val written = content("wc -c < '${row.dataPath}'").trim().toLongOrNull()
        check(written == seed.length()) {
            "Foreign fixture bytes not written ($written vs ${seed.length()}): $row"
        }
        logManifest("foreign", row)
        return row
    }

    /**
     * 应用进程内自有媒体（集合外哨兵 = 自有媒体行，不进入任何选择集，全程必须存活）。
     * pm clear 后首个插入存在 provider 调用方身份解析竞态（PR65 用例同款）：创建者
     * 对自己的 pending 行必有写权限，失败即归属竞态，按 archive() publish 重试先例
     * 做有界重试，仍失败则如实抛出。
     */
    private fun outOfSelectionSentinel(): Row {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                return insertSelfOwnedPendingJpeg()
            } catch (e: Throwable) {
                lastError = e
                Log.w(TAG, "self-owned fixture insert attempt=$attempt failed (identity race window)", e)
                if (attempt < 2) SystemClock.sleep(2000)
            }
        }
        throw requireNotNull(lastError)
    }

    private fun insertSelfOwnedPendingJpeg(): Row {
        val name = "self-" + UUID.randomUUID().toString().replace("-", "").take(12) + ".jpg"
        val item = requireNotNull(
            ctx.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$SELF_DIR/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            )
        )
        createdNames += name
        createdPrimaryUris += ContentUris.withAppendedId(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentUris.parseId(item)
        )
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(name.hashCode() or -0x1000000)
            ctx.contentResolver.openOutputStream(item)!!.use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it))
            }
        } finally { bitmap.recycle() }
        check(
            ctx.contentResolver.update(
                item,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null
            ) == 1
        ) { "Publishing own pending fixture returned 0 rows: $item" }
        val row = appManifestRow(item, name)
        logManifest("self-owned", row)
        return row
    }

    // ---------- 清单（volume/id/name/relativePath/owner 记录进测试输出） ----------

    private fun shellManifestRow(name: String): Row {
        val output = content(
            "content query --uri content://media/external/images/media" +
                " --projection _id:_display_name:relative_path:volume_name:owner_package_name:_data" +
                " --where \"_display_name='$name'\""
        )
        val id = Regex("_id=(\\d+)").find(output)?.groupValues?.get(1)?.toLongOrNull()
            ?: error("Fixture row not found after shell insert: $output")
        val dataPath = Regex("_data=([^,]+)").find(output)?.groupValues?.get(1)
            ?: error("Fixture _data missing: $output")
        val relativePath = Regex("relative_path=([^,]+)").find(output)?.groupValues?.get(1)
            ?: error("Fixture relative_path missing: $output")
        val owner = Regex("owner_package_name=([^,]+)").find(output)?.groupValues?.get(1)
            ?.takeUnless { it in NULLISH }
        val primary = ContentUris.withAppendedId(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id
        )
        val merged = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        return Row(primary, merged, id, name, relativePath, dataPath, owner)
    }

    private fun appManifestRow(item: Uri, name: String): Row {
        val columns = arrayOf(
            MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME
        )
        return requireNotNull(
            ctx.contentResolver.query(item, columns, null, null, null)
        ) { "Null manifest query for own row: $item" }.use { cursor ->
            check(cursor.moveToFirst()) { "No row for own fixture: $item" }
            val volume = requireNotNull(cursor.getString(0))
            val id = cursor.getLong(1)
            val path = requireNotNull(cursor.getString(3))
            check(volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) { "Unexpected volume: $volume" }
            check(cursor.getString(2) == name) { "Unexpected display name: ${cursor.getString(2)}" }
            val primary = ContentUris.withAppendedId(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id
            )
            val merged = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            Row(primary, merged, id, name, path, "", cursor.getString(4))
        }
    }

    private fun logManifest(kind: String, row: Row) {
        val appOwner = try {
            ctx.contentResolver.query(
                row.primary, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else "missing-row" } ?: "null-cursor"
        } catch (e: Exception) { "query-error:${e.javaClass.simpleName}" }
        Log.i(
            TAG,
            "manifest kind=$kind volume=${MediaStore.VOLUME_EXTERNAL_PRIMARY} id=${row.id} " +
                "name=${row.name} path=${row.path} ownerShell=${row.ownerShell ?: "NULL"} " +
                "ownerAppSide=$appOwner appUid=${Process.myUid()}"
        )
    }

    // ---------- 断言辅助（三面源状态，PR65 口径） ----------

    private fun present(uri: Uri): Boolean = requireNotNull(
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
    ) { "MediaStore query returned null: $uri" }.use { it.moveToFirst() }

    private fun shellRowExists(name: String): Boolean =
        content(
            "content query --uri content://media/external/images/media" +
                " --projection _id:_display_name --where \"_display_name='$name'\""
        ) != "No result found."

    private fun shellFileExists(dataPath: String): Boolean =
        shellScript("test -e '$dataPath' && echo __EXISTS || echo __MISSING").trim() == "__EXISTS"

    // ---------- 生产入口 UI 驱动（UiAutomation，PR65/CloudCleanupUiTest 已证路径） ----------

    private fun bringAppToForeground() {
        activity = ins.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val end = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < end) {
            if (ins.uiAutomation.rootInActiveWindow?.packageName == ctx.packageName) return
            SystemClock.sleep(100)
        }
        captureUiEvidence("app foreground")
        error("MainActivity did not come to foreground")
    }

    private fun findNodeIn(
        node: AccessibilityNodeInfo?,
        pred: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (pred(node)) return node
        for (i in 0 until node.childCount) findNodeIn(node.getChild(i), pred)?.let { return it }
        return null
    }

    private fun findNodeInAnyWindow(pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        ins.uiAutomation.windows.mapNotNull { it.root }.firstNotNullOfOrNull { root ->
            findNodeIn(root, pred)
        }

    private fun findNodeInAppWindows(pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        ins.uiAutomation.windows
            .filter { it.root?.packageName?.toString() == ctx.packageName }
            .mapNotNull { it.root }
            .firstNotNullOfOrNull { root -> findNodeIn(root, pred) }

    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    /**
     * 按 contentDescription 精确匹配点按，直到 [effect] 成立（对话框/目标屏出现）。
     * API36 观察到单次点按偶发被过渡层吞掉（CloudCleanupUiTest.run 40723464281 先例）：
     * 有界重试点按（最多 4 次、间隔 4 秒），效果出现即停；超时留证并如实失败。
     */
    private fun tapDescUntilEffect(desc: String, label: String, effect: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20000
        var taps = 0
        var lastTapAt = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            if (effect()) return
            if (taps < 4 && SystemClock.uptimeMillis() - lastTapAt > 4000) {
                val target = findNodeInAnyWindow { it.contentDescription?.toString() == desc }
                    ?.let { clickableSelfOrAncestor(it) }
                if (target != null) {
                    val bounds = Rect()
                    target.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) {
                        Log.i(TAG, "tap $label (attempt=${taps + 1})")
                        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                        taps++
                        lastTapAt = SystemClock.uptimeMillis()
                    }
                }
            }
            SystemClock.sleep(100)
        }
        captureUiEvidence(label)
        error("UI flow did not reach expected effect for: $label")
    }

    /**
     * 应用确认对话框（独立 Compose Dialog 窗口）内「删除」按钮：以对话框正文
     * 「要允许 Tabula 删除这张照片吗？」定位窗口，再在窗口内精确匹配「删除」——
     * 避免与查看器底部「删除」文字标签（同窗歧义）混淆。效果=in-flight 对话框出现。
     */
    private fun tapAppDialogConfirmButton() {
        val deadline = SystemClock.uptimeMillis() + 20000
        var taps = 0
        var lastTapAt = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            if (inFlightDialogVisible()) return
            if (taps < 4 && SystemClock.uptimeMillis() - lastTapAt > 4000) {
                val dialogRoot = ins.uiAutomation.windows.mapNotNull { it.root }.firstOrNull { root ->
                    findNodeIn(root) { it.text?.toString()?.contains("要允许 Tabula 删除这张照片吗") == true } != null
                }
                if (dialogRoot != null) {
                    val target = findNodeIn(dialogRoot) {
                        it.text?.toString() == "删除" && clickableSelfOrAncestor(it) != null
                    }?.let { clickableSelfOrAncestor(it) }
                    if (target != null) {
                        val bounds = Rect()
                        target.getBoundsInScreen(bounds)
                        if (!bounds.isEmpty) {
                            Log.i(TAG, "tap app delete-confirm dialog 删除 (attempt=${taps + 1})")
                            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                            taps++
                            lastTapAt = SystemClock.uptimeMillis()
                        }
                    }
                }
            }
            SystemClock.sleep(100)
        }
        captureUiEvidence("app delete-confirm dialog")
        error("App delete-confirm dialog button not tapped / in-flight state not entered")
    }

    /** in-flight 的 UI 可观测面：「正在处理回收站」对话框（isDeletingRecycleBin || deletePermissionInFlight）。 */
    private fun inFlightDialogVisible(): Boolean =
        findNodeInAnyWindow { it.text?.toString() == "正在处理回收站" } != null

    /** in-flight 进入（生产 DeletePermissionSession.launch 置位；对话框弹出即真）。 */
    private fun waitInFlightEntered() {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            if (inFlightDialogVisible()) {
                Log.i(TAG, "in-flight dialog visible (production session inFlight=true, deletion suspended)")
                return
            }
            SystemClock.sleep(100)
        }
        captureUiEvidence("in-flight dialog never appeared")
        error("In-flight dialog did not appear — production entry did not enter DeletePermissionSession.inFlight")
    }

    /**
     * in-flight 释放（生产 launcher 回调 → deletePermissionSession.complete 消费系统
     * 结果 → inFlight=false）。连续 3 次轮询皆无才算释放，防窗口过渡期误判。
     */
    private fun waitInFlightReleased() {
        val deadline = SystemClock.uptimeMillis() + 20000
        var goneStreak = 0
        while (SystemClock.uptimeMillis() < deadline) {
            goneStreak = if (inFlightDialogVisible()) 0 else goneStreak + 1
            if (goneStreak >= 3) {
                Log.i(TAG, "in-flight dialog released (production callback consumed the system result)")
                return
            }
            SystemClock.sleep(100)
        }
        captureUiEvidence("in-flight dialog still held after system dialog result")
        error("In-flight dialog did not release — system dialog result was not consumed by the production callback")
    }

    private fun systemDialogRoot(): AccessibilityNodeInfo? =
        ins.uiAutomation.windows
            .firstOrNull { window ->
                val owner = window.root?.packageName?.toString().orEmpty()
                owner.contains("providers.media") || owner.contains("permissioncontroller")
            }?.root

    private fun waitForSystemDialogWindow(label: String) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < deadline) {
            if (systemDialogRoot() != null) return
            SystemClock.sleep(100)
        }
        captureUiEvidence(label)
        error("System consent dialog window did not appear ($label)")
    }

    /** PR65 已证点按逻辑：providers.media/permissioncontroller 窗口内精确文本按钮。 */
    private fun tapSystemButton(texts: Set<String>): Boolean {
        val root = systemDialogRoot() ?: return false
        val button = texts.asSequence()
            .flatMap { root.findAccessibilityNodeInfosByText(it).asSequence() }
            .firstOrNull { it.isClickable && it.text?.toString() in texts } ?: return false
        val bounds = Rect()
        button.getBoundsInScreen(bounds)
        check(!bounds.isEmpty) { "Empty bounds on system dialog button" }
        Log.i(TAG, "system consent dialog tapped=${button.text}")
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        return true
    }

    /** 取消分支：优先点 Cancel/取消按钮；按钮缺失时以 BACK 兜底（PermissionActivity 取消语义）。 */
    private fun cancelSystemConsent() {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            if (tapSystemButton(CANCEL_TEXTS)) return
            SystemClock.sleep(100)
        }
        Log.w(TAG, "cancel button not found on system dialog; falling back to BACK key")
        shell("input keyevent 4")
        val until = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < until) {
            if (systemDialogRoot() == null) {
                Log.i(TAG, "system consent dialog dismissed via BACK (cancel semantics)")
                return
            }
            SystemClock.sleep(100)
        }
        captureUiEvidence("system dialog did not dismiss on cancel/back")
        error("System consent dialog could not be cancelled")
    }

    /** 总结 Toast 为瞬时窗口：观察到返回文本（供条件校验），未观察到返回 null（持久断言兜底）。 */
    private fun observeSummaryToast(vararg fragments: String): String? {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            val node = findNodeInAnyWindow { n ->
                val text = n.text?.toString().orEmpty()
                text.isNotEmpty() && fragments.all { text.contains(it) }
            }
            if (node != null) return node.text?.toString()
            SystemClock.sleep(100)
        }
        return null
    }

    private fun waitRowGone(row: Row) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < deadline) {
            if (!present(row.primary) && !shellRowExists(row.name) && !shellFileExists(row.dataPath)) return
            SystemClock.sleep(100)
        }
        captureUiEvidence("row still present after consent")
        error("Row/file not fully removed after confirm: $row")
    }

    /** 授权推进的持久层同步点：本套件记录消费 + journal 回到基线（finishDeletion 已提交）。 */
    private suspend fun waitRecordConsumed(row: Row, beforeJournal: Set<Uri>) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < deadline) {
            val manager = realManager()
            val consumed = manager.loadRecycleBin().none { it.id == row.id }
            if (consumed && manager.pendingDeletionUris().toSet() == beforeJournal) return
            SystemClock.sleep(150)
        }
        captureUiEvidence("recycle record not consumed after confirm")
        error("Recycle record/journal did not advance after confirm: $row")
    }

    /** 拒绝分支的持久层同步点：journal 释放（finishDeletion(emptySet, covered)），回到基线。 */
    private suspend fun waitJournalReleased(beforeJournal: Set<Uri>) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < deadline) {
            if (realManager().pendingDeletionUris().toSet() == beforeJournal) return
            SystemClock.sleep(150)
        }
        captureUiEvidence("journal not released after cancel")
        error("Journal still holds the denied deletion request")
    }

    private fun waitUiNameGone(name: String) {
        val deadline = SystemClock.uptimeMillis() + 20000
        var goneStreak = 0
        while (SystemClock.uptimeMillis() < deadline) {
            goneStreak = if (findNodeInAppWindows {
                    it.text?.toString() == name || it.contentDescription?.toString() == name
                } == null
            ) goneStreak + 1 else 0
            if (goneStreak >= 3) return
            SystemClock.sleep(100)
        }
        captureUiEvidence("recycle bin item still displayed: $name")
        error("Recycle bin UI still shows the consumed item: $name")
    }

    private fun waitUiNamePresent(name: String) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < deadline) {
            if (findNodeInAppWindows {
                    it.text?.toString() == name || it.contentDescription?.toString() == name
                } != null
            ) return
            SystemClock.sleep(100)
        }
        captureUiEvidence("recycle bin item missing after cancel: $name")
        error("Recycle bin UI no longer shows the retained item: $name")
    }

    private fun captureUiEvidence(label: String) {
        fun dump(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 30) return
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            Log.e(TAG, "${" ".repeat(depth)}pkg=${node.packageName} text=${node.text} " +
                "desc=${node.contentDescription} bounds=$bounds clickable=${node.isClickable}")
            for (i in 0 until node.childCount) dump(node.getChild(i), depth + 1)
        }
        try {
            Log.e(TAG, "Capturing UI evidence for: $label")
            Log.e(TAG, "active root pkg=${ins.uiAutomation.rootInActiveWindow?.packageName}")
            dump(ins.uiAutomation.rootInActiveWindow, 0)
            ins.uiAutomation.windows.forEach { window ->
                Log.e(TAG, "window type=${window.type} active=${window.isActive} pkg=${window.root?.packageName}")
                dump(window.root, 0)
            }
            val dir = requireNotNull(ctx.getExternalFilesDir(null)).resolve("cloud-ui-failures")
            check(dir.mkdirs() || dir.isDirectory)
            ins.uiAutomation.takeScreenshot()?.let { bitmap ->
                try {
                    dir.resolve("prodauth-${UUID.randomUUID()}.png").outputStream()
                        .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
            }
        } catch (e: Exception) { Log.e(TAG, "Could not capture UI evidence", e) }
    }

    // ---------- 用例 1：确认分支——生产 launcher 消费 RESULT_OK → 删除推进 + in-flight 释放 ----------

    @Test fun systemConsentConfirmConsumedByProductionLauncherAdvancesDeletion() = runBlocking {
        val row = foreignOwnedFixture()
        val sentinel = outOfSelectionSentinel()
        val beforeJournal = realManager().pendingDeletionUris().toSet()
        val beforeCount = realManager().loadRecycleBin().size
        val record = row.record()
        realManager().addAllToRecycleBin(listOf(record))
        seededRecords += record
        bringAppToForeground()
        // Deck「回收站」→ 条目 → 查看器「彻底删除」→ 应用确认「删除」→ in-flight 进入
        tapDescUntilEffect("回收站", "deck trash button") {
            findNodeInAnyWindow { it.text?.toString() == "${beforeCount + 1} 项待处理" } != null
        }
        tapDescUntilEffect(row.name, "recycle bin item") {
            findNodeInAnyWindow { it.contentDescription?.toString() == "彻底删除" } != null
        }
        tapDescUntilEffect("彻底删除", "viewer delete button") {
            findNodeInAnyWindow { it.text?.toString()?.contains("要允许 Tabula 删除这张照片吗") == true } != null
        }
        tapAppDialogConfirmButton()
        waitInFlightEntered()
        waitForSystemDialogWindow("confirm")
        // 系统对话框确认 → 结果由生产 deletePermissionLauncher 回调消费
        val deadline = SystemClock.uptimeMillis() + 20000
        var granted = false
        while (SystemClock.uptimeMillis() < deadline) {
            if (tapSystemButton(ALLOW_TEXTS)) { granted = true; break }
            SystemClock.sleep(100)
        }
        if (!granted) { captureUiEvidence("system consent confirm button"); error("Allow button not tapped") }
        // 回调消费 → in-flight 释放（complete(true)）
        waitInFlightReleased()
        // 删除推进：行/文件三面消失 + 记录消费 + journal 回到基线（真实单例为共享存储，
        // 基线对照而非全局空——其他条目不属于本套件断言面）。
        waitRowGone(row)
        waitRecordConsumed(row, beforeJournal)
        assertFalse("row must be gone (app query)", present(row.primary))
        assertFalse("row must be gone (shell query)", shellRowExists(row.name))
        assertFalse("file must be gone", shellFileExists(row.dataPath))
        assertTrue("recycle record must be consumed", realManager().loadRecycleBin().none { it.id == row.id })
        assertEquals("journal must return to baseline", beforeJournal, realManager().pendingDeletionUris().toSet())
        val verification = fileOps().verifyDeletionState(listOf(row.primary))
        assertEquals(setOf(row.primary), verification.deleted)
        assertTrue("no unknown may be introduced by a granted delete", verification.unknown.isEmpty())
        // UI 如实反馈：条目从回收站消失
        waitUiNameGone(row.name)
        // 集合外哨兵不受影响。
        assertTrue("sentinel must survive", present(sentinel.primary))
        assertTrue("sentinel must survive shell query", shellRowExists(sentinel.name))
    }

    // ---------- 用例 2：取消分支——生产 launcher 消费 RESULT_CANCELED → 源保留 + in-flight 释放 ----------

    @Test fun systemConsentCancelConsumedByProductionLauncherRetainsSource() = runBlocking {
        val row = foreignOwnedFixture()
        val sentinel = outOfSelectionSentinel()
        val beforeCount = realManager().loadRecycleBin().size
        val beforeJournal = realManager().pendingDeletionUris().toSet()
        val record = row.record()
        realManager().addAllToRecycleBin(listOf(record))
        seededRecords += record
        bringAppToForeground()
        tapDescUntilEffect("回收站", "deck trash button") {
            findNodeInAnyWindow { it.text?.toString() == "${beforeCount + 1} 项待处理" } != null
        }
        tapDescUntilEffect(row.name, "recycle bin item") {
            findNodeInAnyWindow { it.contentDescription?.toString() == "彻底删除" } != null
        }
        tapDescUntilEffect("彻底删除", "viewer delete button") {
            findNodeInAnyWindow { it.text?.toString()?.contains("要允许 Tabula 删除这张照片吗") == true } != null
        }
        tapAppDialogConfirmButton()
        waitInFlightEntered()
        waitForSystemDialogWindow("cancel")
        // 系统对话框取消（Cancel/Back）→ 结果由生产 deletePermissionLauncher 回调消费
        cancelSystemConsent()
        // 回调消费 → in-flight 释放（complete(false)）
        waitInFlightReleased()
        // 瞬时 Toast 反馈（summary.cancelled →「已取消…剩余 1 张」）：自释放点起轮询
        // （Toast 在 journal 释放后立即显示、寿命约 3.5s）；观察到则校验内容，未观察
        // 到以记录保留 + 条目仍在等持久断言兜底（KDoc 已登记口径）。
        val toast = observeSummaryToast("已取消", "剩余")
        Log.i(TAG, "cancel summary toast observed=${toast != null} text=$toast")
        if (toast != null) {
            assertTrue(
                "cancel toast must report the cancellation honestly: $toast",
                toast.contains("已取消") && toast.contains("剩余 1")
            )
        }
        // 拒绝推进：journal 释放（回到基线）+ 源保留三面 + 记录保留
        waitJournalReleased(beforeJournal)
        assertTrue("row must be retained (app query)", present(row.primary))
        assertTrue("row must be retained (shell query)", shellRowExists(row.name))
        assertTrue("file bytes must be retained", shellFileExists(row.dataPath))
        assertTrue(
            "recycle record must be retained on denial",
            realManager().loadRecycleBin().any { it.id == row.id }
        )
        val verification = fileOps().verifyDeletionState(listOf(row.primary))
        assertEquals(setOf(row.primary), verification.existing)
        assertTrue("denial must not widen UNKNOWN", verification.unknown.isEmpty())
        assertTrue("denial must not delete", verification.deleted.isEmpty())
        // UI 如实反馈：条目仍在回收站（未被移除）
        waitUiNamePresent(row.name)
        // 集合外哨兵不受影响。
        assertTrue("sentinel must survive", present(sentinel.primary))
        assertTrue("sentinel must survive shell query", shellRowExists(sentinel.name))
    }

    // ---------- 基础设施 ----------

    private fun Row.record(): ImageFile = ImageFile(
        id = id, uri = primary, displayName = name, dateModified = 1L, size = 1L,
        width = 32, height = 32, bucketDisplayName = null, deletedAt = 1L,
        volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY, relativePath = path
    )

    @After fun cleanupFixturesAndAppState() = runBlocking {
        val errors = mutableListOf<Throwable>()
        // suspend 本地函数：回收站单例还原块内有挂起调用（loadRecycleBin 等）。
        suspend fun attempt(block: suspend () -> Unit) { try { block() } catch (e: Throwable) { errors += e } }
        activity?.let { current ->
            attempt {
                ins.runOnMainSync { current.finish() }
                ins.waitForIdleSync()
                val end = SystemClock.uptimeMillis() + 10000
                while (SystemClock.uptimeMillis() < end && !current.isDestroyed) SystemClock.sleep(100)
                check(current.isDestroyed) { "MainActivity not destroyed in @After" }
            }
        }
        // 真实回收站单例还原：移除本套件登记的记录；若流程中断残留 journal，仅释放
        // 本套件登记的条目（不动他人/基线条目），并双面核对。
        attempt {
            if (seededRecords.isNotEmpty()) {
                realManager().removeAllFromRecycleBin(seededRecords.toList())
            }
            val seededUris = seededRecords.map { it.uri }.toSet()
            val seededIds = seededRecords.map { it.id }.toSet()
            val pending = realManager().pendingDeletionUris()
            val mine = pending.filter { it in seededUris }.toSet()
            if (mine.isNotEmpty()) {
                Log.w(TAG, "journal still holds seeded uris after test; releasing in @After: $mine")
                realManager().finishDeletion(emptySet(), covered = mine)
            }
            check(realManager().loadRecycleBin().none { it.id in seededIds }) {
                "Seeded recycle records remain in the real manager after cleanup"
            }
            check(realManager().pendingDeletionUris().none { it in seededUris }) {
                "Seeded journal entries remain in the real manager after cleanup"
            }
        }
        // shell 可删除任意归属的行（WRITE_MEDIA_STORAGE）：按唯一名集合级删除，
        // 逐行双面验证（shell 查询 + app 查询）。
        for (name in createdNames) {
            attempt {
                content(
                    "content delete --uri content://media/external/images/media" +
                        " --where \"_display_name='$name'\""
                )
            }
        }
        for (name in createdNames) {
            attempt { check(!shellRowExists(name)) { "Fixture remains in shell query: $name" } }
        }
        for (uri in createdPrimaryUris) {
            attempt { check(!present(uri)) { "Fixture remains in app query: $uri" } }
        }
        attempt { shellScript("rm -rf '$seedRoot'") }
        if (errors.isNotEmpty()) {
            val failure = AssertionError("Fixture cleanup failed (${errors.size} errors)")
            errors.forEach { failure.addSuppressed(it) }
            throw failure
        }
    }

    private val seedRoot: String
        get() = File(requireNotNull(ctx.getExternalFilesDir(null)), "prodauth-seeds").absolutePath

    companion object {
        private const val TAG = "ProdAuthCallback"
        private const val FOREIGN_DIR = "Pictures/TabulaProdAuth"
        private const val SELF_DIR = "Pictures/TabulaProdAuthSelf"

        private val ALLOW_TEXTS = setOf("Allow", "Delete", "允许", "删除")
        private val CANCEL_TEXTS = setOf("Cancel", "取消", "Don't allow", "不允许")
        private val NULLISH = setOf("NULL", "null", "")
    }
}
