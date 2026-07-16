package com.borntemp.app.obd

/**
 * Bounded retry for `ObdForegroundService`'s connect attempt: up to
 * [maxAttempts] tries, waiting [delayMs] between failures, surfaced as a
 * [State] the service renders into its notification. [delay] is injected
 * so tests can run the whole attempt sequence instantly.
 */
class ConnectRetryPolicy(
    private val maxAttempts: Int = 5,
    private val delayMs: Long = 3000L,
    private val delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
) {
    sealed class State {
        data class Attempting(val attempt: Int, val maxAttempts: Int) : State()
        object Succeeded : State()
        object GaveUp : State()
    }

    /**
     * Runs [connect] up to [maxAttempts] times, invoking [onState] before
     * each attempt and once more with the final outcome. Returns true iff
     * [connect] eventually returned true.
     */
    suspend fun run(onState: (State) -> Unit, connect: suspend () -> Boolean): Boolean {
        for (attempt in 1..maxAttempts) {
            onState(State.Attempting(attempt, maxAttempts))
            if (connect()) {
                onState(State.Succeeded)
                return true
            }
            if (attempt < maxAttempts) delay(delayMs)
        }
        onState(State.GaveUp)
        return false
    }
}
