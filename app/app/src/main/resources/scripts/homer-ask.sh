#!/bin/sh
# homer-ask — ask the Homer operator a question from a running command.
#
# Materialized by Homer into {projectRoot}/.homer/bin (on PATH) for every
# process. Reads HMR_API_URL and HMR_CALLBACK_TOKEN from the environment at
# call time. See AGENT.md "Asking the operator questions" and ADR-0019.
#
# Usage:
#   homer-ask ask "Question (Markdown)" [--option TEXT]...
#       Prints the question id on stdout and returns immediately.
#   homer-ask check ID [--wait SEC]
#       Prints the answer when ANSWERED (exit 0). Exit 3 while OPEN
#       (poll again with the same id), 4 when EXPIRED.
#   homer-ask ask-and-wait "Question (Markdown)" [--option TEXT]... [--ttl SEC]
#       Asks and blocks until answered (prints the answer, exit 0). With
#       --ttl, gives up after SEC seconds, printing the question id on
#       stderr so a later `homer-ask check ID` can pick up the answer.
#   homer-ask ask-and-dispatch "Question (Markdown)" --agent NAME
#       [--answer-param NAME] [--param k=v]... [--option TEXT]... [--ttl SEC]
#       Registers a dispatch: when the operator answers, Homer starts the
#       named agent with the answer (under --answer-param, default "answer")
#       plus any --param inputs. Prints "dispatchId\nquestionId" and exits
#       immediately (fire-and-forget).
#
# Exit codes: 0 ok/answered, 2 usage or transport error, 3 still open /
# TTL lapsed, 4 expired (the run reached a terminal state).

set -u

CHUNK_SEC=55
CURL_MAX_TIME=70
MAX_POLL_FAILURES=3
POLL_RETRY_SEC=5

die() {
    printf 'homer-ask: %s\n' "$1" >&2
    exit 2
}

# Dies unless $2 is a non-negative integer; $1 names the flag in the error.
require_uint() {
    case "$2" in
        '' | *[!0-9]*) die "$1 must be a non-negative integer (got: $2)" ;;
    esac
}

[ -n "${HMR_API_URL:-}" ] || die "HMR_API_URL is not set"
[ -n "${HMR_CALLBACK_TOKEN:-}" ] || die "HMR_CALLBACK_TOKEN is not set"

BASE_URL="${HMR_API_URL%/}/api/v1/callback/questions"
DISPATCH_URL="${HMR_API_URL%/}/api/v1/callback/dispatches"

# Appends one --option TEXT value to OPTIONS_JSON (comma-joined quoted list).
append_option() {
    escaped="\"$(json_escape "$1")\""
    OPTIONS_JSON="${OPTIONS_JSON:+$OPTIONS_JSON,}$escaped"
}

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

# Prints the unescaped string value of field $2 in JSON $1, if present.
# Line-based (a JSON string value never spans physical lines) and tolerant
# of the pretty-printed `"field": "value"` form Homer responds with.
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
                    if (d == "u") {
                        # kotlinx emits \u00XX for control chars without a
                        # short escape (e.g. ESC); decode the 4 hex digits.
                        n = 0
                        for (k = 3; k <= 6; k++) {
                            v = index("0123456789abcdef", tolower(substr(s, k, 1))) - 1
                            if (v < 0) v = 0
                            n = n * 16 + v
                        }
                        printf "%c", n
                        s = substr(s, 7)
                        continue
                    }
                    if (d == "n") printf "\n"
                    else if (d == "t") printf "\t"
                    else if (d == "r") printf "\r"
                    else if (d == "b") printf "\b"
                    else if (d == "f") printf "\f"
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

# Prints the unquoted numeric value of field $2 in JSON $1, if present.
# Mirrors json_str_field but matches an unquoted digit sequence instead
# of a quoted string (e.g. "dispatchId":11).
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

