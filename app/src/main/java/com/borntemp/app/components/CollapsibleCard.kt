package com.borntemp.app.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borntemp.app.ui.theme.*

/**
 * Reusable expandable card used by the Cockpit collapsibles
 * (Cellules / Compteurs vie / Détails techniques / Journal / Réglages).
 *
 * Defaults follow the Petrol passive-surface treatment from the 2026-06-21
 * handoff (§3 "Cards repliables"). The HealthCard reuses a different,
 * cobre-bordered styling on top of `BornSurface` — see [CockpitHealthCard]
 * for that variant.
 */
@Composable
fun CollapsibleCard(
    label: String,
    summary: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    surface: Color = PetrolSurface,
    border: Color = PetrolBorder,
    summaryColor: Color = BornTextDim,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Surface(
        color = surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(0.5.dp, border),
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    label,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                    color = BornMuted
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        summary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp,
                        color = summaryColor
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (expanded) "▴" else "▾",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = BornMuted
                    )
                }
            }
            if (expanded) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                    content()
                }
            }
        }
    }
}
