package com.colabmcp.bridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.colabmcp.core.util.Log
import kotlin.concurrent.thread

/**
 * Foreground service hosting the MCP bridge. Survival measures: foreground notification,
 * partial wake lock + Wi-Fi lock, START_STICKY, restart alarm when the task is swiped away,
 * a 15-minute watchdog alarm, reconnect on network changes, and restart on boot.
 */
class BridgeService : Service() {
    companion object {
        const val ACTION_START = "com.colabmcp.bridge.START"
        const val ACTION_STOP = "com.colabmcp.bridge.STOP"
        const val ACTION_RECONNECT = "com.colabmcp.bridge.RECONNECT"
        private const val TAG = "BridgeService"

        @Volatile var isRunning = false
            private set

        fun start(ctx: Context) {
            Graph.settings.desiredRunning = true
            ContextCompat.startForegroundService(ctx, Intent(ctx, BridgeService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            Graph.settings.desiredRunning = false
            ctx.startService(Intent(ctx, BridgeService::class.java).setAction(ACTION_STOP))
        }

        private fun pending(ctx: Context, code: Int, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, code,
                Intent(ctx, WatchdogReceiver::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        fun scheduleWatchdog(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                pending(ctx, 10, WatchdogReceiver.ACTION_WATCHDOG),
            )
        }

        fun cancelWatchdog(ctx: Context) {
            ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, 10, WatchdogReceiver.ACTION_WATCHDOG))
        }

        /** One-shot restart shortly after the process/task was killed. */
        fun scheduleRestart(ctx: Context, delayMs: Long = 3000) {
            ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pending(ctx, 11, WatchdogReceiver.ACTION_RESTART),
            )
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastNetwork: Network? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        goForeground()
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifications.ID_SERVICE, Notifications.service(this, Graph.status.value, Graph.pin.value), type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Graph.settings.desiredRunning = false
                stopping = true
                shutdown()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> Graph.bridge?.reconnectTunnel()
            else -> {
                // ACTION_START, or a null intent when the system restarts us (START_STICKY).
                Graph.settings.desiredRunning = true
                goForeground()
                if (Graph.bridge == null) startBridge()
            }
        }
        return START_STICKY
    }

    private fun startBridge() {
        isRunning = true
        acquireLocks()
        registerNetworkCallback()
        scheduleWatchdog(this)
        thread(name = "bridge-start") {
            try {
                if (!Graph.auth.isSignedIn) Log.w(TAG, "Not signed in to Google yet: Colab tools will fail until you sign in.")
                val b = Graph.createBridge()
                val onOauthChange = b.oauth.onChange
                b.oauth.onChange = {
                    onOauthChange?.invoke()
                    main.post { if (!stopping) Notifications.update(this, Graph.status.value, Graph.pin.value) }
                }
                b.listener = { st ->
                    Graph.status.value = st
                    main.post { if (!stopping) Notifications.update(this, st, Graph.pin.value) }
                }
                Graph.bridge = b
                b.start()
                Graph.refreshAccount()
            } catch (e: Exception) {
                Log.e(TAG, "Could not start the MCP server: ${e.message}", e)
                Graph.bridge = null
                Graph.status.value = null
                main.post {
                    Graph.settings.desiredRunning = false
                    stopping = true
                    shutdown()
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ColabMcpBridge::server")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        if (wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(mode, "ColabMcpBridge::wifi")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = lastNetwork
                lastNetwork = network
                // The default network changed (e.g. Wi-Fi <-> mobile): the tunnel socket is dead.
                if (previous != null && previous != network) {
                    Log.i(TAG, "Network changed; reconnecting tunnel")
                    main.postDelayed({ Graph.bridge?.reconnectTunnel() }, 1500)
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {}
        }
        cm.registerDefaultNetworkCallback(cb)
        networkCallback = cb
    }

    private fun shutdown() {
        isRunning = false
        cancelWatchdog(this)
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
        val b = Graph.bridge
        Graph.bridge = null
        if (b != null) thread(name = "bridge-stop") { runCatching { b.stop() } }
        Graph.status.value = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (Graph.settings.desiredRunning) {
            Log.i(TAG, "App swiped away; scheduling restart")
            scheduleRestart(this)
        }
    }

    override fun onDestroy() {
        val unexpected = !stopping && Graph.settings.desiredRunning
        shutdown()
        if (unexpected) {
            Log.w(TAG, "Service destroyed unexpectedly; scheduling restart")
            scheduleRestart(this)
        }
        super.onDestroy()
    }
}
