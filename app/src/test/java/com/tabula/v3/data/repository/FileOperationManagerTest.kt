package com.tabula.v3.data.repository

import android.Manifest
import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class FileOperationManagerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: QueryProvider
    private val uri = Uri.parse("content://media/external/images/media/1")

    class QueryProvider : ContentProvider() {
        var mode = "empty"
        var beforeReturn: () -> Unit = {}
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            beforeReturn()
            if (mode == "null") return null
            if (mode == "permission") throw SecurityException("Denied")
            if (mode == "service") throw IllegalStateException("Unavailable")
            return MatrixCursor(arrayOf("_id")).apply {
                if (mode == "present") addRow(arrayOf(1L))
            }
        }
        override fun getType(uri: Uri) = "image/jpeg"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    @Before fun setup() {
        provider = QueryProvider()
        ShadowContentResolver.registerProviderInternal("media", provider)
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
    }

    private fun manager(available: Boolean = true) = FileOperationManager(context,
        createDeleteRequest = { _, _ ->
            PendingIntent.getBroadcast(context, 0, Intent("test"), PendingIntent.FLAG_IMMUTABLE).intentSender
        }, volumeAvailable = { available })

    @Test fun fullAccessAndAvailableVolumeCanConfirmMissing() = runBlocking {
        assertEquals(setOf(uri), manager().verifyDeletionState(listOf(uri)).deleted)
    }

    @Test fun selectedAccessEmptyQueryRemainsUnknownAfterAuthorization() = runBlocking {
        shadowOf(context).revokePermissions(Manifest.permission.READ_MEDIA_IMAGES)
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val result = manager().verifyDeletionState(listOf(uri))
        assertTrue(result.deleted.isEmpty())
        assertEquals(setOf(uri), result.unknown)
    }

    @Test fun unavailableVolumeCannotConfirmMissing() = runBlocking {
        assertEquals(setOf(uri), manager(false).verifyDeletionState(listOf(uri)).unknown)
    }

    @Test fun permissionRevokedDuringQueryCannotConfirmMissing() = runBlocking {
        provider.beforeReturn = { shadowOf(context).revokePermissions(Manifest.permission.READ_MEDIA_IMAGES) }
        assertEquals(setOf(uri), manager().verifyDeletionState(listOf(uri)).unknown)
    }

    @Test fun nullOrFailedQueriesRemainUnknown() = runBlocking {
        for (mode in listOf("null", "permission", "service")) {
            provider.mode = mode
            val result = manager().verifyDeletionState(listOf(uri))
            assertTrue(result.deleted.isEmpty())
            assertEquals(setOf(uri), result.unknown)
        }
    }

    @Test fun visiblePhotoRemainsPresentWithPartialAccess() = runBlocking {
        provider.mode = "present"
        shadowOf(context).revokePermissions(Manifest.permission.READ_MEDIA_IMAGES)
        assertEquals(setOf(uri), manager().verifyDeletionState(listOf(uri)).existing)
    }

    @Test fun actualServiceErrorsMapToNonSplittableFailures() = runBlocking {
        provider.mode = "present"
        for (exception in listOf(SecurityException("Denied"), IllegalStateException("Unavailable"))) {
            val service = FileOperationManager(context, createDeleteRequest = { _, _ -> throw exception },
                volumeAvailable = { true })
            val error = service.deleteImages(listOf(uri)) as FileOperationManager.DeleteResult.Error
            assertFalse(error.asBatchFailure().canSplit)
            assertEquals(if (exception is SecurityException) DeleteFailureKind.PERMISSION else DeleteFailureKind.SERVICE, error.kind)
        }
    }

    @Test fun rejectedRequestCanBeSplitButCancellationPropagates() = runBlocking {
        provider.mode = "present"
        val invalid = FileOperationManager(context,
            createDeleteRequest = { _, _ -> throw IllegalArgumentException("Invalid URI") }, volumeAvailable = { true })
        assertTrue((invalid.deleteImages(listOf(uri)) as FileOperationManager.DeleteResult.Error).asBatchFailure().canSplit)
        val cancelled = FileOperationManager(context,
            createDeleteRequest = { _, _ -> throw CancellationException("Cancelled") }, volumeAvailable = { true })
        try { cancelled.deleteImages(listOf(uri)); fail("Must propagate cancellation") }
        catch (expected: CancellationException) { }
    }
}
