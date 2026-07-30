package io.dualmesh.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.dualmesh.app.connection.ConnectionProfileStore
import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.RadioDevice

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val manager = (application as DualMeshApplication).manager

    fun startScan() = manager.scanner.start()
    fun stopScan() = manager.scanner.stop()
    fun connect(device: RadioDevice) = manager.connect(device)
    fun connect(profile: ConnectionProfileStore.Profile) = manager.connect(profile)
    fun connectMeshtasticTcp(endpoint: String): Result<Unit> =
        manager.connectMeshtasticTcp(endpoint)
    fun selectNetwork(network: MeshNetwork) = manager.selectNetwork(network)
    fun selectChannel(network: MeshNetwork, index: Int) =
        manager.selectChannel(network, index)
    fun selectContact(network: MeshNetwork, contactId: String) =
        manager.selectContact(network, contactId)
    fun disconnect(network: MeshNetwork) = manager.disconnect(network)
    fun disconnect(profile: ConnectionProfileStore.Profile) = manager.disconnect(profile)
    fun selectDevice(profile: ConnectionProfileStore.Profile) = manager.selectDevice(profile)
    fun send(text: String): Result<Unit> = manager.send(text)
}
