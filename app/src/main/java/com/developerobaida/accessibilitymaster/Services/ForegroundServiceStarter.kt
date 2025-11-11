package com.developerobaida.accessibilitymaster.Services

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object ForegroundServiceStarter {
    private const val TAG = "FGServiceStarter"

    fun start(context: Context) {
        val appCtx = context.applicationContext
        val intent = Intent(appCtx, ForegroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(intent)
            } else {
                appCtx.startService(intent)
            }
            Log.d(TAG, "ForegroundService start requested")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start ForegroundService: ${t.message}", t)
        }

    }

    /** Graceful stop: calls into the Service’s ACTION_STOP path */
    fun stop(context: Context) {
        val appCtx = context.applicationContext
        val intent = Intent(appCtx, ForegroundService::class.java)
            .setAction(ForegroundService.ACTION_STOP)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(intent)
            } else {
                appCtx.startService(intent)
            }
            Log.d(TAG, "ForegroundService stop requested")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to request stop: ${t.message}", t)
            // Fallback: try a direct stopService if ACTION_STOP path couldn't be delivered
            try { appCtx.stopService(Intent(appCtx, ForegroundService::class.java)) } catch (_: Throwable) {}
        }
    }

    /** Hard stop: also prevents auto-restart after task removal */
    fun hardStop(context: Context) {
        val appCtx = context.applicationContext
        ForegroundService.disallowRestart(appCtx)  // set a flag + cancel any restart alarm
        stop(appCtx)
    }
}