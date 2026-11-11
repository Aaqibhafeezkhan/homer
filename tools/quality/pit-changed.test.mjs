import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import fc from 'fast-check';
import { execFileSync } from 'node:child_process';
import { DEFAULT_BUDGET_SEC, budgetSeconds, budgetText, changedSources, classGlobs, codeLineNumbers, codeLines, pitPath, withinChanged } from './pit-changed.mjs';
import { parseMutations, withKeys, writeReport } from './pit.mjs';

const SRC = 'app/app/src/main/kotlin';
const source = (pkg, body) => `package ${pkg}\n\nimport x.Y\n\n${body}\n`;

test('a source maps to its file class and each top-level declaration, nested classes included', () => {
  const text = source('homer.app.infra', 'class PathSafety {\n  class Inner\n}\nobject Limits\nfun helper(a: Int) = a\n');
  assert.deepEqual(classGlobs(`${SRC}/homer/app/infra/PathSafety.kt`, text), [
    'homer.app.infra.PathSafetyKt',
    'homer.app.infra.PathSafetyKt$*',
    'homer.app.infra.PathSafety',
    'homer.app.infra.PathSafety$*',
    'homer.app.infra.Limits',
    'homer.app.infra.Limits$*',
    'homer.app.infra.helper',
    'homer.app.infra.helper$*',
  ]);
});

test('a source without a package has no globs and no PIT path', () => {
  assert.deepEqual(classGlobs(`${SRC}/Main.kt`, 'fun main() {}\n'), []);
  assert.equal(pitPath(`${SRC}/Main.kt`, 'fun main() {}\n'), null);
});

test('the PIT path follows the package, not the directory', () => {
  assert.equal(pitPath(`${SRC}/homer/app/elsewhere/A.kt`, source('homer.app.infra', 'class A')), `${SRC}/homer/app/infra/A.kt`);
});

test('no glob holds a character that could widen it or split the Gradle property', () => {
  const identifier = fc.stringMatching(/^[A-Za-z_]\w{0,10}$/);
  const text = fc.tuple(fc.array(identifier, { minLength: 1, maxLength: 3 }), fc.array(identifier, { maxLength: 4 }), fc.string());
  fc.assert(
    fc.property(text, fc.string(), ([pkg, names, noise], fileName) => {
      const body = `${names.map((n) => `class ${n}`).join('\n')}\n${noise}`;
      return classGlobs(`${SRC}/${fileName}.kt`, source(pkg.join('.'), body)).every((g) => /^[\w.]+(\$\*)?$/.test(g) && !g.includes(','));
    }),
  );
});

test('only changed main sources count, test sources, scripts and unreadable files do not', () => {
  const files = {
    [`${SRC}/homer/app/A.kt`]: source('homer.app', 'class A'),
    'app/app/src/test/kotlin/homer/app/ATest.kt': source('homer.app', 'class ATest'),
    'app/app/build.gradle.kts': 'plugins {}',
    [`${SRC}/homer/app/Link.kt`]: null,
  };
  const changed = new Map([...Object.keys(files).map((f) => [f, new Set([5])]), [`${SRC}/homer/app/Deleted.kt`, new Set()]]);
  const { globs, lines } = changedSources(changed, (f) => files[f] ?? null);
  assert.deepEqual(globs, ['homer.app.A', 'homer.app.A$*', 'homer.app.AKt', 'homer.app.AKt$*']);
  assert.deepEqual([...lines.keys()], [`${SRC}/homer/app/A.kt`]);
});

test('comment, blank, package and import lines are no code lines', () => {
  const text = ['package homer.app', '', 'import x.Y', '/**', ' * Doc.', ' */', '// note', 'class A {', '    fun f() = 1 * 2', '}'].join('\n');
  assert.deepEqual([...codeLines(text, new Set([1, 2, 3, 4, 5, 6, 7, 8, 9, 11]))], [8, 9]);
});

test('block comments nest and may lack stars; a comment opener in a string or char literal is text', () => {
  const text = [
    'package a',
    '/* block',
    '   no star',
    '*/ val x = 1',
    'val s = "/* not a comment"',
    '// c',
    '/* a /* nested */ still */',
    'val t = 2 // tail',
    '"""raw',
    '/* inside raw',
    '""" + y',
    "val c = '\"'",
    'z',
  ].join('\n');
  assert.deepEqual([...codeLineNumbers(text)], [1, 4, 5, 8, 9, 10, 11, 12, 13]);
});

test('text inside a line comment or a closed block comment is never code', () => {
  const noNewline = fc.string().map((t) => t.replace(/[\r\n]/g, ' '));
  fc.assert(fc.property(fc.array(noNewline, { maxLength: 5 }), (lines) => codeLineNumbers(lines.map((l) => `// ${l}`).join('\n')).size === 0));
  const noMarkers = fc.string().filter((t) => !t.includes('*/') && !t.includes('/*') && !t.endsWith('*') && !t.endsWith('/'));
  fc.assert(fc.property(noMarkers, (body) => codeLineNumbers(`/*${body}*/`).size === 0));
});

