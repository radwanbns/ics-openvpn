package com.xrayovpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps the relay alive in the background (foreground service) while the VPN is up. */
class XrayService : Service() {
    private var relay: XrayRelay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        relay?.stop()
        val r = XrayRelay(
            Mode.valueOf(intent.getStringExtra("mode") ?: "TCP"),
            intent.getIntExtra("localPort", 11940),
            intent.getStringExtra("server") ?: "",
            intent.getIntExtra("port", 1194),
            intent.getStringExtra("sni") ?: "",
            intent.getStringExtra("host") ?: "",
            intent.getStringExtra("ua") ?: "",
            intent.getStringExtra("payload") ?: "",
        )
        r.start()
        relay = r
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        relay?.stop()
        relay = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("xrayovpn", "Xray OVPN", NotificationManager.IMPORTANCE_LOW)
            )
            Notification.Builder(this, "xrayovpn")
        } else {
            Notification.Builder(this)
        }
        val n = builder
            .setContentTitle("Xray OVPN")
            .setContentText("Tunnel relay running")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }
}
