import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseReport, testFile, testLine } from './mock-calls.mjs';

const entry = (testClass, name, calls) =>
  JSON.stringify({ testClass, test: name, method: 'Process.waitFor', calls, warnAt: 5000, failAt: 20000 });

test('a test class maps to its outermost class file under the app test sources', () => {
  assert.equal(testFile('homer.app.runners.KTest'), 'app/app/src/test/kotlin/homer/app/runners/KTest.kt');
  assert.equal(testFile('homer.app.runners.KTest$Nested'), 'app/app/src/test/kotlin/homer/app/runners/KTest.kt');
});

test('a backticked or plain test function is found by its display name, a missing one is line 1', () => {
  const source = 'class KTest {\n    @Test\n    fun `polls until done`(\n    ) {}\n    @Test\n    fun plain() {}\n}\n';
  assert.equal(testLine(source, 'polls until done(Path)'), 3);
  assert.equal(testLine(source, 'plain()'), 6);
  assert.equal(testLine(source, '[1] a parameterised case'), 1);
});

test('each report line is one minor finding at its test, naming the method and the count', () => {
  const sources = { 'app/app/src/test/kotlin/homer/app/B.kt': 'class B {\n  fun `spins`() {}\n}\n' };
  const findings = parseReport([entry('homer.app.B', 'spins()', 12345), entry('homer.app.A', 'gone()', 6000)], (f) => sources[f]);
  assert.deepEqual(
    findings.map((f) => [f.path, f.line, f.severity]),
    [
      ['app/app/src/test/kotlin/homer/app/A.kt', 1, 'minor'],
      ['app/app/src/test/kotlin/homer/app/B.kt', 2, 'minor'],
    ],
  );
  assert.match(findings[1].message, /^spins\(\): Process\.waitFor was called 12345 times in one test \(warning from 5000, failure from 20000\): /);
});

test('an empty report has no findings', () => {
  assert.deepEqual(parseReport([], () => undefined), []);
});
