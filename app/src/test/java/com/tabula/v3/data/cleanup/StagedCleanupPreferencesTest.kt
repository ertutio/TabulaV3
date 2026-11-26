package com.tabula.v3.data.cleanup

import android.content.Context
import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 暂存清理的偏好层测试（路线图第 1 项）：跨重启生效的两条语义必须分开。
 *
 * - 长期保留：写入 SharedPreferences，新建实例仍读得到 → 真的跨重启。
 * - 本次跳过：只存进程内字段，新建实例读不到 → 重启后照片回来。
 *
 * 这两条如果混成同一个存储，用户点「这次先不看」的照片会永久消失，
 * 或点「长期保留」的照片下次又冒出来，两种都是产品缺陷。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class StagedCleanupPreferencesTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun photo(id: Long, volume: String = "external_primary") = ImageFile(
        id = id,
        uri = Uri.parse("content://media/$volume/images/media/$id"),
        displayName = "photo$id.jpg",
        dateModified = 1L,
        size = 1L,
        width = 10,
        height = 10,
        bucketDisplayName = "Screenshots",
        volumeName = volume,
        relativePath = "DCIM/Screenshots/",
        dateAdded = 1_700_000_000_000L
    )

    @Test
    fun retainedKeysSurviveANewPreferencesInstance() {
        val first = AppPreferences(context)
        first.stagedCleanupSourceKeys = setOf("external_primary|dcim/screenshots")
        first.hasStagedCleanupSourceChoice = true
        first.retainStagedCleanupImages(
            listOf(cleanupIdentityKey(photo(1)), cleanupIdentityKey(photo(2)))
        )

        // 新实例 = 模拟进程重启后重新读偏好
        val afterRestart = AppPreferences(context)
        assertEquals(setOf("external_primary|dcim/screenshots"), afterRestart.stagedCleanupSourceKeys)
        assertTrue(afterRestart.hasStagedCleanupSourceChoice)
        assertEquals(
            setOf("external_primary|1", "external_primary|2"),
            afterRestart.stagedCleanupRetainedIdentityKeys
        )
    }

    @Test
    fun skippedKeysAreDeliberatelyProcessLocal() {
        val first = AppPreferences(context)
        first.skipStagedCleanupImages(listOf(cleanupIdentityKey(photo(3))))
        assertEquals(setOf("external_primary|3"), first.stagedCleanupSkippedIdentityKeys)

        // 重启后跳过集合清空——照片重新出现在候选里，这是设计意图
        val afterRestart = AppPreferences(context)
        assertTrue(afterRestart.stagedCleanupSkippedIdentityKeys.isEmpty())
    }

    @Test
    fun retentionCanBeReleased() {
        val prefs = AppPreferences(context)
        val key = cleanupIdentityKey(photo(9))
        prefs.retainStagedCleanupImages(listOf(key))
        assertTrue(key in prefs.stagedCleanupRetainedIdentityKeys)

        prefs.releaseStagedCleanupRetention(listOf(key))
        assertFalse(key in prefs.stagedCleanupRetainedIdentityKeys)
    }

    @Test
    fun emptySourceSelectionIsDistinctFromNeverChosen() {
        val prefs = AppPreferences(context)
        // 还没选过
        assertTrue(prefs.stagedCleanupSourceKeys.isEmpty())
        assertFalse(prefs.hasStagedCleanupSourceChoice)

        // 用户主动清空来源：集合仍是空，但"选过"必须为真，
        // 否则界面会永远停在来源选择页，用户出不去。
        prefs.stagedCleanupSourceKeys = emptySet()
        prefs.hasStagedCleanupSourceChoice = true
        assertTrue(prefs.stagedCleanupSourceKeys.isEmpty())
        assertTrue(prefs.hasStagedCleanupSourceChoice)
    }
}
