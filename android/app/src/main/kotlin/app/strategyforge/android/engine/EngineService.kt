package app.strategyforge.android.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.strategyforge.android.MainActivity
import app.strategyforge.android.R
import app.strategyforge.android.platform.LocalConfig
import app.strategyforge.engine.EngineScheduler
import app.strategyforge.engine.api.EngineRuntime
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the on-device engine running while the app is closed (D-027): market data, strategy
 * evaluation, recommendations and autonomous paper trading continue with the screen off. It shows
 * a persistent notification, as Android requires, and optionally holds a partial wake lock so the
 * processor does not sleep between ticks.
 */
@AndroidEntryPoint
class EngineService : Service() {
    @Inject lateinit var runtime: EngineRuntime

    @Inject lateinit var config: LocalConfig

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP || !config.runInBackground) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Starting…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        updateWakeLock()
        if (!started) {
            started = true
            scope.launch { loop() }
        }
        return START_STICKY
    }

    private suspend fun loop() {
        var lastText = ""
        while (scope.isActive) {
            val report = runtime.tick()
            val text =
                when {
                    report == null -> "Engine error; retrying"
                    report.mode.name == "DEMO" -> "Demo mode · market time ${report.marketTime.toString().take(16).replace('T', ' ')} UTC"
                    else -> "Live paper trading · crypto data from Coinbase"
                }
            if (text != lastText) {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
                lastText = text
            }
            delay(EngineScheduler.TICK_INTERVAL.toMillis())
        }
    }

    private fun updateWakeLock() {
        if (config.keepAwake && wakeLock == null) {
            wakeLock =
                getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StrategyForge:engine")
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
        } else if (!config.keepAwake) {
            wakeLock?.release()
            wakeLock = null
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("StrategyForge is running")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "engine_status"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "app.strategyforge.android.STOP_ENGINE"

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Background engine", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while StrategyForge keeps paper trading in the background"
                    setShowBadge(false)
                },
            )
        }

        /** Starts (or refreshes the settings of) the background engine; stops it when disabled. */
        fun start(context: Context) {
            val config = LocalConfig(context)
            if (!config.runInBackground) {
                stop(context)
                return
            }
            runCatching { ContextCompat.startForegroundService(context, Intent(context, EngineService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EngineService::class.java))
        }
    }
}

/** Restarts the background engine after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            EngineService.start(context)
        }
    }
}
