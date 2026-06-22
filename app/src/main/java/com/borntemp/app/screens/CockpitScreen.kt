package com.borntemp.app.screens

import android.bluetooth.BluetoothDevice
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borntemp.app.BuildConfig
import com.borntemp.app.components.BleChip
import com.borntemp.app.components.CockpitHero
import com.borntemp.app.components.CollapsibleCard
import com.borntemp.app.ui.theme.*
import com.borntemp.app.viewmodel.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cockpit (home) screen — replaces the legacy 16-section scroll with a
 * hero block + a column of collapsible cards. Spec: 2026-06-21 design
 * handoff (`design_handoff_main_screen_rework/README.md`).
 *
 * Only consumes fields already present on [UiState] / [BatteryData] /
 * [ChargeProjection] / [ThermalTrajectory]. No new ViewModel state.
 */
@Composable
fun CockpitScreen(
    uiState: UiState,
    pairedDevices: List<BluetoothDevice>,
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
    val connected = uiState.connectionState == ConnectionState.CONNECTED
    val connecting = uiState.connectionState == ConnectionState.CONNECTING ||
                     uiState.connectionState == ConnectionState.INITIALIZING

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BornBg)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                CockpitHeader(
                    connectionState = uiState.connectionState,
                    deviceName = uiState.selectedDevice
                )
            }

            uiState.errorMessage?.let { err ->
                item { ErrorBanner(message = err, onClick = onOpenErrorDetail) }
            }

            item {
                CockpitHero(
                    tempAvg = uiState.batteryData.avgTemp,
                    tempMin = uiState.batteryData.cellTempMin,
                    tempMax = uiState.batteryData.cellTempMax,
                    tempSlopeCPerMin = uiState.thermalTrajectory.slopeCPerMin,
                    socHmi = uiState.batteryData.soc,
                    sohPct = uiState.batteryData.sohPct,
                    volt12v = uiState.batteryData.volt12v
                )
            }

            item {
                ChargePlannerEntryCard(onClick = onOpenEstimator)
            }

            item {
                CockpitHealthCard(
                    data = uiState.batteryData,
                    onOpenTrend = onOpenTrend
                )
            }

            item {
                CellsCollapsible(uiState.batteryData)
            }

            item {
                LifetimeCollapsible(uiState.batteryData)
            }

            item {
                TechDetailsCollapsible(uiState.batteryData)
            }

            item {
                JournalCollapsible(
                    logEntries = uiState.logEntries,
                    captureFileUri = uiState.captureFileUri,
                    captureFileName = uiState.captureFileName,
                    sohHistoryFileUri = uiState.sohHistoryFileUri,
                    sohHistoryFileName = uiState.sohHistoryFileName
                )
            }

            item {
                SettingsCollapsible(
                    pollingIntervalMs = uiState.pollingIntervalMs,
                    onPollingIntervalChange = onPollingIntervalChange,
                    abrp = uiState.abrp,
                    onAbrpEnabledChange = onAbrpEnabledChange,
                    onAbrpApiKeyChange = onAbrpApiKeyChange,
                    onAbrpUserTokenChange = onAbrpUserTokenChange,
                    packTypeOverride = uiState.packTypeOverride,
                    onPackTypeOverrideChange = onPackTypeOverrideChange
                )
            }

            item {
                ActionBar(
                    connected = connected,
                    connecting = connecting,
                    onPrimary = {
                        if (connected) onDisconnect() else showDeviceDialog = true
                    },
                    onRefresh = onRefresh
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "v${BuildConfig.VERSION_NAME}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.5.sp,
                    letterSpacing = 1.sp,
                    color = BornMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
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

// ── Header ──────────────────────────────────────────────────────────────────

@Composable
private fun CockpitHeader(connectionState: ConnectionState, deviceName: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row {
                Text(
                    "BORN",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    letterSpacing = 3.sp,
                    color = BornText
                )
                Text(
                    "TEMP",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    letterSpacing = 3.sp,
                    color = CupraCobre
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                "CUPRA BORN · BMS LIVE",
                fontFamily = FontFamily.Monospace,
                fontSize = 8.5.sp,
                letterSpacing = 2.sp,
                color = BornMuted
            )
        }
        val label = when (connectionState) {
            ConnectionState.CONNECTED    -> "BLE · CONNECTÉ"
            ConnectionState.CONNECTING   -> "BLE · CONNEXION..."
            ConnectionState.INITIALIZING -> "BLE · INIT..."
            ConnectionState.SCANNING     -> "BLE · SCAN..."
            ConnectionState.ERROR        -> "BLE · ERREUR"
            ConnectionState.DISCONNECTED -> "BLE · OFF"
        }
        BleChip(connected = connectionState == ConnectionState.CONNECTED, label = label)
    }
}

// ── Planner entry ──────────────────────────────────────────────────────────

@Composable
private fun ChargePlannerEntryCard(onClick: () -> Unit) {
    // Cobre gradient — the only filled-accent card on the screen so the
    // "go plan a charge" affordance is unmistakable.
    val gradient = Brush.linearGradient(
        colors = listOf(CupraCobre, Color(0xFF8A5436))
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(gradient)
            .clickable { onClick() }
            .padding(horizontal = 17.dp, vertical = 15.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "PLANIFIER",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = BornBg.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Estimer le temps de charge",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.3.sp,
                    color = BornBg
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Borne DC · cible SOC · coût",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.5.sp,
                    color = BornBg.copy(alpha = 0.65f)
                )
            }
            Text(
                "→",
                fontFamily = FontFamily.Monospace,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = BornBg
            )
        }
    }
}

