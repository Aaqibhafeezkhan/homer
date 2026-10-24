#!/bin/sh
# Decides the PR policy check (CONTRIBUTING.md § Submitting changes): a pull request must link an
# issue of this repository that carries the `accepted` label. Only maintainers and triagers can
# set a label, so `accepted` records that the change belongs in Homer. An issue in another
# repository does not count: anyone can label issues in a repository they own.
#
# One exemption (ADR-0090, 2026-10-24 amendment): the AIF factory's release pull request, which
# promotes CHANGELOG.md's [Unreleased] to the release's version after a maintainer approved the
# release. It passes only when every one of these holds, each read from GitHub by the workflow:
# - its author is `homer-factory[bot]` (an App's login: GitHub reserves the `[bot]` suffix, so
#   no user account can hold it);
# - its branch is `aif/release-X.Y.Z` of this repository (only writers push branches here);
# - CHANGELOG.md is the one file it changes, and it only modifies it (a rename onto it would
#   delete another file).
#
# Usage: tools/pr-policy/check.sh OWNER/REPO <closing-issues.json
# The JSON is the answer to the GraphQL query in .github/workflows/pr-policy.yml. For the
# exemption, the workflow also sets PR_AUTHOR, PR_HEAD_REPO, PR_HEAD_REF and PR_FILES (a file
# listing the pull request's changes, one `STATUS PATH` line each, as GitHub's file list gives them).
set -eu

repo=$1

if [ "${PR_AUTHOR:-}" = "homer-factory[bot]" ] && [ "${PR_HEAD_REPO:-}" = "$repo" ] \
  && [ "$(printf '%s\n' "${PR_HEAD_REF:-}" | grep -Ex 'aif/release-[0-9]+\.[0-9]+\.[0-9]+')" = "${PR_HEAD_REF:-}" ] \
  && [ -n "${PR_FILES:-}" ] && [ -f "$PR_FILES" ] && [ "$(cat "$PR_FILES")" = "modified CHANGELOG.md" ]; then
  echo "The AIF factory's release pull request ($PR_HEAD_REF): it changes CHANGELOG.md alone."
  exit 0
fi
refs=$(jq -c --arg repo "$repo" \
  '[.data.repository.pullRequest.closingIssuesReferences.nodes[] | select(.repository.nameWithOwner == $repo)]')

if [ "$(printf '%s' "$refs" | jq 'length')" -eq 0 ]; then
  echo "This pull request links no issue of $repo. Add 'Closes #<issue>' to its description, then re-run this check."
  exit 1
fi
accepted=$(printf '%s' "$refs" | jq -r '[.[] | select(any(.labels.nodes[]; .name == "accepted")) | "#\(.number)"] | join(" ")')
if [ -z "$accepted" ]; then
  echo "No linked issue is labelled 'accepted' yet. A maintainer or triager labels the issue once the change is agreed; re-run this check after that."
  exit 1
fi
echo "Linked accepted issue: $accepted"
