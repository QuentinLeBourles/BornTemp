package com.borntemp.app.screens.cockpit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borntemp.app.components.CollapsibleCard
import com.borntemp.app.domain.ChargeRecord
import com.borntemp.app.domain.MonthEnergy
import com.borntemp.app.domain.ParkedGap
import com.borntemp.app.ui.theme.BornMuted
import com.borntemp.app.ui.theme.BornText
import com.borntemp.app.ui.theme.BornTextDim
import com.borntemp.app.ui.theme.CupraSheen
import com.borntemp.app.ui.theme.Spacing
import com.borntemp.app.ui.theme.TealOk
import com.borntemp.app.viewmodel.UiState
import java.time.ZoneId
import java.util.Locale

/**
 * Historique tab — what the app has kept while the car was away: past charges,
 * energy per month from the BMS counters, and standby drain between
 * connections. Everything here reads from the offline store, so it works with
 * no OBD connection at all.
 */
@Composable
fun CockpitHistoryTab(uiState: UiState) {
    val zone = remember { ZoneId.systemDefault() }
    val offline = uiState.offline
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item { ChargesCard(offline.charges, zone) }
        item { MonthsCard(offline.months) }
        item { ParkedCard(offline.parked, zone) }
    }
}

@Composable
private fun ChargesCard(charges: List<ChargeRecord>, zone: ZoneId) {
    val total = charges.mapNotNull { it.bestKwh }.sum()
    CollapsibleCard(
        label = "CHARGES",
        summary = if (charges.isEmpty()) "aucune" else "${charges.size} · ${"%.0f".format(Locale.US, total)} kWh",
        initiallyExpanded = true,
    ) {
        if (charges.isEmpty()) {
            EmptyNote("Aucune charge enregistrée. Elle apparaîtra ici après la prochaine charge suivie en direct.")
            return@CollapsibleCard
        }
        charges.forEachIndexed { i, r ->
            if (i > 0) Divider()
            val l = chargeLines(r, zone)
            Text(l.title, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = BornMuted)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(l.soc, fontFamily = FontFamily.Monospace, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, color = BornText)
                Text(l.energy, fontFamily = FontFamily.Monospace, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, color = CupraSheen)
            }
            listOf(l.power, l.thermal).filter { it.isNotEmpty() }.forEach {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = BornTextDim)
            }
        }
    }
}

@Composable
private fun MonthsCard(months: List<MonthEnergy>) {
    CollapsibleCard(
        label = "ÉNERGIE PAR MOIS",
        summary = months.firstOrNull()?.let { "${monthLabel(it.month)} · ${"%.0f".format(Locale.US, it.chargedKwh)} kWh" } ?: "--",
        initiallyExpanded = true,
    ) {
        if (months.isEmpty()) {
            EmptyNote("Il faut au moins deux relevés des compteurs BMS. Reviens après la prochaine connexion.")
            return@CollapsibleCard
        }
        Row(Modifier.fillMaxWidth()) {
            Header("MOIS", Modifier.weight(1.2f))
            Header("CHARGÉ", Modifier.weight(1f))
            Header("DÉCHARGÉ", Modifier.weight(1f))
        }
        months.forEach { m ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Cell(monthLabel(m.month), BornText, Modifier.weight(1.2f))
                Cell("${"%.1f".format(Locale.US, m.chargedKwh)} kWh", TealOk, Modifier.weight(1f))
                Cell("${"%.1f".format(Locale.US, m.dischargedKwh)} kWh", BornTextDim, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
        EmptyNote("Compteurs de la batterie : l'énergie utilisée sans le téléphone est comptée aussi.")
    }
}

@Composable
private fun ParkedCard(gaps: List<ParkedGap>, zone: ZoneId) {
    val perDay = gaps.mapNotNull { it.dischargeKwhPerDay }.sorted()
    val median = perDay.getOrNull(perDay.size / 2)
    CollapsibleCard(
        label = "VEILLE",
        summary = median?.let { "${"%.2f".format(Locale.US, it)} kWh/j" } ?: "--",
        initiallyExpanded = true,
    ) {
        if (gaps.isEmpty()) {
            EmptyNote("Mesurée entre deux connexions espacées d'au moins 6 h, sans roulage ni charge entre les deux.")
            return@CollapsibleCard
        }
        gaps.take(10).forEach {
            Text(parkedLine(it, zone), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = BornText, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = BornMuted)
}

@Composable
private fun Header(text: String, modifier: Modifier) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 8.sp, letterSpacing = 2.sp,
        color = BornMuted, modifier = modifier)
}

@Composable
private fun Cell(text: String, color: Color, modifier: Modifier) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = color, modifier = modifier)
}

@Composable
private fun Divider() {
    Column(Modifier.padding(vertical = 10.dp)) {
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f), thickness = 0.5.dp)
    }
}
