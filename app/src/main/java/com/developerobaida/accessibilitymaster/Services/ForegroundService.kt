package com.developerobaida.accessibilitymaster.Services

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.developerobaida.accessibilitymaster.MainActivity
import com.developerobaida.accessibilitymaster.R
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class ForegroundService : Service() {


    companion object {
        const val CHANNEL_ID = "ussd_channel"
        const val CHANNEL_NAME = "USSD Automation"
        const val NOTIF_ID = 1

        const val ACTION_START = "com.developerobaida.simsupport.action.START"
        const val ACTION_STOP = "com.developerobaida.simsupport.action.STOP"
        const val ACTION_RECREATE = "com.developerobaida.simsupport.action.RECREATE"

        // If true: user can swipe to dismiss, and we instantly recreate it.
        // If false: classic non-dismissable ongoing notification.
        private const val RECREATE_ON_DISMISS = true

        fun start(context: Context) {
            val i = Intent(context, ForegroundService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, ForegroundService::class.java).setAction(ACTION_STOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }


        private val RESTART_REQ_CODE = 1001
        @Volatile private var allowRestart = true

        fun disallowRestart(ctx: Context) {
            allowRestart = false
            cancelRestartAlarm(ctx)
        }

        private fun cancelRestartAlarm(ctx: Context) {
            val restartIntent = Intent(ctx.applicationContext, ForegroundService::class.java)
                .setAction(ACTION_START)
                .setPackage(ctx.packageName)
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    ctx, RESTART_REQ_CODE, restartIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    ctx, RESTART_REQ_CODE, restartIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val alarm = ctx.getSystemService(ALARM_SERVICE) as AlarmManager
            alarm.cancel(pi)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null
    private var startEpochMs: Long = 0L

    private lateinit var notificationManager: NotificationManager
    private lateinit var builder: NotificationCompat.Builder
    private lateinit var contentView: RemoteViews
    private var bigContentView: RemoteViews? = null

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            stopSelfSafely()
        }
    }

    private val recreateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (RECREATE_ON_DISMISS) {
                updateRemoteViews(makeElapsedText())
                notifyNow()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
        startEpochMs = System.currentTimeMillis()

        val stopFilter = IntentFilter(ACTION_STOP)
        val recreateFilter = IntentFilter(ACTION_RECREATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, stopFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(recreateReceiver, recreateFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopReceiver, stopFilter)
            registerReceiver(recreateReceiver, recreateFilter)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(recreateReceiver) } catch (_: Exception) {}
        tickerJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                startEpochMs = System.currentTimeMillis()
                setupAndStartForeground(makeElapsedText())
                startTicker()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

//    override fun onTaskRemoved(rootIntent: Intent?) {
//        // bounce back in ~1s like your Java version
//        val restartIntent = Intent(applicationContext, ForegroundService::class.java).apply {
//            action = ACTION_START
//            `package` = packageName
//        }
//        val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
//        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            PendingIntent.getForegroundService(applicationContext, 1001, restartIntent, flags)
//        } else {
//            PendingIntent.getService(applicationContext, 1001, restartIntent, flags)
//        }
//        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
//        alarm.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000L, pi)
//        super.onTaskRemoved(rootIntent)
//    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Only schedule a bounce-back if allowed
        if (allowRestart) {
            val restartIntent = Intent(applicationContext, ForegroundService::class.java).apply {
                action = ACTION_START
                `package` = packageName
            }
            val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(applicationContext, RESTART_REQ_CODE, restartIntent, flags)
            } else {
                PendingIntent.getService(applicationContext, RESTART_REQ_CODE, restartIntent, flags)
            }
            val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
            alarm.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000L, pi)
        }
        super.onTaskRemoved(rootIntent)
    }

    // ---- custom notification ----

    private fun setupAndStartForeground(elapsedText: String) {
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentPI = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopPI = PendingIntent.getBroadcast(
            this, 1, Intent(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val deletePI = PendingIntent.getBroadcast(
            this, 2, Intent(ACTION_RECREATE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )



        builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("USSD Automation running")
            .setContentText("Ongoing AUTOServer $elapsedText")
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(contentPI)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (RECREATE_ON_DISMISS) {
            builder.setOngoing(false).setDeleteIntent(deletePI)
        } else {
            builder.setOngoing(true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        val notif = builder.build().apply {
            if (!RECREATE_ON_DISMISS) {
                flags = flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
            }
        }

        startForeground(NOTIF_ID, notif)
    }

    private fun updateRemoteViews(elapsedText: String) {
//        contentView.setTextViewText(R.id.subtitle, "Ongoing AUTOServer $elapsedText")
//        bigContentView?.setTextViewText(R.id.subtitle, "Ongoing AUTOServer $elapsedText")
    }

    private fun notifyNow() {
        notificationManager.notify(NOTIF_ID, builder.build())
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(1000L)
                updateRemoteViews(makeElapsedText())
                notifyNow()
            }
        }
    }

    private fun makeElapsedText(): String {
        val diff = System.currentTimeMillis() - startEpochMs
        val h = TimeUnit.MILLISECONDS.toHours(diff)
        val m = TimeUnit.MILLISECONDS.toMinutes(diff) - TimeUnit.HOURS.toMinutes(h)
        val s = TimeUnit.MILLISECONDS.toSeconds(diff) -
                TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(diff))
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun stopSelfSafely() {
        tickerJob?.cancel()
        // ensure any pending restart is canceled on explicit stop
        cancelRestartAlarm(this)  // NEW
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification for USSD/SMS automation"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(ch)
        }
    }
}
