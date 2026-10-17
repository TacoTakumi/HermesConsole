package com.hermesconsole.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * The always-on anchor (REQ-25). A wall-mounted console must survive everything short of a power
 * cut, and on Android the contract for "this process matters, leave it alone" is a foreground
 * service of type microphone - the type is what licenses capture to continue when the screen or
 * the Activity ever lapses, and what dumpsys shows the integrator after a cold reboot.
 *
 * Deliberately empty of logic: the voice loop and the capture path live in the Activity's world
 * (see MainActivity), and this service owns none of it. It exists to hold the process's foreground
 * standing, nothing more - so it starts, plants its notification, and goes quiet.
 *
 * It is started from the visible Activity (the Android 14+ compliant path for a mic-typed FGS),
 * and only once RECORD_AUDIO is granted - startForeground with FOREGROUND_SERVICE_TYPE_MICROPHONE
 * throws without that grant. POST_NOTIFICATIONS is *not* required: denied, the notification simply
 * stays out of the shade while the service runs on regardless.
 */
class MicForegroundService : Service() {

    /**
     * Screen-off survival (REQ-26). The FGS keeps the *process* alive; this keeps the *CPU*
     * alive. Once the display sleeps the SM-X210 heads for doze, and a suspended CPU stops the
     * wake-word engine mid-listen - the console goes deaf exactly when the room goes dark. A
     * partial wake lock held for the anchor's whole life is the sanctioned answer, and it is
     * honoured through doze only because the console is battery-exempt (Unrestricted battery,
     * docs/device-setup.md) - the OS ignores a non-exempt app's wake locks in deep doze.
     *
     * Held indefinitely on purpose: a wall console has no "done listening". The Play-policy
     * penalty for long screen-off wake locks is a store-visibility rule, not a platform one,
     * and this app is sideloaded.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        // IMPORTANCE_LOW: the anchor announces nothing. No sound, no heads-up - a wall console
        // that chimes at itself on every boot is a broken appliance.
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_console)
                .setContentTitle(getString(R.string.app_name))
                .setOngoing(true)
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply {
                    // Unreference-counted so a re-delivered start intent can never leave a
                    // phantom count behind: one acquire, one release, however many starts.
                    setReferenceCounted(false)
                    acquire()
                }
        }
        // Hands-off reachability (T-48): wireless debugging resets at every boot, and a wall
        // tablet with no hand on it stays unreachable. With WRITE_SECURE_SETTINGS granted over
        // adb the anchor re-asserts it each start; ungranted, this must stay a logged no-op -
        // the anchor never dies for a debugging convenience.
        try {
            Settings.Global.putInt(contentResolver, "adb_wifi_enabled", 1)
            Log.i(TAG, "wireless debugging re-asserted")
        } catch (e: SecurityException) {
            Log.i(TAG, "wireless debugging left as-is (WRITE_SECURE_SETTINGS not granted)")
        }
        // STICKY: if the system ever does reclaim the process, ask to be stood back up. The HOME
        // relaunch would restart the anchor anyway; this covers the gap before anyone looks.
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val TAG = "MicForegroundService"
        const val CHANNEL_ID = "console_anchor"
        const val NOTIFICATION_ID = 1

        /** The name `dumpsys power` shows for the held lock - the soak reads it by this. */
        const val WAKE_LOCK_TAG = "HermesConsole:anchor"
    }
}
