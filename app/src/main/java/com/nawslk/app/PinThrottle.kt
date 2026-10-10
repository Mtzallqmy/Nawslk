package com.nawslk.app

/** Pure lockout policy; persisted timestamps must use wall-clock time. */
object PinThrottle {
    const val MAX_FAILURES = 5
    const val LOCKOUT_MS = 60_000L
    fun lockedUntil(failures: Int, lastFailureMs: Long): Long =
        if (failures >= MAX_FAILURES) lastFailureMs + LOCKOUT_MS else 0L
    fun canTry(failures: Int, lastFailureMs: Long, nowMs: Long): Boolean =
        nowMs >= lockedUntil(failures, lastFailureMs)
}
