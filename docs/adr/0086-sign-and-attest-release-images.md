---
status: proposed
date: 2026-10-15
deciders: Lukáš Vlk
consulted: ADR-0084, ADR-0085
---

# ADR-0086: Sign release images with cosign and attest their SBOM and provenance

## Context and Problem Statement

The release workflow publishes the `homer-app`, `homer-runner` and
`homer-console` images to GHCR (ADR-0084). A team that deploys them must be
able to check before deploying that an image was built by this repository's
release workflow from a release tag, and what it contains. The EU Cyber Resilience Act asks for a software bill of materials in a
standard, machine-readable format. Until now the workflow pushed unsigned
images with no SBOM and no provenance.

## Decision Drivers

- A consumer verifies an image with standard tools and without a key that
  Homer has to distribute or rotate.
- The SBOM uses a standard format: CycloneDX.
- Signature, SBOM and provenance bind to the image digest, not to a tag that
  can move.
- A release whose signature or attestations do not verify fails, so a broken
  release is not first noticed by a deployment.

## Considered Options

- **A: cosign keyless signature, plus GitHub artifact attestations for SLSA
  provenance and the CycloneDX SBOM.**
- **B: cosign with a long-lived signing key** stored as a repository secret.
- **C: BuildKit attestations** (`--provenance`, `--sbom`) only.
- **D: no published signatures.** Each consumer rebuilds the images from source
  in its own CI.

## Decision Outcome

Chosen: **A**. After the push, the `images` job of `release.yml`:

1. generates a CycloneDX SBOM of the pushed image with syft
   (`anchore/sbom-action`, `cyclonedx-json`);
2. signs the image digest with `cosign sign`, keyless through the workflow's
   GitHub OIDC token;
3. attests SLSA build provenance and the SBOM with `actions/attest`, both
   pushed to the registry next to the image;
4. verifies the signature and both attestations the way a consumer does, and
   fails the release when any of them is missing.

`ReleaseWorkflowGateTest` keeps these steps and their order.

A consumer verifies a release image like this:

```sh
image=ghcr.io/livesport/homer/homer-app@sha256:<digest>
cosign verify "$image" \
  --certificate-identity-regexp '^https://github\.com/livesport/homer/\.github/workflows/release\.yml@refs/tags/[0-9]+\.[0-9]+\.[0-9]+$' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
gh attestation verify "oci://$image" --repo livesport/homer \
  --signer-workflow livesport/homer/.github/workflows/release.yml \
  --predicate-type https://slsa.dev/provenance/v1
gh attestation verify "oci://$image" --repo livesport/homer \
  --signer-workflow livesport/homer/.github/workflows/release.yml \
  --predicate-type https://cyclonedx.org/bom
```

**Why not the others:**
- **B** needs a key that must be kept secret, rotated and published for
  verifiers. A leaked key signs anything, while a keyless certificate names the
  exact workflow and ref.
- **C** stores the BuildKit SBOM in SPDX only, and its attestations carry no
  signature unless the index is signed separately. It also needs a buildx push,
  while the console's license gate scans the image before the push.
- **D** keeps two build paths, and each must stay 1:1 with this repository's
  checks.

### Consequences

- Good: a consumer can verify who built an image and from which tag, and read
  what it contains, with cosign or the GitHub CLI and no shared key.
- Good: the SBOM covers the whole image: base-image packages, the backend's
  jars and the console's npm packages.
- Bad: keyless signing writes an entry to Sigstore's public transparency log
  (Rekor), which names the repository, the workflow and the tag. That is
  acceptable for a public repository. The release runs only on a protected ref,
  and the organization's plan offers rulesets, which make a ref protected, only
  on public repositories, so nothing is logged before the repository is public.
- Bad: GitHub artifact attestations need a public repository on the
  organization's plan, or GitHub Enterprise Cloud for a private one. The same
  public-only gate applies.
- Neutral: when signing fails after the push, the image stays in the registry
  unsigned, and a consumer that verifies refuses it. A rerun of the release
  rebuilds the image, so it pushes and signs a new digest: the build is not
  reproducible, because several installs in the backend image take the newest
  version at build time, among them the Claude Code and Codex CLIs
  (`CLAUDE_CODE_VERSION` defaults to `latest`), the LangGraph Python packages
  and the Alpine packages. The unsigned digest stays in the registry until it
  is pruned, so a consumer verifies the digest it actually deploys.
- Neutral: images that the upstream CI builds during the migration (ADR-0085) are not
  signed. They go to the internal registry only.

## Amendment (2026-10-24): the release page lists the verified digests

A consumer should not have to search the documentation for what to verify. After the images
are pushed, the `release-notes` job of `release.yml` appends an *Images* section to the GitHub
release: each image's digest, read from GHCR and verified with cosign against the run's own
signing identity before it is listed, and one `cosign verify` command per image with that
identity (a signature covers its own digest only, so one example command would mislead). The
image list is the `images` job's matrix; `ReleaseWorkflowGateTest` keeps the two equal.
`tools/release/notes.sh` writes the section and puts it in place of an earlier one, so a rerun,
which pushes and signs new digests, lists the new ones. The release's notes above it are the
version's `CHANGELOG.md` section; a tag whose version has none fails before any image is pushed.
