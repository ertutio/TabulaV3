package com.tabula.v3.data.relay

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 系统分享中转的标记存储（路线图第 2 项）。
 *
 * 存什么：每条标记的身份键（能定位时）、URI、可读名、时间、状态。
 * 为什么落盘：用户分享完可能立刻杀进程，但"待清"这件事必须跨重启还在，
 * 否则中转标记等于没做。
 *
 * 与回收站的关系：标记**不等于**已删除，也**不会**自动进回收站。它只表示
 * "这张图经过 Tabula 中转，按约定可以列为待清"，真正的删除仍走用户确认。
 */
class ShareRelayStore(
    private val storage: RelayStorage,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * 审计修复（#85）：标记读回必须区分"没有标记"和"读失败"。
     *
     * 旧 load() 把读失败也当空列表，事务会误判"标记已移除成功"——
     * 这正是"回收站写成功但标记移除失败"被掩盖的根因之一。
     */
    sealed interface LoadResult {
        data class Ok(val marks: List<RelayMark>) : LoadResult
        data class Failed(val reason: String) : LoadResult
    }

    fun loadResult(): LoadResult {
        val raw = try {
            storage.read()
        } catch (e: Exception) {
            return LoadResult.Failed(e.message ?: "storage read failed")
        }
        if (raw == null) return LoadResult.Ok(emptyList())
        return try {
            val array = JSONArray(raw)
            val marks = (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val state = runCatching {
                    RelayMarkState.valueOf(obj.getString("state"))
                }.getOrNull() ?: return@mapNotNull null
                RelayMark(
                    identityKey = obj.optString("identityKey", "").ifBlank { null },
                    uriString = obj.getString("uri"),
                    displayName = obj.optString("displayName", "").ifBlank { null },
                    markedAt = obj.optLong("markedAt", 0L),
                    state = state
                )
            }
            LoadResult.Ok(marks)
        } catch (e: Exception) {
            LoadResult.Failed(e.message ?: "mark parse failed")
        }
    }

    /** 读全部标记；读失败仍按空处理，供不关心失败的展示点使用。 */
    fun load(): List<RelayMark> = when (val result = loadResult()) {
        is LoadResult.Ok -> result.marks
        is LoadResult.Failed -> emptyList()
    }

    /**
     * 审计 #85：所有读后写（merge/remove/removeByUri）必须区分失败。
     * 读或解析失败时**停止写入**并向调用方报告，原始数据保持可恢复，
     * 不把失败包装成空列表再覆盖。
     */
    sealed interface WriteResult {
        data class Ok(val marks: List<RelayMark>) : WriteResult
        data class Failed(val reason: String) : WriteResult
    }

    private fun withMarksOrFail(block: (List<RelayMark>) -> List<RelayMark>): WriteResult =
        when (val read = loadResult()) {
            is LoadResult.Failed -> WriteResult.Failed(read.reason)
            is LoadResult.Ok -> {
                val updated = block(read.marks)
                try {
                    save(updated)
                    WriteResult.Ok(updated)
                } catch (e: Exception) {
                    WriteResult.Failed("mark write failed: ${e.message}")
                }
            }
        }

    /**
     * 合并保存：同一身份键（或同一未定位 URI）只保留最新一条。
     * 读/写失败返回 [WriteResult.Failed]，存储原样保留。
     */
    fun merge(marks: List<RelayMark>): WriteResult {
        if (marks.isEmpty()) return WriteResult.Ok(load())
        return withMarksOrFail { existing ->
            val merged = LinkedHashMap<String, RelayMark>()
            existing.forEach { merged[keyOf(it)] = it }
            marks.forEach { merged[keyOf(it)] = it }
            merged.values.toList()
        }
    }

    fun remove(identityKeys: Collection<String>): WriteResult {
        if (identityKeys.isEmpty()) return WriteResult.Ok(load())
        val keys = identityKeys.toSet()
        return withMarksOrFail { existing ->
            existing.filterNot { it.identityKey != null && it.identityKey in keys }
        }
    }

    fun removeByUri(uriStrings: Collection<String>): WriteResult {
        if (uriStrings.isEmpty()) return WriteResult.Ok(load())
        val uris = uriStrings.toSet()
        return withMarksOrFail { existing ->
            existing.filterNot { it.uriString in uris }
        }
    }

    fun clear(): WriteResult = try {
        storage.write(JSONArray().toString())
        WriteResult.Ok(emptyList())
    } catch (e: Exception) {
        WriteResult.Failed("clear failed: ${e.message}")
    }

    /**
     * 审计 #85：手动确认的原子更新——在**同一次读取**得到的记录集合里，
     * 移除待核对项（按身份键或 URI 键）并加入已确认项，然后只写一次。
     *
     * 不再使用先 removeByUri、再 merge 的两步：两步流程在第二步失败时
     * 会丢失待核对项。
     *
     * 读失败 / 写失败返回 [WriteResult.Failed]，存储原样保留；
     * 成功返回 [WriteResult.Ok]（携带写后的完整集合，即确认写入结果）。
     */
    fun replace(
        removeKey: String?,
        removeUri: String?,
        add: RelayMark
    ): WriteResult = withMarksOrFail { existing ->
        val updated = existing
            .filterNot { it.identityKey != null && it.identityKey == removeKey }
            .filterNot { it.uriString == removeUri }
            .let { list -> list + add }
        updated
    }

    private fun save(marks: List<RelayMark>) {
        val array = JSONArray()
        marks.forEach { mark ->
            array.put(JSONObject().apply {
                mark.identityKey?.let { put("identityKey", it) }
                put("uri", mark.uriString)
                mark.displayName?.let { put("displayName", it) }
                put("markedAt", mark.markedAt)
                put("state", mark.state.name)
            })
        }
        storage.write(array.toString())
    }

    private fun keyOf(mark: RelayMark): String =
        mark.identityKey?.let { "id|$it" } ?: "uri|${mark.uriString}"

    companion object {
        private const val PREFS_NAME = "tabula_share_relay"
        internal const val KEY_MARKS = "relay_marks"

        @Volatile
        private var instance: ShareRelayStore? = null

        fun getInstance(context: Context): ShareRelayStore =
            instance ?: synchronized(this) {
                instance ?: ShareRelayStore(
                    SharedPrefsRelayStorage(
                        context.applicationContext
                            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    )
                ).also { instance = it }
            }
    }
}

