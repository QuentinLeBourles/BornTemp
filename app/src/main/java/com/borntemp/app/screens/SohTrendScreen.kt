package com.borntemp.app.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borntemp.app.ui.theme.*
import com.borntemp.app.viewmodel.SohConfidence
import com.borntemp.app.viewmodel.SohHistoryReader
import com.borntemp.app.viewmodel.SohTrendSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SOH historical-trend screen. Lists past sessions' CSVs, fits a simple line
 * chart over SOH-vs-date, colours dots by confidence. Reached from the
 * [CockpitHealthCard]'s "VOIR LA TENDANCE SOH →" button.
 *
 * Originally lived inside MainScreen.kt; extracted 2026-06-21 as part of
 * the cockpit rework so the main router stays small.
 */
@Composable
fun SohTrendScreen(onDismiss: () -> Unit) {
    BackHandler { onDismiss() }
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    val fullFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    val snapshot = remember {
        val entries = SohHistoryReader.loadAll(context)
        SohTrendSnapshot.from(entries)
    }

    Scaffold(
        containerColor = BornBg,
        topBar = {
            Surface(color = BornSurface) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            "← RETOUR",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = BornText,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            "TENDANCE SOH",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            color = CupraSheen
                        )
                        Text(
                            "${snapshot.sampleCount} relevés · ${snapshot.sessionCount} sessions",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = BornMuted
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (snapshot.entries.isEmpty()) {
                Surface(
                    color = BornSurface,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(0.5.dp, BornBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Aucun historique trouvé.\n\nConnecte-toi à la voiture pendant quelques " +
                                "minutes : un fichier CSV est créé à chaque session, et la courbe " +
                                "se peuplera ici.",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = BornMuted,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(20.dp)
                    )
                }
                return@Column
            }

            Surface(
                color = BornSurface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(0.5.dp, BornBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    TrendInfoRow("Premier relevé",
                        if (snapshot.firstTs > 0L) fullFormat.format(Date(snapshot.firstTs)) else "--")
                    HorizontalDivider(color = BornBorder, thickness = 0.5.dp)
                    TrendInfoRow("Dernier relevé",
                        if (snapshot.lastTs > 0L) fullFormat.format(Date(snapshot.lastTs)) else "--")
                    HorizontalDivider(color = BornBorder, thickness = 0.5.dp)
                    TrendInfoRow("SOH initial",
                        snapshot.firstSoh?.let { "%.2f %%".format(it) } ?: "--")
                    HorizontalDivider(color = BornBorder, thickness = 0.5.dp)
                    TrendInfoRow("SOH courant",
                        snapshot.lastSoh?.let { "%.2f %%".format(it) } ?: "--")
                    HorizontalDivider(color = BornBorder, thickness = 0.5.dp)
                    val delta = if (snapshot.firstSoh != null && snapshot.lastSoh != null)
                        "%+.2f %%".format(snapshot.lastSoh - snapshot.firstSoh) else "--"
                    TrendInfoRow("Δ depuis le premier relevé", delta)
                }
            }

            Text(
                "ÉVOLUTION SOH",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = BornMuted,
                letterSpacing = 2.sp
            )
            SohTrendChart(
                snapshot = snapshot,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot("Fiable", TealOk)
                LegendDot("Indicatif", AmberHi)
                LegendDot("Indispo.", BornMuted)
            }

            Text(
                "DERNIERS RELEVÉS",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = BornMuted,
                letterSpacing = 2.sp
            )
            Surface(
                color = BornSurface,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(0.5.dp, BornBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    snapshot.entries.asReversed().take(15).forEach { e ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                timeFormat.format(Date(e.timestampMs)),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = BornMuted,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                e.sohPct?.let { "%.2f %%".format(it) } ?: "--",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = BornText,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                e.tempAvgC?.let { "%.1f °C".format(it) } ?: "--",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = BornMuted,
                                modifier = Modifier.weight(1f)
                            )
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(confidenceColor(e.confidence))
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendInfoRow(key: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(key, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = BornMuted)
        Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp, color = BornText)
    }
}

private fun confidenceColor(c: SohConfidence): Color = when (c) {
    SohConfidence.RELIABLE    -> TealOk
    SohConfidence.INDICATIVE  -> AmberHi
    SohConfidence.UNAVAILABLE -> BornMuted
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = BornMuted
        )
    }
}

@Composable
private fun SohTrendChart(snapshot: SohTrendSnapshot, modifier: Modifier = Modifier) {
    val tStart = snapshot.firstTs
    val tSpan = (snapshot.lastTs - snapshot.firstTs).coerceAtLeast(1L)
    val sohSpan = (snapshot.maxSoh - snapshot.minSoh).coerceAtLeast(0.01f)

    Surface(
        color = BornSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(0.5.dp, BornBorder),
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            val w = size.width
            val h = size.height
            val gridColor = BornBorder.copy(alpha = 0.6f)
            for (i in 0..4) {
                val y = h * i / 4f
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 0.5.dp.toPx()
                )
            }
            val pts = snapshot.entries.mapNotNull { e ->
                val soh = e.sohPct ?: return@mapNotNull null
                val x = (e.timestampMs - tStart).toFloat() / tSpan.toFloat() * w
                val y = h - (soh - snapshot.minSoh) / sohSpan * h
                Offset(x, y) to e.confidence
            }
            for (i in 1 until pts.size) {
                drawLine(
                    color = CupraSheen.copy(alpha = 0.55f),
                    start = pts[i - 1].first,
                    end = pts[i].first,
                    strokeWidth = 1.5.dp.toPx()
                )
            }
            for ((p, conf) in pts) {
                drawCircle(
                    color = when (conf) {
                        SohConfidence.RELIABLE    -> TealOk
                        SohConfidence.INDICATIVE  -> AmberHi
                        SohConfidence.UNAVAILABLE -> BornMuted
                    },
                    radius = 3.dp.toPx(),
                    center = p
                )
            }
        }
    }
}
