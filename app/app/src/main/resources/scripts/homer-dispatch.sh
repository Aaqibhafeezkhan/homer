#!/bin/sh
# homer-dispatch — start another agent immediately from a running command.
#
# Materialized by Homer into {projectRoot}/.homer/bin (on PATH) for every
# process. Reads HMR_API_URL and HMR_CALLBACK_TOKEN from the environment at
# call time. See AGENT.md "Dispatching another agent" and ADR-0022.
#
# Usage:
#   homer-dispatch --agent NAME [--param k=v]...
#       Starts the named agent fire-and-forget (fresh chain; this process is
#       recorded only as the referer). The target must opt in with
#       `dispatch.enabled`. Prints the new process id on stdout and returns
#       immediately.
#
#   homer-dispatch --agent NAME --continue-agent NAME [--continue-thread ID] \
#       [--continue-param k=v]... [--resume-param NAME] [--result-jsonpath PATH] \
#       [--ttl SECONDS] [--param k=v]...
#       As above, plus registers a completion continuation: when the started
#       sub-process reaches a terminal state, --continue-agent is fired with the
#       sub's result under --resume-param (default HMR_LG_RESUME). --param go to
#       the sub; --continue-param go to the continuation run (the resumed
#       parent). Both agents must opt in with `dispatch.enabled`. See ADR-0023.
#
# Exit codes: 0 ok, 2 usage or transport error (e.g. dead origin process,
# unknown or non-dispatchable agent, Homer unreachable).

set -u

CURL_MAX_TIME=70

die() {
    printf 'homer-dispatch: %s\n' "$1" >&2
    exit 2
}

[ -n "${HMR_API_URL:-}" ] || die "HMR_API_URL is not set"
[ -n "${HMR_CALLBACK_TOKEN:-}" ] || die "HMR_CALLBACK_TOKEN is not set"

RUNS_URL="${HMR_API_URL%/}/api/v1/callback/runs"

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

# Prints the unquoted numeric value of field $2 in JSON $1, if present
# (e.g. "processId":11). Line-based; tolerant of pretty-printed output.
json_num_field() {
    printf '%s' "$1" | awk -v field="$2" '
        BEGIN { found = 0 }
        !found {
            pat = "\"" field "\":"
            i = index($0, pat)
            if (i == 0) next
            s = substr($0, i + length(pat))
            while (substr(s, 1, 1) == " ") s = substr(s, 2)
            if (substr(s, 1, 1) !~ /[0-9]/) next
            found = 1
            n = ""
            while (length(s) > 0 && substr(s, 1, 1) ~ /[0-9]/) {
                n = n substr(s, 1, 1)
                s = substr(s, 2)
            }
            printf "%s", n
        }
        END { exit !found }'
}

AGENT=""
PARAMS_JSON=""
CONTINUE_PARAMS_JSON=""
CONTINUE_AGENT=""
CONTINUE_THREAD=""
RESUME_PARAM=""
RESULT_JSONPATH=""
TTL=""
while [ $# -gt 0 ]; do
    case "$1" in
        --agent) [ $# -ge 2 ] || die "--agent needs a value"; AGENT=$2; shift 2 ;;
        --param)
            [ $# -ge 2 ] || die "--param needs a value (key=value)"
            case "$2" in
                *=*) ;;
                *) die "--param must be key=value (got: $2)" ;;
            esac
            pkey=${2%%=*}
            pval=${2#*=}
            [ -n "$pkey" ] || die "--param key must not be empty (got: $2)"
            pair="\"$(json_escape "$pkey")\":\"$(json_escape "$pval")\""
            PARAMS_JSON="${PARAMS_JSON:+$PARAMS_JSON,}$pair"
            shift 2 ;;
        --continue-param)
            [ $# -ge 2 ] || die "--continue-param needs a value (key=value)"
            case "$2" in
                *=*) ;;
                *) die "--continue-param must be key=value (got: $2)" ;;
            esac
            cpkey=${2%%=*}
            cpval=${2#*=}
            [ -n "$cpkey" ] || die "--continue-param key must not be empty (got: $2)"
            cpair="\"$(json_escape "$cpkey")\":\"$(json_escape "$cpval")\""
            CONTINUE_PARAMS_JSON="${CONTINUE_PARAMS_JSON:+$CONTINUE_PARAMS_JSON,}$cpair"
            shift 2 ;;
        --continue-agent) [ $# -ge 2 ] || die "--continue-agent needs a value"; CONTINUE_AGENT=$2; shift 2 ;;
        --continue-thread) [ $# -ge 2 ] || die "--continue-thread needs a value"; CONTINUE_THREAD=$2; shift 2 ;;
        --resume-param) [ $# -ge 2 ] || die "--resume-param needs a value"; RESUME_PARAM=$2; shift 2 ;;
        --result-jsonpath) [ $# -ge 2 ] || die "--result-jsonpath needs a value"; RESULT_JSONPATH=$2; shift 2 ;;
        --ttl)
            [ $# -ge 2 ] || die "--ttl needs a value (seconds)"
            case "$2" in
                ''|*[!0-9]*|0|0*) die "--ttl must be a positive integer (got: $2)" ;;
            esac
            TTL=$2; shift 2 ;;
        *) die "unknown argument: $1" ;;
    esac
done
[ -n "$AGENT" ] || die "--agent is required"

params_field=""
[ -n "$PARAMS_JSON" ] && params_field=",\"params\":{$PARAMS_JSON}"

continue_fields=""
if [ -n "$CONTINUE_AGENT" ]; then
    continue_fields=",\"continueAgent\":\"$(json_escape "$CONTINUE_AGENT")\""
    [ -n "$CONTINUE_THREAD" ] &&
        continue_fields="${continue_fields},\"continueThread\":\"$(json_escape "$CONTINUE_THREAD")\""
    [ -n "$CONTINUE_PARAMS_JSON" ] &&
        continue_fields="${continue_fields},\"continueParams\":{$CONTINUE_PARAMS_JSON}"
    [ -n "$RESUME_PARAM" ] &&
        continue_fields="${continue_fields},\"resumeParam\":\"$(json_escape "$RESUME_PARAM")\""
    [ -n "$RESULT_JSONPATH" ] &&
        continue_fields="${continue_fields},\"resultJsonPath\":\"$(json_escape "$RESULT_JSONPATH")\""
    [ -n "$TTL" ] && continue_fields="${continue_fields},\"ttlSec\":$TTL"
else
    [ -z "$CONTINUE_THREAD" ] || die "--continue-thread requires --continue-agent"
    [ -z "$CONTINUE_PARAMS_JSON" ] || die "--continue-param requires --continue-agent"
    [ -z "$RESUME_PARAM" ] || die "--resume-param requires --continue-agent"
    [ -z "$RESULT_JSONPATH" ] || die "--result-jsonpath requires --continue-agent"
    [ -z "$TTL" ] || die "--ttl requires --continue-agent"
fi

payload="{\"agent\":\"$(json_escape "$AGENT")\"$params_field$continue_fields}"

response=$(
    curl -sS -f --max-time "$CURL_MAX_TIME" -X POST "$RUNS_URL" \
        -H "Authorization: Bearer $HMR_CALLBACK_TOKEN" \
        -H "Content-Type: application/json" \
        --data "$payload"
) || die "dispatch failed (terminal origin process, unknown or non-dispatchable agent, or Homer unreachable)"
pid=$(json_num_field "$response" "processId") || die "no processId in response: $response"
printf '%s\n' "$pid"
