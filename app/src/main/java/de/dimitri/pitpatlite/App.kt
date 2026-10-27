package de.dimitri.pitpatlite

import android.app.Application

/** Holds the BLE client for the whole process so the connection survives screen changes. */
class App : Application() {
    val ble: TreadmillBle by lazy { TreadmillBle(this) }
}