# POSTs a dispatch; prints "dispatchId\nquestionId".
# $1 text  $2 options-json (comma-joined quoted)  $3 agent  $4 answer_param
# $5 params-json-object-body (comma-joined "key":"val")  $6 ttl (optional, may be empty)
dispatch_question() {
    ttl_field=""
    [ -n "${6:-}" ] && ttl_field=",\"ttlSec\":$6"
    payload="{\"text\":\"$(json_escape "$1")\",\"options\":[$2],\"agent\":\"$(json_escape "$3")\",\"answerParam\":\"$(json_escape "$4")\",\"params\":{$5}$ttl_field}"
    response=$(
        curl -sS -f --max-time "$CURL_MAX_TIME" -X POST "$DISPATCH_URL" \
            -H "Authorization: Bearer $HMR_CALLBACK_TOKEN" \
            -H "Content-Type: application/json" \
            --data "$payload"
    ) || die "registering the dispatch failed (terminal process, unknown agent, pending cap, or Homer unreachable)"
    did=$(json_num_field "$response" "dispatchId") || die "no dispatchId in response: $response"
    qid=$(json_str_field "$response" "questionId") || die "no questionId in response: $response"
    printf '%s\n%s\n' "$did" "$qid"
}

# POSTs a question built from "$text" and $OPTIONS_JSON; prints the id.
ask_question() {
    payload="{\"text\":\"$(json_escape "$1")\",\"options\":[$2]}"
    response=$(
        curl -sS -f --max-time "$CURL_MAX_TIME" -X POST "$BASE_URL" \
            -H "Authorization: Bearer $HMR_CALLBACK_TOKEN" \
            -H "Content-Type: application/json" \
            --data "$payload"
    ) || die "asking the question failed (terminal process, open-question cap, or Homer unreachable)"
    id=$(json_str_field "$response" "id") || die "no question id in response: $response"
    printf '%s\n' "$id"
}

# GETs the question once with waitSec=$2; prints the answer on ANSWERED.
# Returns 0 answered, 3 open, 4 expired, 2 on transport/parse errors —
# callers decide whether 2 is fatal (plain check) or retryable
# (ask-and-wait poll loop).
check_once() {
    response=$(
        curl -sS -f --max-time "$CURL_MAX_TIME" \
            -H "Authorization: Bearer $HMR_CALLBACK_TOKEN" \
            "$BASE_URL/$1?waitSec=$2"
    ) || {
        printf 'homer-ask: checking the answer failed (unknown id or Homer unreachable; a server restart drops open questions)\n' >&2
        return 2
    }
    status=$(json_str_field "$response" "status") || {
        printf 'homer-ask: no status in response: %s\n' "$response" >&2
        return 2
    }
    case "$status" in
        ANSWERED)
            json_str_field "$response" "answer"
            printf '\n'
            return 0
            ;;
        OPEN) return 3 ;;
        EXPIRED) return 4 ;;
        *)
            printf 'homer-ask: unexpected question status: %s\n' "$status" >&2
            return 2
            ;;
    esac
}

# Parses [--option TEXT]... and [--ttl SEC] (any order) into OPTIONS_JSON
# and TTL; remaining flags rejected. Callers without a --ttl flag reject a
# non-empty TTL themselves.
parse_options() {
    OPTIONS_JSON=""
    TTL=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --option)
                [ $# -ge 2 ] || die "--option needs a value"
                append_option "$2"
                shift 2
                ;;
            --ttl)
                [ $# -ge 2 ] || die "--ttl needs a value"
                TTL=$2
                shift 2
                ;;
            *) die "unknown argument: $1" ;;
        esac
    done
}

