package com.borntemp.app.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borntemp.app.ui.theme.*
import com.borntemp.app.viewmodel.LogEntry
import com.borntemp.app.viewmodel.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen detail view for the last connection error. Extracted from
 * MainScreen.kt during the 2026-06-21 cockpit rework. Reached when the
 * user taps the error banner on the Cockpit screen.
 */
@Composable
fun ErrorDetailScreen(
    errorMessage: String,
    errorLog: List<LogEntry>,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

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
                            "ERREUR",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            color = RedHi
                        )
                        Text(
                            "Détails complets",
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
            Surface(
                color = RedHi.copy(alpha = 0.08f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(0.5.dp, RedHi.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer {
                    Text(
                        errorMessage,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = RedHi,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            if (errorLog.isNotEmpty()) {
                Text(
                    "HISTORIQUE",
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
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        errorLog.asReversed().forEach { entry ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "[${timeFormat.format(Date(entry.timestamp))}]",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = BornMuted.copy(alpha = 0.6f)
                                )
                                SelectionContainer {
                                    Text(
                                        entry.message,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = if (entry.level == LogLevel.ERROR) RedHi else AmberHi,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
