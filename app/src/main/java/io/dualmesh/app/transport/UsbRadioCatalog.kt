package io.dualmesh.app.transport

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class UsbRadioCatalog(private val context: Context) {
    data class Device(val target: UsbRadioTarget, val name: String, val authorized: Boolean)
    private val manager = context.getSystemService(UsbManager::class.java)
    private val action = "${context.packageName}.USB_RADIO_PERMISSION"
    private val pending = mutableMapOf<String, MutableList<(Result<UsbRadioTarget>) -> Unit>>()
    private val mutableDevices = MutableStateFlow<List<Device>>(emptyList())
    val devices = mutableDevices.asStateFlow()
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != action) return
            val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            val callbacks = pending.remove(device?.deviceName) ?: return
            if (device != null && manager.hasPermission(device)) {
                refresh()
                callbacks.forEach { it(Result.success(UsbRadioTarget(device.vendorId, device.productId, 0, runCatching { device.serialNumber }.getOrNull(), device.deviceName))) }
            } else {
                mutableMessage.value = "USB permission was denied. Tap the device to try again."
                callbacks.forEach { it(Result.failure(IllegalStateException(mutableMessage.value))) }
            }
        }
    }
    init { ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED); refresh() }
    fun refresh() {
        mutableDevices.value = UsbSerialProber.getDefaultProber().findAllDrivers(manager).flatMap { driver ->
            val d = driver.device
            val allowed = manager.hasPermission(d)
            val serial = if (allowed) runCatching { d.serialNumber }.getOrNull() else null
            driver.ports.indices.map { port -> Device(UsbRadioTarget(d.vendorId, d.productId, port, serial, d.deviceName),
                (d.productName ?: "USB %04x:%04x".format(d.vendorId, d.productId)) + " · port ${port + 1}", allowed) }
        }
    }
    /** Called only after an explicit user choice, never by the automatic retry loop. */
    fun authorize(target: UsbRadioTarget, callback: (Result<UsbRadioTarget>) -> Unit) {
        val matches = manager.deviceList.values.filter { it.vendorId == target.vendor && it.productId == target.product }
        val device = matches.firstOrNull { target.serial != null && manager.hasPermission(it) && runCatching { it.serialNumber }.getOrNull() == target.serial }
            ?: matches.firstOrNull { it.deviceName == target.path }
            ?: matches.singleOrNull()?.takeIf { target.serial != null }
        if (device == null) { callback(Result.failure(IllegalStateException("USB radio is not attached, or its identity is ambiguous. Select it from the USB list."))); return }
        fun verified(resolved: Result<UsbRadioTarget>) {
            callback(resolved.mapCatching {
                require(target.serial == null || it.serial == target.serial) { "The attached USB device has a different serial number" }
                it.copy(port = target.port)
            })
        }
        if (manager.hasPermission(device)) {
            verified(Result.success(target.copy(serial = runCatching { device.serialNumber }.getOrNull(), path = device.deviceName)))
            return
        }
        if (device.deviceName in pending) { pending.getValue(device.deviceName).add(::verified); mutableMessage.value = "Waiting for USB permission"; return }
        val waiting = mutableListOf<(Result<UsbRadioTarget>) -> Unit>(::verified)
        pending[device.deviceName] = waiting
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (pending[device.deviceName] === waiting) pending.remove(device.deviceName)?.forEach {
                it(Result.failure(IllegalStateException("USB access request timed out. Select the device to try again.")))
            }
        }, 60_000)
        mutableMessage.value = "Allow USB access on the phone to connect this radio."
        val permissionIntent = PendingIntent.getBroadcast(context, device.deviceId, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        runCatching { manager.requestPermission(device, permissionIntent) }.onFailure {
            pending.remove(device.deviceName)
            callback(Result.failure(it))
        }
    }
    fun report(message: String?) { mutableMessage.value = message }
    fun close() { context.unregisterReceiver(receiver); pending.clear() }
}
