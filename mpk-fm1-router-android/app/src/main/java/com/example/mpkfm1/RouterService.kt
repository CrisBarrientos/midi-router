package com.example.mpkfm1

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class RouterService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val b = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("router", "Enrutador MIDI", NotificationManager.IMPORTANCE_LOW)
            )
            Notification.Builder(this, "router")
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        b.setContentTitle("Enrutador MIDI activo")
            .setContentText("MPK Mini -> FM-1")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
        startForeground(1, b.build())
        Router.start(applicationContext, i?.getIntExtra("src", -1) ?: -1, i?.getIntExtra("dst", -1) ?: -1)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Router.stop()
        super.onDestroy()
    }
}
