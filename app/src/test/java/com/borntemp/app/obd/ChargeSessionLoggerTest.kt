package com.borntemp.app.obd

import com.borntemp.app.viewmodel.ChargeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChargeSessionLoggerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sessionsCsv(dir: File) = File(dir, "sessions.csv").readLines()
    private fun samplesCsv(dir: File) = File(dir, "samples.csv").readLines()

    @Test
    fun `unknown charge state is a no-op`() {
        val dir = tempFolder.newFolder()
        val logger = ChargeSessionLogger(dir)

        logger.recordSample(1_000L, 50f, 20f, 10f, 5f, 400f, ChargeState.UNKNOWN)

        assertEquals(1, sessionsCsv(dir).size) // header only
        assertEquals(1, samplesCsv(dir).size)  // header only
    }

    @Test
    fun `null soc or temp is a no-op`() {
        val dir = tempFolder.newFolder()
        val logger = ChargeSessionLogger(dir)

        logger.recordSample(1_000L, null, 20f, 10f, 5f, 400f, ChargeState.NOT_CHARGING)
        logger.recordSample(2_000L, 50f, null, 10f, 5f, 400f, ChargeState.NOT_CHARGING)

        assertEquals(1, samplesCsv(dir).size)
    }

    @Test
    fun `first sample opens a segment and writes one samples row`() {
        val dir = tempFolder.newFolder()
        val logger = ChargeSessionLogger(dir)

        logger.recordSample(1_000L, 50f, 20f, 10f, 5f, 400f, ChargeState.NOT_CHARGING)

        val samples = samplesCsv(dir)
        assertEquals(2, samples.size) // header + 1 row
        assertTrue(samples[1].startsWith("19700101-000001-0,1000,50.0,20.0,10.0,5.0,400.0,"))
    }

    @Test
    fun `charger type transition closes old segment and opens a new one`() {
        val dir = tempFolder.newFolder()
        val logger = ChargeSessionLogger(dir)

        logger.recordSample(1_000L, 50f, 20f, null, null, null, ChargeState.NOT_CHARGING)
        logger.recordSample(2_000L, 55f, 21f, 80f, 200f, 400f, ChargeState.DC_CHARGING)

        val sessions = sessionsCsv(dir)
        assertEquals(2, sessions.size) // header + 1 closed segment
        assertTrue(sessions[1].contains(",UNKNOWN,20.0,unknown"))

        val samples = samplesCsv(dir)
        assertEquals(3, samples.size) // header + 2 rows across 2 segments
        assertTrue(samples[1].contains("19700101-000001-0"))
        assertTrue(samples[2].contains("19700101-000001-1"))
    }

    @Test
    fun `charging segment ending back to not-charging is charge_complete`() {
        val dir = tempFolder.newFolder()
        val logger = ChargeSessionLogger(dir)

        logger.recordSample(1_000L, 50f, 20f, 80f, 200f, 400f, ChargeState.DC_CHARGING)
        logger.recordSample(2_000L, 60f, 22f, null, null, null, ChargeState.NOT_CHARGING)

        val sessions = sessionsCsv(dir)
        assertTrue(sessions[1].contains(",DC,20.0,charge_complete"))
    }

    @Test
    fun `endConnection closes the open segment as presence_loss`() {
        val dir = tempFolder.newFolder()
        val logger = ChargeSessionLogger(dir)

        logger.recordSample(1_000L, 50f, 20f, null, null, null, ChargeState.NOT_CHARGING)
        logger.endConnection()

        val sessions = sessionsCsv(dir)
        assertEquals(2, sessions.size)
        assertTrue(sessions[1].contains(",presence_loss"))
    }

    @Test
    fun `endConnection with no open segment is a no-op`() {
        val dir = tempFolder.newFolder()
        val logger = ChargeSessionLogger(dir)

        logger.endConnection()

        assertEquals(1, sessionsCsv(dir).size)
    }

    @Test
    fun `session id restarts its segment counter after endConnection`() {
        val dir = tempFolder.newFolder()
        val logger = ChargeSessionLogger(dir)

        logger.recordSample(1_000L, 50f, 20f, null, null, null, ChargeState.NOT_CHARGING)
        logger.endConnection()
        logger.recordSample(3_000L, 51f, 20f, null, null, null, ChargeState.NOT_CHARGING)

        val samples = samplesCsv(dir)
        assertTrue(samples[1].contains("-0,"))
        assertTrue(samples[2].contains("-0,")) // fresh connection, counter reset
    }

    @Test
    fun `header is written once, not duplicated across instances pointed at the same dir`() {
        val dir = tempFolder.newFolder()
        ChargeSessionLogger(dir).recordSample(1_000L, 50f, 20f, null, null, null, ChargeState.NOT_CHARGING)

        // Simulate an app restart: a fresh logger instance, same directory.
        val logger2 = ChargeSessionLogger(dir)
        logger2.recordSample(2_000L, 51f, 20f, null, null, null, ChargeState.NOT_CHARGING)

        val samples = samplesCsv(dir)
        assertEquals(1, samples.count { it.startsWith("session_id,") })
    }

    @Test
    fun `null outputDir makes every call a harmless no-op`() {
        val logger = ChargeSessionLogger(null)
        logger.recordSample(1_000L, 50f, 20f, null, null, null, ChargeState.NOT_CHARGING)
        logger.endConnection()
        // No assertion beyond "doesn't throw" — there's no directory to inspect.
    }
}
