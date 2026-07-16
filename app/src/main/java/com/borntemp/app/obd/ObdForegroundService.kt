package com.borntemp.app.obd

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.borntemp.app.MainActivity
import com.borntemp.app.ObdSessionHolder
import com.borntemp.app.viewmodel.ConnectionState
import com.borntemp.app.viewmodel.ObdSessionController
import com.borntemp.app.viewmodel.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground counterpart to a manual "CONNECTER" tap: started by
 * [ObdBeaconReceiver] when the OBDLink CX is detected, it drives the same
 * [ObdSessionController] singleton the UI uses, so there is only ever one
 * BLE connection to the adapter in this process. Stops itself (and
 * re-arms detection) once the controller reports disconnected, or once
 * [ConnectRetryPolicy] gives up after 5 attempts.
 */
class ObdForegroundService : Service() {

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        private const val CHANNEL_ID = "obd_session"
        private const val FAILURE_CHANNEL_ID = "obd_session_failure"
        private const val NOTIFICATION_ID = 1
        private const val FAILURE_NOTIFICATION_ID = 2
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionJob: Job? = null
    private val retryPolicy = ConnectRetryPolicy()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        if (address == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundWithNotification(buildProgressNotification("Connexion..."))
        sessionJob?.cancel()
        sessionJob = serviceScope.launch { runSession(address) }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun runSession(address: String) {
        val controller = ObdSessionHolder.controllerFor(this)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = bluetoothManager.adapter?.bondedDevices?.firstOrNull { it.address == address }
        if (device == null) {
            finishAndReArm()
            return
        }

        val connected = retryPolicy.run(
            onState = { state ->
                when (state) {
                    is ConnectRetryPolicy.State.Attempting ->
                        updateNotification(buildProgressNotification(
                            "Connexion... (tentative ${state.attempt}/${state.maxAttempts})"
                        ))
                    ConnectRetryPolicy.State.Succeeded -> { /* live notification takes over below */ }
                    ConnectRetryPolicy.State.GaveUp -> postFailureNotification()
                }
            },
            connect = { attemptConnect(controller, device) }
        )

        if (connected) {
            updateNotification(buildLiveNotification(controller.uiState.value))
            val updaterJob = serviceScope.launch {
                controller.uiState.collect { state ->
                    if (state.connectionState == ConnectionState.CONNECTED) {
                        updateNotification(buildLiveNotification(state))
                    }
                }
            }
            controller.uiState.first { it.connectionState != ConnectionState.CONNECTED }
            updaterJob.cancel()
        }
        finishAndReArm()
    }

    private suspend fun attemptConnect(controller: ObdSessionController, device: BluetoothDevice): Boolean {
        controller.connect(device)
        val settled = controller.uiState.first {
            it.connectionState == ConnectionState.CONNECTED || it.connectionState == ConnectionState.ERROR
        }
        return settled.connectionState == ConnectionState.CONNECTED
    }

    private fun finishAndReArm() {
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        ObdBeaconReceiver.armDetection(applicationContext)
        stopSelf()
    }

    private fun buildProgressNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BornTemp")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent())
            .build()

    private fun buildLiveNotification(state: UiState): Notification {
        val temp = state.batteryData.avgTemp
        val soc = state.batteryData.soc
        val text = buildString {
            append(if (temp != null) "%.1f°C".format(temp) else "-- °C")
            if (soc != null) append(" · %.0f%%".format(soc))
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BornTemp — connecté")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent())
            .build()
    }

    private fun postFailureNotification() {
        val notification = NotificationCompat.Builder(this, FAILURE_CHANNEL_ID)
            .setContentTitle("BornTemp")
            .setContentText("Échec de connexion à l'OBDLink CX — vérifiez l'adaptateur")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())
            .build()
        NotificationManagerCompat.from(this).notify(FAILURE_NOTIFICATION_ID, notification)
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun updateNotification(notification: Notification) {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundWithNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Session OBD", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    FAILURE_CHANNEL_ID, "Échecs de connexion OBD", NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionJob?.cancel()
    }
}
