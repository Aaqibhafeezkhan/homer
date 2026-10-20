#!/bin/sh
# Denial-path tests for check.sh: only a linked issue of this repository labelled `accepted` passes.
#
# Usage: tools/pr-policy/check-test.sh   (from anywhere; needs jq)
set -eu

check=$(cd "$(dirname "$0")" && pwd)/check.sh
failures=0
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT

# Prints the GraphQL answer for the given issues, each as REPO:NUMBER:LABEL[,LABEL...].
answer() {
  nodes=
  for issue in "$@"; do
    repo=${issue%%:*}
    rest=${issue#*:}
    number=${rest%%:*}
    labels=$(jq -n -c --arg labels "${rest#*:}" '$labels | split(",") | map(select(. != "") | {name: .})')
    nodes="$nodes${nodes:+,}{\"number\":$number,\"repository\":{\"nameWithOwner\":\"$repo\"},\"labels\":{\"nodes\":$labels}}"
  done
  printf '{"data":{"repository":{"pullRequest":{"closingIssuesReferences":{"nodes":[%s]}}}}}' "$nodes"
}

expect() {
  want=$1 name=$2
  shift 2
  answer "$@" >"$scratch/answer.json"
  jq -e . "$scratch/answer.json" >/dev/null || { echo "FAIL: $name: the fixture is not valid JSON"; failures=$((failures + 1)); return; }
  if "$check" livesport/homer <"$scratch/answer.json" >/dev/null; then got=pass; else got=fail; fi
  if [ "$got" != "$want" ]; then
    echo "FAIL: $name: expected $want, got $got"
    failures=$((failures + 1))
  fi
}

expect fail "no linked issue"
expect fail "a linked issue without labels" "livesport/homer:1:"
expect fail "a linked issue with other labels" "livesport/homer:2:bug,enhancement"
expect fail "a near-miss label" "livesport/homer:3:accepted-later,Accepted"
expect fail "an accepted issue in another repository" "someone/homer:4:accepted"
expect pass "an accepted issue" "livesport/homer:5:bug,accepted"
expect pass "one accepted issue among several" "livesport/homer:6:" "someone/homer:7:accepted" "livesport/homer:8:accepted"

if [ "$failures" -gt 0 ]; then
  echo "$failures PR policy test(s) failed."
  exit 1
fi
echo "All PR policy tests passed."
