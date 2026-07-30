package io.dualmesh.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.RadioDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class BleRadioScanner(private val context: Context) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val scanner get() = bluetoothManager.adapter?.bluetoothLeScanner
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopScan = Runnable { stop() }

    private val _devices = MutableStateFlow<List<RadioDevice>>(emptyList())
    val devices: StateFlow<List<RadioDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = accept(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::accept)
        }

        override fun onScanFailed(errorCode: Int) {
            mainHandler.removeCallbacks(stopScan)
            _scanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasScanPermission() || scanner == null) return
        if (_scanning.value) {
            scanner?.stopScan(scanCallback)
            mainHandler.removeCallbacks(stopScan)
        }
        _devices.value = emptyList()
        addBondedRadios()
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(MESHCORE_SERVICE)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(MESHTASTIC_SERVICE)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(filters, settings, scanCallback)
        _scanning.value = true
        mainHandler.postDelayed(stopScan, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        mainHandler.removeCallbacks(stopScan)
        if (hasScanPermission()) scanner?.stopScan(scanCallback)
        _scanning.value = false
    }

    @SuppressLint("MissingPermission")
    private fun accept(result: ScanResult) {
        val services = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid }
        val network = when {
            MESHCORE_SERVICE in services -> MeshNetwork.MESHCORE
            MESHTASTIC_SERVICE in services -> MeshNetwork.MESHTASTIC
            else -> return
        }
        val address = result.device.address
        val name = runCatching {
            result.device.name ?: result.scanRecord?.deviceName
        }.getOrNull() ?: "${network.label} ${address.takeLast(5)}"
        val device = RadioDevice(address, name, network, result.rssi)
        _devices.value = (_devices.value.filterNot { it.address == address } + device)
            .sortedWith(compareBy<RadioDevice> { it.network }.thenByDescending { it.rssi })
    }

    @SuppressLint("MissingPermission")
    private fun addBondedRadios() {
        if (!hasConnectPermission()) return
        bluetoothManager.adapter?.bondedDevices.orEmpty().forEach { device ->
            val services = device.uuids.orEmpty().map { it.uuid }
            val network = when {
                MESHCORE_SERVICE in services -> MeshNetwork.MESHCORE
                MESHTASTIC_SERVICE in services -> MeshNetwork.MESHTASTIC
                else -> return@forEach
            }
            val address = device.address
            val name = device.name ?: "${network.label} ${address.takeLast(5)}"
            _devices.value = _devices.value + RadioDevice(
                address = address,
                name = name,
                network = network,
                rssi = BONDED_DEVICE_RSSI,
            )
        }
    }

    private fun hasScanPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val BONDED_DEVICE_RSSI = -127
        private const val SCAN_TIMEOUT_MS = 15_000L
        val MESHCORE_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val MESHTASTIC_SERVICE: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
    }
}
