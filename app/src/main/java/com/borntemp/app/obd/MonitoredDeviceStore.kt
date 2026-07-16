package com.borntemp.app.obd

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the MAC address of the OBDLink CX that auto-connect should watch
 * for. Overwritten every time the user manually connects (see
 * `ObdSessionController.connect`), so pairing a replacement adapter and
 * connecting to it once retargets auto-connect at the new device.
 */
class MonitoredDeviceStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("monitored_device", Context.MODE_PRIVATE)

    var deviceAddress: String?
        get() = prefs.getString("device_address", null)
        set(v) { prefs.edit().putString("device_address", v).apply() }
}
