package io.github.manuelrtsch.sidetrack

import android.os.Process
import android.os.SystemClock

/**
 * Where a cold launch spends its time, in the same rolling log the playback paths use.
 *
 * A cold start is dominated by work that leaves no trace of its own — dex verification, class
 * loading, the Sidekit host read and the first composition — so a `Displayed +4s173ms` from
 * logcat says nothing about which phase to fix. These marks turn that into four numbers: process
 * to Application, Application to the activity, the session build, and the first frame.
 *
 * Cheap enough to keep on: a handful of [SidetrackDebug] lines per launch. Marks land in the
 * debug file log too, so a slow first start can be compared with a warmed one.
 */
object StartupTrace {

    /** Milliseconds since the process was forked, or -1 when the platform cannot say. */
    private fun ageMs(): Long {
        val start = Process.getStartElapsedRealtime()
        if (start <= 0L) return -1L
        return SystemClock.elapsedRealtime() - start
    }

    /** Phases already recorded; a recomposition must not turn one launch into fifty lines. */
    private val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Records [phase] once per process, for marks inside composables. */
    fun once(phase: String) {
        if (seen.add(phase)) mark(phase)
    }

    /** Records [phase] with its distance from process start. */
    fun mark(phase: String) {
        val age = ageMs()
        SidetrackDebug.log(if (age >= 0) "startup $phase at +${age}ms" else "startup $phase")
    }

    /**
     * Records [phase] with the time [block] took, so a phase can be judged on its own cost
     * rather than only on the total. Returns the block's value.
     */
    inline fun <T> measure(phase: String, block: () -> T): T {
        val began = SystemClock.elapsedRealtime()
        val value = block()
        SidetrackDebug.log { "startup $phase took ${SystemClock.elapsedRealtime() - began}ms" }
        return value
    }
}
