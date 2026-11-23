package io.github.manuelrtsch.sidetrack

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Starts, dedupes and cancels the work identified by [TaskKey].
 *
 * It is the one owner of the jobs; controllers keep their own state updates and error handling
 * inside the block, so neither the guards nor the notices move. What moves is the bookkeeping:
 * [SidetrackUiState.runningTasks] is maintained here, and nothing else writes it.
 *
 * A key can be superseded. [restart] cancels the previous job and starts a new one under the same
 * key without dropping the key from the running set, because the UI should keep showing "waiting"
 * across a retry rather than flicker idle. The owner token makes the superseded job's `finally`
 * leave the newer one's bookkeeping alone.
 *
 * A failure is recorded here only for work that has no cached entry to remember it: a paging footer
 * whose page kept its rows and its `hasMore`. First-page failures live in the cache the screen
 * retries from instead, so they are not duplicated into the record.
 */
internal class TaskRegistry(
    private val store: UiStateStore,
    private val scope: CoroutineScope
) {

    private val jobs = mutableMapOf<TaskKey, Job>()
    private val owners = mutableMapOf<TaskKey, Any>()

    fun isRunning(key: TaskKey): Boolean = jobs[key]?.isActive == true

    /** Starts [block] unless [key] is already running. */
    fun start(key: TaskKey, block: suspend () -> Unit): Job? {
        if (isRunning(key)) return null
        return launch(key, block)
    }

    /** Supersedes whatever runs under [key], then starts [block] under it. */
    fun restart(key: TaskKey, block: suspend () -> Unit): Job {
        jobs.remove(key)?.cancel()
        return launch(key, block)
    }

    fun cancel(key: TaskKey) {
        jobs.remove(key)?.cancel()
        owners.remove(key)
        store.update { it.copy(runningTasks = it.runningTasks - key, taskFailures = it.taskFailures - key) }
    }

    /** Records why [key]'s last run failed, for a surface that has nowhere else to keep it. */
    fun fail(key: TaskKey, message: String) {
        store.update { it.copy(taskFailures = it.taskFailures + (key to message)) }
    }

    fun cancelWhere(predicate: (TaskKey) -> Boolean) {
        jobs.keys.filter(predicate).toList().forEach(::cancel)
    }

    fun cancelAll() = cancelWhere { true }

    private fun launch(key: TaskKey, block: suspend () -> Unit): Job {
        val owner = Any()
        owners[key] = owner
        // Starting again retires the previous failure: the retry row becomes the spinner now.
        store.update { it.copy(runningTasks = it.runningTasks + key, taskFailures = it.taskFailures - key) }
        // LAZY so the job is recorded before its block can run: on a main-immediate dispatcher a
        // block without a suspension point would otherwise finish before `jobs[key]` is assigned.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                // A restarted key belongs to the newer job; only its owner clears the record.
                if (owners[key] === owner) {
                    owners.remove(key)
                    jobs.remove(key)
                    store.update { it.copy(runningTasks = it.runningTasks - key) }
                }
            }
        }
        jobs[key] = job
        job.start()
        return job
    }
}
