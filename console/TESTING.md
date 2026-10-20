# Testing Guide

This document covers both automated tests and a manual QA checklist for
the Homer Console.

## Automated Tests

### Stack

- **Vitest** — test runner.
- **React Testing Library** (`@testing-library/react` + `user-event`) —
  component-level testing against a real DOM.
- **jsdom** — browser-like environment.
- **`@testing-library/jest-dom`** — extra matchers, wired in
  [`vitest.setup.ts`](vitest.setup.ts).
- **`vitest-axe`** — axe accessibility matchers (`toHaveNoViolations`),
  wired in [`vitest.setup.ts`](vitest.setup.ts).
- **fast-check** — property-based tests (see below).
- **Stryker** — mutation testing over the Vitest suite (see Quality reports).

Config lives in [`vitest.config.ts`](vitest.config.ts). The `@/*`
import alias works in tests via the config's own `resolve.alias`
(`@` → the console root), which mirrors the `paths` mapping in
`tsconfig.json`.

### Running

```bash
npm test          # run once
npm run test:watch  # watch mode
npm run test:coverage && npm run crap  # CI-style: coverage, then the CRAP report
```

Coverage has no threshold of its own; `npm run crap` turns it into a CRAP
score per function (complexity × missing coverage) in `coverage/crap/`, and
the `quality-gate` job fails the pipeline while any function scores over
`crap.threshold` in `tools/quality/checks.json`. See
[ADR-0081](../docs/adr/0081-crap-score-gate.md). V8 instrumentation slows
CPU-heavy tests by an order of magnitude, so `test:coverage` raises the
per-test timeout to 30 s. The same run writes `coverage/cobertura-coverage.xml`
for the quality reports; GitHub renders no line coverage from it on a pull
request (ADR-0084).

### Quality reports

```bash
npm run quality:report          # ESLint warnings, dependency-cruiser layering, knip unused code
npm run mutation                # Stryker over the whole coverage scope (hours; a manual CI job)
MUTATE_BASE=origin/main npm run mutation:changed  # only the lines changed since that commit
```

All of them are report-only ([ADR-0082](../docs/adr/0082-quality-reports-report-first.md)):
they exit 0 whatever they find and write `<check>-summary.txt`,
`<check>-report.txt` and `gl-code-quality-<check>.json` under
`reports/quality/<check>/`. `mutation:changed` mutates the lines added or
changed since `MUTATE_BASE` in files of the coverage scope; with `MUTATE_WORKTREE=1`
it takes them from the working tree, uncommitted and untracked files included, as the
diff mode (`node tools/quality/changed.mjs --only mutation-console`) runs it (a file whose path
has glob characters, like a `[name]` route, is mutated whole) and skips
Stryker when there are none. Both modes skip static mutants. Stryker
runs Vitest through [`vitest.stryker.config.ts`](vitest.stryker.config.ts), which
raises the per-test timeout for its instrumentation; Stryker's HTML report is
`reports/quality/mutation-console/mutation.html`.

### Layout

Co-locate tests next to the code they exercise, as
`*.test.ts(x)`. For example:

```
lib/utils/format.ts
lib/utils/format.test.ts
components/processes/status-badge.tsx
components/processes/status-badge.test.tsx
```

### What to test

Per the root project rules (§6), every new component, hook, or utility
ships with at least one test. Prioritise:

- Pure utilities (`lib/utils/*`) — easiest, highest ROI.
- Component rendering invariants (correct text, correct attributes for
  each prop combination).
- Hook behaviour using `renderHook` from `@testing-library/react`.
- User flows with `userEvent` — click, type, assert.

Mock the API client (`lib/api/client.ts`) rather than hitting the real
backend in tests — inject via React Query provider in component tests.

### Property-based tests

