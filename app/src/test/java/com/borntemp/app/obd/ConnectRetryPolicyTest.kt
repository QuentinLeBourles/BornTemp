package com.borntemp.app.obd

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectRetryPolicyTest {

    @Test
    fun `succeeds on first attempt without retrying`() = runTest {
        var connectCalls = 0
        val states = mutableListOf<ConnectRetryPolicy.State>()
        val policy = ConnectRetryPolicy(maxAttempts = 5, delayMs = 3000L, delay = { })

        val result = policy.run(
            onState = { states.add(it) },
            connect = { connectCalls++; true }
        )

        assertTrue(result)
        assertEquals(1, connectCalls)
        assertEquals(
            listOf(ConnectRetryPolicy.State.Attempting(1, 5), ConnectRetryPolicy.State.Succeeded),
            states
        )
    }

    @Test
    fun `succeeds on third attempt after two failures`() = runTest {
        var connectCalls = 0
        var delayCalls = 0
        val policy = ConnectRetryPolicy(maxAttempts = 5, delayMs = 3000L, delay = { delayCalls++ })

        val result = policy.run(
            onState = { },
            connect = { connectCalls++; connectCalls == 3 }
        )

        assertTrue(result)
        assertEquals(3, connectCalls)
        assertEquals(2, delayCalls)
    }

    @Test
    fun `gives up after five failed attempts`() = runTest {
        var connectCalls = 0
        var delayCalls = 0
        val states = mutableListOf<ConnectRetryPolicy.State>()
        val policy = ConnectRetryPolicy(maxAttempts = 5, delayMs = 3000L, delay = { delayCalls++ })

        val result = policy.run(
            onState = { states.add(it) },
            connect = { connectCalls++; false }
        )

        assertFalse(result)
        assertEquals(5, connectCalls)
        assertEquals(4, delayCalls)
        assertEquals(ConnectRetryPolicy.State.GaveUp, states.last())
    }
}
