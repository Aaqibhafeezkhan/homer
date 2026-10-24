#!/bin/sh
# Decides the PR policy check (CONTRIBUTING.md § Submitting changes): a pull request must link an
# issue of this repository that carries the `accepted` label. Only maintainers and triagers can
# set a label, so `accepted` records that the change belongs in Homer. An issue in another
# repository does not count: anyone can label issues in a repository they own.
#
# One exemption, granted only as a whole: the AIF factory's release pull request, which changes
# the changelog and the pinned release version only (ADR-0090, 2026-10-24 amendment).
#
# Usage: tools/pr-policy/check.sh OWNER/REPO <closing-issues.json
# The JSON is the answer to the GraphQL query in .github/workflows/pr-policy.yml. For the
# exemption, the workflow also sets PR_AUTHOR_ID, PR_AUTHOR_TYPE, PR_HEAD_REPO, PR_HEAD_REF and
# PR_FILES (a file of the pull request's changes, one `STATUS PATH` line each).
set -eu

repo=$1
# The factory's GitHub App, by its bot account's user id (a login can be renamed).
factory_app_user_id=346317994
# True when PR_FILES lists only `modified` release files, each once, CHANGELOG.md among them:
# the changelog, and the release's version where it is pinned for a fresh clone (ADR-0093).
release_files_only() {
  [ -n "${PR_FILES:-}" ] && [ -f "$PR_FILES" ] && grep -qx 'modified CHANGELOG.md' "$PR_FILES" || return 1
  [ -z "$(sort "$PR_FILES" | uniq -d)" ] || return 1
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in "modified "*) ;; *) return 1 ;; esac
    # Literal names: a path is never used as a pattern.
    case "${line#modified }" in
      CHANGELOG.md | docker-compose.yml | .env.example | README.md | DOCKER.md) ;;
      *) return 1 ;;
    esac
  done <"$PR_FILES"
}

if [ "${PR_AUTHOR_TYPE:-}" = Bot ] && [ "${PR_AUTHOR_ID:-}" = "$factory_app_user_id" ] && [ "${PR_HEAD_REPO:-}" = "$repo" ] \
  && [ "$(printf '%s\n' "${PR_HEAD_REF:-}" | grep -Ex 'aif/release-[0-9]+\.[0-9]+\.[0-9]+')" = "${PR_HEAD_REF:-}" ] \
  && release_files_only; then
  echo "The AIF factory's release pull request ($PR_HEAD_REF): it changes the changelog and the pinned version only."
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
