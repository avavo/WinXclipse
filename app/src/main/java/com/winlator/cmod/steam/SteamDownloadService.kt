package com.winlator.cmod.steam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.winlator.cmod.R
import com.winlator.cmod.gog.GogDownloads
import com.winlator.cmod.gog.GogSession
import com.winlator.cmod.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps Steam downloads alive while WinXclipse is in the background: a foreground
 * notification with the progress, plus CPU and Wi-Fi locks. Stops itself when no download
 * is running.
 */
class SteamDownloadService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification(getString(R.string.steam_dl_preparing), null))
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WinXclipse:SteamDownload")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "WinXclipse:SteamDownload")
            .apply { acquire() }
        scope.launch { watch() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    /** Mirrors the downloads in the notification until none is running. */
    private suspend fun watch() {
        // Give a freshly started download a moment to register before checking.
        delay(2000)
        val manager = getSystemService(NotificationManager::class.java)
        while (SteamDownloads.hasActiveDownloads() || GogDownloads.hasActiveDownloads()) {
            // The first running download of any store: (name, state).
            val active: Pair<String, SteamInstall>? =
                SteamDownloads.installs.value.entries.firstOrNull { SteamDownloads.isActive(it.key) }?.let { entry ->
                    (SteamSession.library.value.firstOrNull { it.appId == entry.key }?.name ?: "Steam") to entry.value
                } ?: GogDownloads.installs.value.entries.firstOrNull { GogDownloads.isActive(it.key) }?.let { entry ->
                    (GogSession.library.value.firstOrNull { it.id == entry.key }?.title ?: "GOG") to entry.value
                }
            if (active != null) {
                val name = active.first
                val install = active.second
                val text = if (install is SteamInstall.Downloading) {
                    getString(
                        R.string.steam_dl_progress,
                        SteamDownloads.formatBytes(install.downloadedBytes),
                        SteamDownloads.formatBytes(install.totalBytes),
                        SteamDownloads.formatBytes(install.bytesPerSecond),
                    )
                } else {
                    getString(R.string.steam_dl_preparing)
                }
                val progress = (install as? SteamInstall.Downloading)?.progress
                manager.notify(NOTIFICATION_ID, notification(getString(R.string.steam_dl_notification, name), text, progress))
            }
            delay(1000)
        }
        stopSelf()
    }

    private fun notification(title: String, text: String?, progress: Float? = null): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(1000, ((progress ?: 0f) * 1000).toInt(), progress == null)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.steam_dl_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL_ID = "steam_downloads"
        const val NOTIFICATION_ID = 4107
    }
}
