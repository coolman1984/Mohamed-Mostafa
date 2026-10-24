package com.mm.smartcgm

import androidx.test.core.app.ApplicationProvider
import com.mm.smartcgm.core.GlucoseReading
import com.mm.smartcgm.core.ReadingTrust
import com.mm.smartcgm.sources.linx.LinxBleSource
import com.mm.smartcgm.sources.linx.LinxFrameCodec
import com.mm.smartcgm.sources.linx.LinxRawReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LinxBleSourceTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun defaultSourceIsUnavailableAndRefusesToGuess() {
        val got = mutableListOf<GlucoseReading>()
        val src = LinxBleSource(ctx, { got += it })
        assertFalse(src.isAvailable())
        // UNEVIDENCED codec → frame is dropped with a reason, nothing ingested (no fabricated glucose).
        assertEquals("codec-unavailable", src.onFrame(byteArrayOf(1, 2, 3), sensorId = "SN"))
        assertTrue(got.isEmpty())
    }

    @Test fun validDecodedFrameIsIngestedAsExperimental() {
        // A fake *test-only* codec stands in for the (still unverified) real one, to prove the pipeline + tagging.
        val fake = object : LinxFrameCodec {
            override fun decodeReading(frame: ByteArray) = LinxRawReading(mgdl = 120.0, sensorTimeMs = 0, timeMs = 1_800_000_000_000L)
        }
        val got = mutableListOf<GlucoseReading>()
        val src = LinxBleSource(ctx, { got += it }, fake)
        assertTrue(src.isAvailable())
        val now = 1_800_000_000_000L
        assertNull(src.onFrame(byteArrayOf(0), now, "SN"))
        assertEquals(1, got.size)
        assertEquals(ReadingTrust.EXPERIMENTAL, got[0].trust)
        assertEquals("linx-ble", got[0].source)
        assertEquals(120.0, got[0].mgdl, 0.001)
    }

    @Test fun arrivalTimeCannotMakeAnUnmappedOrReplayedSampleLookLive() {
        val now = 1_800_000_000_000L
        val got = mutableListOf<GlucoseReading>()
        val unmapped = object : LinxFrameCodec {
            override fun decodeReading(frame: ByteArray) = LinxRawReading(120.0, 0)
        }
        assertEquals("sensor-clock-unmapped", LinxBleSource(ctx, { got += it }, unmapped).onFrame(byteArrayOf(0), now, "SN"))
        val mapped = object : LinxFrameCodec {
            override fun decodeReading(frame: ByteArray) = LinxRawReading(120.0, 0, timeMs = now)
        }
        val src = LinxBleSource(ctx, { got += it }, mapped)
        assertNull(src.onFrame(byteArrayOf(0), now, "SN"))
        assertEquals("duplicate-or-out-of-order", src.onFrame(byteArrayOf(0), now + 60_000, "SN"))
        assertEquals(1, got.size)
        assertEquals(now, got.single().timeMs)
        assertEquals("sensor-identity-unavailable", src.onFrame(byteArrayOf(0), now, null))
    }

    @Test fun implausibleDecodedValueIsRejected() {
        val fake = object : LinxFrameCodec {
            override fun decodeReading(frame: ByteArray) = LinxRawReading(mgdl = 999.0, sensorTimeMs = 0, timeMs = System.currentTimeMillis())
        }
        val got = mutableListOf<GlucoseReading>()
        val src = LinxBleSource(ctx, { got += it }, fake)
        assertEquals("out-of-range:999.0", src.onFrame(byteArrayOf(0), sensorId = "SN"))
        assertTrue(got.isEmpty())
    }
}
