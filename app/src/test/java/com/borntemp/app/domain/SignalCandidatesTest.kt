package com.borntemp.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalCandidatesTest {

    @Test
    fun `catalogue ids are unique and every target is covered`() {
        val ids = SignalCandidates.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val targets = SignalCandidates.ALL.map { it.target }.toSet()
        assertEquals(CandidateTarget.entries.toSet(), targets)
    }

    @Test
    fun `scans are off by default, cheap single reads are on`() {
        SignalCandidates.ALL.filter { it.cadence == Cadence.ON_STATE_CHANGE }
            .forEach { assertFalse(it.id, it.enabledByDefault) }
        assertTrue(SignalCandidates.byId("v12_dcdc_465d")!!.enabledByDefault)
        assertTrue(SignalCandidates.byId("v12_atrv")!!.enabledByDefault)
    }

    @Test
    fun `scan ranges expand to one read per DID`() {
        val scan = SignalCandidates.byId("scan_bms_1e00")!!
        assertEquals(0x50, scan.commands.size)
        assertEquals("221E00", scan.commands.first())
        assertEquals("221E4F", scan.commands.last())
    }

    @Test
    fun `due candidates follow cadence and the enabled set`() {
        val enabled = setOf("i_hv_1e3d", "v12_atrv", "scan_bms_1e00")
        val fast = SignalCandidates.due(enabled, slowTick = false, stateChanged = false).map { it.id }
        assertEquals(listOf("i_hv_1e3d"), fast)

        val slow = SignalCandidates.due(enabled, slowTick = true, stateChanged = false).map { it.id }
        assertTrue("v12_atrv" in slow)
        assertFalse("scan_bms_1e00" in slow)

        val change = SignalCandidates.due(enabled, slowTick = false, stateChanged = true).map { it.id }
        assertTrue("scan_bms_1e00" in change)
    }

    @Test
    fun `disabled candidates are never due`() {
        assertTrue(SignalCandidates.due(emptySet(), slowTick = true, stateChanged = true).isEmpty())
    }

    @Test
    fun `default enabled set is the catalogue defaults`() {
        assertEquals(
            SignalCandidates.ALL.filter { it.enabledByDefault }.map { it.id }.toSet(),
            SignalCandidates.defaultEnabled()
        )
    }
}