/** 中转标记的持久化后端；测试用内存实现替换。 */
interface RelayStorage {
    fun read(): String?
    fun write(content: String)
}

internal class SharedPrefsRelayStorage(
    private val prefs: SharedPreferences
) : RelayStorage {
    override fun read(): String? = prefs.getString(ShareRelayStore.KEY_MARKS, null)
    override fun write(content: String) {
        prefs.edit().putString(ShareRelayStore.KEY_MARKS, content).apply()
    }
}

/**
 * 从收到的 Intent 里抽出中转项。
 *
 * 覆盖 ACTION_SEND 与 ACTION_SEND_MULTIPLE；EXTRA_STREAM 为主，ClipData
 * 为补充（部分分享方只填 ClipData）。**不改动** Intent 本身——转发时要用
 * 原始 URI 与授权。
 */
object ShareRelayIntentReader {

    fun read(intent: Intent?, resolver: (Uri) -> String?): List<RelayItem> {
        if (intent == null) return emptyList()
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return emptyList()

        val uriStrings = linkedSetOf<String>()
        when (action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uriStrings += it.toString() }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    ?.forEach { uriStrings += it.toString() }
            }
        }
        // ClipData 补充：只在 EXTRA_STREAM 缺失时补，避免同一次分享重复计数
        if (uriStrings.isEmpty()) {
            val clip = intent.clipData
            if (clip != null) {
                (0 until clip.itemCount).forEach { index ->
                    clip.getItemAt(index)?.uri?.let { uriStrings += it.toString() }
                }
            }
        }

        val declaredType = intent.type
        val labels = intent.clipData?.let { clip ->
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri?.toString() to clip.getDescription()?.label?.toString() }
                .toMap()
        }.orEmpty()

        return uriStrings.map { raw ->
            val uri = Uri.parse(raw)
            RelayItem(
                uriString = raw,
                mimeType = resolver(uri) ?: declaredType,
                displayName = labels[raw]
            )
        }
    }
}
