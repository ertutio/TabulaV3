package com.tabula.v3

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentSender
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
import com.tabula.v3.data.repository.deleteInBatches
import com.tabula.v3.data.repository.deleteMediaUrisBatch
import com.tabula.v3.data.repository.deleteRecycleBinSelection
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实"他人媒体"系统授权流程验证（包C2）。仅使用本测试创建的合成媒体，仅在显式
 * opt-in 的模拟器上运行（allowSyntheticMediaProbe=true，ranchu/goldfish 硬件门）。
 *
 * ## 夹具身份对照（实测口径，取代任务登记中"现有夹具经 shell 创建"的前提）
 *
 * - **自有媒体**（selfOwnedFixture）：应用进程内 insert（uid=被测 debug 应用
 *   com.tabula.v3.recyclebin.test）。仓库现有全部设备测试夹具均属此类——
 *   CloudCleanupUiTest.photo/VolumeDeletionDeviceTest.fixture 均为应用进程内
 *   `contentResolver.insert`（shell 仅参与 pm grant/清理删除/UI 点按），并非 shell
 *   身份媒体。对此类媒体应用是拥有者：直接 delete 成功（对照组 1 的自有序）。
 * - **shell 无主媒体**（unownedShellFixture）：shell `content insert` 不绑定 owner。
 *   AOSP MediaProvider（insertFile 的 isCallingPackageShell 分支）按路径猜测归属，
 *   Pictures/ 路径无归属包 → owner_package_name 为 NULL。AccessChecker 的写面
 *   过滤 `owner_package_name IN (<调用方共享包>)` 对 NULL 不匹配 → 应用（现代
 *   非遗留应用）直接写/删被拒，读面由 READ_MEDIA_IMAGES 权限覆盖 → 可见。
 * - **他人包名媒体**（foreignOwnedFixture）：shell 以 WRITE_MEDIA_STORAGE 显式
 *   绑定 `owner_package_name=<androidTest APK 包名>`（AOSP 明文允许 shell 任意
 *   设置归属："When the caller is the media scanner or the shell, we let them
 *   change ownership however they see fit"）。androidTest APK
 *   （com.tabula.v3.recyclebin.test.test）在 CI 设备上真实安装、uid 独立于被测
 *   应用 → 真实"另一个应用"拥有的媒体行。instrumentation 运行于被测应用进程/uid
 *   （am instrument 装载进目标进程），进程内 insert 无法产生跨 uid 归属——这是
 *   经源码与 run 42369922277 工件（CloudMediaIdentity uid=10234）核实的进程模型
 *   事实，跨身份夹具只能由 shell 侧创建。
 *
 * ## 真实系统授权行为（断言面，均经 AOSP MediaProvider 源码核对）
 *
 * 1. 读可见：READ_MEDIA_IMAGES 授予后，他人/无主/自有行均可查询。
 * 2. 直接删除他人/无主行：enforceCallingPermissionInternal 写面无归属匹配、
 *    读面匹配 + allowUserGrant → RecoverableSecurityException("has no access to …")，
 *    行保留。自有行直接删除成功（同一路径的归属差异即授权边界）。
 * 3. 生产删除组合（回收站 deleteRecycleBinSelection / 清理入口
 *    deleteMediaUrisBatch+deleteInBatches，共享 FileOperationManager.deleteImages
 *    request 接缝）：API 30+ 走 MediaStore.createDeleteRequest →
 *    DeleteResult.NeedsPermission(真实 intentSender)。未授权 → 按项保留、
 *    remaining 如实、journal 关闭、记录保留；UNKNOWN 语义不扩大（可读行核验为
 *    existing 而非 unknown）。
 * 4. 真实系统授权对话框：应用无 MANAGE_MEDIA/MANAGE_EXTERNAL_STORAGE →
 *    PermissionActivity 必现（正向按钮 R.string.allow="Allow"）→ 点 Allow 后由
 *    MediaProvider applyBatch 完成删除 → 行消失（app/shell/文件三面一致）、记录
 *    消费、journal 干净、集合外哨兵不受影响。
 *
 * 纪律：中心稳定断言（行/清单/文件精确核对）、截止时间轮询、无固定等待（仅
 * 轮询节奏 100ms 与身份竞态重试退避）；不授予生产应用 MANAGE_MEDIA、不改 app-op；
 * 集合外哨兵全程存活。
 */
