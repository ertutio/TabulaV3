package com.tabula.v3.data.repository

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ProjectSnapshotTest {
    private fun image(id: Long, bucket: String?) = ImageFile(
        id = id,
        uri = Uri.parse("content://media/external/images/media/$id"),
        displayName = "img$id",
        dateModified = id,
        size = 1L,
        width = 1,
        height = 1,
        bucketDisplayName = bucket
    )

    private fun snapshot(
        all: List<ImageFile>,
        excludingTabula: List<ImageFile> = all,
        buckets: List<LocalImageRepository.SystemBucket> = emptyList()
    ) = LocalImageRepository.LoadResult(all, excludingTabula, buckets)

    @Test
    fun softDeletedImagesAreExcludedFromAllProjections() {
        val snap = snapshot(
            all = listOf(image(1, "Camera"), image(2, "Camera"), image(3, "Tabula")),
            excludingTabula = listOf(image(1, "Camera"), image(2, "Camera")),
            buckets = listOf(LocalImageRepository.SystemBucket("Camera", 2, 1L, "DCIM/Camera"))
        )

        val projected = projectSnapshot(snap, setOf(2L))

        assertEquals(listOf(1L, 3L), projected.allImages.map { it.id })
        assertEquals(listOf(1L), projected.imagesExcludingTabula.map { it.id })
        assertEquals(1L, projected.systemBuckets.single().coverImageId)
        assertEquals(1, projected.systemBuckets.single().imageCount)
    }

    @Test
    fun coverFallsBackToNextNewestInBucket() {
        val snap = snapshot(
            all = listOf(image(1, "Camera"), image(2, "Camera"), image(3, "Camera")),
            buckets = listOf(LocalImageRepository.SystemBucket("Camera", 3, 1L, "DCIM/Camera"))
        )

        val projected = projectSnapshot(snap, setOf(1L))

        val bucket = projected.systemBuckets.single()
        assertEquals(2, bucket.imageCount)
        assertEquals(2L, bucket.coverImageId)
    }

    @Test
    fun emptySoftDeleteSetReturnsSnapshotUnchanged() {
        val snap = snapshot(
            all = listOf(image(1, "Camera")),
            buckets = listOf(LocalImageRepository.SystemBucket("Camera", 1, 1L, "DCIM/Camera"))
        )

        val projected = projectSnapshot(snap, emptySet())

        assertSame(snap, projected)
    }

    @Test
    fun fullyDeletedBucketKeepsNameWithZeroCount() {
        val snap = snapshot(
            all = listOf(image(1, "Camera")),
            buckets = listOf(LocalImageRepository.SystemBucket("Camera", 1, 1L, "DCIM/Camera"))
        )

        val projected = projectSnapshot(snap, setOf(1L))

        val bucket = projected.systemBuckets.single()
        assertEquals("Camera", bucket.name)
        assertEquals("DCIM/Camera", bucket.relativePath)
        assertEquals(0, bucket.imageCount)
        assertNull(bucket.coverImageId)
    }

    @Test
    fun bucketOrderAndPathsPreservedAfterProjection() {
        val snap = snapshot(
            all = listOf(image(1, "Camera"), image(2, "Camera"), image(3, "Downloads")),
            buckets = listOf(
                LocalImageRepository.SystemBucket("Camera", 2, 1L, "DCIM/Camera"),
                LocalImageRepository.SystemBucket("Downloads", 1, 3L, "Download/")
            )
        )

        val projected = projectSnapshot(snap, setOf(1L, 2L))

        assertEquals(listOf("Downloads", "Camera"), projected.systemBuckets.map { it.name })
        assertEquals("DCIM/Camera", projected.systemBuckets.last().relativePath)
        assertEquals("Download/", projected.systemBuckets.first().relativePath)
    }
}
