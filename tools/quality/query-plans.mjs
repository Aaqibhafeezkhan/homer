/**
 * query-plans: full scans of tables that grow with use, in the SQL production code ran during
 * `:app:test` (ADR-0082, SQLite rule 1 in app/CLAUDE.md). Reads the JSON `:app:queryPlanReport`
 * writes; the planning itself is `QueryPlanReport` in the backend's test fixtures.
 * Usage (repository root): node tools/quality/query-plans.mjs [report json] [out dir]
 *
 * A statement the planner cannot prepare is a finding too, so a broken plan never reads as clean.
 * One finding per source line and table: the shapes one call site builds (filters, page sizes)
 * are listed in its message rather than counted separately.
 */
import fs from 'node:fs';
import { runMain, writeCheck } from './lib.mjs';

const REPORT = 'app/app/build/reports/query-plans/query-plans.json';
const MAX_SQL = 300;

const clip = (sql) => (sql.length > MAX_SQL ? `${sql.slice(0, MAX_SQL)}…` : sql);

/** Findings of one report, grouped by call site and scanned table. */
export function parseReport(report) {
  const sites = new Map();
  for (const f of report.findings ?? []) {
    const key = `${f.path}:${f.line}:${f.table}`;
    if (!sites.has(key)) sites.set(key, { ...f, shapes: [] });
    sites.get(key).shapes.push(f.sql.replace(/^SELECT .*? FROM /, 'SELECT … FROM '));
  }
  const unplanned = (report.unplanned ?? []).map((u) => ({
    path: u.path,
    line: u.line,
    severity: 'major',
    message: `could not plan: ${u.error}: ${clip(u.sql)}`,
  }));
  const scans = [...sites.values()]
    .map((s) => ({
      path: s.path,
      line: s.line,
      severity: 'major',
      message:
        `${s.function}: full scan of ${s.table} (${s.plan.join(' / ')})` +
        (s.shapes.length > 1 ? `, ${s.shapes.length} shapes, e.g. ` : ': ') +
        clip(s.shapes[0]),
    }))
    .sort((a, b) => a.path.localeCompare(b.path) || a.line - b.line);
  return [...scans, ...unplanned];
}

export function main([reportFile = REPORT, outDir = 'reports/quality/query-plans']) {
  if (!fs.existsSync(reportFile)) throw new Error(`no ${reportFile}: did :app:queryPlanReport run?`);
  const report = JSON.parse(fs.readFileSync(reportFile, 'utf8'));
  const findings = parseReport(report);
  const title = 'Full scans of growing tables (SQLite query plans)';
  const extra = { statements: report.statements, unplanned: report.unplanned?.length ?? 0 };
  return writeCheck(outDir, 'query-plans', title, findings, extra).split('\n').slice(0, 21).join('\n') + '\n';
}

runMain(import.meta, main);
