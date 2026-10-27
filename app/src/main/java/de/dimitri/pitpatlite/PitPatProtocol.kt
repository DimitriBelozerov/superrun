package de.dimitri.pitpatlite

import java.util.UUID

/**
 * PitPat-T01 BLE protocol, service 0xFBA0 (Superun / DeerRun / PitPat walking pads).
 * Commands: 23-byte packets on FBA1. Status: 31+ byte notifications on FBA2.
 * Community-documented format (pacekeeper, Razzolate99/pitpat-treadmill, azmke/pitpat-treadmill-control).
 */
object PitPatProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("0000fba0-0000-1000-8000-00805f9b34fb")
    val WRITE_UUID: UUID = UUID.fromString("0000fba1-0000-1000-8000-00805f9b34fb")
    val NOTIFY_UUID: UUID = UUID.fromString("0000fba2-0000-1000-8000-00805f9b34fb")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val NAME_HINTS = listOf("pitpat", "t01", "deerrun", "superun")

    const val CMD_STOP = 0
    const val CMD_PAUSE = 2
    const val CMD_START_OR_SET = 4

    // Protocol constants from the public packet format, not an account id.
    private const val USER_ID = 58965456623L
    private const val WEIGHT_KG = 80

    // Settings packets captured from the PitPat app (BA09-B, same FBA0 service).
    val SOUND_OFF: ByteArray = hex("6a17f1000000000000000000100000000000000001f743")
    val SOUND_ON: ByteArray = hex("6a17f1000000000000000000000000000000000001e743")

    enum class BeltState { STOPPED, RUNNING, PAUSED, STARTING, UNKNOWN }

    data class Status(
        val state: BeltState,
        val currentKph: Double,
        val targetKph: Double,
        val maxKph: Double,
        val distanceKm: Double,
        val incline: Int,
        val targetIncline: Int,
        val maxIncline: Int,
        val heartRate: Int,
        val steps: Long,
        val calories: Int,
        val durationS: Double,
        val firmware: Int,
        val metric: Boolean,
        val buzzerOn: Boolean?,
        val checksumOk: Boolean,
        val raw: ByteArray,
    )

    /** speed in km/h (thousandths on the wire), ramp 1 = start/stop/pause, 5 = speed change while running. */
    fun command(cmd: Int, speedKph: Double = 0.0, incline: Int = 0, ramp: Int? = null): ByteArray {
        val speedRaw = Math.round(speedKph * 1000).toInt()
        require(speedRaw in 0..0xFFFF) { "speed out of range" }
        require(incline in 0..255) { "incline out of range" }
        val r = ramp ?: if (speedRaw > 0) 5 else 1
        val p = ByteArray(23)
        p[0] = 0x6A
        p[1] = 0x17
        p[6] = (speedRaw shr 8).toByte()
        p[7] = speedRaw.toByte()
        p[8] = r.toByte()
        p[9] = incline.toByte()
        p[10] = WEIGHT_KG.toByte()
        p[12] = (cmd and 0xF7).toByte() // bit 3 clear = km/h
        for (i in 0 until 8) p[13 + i] = (USER_ID shr (56 - i * 8)).toByte()
        var cs = 0
        for (i in 1..20) cs = cs xor (p[i].toInt() and 0xFF)
        p[21] = cs.toByte()
        p[22] = 0x43
        return p
    }

    fun start(speedKph: Double, incline: Int) = command(CMD_START_OR_SET, speedKph, incline, ramp = 1)
    fun setSpeed(speedKph: Double, incline: Int) = command(CMD_START_OR_SET, speedKph, incline, ramp = 5)
    fun pause() = command(CMD_PAUSE, ramp = 1)
    fun stop() = command(CMD_STOP, ramp = 1)

    fun parseStatus(d: ByteArray): Status? {
        if (d.size < 31) return null
        var cs = 0
        for (i in 1 until d.size - 2) cs = cs xor (d[i].toInt() and 0xFF)
        val checksumOk = cs == (d[d.size - 2].toInt() and 0xFF)
        val firmware = u8(d, 25)
        val flags = u8(d, 26)
        val state = when (flags and 0x18) {
            0x18 -> BeltState.STARTING
            0x08 -> BeltState.RUNNING
            0x10 -> BeltState.PAUSED
            0x00 -> BeltState.STOPPED
            else -> BeltState.UNKNOWN
        }
        val durationRaw = u32(d, 20)
        return Status(
            state = state,
            currentKph = u16(d, 3) / 1000.0,
            targetKph = u16(d, 5) / 1000.0,
            maxKph = u16(d, 27) / 1000.0,
            distanceKm = u32(d, 7) / 1000.0,
            incline = u8(d, 11),
            targetIncline = u8(d, 12) and 0x3F,
            maxIncline = u8(d, 29),
            heartRate = u8(d, 13),
            steps = u32(d, 14),
            calories = u16(d, 18),
            durationS = if (firmware > 19) durationRaw / 1000.0 else durationRaw.toDouble(),
            firmware = firmware,
            metric = flags and 0x80 == 0,
            buzzerOn = if (d.size > 47) (u8(d, 47) and 0x01) == 1 else null,
            checksumOk = checksumOk,
            raw = d,
        )
    }

    private fun u8(d: ByteArray, i: Int) = d[i].toInt() and 0xFF
    private fun u16(d: ByteArray, i: Int) = (u8(d, i) shl 8) or u8(d, i + 1)
    private fun u32(d: ByteArray, i: Int): Long =
        (u8(d, i).toLong() shl 24) or (u8(d, i + 1).toLong() shl 16) or (u8(d, i + 2).toLong() shl 8) or u8(d, i + 3).toLong()

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun toHex(d: ByteArray): String = d.joinToString(" ") { "%02X".format(it) }
}
