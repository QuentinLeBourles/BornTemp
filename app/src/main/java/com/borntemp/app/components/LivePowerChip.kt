package com.borntemp.app.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borntemp.app.ui.theme.*
import com.borntemp.app.viewmodel.ChargeState

/**
 * Live HV power strip — charge state on the left, V / I / P on the right.
 * Spec: 2026-06-21 design handoff §1 ("LivePowerChip, s'auto-cache si null").
 *
 * The pack's voltage, current and power are polled every tick and pushed to
 * ABRP, but until this component existed they were shown nowhere in the app,
 * which is why an active charge looked invisible on the cockpit.
 *
 * Sign convention is already normalised upstream in `ObdSessionController`
 * ("+ = into the pack"), so this only formats.
 */
@Composable
fun LivePowerChip(
    chargeState: ChargeState,
    powerKw: Float?,
    current: Float?,
    voltage: Float?,
    modifier: Modifier = Modifier
) {
    // Nothing measured this tick — stay out of the way rather than render a
    // row of dashes. Same "hide until there's data" rule as the collapsibles.
    if (powerKw == null && current == null) return

    val charging = chargeState == ChargeState.AC_CHARGING ||
                   chargeState == ChargeState.DC_CHARGING
    val accent = when {
        charging -> TealOk
        chargeState == ChargeState.NOT_CHARGING -> BornTextDim
        else -> BornMuted
    }

    Surface(
        color = PetrolSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(0.5.dp, if (charging) CobreBorderSoft else PetrolBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                // The FR labels already live on the enum — don't restate them.
                (if (charging) "⏵ " else "") + chargeState.label.uppercase(),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
                color = accent
            )
            Text(
                buildLiveValues(powerKw, current, voltage),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = if (charging) CupraSheen else BornText
            )
        }
    }
}

/** `+18.4 kW · +52.1 A · 402 V`, skipping whatever wasn't measured. */
private fun buildLiveValues(powerKw: Float?, current: Float?, voltage: Float?): String =
    listOfNotNull(
        powerKw?.let { "%+.1f kW".format(it) },
        current?.let { "%+.1f A".format(it) },
        voltage?.let { "%.0f V".format(it) }
    ).joinToString(" · ").ifEmpty { "--" }
