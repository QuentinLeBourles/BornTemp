package com.borntemp.app.screens

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borntemp.app.components.CockpitTab
import com.borntemp.app.screens.cockpit.CockpitLandscapeLayout
import com.borntemp.app.screens.cockpit.CockpitPortraitLayout
import com.borntemp.app.ui.theme.*
import com.borntemp.app.viewmodel.AutoConnectStatus
import com.borntemp.app.viewmodel.ConnectionState
import com.borntemp.app.viewmodel.PackTypeOverride
import com.borntemp.app.viewmodel.UiState

/**
 * Cockpit (home) screen — a fixed header/hero frame around 3 flat tabs
 * (Live / Santé / Réglages). Spec: 2026-06-21 design handoff (Piste B) +
 * 2026-07-20 tab restructure (Piste A), `docs/design-handoff-ui-rework.md`.
 *
 * Only consumes fields already present on [UiState] / [BatteryData] /
 * [ChargeProjection] / [ThermalTrajectory]. No new ViewModel state.
 *
 * This file just owns top-level screen state (selected tab, device dialog);
 * the actual frame lives in [CockpitPortraitLayout] and the tab content in
 * `screens/cockpit/CockpitLiveTab.kt` / `CockpitHealthTab.kt` / `CockpitReglagesTab.kt`.
 */
@Composable
fun CockpitScreen(
    uiState: UiState,
    pairedDevices: List<BluetoothDevice>,
    autoConnectStatus: AutoConnectStatus,
    onConnectDevice: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    onPollingIntervalChange: (Long) -> Unit,
    onAbrpEnabledChange: (Boolean) -> Unit,
    onAbrpApiKeyChange: (String) -> Unit,
    onAbrpUserTokenChange: (String) -> Unit,
    onPackTypeOverrideChange: (PackTypeOverride) -> Unit,
    onOpenEstimator: () -> Unit,
    onOpenTrend: () -> Unit,
    onOpenErrorDetail: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeviceDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(CockpitTab.LIVE) }
    val connected = uiState.connectionState == ConnectionState.CONNECTED
    val connecting = uiState.connectionState == ConnectionState.CONNECTING ||
                     uiState.connectionState == ConnectionState.INITIALIZING
    val autoConnectBanner = when (autoConnectStatus) {
        AutoConnectStatus.ARMED -> null
        AutoConnectStatus.NO_DEVICE_SAVED ->
            if (uiState.connectionState == ConnectionState.DISCONNECTED)
                "Connectez-vous une fois manuellement pour activer la connexion automatique."
            else null
        AutoConnectStatus.NO_PERMISSION ->
            "Connexion automatique désactivée — permission Bluetooth manquante."
        AutoConnectStatus.BLUETOOTH_OFF ->
            "Connexion automatique désactivée — Bluetooth éteint."
    }

    val onConnectToggle = { if (connected) onDisconnect() else showDeviceDialog = true }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(BornBg)
    ) {
        // 600dp matches Material3's own "medium" width breakpoint. Width is a
        // more robust signal than orientation flags — a narrow split-screen
        // pane in landscape shouldn't force a 2-column layout it can't fit.
        if (maxWidth >= 600.dp) {
            CockpitLandscapeLayout(
                uiState = uiState,
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
                autoConnectBanner = autoConnectBanner,
                connected = connected,
                connecting = connecting,
                onOpenEstimator = onOpenEstimator,
                onOpenTrend = onOpenTrend,
                onOpenErrorDetail = onOpenErrorDetail,
                onPollingIntervalChange = onPollingIntervalChange,
                onAbrpEnabledChange = onAbrpEnabledChange,
                onAbrpApiKeyChange = onAbrpApiKeyChange,
                onAbrpUserTokenChange = onAbrpUserTokenChange,
                onPackTypeOverrideChange = onPackTypeOverrideChange,
                onConnectToggle = onConnectToggle,
                onRefresh = onRefresh
            )
        } else {
            CockpitPortraitLayout(
                uiState = uiState,
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
                autoConnectBanner = autoConnectBanner,
                connected = connected,
                connecting = connecting,
                onOpenEstimator = onOpenEstimator,
                onOpenTrend = onOpenTrend,
                onOpenErrorDetail = onOpenErrorDetail,
                onPollingIntervalChange = onPollingIntervalChange,
                onAbrpEnabledChange = onAbrpEnabledChange,
                onAbrpApiKeyChange = onAbrpApiKeyChange,
                onAbrpUserTokenChange = onAbrpUserTokenChange,
                onPackTypeOverrideChange = onPackTypeOverrideChange,
                onConnectToggle = onConnectToggle,
                onRefresh = onRefresh
            )
        }
    }

    if (showDeviceDialog) {
        CockpitDeviceDialog(
            devices = pairedDevices,
            onSelect = {
                showDeviceDialog = false
                onConnectDevice(it)
            },
            onDismiss = { showDeviceDialog = false }
        )
    }
}

// ── Device dialog (mirrors the legacy one, restyled cobre) ─────────────────

@Composable
private fun CockpitDeviceDialog(
    devices: List<BluetoothDevice>,
    onSelect: (BluetoothDevice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BornSurface,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                "Sélectionner l'OBDLink CX",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = BornText
            )
        },
        text = {
            if (devices.isEmpty()) {
                Text(
                    "Aucun appareil OBD apparié trouvé.\n\nVa dans Paramètres → Bluetooth et appaire ton OBDLink CX d'abord.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = BornMuted,
                    lineHeight = 20.sp
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    devices.forEach { device ->
                        Surface(
                            onClick = { onSelect(device) },
                            color = BornBg,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(0.5.dp, BornBorder)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp, 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        @Suppress("MissingPermission") (device.name ?: "Appareil inconnu"),
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = BornText,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        device.address,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = BornMuted
                                    )
                                }
                                Text("→", color = CupraSheen, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler", fontFamily = FontFamily.Monospace, color = BornMuted)
            }
        }
    )
}
