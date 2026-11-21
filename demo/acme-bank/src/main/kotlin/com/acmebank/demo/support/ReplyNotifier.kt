package com.acmebank.demo.support

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.acmebank.demo.MainActivity
import com.acmebank.demo.R
import com.amazon.connect.touchpoint.core.IncomingMessages

/**
 * What the bank does with a reply while the customer is away: the SDK reports each new
 * message (`TouchpointSession.onIncomingMessages`) and posts nothing; the bank posts a
 * generic local notification — never the message's words — when the app is in the background and
 * notifications are allowed. A tap opens support.
 */
internal class ReplyNotifier(private val context: Context) {
    fun onIncoming(incoming: IncomingMessages) {
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return // on screen: the chat shows it
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.support_reply_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open =
            PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_OPEN_SUPPORT, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            android.app.Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(context.getString(R.string.support_reply_title, com.acmebank.demo.PartnerBrand.current.bankName))
                .setContentText(context.getString(R.string.support_reply_body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        manager.notify(NOTIFICATION, notification)
    }

    private companion object {
        const val CHANNEL = "support-replies"
        const val NOTIFICATION = 7
    }
}