// ── Health card (revised, cobre-bordered, open by default) ─────────────────

@Composable
private fun CockpitHealthCard(data: BatteryData, onOpenTrend: () -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    val sohPct = data.sohPct
    Surface(
        color = BornSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.5.dp, CupraCobre.copy(alpha = 0.30f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        "SANTÉ BATTERIE · SOH",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.5.sp,
                        color = BornMuted
                    )
                    Spacer(Modifier.height(7.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            sohPct?.let { "%.1f".format(it) } ?: "--",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 40.sp,
                            letterSpacing = (-2).sp,
                            color = CupraSheen
                        )
                        Text(
                            " %",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = BornMuted,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
                Text(
                    if (expanded) "▴" else "▾",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = BornMuted,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                // Always-visible progress bar + capacity sub-label.
                val fill = (sohPct?.div(100f))?.coerceIn(0f, 1f) ?: 0f
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .padding(top = 14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fill)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                Brush.horizontalGradient(listOf(CupraCobre, CupraSheen))
                            )
                    )
                }
                Spacer(Modifier.height(9.dp))
                Text(
                    capacityLine(data),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp,
                    color = BornMuted
                )
            }
            if (expanded) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
                    HealthBufferRow(data)
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.07f), thickness = 0.5.dp)
                    Spacer(Modifier.height(13.dp))
                    HealthSocRow(data)
                    Spacer(Modifier.height(14.dp))
                    HealthConfidenceRow(data)
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = onOpenTrend,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(0.5.dp, CupraCobre.copy(alpha = 0.55f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CupraSheen),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Text(
                            "VOIR LA TENDANCE SOH →",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.8.sp
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

private fun capacityLine(data: BatteryData): String {
    val mec = data.mecKwh?.let { "%.1f".format(it) } ?: "--"
    val orig = data.capacityOrigKwh?.let { "%.1f".format(it) } ?: "?"
    val pack = data.packType.label
    return "$mec / $orig kWh · $pack"
}

@Composable
private fun HealthBufferRow(data: BatteryData) {
    Text(
        "BUFFER",
        fontFamily = FontFamily.Monospace,
        fontSize = 7.5.sp,
        letterSpacing = 1.5.sp,
        color = BornMuted
    )
    Spacer(Modifier.height(6.dp))
    val mec = data.mecKwh
    val bottom = data.bufferBottomKwh ?: 0f
    val top = data.bufferTopKwh ?: 0f
    val usable = data.usableKwh ?: ((mec ?: 0f) - bottom - top)
    val total = (bottom + usable + top).coerceAtLeast(0.001f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            Modifier
                .weight((bottom / total).coerceAtLeast(0.001f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(AuroraBlue)
        )
        Box(
            Modifier
                .weight((usable / total).coerceAtLeast(0.001f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(TealOk.copy(alpha = 0.55f))
        )
        Box(
            Modifier
                .weight((top / total).coerceAtLeast(0.001f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(AmberHi.copy(alpha = 0.55f))
        )
    }
    Spacer(Modifier.height(5.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        listOf("BAS", "UTILISABLE", "HAUT").forEach {
            Text(
                it,
                fontFamily = FontFamily.Monospace,
                fontSize = 7.sp,
                letterSpacing = 1.sp,
                color = BornMuted
            )
        }
    }
}

@Composable
private fun HealthSocRow(data: BatteryData) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        SocStat("SOC HMI", data.soc?.let { "%.1f".format(it) } ?: "--", BornText)
        SocStat("SOC BMS", data.socBms?.let { "%.1f".format(it) } ?: "--", BornText)
        val buffer = if (data.soc != null && data.socBms != null)
            "%+.1f".format(data.socBms - data.soc) else "--"
        SocStat("TAMPON", buffer, CupraSheen)
    }
}

@Composable
private fun SocStat(label: String, value: String, color: Color) {
    Column {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 7.5.sp,
            letterSpacing = 1.5.sp,
            color = BornMuted
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun HealthConfidenceRow(data: BatteryData) {
    val (label, dotColor) = when (data.confidence) {
        SohConfidence.RELIABLE    -> "FIABLE" to TealOk
        SohConfidence.INDICATIVE  -> "INDICATIF" to AmberHi
        SohConfidence.UNAVAILABLE -> "INDISPO." to BornMuted
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            color = dotColor
        )
        Spacer(Modifier.width(8.dp))
        Text(
            data.confidenceReason ?: "--",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = BornTextDim,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ── Collapsibles ───────────────────────────────────────────────────────────

@Composable
private fun CellsCollapsible(data: BatteryData) {
    val delta = data.cellVoltDeltaMv
    val summary = delta?.let { "Δ $it mV" } ?: "--"
    val summaryColor = when {
        delta == null -> BornMuted
        delta >= 80   -> RedHi
        delta >= 30   -> AmberHi
        else          -> TealOk
    }
    CollapsibleCard(label = "CELLULES", summary = summary, summaryColor = summaryColor) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            CellStat("V MIN", data.cellVoltMinMv?.let { "%.3f V".format(it / 1000f) } ?: "--")
            CellStat("V MAX", data.cellVoltMaxMv?.let { "%.3f V".format(it / 1000f) } ?: "--")
            CellStat("T MIN", data.cellTempMin?.let { "%.1f".format(it) } ?: "--")
            CellStat("T MAX", data.cellTempMax?.let { "%.1f".format(it) } ?: "--")
        }
    }
}

@Composable
private fun CellStat(label: String, value: String) {
    Column {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 7.5.sp,
            letterSpacing = 1.5.sp,
            color = BornMuted
        )
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = BornText
        )
    }
}

@Composable
private fun LifetimeCollapsible(data: BatteryData) {
    val charge = data.lifetimeChargeKwh
    val discharge = data.lifetimeDischargeKwh
    val efficiency = if (charge != null && discharge != null && charge > 0f)
        (discharge / charge * 100f).coerceIn(0f, 110f) else null
    val summary = efficiency?.let { "%.1f %% effic.".format(it) } ?: "--"
    CollapsibleCard(label = "COMPTEURS VIE", summary = summary, summaryColor = TealOk) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            CellStat("CHARGÉ",   charge?.let { "%.2f MWh".format(it / 1000f) } ?: "--")
            CellStat("CONSOMMÉ", discharge?.let { "%.2f MWh".format(it / 1000f) } ?: "--")
            CellStat("EFFIC.",   efficiency?.let { "%.1f %%".format(it) } ?: "--")
        }
    }
}

@Composable
private fun TechDetailsCollapsible(data: BatteryData) {
    val summary = listOfNotNull(
        data.coolantPumpPct?.let { "pompe ${it.toInt()}%" },
        if (data.coolantTempIn != null || data.coolantTempOut != null) "fluide" else null,
        "proto"
    ).joinToString(" · ")
    CollapsibleCard(label = "DÉTAILS TECHNIQUES", summary = summary) {
        TechRow("POMPE",            data.coolantPumpPct?.let { "%.0f %%".format(it) } ?: "--")
        TechRow("FLUIDE IN / OUT",
            if (data.coolantTempIn != null || data.coolantTempOut != null)
                "${data.coolantTempIn?.let { "%.1f".format(it) } ?: "--"} / " +
                "${data.coolantTempOut?.let { "%.1f".format(it) } ?: "--"} °C"
            else "--"
        )
        TechRow("MODE",             data.vehicleMode.label)
        TechRow("DERNIER RELEVÉ",
            if (data.timestamp > 0L) {
                val secs = ((System.currentTimeMillis() - data.timestamp) / 1000L).coerceAtLeast(0L)
                "il y a $secs s"
            } else "--"
        )
        TechRow("PROTOCOLE", "ISO 15765-4 CAN · MEB 29-bit")
    }
}

@Composable
private fun TechRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
            color = BornMuted
        )
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = BornText
        )
    }
}

