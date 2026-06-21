package com.borntemp.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.borntemp.app.ui.theme.BornTempTheme
import com.borntemp.app.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // ── Permission launcher ──────────────────────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "Permissions accordées", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Permissions Bluetooth requises pour se connecter à l'OBDLink CX",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── Bluetooth enable launcher ────────────────────────────────────────────

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            Toast.makeText(this, "Le Bluetooth doit être activé", Toast.LENGTH_LONG).show()
        }
    }

    // ── Location permission launcher (for ABRP telemetry) ────────────────────

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "ABRP : permission de localisation refusée — coordonnées GPS désactivées",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestBluetoothPermissions()

        setContent {
            BornTempTheme {
                val uiState by viewModel.uiState.collectAsState()
                val pairedDevices = viewModel.getPairedDevices(this)

                MainScreen(
                    uiState = uiState,
                    pairedDevices = pairedDevices,
                    onConnectDevice = { device ->
                        if (!hasBluetoothPermissions()) {
                            requestBluetoothPermissions()
                        } else {
                            val adapter = viewModel.getBluetoothAdapter(this)
                            if (adapter?.isEnabled == false) {
                                enableBluetoothLauncher.launch(
                                    Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                                )
                            } else {
                                viewModel.connect(device)
                            }
                        }
                    },
                    onDisconnect = { viewModel.disconnect() },
                    onRefresh = { viewModel.refreshNow() },
                    onPollingIntervalChange = { viewModel.setPollingInterval(it) },
                    onAbrpEnabledChange = { enabled ->
                        if (enabled &&
                            ContextCompat.checkSelfPermission(
                                this, Manifest.permission.ACCESS_FINE_LOCATION
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            locationPermissionLauncher.launch(
                                Manifest.permission.ACCESS_FINE_LOCATION
                            )
                        }
                        viewModel.setAbrpEnabled(enabled)
                    },
                    onAbrpApiKeyChange = { viewModel.setAbrpApiKey(it) },
                    onAbrpUserTokenChange = { viewModel.setAbrpUserToken(it) },
                    onPackTypeOverrideChange = { viewModel.setPackTypeOverride(it) }
                )
            }
        }
    }

    // ── Permission helpers ───────────────────────────────────────────────────

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
