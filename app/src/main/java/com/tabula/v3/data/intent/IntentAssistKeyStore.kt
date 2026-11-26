package com.tabula.v3.data.intent

import android.content.Context
import java.io.File

/**
 * 可选文字辅助的 API Key 存储（**每个服务一个独立文件**）。
 *
 * 明确的设计选择（#94 方案要求）：
 * - 存在 App 私有目录里的**独立文件**，用 [Context.getNoBackupFilesDir]：
 *   这个目录按 Android 定义就不参与云备份与设备迁移，不需要额外的 exclude 规则，
 *   也不会和别的偏好混在一起。
 * - **不使用 Android Keystore**，也不走任何由 Keystore 间接支撑的加密存储：
 *   Key 是用户自带、可随时撤销的普通凭据，加密只会把「文件损坏就全丢」的风险
 *   变成「Keystore 不可用就全丢」，与「文件缺失/损坏时只停用模型辅助」的要求冲突。
 * - 文件权限尽力收紧到 owner-only（Android 上 filesDir 本来就是 0700 的父目录，
 *   这里只是把文件本身也设成同样的权限）。
 * - **永不**写日志、崩溃报告或导出：本类没有任何日志调用，读取失败只返回 null。
 *
 * ## 多服务（本次新增）
 *
 * 服务由 [IntentAssistService] 指定，默认 [IntentAssistService.DEEPSEEK]：
 * - DeepSeek 的文件名仍是 `intent-assist.key` —— **已有用户升级后 Key 不丢**，
 *   构造签名 `IntentAssistKeyStore(context)` / `IntentAssistKeyStore(file)` 的语义也没变；
 * - OpenCode Go 用 `intent-assist.opencode-go.key`。
 *
 * 两个文件互相独立：写/删一个不会碰另一个，因此「换服务后另一个服务的 Key 还在」。
 * 这一点有测试钉住（`IntentOpenCodeGoAssistTest.servicesKeepSeparateKeys`）。
 *
 * 已知边界（必须写进 PR 正文，不要假装没有）：
 * - Root 设备仍可读取该文件；建议用户使用独立、可撤销的 Key。
 */
class IntentAssistKeyStore(
    private val keyFile: File,
    /** 这份存储属于哪个服务。只影响界面文案与诊断，读写都只看 [keyFile]。 */
    val service: IntentAssistService = IntentAssistService.DEEPSEEK
) {

    /** 按服务解析默认文件名（默认 DeepSeek，文件名为 [FILE_NAME]）。 */
    constructor(
        context: Context,
        service: IntentAssistService = IntentAssistService.DEEPSEEK
    ) : this(File(context.noBackupFilesDir, service.keyFileName), service)

    /** 是否已配置 Key（只判断存在且非空，不判断是否有效）。 */
    fun hasKey(): Boolean = read() != null

    /** 读取 Key；文件缺失、空、损坏都返回 null（调用方据此停用辅助，不影响本地结果）。 */
    fun read(): String? {
        if (!keyFile.exists()) return null
        return try {
            val value = keyFile.readText().trim()
            value.ifEmpty { null }
        } catch (e: Throwable) {
            // 刻意不记录异常内容，避免把路径或片段写进日志
            null
        }
    }

    /** 写入/替换 Key。写失败返回 false（界面据此提示，不抛异常）。 */
    fun write(key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return false
        return try {
            keyFile.parentFile?.mkdirs()
            keyFile.writeText(trimmed)
            runCatching { keyFile.setReadable(false, false) }
            runCatching { keyFile.setReadable(true, true) }
            runCatching { keyFile.setWritable(false, false) }
            runCatching { keyFile.setWritable(true, true) }
            true
        } catch (e: Throwable) {
            false
        }
    }

    /** 删除 Key：删除后辅助立刻停用，本地 OCR / 规则 / 排序不受影响。 */
    fun delete(): Boolean = try {
        if (keyFile.exists()) keyFile.delete() else true
    } catch (e: Throwable) {
        false
    }

    /** 仅供诊断展示：Key 是否配置，不回显内容。 */
    fun describeState(): String = when {
        !keyFile.exists() -> "未配置"
        read() == null -> "文件损坏或为空"
        else -> "已配置"
    }

    /**
     * 按服务取一个存储实例——供上层在 Compose 里 `remember` 时少写一层 `File` 拼接。
     *
     * 与 [IntentAssistService.keyStore] 等价，保留两处入口是因为既有调用点用的是
     * `IntentAssistKeyStore(context)` 这种构造函数风格。
     */
    companion object {
        /** DeepSeek（以及 #95 的默认服务）的 Key 文件名。**不要改**，改了老用户就丢 Key。 */
        const val FILE_NAME = "intent-assist.key"

        /** OpenCode Go 的 Key 文件名（新增，独立于 DeepSeek）。 */
        const val OPENCODE_GO_FILE_NAME = "intent-assist.opencode-go.key"

        /** 指定服务的 Key 存储。 */
        fun forService(context: Context, service: IntentAssistService): IntentAssistKeyStore =
            IntentAssistKeyStore(context, service)
    }
}
