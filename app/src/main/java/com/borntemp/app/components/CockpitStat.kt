package com.borntemp.app.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borntemp.app.ui.theme.BornMuted
import com.borntemp.app.ui.theme.BornText

/** Small label+value stat block, reused by the Cells and Lifetime collapsibles
 *  (Live and Santé tabs respectively). */
@Composable
fun CellStat(label: String, value: String) {
    Column {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 7.5.sp,
            letterSpacing = 1.5.sp,
            color = BornMuted
        )
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = BornText
        )
    }
}
