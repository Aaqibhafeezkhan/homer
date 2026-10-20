---
status: accepted
date: 2026-04-21
deciders: Lukáš Vlk
---
# ADR-0001: Record architecture decisions

## Context and Problem Statement

Homer has accumulated non-trivial architectural decisions — async process
model, lock strategies, status-precedence rules, CORS allowlist, unified
error model — that were made in issues, chat, or commits and are hard to
rediscover after the fact. New contributors can read the code but cannot
easily see *why* it looks the way it does, which leads to rework or
accidental reversal of earlier choices.

## Decision Drivers

- Give future contributors a single place to find the *why* behind
  structural choices.
- Keep the process low-friction so it actually gets used.
- Stay close to community convention so tooling (IDE plugins, `adr-tools`)
  and newcomers recognise the format.

## Considered Options

- Adopt ADRs using the MADR template, stored in `docs/adr/`.
- Keep decisions in commit messages and PR descriptions only.
- Maintain a free-form `DECISIONS.md` file.

## Decision Outcome

Chosen option: **"Adopt ADRs using the MADR template, stored in
`docs/adr/`"**, because it is the lightest-weight format that still imposes
enough structure to be useful, and MADR is the de-facto community standard
that most contributors will already recognise.

### Consequences

- Good: decisions with lasting impact get a durable, reviewable home.
- Good: the structured template (Context → Drivers → Options → Outcome →
  Consequences) forces trade-offs to be articulated, not just declared.
- Good: ADRs are plain Markdown in-repo, so they diff, review, and version
  alongside the code they describe.
- Bad: adds a small authoring cost for meaningful decisions. Mitigation: we
  do not require an ADR for every change — only for decisions a future
  reader could not reconstruct from code + commit history.
- Bad: ADRs can drift from reality if superseded decisions are not marked.
  Mitigation: when a decision is replaced, the old ADR's `status` flips to
  `superseded by ADR-NNNN` rather than being edited in place.

## More Information

- Template: [`0000-template.md`](0000-template.md).
- Index and conventions: [`README.md`](README.md).
- MADR project: <https://adr.github.io/madr/>.
