/**
 * Diff mode of the quality gate, for coding agents checking their work in a loop (ADR-0082): runs
 * the checks of checks.json that a working-tree change can affect and prints what they find.
 * Usage (repository root, JAVA_HOME on a JDK 25): node tools/quality/changed.mjs [--base <rev>] [--only <check>[,<check>…]]
 *
 * The change is the working tree against `--base` (default `git merge-base origin/main HEAD`),
 * uncommitted and untracked files included. A check runs only when the change touches what it reads.
 * Tests run only where they relate to the change: a changed Kotlin file's own `<Name>*Test` classes
 * in its test package, else the tests naming its declarations; `vitest related` in the console. So
 * `crap` and `diff-coverage` judge only the changed functions and lines, and a Kotlin file no test
 * relates to is listed as not measured; CI's full run decides it. The other checks read the whole
 * project and report everything they find, since main has no findings: a change can make an export
 * unused or a query scan in a file it does not touch. Such findings are marked outside the change.
 * The `build` row also reports what fails CI outside the gate: compile errors, failed tests, ktlint,
 * ESLint errors and tsc errors (left out under --only). mutation-console (warn mode, minutes per run)
 * runs only when --only names it.
 *
 * Exit codes as the gate's: 0 no findings; 3 findings only in warn-mode checks; 1 findings in a
 * required check, a failed build or test, or a tool error.
 */
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { compare as compareDtos } from './dto-contract.mjs';
import { coverageLookup, isConsoleSource, isKotlinSource, measure, toFindings as coverageFindings } from './diff-coverage.mjs';
import { main as duplication } from './duplication.mjs';
import { parseSarif } from './detekt.mjs';
import { parseJUnit } from './architecture-backend.mjs';
import { exitCode, formatTable } from './gate.mjs';
import { changedFiles, worktreeChanges } from './git-diff.mjs';
import { diagnostic, parseLog } from './kotlin-warnings.mjs';
import { repoRelative, runMain } from './lib.mjs';
import { parseReport as queryPlans } from './query-plans.mjs';
import { parseReport as queryRepeats } from './query-repeats.mjs';

const OUT = 'reports/quality/changed';
// Above this many related test classes in a module its whole test task runs: the filter saves little.
const MAX_RELATED_TESTS = 40;
// Modules whose tests the diff mode runs; :architecture:test always runs whole, e2e needs Docker.
const TESTED_MODULES = new Set(['app', 'agentdebug', 'homer-runner']);
const KOTLIN_FILE = /^app\/([\w-]+)\/src\/(main|test)\/kotlin\/(?:(.+)\/)?([^/]+)\.kt$/;
// Kotlin sources only: a build-script change compiles nothing the diff mode measures (docs/quality-checks.md).
const BACKEND = /^app\/(?!buildSrc\/).+\.kt$/;
const CONSOLE = /^console\/.+\.(?:[cm]?js|tsx?|json)$/;
const DTO_SOURCES = /^(?:app\/app\/src\/main\/kotlin\/.+\.kt|console\/types\/homer\.ts)$/;
const DTO_TEST = 'homer.app.models.DtoContractTest';
const BACKEND_CHECKS = ['kotlin-warnings', 'architecture-backend', 'detekt', 'query-plans', 'query-repeats'];
const CONSOLE_CHECKS = { 'eslint-warnings': 'quality:eslint', 'architecture-console': 'quality:architecture', 'unused-console': 'quality:unused', 'size-console': 'quality:size' };
// Checks that judge only the changed lines; every other check reports all it finds.
const LINE_SCOPED = new Set(['crap', 'diff-coverage', 'mutation-console']);

export function parseArgs(argv) {
  const options = { base: undefined, only: undefined };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--base') options.base = argv[++i];
    else if (argv[i] === '--only') options.only = argv[++i]?.split(',').filter(Boolean);
    else throw new Error(`unknown argument ${argv[i]}: usage: changed.mjs [--base <rev>] [--only <check>[,<check>…]]`);
    if (argv[i] === undefined) throw new Error(`${argv[i - 1]} needs a value`);
  }
  return options;
}

