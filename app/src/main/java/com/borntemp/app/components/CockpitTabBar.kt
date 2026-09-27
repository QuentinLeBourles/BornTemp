package com.borntemp.app.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borntemp.app.ui.theme.BornBorder
import com.borntemp.app.ui.theme.BornTextDim
import com.borntemp.app.ui.theme.CupraCobre
import com.borntemp.app.ui.theme.CupraSheen
import com.borntemp.app.ui.theme.Radius

/** The 3 sibling sections of the cockpit — flat tabs, no back stack. */
enum class CockpitTab(val label: String) {
    LIVE("LIVE"),
    SANTE("SANTÉ"),
    REGLAGES("RÉGLAGES")
}

/**
 * Bottom (or top, in landscape) tab selector. Deliberately not a stock M3
 * `NavigationBar` — this app is icon-free and typography-driven, so it reuses
 * the same "selected/unselected OutlinedButton" language already established
 * by the polling-interval and pack-type-override selectors.
 */
@Composable
fun CockpitTabBar(
    selected: CockpitTab,
    onSelect: (CockpitTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        CockpitTab.entries.forEach { tab ->
            val isSelected = tab == selected
            OutlinedButton(
                onClick = { onSelect(tab) },
                shape = RoundedCornerShape(Radius.sm),
                border = BorderStroke(0.5.dp, if (isSelected) CupraCobre else BornBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isSelected) CupraSheen else BornTextDim,
                    containerColor = if (isSelected) CupraCobre.copy(alpha = 0.16f) else Color.Transparent
                ),
                contentPadding = PaddingValues(vertical = 10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    tab.label,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
            }
        }
    }
}
