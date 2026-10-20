import assert from 'node:assert/strict';
import { test } from 'node:test';
import { isConsoleSource, isKotlinSource, koverKey, koverLines, measure, statementLines, toFindings } from './diff-coverage.mjs';

test('only instrumented production sources are measured', () => {
  assert.ok(isKotlinSource('app/app/src/main/kotlin/homer/app/A.kt'));
  assert.ok(!isKotlinSource('app/app/src/test/kotlin/homer/app/ATest.kt'));
  assert.ok(!isKotlinSource('app/buildSrc/src/main/kotlin/CrapScore.kt'));
  assert.ok(isConsoleSource('console/lib/api/client.ts'));
  assert.ok(isConsoleSource('console/middleware.ts'));
  assert.ok(!isConsoleSource('console/lib/api/client.test.ts'));
  assert.ok(!isConsoleSource('console/components/ui/button.tsx'));
  assert.ok(!isConsoleSource('console/scripts/crap.mjs'));
});

test('Kover lines are keyed by package path and file name', () => {
  const xml = `<report><package name="homer/app"><class name="homer/app/A"/><sourcefile name="A.kt">
    <line nr="4" mi="2" ci="0" mb="0" cb="0"/><line nr="5" mi="0" ci="3" mb="0" cb="0"/></sourcefile></package></report>`;
  assert.deepEqual(koverLines(xml), new Map([['homer/app/A.kt', new Map([[4, false], [5, true]])]]));
  assert.equal(koverKey('app/app/src/main/kotlin/x/A.kt', '// c\npackage homer.app\n'), 'homer/app/A.kt');
});

test('a console line is covered only when every statement starting on it ran', () => {
  const entry = {
    statementMap: { 0: { start: { line: 1 } }, 1: { start: { line: 1 } }, 2: { start: { line: 2 } } },
    s: { 0: 1, 1: 0, 2: 4 },
  };
  assert.deepEqual(statementLines(entry), new Map([[1, false], [2, true]]));
});

test('files under the threshold are findings with their uncovered ranges; uninstrumented lines are ignored', () => {
  const coverage = new Map([[1, true], [2, false], [3, false], [5, false], [8, true]]);
  const files = measure(
    new Map([
      ['a.kt', new Set([1, 2, 3, 4, 5])],
      ['b.kt', new Set([8])],
      ['c.kt', new Set([9])],
    ]),
    (file) => (file === 'c.kt' ? undefined : coverage),
  );
  assert.deepEqual(files, [
    { file: 'a.kt', total: 4, uncovered: [2, 3, 5] },
    { file: 'b.kt', total: 1, uncovered: [] },
  ]);
  const [finding, ...rest] = toFindings(files);
  assert.equal(rest.length, 0);
  assert.equal(finding.line, 2);
  assert.equal(finding.message, '1/4 changed lines covered (25.0%), uncovered: 2-3, 5');
  assert.equal(finding.severity, 'major');
});
