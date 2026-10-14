package com.tabula.v3.data.repository

import android.net.Uri

class RecycleBinCommitException(val physicallyDeleted: Set<Uri>, cause: Exception) :
    Exception("照片已处理，但回收站记录未保存；已停止后续删除，请重试核对", cause)

/** Recovery only queries and commits records: it must never launch another delete request. */
internal suspend fun reconcileRecycleBinDeletion(
    manager: RecycleBinManager,
    verify: suspend (List<Uri>) -> DeletionVerification
): DeletionVerification {
    val pending = manager.pendingDeletionUris()
    if (pending.isEmpty()) return DeletionVerification(emptySet(), emptySet(), emptySet())
    val state = verify(pending)
    manager.finishDeletion(state.deleted, state.unknown)
    return state
}

internal suspend fun deleteRecycleBinBatch(
    uris: List<Uri>,
    manager: RecycleBinManager,
    request: suspend (List<Uri>) -> FileOperationManager.DeleteResult,
    permission: suspend (FileOperationManager.DeleteResult.NeedsPermission) -> Boolean,
    verify: suspend (List<Uri>) -> DeletionVerification
): BatchDeleteResult<Uri> {
    manager.beginDeletion(uris)
    val state = when (val result = request(uris)) {
        is FileOperationManager.DeleteResult.Error -> {
            manager.finishDeletion(emptySet())
            return result.asBatchFailure()
        }
        is FileOperationManager.DeleteResult.Success ->
            DeletionVerification(result.deleted, uris.toSet() - result.deleted, emptySet())
        is FileOperationManager.DeleteResult.NeedsPermission -> {
            if (!permission(result)) {
                manager.finishDeletion(emptySet())
                return BatchDeleteResult.Cancelled
            }
            verify(result.pendingUris)
        }
    }
    try {
        manager.finishDeletion(state.deleted, state.unknown)
    } catch (e: RecycleBinPersistenceException) {
        throw RecycleBinCommitException(state.deleted, e)
    }
    return BatchDeleteResult.Completed(
        state.deleted,
        if (state.unknown.isEmpty()) null else "部分照片无法确认删除结果，请检查完整照片访问权限和存储卷后重试核对"
    )
}
