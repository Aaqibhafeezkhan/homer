import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseReport } from './query-plans.mjs';

const finding = (path, line, table, sql) => ({ path, line, function: 'list', table, sql, plan: [`SCAN ${table}`] });

const report = {
  statements: 3,
  findings: [
    finding('app/app/src/main/kotlin/homer/app/B.kt', 7, 'questions', 'SELECT questions.id FROM questions WHERE 1'),
    finding('app/app/src/main/kotlin/homer/app/A.kt', 4, 'processes', 'SELECT processes.id FROM processes WHERE owner = ? LIMIT 5'),
    finding('app/app/src/main/kotlin/homer/app/A.kt', 4, 'processes', 'SELECT processes.id FROM processes WHERE owner = ? LIMIT 10'),
  ],
  unplanned: [],
};

test('the shapes of one call site and table become one finding, sorted by path and line', () => {
  const findings = parseReport(report);
  assert.deepEqual(
    findings.map((f) => [f.path, f.line, f.severity]),
    [
      ['app/app/src/main/kotlin/homer/app/A.kt', 4, 'major'],
      ['app/app/src/main/kotlin/homer/app/B.kt', 7, 'major'],
    ],
  );
  assert.equal(findings[0].message, 'list: full scan of processes (SCAN processes), 2 shapes, e.g. SELECT … FROM processes WHERE owner = ? LIMIT 5');
  assert.equal(findings[1].message, 'list: full scan of questions (SCAN questions): SELECT … FROM questions WHERE 1');
});

test('a statement the planner could not prepare is a finding', () => {
  const unplanned = [{ path: 'app/C.kt', line: 3, sql: 'SELECT x FROM gone', error: 'no such table: gone' }];
  const findings = parseReport({ statements: 1, findings: [], unplanned });
  assert.deepEqual(
    findings.map((f) => [f.path, f.line, f.message]),
    [['app/C.kt', 3, 'could not plan: no such table: gone: SELECT x FROM gone']],
  );
});

test('a report without findings has none', () => {
  assert.deepEqual(parseReport({ statements: 1, findings: [], unplanned: [] }), []);
});
