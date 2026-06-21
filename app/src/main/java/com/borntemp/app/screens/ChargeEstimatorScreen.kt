package com.borntemp.app.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borntemp.app.components.ChargeEstimatorChart
import com.borntemp.app.ui.theme.*
import com.borntemp.app.viewmodel.BatteryData
import com.borntemp.app.viewmodel.ChargeEstimator
import com.borntemp.app.viewmodel.PackType

/**
 * Charge Estimator — DC fast-charge planner. Spec: README §2.
 *
 * All controls are UI-local; the live `batteryData` is read once to seed
 * sensible defaults (current SOC for Départ, current battery temp for the
 * derate). Recomputes the estimate on every change.
 */
@Composable
fun ChargeEstimatorScreen(
    batteryData: BatteryData,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val packKwh = batteryData.capacityOrigKwh ?: ChargeEstimator.DEFAULT_PACK_KWH
    val packLabel = if (batteryData.packType != PackType.UNKNOWN) batteryData.packType.label else "LG"
    val liveSoc = batteryData.soc?.let { it.coerceIn(0f, 100f) }
    val batteryTemp = batteryData.avgTemp ?: 25f
    // Seed ambient from the coolant inlet — at idle/parked it equilibrates
    // with outside air well enough to be a useful first guess, and we don't
    // have a dedicated T_ext PID confirmed yet. Snap to the nearest chip preset.
    val seedAmbient = batteryData.coolantTempIn?.let { snapToPreset(it, ambientPresets()) } ?: 20f

    var chargerKw by remember { mutableStateOf(150f) }
    var startSoc by remember { mutableStateOf(20f) }
    var targetSoc by remember { mutableStateOf(80f) }
    var ambientTemp by remember { mutableStateOf(seedAmbient) }
    var costOn by remember { mutableStateOf(false) }
    var pricePerKwh by remember { mutableStateOf(0.45f) }

    val result = remember(chargerKw, startSoc, targetSoc, batteryTemp, ambientTemp, packKwh) {
        ChargeEstimator.estimate(chargerKw, startSoc, targetSoc, batteryTemp, ambientTemp, packKwh)
    }

    Box(modifier = modifier
        .fillMaxSize()
        .background(BornBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            EstimatorHeader(packKwh = packKwh, packLabel = packLabel, onBack = onBack)

            ChargerPowerSelector(
                current = chargerKw,
                onChange = { chargerKw = it }
            )

            AmbientTempSelector(
                current = ambientTemp,
                seededFromLive = batteryData.coolantTempIn != null,
                onChange = { ambientTemp = it }
            )

            SocSlider(
                label = "DÉPART SOC",
                value = startSoc,
                onValueChange = { startSoc = it },
                min = 5f, max = 90f, step = 5f,
                liveSoc = liveSoc,
                onUseLiveSoc = { liveSoc?.let { startSoc = roundToStep(it, 5f).coerceIn(5f, 90f) } }
            )

            SocSlider(
                label = "CIBLE SOC",
                value = targetSoc,
                onValueChange = { targetSoc = it },
                min = 50f, max = 100f, step = 5f
            )

            ResultCard(
                result = result,
                batteryTemp = batteryTemp,
                ambientTemp = ambientTemp
            )

            ChargeEstimatorChart(
                chargerKw = chargerKw,
                startSoc = startSoc,
                targetSoc = targetSoc,
                batteryCelsius = batteryTemp,
                ambientCelsius = ambientTemp,
                modifier = Modifier.fillMaxWidth()
            )

            CostCard(
                costOn = costOn,
                onCostOnChange = { costOn = it },
                pricePerKwh = pricePerKwh,
                onPriceChange = { pricePerKwh = it },
                energyKwh = result.energyKwh
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun roundToStep(value: Float, step: Float): Float =
    (Math.round(value / step) * step)

private fun ambientPresets(): List<Float> = listOf(0f, 10f, 20f, 30f, 40f)

private fun snapToPreset(value: Float, presets: List<Float>): Float =
    presets.minByOrNull { kotlin.math.abs(it - value) } ?: value

// ── Header ──────────────────────────────────────────────────────────────────

@Composable
private fun EstimatorHeader(packKwh: Float, packLabel: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "←",
            fontFamily = FontFamily.Monospace,
            fontSize = 22.sp,
            color = CupraSheen,
            modifier = Modifier
                .clickable { onBack() }
                .padding(end = 14.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "ESTIMATEUR DE CHARGE",
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp,
                color = BornText
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "DC RAPIDE · PACK %.0f kWh · %s".format(packKwh, packLabel.uppercase()),
                fontFamily = FontFamily.Monospace,
                fontSize = 8.5.sp,
                letterSpacing = 1.5.sp,
                color = BornMuted
            )
        }
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(CupraCobre)
        )
    }
}

// ── Charger chips ───────────────────────────────────────────────────────────

@Composable
private fun ChargerPowerSelector(current: Float, onChange: (Float) -> Unit) {
    Column {
        Text(
            "PUISSANCE BORNE · kW",
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            letterSpacing = 2.sp,
            color = BornMuted
        )
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(50f, 100f, 150f, 175f, 350f).forEach { kw ->
                val selected = current == kw
                OutlinedButton(
                    onClick = { onChange(kw) },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        0.5.dp,
                        if (selected) CupraCobre else Color.White.copy(alpha = 0.1f)
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (selected) CupraSheen else BornMuted,
                        containerColor = if (selected) CupraCobre.copy(alpha = 0.16f) else BornBg
                    ),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        kw.toInt().toString(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ── Ambient temp chips ─────────────────────────────────────────────────────

@Composable
private fun AmbientTempSelector(
    current: Float,
    seededFromLive: Boolean,
    onChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "T° AMBIANTE · °C",
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                letterSpacing = 2.sp,
                color = BornMuted
            )
            if (seededFromLive) {
                Text(
                    "depuis fluide OBD",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    letterSpacing = 1.sp,
                    color = CupraSheen
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ambientPresets().forEach { t ->
                val selected = current == t
                OutlinedButton(
                    onClick = { onChange(t) },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        0.5.dp,
                        if (selected) CupraCobre else Color.White.copy(alpha = 0.1f)
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (selected) CupraSheen else BornMuted,
                        containerColor = if (selected) CupraCobre.copy(alpha = 0.16f) else BornBg
                    ),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        "${t.toInt()}°",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ── SoC sliders ─────────────────────────────────────────────────────────────

@Composable
private fun SocSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    min: Float,
    max: Float,
    step: Float,
    liveSoc: Float? = null,
    onUseLiveSoc: (() -> Unit)? = null
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                letterSpacing = 2.sp,
                color = BornMuted
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (liveSoc != null && onUseLiveSoc != null) {
                    Surface(
                        color = CupraCobre.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(0.5.dp, CupraCobre.copy(alpha = 0.45f)),
                        modifier = Modifier.clickable { onUseLiveSoc() }
                    ) {
                        Text(
                            "= ACTUEL ${liveSoc.toInt()} %",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CupraSheen,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    "${value.toInt()} %",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = BornText
                )
            }
        }
        val steps = ((max - min) / step).toInt() - 1
        Slider(
            value = value,
            onValueChange = { v -> onValueChange(roundToStep(v, step).coerceIn(min, max)) },
            valueRange = min..max,
            steps = if (steps > 0) steps else 0,
            colors = SliderDefaults.colors(
                thumbColor = CupraSheen,
                activeTrackColor = CupraCobre,
                inactiveTrackColor = Color.White.copy(alpha = 0.1f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            ),
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

// ── Result card ─────────────────────────────────────────────────────────────

@Composable
private fun ResultCard(result: ChargeEstimator.Result, batteryTemp: Float, ambientTemp: Float) {
    val (verdictColor, verdictLabel) = when (result.verdict) {
        ChargeEstimator.Verdict.TRES_RAPIDE -> TealOk to "TRÈS RAPIDE"
        ChargeEstimator.Verdict.RAPIDE      -> CupraSheen to "RAPIDE"
        ChargeEstimator.Verdict.CORRECT     -> AmberHi to "CORRECT"
        ChargeEstimator.Verdict.LENT        -> RedHi to "LENT"
    }
    Surface(
        color = BornSurface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(0.5.dp, CobreBorderHi),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(Color(0xFF1B1410), BornSurface)),
                RoundedCornerShape(18.dp)
            )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        "TEMPS ESTIMÉ",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        letterSpacing = 2.sp,
                        color = BornMuted
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        ChargeEstimator.formatTime(result.timeMinutes),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-1.5).sp,
                        color = BornText
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "+%.0f %% · %.1f kWh".format(result.deltaSocPct, result.energyKwh),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = BornTextDim
                    )
                }
                VerdictChip(label = verdictLabel, color = verdictColor)
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.07f), thickness = 0.5.dp)
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricStat("PUISSANCE MOY", "%.0f kW".format(result.avgKw), BornText)
                MetricStat("CRÊTE", "%.0f kW".format(result.peakKw), CupraSheen)
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "%s · batt %.1f° · amb %.0f°".format(
                    result.limiterLabel, batteryTemp, ambientTemp
                ),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = BornMuted
            )
        }
    }
}

@Composable
private fun VerdictChip(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = color
            )
        }
    }
}

