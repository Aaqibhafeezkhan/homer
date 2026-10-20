/**
 * query-repeats: statements production code runs three or more times in one transaction during
 * `:app:test`, the shape of a query per row in a loop (ADR-0082, SQLite rule 2 in app/CLAUDE.md).
 * Reads the `repeats` of the JSON `:app:queryPlanReport` writes; `QueryPlanCapture` records them.
 * Usage (repository root): node tools/quality/query-repeats.mjs [report json] [out dir]
 *
 * A repeated read is major (it costs a round trip per row on the caller's path); a repeated write
 * is minor (it is one statement per row, but inside the transaction that holds the write lock).
 * A repeat made on purpose is listed in ACCEPTED with its reason; an entry no longer matching any
 * repeat is a finding.
 */
import fs from 'node:fs';
import { runMain, writeCheck } from './lib.mjs';

const REPORT = 'app/app/build/reports/query-plans/query-plans.json';
const MAX_SQL = 300;

/** Deliberate repeats, `<file name>#<function>` → why. */
export const ACCEPTED = {
  'ProcessRepository.kt#stampUnknownPurgeTimes':
    'boot only, before traffic: one UPDATE per distinct purge schedule (and id chunk), not per row',
};

const clip = (sql) => (sql.length > MAX_SQL ? `${sql.slice(0, MAX_SQL)}…` : sql);
const keyOf = (r) => `${r.path.split('/').pop()}#${r.function}`;

/**
 * Findings of one report's repeats, less [accepted] ones, plus stale [accepted] entries. A report of
 * only some tests (the diff mode) proves no entry stale: `reportStale` false leaves them out.
 */
export function parseReport(report, accepted = ACCEPTED, { reportStale = true } = {}) {
  const used = new Set();
  const findings = [];
  for (const r of report.repeats ?? []) {
    if (keyOf(r) in accepted) {
      used.add(keyOf(r));
      continue;
    }
    // A WITH clause may front a write (a writable CTE): a write keyword anywhere grades it as one.
    const read = /^\s*SELECT\b/i.test(r.sql) || (/^\s*WITH\b/i.test(r.sql) && !/\b(INSERT|UPDATE|DELETE)\b/i.test(r.sql));
    findings.push({
      path: r.path,
      line: r.line,
      severity: read ? 'major' : 'minor',
      message: `${r.function}: runs ${read ? 'a read' : 'a write'} once per row in one transaction; batch it (IN list, batchInsert): ${clip(r.sql.replace(/^SELECT .*? FROM /, 'SELECT … FROM '))}`,
    });
  }
  const stale = Object.keys(accepted)
    .filter((key) => reportStale && !used.has(key))
    .map((key) => ({
      path: 'tools/quality/query-repeats.mjs',
      line: 1,
      severity: 'minor',
      message: `ACCEPTED entry ${key} matches no repeat any more: remove it`,
    }));
  return [...findings.sort((a, b) => a.path.localeCompare(b.path) || a.line - b.line), ...stale];
}

export function main([reportFile = REPORT, outDir = 'reports/quality/query-repeats']) {
  if (!fs.existsSync(reportFile)) throw new Error(`no ${reportFile}: did :app:queryPlanReport run?`);
  const report = JSON.parse(fs.readFileSync(reportFile, 'utf8'));
  if (!Array.isArray(report.repeats)) throw new Error(`${reportFile} has no repeats: is :app:queryPlanReport current?`);
  const findings = parseReport(report);
  const title = 'Statements repeated per row in one transaction';
  return writeCheck(outDir, 'query-repeats', title, findings, { repeats: report.repeats.length }).split('\n').slice(0, 21).join('\n') + '\n';
}

runMain(import.meta, main);
