package com.borntemp.app.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borntemp.app.ui.theme.*
import com.borntemp.app.viewmodel.ChargeEstimator

/**
 * "Power vs SoC" bar chart for the Charge Estimator screen.
 * Spec: README §2 ("Power-vs-SOC chart card").
 *
 * Renders one bar per 10 % SoC band:
 *  - faint background bar = battery-achievable power at that SoC (after the
 *    current temperature derate),
 *  - solid foreground bar = effective power (= min(achievable, chargerKw)),
 *    coloured cobre when the band is inside [startSoc, targetSoc] and muted
 *    grey otherwise so the active range pops out without losing context.
 *
 * A dashed horizontal line marks the charger cap.
 */
@Composable
fun ChargeEstimatorChart(
    chargerKw: Float,
    startSoc: Float,
    targetSoc: Float,
    batteryCelsius: Float,
    ambientCelsius: Float = 20f,
    modifier: Modifier = Modifier
) {
    Surface(
        color = PetrolSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(0.5.dp, PetrolBorder),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "PUISSANCE VS SOC",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                    color = BornMuted
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LegendSwatch(color = CupraCobre, label = "UTILISÉ")
                    Spacer(Modifier.width(12.dp))
                    LegendSwatch(color = BornMuted, dashed = true, label = "BORNE")
                }
            }

            Spacer(Modifier.height(10.dp))

            // Combined cooling-margin × cell-temp derate, matches the engine
            // used by ChargeEstimator.estimate() so the chart's bars line up
            // with the result card's reported avg/peak.
            val tFactor = ChargeEstimator.tempFactor(batteryCelsius) *
                          ChargeEstimator.ambientFactor(ambientCelsius)
            val maxScaleKw = 180f

            Canvas(modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
            ) {
                val barAreaH = size.height - 10f
                val bandCount = 10
                val gap = 4f
                val barW = (size.width - gap * (bandCount - 1)) / bandCount

                for (i in 0 until bandCount) {
                    val socLo = i * 10f
                    val socHi = socLo + 10f
                    val midSoc = socLo + 5f
                    val achievable = ChargeEstimator.battMax(midSoc) * tFactor
                    val effective = minOf(achievable, chargerKw)

                    val x = i * (barW + gap)
                    val faintH = (achievable / maxScaleKw).coerceIn(0f, 1f) * barAreaH
                    val solidH = (effective / maxScaleKw).coerceIn(0f, 1f) * barAreaH

                    val inWindow = socHi > startSoc && socLo < targetSoc
                    val solidColor = if (inWindow) CupraCobre else Color(0xFF6B7A94).copy(alpha = 0.3f)

                    // Faint achievable bar (background)
                    drawRect(
                        color = Color.White.copy(alpha = 0.08f),
                        topLeft = Offset(x, barAreaH - faintH),
                        size = Size(barW, faintH)
                    )
                    // Solid effective bar (foreground)
                    drawRect(
                        color = solidColor,
                        topLeft = Offset(x, barAreaH - solidH),
                        size = Size(barW, solidH)
                    )
                }

                // Dashed line at charger cap
                val capY = barAreaH - (chargerKw / maxScaleKw).coerceIn(0f, 1f) * barAreaH
                drawLine(
                    color = CupraSheen.copy(alpha = 0.9f),
                    start = Offset(0f, capY),
                    end = Offset(size.width, capY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                )
            }

            Spacer(Modifier.height(6.dp))
            // X-axis labels at 0 / 25 / 50 / 75 / 100 %
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("0", "25", "50", "75", "100").forEach {
                    Text(
                        "$it %",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 7.5.sp,
                        letterSpacing = 1.sp,
                        color = BornMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (dashed) {
            // Mini dashed line indicator
            Canvas(modifier = Modifier.size(width = 14.dp, height = 6.dp)) {
                drawLine(
                    color = color,
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
                )
            }
        } else {
            Box(
                Modifier
                    .size(width = 10.dp, height = 6.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color)
            )
        }
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 7.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
            color = BornMuted
        )
    }
}
