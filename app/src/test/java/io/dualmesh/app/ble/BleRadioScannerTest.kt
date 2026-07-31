package io.dualmesh.app.ble

import io.dualmesh.app.model.MeshNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleRadioScannerTest {
    @Test
    fun `classifies bonded radios from cached services`() {
        assertEquals(
            MeshNetwork.MESHCORE,
            BleRadioScanner.classifyBondedRadio(
                name = "Custom radio name",
                services = listOf(BleRadioScanner.MESHCORE_SERVICE),
            ),
        )
        assertEquals(
            MeshNetwork.MESHTASTIC,
            BleRadioScanner.classifyBondedRadio(
                name = "Custom radio name",
                services = listOf(BleRadioScanner.MESHTASTIC_SERVICE),
            ),
        )
    }

    @Test
    fun `falls back to known product names when the UUID cache is empty`() {
        assertEquals(
            MeshNetwork.MESHCORE,
            BleRadioScanner.classifyBondedRadio("MeshCore-test bench", emptyList()),
        )
        assertEquals(
            MeshNetwork.MESHTASTIC,
            BleRadioScanner.classifyBondedRadio("Meshtastic_a024", emptyList()),
        )
    }

    @Test
    fun `does not guess an unknown bonded device`() {
        assertNull(BleRadioScanner.classifyBondedRadio("Car stereo", emptyList()))
    }
}
