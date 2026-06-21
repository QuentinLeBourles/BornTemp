package com.borntemp.app.abrp

import android.content.Context
import android.content.SharedPreferences

class AbrpSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("abrp_settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) { prefs.edit().putString("api_key", v).apply() }

    var userToken: String
        get() = prefs.getString("user_token", "") ?: ""
        set(v) { prefs.edit().putString("user_token", v).apply() }

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) { prefs.edit().putBoolean("enabled", v).apply() }
}
