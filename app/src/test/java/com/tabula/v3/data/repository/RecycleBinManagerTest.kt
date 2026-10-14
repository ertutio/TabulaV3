package com.tabula.v3.data.repository

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal class MemoryRecycleBinStorage(var content: String? = null) : RecycleBinStorage {
    var failRead = false
    var failWrite = false
    var writes = 0
    override fun read(): String? {
        if (failRead) throw IOException("Injected read failure")
        return content
    }
    override fun write(content: String) {
        if (failWrite) throw IOException("Injected write failure")
        writes++
        this.content = content
    }
}

internal fun fixturePhoto(id: Long) = ImageFile(
    id, Uri.parse("content://media/external/images/media/$id"), "test.jpg",
    1L, 100L, 10, 10, null, deletedAt = 1L
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RecycleBinManagerTest {
    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); fail("Expected persistence failure") }
        catch (expected: RecycleBinPersistenceException) { assertNotNull(expected.cause) }
    }

    @Test fun missingFileIsEmptyButUnreadableFileDoesNotOverwrite() = runBlocking {
        val storage = MemoryRecycleBinStorage()
        val manager = RecycleBinManager(storage, MemoryRecycleBinStorage())
        assertTrue(manager.loadRecycleBin().isEmpty())
        manager.addToRecycleBin(fixturePhoto(1))
        val old = storage.content
        val writes = storage.writes
        storage.failRead = true
        expectFailure { manager.loadRecycleBin() }
        expectFailure { manager.addToRecycleBin(fixturePhoto(2)) }
        expectFailure { manager.clearRecycleBin() }
        assertEquals(old, storage.content)
        assertEquals(writes, storage.writes)
    }

    @Test fun corruptAndBlankFilesAreFailures() = runBlocking {
        for (raw in listOf("", " ", "{bad", "[{}]")) {
            val storage = MemoryRecycleBinStorage(raw)
            val manager = RecycleBinManager(storage, MemoryRecycleBinStorage())
            expectFailure { manager.removeAllFromRecycleBin(listOf(fixturePhoto(1))) }
            assertEquals(raw, storage.content)
            assertEquals(0, storage.writes)
        }
    }

    @Test fun failedSavePropagatesAndRetainsOldRecords() = runBlocking {
        val storage = MemoryRecycleBinStorage()
        val manager = RecycleBinManager(storage, MemoryRecycleBinStorage())
        manager.addAllToRecycleBin(listOf(fixturePhoto(1), fixturePhoto(2)))
        val old = storage.content
        storage.failWrite = true
        expectFailure { manager.removeFromRecycleBin(fixturePhoto(1)) }
        expectFailure { manager.addToRecycleBin(fixturePhoto(3)) }
        assertEquals(old, storage.content)
    }

    @Test fun realAtomicFileWriteFailurePreservesCommittedFile() = runBlocking {
        val directory = Files.createTempDirectory("tabula-atomic").toFile()
        val file = File(directory, "recycle_bin.json")
        val manager = RecycleBinManager(AtomicRecycleBinStorage(file),
            AtomicRecycleBinStorage(File(directory, "pending.json")))
        manager.addToRecycleBin(fixturePhoto(1))
        val committed = file.readText()
        val failing = RecycleBinManager(
            AtomicRecycleBinStorage(file, writeBytes = { _, _ -> throw IOException("Injected write failure") }),
            AtomicRecycleBinStorage(File(directory, "pending.json"))
        )
        expectFailure { failing.removeFromRecycleBin(fixturePhoto(1)) }
        assertEquals(committed, file.readText())
        assertEquals(listOf(1L), manager.loadRecycleBin().map { it.id })
    }

    @Test fun realDirectoryReadFailureIsNotAnEmptyRecycleBin() = runBlocking {
        val directory = Files.createTempDirectory("tabula-read").toFile()
        val file = File(directory, "recycle_bin.json")
        assertTrue(file.mkdir())
        val manager = RecycleBinManager(AtomicRecycleBinStorage(file),
            AtomicRecycleBinStorage(File(directory, "pending.json")))
        expectFailure { manager.loadRecycleBin() }
        expectFailure { manager.removeFromRecycleBin(fixturePhoto(1)) }
        assertTrue(file.isDirectory)
    }

    @Test fun atomicReadRestoresLegacyBackup() = runBlocking {
        val directory = Files.createTempDirectory("tabula-backup").toFile()
        val file = File(directory, "recycle_bin.json")
        val store = AtomicRecycleBinStorage(file)
        val manager = RecycleBinManager(store, MemoryRecycleBinStorage())
        manager.addToRecycleBin(fixturePhoto(1))
        File(file.path + ".bak").writeText(file.readText())
        file.writeText("truncated")
        assertEquals(listOf(1L), manager.loadRecycleBin().map { it.id })
    }

    @Test fun concurrentUpdatesDoNotLoseItemsAndDuplicatesStayUnique() = runBlocking {
        val manager = RecycleBinManager(MemoryRecycleBinStorage(), MemoryRecycleBinStorage())
        (1L..40L).map { async { manager.addToRecycleBin(fixturePhoto(it)) } }.awaitAll()
        assertEquals((1L..40L).toSet(), manager.loadRecycleBin().map { it.id }.toSet())
        manager.addAllToRecycleBin(listOf(fixturePhoto(1), fixturePhoto(1), fixturePhoto(41)))
        assertEquals(41, manager.getRecycleBinCount())
        (1L..20L).map { async { manager.removeFromRecycleBin(fixturePhoto(it)) } }.awaitAll()
        assertEquals((21L..41L).toSet(), manager.loadRecycleBin().map { it.id }.toSet())
    }

    @Test fun migrationKeepsOrderAndFailsVisiblyIfItCannotSave() = runBlocking {
        val store = MemoryRecycleBinStorage()
        val manager = RecycleBinManager(store, MemoryRecycleBinStorage())
        manager.addAllToRecycleBin(listOf(fixturePhoto(3), fixturePhoto(1)))
        val legacy = org.json.JSONArray(store.content)
        for (i in 0 until legacy.length()) legacy.getJSONObject(i).remove("deletedAt")
        store.content = legacy.toString()
        val old = store.content
        store.failWrite = true
        expectFailure { manager.loadRecycleBin() }
        assertEquals(old, store.content)
        store.failWrite = false
        val migrated = manager.loadRecycleBin()
        assertEquals(listOf(3L, 1L), migrated.map { it.id })
        assertEquals(listOf(1L, 2L), migrated.map { it.deletedAt })
    }

    @Test fun newManagerRestoresPendingJournalAndCannotOverwriteIt() = runBlocking {
        val directory = Files.createTempDirectory("tabula-journal").toFile()
        val record = File(directory, "records.json")
        val journal = File(directory, "pending.json")
        val manager = RecycleBinManager(AtomicRecycleBinStorage(record), AtomicRecycleBinStorage(journal))
        manager.addToRecycleBin(fixturePhoto(1))
        manager.beginDeletion(listOf(fixturePhoto(1).uri))
        val recreated = RecycleBinManager(AtomicRecycleBinStorage(record), AtomicRecycleBinStorage(journal))
        assertEquals(listOf(fixturePhoto(1).uri), recreated.pendingDeletionUris())
        expectFailure { recreated.beginDeletion(listOf(fixturePhoto(2).uri)) }
        assertEquals(listOf(fixturePhoto(1).uri), recreated.pendingDeletionUris())
    }

    @Test fun failedPartialWriteRollsBackAndSilentCommitFailureIsDetected() = runBlocking {
        val directory = Files.createTempDirectory("tabula-rollback").toFile()
        val file = File(directory, "records.json")
        val healthy = RecycleBinManager(AtomicRecycleBinStorage(file), MemoryRecycleBinStorage())
        healthy.addToRecycleBin(fixturePhoto(1))
        val old = file.readText()
        val partial = RecycleBinManager(AtomicRecycleBinStorage(file, writeBytes = { stream, bytes ->
            stream.write(bytes, 0, bytes.size / 2)
            throw IOException("Injected disk failure during write")
        }), MemoryRecycleBinStorage())
        expectFailure { partial.removeFromRecycleBin(fixturePhoto(1)) }
        assertEquals(old, file.readText())
        val uncommitted = RecycleBinManager(AtomicRecycleBinStorage(file, commit = { _, stream ->
            stream.close() // Simulate AtomicFile logging a failed rename without throwing.
        }), MemoryRecycleBinStorage())
        expectFailure { uncommitted.removeFromRecycleBin(fixturePhoto(1)) }
        assertEquals(old, file.readText())
        assertEquals(listOf(1L), healthy.loadRecycleBin().map { it.id })
    }
}
