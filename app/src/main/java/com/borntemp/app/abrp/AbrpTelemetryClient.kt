package com.borntemp.app.abrp

import com.borntemp.app.viewmodel.BatteryData
import com.borntemp.app.viewmodel.ChargeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Posts a telemetry frame to the ABRP / Iternio Generic Telemetry endpoint.
 *
 * Endpoint  : https://api.iternio.com/1/tlm/send
 * Auth      : Authorization: APIKEY <api_key>
 * Body      : application/x-www-form-urlencoded — token=<user_token>&tlm=<json>
 *
 * The "tlm" payload is a JSON object with at minimum {utc, soc}. ABRP's
 * documented richer fields (power/voltage/current/batt_temp/lat/lon/speed/...)
 * are added when available.
 */
class AbrpTelemetryClient {

    companion object {
        private const val ENDPOINT = "https://api.iternio.com/1/tlm/send"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 8_000
    }

    data class Result(val success: Boolean, val message: String)

    suspend fun send(
        apiKey: String,
        userToken: String,
        data: BatteryData,
        location: LocationSnapshot? = null
    ): Result = withContext(Dispatchers.IO) {
        val tlm = buildTelemetryJson(data, location).toString()
        val body = "token=" + URLEncoder.encode(userToken, "UTF-8") +
                   "&tlm=" + URLEncoder.encode(tlm, "UTF-8")

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Authorization", "APIKEY $apiKey")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
                ?: ""
            if (code in 200..299) Result(true, "HTTP $code")
            else Result(false, "HTTP $code: ${text.take(160)}")
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    private fun buildTelemetryJson(data: BatteryData, loc: LocationSnapshot?): JSONObject {
        val json = JSONObject()
        json.put("utc", System.currentTimeMillis() / 1000L)
        data.soc?.let { json.put("soc", it.toDouble()) }
        data.powerKw?.let { json.put("power", it.toDouble()) }
        data.voltage?.let { json.put("voltage", it.toDouble()) }
        data.current?.let { json.put("current", it.toDouble()) }
        data.avgTemp?.let { json.put("batt_temp", it.toDouble()) }
        val isCharging = data.chargeState == ChargeState.AC_CHARGING ||
                         data.chargeState == ChargeState.DC_CHARGING
        json.put("is_charging", if (isCharging) 1 else 0)
        json.put("is_dcfc", if (data.chargeState == ChargeState.DC_CHARGING) 1 else 0)
        val speedKph = loc?.speedKph ?: 0f
        json.put("is_parked", if (!isCharging && speedKph < 1f) 1 else 0)
        loc?.let {
            json.put("lat", it.lat)
            json.put("lon", it.lon)
            it.speedKph?.let { s -> json.put("speed", s.toDouble()) }
        }
        return json
    }
}

data class LocationSnapshot(
    val lat: Double,
    val lon: Double,
    val speedKph: Float? = null
)
