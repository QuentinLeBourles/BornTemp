package com.borntemp.app.screens.cockpit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borntemp.app.components.CellStat
import com.borntemp.app.components.ChargeProjectionCard
import com.borntemp.app.components.CollapsibleCard
import com.borntemp.app.ui.theme.AmberHi
import com.borntemp.app.ui.theme.BornBg
import com.borntemp.app.ui.theme.BornMuted
import com.borntemp.app.ui.theme.CupraCobre
import com.borntemp.app.ui.theme.RedHi
import com.borntemp.app.ui.theme.Spacing
import com.borntemp.app.ui.theme.TealOk
import com.borntemp.app.viewmodel.BatteryData
import com.borntemp.app.viewmodel.ThermalAdvice
import com.borntemp.app.viewmodel.UiState

/** Live tab — the charge planner entry point + cell-level readings. The hero
 *  gauge is owned by the layout shell (portrait/landscape), not this tab, so
 *  it's never rendered twice. [showChargeProjection] is turned off in the
 *  landscape layout, which renders [ChargeProjectionCard] once in its
 *  persistent left pane instead. */
@Composable
fun CockpitLiveTab(
    uiState: UiState,
    onOpenEstimator: () -> Unit,
    showChargeProjection: Boolean = true
) {
    val showProjection = showChargeProjection && (
        uiState.chargeProjection.visible ||
            uiState.thermalTrajectory.advice != ThermalAdvice.NONE
        )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item { ChargePlannerEntryCard(onClick = onOpenEstimator) }
        if (showProjection) {
            item {
                ChargeProjectionCard(
                    projection = uiState.chargeProjection,
                    trajectory = uiState.thermalTrajectory
                )
            }
        }
        item { CellsCollapsible(uiState.batteryData) }
    }
}

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
