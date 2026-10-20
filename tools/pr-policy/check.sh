#!/bin/sh
# Decides the PR policy check (CONTRIBUTING.md § Submitting changes): a pull request must link an
# issue of this repository that carries the `accepted` label. Only maintainers and triagers can
# set a label, so `accepted` records that the change belongs in Homer. An issue in another
# repository does not count: anyone can label issues in a repository they own.
#
# Usage: tools/pr-policy/check.sh OWNER/REPO <closing-issues.json
# The JSON is the answer to the GraphQL query in .github/workflows/pr-policy.yml.
set -eu

repo=$1
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