/** The checks to run for `files`, as names of `checks`; `only` narrows them and alone selects warn-mode checks. */
export function selectChecks(files, checks, only) {
  for (const name of only ?? []) if (!checks.some((c) => c.name === name)) throw new Error(`unknown check ${name}`);
  const backend = files.some((f) => BACKEND.test(f));
  const front = files.some((f) => CONSOLE.test(f));
  const wanted = new Set([
    ...(backend ? BACKEND_CHECKS : []),
    ...(front ? Object.keys(CONSOLE_CHECKS) : []),
    ...(backend || front ? ['crap', 'diff-coverage', 'duplication'] : []),
    ...(files.some((f) => DTO_SOURCES.test(f)) ? ['dto-contract'] : []),
    ...(front ? ['mutation-console'] : []),
  ]);
  return new Set(
    checks
      .filter((c) => wanted.has(c.name) && (only ? only.includes(c.name) : c.mode === 'required'))
      .map((c) => c.name),
  );
}

/** `{module, sourceSet, dir, name}` of a Kotlin source path, else null. */
export function kotlinFile(file) {
  const match = KOTLIN_FILE.exec(file);
  return match && { module: match[1], sourceSet: match[2], dir: match[3] ?? '', name: match[4] };
}

/** Top-level class, object, interface and function names a Kotlin source declares. */
export function declaredNames(text) {
  const modifiers = String.raw`(?:(?:public|internal|private|protected|data|sealed|abstract|open|enum|value|annotation|inline|suspend|operator|infix|tailrec|fun)\s+)*`;
  const types = new RegExp(String.raw`^${modifiers}(?:class|object|interface|typealias)\s+(\w+)`, 'gm');
  const functions = new RegExp(String.raw`^${modifiers}fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)\s*\(`, 'gm');
  return [...new Set([...text.matchAll(types), ...text.matchAll(functions)].map((m) => m[1]))];
}

// A file name may hold any character but `/` (`A(1).kt`); declared names are \w+ already.
const escapeRegExp = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const packageOf = (text) => /^\s*package\s+([\w.]+)/m.exec(text)?.[1];
const isTestClass = (file) => file.sourceSet === 'test' && file.name.endsWith('Test');

/**
 * Test classes per module for the changed Kotlin files: a changed test class itself; for any other
 * file, the `<Name>*Test` classes of its module's test package, else the module's test classes that
 * name one of its declarations. `unmeasured` lists the changed main files no test relates to.
 */
export function relatedTests(changed, testFiles, read) {
  const tests = new Map();
  const unmeasured = [];
  const testClasses = testFiles.map((f) => ({ path: f, ...kotlinFile(f) })).filter(isTestClass);
  const add = (module, file) => {
    if (!tests.has(module)) tests.set(module, new Set());
    tests.get(module).add(`${packageOf(read(file.path)) ?? file.dir.replaceAll('/', '.')}.${file.name}`);
  };
  for (const changedPath of changed) {
    const file = kotlinFile(changedPath);
    if (!file) continue;
    if (!TESTED_MODULES.has(file.module)) {
      if (file.sourceSet === 'main') unmeasured.push(changedPath);
      continue;
    }
    if (isTestClass(file)) {
      add(file.module, { path: changedPath, ...file });
      continue;
    }
    const inModule = testClasses.filter((t) => t.module === file.module);
    let related = inModule.filter((t) => t.dir === file.dir && t.name.startsWith(file.name));
    if (related.length === 0) {
      const names = new RegExp(String.raw`\b(?:${[file.name, ...declaredNames(read(changedPath))].map(escapeRegExp).join('|')})\b`);
      related = inModule.filter((t) => names.test(read(t.path)));
    }
    related.forEach((t) => add(file.module, t));
    if (related.length === 0 && file.sourceSet === 'main') unmeasured.push(changedPath);
  }
  return { tests, unmeasured };
}

/**
 * The Gradle tasks of one diff-mode backend run: compile (and with `lint` ktlint) the changed
 * `modules`, run each module's `tests` (test classes), and report coverage (`crapReport`, else
 * `koverXmlReport`) of `covered` ones.
 */
