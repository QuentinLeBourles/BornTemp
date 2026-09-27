package com.borntemp.app.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.borntemp.app.ui.theme.*
import com.borntemp.app.viewmodel.ChargeProjection
import com.borntemp.app.viewmodel.ThermalAdvice
import com.borntemp.app.viewmodel.ThermalTrajectory

/**
 * Surfaces Handoff-2's [ChargeProjection] and [ThermalTrajectory] — both were
 * already computed by `ChargeAnalytics` but never rendered anywhere before
 * this. Each half only renders when it has something to say (a charge in
 * progress / an active thermal advice), so the card disappears entirely
 * when idle instead of showing empty placeholders.
 */
@Composable
fun ChargeProjectionCard(
    projection: ChargeProjection,
    trajectory: ThermalTrajectory,
    modifier: Modifier = Modifier
) {
    val showCharge = projection.visible
    val showThermal = trajectory.advice != ThermalAdvice.NONE
    if (!showCharge && !showThermal) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        if (showCharge) ChargeEtaSection(projection)
        if (showThermal) ThermalAdviceSection(trajectory)
    }
}

@Composable
private fun ChargeEtaSection(projection: ChargeProjection) {
    CollapsibleCard(
        label = "TEMPS RESTANT",
        summary = projection.avgPowerKw?.let { "%.1f kW".format(it) } ?: "--",
        initiallyExpanded = true
    ) {
        EtaRow("→ 80 %", formatEta(projection.etaMinutesTo80))
        Spacer(Modifier.height(Spacing.sm))
        EtaRow("→ 100 %", formatEta(projection.etaMinutesTo100))
        Spacer(Modifier.height(Spacing.sm))
        EtaRow("PENTE SOC", projection.socSlopePctPerMin?.let { "%+.2f %%/min".format(it) } ?: "--")
        Spacer(Modifier.height(Spacing.sm))
        EtaRow("CAPACITÉ INTÉGRÉE", projection.apparentCapacityKwh?.let { "%.1f kWh".format(it) } ?: "--")
    }
}

@Composable
private fun ThermalAdviceSection(trajectory: ThermalTrajectory) {
    val tint = when (trajectory.advice) {
        ThermalAdvice.OPTIMAL -> TealOk
        ThermalAdvice.DRIVE_BEFORE_CHARGING,
        ThermalAdvice.POSTPONE_CHARGE,
        ThermalAdvice.CHARGE_DERATED -> AmberHi
        ThermalAdvice.NONE -> BornMuted
    }
    CollapsibleCard(
        label = "TRAJECTOIRE THERMIQUE",
        summary = trajectory.advice.label,
        summaryColor = tint,
        initiallyExpanded = true
    ) {
        EtaRow("PENTE TEMP", trajectory.slopeCPerMin?.let { "%+.2f °C/min".format(it) } ?: "--")
        trajectory.minutesToOptimal?.let {
            Spacer(Modifier.height(Spacing.sm))
            EtaRow("ATTEINT 15°C DANS", "~${it.toInt()} min")
        }
        trajectory.minutesToHot?.let {
            Spacer(Modifier.height(Spacing.sm))
            EtaRow("ATTEINT 40°C DANS", "~${it.toInt()} min")
        }
        trajectory.adviceDetail?.let { detail ->
            Spacer(Modifier.height(Spacing.sm))
            Text(
                detail,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = BornTextDim
            )
        }
    }
}

@Composable
private fun EtaRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = BornType.eyebrow, color = BornMuted)
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = BornText
        )
    }
}

private fun formatEta(minutes: Float?): String {
    if (minutes == null) return "--"
    val total = minutes.toInt()
    val h = total / 60
    val m = total % 60
    return if (h > 0) "${h}h ${m.toString().padStart(2, '0')}m" else "$m min"
}
