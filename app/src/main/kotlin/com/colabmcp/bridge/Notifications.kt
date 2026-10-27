package com.colabmcp.bridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.colabmcp.core.BridgeStatus
import com.colabmcp.core.tunnel.TunnelStatus

object Notifications {
    const val CHANNEL_SERVICE = "bridge_service"
    const val CHANNEL_ALERTS = "bridge_alerts"
    const val ID_SERVICE = 1
    private const val ID_ACTION = 2

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "MCP server", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the Colab MCP server running in the background"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Action required", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Links you need to open, e.g. to allow Google Drive access for a Colab runtime"
            },
        )
    }

    private fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun service(ctx: Context, status: BridgeStatus?, pin: String): Notification {
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags())
        val stop = PendingIntent.getService(ctx, 1, Intent(ctx, BridgeService::class.java).setAction(BridgeService.ACTION_STOP), flags())
        val copy = PendingIntent.getBroadcast(ctx, 2, Intent(ctx, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_COPY_URL), flags())

        val tunnel = status?.tunnel
        val title = when {
            status == null || !status.running -> "Starting Colab MCP..."
            tunnel?.state == TunnelStatus.State.ONLINE -> "Colab MCP is online"
            tunnel?.state == TunnelStatus.State.CONNECTING -> "Connecting tunnel..."
            tunnel?.state == TunnelStatus.State.ERROR -> "Tunnel problem, retrying"
            else -> "Colab MCP running (local only)"
        }
        val url = status?.mcpUrl ?: status?.localUrl?.let { "$it/mcp" }
        val text = url ?: tunnel?.message ?: "Starting..."
        val big = buildString {
            url?.let { append(it).append('\n') }
            if (tunnel?.state == TunnelStatus.State.ERROR) tunnel.message?.let { append(it.take(200)).append('\n') }
            if (pin.isNotEmpty()) append("Approval code for new connections: ").append(pin).append('\n')
            status?.lastTool?.let { append("Last tool: ").append(it).append(" (").append(status.toolCalls).append(" calls)") }
        }.trim()
        return NotificationCompat.Builder(ctx, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Copy MCP URL", copy)
            .addAction(0, "Stop MCP", stop)
            .build()
    }

    fun update(ctx: Context, status: BridgeStatus?, pin: String) {
        if (!canPost(ctx)) return
        ctx.getSystemService(NotificationManager::class.java).notify(ID_SERVICE, service(ctx, status, pin))
    }

    fun actionRequired(ctx: Context, title: String, url: String) {
        if (!canPost(ctx)) return
        val open = PendingIntent.getActivity(ctx, 3, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags())
        val n = NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(title)
            .setContentText("Tap to open the authorization page")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_ACTION, n)
    }
}
