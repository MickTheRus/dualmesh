package io.dualmesh.app.car

import android.app.IntentService
import android.content.Intent
import androidx.core.app.RemoteInput
import io.dualmesh.app.DualMeshApplication
import io.dualmesh.app.model.MeshNetwork

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class MeshMessagingService : IntentService("MeshMessagingService") {
    override fun onHandleIntent(intent: Intent?) {
        intent ?: return
        val network = intent.getStringExtra(EXTRA_NETWORK)
            ?.let { runCatching { MeshNetwork.valueOf(it) }.getOrNull() }
            ?: return
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION)
            ?.takeIf(String::isNotBlank)
            ?: return
        val deviceId = intent.getStringExtra(EXTRA_DEVICE)
            ?.takeIf(String::isNotBlank)
            ?: return
        val app = application as DualMeshApplication

        when (intent.action) {
            ACTION_REPLY -> {
                val reply = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(REMOTE_INPUT_KEY)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                if (reply.isNotEmpty() &&
                    app.manager.send(network, deviceId, conversationId, reply).isSuccess
                ) {
                    app.autoMessaging.markRead(network, deviceId, conversationId)
                }
            }
            ACTION_MARK_READ -> app.autoMessaging.markRead(network, deviceId, conversationId)
        }
    }

    companion object {
        const val ACTION_REPLY = "io.dualmesh.app.action.REPLY"
        const val ACTION_MARK_READ = "io.dualmesh.app.action.MARK_READ"
        const val EXTRA_NETWORK = "mesh_network"
        const val EXTRA_DEVICE = "mesh_device"
        const val EXTRA_CONVERSATION = "mesh_conversation"
        const val REMOTE_INPUT_KEY = "mesh_reply"
    }
}