@Composable
private fun JournalCollapsible(
    logEntries: List<LogEntry>,
    captureFileUri: android.net.Uri?,
    captureFileName: String?,
    sohHistoryFileUri: android.net.Uri?,
    sohHistoryFileName: String?
) {
    val summary = "${logEntries.size} lignes"
    CollapsibleCard(label = "JOURNAL", summary = summary) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExportButton(
                label = "CAPTURE BRUTE →",
                uri = captureFileUri,
                fileName = captureFileName,
                mime = "text/plain",
                modifier = Modifier.weight(1f)
            )
            ExportButton(
                label = "HISTO SOH (CSV) →",
                uri = sohHistoryFileUri,
                fileName = sohHistoryFileName,
                mime = "text/csv",
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Surface(
            color = BornBg,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(0.5.dp, BornBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
            val listState = rememberLazyListState()
            LaunchedEffect(logEntries.size) {
                if (logEntries.isNotEmpty()) listState.animateScrollToItem(logEntries.size - 1)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(logEntries) { entry ->
                    Row {
                        Text(
                            "[${timeFormat.format(Date(entry.timestamp))}] ",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.5.sp,
                            color = BornMuted.copy(alpha = 0.4f)
                        )
                        Text(
                            entry.message,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.5.sp,
                            color = when (entry.level) {
                                LogLevel.OK    -> TealOk
                                LogLevel.WARN  -> AmberHi
                                LogLevel.ERROR -> RedHi
                                else           -> BornTextDim
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportButton(
    label: String,
    uri: android.net.Uri?,
    fileName: String?,
    mime: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val enabled = uri != null
    OutlinedButton(
        onClick = {
            uri?.let { u ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, u)
                    putExtra(Intent.EXTRA_SUBJECT, fileName ?: "BornTemp")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(send, "Exporter").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        },
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            0.5.dp,
            if (enabled) CupraCobre.copy(alpha = 0.55f) else BornBorder
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (enabled) CupraSheen else BornMuted
        ),
        contentPadding = PaddingValues(vertical = 8.dp),
        modifier = modifier
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp
        )
    }
}

@Composable
private fun SettingsCollapsible(
    pollingIntervalMs: Long,
    onPollingIntervalChange: (Long) -> Unit,
    abrp: AbrpUiState,
    onAbrpEnabledChange: (Boolean) -> Unit,
    onAbrpApiKeyChange: (String) -> Unit,
    onAbrpUserTokenChange: (String) -> Unit,
    packTypeOverride: PackTypeOverride,
    onPackTypeOverrideChange: (PackTypeOverride) -> Unit
) {
    val summary = "${pollingIntervalMs / 1000} s · ABRP ${if (abrp.enabled) "on" else "off"}"
    CollapsibleCard(label = "RÉGLAGES", summary = summary) {
        // Polling
        Text(
            "VITESSE DE RELEVÉ",
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            letterSpacing = 2.sp,
            color = BornMuted
        )
        Spacer(Modifier.height(6.dp))
        val intervals = listOf(2000L, 5000L, 10000L, 30000L)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            intervals.forEach { ms ->
                val selected = pollingIntervalMs == ms
                OutlinedButton(
                    onClick = { onPollingIntervalChange(ms) },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        0.5.dp,
                        if (selected) CupraCobre else BornBorder
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (selected) CupraSheen else BornTextDim,
                        containerColor = if (selected) CupraCobre.copy(alpha = 0.16f) else Color.Transparent
                    ),
                    contentPadding = PaddingValues(vertical = 7.dp, horizontal = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        "${ms / 1000} s",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Pack override
        Text(
            "TYPE PACK (RÉF SOH)",
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            letterSpacing = 2.sp,
            color = BornMuted
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PackTypeOverride.entries.forEach { opt ->
                val selected = opt == packTypeOverride
                OutlinedButton(
                    onClick = { onPackTypeOverrideChange(opt) },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        0.5.dp,
                        if (selected) CupraCobre else BornBorder
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (selected) CupraSheen else BornTextDim,
                        containerColor = if (selected) CupraCobre.copy(alpha = 0.16f) else Color.Transparent
                    ),
                    contentPadding = PaddingValues(vertical = 7.dp, horizontal = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        opt.label,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ABRP toggle + creds
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "ABRP TÉLÉMÉTRIE",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BornText
                )
                Text(
                    if (abrp.apiKey.isNotBlank() && abrp.userToken.isNotBlank())
                        "Configuré" else "Clé API + Token requis",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = if (abrp.apiKey.isNotBlank() && abrp.userToken.isNotBlank())
                        TealOk else AmberHi
                )
            }
            Switch(
                checked = abrp.enabled,
                onCheckedChange = onAbrpEnabledChange,
                enabled = (abrp.apiKey.isNotBlank() && abrp.userToken.isNotBlank()) || abrp.enabled
            )
        }
        OutlinedTextField(
            value = abrp.apiKey,
            onValueChange = onAbrpApiKeyChange,
            label = { Text("Clé API ABRP", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = BornText
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        OutlinedTextField(
            value = abrp.userToken,
            onValueChange = onAbrpUserTokenChange,
            label = { Text("Token utilisateur", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = BornText
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
    }
}

// ── Action bar ─────────────────────────────────────────────────────────────

@Composable
private fun ActionBar(
    connected: Boolean,
    connecting: Boolean,
    onPrimary: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = onPrimary,
            enabled = !connecting,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(
                0.5.dp,
                if (connected) RedHi.copy(alpha = 0.45f) else CupraCobre.copy(alpha = 0.55f)
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (connected) RedHi else CupraSheen
            ),
            contentPadding = PaddingValues(vertical = 10.dp)
        ) {
            Text(
                when {
                    connecting -> "CONNEXION..."
                    connected  -> "DÉCONNECTER"
                    else       -> "CONNECTER"
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
        }
        Button(
            onClick = onRefresh,
            enabled = connected,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CupraCobre,
                contentColor = BornBg,
                disabledContainerColor = CupraCobre.copy(alpha = 0.25f),
                disabledContentColor = BornBg.copy(alpha = 0.55f)
            ),
            contentPadding = PaddingValues(vertical = 10.dp)
        ) {
            Text(
                "RAFRAÎCHIR",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
        }
    }
}

// ── Error banner ───────────────────────────────────────────────────────────

@Composable
private fun ErrorBanner(message: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = RedHi.copy(alpha = 0.1f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(0.5.dp, RedHi.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                message,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = RedHi,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "VOIR →",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = RedHi
            )
        }
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
