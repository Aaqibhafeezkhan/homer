# Contributing to Homer

## Getting started

- Backend (Kotlin/Ktor): see [`app/CLAUDE.md`](app/CLAUDE.md) for
  architecture and conventions. Requires JDK 25.
- Console (Next.js): see [`console/CLAUDE.md`](console/CLAUDE.md) for
  architecture and conventions. Requires Node.
- Questions and help go to
  [GitHub Discussions](https://github.com/livesport/homer/discussions/categories/q-a).

## Before submitting

Run the relevant verification command and make sure it's green:

| Area touched | Verify |
| --- | --- |
| `app/**` | `JAVA_HOME=<path to a JDK 25> app/gradlew -p app ktlintFormat :app:test :app:checkThirdPartyNotices` |
| `app/buildSrc/**` | `JAVA_HOME=<path to a JDK 25> app/gradlew -p app/buildSrc check` (a separate build — `-p app check` does not cover it) |
| `console/**` | `cd console && npm run lint && npm test && npm run build && npm run licenses:check` |
| `clients/python/**` | `cd clients/python && .venv/bin/python -m pytest` (venv setup: [`clients/python/homer_langgraph/CLAUDE.md`](clients/python/homer_langgraph/CLAUDE.md)) |
| `tools/quality/**` | `cd tools/quality && npm ci && npm test` |
| `site/**`, or a file the site publishes (listed in `site/scripts/sources.mjs`, every ADR) | `cd site && npm ci && npm test && npm run docs:build` (fails on a link to a missing file) |
| `app/schemas/**` or `Agent.kt` / `AppConfig.kt` | backend verify (runs `SchemaValidationTest`, `AgentMdExamplesTest`) |
| `.github/ISSUE_TEMPLATE/**` or `.github/pull_request_template.md` | `JAVA_HOME=<path to a JDK 25> app/gradlew -p app :app:test --tests 'homer.app.CommunityTemplatesTest'` (the community-template guard; `node tools/quality/changed.mjs` selects no check for a template-only change) |

## Expectations

- Tests are required for new code — see each dev guide's testing
  section for the stack and layout.
- Every pull request runs the quality checks listed in
  [`docs/quality-checks.md`](docs/quality-checks.md); `required` ones
  fail the pipeline. `node tools/quality/changed.mjs` runs them locally
  against your change since `origin/main` (uncommitted edits included),
  with only the related tests.
- One logical change per commit; imperative subject, body explains why.
- User-visible changes get a `CHANGELOG.md` entry under `[Unreleased]`.
- Architectural decisions with lasting impact get an ADR under
  [`docs/adr/`](docs/adr/) — see [`docs/adr/README.md`](docs/adr/README.md).

## Submitting changes

Open a pull request on [github.com/livesport/homer](https://github.com/livesport/homer).

1. Open an issue first. A maintainer or triager labels it `accepted` once
   the change belongs in Homer and is welcome there; only those roles can set
   labels.
2. Link the accepted issue from the pull request: `Closes #123` in the
   description, or the pull request's *Development* field. The `PR policy`
   check fails a pull request that links no accepted issue of this
   repository. Labelling the issue does not re-run the check, so re-run it
   afterwards. [ADR-0090](docs/adr/0090-accepted-issue-pull-request-gate.md)
   records why the gate works this way.
3. CI runs on GitHub-hosted runners. A first-time contributor's first run
   waits until a maintainer approves it, as GitHub does by default.
4. Reviews start once every automated check is green. A pull request from
   a member of the `livesport` organization, or from Homer's own factory,
   gets the AI review and a maintainer's review. One from anyone else gets
   a maintainer's review first, and the AI review only after the maintainer
   approves, so no automated review runs on code nobody has read. A new
   push dismisses the approval.
5. A pull request merges once every check, the AI review and a maintainer's
   approval are green on its latest commit and every review conversation is
   resolved. The AI review can block a merge but never approves one. Only
   maintainers merge.
