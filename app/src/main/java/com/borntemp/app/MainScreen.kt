package com.borntemp.app

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.borntemp.app.screens.ChargeEstimatorScreen
import com.borntemp.app.screens.CockpitScreen
import com.borntemp.app.screens.ErrorDetailScreen
import com.borntemp.app.screens.SohTrendScreen
import com.borntemp.app.viewmodel.ConnectionState
import com.borntemp.app.viewmodel.LogLevel
import com.borntemp.app.viewmodel.PackTypeOverride
import com.borntemp.app.viewmodel.UiState

/**
 * Top-level router for the BornTemp UI. Picks one of four destinations
 * based on local navigation state:
 *
 *   - "home"        → [CockpitScreen]            (default, the dashboard)
 *   - "estimator"   → [ChargeEstimatorScreen]    (DC fast-charge planner)
 *   - "trend"       → [SohTrendScreen]           (historical CSV chart)
 *   - "errorDetail" → [ErrorDetailScreen]        (full error stack + log)
 *
 * Also owns the `FLAG_KEEP_SCREEN_ON` lifecycle: as soon as we transition
 * into CONNECTING/INITIALIZING/CONNECTED, the screen is held awake; on any
 * other state (or on disposal) the flag is cleared.
 *
 * This file is intentionally tiny — the prior ~2200-line MainScreen.kt was
 * split into `screens/` and `components/` packages during the 2026-06-21
 * cockpit rework. See `docs/design-handoff-ui-rework.md`.
 */
@Composable
fun MainScreen(
    uiState: UiState,
    pairedDevices: List<BluetoothDevice>,
    autoConnectStatus: com.borntemp.app.viewmodel.AutoConnectStatus,
    onConnectDevice: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    onPollingIntervalChange: (Long) -> Unit,
    onAbrpEnabledChange: (Boolean) -> Unit,
    onAbrpApiKeyChange: (String) -> Unit,
    onAbrpUserTokenChange: (String) -> Unit,
    onPackTypeOverrideChange: (PackTypeOverride) -> Unit
) {
    var route by remember { mutableStateOf("home") }

    // Keep the screen awake while we have an active BLE conversation. The
    // 2026-06-21 field session showed the polling coroutine being throttled
    // ~8 min after the screen turned off, ending in "Connexion perdue".
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity, uiState.connectionState) {
        val window = activity?.window
        val keepOn = uiState.connectionState == ConnectionState.CONNECTED ||
                     uiState.connectionState == ConnectionState.INITIALIZING ||
                     uiState.connectionState == ConnectionState.CONNECTING
        if (window != null) {
            if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    when (route) {
        "estimator" -> ChargeEstimatorScreen(
            batteryData = uiState.batteryData,
            onBack = { route = "home" }
        )
        "trend" -> SohTrendScreen(onDismiss = { route = "home" })
        "errorDetail" -> ErrorDetailScreen(
            errorMessage = uiState.errorMessage ?: "(aucun détail)",
            errorLog = uiState.logEntries.filter {
                it.level == LogLevel.ERROR || it.level == LogLevel.WARN
            },
            onDismiss = { route = "home" }
        )
        else -> CockpitScreen(
            uiState = uiState,
            pairedDevices = pairedDevices,
            autoConnectStatus = autoConnectStatus,
            onConnectDevice = onConnectDevice,
            onDisconnect = onDisconnect,
            onRefresh = onRefresh,
            onPollingIntervalChange = onPollingIntervalChange,
            onAbrpEnabledChange = onAbrpEnabledChange,
            onAbrpApiKeyChange = onAbrpApiKeyChange,
            onAbrpUserTokenChange = onAbrpUserTokenChange,
            onPackTypeOverrideChange = onPackTypeOverrideChange,
            onOpenEstimator = { route = "estimator" },
            onOpenTrend = { route = "trend" },
            onOpenErrorDetail = { route = "errorDetail" }
        )
    }
}
