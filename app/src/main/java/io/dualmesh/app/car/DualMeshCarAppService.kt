package io.dualmesh.app.car

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.messaging.model.CarMessage
import androidx.car.app.messaging.model.ConversationCallback
import androidx.car.app.messaging.model.ConversationItem
import androidx.car.app.model.Action
import androidx.car.app.model.CarText
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.app.Person
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.dualmesh.app.BuildConfig
import io.dualmesh.app.DualMeshApplication
import io.dualmesh.app.DualMeshManager
import io.dualmesh.app.model.MeshNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class DualMeshCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(this)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session =
        DualMeshCarSession(application as DualMeshApplication)
}

internal class DualMeshConversationsScreen(
    carContext: CarContext,
    private val application: DualMeshApplication,
    private val nodeMapSurface: CarNodeMapSurface,
) : Screen(carContext) {
    private val manager: DualMeshManager = application.manager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val self = Person.Builder()
        .setName("You")
        .setKey("dualmesh-self")
        .build()
    private val system = Person.Builder()
        .setName("DualMesh")
        .setKey("dualmesh-system")
        .build()

    init {
        combine(
            manager.messages,
            manager.meshCore.deviceConnections,
            manager.meshtastic.deviceConnections,
        ) { _, _, _ -> Unit }
            .onEach { invalidate() }
            .launchIn(scope)

        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    scope.cancel()
                }
            },
        )
    }

    override fun onGetTemplate(): Template {
        val conversations = ItemList.Builder()
        configuredConversations().forEach { item ->
            conversations.addItem(conversation(item))
        }

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setStartHeaderAction(Action.APP_ICON)
                    .setTitle("DualMesh messages")
                    .addEndHeaderAction(
                        Action.Builder()
                            .setTitle("Nodes")
                            .setOnClickListener {
                                screenManager.push(
                                    DualMeshNodeMapScreen(
                                        carContext,
                                        manager,
                                        nodeMapSurface,
                                    ),
                                )
                            }
                            .build(),
                    )
                    .build(),
            )
            .setSingleList(conversations.build())
            .build()
    }

    private fun configuredConversations(): List<CarConversation> =
        buildList {
            manager.meshCore.deviceConnections.value.forEach { device ->
                device.channels.forEach { channel ->
                    add(
                        CarConversation(
                            MeshNetwork.MESHCORE,
                            deviceId = io.dualmesh.app.backend.MultiDeviceBackend.targetId(
                                device.profile,
                            ),
                            deviceName = device.profile.name,
                            conversationId = "channel:${channel.index}",
                            title = "#${channel.index} ${channel.name}",
                            isGroup = true,
                        ),
                    )
                }
            }
            manager.meshtastic.deviceConnections.value.forEach { device ->
                device.channels.forEach { channel ->
                    add(
                        CarConversation(
                            MeshNetwork.MESHTASTIC,
                            deviceId = io.dualmesh.app.backend.MultiDeviceBackend.targetId(
                                device.profile,
                            ),
                            deviceName = device.profile.name,
                            conversationId = "channel:${channel.index}",
                            title = "#${channel.index} ${channel.name}",
                            isGroup = true,
                        ),
                    )
                }
            }
            manager.messages.value
                .filter { !it.isReaction && it.conversationId.startsWith("contact:") }
                .map { Triple(it.network, it.deviceId, it.conversationId) }
                .distinct()
                .forEach { (network, deviceId, conversationId) ->
                    val message = manager.messages.value.lastOrNull {
                        !it.isReaction &&
                        it.network == network &&
                            it.deviceId == deviceId &&
                            it.conversationId == conversationId
                    }
                    add(
                        CarConversation(
                            network = network,
                            deviceId = deviceId,
                            deviceName = message?.deviceName ?: "radio",
                            conversationId = conversationId,
                            title = manager.conversationName(network, conversationId),
                            isGroup = false,
                        ),
                    )
                }
        }

    private fun conversation(item: CarConversation): ConversationItem {
        val network = item.network
        val deviceId = item.deviceId
        val conversationId = item.conversationId
        val channelMessages = manager.messages.value
            .filter {
                !it.isReaction &&
                it.network == network &&
                    it.deviceId == deviceId &&
                    it.conversationId == conversationId
            }
            .takeLast(5)
            .map { message ->
                val sender = if (message.outgoing) {
                    self
                } else {
                    Person.Builder()
                        .setName(message.sender)
                        .setKey("${network.name}:${message.sender}")
                        .build()
                }
                CarMessage.Builder()
                    .setSender(sender)
                    .setBody(CarText.create(message.text))
                    .setReceivedTimeEpochMillis(message.timestampMillis)
                    .setRead(
                        message.outgoing ||
                            application.autoMessaging.isRead(
                                network,
                                deviceId,
                                conversationId,
                                message.id,
                            ),
                    )
                    .build()
            }
            .toList()
        val carMessages = channelMessages.ifEmpty {
            listOf(
                CarMessage.Builder()
                    .setSender(system)
                    .setBody(CarText.create("No messages received on this channel yet"))
                    .setReceivedTimeEpochMillis(System.currentTimeMillis())
                    .setRead(true)
                    .build(),
            )
        }

        val callback = object : ConversationCallback {
            override fun onMarkAsRead() {
                mainHandler.post {
                    application.autoMessaging.markRead(network, deviceId, conversationId)
                    invalidate()
                }
            }

            override fun onTextReply(replyText: String) {
                mainHandler.post {
                    manager.send(network, deviceId, conversationId, replyText)
                        .onSuccess {
                            application.autoMessaging.markRead(network, deviceId, conversationId)
                            invalidate()
                        }
                        .onFailure { error ->
                            CarToast.makeText(
                                carContext,
                                error.message ?: "Could not send message",
                                CarToast.LENGTH_LONG,
                            ).show()
                        }
                }
            }
        }

        return ConversationItem.Builder(
            "${network.name}:$deviceId:$conversationId",
            CarText.create("${network.label} · ${item.deviceName} · ${item.title}"),
            self,
            carMessages,
            callback,
        )
            .setGroupConversation(item.isGroup)
            .build()
    }
}

private data class CarConversation(
    val network: MeshNetwork,
    val deviceId: String,
    val deviceName: String,
    val conversationId: String,
    val title: String,
    val isGroup: Boolean,
)
