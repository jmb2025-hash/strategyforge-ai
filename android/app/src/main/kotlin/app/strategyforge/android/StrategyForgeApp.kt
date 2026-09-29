package app.strategyforge.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.strategyforge.android.core.notify.Channel
import app.strategyforge.android.engine.EngineService
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class StrategyForgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    /** One channel per notification category (section 14). Critical channels use high importance. */
    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        Channel.entries.forEach { c ->
            val importance = if (c.critical) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
            nm.createNotificationChannel(
                NotificationChannel(c.id, c.label, importance).apply {
                    // Lock-screen content is redacted by the notification's public version (FR-102).
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                },
            )
        }
        EngineService.createChannel(this)
    }
}
