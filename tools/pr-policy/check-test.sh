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

# The AIF factory's release pull request (ADR-0090, 2026-10-24 amendment): exempt only as a
# whole — the App's login, a release branch of this repository, CHANGELOG.md alone.
release() {
  want=$1 name=$2 author=$3 repo=$4 ref=$5 files=$6
  answer >"$scratch/answer.json"
  printf '%b' "$files" >"$scratch/files.txt"
  if PR_AUTHOR=$author PR_HEAD_REPO=$repo PR_HEAD_REF=$ref PR_FILES="$scratch/files.txt" \
      "$check" livesport/homer <"$scratch/answer.json" >/dev/null; then got=pass; else got=fail; fi
  if [ "$got" != "$want" ]; then
    echo "FAIL: $name: expected $want, got $got"
    failures=$((failures + 1))
  fi
}

release pass "the factory's release pull request" "homer-factory[bot]" livesport/homer aif/release-1.36.0 'modified CHANGELOG.md'
release fail "a user account named like the App" "homer-factory" livesport/homer aif/release-1.36.0 'modified CHANGELOG.md'
release fail "another bot" "dependabot[bot]" livesport/homer aif/release-1.36.0 'modified CHANGELOG.md'
release fail "a release branch of a fork" "homer-factory[bot]" someone/homer aif/release-1.36.0 'modified CHANGELOG.md'
release fail "a branch that is not a release" "homer-factory[bot]" livesport/homer aif/GH-12 'modified CHANGELOG.md'
release fail "a release branch with a suffix" "homer-factory[bot]" livesport/homer aif/release-1.36.0-x 'modified CHANGELOG.md'
release fail "a code change beside the changelog" "homer-factory[bot]" livesport/homer aif/release-1.36.0 'modified CHANGELOG.md\nmodified app/x.kt'
release fail "another file than the changelog" "homer-factory[bot]" livesport/homer aif/release-1.36.0 'modified docs/CHANGELOG.md'
release fail "a release branch name with a second line" "homer-factory[bot]" livesport/homer 'aif/release-1.36.0
x' 'modified CHANGELOG.md'
release fail "a rename onto the changelog" "homer-factory[bot]" livesport/homer aif/release-1.36.0 'renamed CHANGELOG.md'
release fail "a new changelog" "homer-factory[bot]" livesport/homer aif/release-1.36.0 'added CHANGELOG.md'
release fail "no file list" "homer-factory[bot]" livesport/homer aif/release-1.36.0 ''

if [ "$failures" -gt 0 ]; then
  echo "$failures PR policy test(s) failed."
  exit 1
fi
echo "All PR policy tests passed."
