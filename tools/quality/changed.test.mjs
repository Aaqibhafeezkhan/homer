import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  asPaths,
  buildFailures,
  consoleErrors,
  declaredNames,
  formatFindings,
  fromCodeQuality,
  gradleTasks,
  kotlinFile,
  parseArgs,
  relatedTests,
  scope,
  selectChecks,
} from './changed.mjs';

const CHECKS = [
  'crap',
  'kotlin-warnings',
  'architecture-backend',
  'eslint-warnings',
  'architecture-console',
  'unused-console',
  'duplication',
  'diff-coverage',
  'size-console',
  'dto-contract',
  'detekt',
  'query-plans',
  'query-repeats',
].map((name) => ({ name, mode: 'required' }));
const ALL = [...CHECKS, { name: 'mutation-console', mode: 'warn' }];
const SRC = 'app/app/src/main/kotlin/homer/app';
const TEST = 'app/app/src/test/kotlin/homer/app';

test('parseArgs reads --base and --only and rejects anything else', () => {
  assert.deepEqual(parseArgs(['--base', 'abc', '--only', 'crap,detekt']), { base: 'abc', only: ['crap', 'detekt'] });
  assert.deepEqual(parseArgs([]), { base: undefined, only: undefined });
  assert.throws(() => parseArgs(['--base']), /--base needs a value/);
  assert.throws(() => parseArgs(['--fast']), /unknown argument --fast/);
});

test('a console-only change selects the console checks and the shared ones', () => {
  assert.deepEqual(
    [...selectChecks(['console/lib/a.ts'], ALL)].sort(),
    ['architecture-console', 'crap', 'diff-coverage', 'duplication', 'eslint-warnings', 'size-console', 'unused-console'],
  );
});

test('a backend change selects the backend checks, dto-contract only for main sources', () => {
  const backend = ['architecture-backend', 'crap', 'detekt', 'diff-coverage', 'duplication', 'kotlin-warnings', 'query-plans', 'query-repeats'];
  assert.deepEqual([...selectChecks([`${TEST}/ATest.kt`], ALL)].sort(), backend);
  assert.deepEqual([...selectChecks([`${SRC}/A.kt`], ALL)].sort(), [...backend, 'dto-contract'].sort());
  assert.ok(selectChecks(['console/types/homer.ts'], ALL).has('dto-contract'));
});

test('docs, build scripts, buildSrc and tooling select nothing', () => {
  const files = ['docs/x.md', 'app/app/build.gradle.kts', 'app/settings.gradle.kts', 'app/buildSrc/src/main/kotlin/CrapScore.kt', 'tools/quality/gate.mjs', 'console/README.md'];
  assert.equal(selectChecks(files, ALL).size, 0);
});

test('--only narrows the selection and alone selects a warn-mode check', () => {
  assert.deepEqual([...selectChecks(['console/lib/a.ts'], ALL, ['eslint-warnings', 'detekt'])], ['eslint-warnings']);
  assert.deepEqual([...selectChecks(['console/lib/a.ts'], ALL, ['mutation-console'])], ['mutation-console']);
  assert.ok(!selectChecks(['console/lib/a.ts'], ALL).has('mutation-console'));
  assert.throws(() => selectChecks([], ALL, ['nope']), /unknown check nope/);
});

test('kotlinFile splits a Kotlin source path', () => {
  assert.deepEqual(kotlinFile(`${SRC}/services/Foo.kt`), { module: 'app', sourceSet: 'main', dir: 'homer/app/services', name: 'Foo' });
  assert.deepEqual(kotlinFile('app/agentdebug/src/test/kotlin/Root.kt'), { module: 'agentdebug', sourceSet: 'test', dir: '', name: 'Root' });
  assert.equal(kotlinFile('console/lib/a.ts'), null);
});

test('declaredNames lists top-level types and functions, extension receivers left out', () => {
  const source = [
    'package x',
    'data class Foo(val a: Int)',
    'internal sealed interface Bar',
    'private object Baz',
    'fun <T> Route.fooRoutes(deps: T) {}',
    'suspend fun run() = Unit',
    '    fun nested() {}',
  ].join('\n');
  assert.deepEqual(declaredNames(source), ['Foo', 'Bar', 'Baz', 'fooRoutes', 'run']);
});

