package homer.app.executors

import homer.app.infra.SecretRedactor

/**
 * What [Process] asks of the question registry as its run starts working: a callback token for the
 * run, registered with the run's secret screen. Implemented by `QuestionsService`. See ADR-0083.
 */
interface QuestionRegistration {
    /**
     * Mints and stores a callback token for [processId]. [secretRedactor] receives the minted token and
     * returns the run's secret screen (its own token included).
     */
    suspend fun registerProcess(
        processId: Int,
        agentName: String,
        secretRedactor: (callbackToken: String) -> SecretRedactor = { SecretRedactor.NONE },
    ): String
}
