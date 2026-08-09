package com.borntemp.app.ui.theme

import androidx.compose.ui.unit.dp

/** Fixed spacing scale — every padding/gap in the cockpit should round to one of these. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

/** Fixed corner-radius scale, paired with [Spacing]. */
object Radius {
    val sm = 10.dp   // buttons, small chips
    val md = 14.dp   // collapsible cards
    val lg = 16.dp   // hero / health / planner cards
    val pill = 20.dp // BleChip
}
