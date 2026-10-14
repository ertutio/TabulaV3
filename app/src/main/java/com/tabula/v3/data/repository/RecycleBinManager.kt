package com.tabula.v3.data.repository

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.tabula.v3.data.model.ImageFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal interface RecycleBinStorage {
    fun read(): String?
    fun write(content: String)
}

internal class AtomicRecycleBinStorage(file: File) : RecycleBinStorage {
    private val atomic = AtomicFile(file)
    override fun read(): String? {
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return null
        return atomic.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
    }
    override fun write(content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
        // Android AtomicFile can log rename failures rather than throw.
        if (read() != content) throw IOException("回收站文件提交失败")
    }
}

class RecycleBinPersistenceException(cause: Exception) : IOException("回收站记录未能读取或保存", cause)

/** All read-modify-write transactions, including migration and the recovery journal, share a lock. */
class RecycleBinManager internal constructor(
    private val storage: RecycleBinStorage,
    private val pendingStorage: RecycleBinStorage
) {
    constructor(context: Context) : this(
        AtomicRecycleBinStorage(File(context.filesDir, "recycle_bin.json")),
        AtomicRecycleBinStorage(File(context.filesDir, "recycle_bin_pending.json"))
    )

    companion object {
        @Volatile private var instance: RecycleBinManager? = null
        fun getInstance(context: Context): RecycleBinManager =
            instance ?: synchronized(this) {
                instance ?: RecycleBinManager(context.applicationContext).also { instance = it }
            }
    }

    private val mutex = Mutex()

    private suspend fun <T> transaction(action: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RecycleBinPersistenceException) {
                throw e
            } catch (e: Exception) {
                throw RecycleBinPersistenceException(e)
            }
        }
    }

    suspend fun addToRecycleBin(image: ImageFile) = addAllToRecycleBin(listOf(image))

    suspend fun addAllToRecycleBin(images: List<ImageFile>) = transaction {
        val items = loadUnlocked().toMutableList()
        val ids = items.map { it.id }.toMutableSet()
        val now = System.currentTimeMillis()
        images.forEach { if (ids.add(it.id)) items.add(it.copy(deletedAt = now)) }
        saveUnlocked(items)
    }

    suspend fun removeFromRecycleBin(image: ImageFile) = removeAllFromRecycleBin(listOf(image))

    suspend fun removeAllFromRecycleBin(images: List<ImageFile>) = transaction {
        val ids = images.map { it.id }.toSet()
        saveUnlocked(loadUnlocked().filterNot { it.id in ids })
    }

    suspend fun clearRecycleBin() = transaction {
        loadUnlocked() // A failed read must never be overwritten with an empty list.
        saveUnlocked(emptyList())
    }

    suspend fun loadRecycleBin(): List<ImageFile> = transaction { loadUnlocked() }
    suspend fun getRecycleBinCount(): Int = loadRecycleBin().size

    suspend fun pendingDeletionUris(): List<Uri> = transaction { pendingUnlocked() }

    /** Persist intent before handing any irreversible request to the system. */
    suspend fun beginDeletion(uris: List<Uri>) = transaction {
        loadUnlocked()
        check(pendingUnlocked().isEmpty()) { "上次删除尚未核对" }
        savePendingUnlocked(uris.distinct())
    }

    /** Commit the records first. If either write fails, recovery is safe to repeat. */
    suspend fun finishDeletion(deleted: Set<Uri>, unknown: Set<Uri> = emptySet()) = transaction {
        val pending = pendingUnlocked().toSet()
        require(deleted.all { it in pending } && unknown.all { it in pending })
        val items = loadUnlocked()
        if (deleted.isNotEmpty()) saveUnlocked(items.filterNot { it.uri in deleted })
        savePendingUnlocked(unknown.toList())
    }

    private fun pendingUnlocked(): List<Uri> {
        val raw = pendingStorage.read() ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { Uri.parse(array.getString(it)) }.distinct()
    }

    private fun savePendingUnlocked(uris: List<Uri>) {
        pendingStorage.write(JSONArray(uris.map { it.toString() }).toString())
    }

    private fun loadUnlocked(): List<ImageFile> {
        val raw = storage.read() ?: return emptyList()
        // A present but truncated/blank file is corruption, not an empty recycle bin.
        val array = JSONArray(raw)
        val items = (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            ImageFile(
                id = obj.getLong("id"),
                uri = Uri.parse(obj.getString("uri")),
                displayName = obj.getString("displayName"),
                dateModified = obj.getLong("dateModified"),
                size = obj.getLong("size"),
                width = obj.getInt("width"),
                height = obj.getInt("height"),
                bucketDisplayName = obj.optString("bucketDisplayName", "").ifBlank { null },
                deletedAt = obj.optLong("deletedAt", 0L)
            )
        }
        if (items.none { it.deletedAt == 0L }) return items
        val migrated = items.mapIndexed { index, image ->
            if (image.deletedAt == 0L) image.copy(deletedAt = (index + 1).toLong()) else image
        }
        saveUnlocked(migrated)
        return migrated
    }

    private fun saveUnlocked(items: List<ImageFile>) {
        val array = JSONArray()
        items.forEach { image ->
            array.put(JSONObject().apply {
                put("id", image.id)
                put("uri", image.uri.toString())
                put("displayName", image.displayName)
                put("dateModified", image.dateModified)
                put("size", image.size)
                put("width", image.width)
                put("height", image.height)
                put("bucketDisplayName", image.bucketDisplayName ?: "")
                put("deletedAt", image.deletedAt)
            })
        }
        storage.write(array.toString())
    }
}
