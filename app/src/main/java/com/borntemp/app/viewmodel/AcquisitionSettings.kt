package com.borntemp.app.viewmodel

import android.content.Context
import android.content.SharedPreferences
import com.borntemp.app.domain.PollIntervals
import com.borntemp.app.domain.SignalCandidates

/**
 * Persisted acquisition knobs: the charge-time poll interval (the driving one
 * stays in UiState.pollingIntervalMs, as before) and which signal-identification
 * candidates run.
 */
class AcquisitionSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("acquisition_settings", Context.MODE_PRIVATE)

    var chargingPollMs: Long
        get() = prefs.getLong("charging_poll_ms", PollIntervals.CHARGING_RANGE.first)
        set(v) { prefs.edit().putLong("charging_poll_ms", v).apply() }

    /** Enabled candidate ids; catalogue defaults until the user touches one.
     *  Ids no longer in the catalogue are dropped on read. */
    var enabledCandidates: Set<String>
        get() = (prefs.getStringSet("enabled_candidates", null) ?: SignalCandidates.defaultEnabled())
            .filter { SignalCandidates.byId(it) != null }.toSet()
        set(v) { prefs.edit().putStringSet("enabled_candidates", v.toSet()).apply() }
}

/** One settings change from the Réglages tab, routed through a single callback. */
sealed interface AcquisitionSetting {
    data class ChargingPollInterval(val ms: Long) : AcquisitionSetting
    data class Candidate(val id: String, val enabled: Boolean) : AcquisitionSetting
}
