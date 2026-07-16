package com.borntemp.app.viewmodel

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.borntemp.app.ObdSessionHolder
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin Activity-scoped adapter over the process-wide [ObdSessionController]
 * singleton (see [ObdSessionHolder]). The controller — not this ViewModel —
 * owns the BLE connection, poll loop and ABRP push, so a background
 * `ObdForegroundService` session survives this ViewModel being cleared when
 * the Activity finishes.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val controller: ObdSessionController = ObdSessionHolder.controllerFor(application)

    val uiState: StateFlow<UiState> = controller.uiState

    fun setPackTypeOverride(override: PackTypeOverride) = controller.setPackTypeOverride(override)
    fun setPollingInterval(ms: Long) = controller.setPollingInterval(ms)
    fun setAbrpApiKey(key: String) = controller.setAbrpApiKey(key)
    fun setAbrpUserToken(token: String) = controller.setAbrpUserToken(token)
    fun setAbrpEnabled(enabled: Boolean) = controller.setAbrpEnabled(enabled)
    fun getPairedDevices(context: Context): List<BluetoothDevice> = controller.getPairedDevices(context)
    fun getBluetoothAdapter(context: Context): BluetoothAdapter? = controller.getBluetoothAdapter(context)
    fun connect(device: BluetoothDevice) = controller.connect(device)
    fun disconnect() = controller.disconnect()
    fun refreshNow() = controller.refreshNow()
}
