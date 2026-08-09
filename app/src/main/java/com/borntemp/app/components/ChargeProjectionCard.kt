package com.borntemp.app.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borntemp.app.ui.theme.*
import com.borntemp.app.viewmodel.ChargeEstimator
import com.borntemp.app.viewmodel.ChargeProjection

/**
 * "Charge en cours" card — live ETA toward 80 / 100 %, SoC slope and the
 * integrated capacity cross-check. Spec: 2026-06-21 design handoff §1
 * ("ChargeProjectionCard", visible si DC/AC).
 *
 * Everything here was already computed each tick into `uiState.chargeProjection`
 * and simply never rendered. Distinct from [ChargeEstimatorScreen]'s planner:
 * that one *predicts* a charge from sliders, this one *reports* the one running.
 */
@Composable
fun ChargeProjectionCard(
    projection: ChargeProjection,
    modifier: Modifier = Modifier
) {
    // `visible` already means "the car is in a CHARGING_* mode".
    if (!projection.visible) return

    Surface(
        color = BornSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(0.5.dp, CobreBorderHi),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "CHARGE EN COURS · TEMPS RESTANT",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                    color = BornMuted
                )
                projection.avgPowerKw?.let {
                    Text(
                        "%.1f kW".format(it),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TealOk
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // An ETA can be null while the power window is still filling. Keep
            // the row with a dash rather than collapsing the card — the slope
            // and average power are already worth showing on their own.
            EtaRow("→ 80 %", projection.etaMinutesTo80)
            Spacer(Modifier.height(6.dp))
            EtaRow("→ 100 %", projection.etaMinutesTo100)

            Spacer(Modifier.height(12.dp))

            projection.socSlopePctPerMin?.let {
                DetailRow("Pente SOC", "%+.2f %%/min".format(it))
            }
            projection.apparentCapacityKwh?.let {
                DetailRow("Capacité intégrée", "%.1f kWh".format(it))
            }
        }
    }
}

@Composable
private fun EtaRow(label: String, minutes: Float?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = BornTextDim
        )
        Text(
            minutes?.let { ChargeEstimator.formatTime(it) } ?: "--",
            fontFamily = FontFamily.Monospace,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp,
            color = CupraSheen
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = BornMuted
        )
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = BornTextDim
        )
    }
}
