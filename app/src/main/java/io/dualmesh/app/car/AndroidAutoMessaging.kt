package io.dualmesh.app.car

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import io.dualmesh.app.DualMeshManager
import io.dualmesh.app.MainActivity
import io.dualmesh.app.R
import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.UnifiedMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class AndroidAutoMessaging(
    private val context: Context,
    private val manager: DualMeshManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notificationManager = NotificationManagerCompat.from(context)
    private val readMessageIds =
        mutableMapOf<Triple<MeshNetwork, String, String>, MutableSet<String>>()

    fun start() {
        createNotificationChannel()
        manager.messageEvents
            .onEach { message ->
                if (!message.outgoing) notifyConversation(message)
            }
            .launchIn(scope)
    }

    fun close() {
        scope.cancel()
    }

    fun markRead(network: MeshNetwork, deviceId: String, conversationId: String) {
        val key = Triple(network, deviceId, conversationId)
        val readIds = readMessageIds.getOrPut(key) { mutableSetOf() }
        manager.messages.value
            .filter {
                !it.outgoing &&
                    it.network == network &&
                    it.deviceId == deviceId &&
                    it.conversationId == conversationId
            }
            .mapTo(readIds, UnifiedMessage::id)
        notificationManager.cancel(notificationId(network, deviceId, conversationId))
    }

    fun isRead(
        network: MeshNetwork,
        deviceId: String,
        conversationId: String,
        messageId: String,
    ): Boolean =
        messageId in readMessageIds[Triple(network, deviceId, conversationId)].orEmpty()

    @SuppressLint("MissingPermission")
    private fun notifyConversation(latest: UnifiedMessage) {
        if (!canPostNotifications()) return
        val conversationId = latest.conversationId
        val alreadyRead = readMessageIds[
            Triple(latest.network, latest.deviceId, conversationId)
        ].orEmpty()
        val conversationTitle =
            "${latest.network.label} · ${latest.deviceName ?: "radio"} · " +
                manager.conversationName(latest.network, conversationId)
        val me = Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(conversationTitle)
            .setGroupConversation(conversationId.startsWith("channel:"))

        manager.messages.value
            .filter {
                !it.outgoing &&
                    !it.isReaction &&
                    it.network == latest.network &&
                    it.deviceId == latest.deviceId &&
                    it.conversationId == latest.conversationId &&
                    it.id !in alreadyRead
            }
            .takeLast(10)
            .forEach { message ->
                val sender = Person.Builder().setName(message.sender).build()
                style.addMessage(message.text, message.timestampMillis, sender)
            }

        val requestBase = notificationId(latest.network, latest.deviceId, conversationId)
        val replyIntent = serviceIntent(
            MeshMessagingService.ACTION_REPLY,
            latest.network,
            latest.deviceId,
            conversationId,
        )
        val replyPendingIntent = PendingIntent.getService(
            context,
            requestBase * 2,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = RemoteInput.Builder(MeshMessagingService.REMOTE_INPUT_KEY)
            .setLabel("Reply on $conversationTitle")
            .build()
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_reply,
            "Reply",
            replyPendingIntent,
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .addRemoteInput(remoteInput)
            .build()

        val readIntent = serviceIntent(
            MeshMessagingService.ACTION_MARK_READ,
            latest.network,
            latest.deviceId,
            conversationId,
        )
        val readPendingIntent = PendingIntent.getService(
            context,
            requestBase * 2 + 1,
            readIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val readAction = NotificationCompat.Action.Builder(
            R.drawable.ic_mark_read,
            "Mark read",
            readPendingIntent,
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val openIntent = PendingIntent.getActivity(
            context,
            requestBase,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dualmesh_notification)
            .setContentTitle(conversationTitle)
            .setContentText(
                if (latest.isReaction) {
                    "${latest.sender} reacted ${latest.reactionEmoji}"
                } else {
                    latest.text
                },
            )
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .addAction(replyAction)
            .addAction(readAction)
            .build()

        notificationManager.notify(requestBase, notification)
    }

    private fun serviceIntent(
        action: String,
        network: MeshNetwork,
        deviceId: String,
        conversationId: String,
    ) =
        Intent(context, MeshMessagingService::class.java).apply {
            this.action = action
            putExtra(MeshMessagingService.EXTRA_NETWORK, network.name)
            putExtra(MeshMessagingService.EXTRA_DEVICE, deviceId)
            putExtra(MeshMessagingService.EXTRA_CONVERSATION, conversationId)
        }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Mesh messages",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Incoming MeshCore and Meshtastic messages"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "mesh_messages"

        fun notificationId(network: MeshNetwork, deviceId: String, conversationId: String): Int =
            20_000 + (
                (31 * (31 * network.ordinal + deviceId.hashCode()) + conversationId.hashCode()) and
                    0x3fffffff
                )
    }
}
