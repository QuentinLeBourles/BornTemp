package com.borntemp.app.screens.cockpit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borntemp.app.components.BleChip
import com.borntemp.app.ui.theme.*
import com.borntemp.app.viewmodel.ConnectionState

/** Shared frame pieces used identically by both the portrait and landscape
 *  cockpit layouts — the wordmark/BLE-status header and the
 *  connect/refresh action bar. */

@Composable
internal fun CockpitHeader(
    connectionState: ConnectionState,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row {
                Text(
                    "BORN",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    letterSpacing = 3.sp,
                    color = BornText
                )
                Text(
                    "TEMP",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    letterSpacing = 3.sp,
                    color = CupraCobre
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                "CUPRA BORN · BMS LIVE",
                fontFamily = FontFamily.Monospace,
                fontSize = 8.5.sp,
                letterSpacing = 2.sp,
                color = BornMuted
            )
        }
        val label = when (connectionState) {
            ConnectionState.CONNECTED    -> "BLE · CONNECTÉ"
            ConnectionState.CONNECTING   -> "BLE · CONNEXION..."
            ConnectionState.INITIALIZING -> "BLE · INIT..."
            ConnectionState.SCANNING     -> "BLE · SCAN..."
            ConnectionState.ERROR        -> "BLE · ERREUR"
            ConnectionState.DISCONNECTED -> "BLE · OFF"
        }
        BleChip(connectionState = connectionState, label = label)
    }
}

@Composable
internal fun ActionBar(
    connected: Boolean,
    connecting: Boolean,
    onPrimary: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = onPrimary,
            enabled = !connecting,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(
                0.5.dp,
                if (connected) RedHi.copy(alpha = 0.45f) else CupraCobre.copy(alpha = 0.55f)
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (connected) RedHi else CupraSheen
            ),
            contentPadding = PaddingValues(vertical = 10.dp)
        ) {
            Text(
                when {
                    connecting -> "CONNEXION..."
                    connected  -> "DÉCONNECTER"
                    else       -> "CONNECTER"
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
        }
        Button(
            onClick = onRefresh,
            enabled = connected,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CupraCobre,
                contentColor = BornBg,
                disabledContainerColor = CupraCobre.copy(alpha = 0.25f),
                disabledContentColor = BornBg.copy(alpha = 0.55f)
            ),
            contentPadding = PaddingValues(vertical = 10.dp)
        ) {
            Text(
                "RAFRAÎCHIR",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
        }
    }
}
