package homer.app.models

/**
 * True when this block runs Claude with the permission gate off while `allowedTools`
 * is its only tool setting: the step reads as scoped, but `allowedTools` only
 * pre-approves — every other tool still runs, subagents included. The real
 * containment is `tools` or `disallowedTools`.
 *
 * The bypass test mirrors the argv precedence in `ClaudeArgvBuilder`: a
 * `permissionMode` suppresses the skip flag, so the flag alone does not bypass
 * when a mode is set. See ADR-0017.
 */
fun ClaudeCmdOptions.bypassesPermissionsWithoutToolLimit(): Boolean =
    bypassesPermissions && allowedTools != null && tools == null && disallowedTools == null

/** Whether the permission gate is off: `bypassPermissions`, or the skip flag with no mode set. */
private val ClaudeCmdOptions.bypassesPermissions: Boolean
    get() = permissionMode == "bypassPermissions" || (permissionMode == null && dangerouslySkipPermissions)
