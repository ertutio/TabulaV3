package com.tabula.v3

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class ViewerGlassRenderEvidenceDeviceTest : ViewerGlassDeviceFixture() {

    @Test
    fun photoIdentityFollowsAB() {
        val tones = FixtureTone.entries
        startDeck(glassEnabled = true, *tones.toTypedArray())
        val rendered = mutableSetOf<FixtureTone>()

        repeat(tones.size) { index ->
            val photo = openCard()
            assertTrue("Card swipe must select a different photo: $photo", rendered.add(photo.tone))
            assertCurrentSample(photo)
            shot("photo-${photo.tone.name.lowercase()}")
            closeViewer()
            if (index < tones.lastIndex) advanceCard()
        }

        assertEquals(tones.toSet(), rendered)
    }

    @Test
    fun zoomAndPanFallBackThenRestoreSampling() {
        startDeck(glassEnabled = true, FixtureTone.HIGH_CONTRAST)
        val photo = openCard()
        assertCurrentSample(photo)

        zoomIn()
        val zoom = waitForAudit { it.scale >= 2.9f }
        assertTransformedFallback(photo, zoom)
        shot("zoomed-solid")

        panPhoto()
        val pan = waitForAudit { abs(it.panX - zoom.panX) >= 8f || abs(it.panY - zoom.panY) >= 8f }
        assertTrue("Photo must remain zoomed during pan: $pan", pan.scale >= 2.9f)
        assertTransformedFallback(photo, pan)
        shot("panned-solid")

        restoreZoom()
        assertCurrentSample(photo)
        shot("restored-glass")
    }

    private fun assertTransformedFallback(photo: PhotoFixture, audit: Audit) {
        assertEquals(photo.identity, audit["photo"])
        assertEquals("fallback", audit["path"])
        assertEquals("VIEWER_TRANSFORMED", audit["reason"])
        assertEquals("false", audit["sampled"])
        assertEquals("false", audit["ready"])
    }

    private fun assertCurrentSample(photo: PhotoFixture) {
        val audit = waitForAudit {
            it["photo"] == photo.identity && it["sampled"] == "true" && it["ready"] == "true"
        }
        assertEquals("glass", audit["path"])
        assertEquals("Ok", audit["effect"])
        assertEquals("true", audit["drawplan"])
        assertEquals(audit["rev"], audit["drawrev"])
        assertTrue("Scale must be back to 1x: $audit", abs(audit.scale - 1f) <= 0.01f)
        assertTrue("Pan must be back to the origin: $audit", abs(audit.panX) <= 0.5f && abs(audit.panY) <= 0.5f)

        for (key in listOf("glass", "layer", "host")) {
            assertPositiveSize(audit, key)
        }
        for (key in listOf("hostdraw", "layerdraw", "buttondraw")) {
            assertTrue("$key must report a draw: $audit", (audit[key]?.toIntOrNull() ?: 0) > 0)
        }

        val (width, height) = assertPositiveSize(audit, "photorect")
        val (bitmapWidth, bitmapHeight) = assertPositiveSize(audit, "bitmap")
        val expectedAspect = photo.expectedAspect
        assertTrue("Photo rectangle has wrong aspect for $photo: $audit", abs(width.toFloat() / height - expectedAspect) <= 0.01f)
        assertTrue("Sampling bitmap has wrong aspect for $photo: $audit", abs(bitmapWidth.toFloat() / bitmapHeight - expectedAspect) <= 0.01f)

        val (hostWidth, hostHeight) = assertPositiveSize(audit, "host")
        assertTrue(
            "Measured photo must be inside the sampling host: $audit",
            width < hostWidth && height < hostHeight
        )
    }

    private fun assertPositiveSize(audit: Audit, key: String): Pair<Int, Int> {
        val parts = audit[key]?.substringAfterLast(',')?.split('x').orEmpty()
        val width = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val height = parts.getOrNull(1)?.toIntOrNull() ?: 0
        assertTrue("$key must report a positive width and height: $audit", width > 0 && height > 0)
        return width to height
    }
}
