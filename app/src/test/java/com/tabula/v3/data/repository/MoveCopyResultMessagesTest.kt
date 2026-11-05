package com.tabula.v3.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T07/TC56-RESULT：移动/复制界面反馈消息的分项选择。
 * 目标行为：全部成功只报成功数；未完成项按授权拒绝/失败/选择失效分项如实列出，
 * 只列非零项——不再把授权拒绝与一般失败折叠成同一句"未移动"。
 */
class MoveCopyResultMessagesTest {

    @Test
    fun allMovedWithoutRetentionReportsPlainSuccess() {
        val message = moveImagesResultMessage(
            AlbumManager.MoveImagesResult(movedCount = 5, retainedCount = 0)
        )
        assertEquals("已移动 5 张图片", message)
    }

    @Test
    fun permissionBlockedIsReportedSeparatelyFromGenericFailure() {
        val message = moveImagesResultMessage(
            AlbumManager.MoveImagesResult(
                movedCount = 2,
                retainedCount = 3,
                permissionBlockedCount = 1,
                failedCount = 2
            )
        )
        assertEquals("已移动 2 张，3 张未完成（1 项需系统授权、2 项失败）", message)
    }

    @Test
    fun staleSelectionIsReportedWithoutCallingItFailure() {
        val message = moveImagesResultMessage(
            AlbumManager.MoveImagesResult(movedCount = 0, retainedCount = 2)
        )
        assertEquals("已移动 0 张，2 张未完成（2 项选择已失效）", message)
    }

    @Test
    fun onlyNonzeroPartsAreListed() {
        val message = moveImagesResultMessage(
            AlbumManager.MoveImagesResult(
                movedCount = 1,
                retainedCount = 1,
                permissionBlockedCount = 1,
                failedCount = 0
            )
        )
        assertEquals("已移动 1 张，1 张未完成（1 项需系统授权）", message)
    }

    @Test
    fun copyWithoutRetentionReportsPlainSuccess() {
        val message = copyImagesResultMessage(
            AlbumManager.CopyImagesResult(
                copiedCount = 3,
                permissionBlockedCount = 0,
                failedCount = 0,
                requestedCount = 3
            )
        )
        assertEquals("已复制 3 张图片", message)
    }

    @Test
    fun copyWithPermissionBlockedAndFailureListsBothParts() {
        val message = copyImagesResultMessage(
            AlbumManager.CopyImagesResult(
                copiedCount = 1,
                permissionBlockedCount = 2,
                failedCount = 1,
                requestedCount = 5
            )
        )
        // requested 5 - copied 1 - permission 2 - failed 1 = 1 项失效选择
        assertEquals("已复制 1 张，4 张未完成（2 项需系统授权、1 项失败、1 项选择已失效）", message)
    }
}
