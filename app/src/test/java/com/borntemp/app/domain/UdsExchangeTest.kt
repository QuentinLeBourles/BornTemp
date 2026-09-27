package com.borntemp.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Frames below are copied verbatim from field captures (2026-09-26). */
class UdsExchangeTest {

    @Test
    fun `positive single frame is OK`() {
        val r = classifyUdsResponse("221E3B", "17FE007B05621E3B05B8")
        assertEquals(ReadStatus.OK, r.status)
        assertNull(r.nrc)
    }

    @Test
    fun `positive multi frame is OK`() {
        val r = classifyUdsResponse(
            "221E32",
            "17FE007B1013621E3200BDE7 17FE007B21E3FF443032044A 17FE007B225E9FFBEC5E99AA"
        )
        assertEquals(ReadStatus.OK, r.status)
    }

    @Test
    fun `negative response carries the NRC code and name`() {
        val r = classifyUdsResponse("222AB2", "17FE007B037F2231")
        assertEquals(ReadStatus.NRC, r.status)
        assertEquals(0x31, r.nrc)
        assertEquals("requestOutOfRange", r.detail)
    }

    @Test
    fun `NO DATA is an ECU timeout, distinct from an adapter timeout`() {
        val ecu = classifyUdsResponse("222AB2", "NO DATA")
        assertEquals(ReadStatus.TIMEOUT, ecu.status)
        assertEquals("NO_DATA", ecu.detail)

        val adapter = classifyUdsResponse("222AB2", null)
        assertEquals(ReadStatus.TIMEOUT, adapter.status)
        assertEquals("ADAPTER_TIMEOUT", adapter.detail)
        assertEquals("ADAPTER_TIMEOUT", classifyUdsResponse("222AB2", "  ").detail)
    }

    @Test
    fun `positive response for another DID is a parse error`() {
        // A late reply from the previous request landing on this one.
        val r = classifyUdsResponse("221E3C", "17FE007B05621E3B05B8")
        assertEquals(ReadStatus.PARSE_ERROR, r.status)
    }

    @Test
    fun `ELM error strings are parse errors with the raw text as detail`() {
        val r = classifyUdsResponse("221E3C", "CAN ERROR")
        assertEquals(ReadStatus.PARSE_ERROR, r.status)
        assertEquals("CAN ERROR", r.detail)
    }

    @Test
    fun `response pending alone is a timeout, not an NRC`() {
        val r = classifyUdsResponse("221E3C", "17FE007B037F2278")
        assertEquals(ReadStatus.TIMEOUT, r.status)
        assertEquals("RESPONSE_PENDING", r.detail)
    }

    @Test
    fun `AT commands are OK on OK or a value, parse error on question mark`() {
        assertEquals(ReadStatus.OK, classifyUdsResponse("ATCRA17FE007B", "OK").status)
        assertEquals(ReadStatus.OK, classifyUdsResponse("ATRV", "14.0V").status)
        assertEquals(ReadStatus.PARSE_ERROR, classifyUdsResponse("ATFOO", "?").status)
    }

    @Test
    fun `tx frame renders the full CAN request`() {
        assertEquals("17FC007B 03 22 1E 3C", udsTxFrame("17FC007B", "221E3C"))
        assertEquals("17FC007B 02 10 03", udsTxFrame("17FC007B", "1003"))
        assertEquals("ATRV", udsTxFrame("17FC007B", "ATRV"))
    }
}
