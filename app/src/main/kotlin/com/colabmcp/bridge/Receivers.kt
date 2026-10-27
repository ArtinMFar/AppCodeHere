package com.colabmcp.bridge

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.colabmcp.core.util.Log

/** Restarts the bridge after a reboot or app update if it was running before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = Graph.settings
        if (s.desiredRunning && s.startOnBoot && Graph.auth.isSignedIn) {
            Log.i("BootReceiver", "Restarting MCP server after ${intent.action}")
            runCatching { BridgeService.start(context) }.onFailure { Log.w("BootReceiver", "Restart failed", it) }
        }
    }
}

/** Periodic watchdog / delayed restart: brings the service back if Android killed it. */
class WatchdogReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_WATCHDOG = "com.colabmcp.bridge.WATCHDOG"
        const val ACTION_RESTART = "com.colabmcp.bridge.RESTART"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!Graph.settings.desiredRunning) return
        if (BridgeService.isRunning && Graph.bridge != null) return
        Log.i("Watchdog", "MCP server is not running; restarting (${intent.action})")
        try {
            BridgeService.start(context)
        } catch (e: Exception) {
            // Android 12+ may refuse background starts unless battery optimisation is disabled.
            Log.w("Watchdog", "Could not restart in background: ${e.message}")
        }
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_COPY_URL = "com.colabmcp.bridge.COPY_URL"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COPY_URL) return
        val url = Graph.status.value?.let { it.mcpUrl ?: it.localUrl?.let { l -> "$l/mcp" } } ?: return
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("MCP URL", url))
        Toast.makeText(context, "MCP URL copied", Toast.LENGTH_SHORT).show()
    }
}