Parsers, validators, formatters and anything that handles security-relevant
input (entry checkers, escaping, URL or path handling) also get a
property-based test with [fast-check](https://fast-check.dev): state what
must hold for *every* input and let fast-check search for a counterexample,
next to the example-based cases in the same `*.test.ts`. Bias the
generators towards the inputs that matter (separators, quotes, `..`, control
characters) rather than relying on uniform random strings, and always
`await fc.assert(...)`. The exemplar is the `serializeForScript` block in
[`lib/utils/html.test.ts`](lib/utils/html.test.ts). A failure prints a shrunk
counterexample and a seed; add the counterexample as an example-based case
when you fix it.

## Manual Testing Checklist

### Authentication Flow

#### Without Authentication (CONSOLE_AUTH_ENABLED=false)

- [ ] Start the app with `CONSOLE_AUTH_ENABLED=false`
- [ ] Navigate to http://localhost:3000
- [ ] Verify you are redirected directly to `/processes`
- [ ] Verify no login page is shown

#### With Authentication (CONSOLE_AUTH_ENABLED=true)

**Valid Credentials:**
- [ ] Start the app with `CONSOLE_AUTH_ENABLED=true`
- [ ] Navigate to http://localhost:3000
- [ ] Verify you are redirected to `/login`
- [ ] Verify the form shows Username and Password fields (no Token option)
- [ ] Enter a valid username and password
- [ ] Click "Login"
- [ ] Verify you are redirected to `/processes`
- [ ] Refresh the page
- [ ] Verify you remain authenticated (not redirected to login)

**Invalid Credentials:**
- [ ] Navigate to `/login`
- [ ] Enter an invalid username or password
- [ ] Click "Login"
- [ ] Verify error message: "Invalid credentials. Please try again."
- [ ] Verify you remain on the login page

**Session Persistence:**
- [ ] Login with valid credentials
- [ ] Close browser tab
- [ ] Reopen the application
- [ ] Verify you are still authenticated
- [ ] Delete the backend's `homer_session` cookie in browser DevTools
- [ ] Refresh page
- [ ] Verify you are redirected to login

#### Bearer Token Mode (CONSOLE_ALLOW_BEARER=true)

- [ ] Start the app with `CONSOLE_AUTH_ENABLED=true` and `CONSOLE_ALLOW_BEARER=true`
- [ ] Navigate to `/login` and select **Token**
- [ ] Enter a valid bearer token and click "Login"
- [ ] Verify you are redirected to `/processes`
- [ ] Verify the token is stored in localStorage
- [ ] Log in with an invalid token
- [ ] Verify error message: "Invalid token. Please try again."

### Processes Screen

#### Loading State
- [ ] Navigate to `/processes`
- [ ] Verify "Loading processes..." appears while fetching
- [ ] Verify loading message disappears when data loads

#### Process List Display
- [ ] Verify table shows all processes
- [ ] Verify columns: Process ID, Agent Name, Status, Status Time, Last Command, Cost, Runner, Purge, Actions (plus Flow in the Root runs view)
- [ ] Verify process IDs are displayed correctly
- [ ] Verify agent names are shown
- [ ] Verify status badges have correct colors:
  - WORKING: Blue with pulse animation
  - FINISHED: Green
  - FAILED: Red
  - KILLED: Orange
  - CREATED: Gray
- [ ] Verify status time shows formatted timestamp
- [ ] Verify last command shows checkmark (✓) for success or X (✗) for failure

#### Filtering
- [ ] Click on "WORKING" status filter
- [ ] Verify only WORKING processes are shown
- [ ] Click on "FINISHED" status filter
- [ ] Verify both WORKING and FINISHED processes are shown
- [ ] Click "Clear" button
- [ ] Verify all processes are shown again
- [ ] Test each status filter: CREATED, FAILED, KILLED
- [ ] Verify filters persist when navigating away and back

#### Pagination
- [ ] Create more than 50 processes (default page size)
- [ ] Verify pagination controls appear
- [ ] Verify "Showing X to Y of Z processes" text is correct
- [ ] Click "Next" button
- [ ] Verify next page of processes loads
- [ ] Verify pagination text updates
- [ ] Click "Previous" button
- [ ] Verify previous page loads
- [ ] Verify "Previous" is disabled on first page
- [ ] Verify "Next" is disabled on last page

#### Real-time Updates
- [ ] Start a new process in Homer
- [ ] Wait for the SSE update (the fallback poll runs every 30 seconds)
- [ ] Verify new process appears in the list
- [ ] Watch a WORKING process
- [ ] Verify status updates automatically when it completes
- [ ] Verify no manual refresh is needed

#### Kill Process
- [ ] Find a WORKING or CREATED process
- [ ] Verify "Kill" button is visible
- [ ] Click "Kill" button
- [ ] Verify confirmation dialog appears
- [ ] Click "Cancel"
- [ ] Verify dialog closes and process is not killed
- [ ] Click "Kill" button again
- [ ] Click "Kill Process" in dialog
- [ ] Verify dialog closes
- [ ] Verify process status changes to KILLED
- [ ] Verify the process list updates
- [ ] Find a FINISHED process
- [ ] Verify no "Kill" button is shown

#### Navigation
- [ ] Click on a process row
- [ ] Verify you navigate to `/processes/[id]`
- [ ] Click browser back button
- [ ] Verify you return to processes list
- [ ] Verify filters and pagination state are maintained

#### Error Handling
- [ ] Stop the Homer backend
- [ ] Wait for next poll (up to 30 seconds; the list stops polling once every row is terminal)
- [ ] Verify error message appears
- [ ] Verify error message contains helpful information
- [ ] Restart Homer backend
- [ ] Wait for next poll
- [ ] Verify data loads successfully

#### Empty State
- [ ] Clear all processes from Homer
- [ ] Navigate to `/processes`
- [ ] Verify "No processes found" message appears

### Process Detail Screen

#### Navigation
- [ ] Navigate to `/processes`
- [ ] Click on a process
- [ ] Verify URL changes to `/processes/[id]`
- [ ] Verify process ID and agent name are displayed in header

#### Status Information
- [ ] Verify current status badge is displayed
- [ ] Verify "Kill Process" button shows for WORKING/CREATED processes
- [ ] Verify "Kill Process" button is hidden for completed processes

#### Status Timeline
- [ ] Verify "Status Timeline" card is visible
- [ ] Verify all status changes are listed
- [ ] Verify timestamps are formatted correctly
- [ ] Verify status badges match the status types
- [ ] Verify entries are in chronological order

#### Executions List
- [ ] Verify "Executions" card is visible
- [ ] Verify all command executions are listed
- [ ] Verify each execution shows its command label
- [ ] Verify exit codes are displayed
- [ ] Verify start time, end time and duration are shown in the header row
- [ ] Verify success icon (✓) for exit code 0
- [ ] Verify failure icon (✗) for non-zero exit codes

#### Expandable Executions
- [ ] Click on an execution
- [ ] Verify details expand
- [ ] Verify Stdout/Stderr tabs show the output content
- [ ] Verify LLM cost is shown for a Claude/Codex step
- [ ] Verify error message is shown (if present)
- [ ] Click on execution again
- [ ] Verify details collapse
- [ ] Expand multiple executions
- [ ] Verify all stay expanded independently

#### Real-time Updates (Active Process)
- [ ] View details of a WORKING process
- [ ] Verify the page updates live over SSE (fallback poll every 30 seconds)
- [ ] Watch for new executions to appear
- [ ] Watch for status changes
- [ ] Verify no manual refresh needed

#### Real-time Updates (Completed Process)
- [ ] View details of a FINISHED process
- [ ] Verify no further polling requests are made (the detail poll stops at a terminal status)
- [ ] Verify data remains consistent

#### Kill Process from Detail
- [ ] View a WORKING process detail
- [ ] Click "Kill Process" button
- [ ] Verify confirmation dialog appears
- [ ] Click "Kill Process"
- [ ] Verify status changes to KILLED
- [ ] Verify timeline updates
- [ ] Verify "Kill Process" button disappears

#### Error Handling
- [ ] Navigate to `/processes/99999` (non-existent process)
- [ ] Verify appropriate error handling

#### Empty Executions
- [ ] View a newly created process with no executions
- [ ] Verify "No executions yet" message appears

### Agents Screen

#### Loading State
- [ ] Navigate to `/agents`
- [ ] Verify "Loading agents..." appears while fetching

#### Agents Grid
- [ ] Verify agents are displayed in a grid layout
- [ ] Verify 2-3 cards per row on desktop
- [ ] Verify single column on mobile
- [ ] Verify agent name is displayed
- [ ] Verify the script count is shown ("Scripts: N")

#### Parameters Display
- [ ] Find an agent with parameters
- [ ] Verify "Parameters:" section is shown
- [ ] Verify parameter names are displayed
- [ ] Verify parameter types (legacy `STRING`/`NUMBER`/`ANY`, or typed `string`/`integer`/`number`/`boolean`) are shown
- [ ] Verify "required" badge for required parameters
- [ ] Verify transformer details are shown correctly:
  - Pass through: "Direct pass-through"
  - JSONPath: "Extract JSON path: $.path.to.value"
  - Regex: "Regex match: pattern (group N)"
- [ ] Verify environment variable names are shown (if different from param name)

#### Search Functionality
- [ ] Type in search box
- [ ] Verify agents filter in real-time
- [ ] Verify search is case-insensitive
- [ ] Clear search box
- [ ] Verify all agents appear again
- [ ] Search for non-existent agent
- [ ] Verify "No agents match your search" message

#### Empty State
- [ ] Clear all agents from Homer
- [ ] Navigate to `/agents`
- [ ] Verify "No agents available" message

#### Agents with No Parameters
- [ ] Find an agent with no parameters
- [ ] Verify "No parameters required" message is displayed

### Schedules Screen

#### Run a Schedule
- [ ] Navigate to `/schedules` as an admin
- [ ] Verify each row with a cron shows a "Run" button
- [ ] Verify a non-admin sees the no-access message instead of the table
- [ ] Click "Run" on a row
- [ ] Verify a confirmation dialog appears and no run has started
- [ ] Click "Cancel"
- [ ] Verify the dialog closes and no run starts
- [ ] Click "Run" again
- [ ] Click "Run now" in the dialog
- [ ] Verify the dialog closes
- [ ] Verify the row's "Last run" cell links to the newly started process once it is the agent's most recent run
- [ ] Verify an agent whose required input has no default has no row at all (the schedule is refused when the agent is loaded)
- [ ] Click "Run" on an agent over its cost cap and confirm
- [ ] Verify no run starts and no error is shown (the refusal is logged only)
- [ ] Repeat with an agent whose parallel-run or queued-runs cap refuses the fire (e.g. its parallel-run slots are held)
- [ ] Verify a run is started and ends `FAILED`, no error is shown in the dialog, and the row's Last run cell links to that run

### UI/UX

#### Theme Switcher
- [ ] Verify app starts in dark mode (System when a light/dark pair is configured via `CONSOLE_THEME_MODES`)
- [ ] Click the theme button in the header
- [ ] Verify a menu lists Light, Dark, System, Terminal, Windows 95 (plus any `CONSOLE_THEMES` entries)
- [ ] With `CONSOLE_THEME_MODES=light:<name>,dark:<name>` set: verify the menu lists only the `CONSOLE_THEMES` entries plus System, that System is the default, and that a stored built-in falls back to System
- [ ] Select Light
- [ ] Verify all colors update correctly
- [ ] Verify the trigger icon switches from moon to sun
- [ ] Refresh page
- [ ] Verify theme preference is saved
- [ ] Select Dark again
- [ ] Verify smooth transition

#### Sidebar Navigation
- [ ] Click "Processes" in sidebar
- [ ] Verify active state (highlighted)
- [ ] Verify navigation to `/processes`
- [ ] Click "Agents" in sidebar
- [ ] Verify active state updates
- [ ] Verify navigation to `/agents`

#### Responsive Design
- [ ] Resize browser to mobile width (<768px)
- [ ] Verify sidebar behavior
- [ ] Verify process table switches to card layout (if implemented)
- [ ] Verify agents grid shows single column
- [ ] Verify all buttons remain accessible
- [ ] Test on tablet width (768px-1024px)
- [ ] Test on desktop width (>1024px)

#### Loading States
- [ ] Verify all loading states show helpful messages
- [ ] Verify loading states don't flicker on fast connections
- [ ] Verify skeleton screens (if implemented)

#### Error Messages
- [ ] Verify all error messages are user-friendly
- [ ] Verify errors are displayed prominently
- [ ] Verify error styling is consistent

### Browser Compatibility

Test in multiple browsers:
- [ ] Chrome (latest)
- [ ] Firefox (latest)
- [ ] Safari (latest)
- [ ] Edge (latest)

For each browser:
- [ ] All features work correctly
- [ ] Styling is consistent
- [ ] No console errors
- [ ] Performance is acceptable

### Performance

#### Initial Load
- [ ] Measure time to first contentful paint
- [ ] Verify under 2 seconds on good connection
- [ ] Check bundle sizes in build output
- [ ] Verify code splitting is working

#### Runtime Performance
- [ ] Monitor memory usage over 10 minutes
- [ ] Verify no memory leaks
- [ ] Check CPU usage during polling
- [ ] Verify animations are smooth (60fps)

#### Network Efficiency
- [ ] Monitor network requests in DevTools
- [ ] Verify polling requests happen at correct intervals
- [ ] Verify no duplicate requests
- [ ] Verify appropriate caching headers

### Accessibility

#### Keyboard Navigation
- [ ] Tab through entire page
- [ ] Verify logical tab order
- [ ] Verify focus indicators are visible
- [ ] Press Enter on buttons
- [ ] Verify actions trigger correctly
- [ ] Test Escape key on dialogs
- [ ] Verify dialogs close

#### Screen Reader
- [ ] Use screen reader (NVDA, JAWS, VoiceOver)
- [ ] Verify headings are announced
- [ ] Verify button labels are clear
- [ ] Verify form inputs have labels
- [ ] Verify status changes are announced

#### Color Contrast
- [ ] Use browser DevTools to check contrast
- [ ] Verify text meets WCAG AA standards
- [ ] Verify status badges are distinguishable
- [ ] Test with color blindness simulators

## Environment-Specific Testing

### Development
```bash
npm run dev
# Test against local Homer backend
```

### Production Build
```bash
npm run build
npm run start
# Test production-optimized bundle
```

### Docker
```bash
docker build -t homer-console:test .
docker run -p 3000:3000 -e CONSOLE_HOMER_API_URL=http://localhost:8080 homer-console:test
# Test Docker image
```

### Docker Compose
```bash
# From the repo root; HOMER_ADMIN_TOKEN must be set (e.g. in .env)
docker compose up
# Test full stack deployment
```

See [DEPLOYMENT.md § Docker Compose](DEPLOYMENT.md#docker-compose).

## Regression Testing

Before each release:
- [ ] Run full manual testing checklist
- [ ] Run automated test suite
- [ ] Test all environment configurations
- [ ] Verify backwards compatibility
- [ ] Test upgrade path from previous version

## Bug Reporting

When filing bugs, include:
1. Steps to reproduce
2. Expected behavior
3. Actual behavior
4. Browser/OS information
5. Console errors (if any)
6. Network tab (if relevant)
7. Screenshots/recordings

## Testing Tools

Recommended tools:
- **Browser DevTools**: Chrome/Firefox developer tools
- **React DevTools**: Component inspection
- **Network monitoring**: Charles Proxy, Fiddler
- **Accessibility**: axe DevTools, WAVE
- **Performance**: Lighthouse, WebPageTest
- **Screen readers**: NVDA, JAWS, VoiceOver

## Continuous Testing

Best practices:
- Test each PR before merging
- Run automated tests in CI/CD
- Perform smoke tests after deployment
- Monitor production for errors
- Collect user feedback
