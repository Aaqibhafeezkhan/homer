#!/bin/sh
# homer-join — register a parallel fan-out join from a running command.
#
# Materialized by Homer into {projectRoot}/.homer/bin (on PATH) for every
# process. Reads HMR_API_URL and HMR_CALLBACK_TOKEN from the environment at
# call time. See AGENT.md "Dispatching another agent" and ADR-0026.
#
# Usage:
#   homer-join < body.json
#       Reads the request JSON body from stdin and POSTs it to
#       /api/v1/callback/joins. The body has a nested `subs` array, so it is
#       built by the caller (trivial in the LangGraph runtime, fragile in POSIX
#       sh) and piped in. Starts every sub at once and registers ONE join
#       continuation that resumes `continueAgent` a single time once the last
#       sub reaches a terminal state, carrying all results keyed by interrupt
#       id. Every agent involved must opt in with `dispatch.enabled`. Prints the
#       response JSON ({ processIds, continuationId }) on stdout.
#
# Exit codes: 0 ok, 2 usage or transport error (e.g. dead origin process,
# unknown or non-dispatchable agent, Homer unreachable).

set -u

CURL_MAX_TIME=70

die() {
    printf 'homer-join: %s\n' "$1" >&2
    exit 2
}

[ -n "${HMR_API_URL:-}" ] || die "HMR_API_URL is not set"
[ -n "${HMR_CALLBACK_TOKEN:-}" ] || die "HMR_CALLBACK_TOKEN is not set"

JOINS_URL="${HMR_API_URL%/}/api/v1/callback/joins"

body=$(cat)
[ -n "$body" ] || die "no request body on stdin"

response=$(
    curl -sS -f --max-time "$CURL_MAX_TIME" -X POST "$JOINS_URL" \
        -H "Authorization: Bearer $HMR_CALLBACK_TOKEN" \
        -H "Content-Type: application/json" \
        --data "$body"
) || die "join failed (terminal origin process, unknown or non-dispatchable agent, or Homer unreachable)"
printf '%s\n' "$response"
