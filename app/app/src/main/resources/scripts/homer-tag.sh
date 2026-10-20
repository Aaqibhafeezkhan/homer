#!/bin/sh
# homer-tag — declare a subject tag against the running command's own run.
#
# Materialized by Homer into {projectRoot}/.homer/bin (on PATH) for every
# process. Reads HMR_API_URL and HMR_CALLBACK_TOKEN from the environment at
# call time. The run is identified by the callback token alone — there is no
# run id to pass. See AGENT.md "Run tags" and ADR-0060.
#
# Usage:
#   homer-tag KEY:VALUE
#       Adds the `KEY:VALUE` tag to this run. KEY must be one the agent
#       declared under `tags.runtime`; VALUE must match ^[A-Za-z0-9._-]{1,128}$.
#       Re-declaring the same tag is idempotent. Returns immediately.
#
# Exit codes: 0 ok, 2 usage or transport error, or the run/tag was rejected
# (terminal run, undeclared key, bad value, or the per-run tag ceiling).

set -u

CURL_MAX_TIME=70

die() {
    printf 'homer-tag: %s\n' "$1" >&2
    exit 2
}

[ -n "${HMR_API_URL:-}" ] || die "HMR_API_URL is not set"
[ -n "${HMR_CALLBACK_TOKEN:-}" ] || die "HMR_CALLBACK_TOKEN is not set"

TAGS_URL="${HMR_API_URL%/}/api/v1/callback/tags"

# Escapes a value for embedding in a JSON string literal. Char-by-char to
# stay portable: gsub replacement-escape semantics differ across awks.
json_escape() {
    printf '%s' "$1" | awk '
        NR > 1 { printf "%s", "\\n" }
        {
            n = length($0)
            for (j = 1; j <= n; j++) {
                c = substr($0, j, 1)
                if (c == "\\") printf "%s", "\\\\"
                else if (c == "\"") printf "%s", "\\\""
                else if (c == "\t") printf "%s", "\\t"
                else if (c == "\r") printf "%s", "\\r"
                else printf "%s", c
            }
        }'
}

# Prints the unescaped string value of field $2 in JSON $1, if present, so a
# server rejection surfaces its own reason rather than a generic message.
json_str_field() {
    printf '%s' "$1" | awk -v field="$2" '
        BEGIN { found = 0 }
        !found {
            pat = "\"" field "\":"
            i = index($0, pat)
            if (i == 0) next
            s = substr($0, i + length(pat))
            while (substr(s, 1, 1) == " ") s = substr(s, 2)
            if (substr(s, 1, 1) != "\"") next
            found = 1
            s = substr(s, 2)
            while (length(s) > 0) {
                c = substr(s, 1, 1)
                if (c == "\\") {
                    d = substr(s, 2, 1)
                    if (d == "n") printf "\n"
                    else printf "%s", d
                    s = substr(s, 3)
                } else if (c == "\"") {
                    break
                } else {
                    printf "%s", c
                    s = substr(s, 2)
                }
            }
        }
        END { exit !found }'
}

[ $# -eq 1 ] || die "usage: homer-tag KEY:VALUE"
# Split on the first ':' — the value charset is colon-free, so a well-formed
# argument has exactly one separator and everything after it is the value.
case "$1" in
    *:*) ;;
    *) die "usage: homer-tag KEY:VALUE" ;;
esac
KEY=${1%%:*}
VALUE=${1#*:}
[ -n "$KEY" ] || die "KEY must not be empty"
[ -n "$VALUE" ] || die "VALUE must not be empty"

payload="{\"tag\":\"$(json_escape "$KEY"):$(json_escape "$VALUE")\"}"

# Capture body and HTTP status separately (no -f): a 4xx/409 carries a reason
# in { msg } that the author needs to see, which -f would discard.
resp=$(
    curl -sS --max-time "$CURL_MAX_TIME" -X POST "$TAGS_URL" \
        -H "Authorization: Bearer $HMR_CALLBACK_TOKEN" \
        -H "Content-Type: application/json" \
        --data "$payload" \
        -w '\n%{http_code}'
) || die "tagging failed (Homer unreachable)"

code=$(printf '%s\n' "$resp" | tail -n1)
body=$(printf '%s\n' "$resp" | sed '$d')

case "$code" in
    200) exit 0 ;;
    *)
        reason=$(json_str_field "$body" "msg")
        die "${reason:-request rejected with HTTP $code}"
        ;;
esac