export function gradleTasks({ modules, tests, covered, crap, architecture, queryPlans, lint }) {
  const tasks = [...modules].sort().flatMap((m) => [`:${m}:testClasses`, ...(lint ? [`:${m}:ktlintCheck`] : [])]);
  for (const [module, classes] of [...tests].sort(([a], [b]) => a.localeCompare(b))) {
    tasks.push(`:${module}:test`);
    if (classes.size <= MAX_RELATED_TESTS) for (const name of [...classes].sort()) tasks.push('--tests', `${name}*`);
    if (covered.has(module)) tasks.push(`:${module}:${crap ? 'crapReport' : 'koverXmlReport'}`);
  }
  if (architecture) tasks.push(':architecture:test');
  if (queryPlans && tests.has('app')) tasks.push(':app:queryPlanReport');
  return tasks;
}

/**
 * Compile errors, ktlint violations, failed tests and other failed tasks in a plain-console Gradle
 * log, one finding each. `ignored` tasks are left out: `:architecture:test` fails exactly when the
 * architecture-backend check has findings.
 */
export function buildFailures(log, root, ignored = new Set([':architecture:test'])) {
  const findings = [];
  const failedTasks = [];
  let task = '';
  for (const line of log.split('\n')) {
    const header = /^> Task (\S+)( FAILED)?/.exec(line);
    if (header) {
      task = header[1];
      if (header[2]) failedTasks.push(task);
    }
    const error = diagnostic(line, root);
    if (error?.kind === 'e') findings.push({ task, path: error.path, line: error.line, message: error.message, severity: 'major' });
    const lint = /^(\/\S+?\.kts?):(\d+):\d+ (.+)$/.exec(line);
    if (lint) findings.push({ task, path: repoRelative(root, lint[1]), line: Number(lint[2]), message: `ktlint: ${lint[3]}`, severity: 'minor' });
    const failed = /^(\S+ > .+) FAILED$/.exec(line);
    if (failed && !ignored.has(task)) findings.push({ task, path: task, line: 0, message: `${failed[1]} failed`, severity: 'major' });
  }
  for (const failedTask of failedTasks) {
    if (ignored.has(failedTask) || findings.some((f) => f.task === failedTask)) continue;
    findings.push({ task: failedTask, path: failedTask, line: 0, message: 'failed; see the log', severity: 'major' });
  }
  if (failedTasks.length === 0 && /^BUILD FAILED/m.test(log)) findings.push({ path: 'gradle', line: 0, message: 'build failed; see the log', severity: 'major' });
  return findings.map(({ task: _task, ...f }) => f);
}

/** Findings of a Code Quality file, `end` set where the entry has one (CRAP: the function's last line). */
export const fromCodeQuality = (entries) =>
  entries.map((e) => ({ path: e.location.path, line: e.location.lines.begin, end: e.location.lines.end, message: e.description, severity: e.severity }));

/** Whether a finding's lines meet the change. */
export function touches(finding, changed) {
  const lines = changed.get(finding.path);
  if (!lines) return false;
  for (let n = finding.line; n <= (finding.end ?? finding.line); n++) if (lines.has(n)) return true;
  return false;
}

/** A line-scoped check keeps the findings on changed lines; any other check marks those outside the change. */
export function scope(check, findings, changed) {
  if (check === 'build') return findings;
  if (LINE_SCOPED.has(check)) return findings.filter((f) => touches(f, changed));
  return findings.map((f) => (changed.has(f.path) ? f : { ...f, outside: true }));
}

export function formatFindings(results) {
  const out = [];
  for (const r of results) {
    if (r.error) out.push(`\n${r.name}: error: ${r.error}`);
    if (r.list.length === 0) continue;
    out.push(`\n${r.name}:`);
    for (const f of r.list) out.push(`  ${f.line > 0 ? `${f.path}:${f.line}` : f.path}  ${f.message}${f.outside ? '  (outside the change)' : ''}`);
  }
  return out.join('\n');
}

// --- running the tools ------------------------------------------------------------------------

const progress = (message) => process.stderr.write(`changed: ${message}\n`);
const git = (args, cwd) => execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();
const readJson = (file) => JSON.parse(fs.readFileSync(file, 'utf8'));
const readCodeQuality = (file) => (fs.existsSync(file) ? fromCodeQuality(readJson(file)) : []);
const kotlinTests = (root) =>
  fs.globSync('app/*/src/test/kotlin/**/*Test.kt', { cwd: root }).map((f) => f.split(path.sep).join('/'));