test('a ${…} template is code again, its own literals and braces included', () => {
  const lines = (...text) => [...codeLineNumbers(text.join('\n'))];
  assert.deepEqual(lines('val s = "${ "a /* b" } c"', 'val t = 1'), [1, 2]);
  assert.deepEqual(lines('val s = "${ "a\\"b" } /* c"', 'val t = 1'), [1, 2]);
  assert.deepEqual(lines('val m = "${ listOf(1).map { it } }" /* x', 'y */', 'val t = 1'), [1, 3]);
  assert.deepEqual(lines("val c = '\\'' + \"/*\"", 'val t = 1'), [1, 2]);
});

test('a backticked name may hold quotes and comment openers', () => {
  assert.deepEqual([...codeLineNumbers("fun `it's /* fine`() = 1\n// note\nval t = 1")], [1, 3]);
});

test('a raw string closes at the last three quotes of a run', () => {
  assert.deepEqual([...codeLineNumbers('val r = """"a"""" // x\n// note\nval t = 1')], [1, 3]);
});

test('a template inside a raw string is code, and the raw string still closes', () => {
  assert.deepEqual([...codeLineNumbers('val r = """${ "a /* b" }"""\n// note\nval t = 1')], [1, 3]);
});

test('a source the lexer cannot read to a clean end counts as code on every line', () => {
  assert.deepEqual([...codeLineNumbers('val s = "open\n// a\n// b')], [1, 2, 3]);
  assert.deepEqual([...codeLineNumbers('/* never closed\nval t = 1')], [1, 2]);
  assert.deepEqual([...codeLineNumbers('val s = "${ x\n// a')], [1, 2]);
  assert.deepEqual([...codeLineNumbers('fun `never closed() = 1\n// a')], [1, 2]);
});

test('a source with any changed code line keeps all its changed lines, comment lines included', () => {
  const text = source('homer.app', 'class A {\n  // note\n  fun f() = 1\n}');
  const { lines } = changedSources(new Map([[`${SRC}/homer/app/A.kt`, new Set([6, 7])]]), () => text);
  // The lexer only decides whether PIT runs; a line it misread must still filter mutants.
  assert.deepEqual(lines.get(`${SRC}/homer/app/A.kt`).lines, new Set([6, 7]));
});

test('two changed sources with one PIT path are refused, not merged', () => {
  const text = source('homer.app.infra', 'class A');
  const changed = new Map([
    [`${SRC}/homer/app/infra/A.kt`, new Set([5])],
    [`${SRC}/homer/app/elsewhere/A.kt`, new Set([5])],
  ]);
  assert.throws(() => changedSources(changed, () => text), /share PIT source path/);
});

test('a source whose changed lines are all comments is left out', () => {
  const text = source('homer.app', '// changed comment\nclass A');
  assert.deepEqual(changedSources(new Map([[`${SRC}/homer/app/A.kt`, new Set([5])]]), () => text), { globs: [], lines: new Map() });
});

test('a Unit null-return mutant stays ignored after withinChanged rewrites its PIT path', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pit-changed-unit-'));
  const text = 'package homer.app.infra\n\nprivate suspend fun tick(delay: Long) {\n  delay(delay)\n}\n';
  const xml = '<mutations><mutation status=\'SURVIVED\'><sourceFile>A.kt</sourceFile><mutatedClass>homer.app.infra.AKt</mutatedClass><mutatedMethod>tick</mutatedMethod><methodDescription>(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;</methodDescription><lineNumber>3</lineNumber><description>replaced return value with null for homer/app/infra/AKt::tick#private suspend fun tick(</description></mutation></mutations>';
  // The Unit flag is stamped while the mutant still carries its PIT path, which the reader answers.
  const [keyed] = withKeys(parseMutations(xml), () => text);
  assert.equal(keyed.unitReturn, true);
  const lines = new Map([[`${SRC}/homer/app/infra/A.kt`, { path: `${SRC}/homer/app/elsewhere/A.kt`, lines: new Set([3]) }]]);
  const [changed] = withinChanged([keyed], lines);
  assert.equal(changed.path, `${SRC}/homer/app/elsewhere/A.kt`);
  assert.match(writeReport(dir, 'T', [changed], {}), /\(no mutants\): 0 finding\(s\)/);
  assert.match(fs.readFileSync(path.join(dir, 'mutation-backend-summary.txt'), 'utf8'), /ignored=1\n/);
});