@Composable
private fun MetricStat(label: String, value: String, color: Color) {
    Column {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            letterSpacing = 1.5.sp,
            color = BornMuted
        )
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

// ── Cost card ───────────────────────────────────────────────────────────────

@Composable
private fun CostCard(
    costOn: Boolean,
    onCostOnChange: (Boolean) -> Unit,
    pricePerKwh: Float,
    onPriceChange: (Float) -> Unit,
    energyKwh: Float
) {
    Surface(
        color = PetrolSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(0.5.dp, PetrolBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "AJOUTER LE COÛT",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp,
                        color = if (costOn) CupraSheen else BornMuted
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Affiche un total estimé en €",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = BornMuted
                    )
                }
                Switch(
                    checked = costOn,
                    onCheckedChange = onCostOnChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CupraSheen,
                        checkedTrackColor = CupraCobre,
                        checkedBorderColor = CupraCobre,
                        uncheckedThumbColor = BornMuted,
                        uncheckedTrackColor = BornBg,
                        uncheckedBorderColor = BornBorder
                    )
                )
            }
            if (costOn) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "PRIX €/kWh",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    letterSpacing = 2.sp,
                    color = BornMuted
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.25f, 0.45f, 0.59f, 0.79f).forEach { p ->
                        val selected = pricePerKwh == p
                        OutlinedButton(
                            onClick = { onPriceChange(p) },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                0.5.dp,
                                if (selected) CupraCobre else Color.White.copy(alpha = 0.1f)
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (selected) CupraSheen else BornMuted,
                                containerColor = if (selected) CupraCobre.copy(alpha = 0.16f) else BornBg
                            ),
                            contentPadding = PaddingValues(vertical = 7.dp, horizontal = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                "%.2f".format(p).replace('.', ','),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "COÛT ESTIMÉ",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    letterSpacing = 2.sp,
                    color = BornMuted
                )
                Text(
                    "%.2f €".format(energyKwh * pricePerKwh).replace('.', ','),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = CupraSheen
                )
            }
        }
    }
}
