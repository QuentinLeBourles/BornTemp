package com.borntemp.app.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the ELM327 / UDS response parsers.
 *
 * Inputs are written in the wire shape the parsers consume: a 29-bit MEB
 * source ID (`17FE007B` for BMS / `17FE0010` for EM, or `18DAF1xx` for an
 * OBD-II functional broadcast) followed by the ISO-TP single-frame length
 * byte (`0X`), the UDS positive-response service byte (`62`), the echoed
 * 2-byte DID, then the data bytes. Whitespace in fixtures is stripped.
 */
class ObdPidsTest {

    private val delta = 0.01f

    // ── Pack temperature: (B0*256+B1)/64 °C from 1E0E/1E0F ──────────────────

    @Test
    fun `parses pack temperature max from 4-byte response`() {
        assertEquals(35.875f, ObdPids.parsePackTemp("17FE007B07621E0E08F80002")!!, delta)
    }

    @Test
    fun `parses pack temperature min from 4-byte response`() {
        assertEquals(35.125f, ObdPids.parsePackTemp("17FE007B07621E0F08C8000B")!!, delta)
    }

    @Test
    fun `rejects pack temperature outside plausible range`() {
        assertNull(ObdPids.parsePackTemp("17FE007B07621E0EFFFFAAAA"))
    }

    // ── SOC BMS: raw / 2.5 % from 22028C ────────────────────────────────────

    @Test
    fun `parses SOC BMS from single-byte response`() {
        assertEquals(23.6f, ObdPids.parseSocBms("17FE007B0462028C3B")!!, delta)
    }

    @Test
    fun `parses SOC at full scale`() {
        assertEquals(100.0f, ObdPids.parseSocBms("17FE007B0462028CFA")!!, delta)
    }

    @Test
    fun `legacy parseSoc returns same value as parseSocBms`() {
        val sample = "17FE007B0462028C3B"
        assertEquals(ObdPids.parseSocBms(sample), ObdPids.parseSoc(sample))
    }

    @Test
    fun `bmsSocToDisplay maps 5_77 BMS to roughly 0 percent HMI`() {
        val display = ObdPids.bmsSocToDisplay(5.77f)!!
        assertTrue("expected near 0, got $display", display in 0f..0.5f)
    }

    @Test
    fun `bmsSocToDisplay clamps full scale to 100`() {
        // 100 % BMS over-shoots HMI (SOC_HMI = 100*51/46 - 6.4 ≈ 104.5),
        // the helper clamps the displayed value to 100.
        assertEquals(100f, ObdPids.bmsSocToDisplay(100f)!!, delta)
    }

    @Test
    fun `bmsSocToDisplay returns null for null input`() {
        assertNull(ObdPids.bmsSocToDisplay(null))
    }

    // ── Pack voltage / current ──────────────────────────────────────────────

    @Test
    fun `parses HV pack voltage from 2-byte response`() {
        assertEquals(342.0f, ObdPids.parsePackVoltage("17FE007B05621E3B0558")!!, delta)
    }

    @Test
    fun `parses HV pack current from 4-byte response`() {
        assertEquals(4.4f, ObdPids.parsePackCurrent("17FE007B07621E3C001664DC")!!, delta)
    }

    @Test
    fun `parses negative pack current as signed two's complement`() {
        assertEquals(-10.0f, ObdPids.parsePackCurrent("17FE007B07621E3CFFCEAAAA")!!, delta)
    }

    // ── Vehicle mode / pump / coolant temps ─────────────────────────────────

    @Test
    fun `parses vehicle mode standby driving and charging`() {
        assertEquals(ObdPids.VehicleMode.STANDBY,     ObdPids.parseVehicleMode("17FE007B04627448 00"))
        assertEquals(ObdPids.VehicleMode.DRIVING,     ObdPids.parseVehicleMode("17FE007B04627448 01"))
        assertEquals(ObdPids.VehicleMode.CHARGING_AC, ObdPids.parseVehicleMode("17FE007B04627448 04"))
        assertEquals(ObdPids.VehicleMode.CHARGING_DC, ObdPids.parseVehicleMode("17FE007B04627448 06"))
    }

    @Test
    fun `parses coolant pump duty cycle as percent`() {
        // 0x4B = 75 %
        assertEquals(75f, ObdPids.parseCoolantPump("17FE007B0462743B4B")!!, delta)
    }

    @Test
    fun `parses coolant in_out temperatures with WW XX YY ZZ layout`() {
        // out = (0x0860/64) = 33.5 °C, in = (0x0810/64) = 32.25 °C
        val (inlet, outlet) = ObdPids.parseCoolantTemps("17FE007B0762189D0860 0810")
        assertEquals(32.25f, inlet!!, delta)
        assertEquals(33.5f, outlet!!, delta)
    }

    // ── Cell voltage helpers ────────────────────────────────────────────────

