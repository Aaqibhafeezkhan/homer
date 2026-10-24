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
# whole — the App's bot account, a release branch of this repository, the changelog and the
# pinned version only.
release() {
  want=$1 name=$2 type=$3 id=$4 repo=$5 ref=$6 files=$7 list=${8:-$scratch/files.txt}
  answer >"$scratch/answer.json"
  printf '%b' "$files" >"$scratch/files.txt"
  if PR_AUTHOR_TYPE=$type PR_AUTHOR_ID=$id PR_HEAD_REPO=$repo PR_HEAD_REF=$ref PR_FILES="$list" \
      "$check" livesport/homer <"$scratch/answer.json" >/dev/null; then got=pass; else got=fail; fi
  if [ "$got" != "$want" ]; then
    echo "FAIL: $name: expected $want, got $got"
    failures=$((failures + 1))
  fi
}

app=346317994
rel=aif/release-1.36.0
cl='modified CHANGELOG.md'
release pass "the factory's release pull request" Bot "$app" livesport/homer "$rel" "$cl"
release fail "a user account with the App's id" User "$app" livesport/homer "$rel" "$cl"
release fail "another bot" Bot 49699333 livesport/homer "$rel" "$cl"
release fail "no author" "" "" livesport/homer "$rel" "$cl"
release fail "a release branch of a fork" Bot "$app" someone/homer "$rel" "$cl"
release fail "a branch that is not a release" Bot "$app" livesport/homer aif/GH-12 "$cl"
release fail "a release branch with a suffix" Bot "$app" livesport/homer aif/release-1.36.0-x "$cl"
release fail "a release branch name with a second line" Bot "$app" livesport/homer "$rel
x" "$cl"
release pass "the changelog and the pinned version" Bot "$app" livesport/homer "$rel" "modified .env.example\n$cl\nmodified DOCKER.md\nmodified README.md\nmodified docker-compose.yml\n"
release fail "a code change beside the changelog" Bot "$app" livesport/homer "$rel" "$cl\nmodified app/x.kt"
release fail "the pinned version without the changelog" Bot "$app" livesport/homer "$rel" 'modified docker-compose.yml'
release fail "a nested file of a release file's name" Bot "$app" livesport/homer "$rel" "$cl\nmodified docs/README.md"
release fail "a new compose file" Bot "$app" livesport/homer "$rel" "$cl\nadded docker-compose.yml"
release fail "a release file deleted" Bot "$app" livesport/homer "$rel" "$cl\nremoved DOCKER.md"
release fail "a file listed twice" Bot "$app" livesport/homer "$rel" "$cl\n$cl"
release fail "an empty line among the files" Bot "$app" livesport/homer "$rel" "$cl\n\nmodified README.md"
release fail "a path with a space" Bot "$app" livesport/homer "$rel" "$cl\nmodified README.md DOCKER.md"
release fail "a path that is a pattern for a release file" Bot "$app" livesport/homer "$rel" "$cl\nmodified README.m?"
release fail "a bracket pattern for a release file" Bot "$app" livesport/homer "$rel" "$cl\nmodified README.m[!x]"
release fail "a modified line without a path" Bot "$app" livesport/homer "$rel" "$cl\nmodified "
release fail "a file list that is not a file" Bot "$app" livesport/homer "$rel" "$cl" /nonexistent/files.txt
release fail "another file than the changelog" Bot "$app" livesport/homer "$rel" 'modified docs/CHANGELOG.md'
release fail "a rename onto the changelog" Bot "$app" livesport/homer "$rel" 'renamed CHANGELOG.md'
release fail "a new changelog" Bot "$app" livesport/homer "$rel" 'added CHANGELOG.md'
release fail "no file list" Bot "$app" livesport/homer "$rel" ''

if [ "$failures" -gt 0 ]; then
  echo "$failures PR policy test(s) failed."
  exit 1
fi
echo "All PR policy tests passed."
