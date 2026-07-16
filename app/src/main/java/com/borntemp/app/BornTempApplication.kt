package com.borntemp.app

import android.app.Application
import android.content.Context
import com.borntemp.app.viewmodel.ObdSessionController

/**
 * Custom [Application] so the OBD session survives individual Activities —
 * [ObdSessionController] is created once here and shared by `MainViewModel`
 * and `com.borntemp.app.obd.ObdForegroundService`, so there is structurally
 * only ever one BLE connection to the OBDLink CX in this process.
 */
class BornTempApplication : Application() {
    val sessionController: ObdSessionController by lazy { ObdSessionController(this) }
}

object ObdSessionHolder {
    fun controllerFor(context: Context): ObdSessionController =
        (context.applicationContext as BornTempApplication).sessionController
}
