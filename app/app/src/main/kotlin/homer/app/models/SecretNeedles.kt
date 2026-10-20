package homer.app.models

/**
 * Shortest secret value the substring screens match against: the artifact-stem screen
 * (`SecretRedactor.ARTIFACT_STEM_MIN_NEEDLE_LENGTH`) and the run-tag guards
 * ([TagValidation.SECRET_NEEDLE_MIN_LENGTH]). A shorter secret ("0", "prod") would match
 * nearly any value and refuse it (§12: availability is in scope). Telemetry and tail
 * redaction do not use it: they screen every non-empty secret.
 */
const val MIN_SCREENED_SECRET_LENGTH = 8
