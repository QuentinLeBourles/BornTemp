package com.borntemp.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BornGreen     = Color(0xFF00D4A8)   // legacy teal (kept for compat)
val BornGreenDim  = Color(0xFF00A882)
val BornRed       = Color(0xFFFF4D4D)
val BornAmber     = Color(0xFFF59E0B)
val BornBlue      = Color(0xFF60A5FA)
val BornSurface   = Color(0xFF111720)
val BornBg        = Color(0xFF0A0E14)
val BornBorder    = Color(0x12FFFFFF)   // 0.07 alpha — softer than the legacy 0x14
val BornMuted     = Color(0xFF6B7A94)
val BornText      = Color(0xFFE8EDF5)

// ── Cockpit rework tokens (2026-06-21 design handoff) ─────────────────────
// Cupra's identity is Petrol Blue + Cobre/Copper — no green in the brand.
// The teal here is a brightened OK accent in the petrol family, used for
// status dots & verdicts; PetrolSurface anchors the passive info cards.
val CupraCobre    = Color(0xFFB26F47)   // primary identity accent
val CupraSheen    = Color(0xFFD9956C)   // lighter cobre — text on cobre, glows, peak values
val TealOk        = Color(0xFF16C39A)   // STABLE / OK / FIABLE / "TRÈS RAPIDE"
val AuroraBlue    = Color(0x8C4C8DD6)   // 0.55 alpha — cold / buffer-low
val AmberHi       = Color(0xFFEFA02C)   // warning / buffer-high / "CORRECT"
val RedHi         = Color(0xFFE23B3B)   // "LENT" verdict / hard error
val PetrolSurface = Color(0xFF13232A)   // passive card background (dual-tone)
val PetrolBorder  = Color(0x734F6770)   // 0.45 alpha — petrol card border
val BornTextDim   = Color(0xFFA8B4C8)   // secondary body text
val CobreBorderSoft = Color(0x47B26F47) // 0.28 alpha — borders on cobre-tinted tiles
val CobreBorderHi   = Color(0x59B26F47) // 0.35 alpha — borders on result card / hi-emphasis

private val DarkColors = darkColorScheme(
    primary          = BornGreen,
    onPrimary        = Color(0xFF003028),
    secondary        = BornGreenDim,
    onSecondary      = Color.White,
    background       = BornBg,
    surface          = BornSurface,
    onBackground     = BornText,
    onSurface        = BornText,
    error            = BornRed,
    onError          = Color.White,
    outline          = BornBorder,
    surfaceVariant   = Color(0xFF1A2235),
    onSurfaceVariant = BornMuted,
)

@Composable
fun BornTempTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
