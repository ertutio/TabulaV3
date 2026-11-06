package com.tabula.v3.data.repository

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RecycleBinDeletionTest {
    private fun volumePhoto(id: Long) = fixturePhoto(id).copy(
        uri = Uri.parse("content://media/external_primary/images/media/$id"),
        volumeName = "external_primary"
    )

    private suspend fun mixedSelectionKeepsPendingUnknown(outcome: String) {
        val records = MemoryRecycleBinStorage()
        val journal = MemoryRecycleBinStorage()
        val manager = RecycleBinManager(records, journal)
        val unknown = volumePhoto(1)
        val other = volumePhoto(2)
        manager.addAllToRecycleBin(listOf(unknown, other))
        manager.beginDeletion(listOf(unknown.uri))
        val requests = mutableListOf<List<Uri>>()
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val sender = android.app.PendingIntent.getBroadcast(context, 3,
            android.content.Intent("mixed-delete"), android.app.PendingIntent.FLAG_IMMUTABLE).intentSender
        val summary = deleteRecycleBinSelection(listOf(unknown.uri, other.uri), manager,
            request = {
                requests += it.toList()
                when (outcome) {
                    "success" -> FileOperationManager.DeleteResult.Success(it.toSet())
                    "error" -> FileOperationManager.DeleteResult.Error("Injected service error", DeleteFailureKind.SERVICE)
                    else -> FileOperationManager.DeleteResult.NeedsPermission(sender, it)
                }
            },
            permission = { false },
            verify = { DeletionVerification(emptySet(), emptySet(), it.toSet()) })
        // Rebuilding the manager must retain both the unresolved record and its old intent.
        val rebuilt = RecycleBinManager(records, journal)
        assertEquals(listOf(unknown.uri), rebuilt.pendingDeletionUris())
        assertEquals(listOf(listOf(other.uri)), requests)
        assertEquals(if (outcome == "success") setOf(other.uri) else emptySet<Uri>(), summary.deleted)
        assertEquals(if (outcome == "success") setOf(unknown.uri) else setOf(unknown.uri, other.uri), summary.remaining)
        assertEquals(outcome == "cancel", summary.cancelled)
        assertEquals(outcome == "error", summary.error != null)
        assertEquals(if (outcome == "success") listOf(unknown.uri) else listOf(unknown.uri, other.uri),
            rebuilt.loadRecycleBin().map { it.uri })
    }

    @Test fun selectedPendingUnknownIsIsolatedFromSuccessfulDeletion() = runBlocking {
        mixedSelectionKeepsPendingUnknown("success")
    }

    @Test fun selectedPendingUnknownSurvivesUnrelatedServiceError() = runBlocking {
        mixedSelectionKeepsPendingUnknown("error")
    }

    @Test fun selectedPendingUnknownSurvivesUnrelatedPermissionCancellation() = runBlocking {
        mixedSelectionKeepsPendingUnknown("cancel")
    }

    @Test fun unselectedPendingUnknownDoesNotCountAsRemaining() = runBlocking {
        val manager = RecycleBinManager(MemoryRecycleBinStorage(), MemoryRecycleBinStorage())
        val unknown = volumePhoto(1)
        val selected = volumePhoto(2)
        manager.addAllToRecycleBin(listOf(unknown, selected))
        manager.beginDeletion(listOf(unknown.uri))
        val summary = deleteRecycleBinSelection(listOf(selected.uri), manager,
            request = { FileOperationManager.DeleteResult.Success(it.toSet()) },
            permission = { error("Unexpected permission") },
            verify = { DeletionVerification(emptySet(), emptySet(), it.toSet()) })
        assertEquals(setOf(selected.uri), summary.deleted)
        assertTrue(summary.remaining.isEmpty())
        assertEquals(listOf(unknown.uri), manager.pendingDeletionUris())
    }

    @Test fun pendingUnknownCanLaterResolveExistingOrDeletedWithoutReissuingUnknown() = runBlocking {
        for (wasDeleted in listOf(false, true)) {
            val records = MemoryRecycleBinStorage()
            val journal = MemoryRecycleBinStorage()
            var manager = RecycleBinManager(records, journal)
            val photo = volumePhoto(1)
            manager.addToRecycleBin(photo)
            manager.beginDeletion(listOf(photo.uri))
            val requests = mutableListOf<List<Uri>>()
            val unknown = deleteRecycleBinSelection(listOf(photo.uri), manager,
                request = { requests += it.toList(); FileOperationManager.DeleteResult.Success(it.toSet()) },
                permission = { false },
                verify = { DeletionVerification(emptySet(), emptySet(), it.toSet()) })
            assertTrue(requests.isEmpty())
            assertEquals(setOf(photo.uri), unknown.remaining)
            manager = RecycleBinManager(records, journal)
            val resolved = deleteRecycleBinSelection(listOf(photo.uri), manager,
                request = { requests += it.toList(); FileOperationManager.DeleteResult.Success(it.toSet()) },
                permission = { false },
                verify = {
                    if (wasDeleted) DeletionVerification(it.toSet(), emptySet(), emptySet())
                    else DeletionVerification(emptySet(), it.toSet(), emptySet())
                })
            assertEquals(if (wasDeleted) emptyList<List<Uri>>() else listOf(listOf(photo.uri)), requests)
            assertTrue(resolved.remaining.isEmpty())
            assertTrue(manager.pendingDeletionUris().isEmpty())
            assertTrue(manager.loadRecycleBin().isEmpty())
        }
    }

    @Test fun saveFailureStopsTaskAndRecoveryNeverDeletesAgain() = runBlocking {
        val records = MemoryRecycleBinStorage()
        val journal = MemoryRecycleBinStorage()
        val manager = RecycleBinManager(records, journal)
        val photo = fixturePhoto(1)
        manager.addToRecycleBin(photo)
        var requests = 0
        try {
            deleteRecycleBinBatch(listOf(photo.uri), manager,
                request = {
                    requests++
                    records.failWrite = true
                    FileOperationManager.DeleteResult.Success(it.toSet())
                }, permission = { error("Unexpected permission") },
                verify = { error("Unexpected query") })
            fail("Must report record commit failure")
        } catch (e: RecycleBinCommitException) {
            assertEquals(setOf(photo.uri), e.physicallyDeleted)
        }
        assertEquals(listOf(photo.uri), manager.pendingDeletionUris())
        assertEquals(listOf(1L), manager.loadRecycleBin().map { it.id })
        records.failWrite = false
        val recovered = reconcileRecycleBinDeletion(manager) {
            DeletionVerification(it.toSet(), emptySet(), emptySet())
        }
        assertEquals(setOf(photo.uri), recovered.deleted)
        assertTrue(manager.loadRecycleBin().isEmpty())
        assertTrue(manager.pendingDeletionUris().isEmpty())
        assertEquals(1, requests)
    }

    @Test fun journalCommitFailureCanBeReconciledIdempotently() = runBlocking {
        val records = MemoryRecycleBinStorage()
        val journal = MemoryRecycleBinStorage()
        val manager = RecycleBinManager(records, journal)
        val uri = fixturePhoto(1).uri
        manager.addToRecycleBin(fixturePhoto(1))
        try {
            deleteRecycleBinBatch(listOf(uri), manager, request = {
                journal.failWrite = true
                FileOperationManager.DeleteResult.Success(it.toSet())
            }, permission = { false }, verify = { error("Unused") })
            fail("Journal commit failure must be visible")
        } catch (expected: RecycleBinCommitException) { }
        assertTrue(manager.loadRecycleBin().isEmpty())
        journal.failWrite = false
        reconcileRecycleBinDeletion(manager) { DeletionVerification(it.toSet(), emptySet(), emptySet()) }
        assertTrue(manager.pendingDeletionUris().isEmpty())
        assertTrue(manager.loadRecycleBin().isEmpty())
    }

    @Test fun failedJournalPreparationDoesNotRequestDeletion() = runBlocking {
        val records = MemoryRecycleBinStorage()
        val journal = MemoryRecycleBinStorage().apply { failWrite = true }
        val manager = RecycleBinManager(records, journal)
        manager.addToRecycleBin(fixturePhoto(1))
        try {
            deleteRecycleBinBatch(listOf(fixturePhoto(1).uri), manager,
                request = { fail("No irreversible request before journal"); error("Unused") },
                permission = { false }, verify = { error("Unused") })
            fail("Expected failure")
        } catch (expected: RecycleBinPersistenceException) { }
        assertEquals(1, manager.getRecycleBinCount())
    }

    @Test fun globalErrorsAreNotSplitAndStopAfterOneRequest() = runBlocking {
        for (kind in listOf(DeleteFailureKind.PERMISSION, DeleteFailureKind.SERVICE, DeleteFailureKind.UNKNOWN)) {
            val manager = RecycleBinManager(MemoryRecycleBinStorage(), MemoryRecycleBinStorage())
            val photos = (1L..250L).map { fixturePhoto(it) }
            manager.addAllToRecycleBin(photos)
            var calls = 0
            val summary = deleteInBatches(photos.map { it.uri }, deleteBatch = { batch ->
                deleteRecycleBinBatch(batch, manager, request = {
                    calls++
                    FileOperationManager.DeleteResult.Error("Injected global failure", kind)
                }, permission = { false }, verify = { error("Unused") })
            }, onDeleted = { fail("No deleted photos") })
            assertEquals(1, calls)
            assertEquals(250, summary.remaining.size)
            assertEquals(250, manager.getRecycleBinCount())
            assertTrue(manager.pendingDeletionUris().isEmpty())
        }
    }

    @Test fun invalidItemIsIsolatedWithoutLosingOtherRecords() = runBlocking {
        val manager = RecycleBinManager(MemoryRecycleBinStorage(), MemoryRecycleBinStorage())
        val photos = (1L..4L).map { fixturePhoto(it) }
        val bad = photos[1].uri
        manager.addAllToRecycleBin(photos)
        val summary = deleteInBatches(photos.map { it.uri }, deleteBatch = { batch ->
            deleteRecycleBinBatch(batch, manager, request = {
                if (bad in it) FileOperationManager.DeleteResult.Error("Invalid URI", DeleteFailureKind.INVALID_REQUEST)
                else FileOperationManager.DeleteResult.Success(it.toSet())
            }, permission = { false }, verify = { error("Unused") })
        }, onDeleted = {})
        assertEquals(setOf(bad), summary.remaining)
        assertEquals(listOf(2L), manager.loadRecycleBin().map { it.id })
    }

    @Test fun recoveryKeepsUnknownAndExistingPhotosDistinct() = runBlocking {
        val manager = RecycleBinManager(MemoryRecycleBinStorage(), MemoryRecycleBinStorage())
        val photos = (1L..3L).map { fixturePhoto(it) }
        manager.addAllToRecycleBin(photos)
        manager.beginDeletion(photos.map { it.uri })
        val recovered = reconcileRecycleBinDeletion(manager) {
            DeletionVerification(setOf(photos[0].uri), setOf(photos[1].uri), setOf(photos[2].uri))
        }
        assertEquals(setOf(photos[2].uri), recovered.unknown)
        assertEquals(listOf(2L, 3L), manager.loadRecycleBin().map { it.id })
        assertEquals(listOf(photos[2].uri), manager.pendingDeletionUris())
    }

    @Test fun cancelledCoroutineLeavesJournalForLaterRecovery() = runBlocking {
        val manager = RecycleBinManager(MemoryRecycleBinStorage(), MemoryRecycleBinStorage())
        val uri = fixturePhoto(1).uri
        manager.addToRecycleBin(fixturePhoto(1))
        try {
            deleteRecycleBinBatch(listOf(uri), manager,
                request = { throw CancellationException("Activity disposed") },
                permission = { false }, verify = { error("Unused") })
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) { }
        assertEquals(listOf(uri), manager.pendingDeletionUris())
        reconcileRecycleBinDeletion(manager) { DeletionVerification(emptySet(), it.toSet(), emptySet()) }
        assertEquals(1, manager.getRecycleBinCount())
        assertTrue(manager.pendingDeletionUris().isEmpty())
    }

    @Test fun unknownPermissionResultStopsFurtherDeletionAndKeepsJournal() = runBlocking {
        val manager = RecycleBinManager(MemoryRecycleBinStorage(), MemoryRecycleBinStorage())
        val photos = (1L..101L).map { fixturePhoto(it) }
        manager.addAllToRecycleBin(photos)
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val sender = android.app.PendingIntent.getBroadcast(context, 1, android.content.Intent("test-delete"),
            android.app.PendingIntent.FLAG_IMMUTABLE).intentSender
        var requests = 0
        val summary = deleteInBatches(photos.map { it.uri }, deleteBatch = { batch ->
            deleteRecycleBinBatch(batch, manager,
                request = { requests++; FileOperationManager.DeleteResult.NeedsPermission(sender, it) },
                permission = { true },
                verify = { DeletionVerification(setOf(it.first()), emptySet(), it.drop(1).toSet()) })
        }, onDeleted = {})
        assertEquals(1, requests)
        assertEquals(1, summary.deleted.size)
        assertNotNull(summary.error)
        assertEquals(99, manager.pendingDeletionUris().size)
        assertEquals(100, manager.getRecycleBinCount())
    }

    @Test fun systemCancellationLeavesRecordsAndClearsUnissuedRecoveryWork() = runBlocking {
        val manager = RecycleBinManager(MemoryRecycleBinStorage(), MemoryRecycleBinStorage())
        val photo = fixturePhoto(1)
        manager.addToRecycleBin(photo)
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val sender = android.app.PendingIntent.getBroadcast(context, 2, android.content.Intent("cancel-delete"),
            android.app.PendingIntent.FLAG_IMMUTABLE).intentSender
        val result = deleteRecycleBinBatch(listOf(photo.uri), manager,
            request = { FileOperationManager.DeleteResult.NeedsPermission(sender, it) },
            permission = { false }, verify = { error("Must not verify an unapproved request") })
        assertEquals(BatchDeleteResult.Cancelled, result)
        assertEquals(1, manager.getRecycleBinCount())
        assertTrue(manager.pendingDeletionUris().isEmpty())
    }

    @Test
    fun twoThousandOneRecycleRecordsSplitThroughProductionComposition() = runBlocking {
        // C2/T04 登记候选（回收站入口，仅测试）：2001 条回收站记录经生产组合
        // （deleteRecycleBinSelection = deleteInBatches + deleteRecycleBinBatch，
        // 即回收站 UI 批量清除的实际形态）分批——每请求 ≤100 项、顺序无丢失
        // 重复、逐批授权+核验、已确认 2000 条按批落盘（增量交付 20 次）、
        // 末批服务错误停止且不把整体结果当每项结果。SERVICE 停止后续批与
        // SERVICE 不可拆分分别由 globalErrorsAreNotSplitAndStopAfterOneRequest
        // 与 BatchDeletionTest 套件层钉死，此处不重复。
        val all = (1..2001).map { volumePhoto(it.toLong()) }
        val manager = RecycleBinManager(MemoryRecycleBinStorage(), MemoryRecycleBinStorage())
        manager.addAllToRecycleBin(all)

        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val sender = android.app.PendingIntent.getBroadcast(context, 7,
            android.content.Intent("recycle-bin-2001"), android.app.PendingIntent.FLAG_IMMUTABLE).intentSender
        val requests = mutableListOf<List<Uri>>()
        val delivered = mutableListOf<Set<Uri>>()

        val summary = deleteRecycleBinSelection(
            uris = all.map { it.uri },
            manager = manager,
            request = { uris ->
                requests += uris
                if (uris.size == 1) {
                    // 末批（1 条）服务错误：不可拆分失败并停止。
                    FileOperationManager.DeleteResult.Error("删除服务不可用", DeleteFailureKind.SERVICE)
                } else {
                    FileOperationManager.DeleteResult.NeedsPermission(sender, uris)
                }
            },
            permission = { true },
            verify = { DeletionVerification(it.toSet(), emptySet(), emptySet()) },
            onDeleted = { delivered += it }
        )

        val allUris = all.map { it.uri }
        assertEquals(21, requests.size)
        assertEquals((1..20).map { 100 }, requests.take(20).map { it.size })
        assertEquals(1, requests.last().size)
        assertEquals(allUris, requests.flatten())
        // 按项结果逐批更新回收站记录：前 2000 条已出站，仅末条留存；无悬挂 journal。
        assertEquals(allUris.dropLast(1).toSet(), summary.deleted)
        assertEquals(setOf(allUris[2000]), summary.remaining)
        assertEquals(false, summary.cancelled)
        assertEquals("删除服务不可用", summary.error)
        assertEquals(20, delivered.size)
        assertEquals(allUris.dropLast(1), delivered.flatten())
        assertEquals(2000, delivered.flatten().toSet().size)
        assertEquals(listOf(allUris[2000]), manager.loadRecycleBin().map { it.uri })
        assertTrue(manager.pendingDeletionUris().isEmpty())
    }
}
