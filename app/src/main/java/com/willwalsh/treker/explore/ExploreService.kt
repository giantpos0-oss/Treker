package com.willwalsh.treker.explore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.willwalsh.treker.MainActivity
import com.willwalsh.treker.TrekerApp
import com.willwalsh.treker.location.ShakeDetector
import kotlinx.coroutines.launch

/**
 * Keeps GPS and live exploration running when the screen is off or you're in another app.
 * Android requires a notification while it runs.
 */
class ExploreService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        val app = application as TrekerApp
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live exploration", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ExploreService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 2, Intent(this, ExploreService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Treker is exploring")
            .setContentText("Live exploration is announcing where you are.")
            .setContentIntent(open)
            .addAction(0, "Pause or resume", pause)
            .addAction(0, "Stop Treker", stop)
            .setOngoing(true)
            .build()
        runCatching {
            ServiceCompat.startForeground(this, 7, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        }.onFailure { stopSelf(); return }
        app.location.start()
        lifecycleScope.launch { app.location.fix.collect { f -> if (f != null) app.explorer.onFix(f) } }
        // Shake the phone to hear where you are, even with the screen off or in another app.
        shake = ShakeDetector(this, { app.settings.shakeStrength.g }) {
            if (app.settings.shakeWhereAmI) {
                if (app.settings.vibrate) app.speaker.tick(60)
                app.speaker.say(app.explorer.whereAmI(), interrupt = true)
            }
        }.also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val app = application as TrekerApp
        when (intent?.action) {
            ACTION_PAUSE -> { app.togglePause(); return START_STICKY }
        }
        if (intent?.action == ACTION_STOP) {
            app.whatsAround.stop()
            app.guidance.stop(silent = true)
            app.speaker.stop()
            app.location.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private var shake: ShakeDetector? = null

    override fun onDestroy() {
        shake?.stop()
        (application as TrekerApp).location.stop()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "explore"
        private const val ACTION_STOP = "stop"
        private const val ACTION_PAUSE = "pause"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ExploreService::class.java)) }
        }
    }
}
