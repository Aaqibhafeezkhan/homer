#!/bin/sh
# The GitHub release page of a version (release.yml): its notes are the version's CHANGELOG.md
# section, and after the images are published, their digests and the command that verifies them.
#
# Usage:
#   tools/release/notes.sh changelog VERSION [CHANGELOG]   the section, links made absolute at the
#                                                           tag; fails when the version has none
#   tools/release/notes.sh images TAG <LINES               the images section; LINES are
#                                                           `IMAGE DIGEST [SUFFIX]`, one per image,
#                                                           SUFFIX a tag variant such as `-slim`
#   tools/release/notes.sh merge BODY SECTION              BODY with SECTION in place of any
#                                                           images section it already has
# REPO (default livesport/homer) names the repository and the GHCR namespace.
set -eu

repo=${REPO:-livesport/homer}
marker='<!-- release-images -->'

semver() {
  printf '%s\n' "$1" | grep -qxE '[0-9]+\.[0-9]+\.[0-9]+' || { echo "Not a release version: $1" >&2; exit 1; }
}

case "${1:-}" in
  changelog)
    version=$2
    semver "$version"
    section=$(awk -v head="## [$version]" '
      index($0, head) == 1 { found = 1; next }
      found && /^## \[/ { exit }
      found { print }' "${3:-CHANGELOG.md}")
    if ! printf '%s\n' "$section" | grep -q '^- \*\*'; then
      echo "CHANGELOG.md has no entry under '## [$version]'; add the section before releasing." >&2
      exit 1
    fi
    blob="https://github.com/$repo/blob/$version"
    printf '%s\n' "$section" \
      | sed -E "s#\]\(((docs|app|console|clients|site)/[^)]*|CHANGELOG[^)]*)\)#]($blob/\1)#g" \
      | awk 'NF { started = 1 } started' | awk '{ lines[NR] = $0 } NF { last = NR } END { for (i = 1; i <= last; i++) print lines[i] }'
    printf '\n**Full changelog:** [CHANGELOG.md](%s/CHANGELOG.md)\n' "$blob"
    ;;
  images)
    tag=$2
    semver "$tag"
    identity="https://github.com/$repo/.github/workflows/release.yml@refs/tags/$tag"
    printf '%s\n### Images\n\n' "$marker"
    printf 'Signed with cosign (keyless) and carrying SLSA provenance and a CycloneDX SBOM ([ADR-0086](https://github.com/%s/blob/%s/docs/adr/0086-sign-and-attest-release-images.md)).\n\n' "$repo" "$tag"
    printf '| Image | Digest |\n|---|---|\n'
    refs=
    while read -r image digest suffix; do
      printf '%s\n' "$image" | grep -qxE '[a-z0-9-]+' || { echo "Bad image name: $image" >&2; exit 1; }
      printf '%s\n' "$digest" | grep -qxE 'sha256:[0-9a-f]{64}' || { echo "Bad digest for $image: $digest" >&2; exit 1; }
      [ -z "$suffix" ] || printf '%s\n' "$suffix" | grep -qxE -- '-[a-z0-9]+' || { echo "Bad tag suffix for $image: $suffix" >&2; exit 1; }
      # shellcheck disable=SC2016 # Markdown backticks, not a command substitution
      printf '| `ghcr.io/%s/%s:%s%s` | `%s` |\n' "$repo" "$image" "$tag" "$suffix" "$digest"
      refs="$refs ghcr.io/$repo/$image@$digest"
    done
    [ -n "$refs" ] || { echo "No image given" >&2; exit 1; }
    # One command per image: a signature covers its own digest only.
    printf '\nVerify each image before you pull it, then pin its digest:\n\n```bash\n'
    for ref in $refs; do
      printf 'cosign verify %s \\\n  --certificate-identity %s \\\n  --certificate-oidc-issuer https://token.actions.githubusercontent.com\n' "$ref" "$identity"
    done
    printf '```\n'
    ;;
  merge)
    body=$(awk -v m="$marker" '$0 == m { exit } { print }' "$2" \
      | awk '{ lines[NR] = $0 } NF { last = NR } END { for (i = 1; i <= last; i++) print lines[i] }')
    if [ -n "$body" ]; then printf '%s\n\n' "$body"; fi
    cat "$3"
    ;;
  *)
    echo "usage: notes.sh changelog VERSION [CHANGELOG] | images TAG <LINES | merge BODY SECTION" >&2
    exit 2
    ;;
esac