test('only mutants on changed lines remain, with their repository path', () => {
  const lines = new Map([[`${SRC}/homer/app/infra/A.kt`, { path: `${SRC}/homer/app/elsewhere/A.kt`, lines: new Set([3, 4]) }]]);
  const m = (p, line) => ({ status: 'SURVIVED', path: p, line, method: 'f', description: 'd' });
  assert.deepEqual(withinChanged([m(`${SRC}/homer/app/infra/A.kt`, 3), m(`${SRC}/homer/app/infra/A.kt`, 9), m(`${SRC}/homer/app/B.kt`, 3)], lines), [
    m(`${SRC}/homer/app/elsewhere/A.kt`, 3),
  ]);
});

/**
 * Runs `main` in a temporary repository whose last commit adds `A.kt` with `text`, against a
 * stand-in for Gradle that records its arguments, writes `mutations` as PIT's report and exits
 * `exit`, or first hangs for `hang` seconds (and `exceptions` as the backend exceptions file, when
 * given; `budget` as MUTATE_BUDGET_SEC). The stand-in answers `--stop` at once, exiting `stopExit`.
 * Returns main's output (or the error it threw), what it wrote to stderr and the recorded arguments, if any.
 */
async function runMain(text, { mutations = [], exit = 0, exceptions, hang, budget, stopExit = 0 } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pit-changed-'));
  const git = (...args) => execFileSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@t', ...args], { cwd: dir, encoding: 'utf8' }).trim();
  git('init', '-q');
  git('commit', '-q', '--allow-empty', '-m', 'base');
  const base = git('rev-parse', 'HEAD');
  fs.mkdirSync(path.join(dir, `${SRC}/homer/app`), { recursive: true });
  fs.writeFileSync(path.join(dir, `${SRC}/homer/app/A.kt`), text);
  const xml = mutations.map((line) => `<mutation status='SURVIVED'><sourceFile>A.kt</sourceFile><mutatedClass>homer.app.A</mutatedClass><mutatedMethod>f</mutatedMethod><lineNumber>${line}</lineNumber><description>d</description></mutation>`);
  fs.mkdirSync(path.join(dir, 'app'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'app/report.xml'), `<mutations>${xml.join('')}</mutations>`);
  if (exceptions !== undefined) {
    fs.mkdirSync(path.join(dir, 'tools/quality/mutation-exceptions'), { recursive: true });
    fs.writeFileSync(path.join(dir, 'tools/quality/mutation-exceptions/backend.json'), exceptions);
  }
  const end = hang ? `exec sleep ${hang}` : `exit ${exit}`;
  const gradlew = `#!/bin/sh\nprintf "%s\\n" "$@" >> app/args.txt\n[ "$3" = --stop ] && exit ${stopExit}\nmkdir -p app/app/build/reports/pitest\ncp app/report.xml app/app/build/reports/pitest/mutations.xml\n${end}\n`;
  fs.writeFileSync(path.join(dir, 'app/gradlew'), gradlew, { mode: 0o755 });
  git('add', '-A');
  git('commit', '-q', '-m', 'change');
  const cwd = process.cwd();
  const saved = { MUTATE_BASE: process.env.MUTATE_BASE, MUTATE_BUDGET_SEC: process.env.MUTATE_BUDGET_SEC };
  process.env.MUTATE_BASE = base;
  if (budget === undefined) delete process.env.MUTATE_BUDGET_SEC;
  else process.env.MUTATE_BUDGET_SEC = budget;
  process.chdir(dir);
  const stderr = [];
  const error = console.error;
  console.error = (...line) => stderr.push(line.join(' '));
  try {
    const { main } = await import('./pit-changed.mjs');
    let output;
    try {
      output = main();
    } catch (error) {
      output = error;
    }
    const argsFile = path.join(dir, 'app/args.txt');
    const summary = path.join(dir, 'app/app/build/reports/pitest/mutation-backend-summary.txt');
    return {
      output,
      stderr: stderr.join('\n'),
      args: fs.existsSync(argsFile) ? fs.readFileSync(argsFile, 'utf8').trim().split('\n') : null,
      summary: fs.existsSync(summary) ? fs.readFileSync(summary, 'utf8') : null,
    };
  } finally {
    console.error = error;
    process.chdir(cwd);
    for (const [name, value] of Object.entries(saved)) {
      if (value === undefined) delete process.env[name];
      else process.env[name] = value;
    }
  }
}

test('main mutates the changed classes through -PpitestTargetClasses and reports only their changed lines', async () => {
  const { output, args } = await runMain(source('homer.app', 'class A {\n  fun f() = 1\n}'), { mutations: [6, 7] });
  assert.match(output, /^Backend mutation testing of the change \(score 0\.0%\): 2 finding\(s\)/);
  assert.ok(args.includes(':app:pitest'));
  assert.ok(args.includes('-PpitestTargetClasses=homer.app.A,homer.app.A$*,homer.app.AKt,homer.app.AKt$*'), args.join(' '));
});

