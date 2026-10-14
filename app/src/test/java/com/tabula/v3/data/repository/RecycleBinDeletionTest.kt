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
}
