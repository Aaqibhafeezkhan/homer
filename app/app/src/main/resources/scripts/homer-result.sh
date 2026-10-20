#!/bin/sh
# homer-result — fetch a large continuation result by reference.
#
# Materialized by Homer into {projectRoot}/.homer/bin (on PATH) for every
# process. Reads HMR_API_URL and HMR_CALLBACK_TOKEN from the environment at
# call time. See AGENT.md "Dispatching another agent" and ADR-0025.
#
# Usage:
#   homer-result --token TOKEN [--out FILE]
#       Fetches the (transformed) result behind a resultRef capability token
#       minted when a fan-out sub's result exceeded the inline cap. Without
#       --out the body is written to stdout; with --out it is written to FILE.
#
# Exit codes: 0 ok, 2 usage or transport error (e.g. invalid/expired token,
# artifact gone, oversized, or Homer unreachable).

set -u

CURL_MAX_TIME=70

die() {
    printf 'homer-result: %s\n' "$1" >&2
    exit 2
}

[ -n "${HMR_API_URL:-}" ] || die "HMR_API_URL is not set"
[ -n "${HMR_CALLBACK_TOKEN:-}" ] || die "HMR_CALLBACK_TOKEN is not set"

TOKEN=""
OUT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --token) [ $# -ge 2 ] || die "--token needs a value"; TOKEN=$2; shift 2 ;;
        --out) [ $# -ge 2 ] || die "--out needs a value"; OUT=$2; shift 2 ;;
        *) die "unknown argument: $1" ;;
    esac
done
[ -n "$TOKEN" ] || die "--token is required"

RESULTS_URL="${HMR_API_URL%/}/api/v1/callback/results/$TOKEN"

if [ -n "$OUT" ]; then
    curl -sS -f --max-time "$CURL_MAX_TIME" "$RESULTS_URL" \
        -H "Authorization: Bearer $HMR_CALLBACK_TOKEN" \
        -o "$OUT" ||
        die "fetch failed (invalid/expired token, artifact gone, oversized, or Homer unreachable)"
else
    curl -sS -f --max-time "$CURL_MAX_TIME" "$RESULTS_URL" \
        -H "Authorization: Bearer $HMR_CALLBACK_TOKEN" ||
        die "fetch failed (invalid/expired token, artifact gone, oversized, or Homer unreachable)"
fi