    @Test
    fun `cellVoltPid builds correct DID for given cell index`() {
        assertEquals("221E40", ObdPids.cellVoltPid(1))
        assertEquals("221E41", ObdPids.cellVoltPid(2))
        assertEquals("221EAB", ObdPids.cellVoltPid(108))
    }

    @Test
    fun `parses cell voltage as integer millivolts`() {
        // raw 0xA8C = 2700, volts = 2700/1000 + 1 = 3.7 V → 3700 mV
        assertEquals(3700, ObdPids.parseCellVoltMv("17FE007B05621E400A8C"))
    }

    @Test
    fun `parses cell index from 1E33 third byte`() {
        // Payload: 4 bytes, 3rd byte = idx = 0x2A = 42
        assertEquals(42, ObdPids.parseCellVoltIndex("17FE007B07621E3300002A00"))
    }

    // ── Energy content (MEC / EC) ──────────────────────────────────────────

    @Test
    fun `parses MEC from 4-byte Wh response near pack nominal`() {
        // 77_000 Wh = 0x00012CC8 → 77.0 kWh
        val resp = "17FE001008622AB200012CC8"
        assertEquals(77.0f, ObdPids.parseEnergyKwh(resp)!!, 0.01f)
    }

    @Test
    fun `parses EC from 4-byte Wh response`() {
        // 35_000 Wh = 0x000088B8 → 35.0 kWh
        val resp = "17FE001008622AB8000088B8"
        assertEquals(35.0f, ObdPids.parseEnergyKwh(resp)!!, 0.01f)
    }

    @Test
    fun `rejects implausible energy values`() {
        // 500_000 Wh = 0x0007A120 → 500 kWh, outside the 10–120 kWh sanity band
        assertNull(ObdPids.parseEnergyKwh("17FE001008622AB20007A120"))
    }

    // ── 12V via EM module (DID 2AF7) ───────────────────────────────────────

    @Test
    fun `parses 12V voltage from EM module 2AF7`() {
        // raw 8400 = 0x20D0; 8400/1024 + 4.26 = 8.203 + 4.26 = 12.46 V
        val v = ObdPids.parse12vVoltageEm("17FE001006622AF7 20D0")!!
        assertEquals(12.46f, v, 0.02f)
    }

    // ── Legacy OBD-II 0x42 ──────────────────────────────────────────────────

    @Test
    fun `parses 12V system voltage via OBD-II mode 01`() {
        assertEquals(12.5f, ObdPids.parse12vVoltage("7E03414230D4")!!, 0.001f)
    }

    @Test
    fun `parses 12V system voltage from 29-bit functional broadcast`() {
        assertEquals(12.5f, ObdPids.parse12vVoltage("18DAF10504414230D4")!!, 0.001f)
    }

    // ── ISO-TP framing: single-frame, multi-frame, pending NRC ──────────────

    @Test
    fun `reassembles MEB multi-frame response skipping leading pending NRC`() {
        val response = "17FE007B037F2278\r17FE007B100862028CFA0000\r17FE007B210000AAAAAAAAAAAA\r>"
        assertEquals(100.0f, ObdPids.parseSocBms(response)!!, delta)
    }

    @Test
    fun `reassembles MEB multi-frame response without leading pending NRC`() {
        val response = "17FE007B100862028CFA0000\r17FE007B21000000AAAAAA\r>"
        assertEquals(100.0f, ObdPids.parseSocBms(response)!!, delta)
    }

    // ── ECU switching commands ──────────────────────────────────────────────

    @Test
    fun `ecuSwitchCommands re-sets ATSH ATCRA ATFCSH STCFCPA in order`() {
        val cmds = ObdPids.ecuSwitchCommands(ObdPids.ECU_EM)
        assertEquals(listOf(
            "ATSHFC0010",
            "ATCRA17FE0010",
            "ATFCSH17FC0010",
            "STCFCPA17FC0010,17FE0010"
        ), cmds)
    }

    @Test
    fun `ECU_BMS and ECU_EM target different request IDs`() {
        assertNotNull(ObdPids.ECU_BMS)
        assertNotNull(ObdPids.ECU_EM)
        assertTrue(ObdPids.ECU_BMS.fullRequestId != ObdPids.ECU_EM.fullRequestId)
    }

    // ── Robustness ───────────────────────────────────────────────────────────

    @Test
    fun `returns null when the response has no parseable data`() {
        assertNull(ObdPids.parseSocBms("NO DATA"))
        assertNull(ObdPids.parsePackTemp(""))
        assertNull(ObdPids.parse12vVoltage("SEARCHING..."))
        assertNull(ObdPids.parsePackVoltage("17FE007B037F2231"))   // NRC 0x31
        assertNull(ObdPids.parseEnergyKwh("17FE001003 7F2231"))
        assertEquals(ObdPids.VehicleMode.UNKNOWN, ObdPids.parseVehicleMode("NO DATA"))
    }
}
