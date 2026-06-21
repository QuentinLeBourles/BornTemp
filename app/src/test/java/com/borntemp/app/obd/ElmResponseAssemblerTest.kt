package com.borntemp.app.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests the BLE notification framing introduced by the move from Bluetooth
 * Classic (byte-stream) to BLE GATT (chunked notifications). A response is only
 * complete once the ELM327 '>' prompt is received, possibly across several
 * notification fragments.
 */
class ElmResponseAssemblerTest {

    @Test
    fun `returns full response when prompt arrives in a single chunk`() {
        val assembler = ElmResponseAssembler()
        assertEquals("7E0462F1A33C\r\r>", assembler.append("7E0462F1A33C\r\r>"))
    }

    @Test
    fun `returns null while no prompt has been seen`() {
        val assembler = ElmResponseAssembler()
        assertNull(assembler.append("7E0462"))
        assertNull(assembler.append("F1A3"))
    }

    @Test
    fun `reassembles a response split across several notifications`() {
        val assembler = ElmResponseAssembler()
        assertNull(assembler.append("ELM327 "))
        assertNull(assembler.append("v1.5"))
        assertNull(assembler.append("\r\r"))
        assertEquals("ELM327 v1.5\r\r>", assembler.append(">"))
    }

    @Test
    fun `starts fresh after a completed response`() {
        val assembler = ElmResponseAssembler()
        assertEquals("OK\r>", assembler.append("OK\r>"))
        // The next command must not carry over the previous response.
        assertNull(assembler.append("41 0C"))
        assertEquals("41 0C 1A F8\r>", assembler.append(" 1A F8\r>"))
    }

    @Test
    fun `reset discards partially buffered data`() {
        val assembler = ElmResponseAssembler()
        assembler.append("stale partial data, no prompt yet")
        assembler.reset()
        assertEquals("fresh\r>", assembler.append("fresh\r>"))
    }

    @Test
    fun `only the configured prompt character completes a response`() {
        val assembler = ElmResponseAssembler(prompt = '#')
        assertNull(assembler.append("data>still"))   // '>' must be ignored here
        assertEquals("data>still#", assembler.append("#"))
    }
}