test('relatedTests: a changed test runs itself; a main file its own tests, else the tests naming it', () => {
  const files = {
    [`${TEST}/services/FooServiceTest.kt`]: 'package homer.app.services\nclass FooServiceTest',
    [`${TEST}/services/FooServicePropertyTest.kt`]: 'package homer.app.services\nclass FooServicePropertyTest',
    [`${TEST}/routes/RoutesTest.kt`]: 'package homer.app.routes\n// uses redact()',
    [`${TEST}/OtherTest.kt`]: 'package homer.app\nclass OtherTest',
    [`${SRC}/services/FooService.kt`]: 'package homer.app.services\nclass FooService',
    [`${SRC}/Redaction.kt`]: 'package homer.app\nfun redact(s: String) = s',
    [`${SRC}/Lonely.kt`]: 'package homer.app\nclass Lonely',
  };
  const read = (f) => files[f];
  const tests = Object.keys(files).filter((f) => f.endsWith('Test.kt'));
  const changed = [`${SRC}/services/FooService.kt`, `${SRC}/Redaction.kt`, `${SRC}/Lonely.kt`, `${TEST}/OtherTest.kt`, 'app/e2e/src/main/kotlin/E.kt', 'console/x.ts'];
  const { tests: related, unmeasured } = relatedTests(changed, tests, read);
  assert.deepEqual(
    [...related.get('app')].sort(),
    ['homer.app.OtherTest', 'homer.app.routes.RoutesTest', 'homer.app.services.FooServicePropertyTest', 'homer.app.services.FooServiceTest'],
  );
  assert.deepEqual(unmeasured, [`${SRC}/Lonely.kt`, 'app/e2e/src/main/kotlin/E.kt']);
});

test('relatedTests reads a file name with regex characters literally', () => {
  const files = {
    [`${SRC}/A(1).kt`]: 'package homer.app\nclass Odd',
    [`${TEST}/OddTest.kt`]: 'package homer.app\n// Odd',
    [`${TEST}/OtherTest.kt`]: 'package homer.app\n// A1',
  };
  const { tests } = relatedTests([`${SRC}/A(1).kt`], [`${TEST}/OddTest.kt`, `${TEST}/OtherTest.kt`], (f) => files[f]);
  assert.deepEqual([...tests.get('app')], ['homer.app.OddTest']);
});

test('gradleTasks lints the changed modules when asked', () => {
  assert.deepEqual(gradleTasks({ modules: new Set(['app']), tests: new Map(), covered: new Set(), lint: true }), [':app:testClasses', ':app:ktlintCheck']);
});

test('gradleTasks compiles changed modules, filters tests and reports coverage of covered modules', () => {
  const tests = new Map([
    ['app', new Set(['homer.app.BTest', 'homer.app.ATest'])],
    ['agentdebug', new Set(['homer.agentdebug.CTest'])],
  ]);
  assert.deepEqual(gradleTasks({ modules: new Set(['app', 'architecture']), tests, covered: new Set(['app']), crap: true, architecture: true, queryPlans: true }), [
    ':app:testClasses',
    ':architecture:testClasses',
    ':agentdebug:test',
    '--tests',
    'homer.agentdebug.CTest*',
    ':app:test',
    '--tests',
    'homer.app.ATest*',
    '--tests',
    'homer.app.BTest*',
    ':app:crapReport',
    ':architecture:test',
    ':app:queryPlanReport',
  ]);
});

test('gradleTasks runs a module whole above the related-test bound', () => {
  const many = new Set(Array.from({ length: 41 }, (_, i) => `homer.app.T${i}Test`));
  assert.deepEqual(gradleTasks({ modules: new Set(), tests: new Map([['app', many]]), covered: new Set(['app']), crap: false }), [':app:test', ':app:koverXmlReport']);
});

