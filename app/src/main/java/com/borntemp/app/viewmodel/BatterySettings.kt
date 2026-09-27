package com.borntemp.app.viewmodel

import android.content.Context
import android.content.SharedPreferences

/**
 * User overrides that the SOH calculator must consult before falling back
 * to its auto-detection heuristics. Persisted across launches because the
 * pack identity is a per-vehicle constant, not a per-session value.
 */
class BatterySettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("battery_settings", Context.MODE_PRIVATE)

    /** AUTO leaves the [guessPackType] heuristic in charge; LG/SK forces
     *  the reference capacity used as the SOH denominator. */
    var packTypeOverride: PackTypeOverride
        get() = PackTypeOverride.fromName(prefs.getString("pack_type_override", null))
        set(v) { prefs.edit().putString("pack_type_override", v.name).apply() }

    /** Last capacity measured over a charge, kept so SOH survives a restart:
     *  a qualifying charge is rare, and without this every reconnect showed
     *  "Indisponible" until the next one. Null until the first measurement. */
    var measuredCapacityKwh: Float?
        get() = prefs.getFloat("measured_capacity_kwh", Float.NaN).takeUnless { it.isNaN() }
        set(v) { prefs.edit().putFloat("measured_capacity_kwh", v ?: Float.NaN).apply() }
}

enum class PackTypeOverride(val label: String, val packType: PackType?) {
    AUTO("Auto",       null),
    LG_2021_22("LG 77 kWh", PackType.LG_2021_22),
    SK_2023("SK 79.9 kWh", PackType.SK_2023);

    companion object {
        fun fromName(name: String?): PackTypeOverride = entries.firstOrNull { it.name == name } ?: AUTO
    }
}