cmd="${1:-}"
case "$cmd" in
    ask)
        [ $# -ge 2 ] || die "usage: homer-ask ask \"Question\" [--option TEXT]..."
        text=$2
        shift 2
        parse_options "$@"
        [ -z "$TTL" ] || die "--ttl is only valid for ask-and-wait"
        ask_question "$text" "$OPTIONS_JSON"
        ;;
    check)
        [ $# -ge 2 ] || die "usage: homer-ask check ID [--wait SEC]"
        id=$2
        shift 2
        wait_sec=0
        if [ "${1:-}" = "--wait" ]; then
            [ $# -ge 2 ] || die "--wait needs a value"
            wait_sec=$2
            shift 2
        fi
        [ $# -eq 0 ] || die "unknown argument: $1"
        require_uint "--wait" "$wait_sec"
        # Chunk the wait below CURL_MAX_TIME like ask-and-wait does: a single
        # long-poll above ~70s would be killed client-side by curl and
        # misreported as a transport error instead of still-open.
        start=$(date +%s)
        while :; do
            remaining=$((wait_sec - ($(date +%s) - start)))
            [ "$remaining" -lt 0 ] && remaining=0
            chunk=$remaining
            [ "$chunk" -gt "$CHUNK_SEC" ] && chunk=$CHUNK_SEC
            check_once "$id" "$chunk"
            rc=$?
            [ "$rc" -ne 3 ] && exit "$rc"
            [ "$remaining" -le "$CHUNK_SEC" ] && exit 3
        done
        ;;
    ask-and-wait)
        [ $# -ge 2 ] || die "usage: homer-ask ask-and-wait \"Question\" [--option TEXT]... [--ttl SEC]"
        text=$2
        shift 2
        parse_options "$@"
        ttl=$TTL
        # Validate before asking: a bad ttl must not orphan an open question.
        [ -z "$ttl" ] || require_uint "--ttl" "$ttl"
        id=$(ask_question "$text" "$OPTIONS_JSON") || exit 2
        start=$(date +%s)
        failures=0
        while :; do
            chunk=$CHUNK_SEC
            if [ -n "$ttl" ]; then
                elapsed=$(($(date +%s) - start))
                remaining=$((ttl - elapsed))
                if [ "$remaining" -le 0 ]; then
                    printf 'homer-ask: no answer after %ss; resume with: homer-ask check %s\n' "$ttl" "$id" >&2
                    exit 3
                fi
                [ "$remaining" -lt "$chunk" ] && chunk=$remaining
            fi
            check_once "$id" "$chunk"
            rc=$?
            case "$rc" in
                2)
                    # Transient transport errors must not lose the question:
                    # retry a few times, and always surface the id so the
                    # caller can resume — it was only ever captured into $id.
                    failures=$((failures + 1))
                    if [ "$failures" -ge "$MAX_POLL_FAILURES" ]; then
                        printf 'homer-ask: giving up after %s transport errors; resume with: homer-ask check %s\n' "$failures" "$id" >&2
                        exit 2
                    fi
                    sleep "$POLL_RETRY_SEC"
                    ;;
                3) failures=0 ;;
                *) exit "$rc" ;;
            esac
        done
        ;;
    ask-and-dispatch)
        [ $# -ge 2 ] || die "usage: homer-ask ask-and-dispatch \"Question\" --agent NAME [--answer-param NAME] [--param k=v]... [--option TEXT]... [--ttl SEC]"
        text=$2
        shift 2
        OPTIONS_JSON=""
        PARAMS_JSON=""
        AGENT=""
        ANSWER_PARAM="answer"
        TTL=""
        while [ $# -gt 0 ]; do
            case "$1" in
                --agent) [ $# -ge 2 ] || die "--agent needs a value"; AGENT=$2; shift 2 ;;
                --answer-param) [ $# -ge 2 ] || die "--answer-param needs a value"; ANSWER_PARAM=$2; shift 2 ;;
                --option)
                    [ $# -ge 2 ] || die "--option needs a value"
                    append_option "$2"
                    shift 2 ;;
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
                --ttl) [ $# -ge 2 ] || die "--ttl needs a value"; TTL=$2; shift 2 ;;
                *) die "unknown argument: $1" ;;
            esac
        done
        [ -n "$AGENT" ] || die "--agent is required"
        [ -z "$TTL" ] || require_uint "--ttl" "$TTL"
        dispatch_question "$text" "$OPTIONS_JSON" "$AGENT" "$ANSWER_PARAM" "$PARAMS_JSON" "$TTL"
        ;;
    *)
        die "usage: homer-ask ask|check|ask-and-wait|ask-and-dispatch ... (see AGENT.md)"
        ;;
esac