function run(command, args, { cwd, env, log }) {
  const fd = fs.openSync(log, 'w');
  try {
    return spawnSync(command, args, { cwd, env: { ...process.env, ...env }, stdio: ['ignore', fd, fd] }).status;
  } finally {
    fs.closeSync(fd);
  }
}

function gradle(root, tasks, log, extra = []) {
  progress(`gradle ${tasks.filter((t) => t.startsWith(':')).join(' ')} (log: ${log})`);
  const status = run(path.join(root, 'app', 'gradlew'), ['-p', 'app', '--continue', '--console=plain', ...extra, ...tasks], { cwd: root, log });
  return { status, text: fs.readFileSync(log, 'utf8') };
}

function backend(ctx) {
  const { root, files, changed, selected, report } = ctx;
  const kotlin = files.filter((f) => fs.existsSync(path.join(root, f)) && (kotlinFile(f)?.module ?? 'buildSrc') !== 'buildSrc');
  const modules = new Set(kotlin.map((f) => kotlinFile(f).module));
  const coverage = modules.size > 0 && (selected.has('crap') || selected.has('diff-coverage'));
  const queries = selected.has('query-plans') || selected.has('query-repeats');
  const texts = new Map();
  const read = (f) => texts.get(f) ?? texts.set(f, fs.readFileSync(path.join(root, f), 'utf8')).get(f);
  const { tests, unmeasured } = coverage || queries ? relatedTests(kotlin, kotlinTests(root), read) : { tests: new Map(), unmeasured: [] };
  const covered = new Set(coverage ? [...tests.keys()].filter((m) => modules.has(m)) : []);
  if (selected.has('dto-contract')) tests.set('app', new Set([...(tests.get('app') ?? []), DTO_TEST]));
  const build = (...parts) => path.join(root, 'app', ...parts);
  // A failed test task leaves the previous run's reports behind; read none of those.
  for (const m of covered) for (const dir of ['kover', 'crap']) fs.rmSync(build(m, 'build', 'reports', dir), { recursive: true, force: true });
  fs.rmSync(build('app', 'build', 'reports', 'query-plans', 'query-plans.json'), { force: true });
  const tasks = gradleTasks({
    modules,
    tests,
    covered,
    crap: selected.has('crap'),
    architecture: selected.has('architecture-backend'),
    queryPlans: queries,
    lint: !ctx.only,
  });
  const { status, text } = gradle(root, tasks, path.join(OUT, 'gradle.log'));
  const compiled = !/^e: /m.test(text);
  if (!compiled) ctx.notes.push('the backend does not compile: its tests, coverage, query plans and detekt did not run');
  // queryPlanReport refuses an empty capture: the related tests ran no SQL, so there is nothing to plan.
  const noSql = !fs.existsSync(build('app', 'build', 'reports', 'query-plans', 'captured'));
  if (compiled && noSql && queries && tests.has('app')) ctx.notes.push('query-plans, query-repeats: the related tests ran no SQL');
  const failures = buildFailures(text, root, new Set([':architecture:test', ...(noSql ? [':app:queryPlanReport'] : [])]));
  // No BUILD FAILED line: Gradle itself did not start (a wrong JAVA_HOME, say).
  report('build', failures, status !== 0 && !/^BUILD FAILED/m.test(text) ? `gradle exited ${status}; see ${OUT}/gradle.log` : undefined);
  if (selected.has('kotlin-warnings')) report('kotlin-warnings', parseLog(text, root).findings);
  const ran = new Set([...covered].filter((m) => fs.existsSync(build(m, 'build', 'reports', 'kover', 'report.xml'))));
  for (const m of covered) if (compiled && !ran.has(m)) ctx.notes.push(`crap, diff-coverage: not measured in :${m}, whose tests or build failed`);
  const measured = (f) => !unmeasured.includes(f) && ran.has(kotlinFile(f)?.module);
  if (selected.has('crap')) {
    const crap = [...ran].flatMap((m) => readCodeQuality(build(m, 'build', 'reports', 'crap', 'gl-code-quality-crap.json')));
    report('crap', crap.filter((f) => measured(f.path)));
  }
  if (selected.has('diff-coverage')) report('diff-coverage', coverageFindings(measure(subset(changed, (f) => isKotlinSource(f) && measured(f)), coverageLookup(root))));
  if (compiled && selected.has('architecture-backend')) {
    const dir = build('architecture', 'build', 'test-results', 'test');
    const xml = fs.existsSync(dir) ? fs.readdirSync(dir).filter((f) => f.endsWith('.xml')) : [];
    report('architecture-backend', xml.flatMap((f) => parseJUnit(fs.readFileSync(path.join(dir, f), 'utf8'))));
  }
  const plans = build('app', 'build', 'reports', 'query-plans', 'query-plans.json');
  if (fs.existsSync(plans)) {
    if (selected.has('query-plans')) report('query-plans', queryPlans(readJson(plans)));
    if (selected.has('query-repeats')) report('query-repeats', queryRepeats(readJson(plans), undefined, { reportStale: false }));
  }
  if (compiled && selected.has('detekt') && modules.size > 0) detekt(ctx, modules);
  return unmeasured;
}

