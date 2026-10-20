import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { test } from 'node:test';
import { EXIT_WARN, evaluate, exitCode, formatTable } from './gate.mjs';

const files = (entries) => (file) => entries[file];

test('findings in a warn check are a warning; in a required check a failure', () => {
  const read = files({ 'd/x-summary.txt': 'findings=2\n', 'd/x-report.txt': 'X: 2 finding(s)\n' });
  const warn = evaluate({ name: 'x', mode: 'warn', dirs: ['d'] }, read);
  assert.equal(warn.status, 'warning');
  assert.equal(warn.findings, 2);
  assert.equal(exitCode([warn]), EXIT_WARN);
  const required = evaluate({ name: 'x', mode: 'required', dirs: ['d'] }, read);
  assert.equal(required.status, 'failed');
  assert.equal(exitCode([warn, required]), 1);
});

test('counts add up across dirs, and the CRAP summaries count through violations=', () => {
  const read = files({ 'a/crap-summary.txt': 'violations=3\nscored=9\n', 'b/crap-summary.txt': 'violations=0\n', 'a/crap-report.txt': 'r' });
  const result = evaluate({ name: 'crap', mode: 'warn', dirs: ['a', 'b'] }, read);
  assert.equal(result.findings, 3);
  assert.deepEqual(result.reports, ['r']);
});

test('a missing summary fails closed, unless an optional check did not run at all', () => {
  const none = files({});
  assert.equal(evaluate({ name: 'x', mode: 'warn', dirs: ['d'] }, none).status, 'missing');
  assert.equal(exitCode([evaluate({ name: 'x', mode: 'warn', dirs: ['d'] }, none)]), 1);
  const optional = evaluate({ name: 'x', mode: 'warn', optional: true, dirs: ['d'] }, none);
  assert.equal(optional.status, 'not run');
  assert.equal(exitCode([optional]), 0);
  const partial = evaluate({ name: 'x', mode: 'warn', optional: true, dirs: ['d', 'e'] }, files({ 'd/x-summary.txt': 'findings=0' }));
  assert.equal(partial.status, 'missing');
});

test('a check whose every summary says skipped shows as skipped, not as clean', () => {
  const read = files({ 'd/x-summary.txt': 'findings=0\nskipped=no merge-request base\n' });
  const result = evaluate({ name: 'x', mode: 'required', dirs: ['d'] }, read);
  assert.equal(result.status, 'skipped');
  assert.equal(exitCode([result]), 0);
});

test('a report written under a threshold other than checks.json\'s is a broken check', () => {
  const check = { name: 'crap', mode: 'required', threshold: 25, dirs: ['a'] };
  assert.equal(evaluate(check, files({ 'a/crap-summary.txt': 'violations=0\nthreshold=25\n' })).status, 'ok');
  assert.throws(() => evaluate(check, files({ 'a/crap-summary.txt': 'violations=0\nthreshold=30\n' })), /threshold 30, checks.json says 25/);
});

test('a summary without a count is a broken check', () => {
  assert.throws(() => evaluate({ name: 'x', mode: 'warn', dirs: ['d'] }, files({ 'd/x-summary.txt': 'scored=1' })), /no findings=/);
});

test('the table lists every check with its mode, count and status', () => {
  const results = [
    { name: 'crap', mode: 'warn', findings: 19, status: 'warning' },
    { name: 'mutation-console', mode: 'warn', findings: 0, status: 'not run' },
  ];
  assert.equal(formatTable(results), ['check             mode  findings  status', 'crap              warn  19        warning', 'mutation-console  warn  -         not run'].join('\n'));
});

test('checks.json names every check once, with a known mode', () => {
  const { checks } = JSON.parse(fs.readFileSync(path.join(import.meta.dirname, 'checks.json'), 'utf8'));
  assert.equal(new Set(checks.map((c) => c.name)).size, checks.length);
  for (const c of checks) assert.ok(['warn', 'required'].includes(c.mode), c.name);
  assert.ok(checks.find((c) => c.name === 'crap').threshold > 0);
});
