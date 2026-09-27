package com.borntemp.app.viewmodel

import android.content.Context
import android.content.SharedPreferences
import com.borntemp.app.domain.PollIntervals

/**
 * Persisted acquisition knobs: the charge-time poll interval (the driving one
 * stays in UiState.pollingIntervalMs, as before).
 */
class AcquisitionSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("acquisition_settings", Context.MODE_PRIVATE)

    var chargingPollMs: Long
        get() = prefs.getLong("charging_poll_ms", PollIntervals.CHARGING_RANGE.first)
        set(v) { prefs.edit().putLong("charging_poll_ms", v).apply() }
}

/** One settings change from the Réglages tab, routed through a single callback. */
sealed interface AcquisitionSetting {
    data class ChargingPollInterval(val ms: Long) : AcquisitionSetting
}