function detekt({ root, report }, modules) {
  const log = path.join(OUT, 'detekt.log');
  const sarif = [...modules].sort().map((m) => path.join(root, 'app', m, 'build', 'reports', 'detekt', 'main.sarif'));
  sarif.forEach((f) => fs.rmSync(f, { force: true }));
  const { status } = gradle(root, [...modules].sort().map((m) => `:${m}:detektMain`), log, ['-Dorg.gradle.jvmargs=-XX:MaxMetaspaceSize=1g']);
  const error = status === 0 ? undefined : `detektMain exited ${status}; see ${log}`;
  report('detekt', sarif.filter((f) => fs.existsSync(f)).flatMap((f) => parseSarif(readJson(f), root)), error);
}

function subset(changed, keep) {
  return new Map([...changed].filter(([file]) => keep(file)));
}

function npm(ctx, script, env = {}) {
  const log = path.join(OUT, `${script.replace(':', '-')}.log`);
  progress(`npm run ${script} (log: ${log})`);
  return run('npm', ['run', '-s', script], { cwd: path.join(ctx.root, 'console'), env, log });
}

/**
 * File arguments for a tool's argv. A changed path is branch content and may read as an option
 * (`console/--config=evil.ts`), so each one starts with `./` (CWE-88). `--` would do for ESLint, but
 * Vitest's CLI parser moves what follows it out of the file list.
 */
export const asPaths = (files) => files.map((f) => `./${f}`);

/** Runs the console tests related to the change, with coverage: `ran`, `failed`, or `none` when no test imports it. */
function vitest({ root, changed, report }) {
  const files = [...changed.keys()].filter((f) => /^console\/.+\.(?:[cm]?js|tsx?)$/.test(f)).map((f) => f.slice('console/'.length));
  const coverageFile = path.join(root, 'console', 'coverage', 'coverage-final.json');
  fs.rmSync(coverageFile, { force: true });
  if (files.length === 0) return 'none';
  const log = path.join(OUT, 'vitest.log');
  progress(`vitest related ${files.join(' ')} (log: ${log})`);
  const status = run(path.join(root, 'console', 'node_modules', '.bin', 'vitest'), ['related', ...asPaths(files), '--run', '--coverage', '--coverage.reporter=json'], { cwd: path.join(root, 'console'), log });
  const failed = [...fs.readFileSync(log, 'utf8').matchAll(/^ FAIL +(.+)$/gm)].map((m) => ({ path: 'console', line: 0, message: `${m[1].trim()} failed`, severity: 'major' }));
  report('build', failed, status !== 0 && failed.length === 0 ? `vitest exited ${status}; see ${log}` : undefined);
  if (status !== 0) return 'failed';
  return fs.existsSync(coverageFile) ? 'ran' : 'none';
}

