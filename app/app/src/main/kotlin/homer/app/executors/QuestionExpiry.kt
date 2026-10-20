package homer.app.executors

/**
 * What [ProcessExecutor] tells the question registry when its run ends: the run's open questions can
 * never be answered any more. Implemented by `QuestionsService`. See ADR-0083.
 */
interface QuestionExpiry {
    /** Closes [processId]'s question registration and expires its OPEN questions, resuming every waiter. */
    suspend fun expireForProcess(processId: Int)
}
