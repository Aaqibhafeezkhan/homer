package homer.app.runners.kubernetes

import homer.app.models.RunnerState

/**
 * Pushes runner lifecycle transitions to the observer
 * (in production: `ProcessManager.setRunnerState`).
 */
fun interface RunnerStateSink {
    suspend fun update(
        processId: Int,
        update: (RunnerState?) -> RunnerState,
    )

    companion object {
        val NOOP: RunnerStateSink = RunnerStateSink { _, _ -> }
    }
}