@RunWith(AndroidJUnit4::class)
class OtherAppMediaAuthDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private val foreignOwnerPackage: String get() = ins.context.packageName
    private val createdNames = mutableListOf<String>()
    private val createdPrimaryUris = mutableListOf<Uri>()
    private val managerDirs = mutableListOf<String>()

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

    // ---------- 夹具创建（三类身份，全部记入清单） ----------

    private fun writeSeedJpeg(target: File) {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(target.name.hashCode() or -0x1000000)
            target.parentFile?.mkdirs()
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally { bitmap.recycle() }
    }

    private fun seedFile(name: String): File {
        val dir = File(requireNotNull(ctx.getExternalFilesDir(null)), "otherauth-seeds")
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

    /** shell 插入无主媒体（现 shell 夹具口径）：owner 为 NULL，写面仍需授权。 */
    private fun unownedShellFixture(): Row {
        val name = "shell-" + UUID.randomUUID().toString().replace("-", "").take(12) + ".jpg"
        val seed = seedFile(name)
        content(
            "content insert --uri content://media/external/images/media" +
                " --bind _display_name:s:$name" +
                " --bind mime_type:s:image/jpeg" +
                " --bind relative_path:s:$FOREIGN_DIR/"
        )
        createdNames += name
        val row = shellManifestRow(name)
        createdPrimaryUris += row.primary
        check(row.ownerShell == null) {
            "Unbound shell insert unexpectedly gained owner=${row.ownerShell}: $row"
        }
        content("cat '${seed.absolutePath}' > '${row.dataPath}'")
        val written = content("wc -c < '${row.dataPath}'").trim().toLongOrNull()
        check(written == seed.length()) {
            "Unowned fixture bytes not written ($written vs ${seed.length()}): $row"
        }
        logManifest("shell-unowned", row)
        return row
    }

    /**
     * 应用进程内自有媒体（现有全部设备测试夹具的口径），复用 IS_PENDING 发布舞步。
     * pm clear 后首个插入存在 provider 调用方身份解析竞态（run 42369922277：行被
     * 创建但归属为空，创建者写自己的 pending 行即被 RecoverableSecurityException
     * 拒绝，约 130ms 后身份解析完成重试成功）——创建者对自己的 pending 行必有写
     * 权限，失败即归属竞态，按 archive() 的 publish 0 行重试先例做有界重试，仍
     * 失败则如实抛出。
     */
    private fun selfOwnedFixture(): Row {
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

    /** 集合外哨兵 = 自有媒体行，不进入任何选择集，全程必须存活。 */
    private fun outOfSelectionSentinel(): Row = selfOwnedFixture()

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

    // ---------- 断言辅助 ----------

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

    /** 直接删除他人行的真实系统行为：SecurityException（RecoverableSecurityException 子类）。 */
    private fun interceptDirectDelete(row: Row): Throwable? = try {
        val deleted = ctx.contentResolver.delete(row.primary, null, null)
        if (deleted > 0) {
            error("Direct delete of non-owned row succeeded — authorization boundary absent: $row")
        }
        null
    } catch (e: SecurityException) { e }

    // ---------- 真实系统授权对话框 ----------

    private fun bringAppToForeground() {
        shell("am start -n ${ctx.packageName}/com.tabula.v3.MainActivity")
        val end = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < end) {
            if (ins.uiAutomation.rootInActiveWindow?.packageName == ctx.packageName) return
            SystemClock.sleep(100)
        }
        captureUiEvidence("app foreground")
        error("MainActivity did not come to foreground")
    }

    private fun tapSystemConsentButton(): Boolean {
        val allowTexts = setOf("Allow", "Delete", "允许", "删除")
        val end = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < end) {
            val root = ins.uiAutomation.rootInActiveWindow
            val owner = root?.packageName?.toString().orEmpty()
            if (owner.contains("providers.media") || owner.contains("permissioncontroller")) {
                val button = allowTexts.asSequence()
                    .flatMap { root!!.findAccessibilityNodeInfosByText(it).asSequence() }
                    .firstOrNull { it.isClickable && it.text?.toString() in allowTexts }
                if (button != null) {
                    val bounds = Rect()
                    button.getBoundsInScreen(bounds)
                    check(!bounds.isEmpty) { "Empty bounds on consent button" }
                    Log.i(TAG, "system consent dialog owner=$owner tapped=${button.text}")
                    shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                    return true
                }
            }
            SystemClock.sleep(100)
        }
        captureUiEvidence("consent dialog")
        return false
    }

    private fun grantThroughRealSystemDialog(result: FileOperationManager.DeleteResult, row: Row): Boolean {
        val needsPermission = result as FileOperationManager.DeleteResult.NeedsPermission
        assertNotNull("createDeleteRequest must produce a real intentSender", needsPermission.intentSender)
        val launchError = AtomicReference<Throwable?>()
        ins.runOnMainSync {
            try {
                ctx.startIntentSender(needsPermission.intentSender, null, 0, 0, Intent.FLAG_ACTIVITY_NEW_TASK)
            } catch (e: Throwable) { launchError.set(e) }
        }
        launchError.get()?.let { Log.e(TAG, "intentSender launch failed", it); return false }
        if (!tapSystemConsentButton()) return false
        // 授权后 MediaProvider 在对话框内 applyBatch 完成删除；以行消失为同步点。
        val end = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < end) {
            if (!present(row.primary)) return true
            SystemClock.sleep(100)
        }
        Log.e(TAG, "Row still present after consent tap: $row")
        captureUiEvidence("row still present after consent")
        return false
    }

    private fun captureUiEvidence(label: String) {
        try {
            Log.e(TAG, "Capturing UI evidence for: $label")
            Log.e(TAG, "active root pkg=${ins.uiAutomation.rootInActiveWindow?.packageName}")
            ins.uiAutomation.windows.forEach { window ->
                Log.e(TAG, "window type=${window.type} active=${window.isActive} pkg=${window.root?.packageName}")
            }
            val dir = requireNotNull(ctx.getExternalFilesDir(null)).resolve("cloud-ui-failures")
            check(dir.mkdirs() || dir.isDirectory)
            ins.uiAutomation.takeScreenshot()?.let { bitmap ->
                try {
                    dir.resolve("otherauth-${UUID.randomUUID()}.png").outputStream()
                        .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
            }
        } catch (e: Exception) { Log.e(TAG, "Could not capture UI evidence", e) }
    }

    // ---------- 用例 1：直接删除的归属授权边界（对照矩阵） ----------

    @Test fun foreignOwnedMediaIsVisibleButDirectDeleteIsDeniedBySystem() = runBlocking {
        val foreign = foreignOwnedFixture()
        val unowned = unownedShellFixture()
        val self = selfOwnedFixture()
        val sentinel = outOfSelectionSentinel()
        // 读可见：READ_MEDIA_IMAGES 覆盖他人/无主/自有行（全量读面）。
        assertTrue("foreign row must be visible", present(foreign.primary))
        assertTrue("unowned row must be visible", present(unowned.primary))
        assertTrue("self row must be visible", present(self.primary))
        // 对照组（现有口径）：自有媒体直接删除成功——应用是拥有者。
        assertEquals(1, ctx.contentResolver.delete(self.primary, null, null))
        assertFalse("own row must be gone after direct delete", present(self.primary))
        // 他人归属媒体：系统拒绝（RecoverableSecurityException），源保留。
        val foreignError = interceptDirectDelete(foreign)
        assertTrue(
            "foreign direct delete must be denied, got: $foreignError",
            foreignError is SecurityException
        )
        Log.i(TAG, "directDelete foreign exception=${foreignError!!.javaClass.name} message=${foreignError.message}")
        assertTrue("foreign row must be retained after denied delete", present(foreign.primary))
        assertTrue("foreign row must exist in shell query", shellRowExists(foreign.name))
        // 无主 shell 行：同等拒绝（NULL owner 不满足写面归属匹配）。
        val unownedError = interceptDirectDelete(unowned)
        assertTrue(
            "unowned direct delete must be denied, got: $unownedError",
            unownedError is SecurityException
        )
        Log.i(TAG, "directDelete unowned exception=${unownedError!!.javaClass.name}")
        assertTrue("unowned row must be retained after denied delete", present(unowned.primary))
        // 集合外哨兵不受影响。
        assertTrue("sentinel must survive", present(sentinel.primary))
        assertTrue("sentinel must survive shell query", shellRowExists(sentinel.name))
    }

    // ---------- 用例 2：未授权时按项结果如实反馈、源保留、UNKNOWN 不扩大 ----------

    @Test fun otherPartyMediaWithoutConsentRetainsSourceWithPerItemResults() = runBlocking {
        val foreign = foreignOwnedFixture()
        val unowned = unownedShellFixture()
        val sentinel = outOfSelectionSentinel()
        val manager = isolatedManager()
        manager.addAllToRecycleBin(listOf(foreign.record(), unowned.record()))
        val observedPendingUris = AtomicReference<List<Uri>?>()
        val observedIntentSender = AtomicReference<IntentSender?>()
        val summary = deleteRecycleBinSelection(
            listOf(foreign.primary, unowned.primary), manager,
            request = fileOps()::deleteImages,
            permission = { result ->
                val needsPermission = result as FileOperationManager.DeleteResult.NeedsPermission
                observedPendingUris.set(needsPermission.pendingUris)
                observedIntentSender.set(needsPermission.intentSender)
                false // 用户未授权：不发起系统对话框
            },
            verify = fileOps()::verifyDeletionState,
            resolveVolume = { null },
            onDeleted = { error("No deletion may be confirmed without consent") }
        )
        // 真实系统授权请求：createDeleteRequest 产生真实 intentSender，待删项如实回传。
        assertEquals(setOf(foreign.primary, unowned.primary), observedPendingUris.get()?.toSet())
        assertNotNull("real createDeleteRequest intentSender must be produced", observedIntentSender.get())
        // 按项结果：未授权 → Cancelled，全部保留在 remaining，无错误伪装。
        assertTrue("summary must report cancelled", summary.cancelled)
        assertTrue("nothing may be deleted without consent", summary.deleted.isEmpty())
        assertEquals(setOf(foreign.primary, unowned.primary), summary.remaining)
        assertNull("denial must not be reported as an error", summary.error)
        // 源保留：app 查询 + shell 查询 + 文件字节三面一致。
        assertTrue(present(foreign.primary)); assertTrue(present(unowned.primary))
        assertTrue(shellRowExists(foreign.name)); assertTrue(shellRowExists(unowned.name))
        assertTrue(shellFileExists(foreign.dataPath)); assertTrue(shellFileExists(unowned.dataPath))
        // UNKNOWN 语义不扩大：可读行核验为 existing 而非 unknown/deleted。
        val verification = fileOps().verifyDeletionState(listOf(foreign.primary, unowned.primary))
        assertEquals(setOf(foreign.primary, unowned.primary), verification.existing)
        assertTrue(verification.unknown.isEmpty())
        assertTrue(verification.deleted.isEmpty())
        // 回收站记录保留、journal 关闭（拒绝不留悬挂 journal）。
        assertEquals(setOf(foreign.id, unowned.id), manager.loadRecycleBin().map { it.id }.toSet())
        assertTrue(manager.pendingDeletionUris().isEmpty())
        // 集合外哨兵不受影响。
        assertTrue(present(sentinel.primary))
        assertTrue(shellRowExists(sentinel.name))
    }

    // ---------- 用例 3：真实系统授权对话框 → 授权 → 真实删除 ----------

    @Test fun foreignOwnedMediaDeletionCompletesThroughRealSystemConsentDialog() = runBlocking {
        val foreign = foreignOwnedFixture()
        val sentinel = outOfSelectionSentinel()
        val manager = isolatedManager()
        manager.addAllToRecycleBin(listOf(foreign.record()))
        bringAppToForeground()
        val summary = deleteRecycleBinSelection(
            listOf(foreign.primary), manager,
            request = fileOps()::deleteImages,
            permission = { result -> grantThroughRealSystemDialog(result, foreign) },
            verify = fileOps()::verifyDeletionState,
            resolveVolume = { null }
        )
        // 授权后由 MediaProvider 真实删除：按项确认 + 记录消费。
        assertEquals(setOf(foreign.primary), summary.deleted)
        assertTrue(summary.remaining.isEmpty())
        assertFalse(summary.cancelled)
        assertNull(summary.error)
        assertFalse("foreign row must be gone (app query)", present(foreign.primary))
        assertFalse("foreign row must be gone (shell query)", shellRowExists(foreign.name))
        assertFalse("foreign file must be gone", shellFileExists(foreign.dataPath))
        assertTrue("recycle record must be consumed", manager.loadRecycleBin().isEmpty())
        assertTrue("journal must be clean", manager.pendingDeletionUris().isEmpty())
        val verification = fileOps().verifyDeletionState(listOf(foreign.primary))
        assertEquals(setOf(foreign.primary), verification.deleted)
        assertTrue(verification.unknown.isEmpty())
        // 集合外哨兵不受影响。
        assertTrue(present(sentinel.primary))
        assertTrue(shellRowExists(sentinel.name))
    }

    // ---------- 用例 4：清理入口组合（deleteMediaUrisBatch）未授权时同样保留 ----------

    @Test fun cleanupEntryCompositionWithoutConsentRetainsForeignSource() = runBlocking {
        val foreign = foreignOwnedFixture()
        val sentinel = outOfSelectionSentinel()
        val observedPendingUris = AtomicReference<List<Uri>?>()
        val summary = deleteInBatches(
            listOf(foreign.primary),
            deleteBatch = { batch ->
                deleteMediaUrisBatch(
                    batch,
                    request = fileOps()::deleteImages,
                    permission = { result ->
                        val needsPermission = result as FileOperationManager.DeleteResult.NeedsPermission
                        observedPendingUris.set(needsPermission.pendingUris)
                        false // 未授权
                    },
                    verify = fileOps()::verifyDeletionState
                )
            },
            onDeleted = { error("No deletion may be confirmed without consent") }
        )
        assertEquals(setOf(foreign.primary), observedPendingUris.get()?.toSet())
        assertTrue(summary.cancelled)
        assertTrue(summary.deleted.isEmpty())
        assertEquals(setOf(foreign.primary), summary.remaining)
        assertTrue("foreign row must be retained", present(foreign.primary))
        assertTrue("foreign row must be retained (shell)", shellRowExists(foreign.name))
        assertTrue("sentinel must survive", present(sentinel.primary))
        assertTrue("sentinel must survive (shell)", shellRowExists(sentinel.name))
    }

    // ---------- 基础设施 ----------

    private fun Row.record(): ImageFile = ImageFile(
        id = id, uri = primary, displayName = name, dateModified = 1L, size = 1L,
        width = 32, height = 32, bucketDisplayName = null, deletedAt = 1L,
        volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY, relativePath = path
    )

    /** 与 VolumeDeletionDeviceTest.isolatedManager 同款隔离目录。 */
    private fun isolatedManager(): RecycleBinManager {
        val directory = File(ctx.cacheDir, "otherauth-" + UUID.randomUUID()).apply { mkdirs() }
        managerDirs.add(directory.name)
        return RecycleBinManager(object : ContextWrapper(ctx) {
            override fun getFilesDir(): File = directory
        })
    }

    @After fun cleanupOnlyOwnedFixtures() = runBlocking {
        val errors = mutableListOf<Throwable>()
        fun attempt(block: () -> Unit) { try { block() } catch (e: Throwable) { errors += e } }
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
        for (directory in managerDirs) {
            attempt {
                val dir = File(ctx.cacheDir, directory)
                check(dir.canonicalFile.parentFile == ctx.cacheDir.canonicalFile)
                dir.deleteRecursively()
            }
        }
        if (errors.isNotEmpty()) {
            val failure = AssertionError("Fixture cleanup failed (${errors.size} errors)")
            errors.forEach { failure.addSuppressed(it) }
            throw failure
        }
    }

    private val seedRoot: String
        get() = File(requireNotNull(ctx.getExternalFilesDir(null)), "otherauth-seeds").absolutePath

    companion object {
        private const val TAG = "OtherMediaAuth"
        private const val FOREIGN_DIR = "Pictures/TabulaForeignAuth"
        private const val SELF_DIR = "Pictures/TabulaSelfAuth"
        private val NULLISH = setOf("NULL", "null", "")
    }
}
