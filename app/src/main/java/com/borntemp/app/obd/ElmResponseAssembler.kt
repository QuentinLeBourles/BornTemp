package com.borntemp.app.obd

/**
 * Reassembles ELM327 responses that arrive as a stream of BLE notification
 * chunks.
 *
 * Over Bluetooth Classic the adapter output could be read one byte at a time,
 * but the OBDLink CX pushes its output over the FFF1 notify characteristic in
 * MTU-sized fragments. A single logical response can therefore be split across
 * several notifications, and is only complete once the ELM327 '>' prompt byte
 * has been received. This class buffers fragments and surfaces the full
 * response when the prompt arrives — the framing rule the rest of the OBD
 * layer relies on.
 *
 * Not thread-safe: callers serialise access (the GATT manager guards it with a
 * lock shared with the command/response state).
 */
class ElmResponseAssembler(private val prompt: Char = '>') {

    private val buffer = StringBuilder()

    /**
     * Feed one notification chunk.
     *
     * @return the complete response (everything buffered up to and including
     *   the prompt) once the prompt has been seen, otherwise `null` while the
     *   response is still arriving. After a complete response is returned the
     *   buffer is cleared, ready for the next command.
     */
    fun append(chunk: String): String? {
        buffer.append(chunk)
        if (buffer.indexOf(prompt.toString()) < 0) return null
        val full = buffer.toString()
        buffer.setLength(0)
        return full
    }

    /** Discard any partially-received data before issuing a new command. */
    fun reset() {
        buffer.setLength(0)
    }
}
