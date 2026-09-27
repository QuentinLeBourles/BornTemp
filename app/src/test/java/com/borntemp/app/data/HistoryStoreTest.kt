package com.borntemp.app.data

import com.borntemp.app.domain.ChargeRecord
import com.borntemp.app.domain.ConnectionRecord
import com.borntemp.app.domain.CounterSnapshot
import com.borntemp.app.domain.LastKnown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun conn(t: Long) = ConnectionRecord(
        CounterSnapshot(t, 50f, 8000f, 7600f), CounterSnapshot(t + 1000, 49f, 8000f, 7601f)
    )

    private fun charge(t: Long) = ChargeRecord(
        t, t + 1000, true, 30f, 60f, 22f, null, 90f, 120f, 25f, 33f, 34f, null
    )

    @Test
    fun `closed connections are appended and read back in order`() {
        val store = HistoryStore(tmp.root)
        store.updateOpenConnection(conn(1L)); store.closeConnection()
        store.updateOpenConnection(conn(2L)); store.closeConnection()
        assertEquals(listOf(1L, 2L), HistoryStore(tmp.root).connections().map { it.start.t })
    }

    @Test
    fun `an open record left by a killed process is recovered on the next start`() {
        HistoryStore(tmp.root).apply {
            updateOpenConnection(conn(1L))
            updateOpenCharge(charge(5L))
            // process dies here: no close
        }
        val next = HistoryStore(tmp.root)
        assertEquals(listOf(1L), next.connections().map { it.start.t })
        assertEquals(listOf(5L), next.charges().map { it.startMs })
        // Recovery is one-shot: a second start must not duplicate them.
        val third = HistoryStore(tmp.root)
        assertEquals(1, third.connections().size)
        assertEquals(1, third.charges().size)
    }

    @Test
    fun `updating an open record replaces it rather than appending`() {
        val store = HistoryStore(tmp.root)
        store.updateOpenCharge(charge(5L))
        store.updateOpenCharge(charge(5L).copy(socEnd = 80f))
        store.closeCharge()
        assertEquals(listOf(80f), store.charges().map { it.socEnd })
    }

    @Test
    fun `closing with nothing open is a no-op`() {
        val store = HistoryStore(tmp.root)
        store.closeConnection(); store.closeCharge()
        assertTrue(store.connections().isEmpty())
    }

    @Test
    fun `last known persists and a corrupt line is skipped, not fatal`() {
        val store = HistoryStore(tmp.root)
        assertNull(store.lastKnown())
        store.saveLastKnown(LastKnown(9L, 58f, null, 29f, 29f, 30f, 14.4f, null, 12, 8388f, 7978f))
        assertEquals(58f, HistoryStore(tmp.root).lastKnown()!!.socHmi)

        java.io.File(tmp.root, HistoryStore.CONNECTIONS).appendText("garbage\n")
        store.updateOpenConnection(conn(3L)); store.closeConnection()
        assertEquals(listOf(3L), HistoryStore(tmp.root).connections().map { it.start.t })
    }

    @Test
    fun `charges come back newest first`() {
        val store = HistoryStore(tmp.root)
        for (t in listOf(1L, 3L, 2L)) { store.updateOpenCharge(charge(t)); store.closeCharge() }
        assertEquals(listOf(3L, 2L, 1L), store.charges().map { it.startMs })
    }
}
