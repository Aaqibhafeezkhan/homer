import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseReport } from './query-repeats.mjs';

const repeat = (path, line, fn, sql) => ({ path, line, function: fn, sql });

const report = {
  repeats: [
    repeat('app/app/src/main/kotlin/homer/app/B.kt', 9, 'tag', 'INSERT INTO process_tags (process_id, tag) VALUES (?, ?)'),
    repeat('app/app/src/main/kotlin/homer/app/A.kt', 4, 'roots', 'SELECT processes.id, processes.owner FROM processes WHERE processes.id = ?'),
    repeat('app/app/src/main/kotlin/homer/app/C.kt', 2, 'boot', 'UPDATE processes SET purge_at=? WHERE processes.id = ?'),
  ],
};

test('a repeated read is major, a repeated write minor, an accepted one none', () => {
  const findings = parseReport(report, { 'C.kt#boot': 'boot only' });
  assert.deepEqual(
    findings.map((f) => [f.path, f.line, f.severity]),
    [
      ['app/app/src/main/kotlin/homer/app/A.kt', 4, 'major'],
      ['app/app/src/main/kotlin/homer/app/B.kt', 9, 'minor'],
    ],
  );
  assert.match(findings[0].message, /^roots: runs a read once per row in one transaction; .*: SELECT … FROM processes WHERE processes\.id = \?$/);
});

test('an accepted entry nothing matches is a finding', () => {
  const findings = parseReport({ repeats: [] }, { 'Gone.kt#old': 'removed' });
  assert.deepEqual(findings.map((f) => f.message), ['ACCEPTED entry Gone.kt#old matches no repeat any more: remove it']);
});

test('a report of only some tests leaves unmatched accepted entries alone', () => {
  assert.deepEqual(parseReport({ repeats: [] }, { 'Gone.kt#old': 'removed' }, { reportStale: false }), []);
});

test('a WITH clause in front of a write grades as a write', () => {
  const cte = { repeats: [repeat('app/D.kt', 5, 'sweep', 'WITH due AS (SELECT id FROM questions) DELETE FROM questions WHERE id IN due')] };
  assert.deepEqual(parseReport(cte, {}).map((f) => f.severity), ['minor']);
});
