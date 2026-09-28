package app.strategyforge.android.push

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.strategyforge.android.MainActivity
import app.strategyforge.android.R
import app.strategyforge.android.core.api.ApiError
import app.strategyforge.android.core.cache.Resource
import app.strategyforge.android.core.data.Repository
import app.strategyforge.android.core.notify.DeepLinks
import app.strategyforge.android.core.notify.NotificationPresenter
import app.strategyforge.android.core.notify.PushPayload
import app.strategyforge.android.platform.LocalConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.resume

/** Shows a notification whose lock-screen version is always generic (FR-102). */
object Notifier {
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
        val n =
            NotificationCompat
                .Builder(context, shown.channel.id)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(shown.privateTitle)
                .setContentText(shown.privateBody)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
        NotificationManagerCompat.from(context).notify((payload.notificationId ?: payload.title ?: "n").hashCode(), n)
    }
}

/**
 * Optional FCM channel (FR-101). The payload contains only ids and already-redacted text; tapping it
 * opens an authenticated screen. It can never execute an action.
 */
@AndroidEntryPoint
class PushService : FirebaseMessagingService() {
    @Inject
    lateinit var repo: Repository

    @Inject
    lateinit var config: LocalConfig

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        Notifier.show(this, PushPayload(d["notificationId"], d["channel"], d["title"], d["body"], d["deepLink"]), config.redactUnlocked)
    }

    override fun onNewToken(token: String) {
        val device = config.deviceId ?: return
        scope.launch { runCatching { repo.updatePushToken(device, token) } }
    }
}

/** Registers this device and, when the backend has push configured, an FCM token. */
object PushSetup {
    suspend fun register(
        context: Context,
        repo: Repository,
        config: LocalConfig,
    ) {
        val deviceId =
            config.deviceId ?: repo
                .registerDevice(
                    android.os.Build.MODEL
                        .take(60),
                ).id
                .also { config.deviceId = it }
        val push = runCatching { repo.pushConfig() }.getOrNull() ?: return
        if (!push.enabled || push.applicationId == null || push.apiKey == null || push.projectId == null || push.senderId == null) {
            repo.updatePushToken(deviceId, null)
            return
        }
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions
                    .Builder()
                    .setApplicationId(push.applicationId!!)
                    .setApiKey(push.apiKey!!)
                    .setProjectId(push.projectId!!)
                    .setGcmSenderId(push.senderId!!)
                    .build(),
            )
        }
        val token =
            suspendCancellableCoroutine { cont ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { t -> cont.resume(if (t.isSuccessful) t.result else null) }
            }
        repo.updatePushToken(deviceId, token)
    }
}

/**
 * Periodic background sync: refreshes the cached dashboard and inbox, and shows a local notification
 * for new critical inbox items, so safety events are surfaced even when push is unavailable (MS-16).
 */
@HiltWorker
class SyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val repo: Repository,
        private val config: LocalConfig,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (!repo.signedIn() || config.serverUrl == null) return Result.success()
            repo.dashboard().last()
            val inbox = repo.notifications().last()
            if (inbox is Resource.Data) {
                val unread = inbox.value.items.filter { it.readAt == null }
                val newest = unread.firstOrNull()
                if (newest != null && newest.id != config.lastNotifiedId && newest.pushStatus != "QUEUED" && newest.pushStatus != "SENT") {
                    unread.filter { it.critical }.take(MAX_LOCAL).forEach {
                        Notifier.show(applicationContext, PushPayload(it.id, channelFor(it.category), it.redactedTitle, it.redactedBody, it.deepLink), config.redactUnlocked)
                    }
                    config.lastNotifiedId = newest.id
                }
            }
            return when ((inbox as? Resource.Data)?.error ?: (inbox as? Resource.Failure)?.error) {
                is ApiError.Offline -> Result.retry()
                else -> Result.success()
            }
        }

        private fun channelFor(category: String) =
            when (category) {
                "RECOMMENDATION", "RECOMMENDATION_EXPIRY" -> "recommendation_action"
                "ORDER_FILL", "ORDER_REJECTION", "STOP_TARGET" -> "order_execution"
                "STRATEGY_HEALTH" -> "strategy_health"
                "STALE_DATA", "MARKET_DATA_HEALTH", "PRICE_ALERT" -> "market_data_health"
                "SECURITY" -> "security"
                "DAILY_SUMMARY" -> "daily_summary"
                else -> "risk_safety"
            }

        companion object {
            private const val MAX_LOCAL = 3

            fun schedule(context: Context) {
                val request =
                    PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }
    }
