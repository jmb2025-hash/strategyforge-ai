package app.strategyforge.android.platform

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.strategyforge.android.MainActivity
import app.strategyforge.android.R
import app.strategyforge.android.core.notify.DeepLinks
import app.strategyforge.android.core.notify.NotificationPresenter
import app.strategyforge.android.core.notify.PushPayload
import app.strategyforge.engine.notifications.NotificationCategory
import app.strategyforge.engine.notifications.NotificationView

/**
 * Local notifications raised by the on-device engine (D-027 replaces push). The lock-screen
 * version is always generic (FR-102); tapping opens a screen behind the app lock and never acts.
 */
object Notifier {
    fun show(
        context: Context,
        n: NotificationView,
        redactUnlocked: Boolean,
    ) {
        val channel = runCatching { NotificationCategory.valueOf(n.category).channel }.getOrDefault("risk_safety")
        show(context, PushPayload(n.id.toString(), channel, n.title, n.body, n.deepLink ?: "strategyforge://notifications/${n.id}"), redactUnlocked)
    }

    fun show(
        context: Context,
        payload: PushPayload,
        redactUnlocked: Boolean,
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val shown = NotificationPresenter.present(payload, redactUnlocked)
        val intent =
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = shown.route?.let { Uri.parse(DeepLinks.toUri(it)) }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val pending = PendingIntent.getActivity(context, (payload.notificationId ?: "x").hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val public =
            NotificationCompat
                .Builder(context, shown.channel.id)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(shown.publicTitle)
                .setContentText(shown.publicBody)
                .build()
        val notification =
            NotificationCompat
                .Builder(context, shown.channel.id)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(shown.privateTitle)
                .setContentText(shown.privateBody)
                .setStyle(NotificationCompat.BigTextStyle().bigText(shown.privateBody))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
        NotificationManagerCompat.from(context).notify((payload.notificationId ?: payload.title ?: "n").hashCode(), notification)
    }
}
