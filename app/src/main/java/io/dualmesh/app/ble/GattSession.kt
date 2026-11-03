package io.dualmesh.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.UUID

class GattSession(
    private val context: Context,
    private val serviceUuid: UUID,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnecting() = Unit
        fun onReady()
        fun onDisconnected()
        fun onError(message: String)
        fun onNotification(characteristic: UUID, value: ByteArray)
        fun onRead(characteristic: UUID, value: ByteArray)
    }

    private sealed interface Operation {
        data class Write(val characteristic: UUID, val value: ByteArray) : Operation
        data class Read(val characteristic: UUID) : Operation
        data class EnableNotify(val characteristic: UUID) : Operation
    }

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val operations = ArrayDeque<Operation>()
    private var operationInFlight = false
    private var currentOperation: Operation? = null
    private var operationTimeout: Runnable? = null
    private var connectionTimeout: Runnable? = null
    private var gatt: BluetoothGatt? = null
    private var service: BluetoothGattService? = null
    private var configured = false
    private var bondReceiver: BroadcastReceiver? = null
    private var bondTimeout: Runnable? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (this@GattSession.gatt !== gatt) {
                closeStaleGatt(gatt)
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("Bluetooth connection failed ($status)")
                close()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!safeDiscoverServices(gatt)) {
                        listener.onError("Could not start Bluetooth service discovery")
                        close()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    close()
                    listener.onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (this@GattSession.gatt !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndClose("Bluetooth service discovery failed ($status)")
                return
            }
            service = gatt.getService(serviceUuid)
            if (service == null) {
                failAndClose("Expected radio service was not found")
                return
            }
            if (!requestMtu(gatt)) notifyReady()
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (this@GattSession.gatt !== gatt) return
            notifyReady()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (this@GattSession.gatt !== gatt) return
            listener.onNotification(characteristic.uuid, value.copyOf())
        }

        @Deprecated("Legacy Android callback")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (this@GattSession.gatt !== gatt) return
            listener.onNotification(characteristic.uuid, characteristic.value?.copyOf() ?: byteArrayOf())
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (this@GattSession.gatt !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                finishOperation()
                listener.onRead(characteristic.uuid, value.copyOf())
            } else {
                listener.onRead(characteristic.uuid, byteArrayOf())
                failAndClose("Bluetooth read failed ($status)")
            }
        }

        @Deprecated("Legacy Android callback")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (this@GattSession.gatt !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                finishOperation()
                listener.onRead(characteristic.uuid, characteristic.value?.copyOf() ?: byteArrayOf())
            } else {
                listener.onRead(characteristic.uuid, byteArrayOf())
                failAndClose("Bluetooth read failed ($status)")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (this@GattSession.gatt !== gatt) return
            if (!writeRequiresCallback(characteristic)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndClose("Bluetooth write failed ($status)")
                return
            }
            finishOperation()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (this@GattSession.gatt !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndClose("Bluetooth notification setup failed ($status)")
                return
            }
            finishOperation()
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String, requireBond: Boolean = false) {
        if (!hasConnectPermission()) {
            listener.onError("Bluetooth connect permission is missing")
            return
        }
        disconnect()
        configured = false
        listener.onConnecting()
        val adapter = bluetoothManager.adapter
        if (adapter == null) {
            listener.onError("Bluetooth is unavailable")
            return
        }
        val device = runCatching { adapter.getRemoteDevice(address) }
            .getOrElse {
                listener.onError("Invalid Bluetooth device address")
                return
            }
        if (requireBond && device.bondState != BluetoothDevice.BOND_BONDED) {
            startBonding(device)
        } else {
            openGatt(device)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        clearBonding()
        if (hasConnectPermission()) gatt?.disconnect()
        close()
    }

    fun write(characteristic: UUID, value: ByteArray) {
        enqueue(Operation.Write(characteristic, value.copyOf()))
    }

    /** Bound broker downlink traffic so it cannot grow the radio operation queue indefinitely. */
    fun tryWrite(characteristic: UUID, value: ByteArray): Boolean = synchronized(operations) {
        if (!configured || operations.size >= 32) return@synchronized false
        operations.addLast(Operation.Write(characteristic, value.copyOf()))
        runNextLocked()
        true
    }

    fun read(characteristic: UUID) {
        enqueue(Operation.Read(characteristic))
    }

    fun enableNotifications(characteristic: UUID) {
        enqueue(Operation.EnableNotify(characteristic))
    }

    private fun enqueue(operation: Operation) {
        synchronized(operations) {
            operations.addLast(operation)
            runNextLocked()
        }
    }

    @SuppressLint("MissingPermission")
    private fun runNextLocked() {
        if (operationInFlight) return
        val activeGatt = gatt ?: return
        if (operations.isEmpty()) return
        val operation = operations.removeFirst()
        val characteristic = service?.getCharacteristic(
            when (operation) {
                is Operation.Write -> operation.characteristic
                is Operation.Read -> operation.characteristic
                is Operation.EnableNotify -> operation.characteristic
            },
        )
        if (characteristic == null) {
            failAndClose("Radio characteristic is missing")
            return
        }

        operationInFlight = true
        currentOperation = operation
        var completesWithoutCallback = false
        val started = when (operation) {
            is Operation.Write -> {
                completesWithoutCallback = !writeRequiresCallback(characteristic)
                writeCharacteristic(activeGatt, characteristic, operation.value)
            }
            is Operation.Read -> activeGatt.readCharacteristic(characteristic)
            is Operation.EnableNotify -> enableNotification(activeGatt, characteristic)
        }
        if (!started) {
            operationInFlight = false
            currentOperation = null
            failAndClose("Bluetooth operation could not be started")
        } else if (completesWithoutCallback) {
            mainHandler.postDelayed(
                { finishOperation(operation) },
                WRITE_WITHOUT_RESPONSE_DELAY_MS,
            )
        } else {
            scheduleOperationTimeout(operation)
        }
    }

    private fun finishOperation(expected: Operation? = null) {
        synchronized(operations) {
            if (currentOperation == null) return
            if (expected != null && currentOperation !== expected) return
            clearOperationTimeout()
            operationInFlight = false
            currentOperation = null
            runNextLocked()
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        val writeType = if (writeRequiresCallback(characteristic)) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun writeRequiresCallback(characteristic: BluetoothGattCharacteristic): Boolean =
        characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0

    @SuppressLint("MissingPermission")
    private fun enableNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeDiscoverServices(gatt: BluetoothGatt): Boolean =
        hasConnectPermission() && gatt.discoverServices()

    @SuppressLint("MissingPermission")
    private fun requestMtu(gatt: BluetoothGatt): Boolean =
        hasConnectPermission() && gatt.requestMtu(185)

    private fun notifyReady() {
        if (!configured) {
            clearConnectionTimeout()
            configured = true
            listener.onReady()
        }
    }

    @SuppressLint("MissingPermission")
    private fun close() {
        clearConnectionTimeout()
        clearOperationTimeout()
        synchronized(operations) {
            operations.clear()
            operationInFlight = false
            currentOperation = null
        }
        service = null
        configured = false
        if (hasConnectPermission()) gatt?.close()
        gatt = null
    }

    @SuppressLint("MissingPermission")
    private fun openGatt(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            listener.onError("Bluetooth connect permission is missing")
            return
        }
        gatt = runCatching {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrElse {
            listener.onError("Could not open Bluetooth connection")
            null
        }
        if (gatt != null) scheduleConnectionTimeout()
        else listener.onError("Bluetooth could not connect. Check that Bluetooth and the radio are on.")
    }

    @SuppressLint("MissingPermission")
    private fun startBonding(device: BluetoothDevice) {
        clearBonding()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val changedDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (changedDevice?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> {
                        clearBonding()
                        openGatt(device)
                    }
                    BluetoothDevice.BOND_NONE -> {
                        clearBonding()
                        listener.onError("Bluetooth bonding failed")
                    }
                }
            }
        }
        bondReceiver = receiver
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val timeout = Runnable {
            clearBonding()
            listener.onError("Bluetooth bonding timed out")
        }
        bondTimeout = timeout
        mainHandler.postDelayed(timeout, BOND_TIMEOUT_MS)

        val started = device.bondState == BluetoothDevice.BOND_BONDING || device.createBond()
        if (!started && device.bondState != BluetoothDevice.BOND_BONDED) {
            clearBonding()
            listener.onError("Could not start Bluetooth bonding")
        } else if (device.bondState == BluetoothDevice.BOND_BONDED) {
            clearBonding()
            openGatt(device)
        }
    }

    private fun clearBonding() {
        bondTimeout?.let(mainHandler::removeCallbacks)
        bondTimeout = null
        bondReceiver?.let { receiver ->
            runCatching { context.unregisterReceiver(receiver) }
        }
        bondReceiver = null
    }

    private fun scheduleOperationTimeout(operation: Operation) {
        clearOperationTimeout()
        val timeout = Runnable {
            val expired = synchronized(operations) {
                if (!operationInFlight || currentOperation !== operation) {
                    false
                } else {
                    operationInFlight = false
                    currentOperation = null
                    operations.clear()
                    true
                }
            }
            if (expired) {
                failAndClose(
                    "Bluetooth ${
                        when (operation) {
                            is Operation.Write -> "write"
                            is Operation.Read -> "read"
                            is Operation.EnableNotify -> "notification setup"
                        }
                    } timed out",
                )
            }
        }
        operationTimeout = timeout
        mainHandler.postDelayed(timeout, OPERATION_TIMEOUT_MS)
    }

    private fun clearOperationTimeout() {
        operationTimeout?.let(mainHandler::removeCallbacks)
        operationTimeout = null
    }

    private fun scheduleConnectionTimeout() {
        clearConnectionTimeout()
        val timeout = Runnable {
            if (!configured && gatt != null) {
                failAndClose("Bluetooth connection setup timed out")
            }
        }
        connectionTimeout = timeout
        mainHandler.postDelayed(timeout, CONNECTION_TIMEOUT_MS)
    }

    private fun clearConnectionTimeout() {
        connectionTimeout?.let(mainHandler::removeCallbacks)
        connectionTimeout = null
    }

    private fun failAndClose(message: String) {
        clearBonding()
        listener.onError(message)
        close()
    }

    @SuppressLint("MissingPermission")
    private fun closeStaleGatt(staleGatt: BluetoothGatt) {
        if (hasConnectPermission()) staleGatt.close()
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val BOND_TIMEOUT_MS = 30_000L
        private const val CONNECTION_TIMEOUT_MS = 20_000L
        private const val OPERATION_TIMEOUT_MS = 10_000L
        private const val WRITE_WITHOUT_RESPONSE_DELAY_MS = 75L
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
