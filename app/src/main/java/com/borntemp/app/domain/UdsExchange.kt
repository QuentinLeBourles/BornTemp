package com.borntemp.app.domain

/**
 * Outcome of one acquisition, shared by the UDS trace (phase 1) and every
 * [Reading] (phase 2). NOT_SUPPORTED is never produced by the classifier: it
 * marks a signal the app chose not to query (muted after repeated failures,
 * or disabled in the settings).
 */
enum class ReadStatus { OK, TIMEOUT, NRC, PARSE_ERROR, NOT_SUPPORTED }

/**
 * Classified ELM327 reply. [detail] is a stable token for the trace:
 * the NRC name, NO_DATA / ADAPTER_TIMEOUT / RESPONSE_PENDING, or the raw
 * ELM error text.
 */
data class UdsResult(val status: ReadStatus, val nrc: Int?, val detail: String?)

/**
 * Classify the raw reply to [command]. Pure — the transport layer measures
 * latency, this only reads the text.
 *
 * Order matters: a positive response for the requested DID wins over any
 * "7F22" that happens to sit inside its data bytes.
 */
fun classifyUdsResponse(command: String, raw: String?): UdsResult {
    val text = raw?.replace(">", "")?.trim().orEmpty()
    if (text.isEmpty()) return UdsResult(ReadStatus.TIMEOUT, null, "ADAPTER_TIMEOUT")
    val upper = text.uppercase()

    if (command.startsWith("AT", ignoreCase = true) || command.startsWith("ST", ignoreCase = true)) {
        return if (upper == "?") UdsResult(ReadStatus.PARSE_ERROR, null, "?")
        else UdsResult(ReadStatus.OK, null, null)
    }

    if ("NO DATA" in upper) return UdsResult(ReadStatus.TIMEOUT, null, "NO_DATA")

    val clean = upper.replace("\\s".toRegex(), "")
    val service = command.take(2).uppercase()
    val positiveSid = (service.toIntOrNull(16) ?: return UdsResult(ReadStatus.PARSE_ERROR, null, text))
        .plus(0x40).toString(16).uppercase().padStart(2, '0')
    val echo = positiveSid + command.drop(2).uppercase()
    if (echo in clean) return UdsResult(ReadStatus.OK, null, null)

    Regex("7F$service([0-9A-F]{2})").findAll(clean).map { it.groupValues[1].toInt(16) }
        .firstOrNull { it != NRC_RESPONSE_PENDING }
        ?.let { return UdsResult(ReadStatus.NRC, it, nrcName(it)) }
    if ("7F${service}78" in clean) return UdsResult(ReadStatus.TIMEOUT, null, "RESPONSE_PENDING")

    return UdsResult(ReadStatus.PARSE_ERROR, null, text.take(40))
}

private const val NRC_RESPONSE_PENDING = 0x78

/** ISO 14229-1 negative response codes seen, or plausible, on MEB ECUs. */
fun nrcName(code: Int): String = when (code) {
    0x10 -> "generalReject"
    0x11 -> "serviceNotSupported"
    0x12 -> "subFunctionNotSupported"
    0x13 -> "incorrectMessageLength"
    0x14 -> "responseTooLong"
    0x22 -> "conditionsNotCorrect"
    0x24 -> "requestSequenceError"
    0x31 -> "requestOutOfRange"
    0x33 -> "securityAccessDenied"
    0x7E -> "subFunctionNotSupportedInActiveSession"
    0x7F -> "serviceNotSupportedInActiveSession"
    else -> "nrc_%02X".format(code)
}

/**
 * The CAN frame actually put on the bus for a UDS [command] sent to
 * [requestId], e.g. `17FC007B 03 22 1E 3C`. AT/ST commands never reach the
 * bus and are returned unchanged.
 */
fun udsTxFrame(requestId: String, command: String): String {
    if (command.startsWith("AT", ignoreCase = true) || command.startsWith("ST", ignoreCase = true)) {
        return command
    }
    val bytes = command.uppercase().chunked(2)
    return "$requestId %02X ${bytes.joinToString(" ")}".format(bytes.size)
}
