---
status: proposed
date: 2026-10-15
deciders: Lukáš Vlk
consulted: ADR-0084, ADR-0086
---

# ADR-0087: A versioned documentation site generated from the repository's Markdown

## Context and Problem Statement

Homer's documentation lives in Markdown files across the repository:
`README.md`, `app/AGENT.md`, `app/SERVER.md`, `DOCKER.md`, the console and
LangGraph guides, `SECURITY.md` and 86 ADRs. Only the single-page feature atlas
was published, to the upstream Pages site. Once the repository is public, people who
evaluate Homer, write agents or run it need the documentation as a site, and
for the release they run, not for whatever `main` holds today. The problem is
how to publish it without a second copy that drifts from the repository, and
how to keep older releases' documentation readable.

## Decision Drivers

- One source: the Markdown files in the repository. The site adds no copy to
  maintain.
- Documentation per release, plus the unreleased state of `main`.
- A broken link fails CI, not a reader.
- Schemas at stable URLs, so an editor outside the repository can validate
  `agent.yaml`.
- No branch that a bot commits to, and nothing that runs before the repository
  is public on GitHub.

## Considered Options

- **A: VitePress, generated from the repository's Markdown; every release
  attaches its built site to its GitHub release, and the Pages deployment
  assembles recent releases plus `next`.**
- **B: MkDocs Material with `mike`**, which commits each version to a
  `gh-pages` branch.
- **C: one site from `main` only.**
- **D: a hand-written documentation site.**

## Decision Outcome

Chosen: **A**.

- `site/scripts/prepare.mjs` turns the published files
  (`site/scripts/sources.mjs`, every ADR) into pages. A link to another
  published file points at its page. A link to any other repository file
  points at it on GitHub at the built tag or branch. An image a page shows
  (png, jpeg, gif, svg, webp, avif, bmp, ico) is copied into the site and
  bundled with a content hash, so it matches the built version and shows while
  the repository is private; any other image target points at GitHub. A
  published file (page, ADR, image or copied asset) that is a symlink, sits
  under one, or is not a regular file fails the build instead of being
  published, as does a published path that leaves the repository (`..` past
  the repository root; an image path with a backslash too), and so does
  `site/pages/` as a symlink or one in it. A link to a file the repository
  does not hold fails the build, and VitePress fails on a dead link between
  pages. The landing page and the schema page live in
  `site/pages/`. The JSON schemas are copied as they are.
- The feature atlas stays one standalone HTML file in the repository. Its
  styles are scoped under `.atlas` and read the site's theme variables, with
  its own palette as the fallback. `site/scripts/atlas.mjs` turns it into the
  site page `/atlas/`, so it shares the site's navigation, colour scheme and
  fonts, and its filter runs again on every visit.
- A table column whose cells average 80 or more characters gets a minimum
  width at build time (`site/.vitepress/table-widths.mjs`), so prose is not
  squeezed beside long code names. A table wider than the page scrolls
  sideways. On wide screens the text column is 860px instead of 688px.
- Diagrams are pre-rendered SVGs, not drawn by Mermaid in the browser. The
  Mermaid package pulled dependencies with known advisories, and the release
  that fixed them was younger than two weeks. The maintainers hold back any
  new dependency or image version until it is two weeks old, so that a
  compromised release is likely found and pulled first; this team norm is
  recorded here, not enforced by a tool. The architecture diagram's source is
  `docs/assets/architecture.mmd`; its light and dark SVGs are regenerated
  together with the `mermaid-cli` image named in the README comment beside
  them.
- `release.yml` builds the release's site under `/homer/<version>/`, attests
  the archive with `actions/attest`, attaches it to the GitHub release as
  `homer-docs.tar.gz`, and starts `pages.yml`.
- `pages.yml` builds `next` from `main`, fetches the archives of recent
  releases, and runs `site/scripts/assemble.mjs`. Anyone with write access can
  replace a release asset, so an archive is published only when
  `gh attestation verify` proves that `release.yml` built it from that
  release's tag. The archive is written as plain ustar and may hold only
  regular files and directories with relative paths; `check-archive.mjs`
  refuses it before extraction otherwise. Pax, global, GNU long-name and sparse
  headers are refused outright rather than parsed, because each can carry the
  name an extractor really uses, and the site's paths fit ustar. The checkout
  keeps no token in `.git/config`: a symlink could otherwise pull a runner file
  into the published site. That script lays out
  `/<version>/`, `/next/`, `/schemas/<version>/`, `/schemas/latest/`, `/atlas/` (a stable
  link to the newest version's atlas) and `/versions.json` for the version menu. The root redirects to the newest
  release, or to `next` before the first one. It keeps the newest patch of
  the ten newest minor versions: one version is about 16 MB, releases come
  almost daily, and GitHub Pages allows 1 GB. An older version's documentation
  stays attached to its release.
- The `docs-site` job runs the generator's tests and a full build upstream
  and on GitHub. Until the switch, the upstream `pages` job publishes the `main`
  site as an internal preview.

**Why not the others:**
- **B** needs a branch that a workflow commits to on every push. Its token can
  then write the repository, and the branch history grows with every build.
  MkDocs would also add a Python toolchain next to the console's Node one.
- **C** documents features a user's release does not have yet, and loses the
  documentation of the release they run.
- **D** is a second copy that drifts from the files the code review keeps
  current.

### Consequences

- Good: a documentation change is reviewed once, in the file next to the code,
  and reaches the site with the next build.
- Good: a link that points at a renamed or deleted file fails CI.
- Good: `/schemas/latest/` and `/schemas/<version>/` give editors outside the
  repository a schema URL. The schemas' `$id` moves there with the merge
  request that switches Homer to GitHub, a little before the site is served:
  editors in the repository load the schemas by relative path and never
  dereference the `$id`, so only an outside tool resolving it in that window
  gets a `404`.
- Bad: the files the site publishes must stay valid for VitePress. Text that
  Vue would compile is kept literal by the generator, and its tests cover
  that, but a new construct can still fail the build.
- Bad: the release job gets `contents: write` to create the release,
  `actions: write` to start `pages.yml`, and `id-token: write` with
  `attestations: write` to attest the archive. It runs only on a protected tag
  (ADR-0084).
- Neutral: the atlas moves from the upstream Pages root to `/atlas/`.
- Neutral: releases before the site have no archive and do not appear on it.
