package com.amazon.connect.touchpoint.voice.telecom

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps a call's audio and microphone while the app is in the background: a foreground service of the
 * phone-call and microphone types, with the system's call notification — who the call is with, a tap
 * back to the app, and Hang up. Started as the call begins, while the app is on screen, as a
 * microphone service must be.
 */
internal class TouchpointCallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_HANG_UP) {
            ActiveCall.hangUp?.invoke()
            return START_NOT_STICKY
        }
        val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        val icon = intent?.getIntExtra(EXTRA_ICON, 0) ?: 0
        try {
            startForeground(NOTIFICATION_ID, notification(name, icon), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: ForegroundServiceStartNotAllowedException) {
            stopSelf() // started from the background after all: the call stays foreground-only
        } catch (_: SecurityException) {
            stopSelf() // a permission the host removed: the same
        }
        return START_NOT_STICKY
    }

    private fun notification(
        name: String,
        icon: Int,
    ): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.touchpoint_telecom_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val hangUp = PendingIntent.getService(this, 0, Intent(this, TouchpointCallService::class.java).setAction(ACTION_HANG_UP), PendingIntent.FLAG_IMMUTABLE)
        val back = packageManager.getLaunchIntentForPackage(packageName)?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(name)
            .setContentText(getString(R.string.touchpoint_telecom_ongoing))
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .apply { back?.let(::setContentIntent) }
            .setStyle(Notification.CallStyle.forOngoingCall(Person.Builder().setName(name).setImportant(true).build(), hangUp))
            .build()
    }

    companion object {
        private const val ACTION_HANG_UP = "com.amazon.connect.touchpoint.voice.telecom.HANG_UP"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_ICON = "icon"
        private const val CHANNEL = "touchpoint_call"
        private const val NOTIFICATION_ID = 0x7043

        fun start(
            context: Context,
            name: String,
            icon: Int,
        ) {
            try {
                context.startForegroundService(Intent(context, TouchpointCallService::class.java).putExtra(EXTRA_NAME, name).putExtra(EXTRA_ICON, icon))
            } catch (_: ForegroundServiceStartNotAllowedException) {
                // in the background already: nothing to keep alive that the app is not
            } catch (_: SecurityException) {
            }
        }
    }
}
