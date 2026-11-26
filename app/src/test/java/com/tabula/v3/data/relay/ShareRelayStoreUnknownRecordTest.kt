package com.tabula.v3.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #85 审计：loadResult 不得把无法解析的数组元素静默当作不存在。
 *
 * 用一个**合法 JSON 数组**，内含正常记录与一条未知状态或非对象记录；
 * 分别执行分享新增、撤销、手动确认，验证原始记录没有被静默删除
 * （读返回 Failed → 禁止覆盖 → 原始内容保持可恢复）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ShareRelayStoreUnknownRecordTest {

    private fun markJson(id: Long, state: String = "CONFIRMED") =
        """{"identityKey":"external_primary|$id",""" +
            """"uri":"content://media/external_primary/images/media/$id",""" +
            """"displayName":"p$id.jpg","markedAt":${100 + id},"state":"$state"}"""

    private fun unknownStateJson(id: Long) =
        """{"identityKey":"external_primary|$id",""" +
            """"uri":"content://media/external_primary/images/media/$id",""" +
            """"displayName":"p$id.jpg","markedAt":${100 + id},"state":"FUTURE_STATE"}"""

    private fun nonObjectJson() = "\"just-a-string\""

    /** 合法数组：正常记录 + 一条坏记录。 */
    private fun mixedStorage(goodCount: Long, bad: String): String {
        val goods = (1..goodCount).joinToString(",") { markJson(it.toLong()) }
        return "[$goods,$bad]"
    }

    private class MemStorage(var content: String? = null) : RelayStorage {
        override fun read(): String? = content
        override fun write(value: String) { content = value }
    }

    private fun storeWith(bad: String, goodCount: Long = 2): Pair<ShareRelayStore, MemStorage> {
        val storage = MemStorage(mixedStorage(goodCount, bad))
        return ShareRelayStore(storage) to storage
    }

    // ============ 分享新增（merge） ============

    @Test
    fun mergeWithUnknownStateRecordReturnsFailedAndKeepsEverything() {
        val (store, storage) = storeWith(unknownStateJson(3))
        val before = storage.content

        val result = store.merge(listOf(mark(4)))

        assertTrue(result is ShareRelayStore.WriteResult.Failed)
        // 原始内容未被覆盖：正常记录与未知记录都还在
        val reloaded = ShareRelayStore(MemStorage(before)).loadResult()
        assertTrue(reloaded is ShareRelayStore.LoadResult.Failed)
        assertEquals(before, storage.content)
    }

    @Test
    fun mergeWithNonObjectRecordReturnsFailedAndKeepsEverything() {
        val (store, storage) = storeWith(nonObjectJson())
        val before = storage.content

        val result = store.merge(listOf(mark(4)))

        assertTrue(result is ShareRelayStore.WriteResult.Failed)
        assertEquals(before, storage.content)
    }

    // ============ 撤销（remove） ============

    @Test
    fun removeWithUnknownStateRecordReturnsFailedAndKeepsEverything() {
        val (store, storage) = storeWith(unknownStateJson(3))
        val before = storage.content

        val result = store.remove(listOf("external_primary|1"))

        assertTrue(result is ShareRelayStore.WriteResult.Failed)
        // 正常记录没有被静默删除
        val original = ShareRelayStore(MemStorage(before)).loadResult()
        assertTrue(original is ShareRelayStore.LoadResult.Failed)
        assertEquals(before, storage.content)
    }

    @Test
    fun removeWithNonObjectRecordReturnsFailedAndNormalMarksSurvive() {
        val (store, storage) = storeWith(nonObjectJson())
        val before = storage.content

        val result = store.remove(listOf("external_primary|1"))

        assertTrue(result is ShareRelayStore.WriteResult.Failed)
        assertEquals(before, storage.content)
        // 原始内容里正常记录仍在（没有被空列表覆盖）
        assertTrue(before!!.contains(markJson(1)))
        assertTrue(before.contains(markJson(2)))
    }

    // ============ 手动确认（replace） ============

    @Test
    fun replaceWithUnknownStateRecordReturnsFailedAndKeepsEverything() {
        val (store, storage) = storeWith(unknownStateJson(3))
        val pending = RelayMark(
            identityKey = null,
            uriString = "content://tmp/1",
            displayName = "tmp.png",
            markedAt = 100L,
            state = RelayMarkState.PENDING_REVIEW
        )
        val before = storage.content

        val result = store.replace(
            removeKey = null,
            removeUri = pending.uriString,
            add = pending.copy(
                identityKey = "external_primary|8",
                uriString = "content://media/external_primary/images/media/8",
                state = RelayMarkState.CONFIRMED
            )
        )

        assertTrue(result is ShareRelayStore.WriteResult.Failed)
        assertEquals(before, storage.content)
    }

    // ============ 读回确认 ============

    @Test
    fun loadResultReportsFailedForUnknownState() {
        val (store, _) = storeWith(unknownStateJson(3))
        val result = store.loadResult()
        assertTrue(result is ShareRelayStore.LoadResult.Failed)
        assertTrue((result as ShareRelayStore.LoadResult.Failed).reason.contains("unknown state"))
    }

    @Test
    fun loadResultReportsFailedForNonObjectElement() {
        val (store, _) = storeWith(nonObjectJson())
        val result = store.loadResult()
        assertTrue(result is ShareRelayStore.LoadResult.Failed)
        assertTrue((result as ShareRelayStore.LoadResult.Failed).reason.contains("not a JSON object"))
    }

    @Test
    fun allGoodRecordsStillLoadNormally() {
        // 无坏记录时行为不变：正常数组完整加载
        val storage = MemStorage("[${markJson(1)},${markJson(2, "PENDING_REVIEW")}]")
        val store = ShareRelayStore(storage)
        val result = store.loadResult()
        assertTrue(result is ShareRelayStore.LoadResult.Ok)
        assertEquals(2, (result as ShareRelayStore.LoadResult.Ok).marks.size)
    }

    // ============ 清除（clear）可以安全重置坏数据 ============

    @Test
    fun clearResetsCorruptStorageAsExplicitUserAction() {
        // clear 是用户显式清空：允许覆盖坏数据（与其他读后写不同）
        val (store, storage) = storeWith(unknownStateJson(3))
        val result = store.clear()
        assertTrue(result is ShareRelayStore.WriteResult.Ok)
        assertEquals("[]", storage.content)
    }

    private fun mark(id: Long) = RelayMark(
        identityKey = "external_primary|$id",
        uriString = "content://media/external_primary/images/media/$id",
        displayName = "p$id.jpg",
        markedAt = 100L + id,
        state = RelayMarkState.CONFIRMED
    )
}
