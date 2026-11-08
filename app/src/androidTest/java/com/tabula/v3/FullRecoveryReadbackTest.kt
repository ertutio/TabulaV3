package com.tabula.v3

import android.Manifest
import android.content.pm.PackageManager
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.LocalImageRepository
import com.tabula.v3.data.repository.RecycleBinManager
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.data.repository.projectSnapshot
import com.tabula.v3.ui.components.buildMonthInfos
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Host force-stop separates this test from the real UI removal process. */
@RunWith(AndroidJUnit4::class)
class FullRecoveryReadbackTest {
    @Test
    fun persistedUserRemovalReopensAtFirstUnprocessedMember() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_GRANTED, ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES))
        val expected = FullRecoveryStateContract.readExpectedState(ctx)
        assertNotEquals("must read from another process", expected.getInt("processingPid"), Process.myPid())
        val albumId = expected.getString("albumId")
        val groupId = expected.getString("groupId")
        val memberArray = expected.getJSONArray("members")
        val members = (0 until memberArray.length()).map { memberArray.getString(it) }
        val removedKey = members[expected.getInt("checkpointIndex")]
        val remaining = members.filter { it != removedKey }

        // Read durable acknowledgement BEFORE recovery in this new process can
        // supply a missing persisted acknowledgement from recycle records.
        val preferences = AppPreferences(ctx)
        assertEquals(setOf(removedKey), preferences.getAlbumAcknowledgedCleanupMembers(albumId))
        assertEquals(setOf(groupId), preferences.getAlbumGroupIds(albumId))
        assertEquals(members, preferences.getGroupImageIds(albumId, groupId))
        assertTrue(preferences.getAlbumPermanentlyProcessedGroups(albumId).isEmpty())
        assertTrue(albumId !in preferences.getCompletedCleanupAlbumIds())
        val recycled = RecycleBinManager.getInstance(ctx).loadRecycleBin()
        assertEquals(listOf(removedKey), recycled.map { it.mediaIdentityKey() })

        // Query the unchanged physical sources. Only production projection may
        // remove soft-deleted rows from the engine's input.
        val raw = LocalImageRepository(ctx).loadAllData()
        val rawKeys = raw.allImages.map { it.mediaIdentityKey() }.toSet()
        assertTrue("soft removal must preserve every fixture source", rawKeys.containsAll(members))
        val outside = expected.getJSONArray("outsideMembers")
        assertTrue("outside media must survive", rawKeys.containsAll((0 until outside.length()).map { outside.getString(it) }))
        val projected = projectSnapshot(raw, recycled)
        val month = buildMonthInfos(projected.allImages).single { it.cleanupAlbumId == albumId }
        val projectedKeys = month.images.map { it.mediaIdentityKey() }.toSet()
        assertTrue(removedKey !in projectedKeys)
        assertTrue(projectedKeys.containsAll(remaining))

        // Exercise production checkpoint fallback after membership changed. A stale
        // positional index 1 would skip the first remaining fixture member.
        FullRecoveryUi.launch()
        FullRecoveryUi.startMonth(month)
        FullRecoveryUi.await("UI saved safe restart at index zero") {
            preferences.getAlbumCleanupCheckpoint(albumId) == (listOf(groupId) to 0)
        }
        FullRecoveryUi.waitFor(FullRecoveryStateContract.rebuildRow(
            ctx, expected.getJSONArray("displayNames").getString(0)
        ).displayName)
        val batch = requireNotNull(FullRecoveryStateContract.cleanupEngine(ctx).getNextBatch(
            albumId, month.images, latestSnapshot = projected.allImages
        ))
        assertEquals(listOf(groupId), batch.groupIds)
        assertEquals("all unprocessed members, in persisted order", remaining, batch.images.map { it.mediaIdentityKey() })
        assertTrue(preferences.getAlbumPermanentlyProcessedGroups(albumId).isEmpty())
        android.util.Log.i("FullRecoveryEvidence", "readback pid=${Process.myPid()} removed=$removedKey remaining=$remaining checkpoint=0")
    }
}
