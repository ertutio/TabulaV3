package com.tabula.v3

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.permission.MediaPermissionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The host revoked access and killed the previous process while preserving app data. */
@RunWith(AndroidJUnit4::class)
class FullRecoveryAbsenceTest {
    @Test
    fun permissionAbsenceGatesMediaAccessAndPreservesCleanupState() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = FullRecoveryStateContract.readExpectedState(ctx)
        assertNotEquals(expected.getInt("phase1Pid"), android.os.Process.myPid())
        assertEquals(
            MediaPermissionPolicy.MediaAccessLevel.NONE,
            MediaPermissionPolicy.mediaAccessLevel(Build.VERSION.SDK_INT) { permission ->
                ctx.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        )
        val albumId = expected.getString("albumId")
        val groupId = expected.getString("groupId")
        val members = expected.getJSONArray("members").let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        val preferences = AppPreferences.getInstance(ctx)
        fun assertSavedState() {
            assertEquals(setOf(groupId), preferences.getAlbumGroupIds(albumId))
            assertEquals(members, preferences.getGroupImageIds(albumId, groupId))
            assertEquals(
                listOf(groupId) to expected.getInt("checkpointIndex"),
                preferences.getAlbumCleanupCheckpoint(albumId)
            )
            assertTrue(preferences.getAlbumAcknowledgedCleanupMembers(albumId).isEmpty())
            assertTrue(preferences.getAlbumPermanentlyProcessedGroups(albumId).isEmpty())
            assertTrue(albumId !in preferences.getCompletedCleanupAlbumIds())
        }
        assertSavedState()
        FullRecoveryUi.launch()
        FullRecoveryUi.waitFor("授权访问照片")
        FullRecoveryUi.waitFor("欢迎使用 Tabula")
        assertSavedState()
        expected.put("absencePid", android.os.Process.myPid())
        FullRecoveryStateContract.stateFile(ctx).writeText(expected.toString())
    }
}
