package homer.app.models

/**
 * Shape rules for `runnerAffinity`. [RunnerAffinity.group] is a free label used
 * only as one half of the in-memory affinity key — never in a path, argv or
 * query — so it has a tag's shape; [RunnerAffinity.waitSec] is bounded so a
 * preference cannot outlive a sane wait (the run's claim TTL clamps it further
 * at use). Rejected values are never echoed back. See ADR-0099.
 */
object RunnerAffinityValidation {
    /** Hard ceiling on `waitSec`; a run's claim TTL bounds the wait further. */
    const val MAX_WAIT_SEC = 86_400

    /**
     * Validates [affinity]. [owner] prefixes every message (e.g. `Agent 'deploy'`)
     * so a config-load failure names the offending block.
     */
    fun validate(
        owner: String,
        affinity: RunnerAffinity,
    ) {
        require(RunnerTagValidation.PATTERN.matches(affinity.group)) {
            "$owner: runnerAffinity.group must match ${RunnerTagValidation.PATTERN}"
        }
        require(affinity.waitSec in 1..MAX_WAIT_SEC) {
            "$owner: runnerAffinity.waitSec must be in 1..$MAX_WAIT_SEC"
        }
    }
}
