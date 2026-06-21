package com.borntemp.app.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borntemp.app.ui.theme.*
import kotlin.math.abs

/**
 * Cockpit hero — temperature ring (left, 152 dp) + three stacked tiles
 * (SOC / SOH / 12V) on the right. Spec: README §1 "Cockpit hero".
 *
 * The ring is drawn with two arcs (track + value) instead of a conic gradient
 * because Compose `drawArc` is cheap and the gradient stops are uniform anyway.
 * Geometry mirrors the prototype: start at 135° (bottom-left), sweep clockwise.
 */

@Composable
fun CockpitHero(
    tempAvg: Float?,
    tempMin: Float?,
    tempMax: Float?,
    tempSlopeCPerMin: Float?,
    socHmi: Float?,
    sohPct: Float?,
    volt12v: Float?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TempRing(
            tempAvg = tempAvg,
            tempMin = tempMin,
            tempMax = tempMax,
            slope = tempSlopeCPerMin
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            CockpitTile(
                label = "SOC",
                value = socHmi?.let { "${it.toInt()}" } ?: "--",
                unit = "%",
                valueColor = BornText
            )
            CockpitTile(
                label = "SOH",
                value = sohPct?.let { "%.1f".format(it) } ?: "--",
                unit = "%",
                valueColor = CupraSheen,
                labelColor = CupraCobre,
                borderColor = CobreBorderSoft
            )
            CockpitTile12V(volt12v = volt12v)
        }
    }
}

@Composable
private fun TempRing(
    tempAvg: Float?,
    tempMin: Float?,
    tempMax: Float?,
    slope: Float?
) {
    // Fill range: 0..60 °C → 0..100 % of a 270° sweep. Matches the
    // prototype (24.6 °C → ~41 % fill, ~110° sweep).
    val fillFraction = ((tempAvg ?: 0f) / 60f).coerceIn(0f, 1f)
    val sweepDeg = 270f * fillFraction
    val (statusLabel, statusColor) = thermalStatus(tempAvg, slope)

    Box(
        modifier = Modifier.size(152.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(152.dp)) {
            val stroke = 11.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            // Full track at low opacity — exactly the prototype's
            // `rgba(255,255,255,0.05)` background ring.
            drawArc(
                color = Color.White.copy(alpha = 0.05f),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke)
            )
            if (tempAvg != null) {
                drawArc(
                    color = statusColor,
                    startAngle = 135f,
                    sweepAngle = sweepDeg,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    tempAvg?.let { "%.1f".format(it) } ?: "--",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 36.sp,
                    letterSpacing = (-1.5).sp,
                    color = BornText
                )
                Text(
                    "°C",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = BornMuted,
                    modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                statusLabel,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.5.sp,
                color = statusColor
            )
            Spacer(Modifier.height(3.dp))
            Text(
                rangeLabel(tempMin, tempMax),
                fontFamily = FontFamily.Monospace,
                fontSize = 7.5.sp,
                letterSpacing = 1.5.sp,
                color = BornMuted
            )
        }
    }
}

private fun rangeLabel(min: Float?, max: Float?): String {
    if (min == null && max == null) return "--"
    val a = min?.let { "%.1f".format(it) } ?: "--"
    val b = max?.let { "%.1f".format(it) } ?: "--"
    return "$a / $b"
}

private fun thermalStatus(tempAvg: Float?, slope: Float?): Pair<String, Color> = when {
    tempAvg == null     -> "EN ATTENTE" to BornMuted
    tempAvg < 10f       -> "FROIDE" to AuroraBlue
    tempAvg in 10f..15f -> "FRAÎCHE" to AuroraBlue
    tempAvg > 40f       -> "TRÈS CHAUDE" to RedHi
    tempAvg > 35f       -> "CHAUDE" to AmberHi
    slope != null && slope > 0.7f -> "MONTÉE" to AmberHi
    slope != null && abs(slope) > 0.3f -> "VARIABLE" to AmberHi
    else                -> "STABLE" to TealOk
}

@Composable
private fun CockpitTile(
    label: String,
    value: String,
    unit: String,
    valueColor: Color = BornText,
    labelColor: Color = BornMuted,
    borderColor: Color = BornBorder
) {
    Surface(
        color = BornSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.5.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp,
                color = labelColor
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 24.sp,
                    color = valueColor,
                    modifier = Modifier.padding(top = 1.dp)
                )
                Text(
                    " $unit",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    color = BornMuted,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun CockpitTile12V(volt12v: Float?) {
    val (status, color) = when {
        volt12v == null     -> "--" to BornMuted
        volt12v < 12.0f     -> "FAIBLE" to RedHi
        volt12v < 12.6f     -> "MOYEN" to AmberHi
        volt12v <= 14.6f    -> "OK" to TealOk
        else                -> "HAUT" to AmberHi
    }
    Surface(
        color = BornSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.5.dp, BornBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    "12V",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                    color = BornMuted
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        volt12v?.let { "%.1f".format(it) } ?: "--",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 19.sp,
                        color = BornText
                    )
                    Text(
                        " V",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        color = BornMuted,
                        modifier = Modifier.padding(bottom = 1.dp)
                    )
                }
            }
            Text(
                status,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = color
            )
        }
    }
}

/**
 * BLE status pill used in the cockpit header. Tinted cobre with a small
 * sheen dot when connected; falls back to muted when disconnected.
 */
@Composable
fun BleChip(connected: Boolean, label: String, modifier: Modifier = Modifier) {
    val tint = if (connected) CupraCobre else BornMuted
    Surface(
        color = tint.copy(alpha = 0.13f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(0.5.dp, tint.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (connected) CupraSheen else BornMuted)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
                color = if (connected) CupraSheen else BornMuted
            )
        }
    }
}
