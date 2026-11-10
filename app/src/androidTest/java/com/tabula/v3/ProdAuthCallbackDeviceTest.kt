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
 * ## 取消分支轮次记录（2026-11-09；三轮红后转阻塞，已从探针类列表摘除）
 *
 * 三轮 CI 双 API 均红，按收尾纪律（每轮一次诊断+一次定向修正，不做下一轮）转阻塞；
 * 轮 3 的 Deny 补齐与判定闭环链代码保留（api36 已部分验证生效），失败点转移如下：
 *
 * - **轮 1（run [42646353367](https://github.com/ertutio/TabulaV3/actions/runs/42646353367)，
 *   head 366d2e1，双 API 双用例同因）**：`IllegalStateException: UI flow did not reach
 *   expected effect for: deck trash button` at tapDescUntilEffect(:408)，调用栈 :705/:696
 *   （用例入口第一步）。工件实证（api36/ProdAuthCallbackDeviceTest/logcat.txt :107984）：
 *   deck 界面节点树含 desc=回收站（父可点击）但**全程零次 tap 日志**——所有查找走
 *   `uiAutomation.windows`，而本环境（API36/37 模拟器）该列表**恒为空**（同 run 各套件
 *   留证 dump 的 window 行全零）。修复（head 2951ed2）：全部查找统一改走
 *   `rootInActiveWindow`（绿套件 CloudRestoreUiTest/CloudCleanupUiTest/PR65 同款）。
 * - **轮 2（run [42653961146](https://github.com/ertutio/TabulaV3/actions/runs/42653961146)，
 *   head 2951ed2）**：修复生效——入口链路全通（deck 回收站 tap→「1 项待处理」回收站屏→
 *   条目→查看器彻底删除→应用确认对话框→in-flight→系统对话框出现），红点移至取消分支：
 *   取消用例 `IllegalStateException: System consent dialog could not be cancelled` at
 *   cancelSystemConsent(:543)（栈 :727）。工件实证（api36 logcat :112403 dump）：系统
 *   对话框（providers.media 窗口）按钮实际文本为 **Deny / Allow**（`text=Deny
 *   clickable=true`），而本套件 CANCEL_TEXTS={Cancel, 取消, Don't allow, 不允许} 缺
 *   "Deny" → 8s 找不到取消键 → BACK 兜底不可靠（对话框在 8s 判定窗后 ~0.1s 才关闭，
 *   logcat :112402 后 Transition CLOSE @16:36:00.989）→ 用例失败且遗留系统对话框/
 *   悬挂删除流，污染次例：确认用例在 bringAppToForeground(:338) 报
 *   `RuntimeException: Could not launch intent … within 45000 milliseconds`（栈 :660）。
 * - **轮 3（run [42662894360](https://github.com/ertutio/TabulaV3/actions/runs/42662894360)，
 *   head 5c035ec，双 API 各现新因）**：①CANCEL_TEXTS 补 "Deny"/"DENY"、②取消判定闭环链
 *   （窗口关闭→应用前台+回收站就绪→in-flight 释放）、③第二段重入验证。**api36**：Deny
 *   点按生效（logcat `system consent dialog tapped=Deny` @17:29:33.742）→ 窗口关闭
 *   （@17:29:34.272）→ 确认用例**转绿**（OK(1 failure) 计 1 失败 1 通过）——轮 2 主因已消；
 *   取消用例红于第二段：`IllegalStateException: App did not regain foreground with
 *   recycle bin ready (count=2 项待处理, item=foreign-cda107b4fac2.jpg)` at
 *   waitAppRecycleBinReady(:622)。dump 实证（logcat :108062 起）：active root 已回应用，
 *   但 **row1 查看器仍开启**（树内仅 desc=关闭 / text=1 / 2 / desc=恢复 / desc=彻底删除
 *   / 查看器页 desc=foreign-cda107b4fac2.jpg），全屏查看器覆盖层使回收站计数/列表节点
 *   **不可达**（「查看器覆盖层不改变节点可达性」假设在 API36 不成立）→ 20s 轮询必超时。
 *   **api37**：Deny 在对话框成为 active window 后 ~0.25s 即点按
 *   （in-flight @17:29:25.550 → `tapped=Deny` @17:29:25.795），进入动画期点按被吞且
 *   重构后仅点按一次无重试 → 对话框滞留：`IllegalStateException: System consent dialog
 *   window did not close after cancel` at waitSystemDialogClosed(:598)；dump 实证
 *   （logcat :95541 起，@17:29:45.859）对话框原样在树（text=Deny/Allow clickable=true）
 *   → 次例污染同轮 2（确认用例 bringAppToForeground(:372) 45s 超时，双用例 2 失败）。
 * - **解锁路径（登记为待验证假设，未经 CI 验证；唯一动作=一次定向修正+一次 CI）**：
 *   ①取消判定第一段（窗口关闭）后先**关闭查看器**——点 desc=关闭（RecycleBinScreen
 *   L798-806 IconButton onClick=onDismiss），效果=彻底删除 desc 消失，再等「N 项待处理」
 *   计数与条目重现（绕开查看器覆盖层遮蔽）；②Deny/BACK 点按改为**有界重试直至对话框
 *   关闭**（tapDescUntilEffect 同款防吞先例：api37 进入动画期单次点按被吞），BACK 兜底
 *   保留；重入验证段（本轮已验证逻辑面）随后按同一链路收尾。验证通过后重新登记
 *   tools/run-cloud-ui-probe.sh（expected_tests=2）。
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
 * in-flight 释放；【取消 Deny/Cancel/Back】生产 launcher 回调消费 RESULT_CANCELED →
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
 * 设备执行原依赖 CI probe——**现已从探针类列表摘除（三轮红阻塞登记，见上节），
 * 解锁验证前 CI 不会执行本类**。
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

    /**
     * 环境事实（run 42646353367 双 API 红诊断）：本环境 `uiAutomation.windows` 恒为
     * 空列表（同 run 各套件留证 dump 的 window 行全零），遍历 windows 的查找是死路。
     * 绿色套件（CloudRestoreUiTest/CloudCleanupUiTest/OtherAppMediaAuthDeviceTest）
     * 均以 rootInActiveWindow 递归查找驱动——应用屏、应用对话框（Compose AlertDialog
     * 独立窗口）、系统授权对话框（providers.media 窗口成为 active window）皆可达。
     * 本套件据此统一走 rootInActiveWindow。
     */
    private fun activeRoot(): AccessibilityNodeInfo? = ins.uiAutomation.rootInActiveWindow

    private fun findNodeInAnyWindow(pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        findNodeIn(activeRoot(), pred)

    private fun findNodeInAppWindows(pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        activeRoot()
            ?.takeIf { it.packageName?.toString() == ctx.packageName }
            ?.let { root -> findNodeIn(root, pred) }

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
                // 应用确认对话框（Compose 独立窗口）显示时即成为 active window
                // （CloudCleanupUiTest 应用对话框同款路径）；正文存在才在其内点「删除」。
                val dialogRoot = activeRoot()?.takeIf { root ->
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

    /** 系统授权对话框以 active window 身份出现（PR65/CloudCleanupUiTest 已证路径）。 */
    private fun systemDialogRoot(): AccessibilityNodeInfo? =
        activeRoot()?.takeIf { root ->
            val owner = root.packageName?.toString().orEmpty()
            owner.contains("providers.media") || owner.contains("permissioncontroller")
        }

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

    /**
     * 取消分支（轮 3 定向修正，run 42653961146 dump 实证）：系统对话框拒绝键实际文本为
     * **Deny**（`text=Deny clickable=true`），CANCEL_TEXTS 已补齐（含大写变体）。点按
     * 成功后的判定链（杜绝轮 2 的遗留状态污染次例）：
     * 1) 系统授权窗口**关闭**（providers.media/permissioncontroller 不再是 active window，
     *    连续 3 次稳定）；2) 应用**恢复前台**且回收站就绪（「N 项待处理」计数与保留条目
     *    名重新可见）；3)「正在处理回收站」进度对话框**消失**（isDeletingRecycleBin ||
     *    deletePermissionInFlight 释放的 UI 面，MainActivity L1128-1141）。
     * 按钮缺失时仍以 BACK 兜底（PermissionActivity 取消语义），兜底后走同一判定链。
     */
    private fun cancelSystemConsent(expectedPendingText: String, retainedName: String) {
        // 收尾单元三：有界重试以「对话框仍存在且效果未发生」为前提；按钮缺失仍以
        // BACK 兜底（PermissionActivity 取消语义），兜底后走同一判定链。
        val deadline = SystemClock.uptimeMillis() + 8000
        var cancelled = false
        var taps = 0
        var lastTap = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            if (!systemDialogActive()) { cancelled = true; break }
            if (taps < 4 && SystemClock.uptimeMillis() - lastTap > 2000) {
                if (tapSystemButton(CANCEL_TEXTS)) { taps++; lastTap = SystemClock.uptimeMillis() }
            }
            SystemClock.sleep(100)
        }
        if (!cancelled) {
            Log.w(TAG, "cancel button not found on system dialog; falling back to BACK key")
            shell("input keyevent 4")
        }
        waitSystemDialogClosed()
        // 收尾单元三：明确关闭动作——取消后查看器覆盖层滞留（轮 3 API36 dump 实证），
        // 先关查看器，使「N 项待处理」与保留条目节点可达，再做就绪判定。
        closeViewerIfOpen()
        waitAppRecycleBinReady(expectedPendingText, retainedName)
        waitInFlightReleased()
    }

    private fun systemDialogActive(): Boolean = systemDialogRoot() != null

    /**
     * 收尾单元三（2026-11-11）：取消后的**明确关闭动作**——轮 3 API36 dump 实证
     * 取消后查看器覆盖层滞留（「N 项待处理」/保留条目节点不可达，原"同窗可达"
     * 假设不成立）。若「彻底删除」入口仍在（查看器未关），点按查看器关闭钮
     * （RecycleBinScreen L803 IconButton onDismiss），效果=入口节点消失。
     */
    private fun closeViewerIfOpen() {
        if (findNodeInAnyWindow { it.contentDescription?.toString() == "彻底删除" } == null) return
        tapDescUntilEffect("关闭", "viewer close button (cancel path)") {
            findNodeInAnyWindow { it.contentDescription?.toString() == "彻底删除" } == null
        }
    }

    /** 收尾单元三：正向观察——应用前台且回收站计数文本可见（确认分支的可用界面观察）。 */
    private fun waitAppRecycleBinCount(countText: String) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < deadline) {
            if (findNodeInAppWindows { it.text?.toString() == countText } != null) {
                Log.i(TAG, "app foreground regained; recycle bin count visible ($countText)")
                return
            }
            SystemClock.sleep(100)
        }
        captureUiEvidence("recycle bin count not visible after confirm")
        error("Recycle bin count not visible after confirm ($countText)")
    }

    /**
     * 收尾单元三：用例末**可靠释放**——若系统对话框滞留（前序失败路径遗留），
     * 以有界 Deny/BACK 点按将其关闭，减少对后续用例的污染。不改变断言语义。
     */
    private fun releaseLingeringSystemDialog() {
        if (!systemDialogActive()) return
        Log.w(TAG, "lingering system dialog at case end; releasing (bounded)")
        var attempts = 0
        val deadline = SystemClock.uptimeMillis() + 10000
        while (systemDialogActive() && SystemClock.uptimeMillis() < deadline && attempts < 3) {
            if (!tapSystemButton(CANCEL_TEXTS)) shell("input keyevent 4")
            attempts++
            SystemClock.sleep(600)
        }
        if (systemDialogActive()) {
            captureUiEvidence("lingering system dialog could not be released at case end")
            Log.e(TAG, "lingering system dialog still active after release attempts")
        }
    }

    /** 系统授权窗口关闭：active window 不再是 providers.media/permissioncontroller（连续 3 次稳定）。 */
    private fun waitSystemDialogClosed() {
        val deadline = SystemClock.uptimeMillis() + 20000
        var goneStreak = 0
        while (SystemClock.uptimeMillis() < deadline) {
            goneStreak = if (systemDialogRoot() == null) goneStreak + 1 else 0
            if (goneStreak >= 3) {
                Log.i(TAG, "system consent dialog window closed")
                return
            }
            SystemClock.sleep(100)
        }
        captureUiEvidence("system consent dialog still active after cancel tap/back")
        error("System consent dialog window did not close after cancel")
    }

    /**
     * 应用恢复前台 + 回收站就绪：active window 回到应用，且回收站屏「N 项待处理」计数与
     * 保留条目名重新可见（同一 Compose 窗口内，查看器覆盖层不改变节点可达性）。in-flight
     * 进度对话框若仍是 active window，计数/条目不可见会自然等待其释放后再通过。
     */
    private fun waitAppRecycleBinReady(expectedPendingText: String, retainedName: String) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < deadline) {
            val ready = findNodeInAppWindows {
                it.text?.toString() == expectedPendingText
            } != null && findNodeInAppWindows {
                it.text?.toString() == retainedName || it.contentDescription?.toString() == retainedName
            } != null
            if (ready) {
                Log.i(TAG, "app foreground regained; recycle bin ready (count + retained item visible)")
                return
            }
            SystemClock.sleep(100)
        }
        captureUiEvidence("app not foreground or recycle bin not ready after cancel")
        error("App did not regain foreground with recycle bin ready " +
            "(count=$expectedPendingText, item=$retainedName)")
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
        // 系统对话框确认 → 结果由生产 deletePermissionLauncher 回调消费。
        // 收尾单元三：有界重试以「对话框仍存在且效果未发生」为前提（对话框关闭
        // =结果已被消费，重试即停）；关闭后正向观察可用界面，再查释放与消费。
        var granted = false
        val confirmDeadline = SystemClock.uptimeMillis() + 20000
        var confirmTaps = 0
        var lastConfirmTap = 0L
        while (SystemClock.uptimeMillis() < confirmDeadline) {
            if (!systemDialogActive()) { granted = true; break }
            if (confirmTaps < 4 && SystemClock.uptimeMillis() - lastConfirmTap > 4000) {
                if (tapSystemButton(ALLOW_TEXTS)) {
                    confirmTaps++
                    lastConfirmTap = SystemClock.uptimeMillis()
                }
            }
            SystemClock.sleep(100)
        }
        if (!granted) { captureUiEvidence("system consent confirm button"); error("Allow button not tapped / dialog did not close") }
        waitSystemDialogClosed()
        // 正向观察：应用前台 + 回收站计数回到删除前基线（条目已消费的可用界面证据）
        waitAppRecycleBinCount("${beforeCount} 项待处理")
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
        // 收尾单元三：用例末可靠释放（滞留系统窗口/对话框的有界清理，防污染后续用例）
        releaseLingeringSystemDialog()
    }

    // ---------- 用例 2：取消分支——生产 launcher 消费 RESULT_CANCELED → 源保留 + in-flight 释放 → 重入验证 ----------

    @Test fun systemConsentCancelConsumedByProductionLauncherRetainsSource() = runBlocking {
        // 第二个合成条目（row2）供第二段重入验证：启动前一并登记进回收站，
        // 应用启动即加载两条（计数文本 beforeCount+2，无需运行中刷新应用状态）。
        val row = foreignOwnedFixture()
        val row2 = foreignOwnedFixture()
        val sentinel = outOfSelectionSentinel()
        val beforeCount = realManager().loadRecycleBin().size
        val beforeJournal = realManager().pendingDeletionUris().toSet()
        val record = row.record()
        val record2 = row2.record()
        realManager().addAllToRecycleBin(listOf(record, record2))
        seededRecords += record
        seededRecords += record2
        val pendingText = "${beforeCount + 2} 项待处理"
        bringAppToForeground()
        tapDescUntilEffect("回收站", "deck trash button") {
            findNodeInAnyWindow { it.text?.toString() == pendingText } != null
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
        // 系统对话框取消（Deny/Cancel/Back）→ 结果由生产 deletePermissionLauncher 回调消费；
        // 判定链：窗口关闭 → 应用恢复前台+回收站就绪 → in-flight 释放（complete(false)）
        cancelSystemConsent(pendingText, row.name)
        // 瞬时 Toast 反馈（summary.cancelled →「已取消…剩余 1 张」）：自释放点起轮询
        // （Toast 在 journal 释放后立即显示、寿命约 3.5s）；本段选择集仅 row1（row2 不在
        // 其中），剩余口径不变；观察到则校验内容，未观察到以记录保留 + 条目仍在等持久
        // 断言兜底（KDoc 已登记口径）。
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

        // ---------- 第二段：in-flight 释放后的重入验证（轮 3 追加） ----------
        // 重入门 isDeletingRecycleBin || deletePermissionInFlight（MainActivity L627）
        // 已随第一次取消释放。对第二个合成条目重复真实删除：若门仍被持有，
        // deleteRecycleBinImages 直接 return——应用确认对话框不会出现、in-flight 不会
        // 再次进入，waitInFlightEntered 必然超时失败；再次出现即证可再入。随后对系统
        // 对话框再次取消，走同一判定链闭环收尾（journal 回基线、源保留）。
        tapDescUntilEffect("关闭", "viewer close button") {
            // 查看器关闭的效果：其内「彻底删除」入口节点消失（列表态无该 desc）
            findNodeInAnyWindow { it.contentDescription?.toString() == "彻底删除" } == null
        }
        tapDescUntilEffect(row2.name, "recycle bin item (re-entry)") {
            findNodeInAnyWindow { it.contentDescription?.toString() == "彻底删除" } != null
        }
        tapDescUntilEffect("彻底删除", "viewer delete button (re-entry)") {
            findNodeInAnyWindow { it.text?.toString()?.contains("要允许 Tabula 删除这张照片吗") == true } != null
        }
        tapAppDialogConfirmButton()
        // 重入断言：in-flight 对话框再次出现（生产 DeletePermissionSession.launch 二次置位）
        waitInFlightEntered()
        waitForSystemDialogWindow("re-entry cancel")
        cancelSystemConsent(pendingText, row2.name)
        // 拒绝推进（第二段）：journal 再次回到基线 + 第二条目源保留 + 记录保留
        waitJournalReleased(beforeJournal)
        assertTrue("re-entry row must be retained (app query)", present(row2.primary))
        assertTrue("re-entry row must be retained (shell query)", shellRowExists(row2.name))
        assertTrue("re-entry file bytes must be retained", shellFileExists(row2.dataPath))
        assertTrue(
            "re-entry recycle record must be retained on denial",
            realManager().loadRecycleBin().any { it.id == row2.id }
        )
        val reentryVerification = fileOps().verifyDeletionState(listOf(row2.primary))
        assertEquals(setOf(row2.primary), reentryVerification.existing)
        assertTrue("re-entry denial must not widen UNKNOWN", reentryVerification.unknown.isEmpty())
        assertTrue("re-entry denial must not delete", reentryVerification.deleted.isEmpty())
        // UI 如实反馈：第二条目仍在回收站
        waitUiNamePresent(row2.name)
        // 第一条目在第二段全程不受影响。
        assertTrue("first row must still be retained after re-entry segment", present(row.primary))
        assertTrue(
            "first record must still be retained after re-entry segment",
            realManager().loadRecycleBin().any { it.id == row.id }
        )
        // 收尾单元三：用例末可靠释放（重入段已再次取消，残留窗口有界清理）
        releaseLingeringSystemDialog()
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
        // 轮 3（run 42653961146 dump 实证）：API36/37 Mainline MediaProvider 系统对话框
        // 拒绝键实际文本为 Deny（text=Deny clickable=true），补齐含大写变体。
        private val CANCEL_TEXTS = setOf("Cancel", "取消", "Deny", "DENY", "Don't allow", "不允许")
        private val NULLISH = setOf("NULL", "null", "")
    }
}
