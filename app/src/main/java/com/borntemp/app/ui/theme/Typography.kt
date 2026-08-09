package com.borntemp.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Fixed monospace type scale for the cockpit's "instrument console" aesthetic.
 * Color is deliberately not baked in here — it's semantic (status/accent) and
 * stays a separate param at call sites.
 */
object BornType {
    val eyebrowSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 7.5.sp,
        letterSpacing = 1.5.sp,
        fontWeight = FontWeight.SemiBold
    )
    val eyebrow = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        letterSpacing = 2.sp,
        fontWeight = FontWeight.SemiBold
    )
    val body = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = FontWeight.Normal
    )
    val stat = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold
    )
    val statLg = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 24.sp,
        fontWeight = FontWeight.ExtraBold
    )
    val hero = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 40.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-1.5).sp
    )
    val brand = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 18.sp,
        letterSpacing = 3.sp,
        fontWeight = FontWeight.ExtraBold
    )
}
