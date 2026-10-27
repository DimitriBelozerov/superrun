package de.dimitri.pitpatlite

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reference vectors generated with Razzolate99/pitpat-treadmill `make_packet` / `parse_status`. */
class PitPatProtocolTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun startPacketMatchesReference() =
        assertArrayEquals(hex("6a170000000003e801005000040000000dba9d76ef1a43"), PitPatProtocol.start(1.0, 0))

    @Test
    fun setSpeedWithInclineMatchesReference() =
        assertArrayEquals(hex("6a170000000009c405035000040000000dba9d76ef3b43"), PitPatProtocol.setSpeed(2.5, 3))

    @Test
    fun pausePacketMatchesReference() =
        assertArrayEquals(hex("6a1700000000000001005000020000000dba9d76eff743"), PitPatProtocol.pause())

    @Test
    fun stopPacketMatchesReference() =
        assertArrayEquals(hex("6a1700000000000001005000000000000dba9d76eff543"), PitPatProtocol.stop())

    @Test
    fun parsesCapturedStatus() {
        val s = PitPatProtocol.parseStatus(
            hex("6834000000000000000000000000000000000000000000002a1b001770000500746c4b613139316655705473733630330e004043")
        )!!
        assertEquals(PitPatProtocol.BeltState.STOPPED, s.state)
        assertEquals(0.0, s.currentKph, 1e-9)
        assertEquals(6.0, s.maxKph, 1e-9)
        assertEquals(27, s.firmware)
        assertEquals(0L, s.steps)
        assertEquals(0.0, s.durationS, 1e-9)
        assertEquals(0, s.maxIncline)
        assertEquals(true, s.buzzerOn)
        assertTrue(s.metric)
        assertTrue(s.checksumOk)
    }

    @Test
    fun parsesRunningStateAndSpeeds() {
        // flags byte 26 = 0x08 running; current 2.500, target 3.000, distance 1.234 km, steps 1500, kcal 42, 25.0 s, fw 27
        val p = ByteArray(34)
        p[0] = 0x66; p[1] = 34
        p[3] = 0x09; p[4] = 0xC4.toByte()
        p[5] = 0x0B; p[6] = 0xB8.toByte()
        p[9] = 0x04; p[10] = 0xD2.toByte()
        p[11] = 2; p[12] = 3; p[29] = 10
        p[16] = 0x05; p[17] = 0xDC.toByte()
        p[19] = 42
        p[22] = 0x61; p[23] = 0xA8.toByte()
        p[25] = 27; p[26] = 0x08
        p[27] = 0x17; p[28] = 0x70
        var cs = 0
        for (i in 1 until p.size - 2) cs = cs xor (p[i].toInt() and 0xFF)
        p[p.size - 2] = cs.toByte(); p[p.size - 1] = 0x43
        val s = PitPatProtocol.parseStatus(p)!!
        assertEquals(PitPatProtocol.BeltState.RUNNING, s.state)
        assertEquals(2.5, s.currentKph, 1e-9)
        assertEquals(3.0, s.targetKph, 1e-9)
        assertEquals(1.234, s.distanceKm, 1e-9)
        assertEquals(2, s.incline)
        assertEquals(3, s.targetIncline)
        assertEquals(10, s.maxIncline)
        assertEquals(1500L, s.steps)
        assertEquals(42, s.calories)
        assertEquals(25.0, s.durationS, 1e-9)
        assertEquals(6.0, s.maxKph, 1e-9)
        assertNull(s.buzzerOn)
        assertTrue(s.checksumOk)
    }

    @Test
    fun rejectsShortPacket() = assertNull(PitPatProtocol.parseStatus(ByteArray(20)))
}
