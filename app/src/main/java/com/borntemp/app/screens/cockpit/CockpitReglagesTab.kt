package com.borntemp.app.screens.cockpit

import com.borntemp.app.viewmodel.AcquisitionSetting
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.borntemp.app.components.CollapsibleCard
import com.borntemp.app.ui.theme.*
import com.borntemp.app.viewmodel.AbrpUiState
import com.borntemp.app.viewmodel.BatteryData
import com.borntemp.app.viewmodel.LogEntry
import com.borntemp.app.viewmodel.LogLevel
import com.borntemp.app.viewmodel.PackTypeOverride
import com.borntemp.app.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Réglages tab — tech details, log/export panel, polling/ABRP/pack settings. */
@Composable
fun CockpitReglagesTab(
    uiState: UiState,
    onPollingIntervalChange: (Long) -> Unit,
    onAcquisitionSettingChange: (AcquisitionSetting) -> Unit,
    onAbrpEnabledChange: (Boolean) -> Unit,
    onAbrpApiKeyChange: (String) -> Unit,
    onAbrpUserTokenChange: (String) -> Unit,
    onPackTypeOverrideChange: (PackTypeOverride) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item { TechDetailsCollapsible(uiState.batteryData) }

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
                chargingPollingIntervalMs = uiState.chargingPollingIntervalMs,
                onPollingIntervalChange = onPollingIntervalChange,
                onAcquisitionSettingChange = onAcquisitionSettingChange,
                abrp = uiState.abrp,
                onAbrpEnabledChange = onAbrpEnabledChange,
                onAbrpApiKeyChange = onAbrpApiKeyChange,
                onAbrpUserTokenChange = onAbrpUserTokenChange,
                packTypeOverride = uiState.packTypeOverride,
                onPackTypeOverrideChange = onPackTypeOverrideChange
            )
        }

        item {
            Spacer(Modifier.height(Spacing.sm))
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
    chargingPollingIntervalMs: Long,
    onPollingIntervalChange: (Long) -> Unit,
    onAcquisitionSettingChange: (AcquisitionSetting) -> Unit,
    abrp: AbrpUiState,
    onAbrpEnabledChange: (Boolean) -> Unit,
    onAbrpApiKeyChange: (String) -> Unit,
    onAbrpUserTokenChange: (String) -> Unit,
    packTypeOverride: PackTypeOverride,
    onPackTypeOverrideChange: (PackTypeOverride) -> Unit
) {
    val summary = "${chargingPollingIntervalMs / 1000} s charge · ${pollingIntervalMs / 1000} s roulage · ABRP ${if (abrp.enabled) "on" else "off"}"
    CollapsibleCard(label = "RÉGLAGES", summary = summary) {
        // Polling
        Text(
            "RELEVÉ EN CHARGE",
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            letterSpacing = 2.sp,
            color = BornMuted
        )
        Spacer(Modifier.height(6.dp))
        IntervalChoiceRow(
            intervals = listOf(5000L, 7000L, 10000L),
            selectedMs = chargingPollingIntervalMs,
            onSelect = { onAcquisitionSettingChange(AcquisitionSetting.ChargingPollInterval(it)) },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "RELEVÉ HORS CHARGE",
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            letterSpacing = 2.sp,
            color = BornMuted
        )
        Spacer(Modifier.height(6.dp))
        IntervalChoiceRow(
            intervals = listOf(5000L, 10000L, 30000L, 60000L),
            selectedMs = pollingIntervalMs,
            onSelect = onPollingIntervalChange,
        )

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

/** One row of interval choices, the selected one highlighted in copper. */
@Composable
private fun IntervalChoiceRow(intervals: List<Long>, selectedMs: Long, onSelect: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        intervals.forEach { ms ->
            val selected = selectedMs == ms
            OutlinedButton(
                onClick = { onSelect(ms) },
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
}
