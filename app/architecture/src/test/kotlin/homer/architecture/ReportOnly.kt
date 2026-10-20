package homer.architecture

/**
 * JUnit tag of a rule that still has findings. Tagged rules run in `architectureReportOnly`, which never
 * fails the build, and are reported by the warn-mode check `architecture-backend-pending`, listed only while
 * a rule carries the tag; a rule is promoted to the required suite by deleting its tag once it reports 0
 * (ADR-0082). No rule carries it since ADR-0083 step 3.
 */
internal const val REPORT_ONLY = "report-only"
