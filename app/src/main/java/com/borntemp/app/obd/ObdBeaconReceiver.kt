package com.borntemp.app.obd

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * Watches for the monitored OBDLink CX's BLE advertisement (filtered on its
 * custom UART service UUID) so [ObdForegroundService] can start the moment
 * the car powers the adapter on — even if BornTemp isn't running. Also
 * re-arms the scan after a reboot or a Bluetooth toggle, since Android does
 * not persist registered scans across either.
 */
class ObdBeaconReceiver : BroadcastReceiver() {

    companion object {
        private val SERVICE_UUID: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        private const val ACTION_OBD_FOUND = "com.borntemp.app.ACTION_OBD_FOUND"

        private fun scanPendingIntent(context: Context): PendingIntent {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            return PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, ObdBeaconReceiver::class.java).setAction(ACTION_OBD_FOUND),
                flags
            )
        }

        private fun hasScanPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
            } else true
        }

        /**
         * Re-registers the background scan for the saved monitored device.
         * No-ops quietly if there's no monitored device yet, the scan
         * permission isn't granted, or Bluetooth is off — in all three
         * cases the caller (MainActivity) surfaces the reason in the UI.
         */
        @SuppressLint("MissingPermission")
        fun armDetection(context: Context) {
            if (MonitoredDeviceStore(context).deviceAddress == null) return
            if (!hasScanPermission(context)) return
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter ?: return
            if (!adapter.isEnabled) return
            val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner ?: return

            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build()
            scanner.startScan(listOf(filter), settings, scanPendingIntent(context))
        }

        @SuppressLint("MissingPermission")
        private fun disarmDetection(context: Context) {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanPendingIntent(context))
        }
    }

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            BluetoothAdapter.ACTION_STATE_CHANGED -> armDetection(context)
            ACTION_OBD_FOUND -> handleScanResults(context, intent)
        }
    }

    @SuppressLint("MissingPermission", "DEPRECATION")
    private fun handleScanResults(context: Context, intent: Intent) {
        val results: List<ScanResult> =
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT) ?: return
        val monitoredAddress = MonitoredDeviceStore(context).deviceAddress ?: return
        val match = results.firstOrNull { it.device.address == monitoredAddress } ?: return
        if (match.device.bondState != BluetoothDevice.BOND_BONDED) return

        disarmDetection(context)
        val serviceIntent = Intent(context, ObdForegroundService::class.java)
            .putExtra(ObdForegroundService.EXTRA_DEVICE_ADDRESS, match.device.address)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