test('buildFailures reports compile errors, failed tests and failed tasks, not architecture rules', () => {
  const log = [
    '> Task :app:compileKotlin FAILED',
    'e: file:///repo/app/app/src/main/kotlin/A.kt:4:54 Unchecked cast',
    '> Task :app:test',
    'FooTest > bar() FAILED',
    '> Task :app:test FAILED',
    '> Task :architecture:test',
    'ArchitectureRulesTest > rule() FAILED',
    '> Task :architecture:test FAILED',
    '> Task :app:queryPlanReport FAILED',
    '> Task :app:ktlintMainSourceSetCheck FAILED',
    '/repo/app/app/src/main/kotlin/B.kt:266:73 Argument should be on a separate line',
    'BUILD FAILED in 6s',
  ].join('\n');
  assert.deepEqual(buildFailures(log, '/repo').map((f) => `${f.path}:${f.line} ${f.message}`), [
    'app/app/src/main/kotlin/A.kt:4 Unchecked cast',
    ':app:test:0 FooTest > bar() failed',
    'app/app/src/main/kotlin/B.kt:266 ktlint: Argument should be on a separate line',
    ':app:queryPlanReport:0 failed; see the log',
  ]);
  assert.deepEqual(buildFailures('> Task :app:queryPlanReport FAILED\nBUILD FAILED', '/repo', new Set([':app:queryPlanReport'])), []);
  assert.deepEqual(buildFailures('BUILD FAILED', '/repo').map((f) => f.message), ['build failed; see the log']);
});

test('fromCodeQuality keeps the end line a CRAP entry carries', () => {
  const entry = { description: 'd', severity: 'minor', location: { path: 'a.kt', lines: { begin: 3, end: 9 } } };
  assert.deepEqual(fromCodeQuality([entry]), [{ path: 'a.kt', line: 3, end: 9, message: 'd', severity: 'minor' }]);
});

test('scope: line-scoped checks keep findings on changed lines; others mark findings outside the change', () => {
  const changed = new Map([['a.kt', new Set([5])]]);
  const crap = [
    { path: 'a.kt', line: 3, end: 6, message: 'spans 5' },
    { path: 'a.kt', line: 7, end: 9, message: 'after' },
    { path: 'b.kt', line: 5, message: 'other file' },
  ];
  assert.deepEqual(scope('crap', crap, changed).map((f) => f.message), ['spans 5']);
  assert.deepEqual(scope('unused-console', crap, changed).map((f) => Boolean(f.outside)), [false, false, true]);
  assert.deepEqual(scope('build', [{ path: ':app:test', line: 0 }], changed), [{ path: ':app:test', line: 0 }]);
});

test('consoleErrors reads ESLint errors (not warnings) and tsc errors as repo paths', () => {
  const eslint = JSON.stringify([
    {
      filePath: '/repo/console/lib/a.ts',
      messages: [
        { severity: 2, line: 3, ruleId: 'no-undef', message: "'x' is not defined." },
        { severity: 1, line: 4, ruleId: 'no-console', message: 'warning only' },
      ],
    },
  ]);
  const tsc = "lib/b.ts(7,5): error TS2322: Type 'string' is not assignable to type 'number'.\n";
  assert.deepEqual(consoleErrors(eslint, tsc, '/repo/console').map((f) => `${f.path}:${f.line} ${f.message}`), [
    "console/lib/a.ts:3 eslint: no-undef: 'x' is not defined.",
    "console/lib/b.ts:7 tsc: TS2322: Type 'string' is not assignable to type 'number'.",
  ]);
  assert.deepEqual(consoleErrors('', '', '/repo/console'), []);
});

test('asPaths keeps a changed path that looks like an option a path (CWE-88)', () => {
  assert.deepEqual(asPaths(['--config=evil.ts', 'lib/a.ts']), ['./--config=evil.ts', './lib/a.ts']);
});

test('formatFindings prints path:line, a bare path for line 0, the outside marker and errors', () => {
  const rows = [
    { name: 'build', list: [{ path: ':app:test', line: 0, message: 'FooTest > bar() failed' }] },
    {
      name: 'unused-console',
      list: [
        { path: 'console/lib/a.ts', line: 3, message: 'unused export a' },
        { path: 'console/lib/b.ts', line: 1, message: 'unused file', outside: true },
      ],
    },
    { name: 'detekt', list: [], error: 'detektMain exited 1' },
    { name: 'crap', list: [] },
  ];
  assert.equal(
    formatFindings(rows),
    [
      '',
      'build:',
      '  :app:test  FooTest > bar() failed',
      '',
      'unused-console:',
      '  console/lib/a.ts:3  unused export a',
      '  console/lib/b.ts:1  unused file  (outside the change)',
      '',
      'detekt: error: detektMain exited 1',
    ].join('\n'),
  );
});