/** ESLint errors in `messages` of its JSON output and tsc errors in its output, as findings. */
export function consoleErrors(eslintJson, tscOutput, consoleDir) {
  const lint = JSON.parse(eslintJson || '[]').flatMap((r) =>
    r.messages
      .filter((m) => m.severity === 2)
      .map((m) => ({ path: repoRelative(path.dirname(consoleDir), r.filePath), line: m.line ?? 1, message: `eslint: ${m.ruleId ?? 'parse'}: ${m.message}`, severity: 'major' })),
  );
  const types = [...tscOutput.matchAll(/^(.+?)\((\d+),\d+\): error (TS\d+: .+)$/gm)].map((m) => ({
    path: `console/${m[1]}`,
    line: Number(m[2]),
    message: `tsc: ${m[3]}`,
    severity: 'major',
  }));
  return [...lint, ...types];
}

/**
 * What `npm run lint` and the type check of `npm run build` fail on: ESLint errors in the changed
 * files and any tsc error. It spawns the ESLint CLI as `npm run lint` does, not the API
 * eslint-warnings.mjs uses for the whole project's warnings, so errors are read exactly as CI reads them.
 */
function consoleBuild({ root, changed, report }) {
  const consoleDir = path.join(root, 'console');
  const files = [...changed.keys()].filter((f) => /^console\/.+\.(?:[cm]?js|tsx?)$/.test(f)).map((f) => f.slice('console/'.length));
  if (files.length === 0) return;
  progress(`eslint (errors) and tsc --noEmit (logs: ${OUT}/eslint.log, ${OUT}/tsc.log)`);
  const eslint = tool(consoleDir, 'eslint', ['--quiet', '--format', 'json', ...asPaths(files)]);
  const tsc = tool(consoleDir, 'tsc', ['--noEmit', '-p', '.']);
  const findings = consoleErrors(eslint.stdout, tsc.stdout, consoleDir);
  // Both exit non-zero when they find errors; non-zero with none parsed means the tool itself failed.
  const broken = [eslint, tsc].filter((t) => t.status !== 0 && !findings.some((f) => f.message.startsWith(`${t.name}:`)));
  report('build', findings, broken.length ? broken.map((t) => `${t.name} exited ${t.status}; see ${OUT}/${t.name}.log`).join('; ') : undefined);
}

/** Runs a console `node_modules/.bin` tool, logging its output to `<OUT>/<name>.log`. */
function tool(consoleDir, name, args) {
  const result = spawnSync(path.join(consoleDir, 'node_modules', '.bin', name), args, { cwd: consoleDir, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });
  fs.writeFileSync(path.join(OUT, `${name}.log`), `${result.stdout ?? ''}${result.stderr ?? ''}${result.error ? `\n${result.error.message}` : ''}`);
  return { name, status: result.error ? -1 : result.status, stdout: result.stdout ?? '' };
}

function consoleChecks(ctx) {
  const { root, changed, selected, report } = ctx;
  if (!ctx.only) consoleBuild(ctx);
  if (selected.has('crap') || selected.has('diff-coverage')) {
    const sources = subset(changed, isConsoleSource);
    const tests = vitest(ctx);
    if (tests === 'failed') ctx.notes.push('crap, diff-coverage: not measured in the console, whose tests failed');
    else if (tests === 'none') {
      const untested = [...sources.keys()].map((f) => ({ path: f, line: 1, message: 'no test imports this file', severity: 'major' }));
      report('diff-coverage', untested);
    } else {
      if (selected.has('crap')) {
        if (npm(ctx, 'crap') !== 0) report('crap', [], `npm run crap failed; see ${OUT}/crap.log`);
        else report('crap', readCodeQuality(path.join(root, 'console', 'coverage', 'crap', 'gl-code-quality-crap.json')));
      }
      if (selected.has('diff-coverage')) report('diff-coverage', coverageFindings(measure(sources, coverageLookup(root))));
    }
  }
  for (const [check, script] of Object.entries(CONSOLE_CHECKS)) {
    if (!selected.has(check)) continue;
    if (npm(ctx, script) !== 0) report(check, [], `npm run ${script} failed; see ${OUT}/${script.replace(':', '-')}.log`);
    else report(check, readCodeQuality(path.join(root, 'console', 'reports', 'quality', check, `gl-code-quality-${check}.json`)));
  }
  if (selected.has('mutation-console')) {
    const status = npm(ctx, 'mutation:changed', { MUTATE_BASE: ctx.base, MUTATE_WORKTREE: '1' });
    if (status !== 0) report('mutation-console', [], `npm run mutation:changed failed; see ${OUT}/mutation-changed.log`);
    else report('mutation-console', readCodeQuality(path.join(root, 'console', 'reports', 'quality', 'mutation-console', 'gl-code-quality-mutation-console.json')));
  }
}

