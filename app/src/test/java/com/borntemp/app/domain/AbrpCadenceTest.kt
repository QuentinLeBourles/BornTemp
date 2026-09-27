package com.borntemp.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbrpCadenceTest {

    @Test
    fun `next send waits out the rest of the period`() {
        assertEquals(700L, abrpNextDelayMs(sendDurationMs = 300L))
        assertEquals(1_000L, abrpNextDelayMs(sendDurationMs = 0L))
    }

    @Test
    fun `a slow send is followed immediately, never with a negative delay`() {
        assertEquals(0L, abrpNextDelayMs(sendDurationMs = 2_500L))
    }

    @Test
    fun `failures are logged once per outage, not every second`() {
        assertTrue(shouldLogAbrpFailure(previousOk = null, ok = false))
        assertTrue(shouldLogAbrpFailure(previousOk = true, ok = false))
        assertFalse(shouldLogAbrpFailure(previousOk = false, ok = false))
        assertFalse(shouldLogAbrpFailure(previousOk = true, ok = true))
    }
}
