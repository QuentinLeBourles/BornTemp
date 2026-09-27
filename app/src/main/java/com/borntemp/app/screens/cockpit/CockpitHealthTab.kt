package com.borntemp.app.screens.cockpit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borntemp.app.components.CellStat
import com.borntemp.app.components.CollapsibleCard
import com.borntemp.app.ui.theme.*
import com.borntemp.app.viewmodel.BatteryData
import com.borntemp.app.viewmodel.SohConfidence
import com.borntemp.app.viewmodel.UiState

/** Santé tab — SOH health card + lifetime counters. */
@Composable
fun CockpitHealthTab(uiState: UiState, onOpenTrend: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item { CockpitHealthCard(data = uiState.batteryData, onOpenTrend = onOpenTrend) }
        item { LifetimeCollapsible(uiState.batteryData) }
    }
}

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
                            style = BornType.hero,
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