function dtoContract(ctx) {
  const { root, report } = ctx;
  const backendFile = path.join(root, 'app', 'app', 'build', 'reports', 'dto-contract', 'backend-dtos.json');
  if (npm(ctx, 'quality:dto-types') !== 0) return report('dto-contract', [], `npm run quality:dto-types failed; see ${OUT}/quality-dto-types.log`);
  if (!fs.existsSync(backendFile)) return report('dto-contract', [], 'DtoContractTest wrote no backend-dtos.json');
  report('dto-contract', compareDtos(readJson(backendFile), readJson(path.join(root, 'console', 'reports', 'dto-contract', 'console-dtos.json'))).findings);
}

function results(checks, found, errors, changed) {
  const rows = [{ name: 'build', mode: 'required' }, ...checks].map((check) => {
    // A selected check the run skipped (no module compiled, no related test in :app) is not run, not ok.
    const ran = found.has(check.name) || errors.has(check.name);
    const list = scope(check.name, found.get(check.name) ?? [], changed);
    const error = errors.get(check.name);
    let status;
    if (!ran) status = 'not run';
    else if (error) status = 'error';
    else if (list.length === 0) status = 'ok';
    else status = check.mode === 'required' ? 'failed' : 'warning';
    return { ...check, findings: list.length, list, status, error, missing: [] };
  });
  return rows.filter((r) => r.name !== 'build' || r.status !== 'not run');
}

export function main(argv) {
  const options = parseArgs(argv);
  const root = git(['rev-parse', '--show-toplevel'], process.cwd());
  process.chdir(root);
  const base = options.base ?? git(['merge-base', 'origin/main', 'HEAD'], root);
  const files = changedFiles(base, root);
  const head = `Diff mode against ${base.slice(0, 12)}: ${files.length} changed file(s)`;
  if (files.length === 0) return `${head}\n`;
  const { checks } = readJson(path.join(import.meta.dirname, 'checks.json'));
  const selected = selectChecks(files, checks, options.only);
  const changed = worktreeChanges(base, ['app', 'console'], root);
  fs.mkdirSync(OUT, { recursive: true });
  const found = new Map();
  const errors = new Map();
  const report = (check, findings, error) => {
    found.set(check, [...(found.get(check) ?? []), ...findings]);
    if (error) errors.set(check, error);
  };
  const ctx = { root, base, files, changed, selected, report, notes: [], only: options.only };
  let unmeasured = [];
  const step = (fn) => {
    try {
      return fn();
    } catch (error) {
      report('build', [], error.message);
    }
  };
  const backendChecks = [...BACKEND_CHECKS, 'dto-contract'].some((c) => selected.has(c));
  const coverage = selected.has('crap') || selected.has('diff-coverage');
  if (backendChecks || (coverage && files.some((f) => BACKEND.test(f)))) unmeasured = step(() => backend(ctx)) ?? [];
  if (files.some((f) => CONSOLE.test(f))) step(() => consoleChecks(ctx));
  if (selected.has('dto-contract')) step(() => dtoContract(ctx));
  if (selected.has('duplication')) {
    step(() => {
      duplication([path.join(OUT, 'duplication')]);
      report('duplication', readCodeQuality(path.join(OUT, 'duplication', 'gl-code-quality-duplication.json')));
    });
  }
  const rows = results(checks, found, errors, changed);
  const notes = [
    unmeasured.length ? `Not measured (no related test; CI's full run decides crap and diff-coverage):\n${unmeasured.map((f) => `  ${f}`).join('\n')}` : '',
    files.some((f) => f.startsWith('app/buildSrc/')) ? 'app/buildSrc is a build of its own: run app/gradlew -p app/buildSrc check' : '',
    ...ctx.notes,
  ].filter(Boolean);
  process.exitCode = rows.some((r) => r.status === 'error') ? 1 : exitCode(rows);
  return `${head}\n\n${formatTable(rows)}\n${formatFindings(rows)}${notes.map((n) => `\n\n${n}`).join('')}\n`;
}

runMain(import.meta, main);
