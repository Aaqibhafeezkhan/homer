import assert from 'node:assert/strict';
import { test } from 'node:test';
import { diagnostic, parseLog } from './kotlin-warnings.mjs';

const log = [
  '> Task :app:compileKotlin',
  "w: file:///repo/app/app/src/main/kotlin/homer/app/A.kt:12:5 'val x: Int' is deprecated. Use 'y' instead.",
  'w: file:///repo/app/app/src/main/kotlin/homer/app/B.kt:3:9 Expression is unused.',
  'w: file:///repo/app/app/src/main/kotlin/homer/app/B.kt:3:9 Expression is unused.',
  '> Task :app:compileTestKotlin',
  "w: file:///repo/app/app/src/test/kotlin/homer/app/CTest.kt:7:1 Comparison with '!==' might not be intended because 'A' and 'B' are incompatible types.",
  'e: not a warning',
  'BUILD SUCCESSFUL in 1m',
].join('\n');

test('every distinct warning becomes a repo-relative finding, likely defects first', () => {
  const { findings, compiled, notCompiled } = parseLog(log, '/repo');
  assert.deepEqual(compiled, [':app:compileKotlin', ':app:compileTestKotlin']);
  assert.deepEqual(notCompiled, []);
  assert.deepEqual(
    findings.map((f) => [f.path, f.line, f.severity]),
    [
      ['app/app/src/main/kotlin/homer/app/B.kt', 3, 'major'],
      ['app/app/src/test/kotlin/homer/app/CTest.kt', 7, 'major'],
      ['app/app/src/main/kotlin/homer/app/A.kt', 12, 'minor'],
    ],
  );
  assert.equal(findings[2].message, "'val x: Int' is deprecated. Use 'y' instead.");
});

test('a compile task that did not run is listed apart, since its warnings are not in the log', () => {
  const { notCompiled } = parseLog('> Task :app:compileKotlin UP-TO-DATE\n> Task :agentdebug:compileTestKotlin FROM-CACHE\n> Task :e2e:compileKotlin NO-SOURCE', '/r');
  assert.deepEqual(notCompiled, [':app:compileKotlin UP-TO-DATE', ':agentdebug:compileTestKotlin FROM-CACHE']);
});

test('diagnostic reads warning and error lines alike, with repo-relative paths', () => {
  assert.deepEqual(diagnostic('e: file:///repo/app/a%20b/A.kt:4:54 Unchecked cast ', '/repo'), {
    kind: 'e',
    path: 'app/a b/A.kt',
    line: 4,
    message: 'Unchecked cast',
  });
  assert.equal(diagnostic('w: file:///repo/A.kt:1:1 x', '/repo').kind, 'w');
  assert.equal(diagnostic('e: warnings found and -Werror specified', '/repo'), null);
});
