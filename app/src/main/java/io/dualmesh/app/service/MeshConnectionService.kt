package io.dualmesh.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.dualmesh.app.DualMeshApplication
import io.dualmesh.app.MainActivity
import io.dualmesh.app.R
import io.dualmesh.app.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class MeshConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting mesh radios…"))

        val manager = (application as DualMeshApplication).manager
        combine(
            manager.meshCore.deviceConnections,
            manager.meshtastic.deviceConnections,
            manager.reconnectTargets,
        ) { meshCore, meshtastic, targets -> Triple(meshCore, meshtastic, targets) }
            .onEach { (meshCore, meshtastic, targets) ->
                val connections = meshCore + meshtastic
                val active = connections.any {
                    it.state == ConnectionState.CONNECTING ||
                        it.state == ConnectionState.CONNECTED
                }
                if (!active && targets.isEmpty()) {
                    stopSelf()
                } else {
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        buildNotification(statusText(connections.map { it.state })),
                    )
                }
            }
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun statusText(states: List<ConnectionState>): String {
        val connected = states.count { it == ConnectionState.CONNECTED }
        return when {
            connected > 0 -> "Keeping $connected mesh ${if (connected == 1) "radio" else "radios"} connected"
            else -> "Connecting mesh radios…"
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dualmesh_notification)
            .setContentTitle("DualMesh is active")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Radio connections",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps MeshCore and Meshtastic radios connected in the background"
            },
        )
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "mesh_connections"
        private const val NOTIFICATION_ID = 19_999

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MeshConnectionService::class.java),
                )
            }
        }
    }
}
