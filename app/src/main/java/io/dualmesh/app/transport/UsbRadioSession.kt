package io.dualmesh.app.transport

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import io.dualmesh.app.model.MeshNetwork
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class UsbRadioSession(context: Context, private val network: MeshNetwork, private val listener: Listener) {
    interface Listener {
        fun onUsbReady()
        fun onUsbPacket(packet: ByteArray)
        fun onUsbError(message: String)
    }
    private val manager = context.getSystemService(UsbManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var generation = 0
    @Volatile private var port: UsbSerialPort? = null
    private var job: Job? = null
    private var writes: Channel<ByteArray>? = null
    fun connect(address: String): Result<Unit> = UsbRadioTarget.parse(address).mapCatching { target ->
        disconnect()
        val version = ++generation
        val queue = Channel<ByteArray>(32)
        writes = queue
        job = scope.launch {
            var connection: android.hardware.usb.UsbDeviceConnection? = null
            var opened: UsbSerialPort? = null
            val framing = RadioStreamFraming(network, listener::onUsbPacket)
            try {
                val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager).filter {
                    val d = it.device
                    d.vendorId == target.vendor && d.productId == target.product &&
                        if (target.serial != null) manager.hasPermission(d) && runCatching { d.serialNumber }.getOrNull() == target.serial else d.deviceName == target.path
                }
                check(drivers.size == 1) { "USB radio unavailable or permission required. Open Radios and tap Retry." }
                val driver = drivers.single()
                check(manager.hasPermission(driver.device)) { "USB permission required. Open Radios and tap Retry." }
                connection = manager.openDevice(driver.device) ?: error("Could not open USB radio")
                val serial = driver.ports.getOrNull(target.port) ?: error("USB serial port is unavailable")
                opened = serial
                serial.open(connection)
                serial.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                try { serial.dtr = true } catch (_: UnsupportedOperationException) { }
                try { serial.rts = true } catch (_: UnsupportedOperationException) { }
                if (version != generation) return@launch
                port = serial
                if (framing.wakeBytes.isNotEmpty()) serial.write(framing.wakeBytes, 2_000)
                listener.onUsbReady()
                coroutineScope {
                    launch {
                        for (payload in queue) {
                            if (version != generation) break
                            serial.write(framing.frame(payload), 2_000)
                        }
                    }
                    while (isActive && version == generation) {
                        val buffer = ByteArray(1024)
                        val size = serial.read(buffer, 1_000)
                        if (size > 0 && version == generation) framing.accept(buffer, size)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (version == generation) listener.onUsbError(error.message ?: "USB connection failed") }
            finally {
                queue.close()
                if (version == generation) { port = null; writes = null }
                runCatching { opened?.close() }
                runCatching { connection?.close() }
            }
        }
    }
    fun send(payload: ByteArray): Boolean = port != null && writes?.trySend(payload.copyOf())?.isSuccess == true
    fun disconnect() {
        generation++
        writes?.close(); writes = null
        job?.cancel(); job = null
        runCatching { port?.close() }; port = null
    }
}
