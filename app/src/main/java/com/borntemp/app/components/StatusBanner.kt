package com.borntemp.app.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borntemp.app.ui.theme.Radius
import com.borntemp.app.ui.theme.Spacing

/**
 * Shared tinted status banner — used for the error banner and the
 * auto-connect status message, so both read as the same visual language
 * instead of one being a hand-drawn one-off.
 */
@Composable
fun StatusBanner(
    message: String,
    tint: Color,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(Radius.sm)
    val border = BorderStroke(0.5.dp, tint.copy(alpha = 0.3f))
    val surfaceModifier = modifier.fillMaxWidth()
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                message,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = tint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (onClick != null) {
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    "VOIR →",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = tint
                )
            }
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            color = tint.copy(alpha = 0.1f),
            shape = shape,
            border = border,
            modifier = surfaceModifier,
            content = content
        )
    } else {
        Surface(
            color = tint.copy(alpha = 0.1f),
            shape = shape,
            border = border,
            modifier = surfaceModifier,
            content = content
        )
    }
}
