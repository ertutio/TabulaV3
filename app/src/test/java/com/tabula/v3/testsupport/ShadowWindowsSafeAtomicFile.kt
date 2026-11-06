package com.tabula.v3.testsupport

import android.util.AtomicFile
import android.util.Log
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Windows JVM 安全的 AtomicFile shadow（仅测试树注册，生产行为不变）。
 *
 * 根因（2026-11-06 本机门禁 5 失败的定位）：android-all 14 的 AtomicFile
 * 私有 rename 用 java.io.File.renameTo——POSIX rename(2) 会原子替换已存在的
 * 目标，Windows JVM 的 renameTo 在目标存在时失败，且 AOSP 对失败只记日志
 * 不抛出。多次写循环后 finishWrite 的 ".new"→base 重命名静默失效，base 停留
 * 旧内容，AtomicRecycleBinStorage 的提交读回校验（RecycleBinManager.kt:45）
 * 如实抛出"回收站文件提交失败"——CI Linux 全绿、Windows 本机 5 失败的完整
 * 解释；同族即登记在案的 atomicReadRestoresLegacyBackup 环境项。
 *
 * 本 shadow 用 Files.move(REPLACE_EXISTING[, ATOMIC_MOVE]) 对齐 Android
 * rename(2) 的替换语义；失败仍按 AOSP 契约只记日志不抛出（调用方的读回
 * 校验兜底，与 Android 行为一致）。仅覆盖三个含 rename 的方法——startWrite
 * （legacy .bak 回迁）、finishWrite（.new→base 提交）、openRead（.bak 回迁 +
 * 过期 .new 清理）；其余方法（failWrite/delete/truncate/openAppend 等）无
 * rename 依赖，回退真实实现。
 *
 * 适用范围（审计限定）：本 shadow 按 API 34 的 AtomicFile 协议实现（.new 提交
 * + legacy .bak 回迁，android-all 14 字节码核对）；`minSdk = 34` 限定只在已
 * 验证的 SDK 上生效——更旧 SDK（如 28）的 startWrite 是"base→bak 先备份"
 * 的旧协议，不可直接套用本 shadow，回退真实实现。
 *
 * 等价性声明（审计限定）：已验证 Windows JVM（本机全量）与 Linux CI（同
 * shadow）的替换语义；不据此宣称与 Android 各文件系统/内核在全平台的全面
 * 等价——生产路径仍在 Android 真实 rename(2) 上运行，本 shadow 仅测试树。
 */
@Implements(value = AtomicFile::class, minSdk = 34)
class ShadowWindowsSafeAtomicFile {
    @RealObject
    lateinit var real: AtomicFile

    private val baseName: File get() = real.baseFile
    private val newName: File get() = File(baseName.path + ".new")
    private val legacyBackupName: File get() = File(baseName.path + ".bak")

    /** AOSP rename 语义：目标已存在则原子替换；失败只记日志，不抛出。 */
    private fun rename(src: File, dest: File) {
        try {
            try {
                Files.move(
                    src.toPath(), dest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            Log.e("AtomicFile", "Failed to rename $src to $dest", e)
        }
    }

    @Implementation
    @Throws(IOException::class)
    fun startWrite(length: Long): FileOutputStream {
        if (legacyBackupName.exists()) {
            rename(legacyBackupName, baseName)
        }
        return try {
            FileOutputStream(newName)
        } catch (e: IOException) {
            val parent = newName.parentFile
                ?: throw IOException("Failed to create directory for $newName", e)
            if (!parent.mkdirs() && !parent.isDirectory) {
                throw IOException("Failed to create directory for $newName", e)
            }
            FileOutputStream(newName)
        }
    }

    @Implementation
    fun finishWrite(str: FileOutputStream?) {
        if (str == null) return
        // 审计轮：sync 与 close 分开处理——sync 失败后仍尝试关闭流
        // （对齐 AOSP finishWrite 的独立 try 块故障语义）。
        try {
            str.fd.sync()
        } catch (e: IOException) {
            Log.e("AtomicFile", "Failed to sync file output stream", e)
        }
        try {
            str.close()
        } catch (e: IOException) {
            Log.e("AtomicFile", "Failed to close file output stream", e)
        }
        rename(newName, baseName)
    }

    @Implementation
    @Throws(FileNotFoundException::class)
    fun openRead(): FileInputStream {
        if (legacyBackupName.exists()) {
            rename(legacyBackupName, baseName)
        }
        if (newName.exists() && baseName.exists() && !newName.delete()) {
            Log.e("AtomicFile", "Failed to delete outdated new file $newName")
        }
        return FileInputStream(baseName)
    }
}
