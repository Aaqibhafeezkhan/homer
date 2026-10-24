#!/bin/sh
# Tests for notes.sh: a release's notes are its changelog section, and its images section is
# replaced, never duplicated, when the release job runs again.
#
# Usage: tools/release/notes-test.sh   (from anywhere)
set -eu

notes=$(cd "$(dirname "$0")" && pwd)/notes.sh
failures=0
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT

fail() { echo "FAIL: $1"; failures=$((failures + 1)); }
has() { grep -qF -- "$2" "$1" || fail "$3: missing '$2'"; }
lacks() { if grep -qF -- "$2" "$1"; then fail "$3: unexpected '$2'"; fi; }

cat >"$scratch/CHANGELOG.md" <<'MD'
# Changelog

## [Unreleased]

- **Added: later** — not released yet.

## [1.36.0] - 2026-10-24

### Homer

- **Added: maintenance mode** — Queues starts. See [ADR-0092](docs/adr/0092-x.md).
- **Changed: the console** — [Runs](console/README.md) and [the client](clients/python/README.md).
- **Fixed: a link** — [elsewhere](https://example.com/x) stays as it is.

## [1.35.0] - 2026-10-20

- **Fixed: old** — x.

## [1.34.0] - 2026-10-10

## [1.33.0] - 2026-10-01

- **Fixed: older** — y.
MD

"$notes" changelog 1.36.0 "$scratch/CHANGELOG.md" >"$scratch/n"
has "$scratch/n" '### Homer' "the section's heading"
has "$scratch/n" '[ADR-0092](https://github.com/livesport/homer/blob/1.36.0/docs/adr/0092-x.md)' "a relative link"
has "$scratch/n" '[elsewhere](https://example.com/x)' "an absolute link"
has "$scratch/n" '**Full changelog:** [CHANGELOG.md](https://github.com/livesport/homer/blob/1.36.0/CHANGELOG.md)' "the full changelog"
has "$scratch/n" '[Runs](https://github.com/livesport/homer/blob/1.36.0/console/README.md)' "a console link"
has "$scratch/n" '[the client](https://github.com/livesport/homer/blob/1.36.0/clients/python/README.md)' "a clients link"
lacks "$scratch/n" 'not released yet' "another section"
(cd "$scratch" && "$notes" changelog 1.36.0) >"$scratch/d"
cmp -s "$scratch/n" "$scratch/d" || fail "the default CHANGELOG.md path"
lacks "$scratch/n" 'Fixed: old' "the next section"
[ "$(head -n 1 "$scratch/n")" = '### Homer' ] || fail "leading blank lines"

for missing in 1.37.0 1.34.0; do
  if "$notes" changelog "$missing" "$scratch/CHANGELOG.md" >/dev/null 2>&1; then fail "no section for $missing passed"; fi
done
if "$notes" changelog 'v1.36.0' "$scratch/CHANGELOG.md" >/dev/null 2>&1; then fail "a version that is no semver passed"; fi

d1=sha256:$(printf 'a%.0s' $(seq 1 64))
d2=sha256:$(printf 'b%.0s' $(seq 1 64))
printf 'homer-app %s\nhomer-console %s\n' "$d1" "$d2" | "$notes" images 1.36.0 >"$scratch/i"
has "$scratch/i" '<!-- release-images -->' "the marker"
has "$scratch/i" "| \`ghcr.io/livesport/homer/homer-app:1.36.0\` | \`$d1\` |" "an image row"
has "$scratch/i" "cosign verify ghcr.io/livesport/homer/homer-app@$d1" "the verify command"
has "$scratch/i" "cosign verify ghcr.io/livesport/homer/homer-console@$d2" "a verify command per image"
has "$scratch/i" '| Image | Digest |' "the table header"
[ "$(grep -cx '```bash' "$scratch/i")" = 1 ] || fail "the opening fence"
[ "$(grep -cx '```' "$scratch/i")" = 1 ] || fail "the closing fence"
[ "$(tail -n 1 "$scratch/i")" = '```' ] || fail "the section ends at its closing fence"
has "$scratch/i" '--certificate-identity https://github.com/livesport/homer/.github/workflows/release.yml@refs/tags/1.36.0' "the identity"
if printf 'homer-app sha256:short\n' | "$notes" images 1.36.0 >/dev/null 2>&1; then fail "a bad digest passed"; fi
if "$notes" images 1.36.0 </dev/null >/dev/null 2>&1; then fail "no image passed"; fi
if printf 'homer_app %s\n' "$d1" | "$notes" images 1.36.0 >/dev/null 2>&1; then fail "a bad image name passed"; fi
if printf 'homer-app %s\n' "$d1" | "$notes" images latest >/dev/null 2>&1; then fail "a tag that is no version passed"; fi
printf 'homer-app %s\nhomer-app %s -slim\n' "$d1" "$d2" | "$notes" images 1.36.0 >"$scratch/s"
has "$scratch/s" "| \`ghcr.io/livesport/homer/homer-app:1.36.0-slim\` | \`$d2\` |" "a tag variant's row"
has "$scratch/s" "| \`ghcr.io/livesport/homer/homer-app:1.36.0\` | \`$d1\` |" "the plain tag beside its variant"
has "$scratch/s" "cosign verify ghcr.io/livesport/homer/homer-app@$d2" "a verify command for the variant"
for bad in slim -Slim '-slim;id' '-'; do
  if printf 'homer-app %s %s\n' "$d1" "$bad" | "$notes" images 1.36.0 >/dev/null 2>&1; then fail "the tag suffix '$bad' passed"; fi
done
printf 'homer-app %s\n' "$d1" | REPO=acme/fork "$notes" images 1.36.0 >"$scratch/f"
has "$scratch/f" "ghcr.io/acme/fork/homer-app:1.36.0" "REPO names the namespace"
has "$scratch/f" "https://github.com/acme/fork/.github/workflows/release.yml@refs/tags/1.36.0" "REPO names the identity"

"$notes" changelog 1.36.0 "$scratch/CHANGELOG.md" >"$scratch/body"
"$notes" merge "$scratch/body" "$scratch/i" >"$scratch/m1"
"$notes" merge "$scratch/m1" "$scratch/i" >"$scratch/m2"
cmp -s "$scratch/m1" "$scratch/m2" || fail "a second run changed the notes"
[ "$(grep -c '<!-- release-images -->' "$scratch/m2")" = 1 ] || fail "the images section twice"
has "$scratch/m2" '### Homer' "merge keeps the notes"
: >"$scratch/empty"
"$notes" merge "$scratch/empty" "$scratch/i" >"$scratch/m3"
[ "$(head -n 1 "$scratch/m3")" = '<!-- release-images -->' ] || fail "merge into an empty body"

if [ "$failures" -gt 0 ]; then
  echo "$failures release notes test(s) failed."
  exit 1
fi
echo "All release notes tests passed."