test('main skips a comment-only change without running Gradle', async () => {
  const { output, args } = await runMain('package homer.app\n\n// only a note\n/* and a block */\n');
  assert.match(output, /skipped: no changed :app code lines/);
  assert.equal(args, null);
});

test('main fails when Gradle fails, rather than reporting', async () => {
  const { output } = await runMain(source('homer.app', 'class A'), { exit: 1 });
  assert.match(output.message, /:app:pitest exited 1/);
});

test('a PIT run over its budget is stopped and reported skipped, not failed', async () => {
  const started = Date.now();
  const { output, args, summary, stderr } = await runMain(source('homer.app', 'class A {\n  fun f() = 1\n}'), { mutations: [6], hang: 30, budget: '1' });
  assert.ok(Date.now() - started < 20_000, 'the budget, not the stand-in, ended the run');
  assert.equal(output, 'Backend mutation testing of the change (skipped: over the 1-second budget): 0 finding(s)\n');
  assert.equal(summary, 'findings=0\nskipped=over the 1-second budget\n');
  assert.deepEqual(args.slice(-3), ['-p', 'app', '--stop']);
  assert.doesNotMatch(stderr, /--stop failed/);
});

test('a failed daemon stop after the budget is logged, and the run still reports skipped', async () => {
  const { output, stderr } = await runMain(source('homer.app', 'class A {\n  fun f() = 1\n}'), { hang: 30, budget: '1', stopExit: 1 });
  assert.match(stderr, /gradlew --stop failed \(exit 1\): the Gradle daemon may still be running PIT/);
  assert.equal(output, 'Backend mutation testing of the change (skipped: over the 1-second budget): 0 finding(s)\n');
});

test('a PIT run inside its budget reports as before', async () => {
  const { output, args } = await runMain(source('homer.app', 'class A {\n  fun f() = 1\n}'), { mutations: [6], budget: '600' });
  assert.match(output, /^Backend mutation testing of the change \(score 0\.0%\): 1 finding\(s\)/);
  assert.ok(!args.includes('--stop'));
});

test('the budget defaults to 14 minutes and takes only a positive whole number of seconds', async () => {
  assert.equal(budgetSeconds(undefined), DEFAULT_BUDGET_SEC);
  assert.equal(budgetSeconds(''), 14 * 60);
  assert.equal(budgetSeconds('90'), 90);
  for (const bad of ['0', '-5', '1.5', '10m', ' 60', '1e3']) assert.throws(() => budgetSeconds(bad), /positive whole number/, bad);
  assert.equal(budgetText(14 * 60), '14-minute');
  assert.equal(budgetText(90), '90-second');
  const { output, args } = await runMain(source('homer.app', 'class A'), { budget: '10m' });
  assert.match(output.message, /MUTATE_BUDGET_SEC/);
  assert.equal(args, null);
});

test('the budget leaves the CI job room for its report steps', () => {
  const ci = fs.readFileSync(path.join(import.meta.dirname, '..', '..', '.github/workflows/ci.yml'), 'utf8');
  const job = /\n  homer-app-mutation:\n([\s\S]*?)\n  \S/.exec(ci)?.[1] ?? '';
  const minutes = Number(/timeout-minutes: (\d+)/.exec(job)?.[1]);
  assert.ok(DEFAULT_BUDGET_SEC / 60 + 5 <= minutes, `budget ${DEFAULT_BUDGET_SEC / 60} min vs job ${minutes} min`);
});

test('main fails on a malformed exceptions file before running Gradle', async () => {
  const { output, args } = await runMain(source('homer.app', 'class A'), { exceptions: '{"a.kt#m#d#x": ""}' });
  assert.match(output.message, /non-empty reason: a\.kt#m#d#x/);
  assert.equal(args, null);
});

test('the Gradle property main passes is the one the build script reads', () => {
  const build = fs.readFileSync(path.join(import.meta.dirname, '..', '..', 'app/app/build.gradle.kts'), 'utf8');
  assert.match(build, /targetClasses = providers\.gradleProperty\("pitestTargetClasses"\)/);
});

test('without a base the check reports itself skipped and runs no Gradle', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pit-changed-'));
  const cwd = process.cwd();
  const saved = process.env.MUTATE_BASE;
  delete process.env.MUTATE_BASE;
  process.chdir(dir);
  try {
    const { main } = await import('./pit-changed.mjs');
    assert.match(main(), /skipped: no MUTATE_BASE/);
    assert.equal(fs.readFileSync(path.join(dir, 'app/app/build/reports/pitest/mutation-backend-summary.txt'), 'utf8'), 'findings=0\nskipped=no MUTATE_BASE\n');
  } finally {
    process.chdir(cwd);
    if (saved !== undefined) process.env.MUTATE_BASE = saved;
  }
});
