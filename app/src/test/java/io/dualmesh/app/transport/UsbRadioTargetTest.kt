package io.dualmesh.app.transport

import io.dualmesh.app.connection.ConnectionProfileStore
import io.dualmesh.app.model.MeshNetwork
import org.junit.Assert.*
import org.junit.Test

class UsbRadioTargetTest {
    @Test fun serialIdentitySurvivesReplugWithoutMergingPortsOrAdapters() {
        val target = UsbRadioTarget(0x239a, 0x8029, 0, "radio:α/123", "/dev/bus/usb/001/002")
        assertEquals(target.encode(), target.copy(path = "/dev/bus/usb/003/099").encode())
        assertNotEquals(target.encode(), target.copy(port = 1).encode())
        assertNotEquals(target.encode(), target.copy(serial = "another").encode())
        val decoded = UsbRadioTarget.parse(target.encode()).getOrThrow()
        assertEquals(target.serial, decoded.serial)
        assertEquals(target.vendor, decoded.vendor)
        assertEquals(target.product, decoded.product)
        assertEquals(target.port, decoded.port)
    }
    @Test fun missingSerialRetainsAttachedDeviceIdentityAndHistoryRoundTripsUsb() {
        val target = UsbRadioTarget(1, 2, 0, null, "/dev/usb/one")
        assertEquals(target, UsbRadioTarget.parse(target.encode()).getOrThrow())
        assertNotEquals(target.encode(), target.copy(path = "/dev/usb/two").encode())
        val profile = ConnectionProfileStore.Profile(MeshNetwork.MESHCORE, ConnectionProfileStore.Transport.USB, target.encode(), "USB radio", enabled = true)
        assertEquals(profile, ConnectionProfileStore.decodeHistoryEntry(profile.network, ConnectionProfileStore.encodeHistoryEntry(profile)))
        listOf("", "1:2:0", "-1:2:0::YWJj", "1:2:99::YWJj").forEach { assertTrue(UsbRadioTarget.parse(it).isFailure) }
    }
}
