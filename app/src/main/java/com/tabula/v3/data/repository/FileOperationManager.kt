package com.tabula.v3.data.repository

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文件操作管理器
 *
 * 专为 Android 14+ (API 34+) 设计的文件操作
 *
 * 主要功能：
 * - 删除图片（移到系统回收站）
 * - 彻底删除图片
 * - 处理 Scoped Storage 权限
 */
class FileOperationManager(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver

    companion object {
        private const val TAG = "FileOperationManager"
        const val REQUEST_DELETE_PERMISSION = 1001
    }

    /**
     * 删除操作结果
     */
    sealed class DeleteResult {
        /**
         * 删除成功
         * @param deleted 成功删除的 URI 集合（支持部分成功场景）
         */
        data class Success(val deleted: Set<Uri>) : DeleteResult()
        
        /**
         * 需要用户授权
         * @param intentSender 用于发起授权请求
         * @param pendingUris 待删除的 URI 列表（授权后需要重试删除）
         */
        data class NeedsPermission(
            val intentSender: IntentSender,
            val pendingUris: List<Uri>
        ) : DeleteResult()
        
        data class Error(val message: String) : DeleteResult()
    }

    /**
     * 删除单张图片
     *
     * Android 14+ 使用 MediaStore.createDeleteRequest 批量删除
     * 会弹出系统确认对话框
     *
     * @param uri 图片 URI
     * @return 删除结果
     */
    suspend fun deleteImage(uri: Uri): DeleteResult = withContext(Dispatchers.IO) {
        try {
            deleteImages(listOf(uri))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete image: ${e.message}")
            DeleteResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * 批量删除图片
     *
     * @param uris 图片 URI 列表
     * @return 删除结果（Success 包含实际成功删除的 URI 集合）
     */
    suspend fun deleteImages(uris: List<Uri>): DeleteResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) {
            return@withContext DeleteResult.Success(emptySet())
        }

        try {
            // Android 11+ (API 30+) 使用 createDeleteRequest
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val uniqueUris = uris.distinct()
                val alreadyDeleted = findAlreadyDeleted(uniqueUris)
                val existingUris = uniqueUris.filterNot { it in alreadyDeleted }
                if (existingUris.isEmpty()) {
                    return@withContext DeleteResult.Success(alreadyDeleted)
                }
                val pendingIntent = MediaStore.createDeleteRequest(contentResolver, existingUris)
                // Keep the original set so verification also accounts for stale recycle-bin entries.
                return@withContext DeleteResult.NeedsPermission(pendingIntent.intentSender, uniqueUris)
            } else {
                // Android 10 及以下直接删除，逐个尝试并记录成功的
                val deleted = mutableSetOf<Uri>()
                uris.forEach { uri ->
                    try {
                        val count = contentResolver.delete(uri, null, null)
                        if (count > 0) {
                            deleted.add(uri)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete $uri: ${e.message}")
                    }
                }
                return@withContext DeleteResult.Success(deleted)
            }
        } catch (e: RecoverableSecurityException) {
            // 权限不足，需要用户确认
            Log.w(TAG, "RecoverableSecurityException: ${e.message}")
            return@withContext DeleteResult.NeedsPermission(e.userAction.actionIntent.intentSender, uris)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}")
            return@withContext DeleteResult.Error("权限不足: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Delete failed: ${e.message}")
            return@withContext DeleteResult.Error(e.message ?: "删除失败")
        }
    }
    
    /**
     * 验证删除结果
     * 
     * 在用户授权后调用，检查哪些 URI 实际被成功删除
     * 
     * @param uris 原本要删除的 URI 列表
     * @return 已被成功删除的 URI 集合
     */
    suspend fun verifyDeletion(uris: List<Uri>): Set<Uri> = withContext(Dispatchers.IO) {
        val deleted = mutableSetOf<Uri>()
        uris.forEach { uri ->
            if (isConfirmedMissing(uri)) {
                // URI 已不存在，说明删除成功
                deleted.add(uri)
            }
        }
        Log.d(TAG, "Verified deletion: ${deleted.size}/${uris.size} URIs deleted")
        deleted
    }

    private fun findAlreadyDeleted(uris: List<Uri>): Set<Uri> {
        // With selected-photo access, an empty query can mean hidden rather than deleted.
        val readPermission = if (Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (context.checkSelfPermission(readPermission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return emptySet()
        }
        return uris.filter { isConfirmedMissing(it) }.toSet()
    }

    private fun isConfirmedMissing(uri: Uri): Boolean {
        return try {
            val queryArgs = android.os.Bundle().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                }
            }
            contentResolver.query(
                uri, arrayOf(MediaStore.Images.Media._ID), queryArgs, null
            )?.use { !it.moveToFirst() } ?: false
        } catch (e: Exception) {
            // Provider/permission failures are not evidence that a photo was deleted.
            Log.w(TAG, "Could not verify deletion of $uri", e)
            false
        }
    }

    /**
     * 移动图片到回收站（Android 12+ 支持）
     *
     * @param uri 图片 URI
     * @return 是否成功
     */
    suspend fun moveToTrash(uri: Uri): DeleteResult = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pendingIntent = MediaStore.createTrashRequest(
                    contentResolver,
                    listOf(uri),
                    true  // 移到回收站
                )
                return@withContext DeleteResult.NeedsPermission(pendingIntent.intentSender, listOf(uri))
            } else {
                // 旧版本直接删除
                return@withContext deleteImage(uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Move to trash failed: ${e.message}")
            return@withContext DeleteResult.Error(e.message ?: "移动失败")
        }
    }

    /**
     * 从回收站恢复图片（Android 12+ 支持）
     *
     * @param uri 图片 URI
     * @return 是否成功
     */
    suspend fun restoreFromTrash(uri: Uri): DeleteResult = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pendingIntent = MediaStore.createTrashRequest(
                    contentResolver,
                    listOf(uri),
                    false  // 从回收站恢复
                )
                return@withContext DeleteResult.NeedsPermission(pendingIntent.intentSender, listOf(uri))
            } else {
                // 旧版本不支持
                return@withContext DeleteResult.Error("此 Android 版本不支持恢复功能")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Restore from trash failed: ${e.message}")
            return@withContext DeleteResult.Error(e.message ?: "恢复失败")
        }
    }

    /**
     * 检查 URI 是否仍然有效
     *
     * @param uri 图片 URI
     * @return 是否有效
     */
    suspend fun isUriValid(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.Media._ID),
                null,
                null,
                null
            )?.use { cursor ->
                cursor.moveToFirst()
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
}

