package com.borntemp.app.abrp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Wraps the system [LocationManager] for periodic position + speed snapshots.
 * No play-services / FusedLocationProvider dependency.
 *
 * Permission: requires ACCESS_FINE_LOCATION at runtime. If not granted, [start]
 * is a no-op and [snapshot] returns null until permission is granted and a fix
 * is acquired.
 */
class LocationProvider(private val context: Context) : LocationListener {

    companion object {
        private const val MIN_INTERVAL_MS = 3_000L
        private const val MIN_DISTANCE_M = 5f
    }

    private val manager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    @Volatile
    private var last: Location? = null

    @Volatile
    private var running = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !hasPermission()) return
        val mgr = manager ?: return
        try {
            // Seed with last known if available — saves waiting for first fix.
            mgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { last = it }
            mgr.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_INTERVAL_MS,
                MIN_DISTANCE_M,
                this,
                Looper.getMainLooper()
            )
            running = true
        } catch (_: SecurityException) {
            // Permission yanked between check and call.
        } catch (_: IllegalArgumentException) {
            // GPS provider not available on this device.
        }
    }

    fun stop() {
        if (!running) return
        manager?.removeUpdates(this)
        running = false
    }

    fun snapshot(): LocationSnapshot? {
        val l = last ?: return null
        val speed = if (l.hasSpeed()) l.speed * 3.6f else null  // m/s → km/h
        return LocationSnapshot(l.latitude, l.longitude, speed)
    }

    override fun onLocationChanged(location: Location) {
        last = location
    }
}
