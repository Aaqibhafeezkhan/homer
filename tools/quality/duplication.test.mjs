import assert from 'node:assert/strict';
import { test } from 'node:test';
import { toFindings } from './duplication.mjs';

const clone = (first, start, lines, fragment = 'x') => ({
  firstFile: { name: first, start },
  secondFile: { name: 'app/B.kt', start: 40 },
  lines,
  tokens: lines * 5,
  fragment,
});

test('clones become findings at their first occurrence, largest first, major from 50 lines', () => {
  const findings = toFindings({ duplicates: [clone('app/A.kt', 3, 10), clone('console/c.ts', 1, 60)] });
  assert.deepEqual(
    findings.map((f) => [f.path, f.line, f.severity]),
    [
      ['console/c.ts', 1, 'major'],
      ['app/A.kt', 3, 'minor'],
    ],
  );
  assert.equal(findings[1].message, '10 duplicated lines (50 tokens), also at app/B.kt:40');
});

test('the stable key follows the fragment, not its position', () => {
  const [a] = toFindings({ duplicates: [clone('app/A.kt', 3, 10, 'same')] });
  const [b] = toFindings({ duplicates: [clone('app/A.kt', 30, 10, 'same')] });
  assert.equal(a.stableKey, b.stableKey);
});
