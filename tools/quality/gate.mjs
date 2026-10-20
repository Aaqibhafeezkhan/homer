/**
 * quality-gate (ADR-0082): reads every check's summary listed in checks.json, prints a table and
 * the top findings, and merges the Code Quality files into one report (a job uploads only one).
 * Usage (repository root): node tools/quality/gate.mjs [merged code-quality file]
 *
 * Exit codes: 0 no findings; 3 findings only in "warn" checks (the CI job allows exit code 3, so it
 * shows as a warning); 1 findings in a "required" check, or a summary that is missing or unreadable.
 */
import fs from 'node:fs';
import path from 'node:path';
import { parseSummary, runMain } from './lib.mjs';

export const EXIT_WARN = 3;
const TOP_FINDINGS = 10;

const fileOf = (check, dir, suffix) => path.join(dir, `${check.name}-${suffix}`);
const codeQualityOf = (check, dir) => path.join(dir, `gl-code-quality-${check.name}.json`);

/** One check's state from the files in its dirs; `read` is injected so tests need no files. */
export function evaluate(check, read) {
  let findings = 0;
  let skipped = 0;
  const missing = [];
  const reports = [];
  for (const dir of check.dirs) {
    const summary = read(fileOf(check, dir, 'summary.txt'));
    if (summary === undefined) {
      missing.push(dir);
      continue;
    }
    const values = parseSummary(summary);
    const count = Number(values.findings ?? values.violations);
    if (!Number.isInteger(count) || count < 0) throw new Error(`${fileOf(check, dir, 'summary.txt')}: no findings= line`);
    if (check.threshold !== undefined && Number(values.threshold) !== check.threshold) {
      throw new Error(`${fileOf(check, dir, 'summary.txt')}: written for threshold ${values.threshold}, checks.json says ${check.threshold}`);
    }
    findings += count;
    if (values.skipped !== undefined) skipped++;
    if (count > 0) reports.push(read(fileOf(check, dir, 'report.txt')) ?? '');
  }
  const ran = missing.length < check.dirs.length;
  let status;
  if (missing.length > 0 && !(check.optional && !ran)) status = 'missing';
  else if (!ran) status = 'not run';
  else if (findings === 0 && skipped === check.dirs.length) status = 'skipped';
  else if (findings === 0) status = 'ok';
  else status = check.mode === 'required' ? 'failed' : 'warning';
  return { ...check, findings, missing, reports, status };
}

export function exitCode(results) {
  if (results.some((r) => r.status === 'missing' || r.status === 'failed')) return 1;
  return results.some((r) => r.status === 'warning') ? EXIT_WARN : 0;
}

export function formatTable(results) {
  const rows = results.map((r) => [r.name, r.mode, ['not run', 'missing', 'skipped'].includes(r.status) ? '-' : String(r.findings), r.status]);
  const header = ['check', 'mode', 'findings', 'status'];
  const widths = header.map((h, i) => Math.max(h.length, ...rows.map((row) => row[i].length)));
  const line = (cells) => cells.map((c, i) => c.padEnd(widths[i])).join('  ').trimEnd();
  return [line(header), ...rows.map(line)].join('\n');
}

export function formatDetails(results) {
  const out = [];
  for (const r of results) {
    if (r.missing.length > 0 && r.status === 'missing') out.push(`\n${r.name}: missing summary in ${r.missing.join(', ')}`);
    for (const report of r.reports) {
      const lines = report.trimEnd().split('\n');
      out.push(`\n${lines.slice(0, TOP_FINDINGS + 1).join('\n')}`);
      if (lines.length > TOP_FINDINGS + 1) out.push(`  … ${lines.length - TOP_FINDINGS - 1} more in the job artifacts`);
    }
  }
  return out.join('\n');
}

const readIfExists = (file) => (fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : undefined);

export function main([merged = 'gl-code-quality.json']) {
  const config = JSON.parse(fs.readFileSync(path.join(import.meta.dirname, 'checks.json'), 'utf8'));
  const results = config.checks.map((check) => evaluate(check, readIfExists));
  const entries = results.flatMap((r) =>
    r.dirs.flatMap((dir) => {
      const text = readIfExists(codeQualityOf(r, dir));
      return text === undefined ? [] : JSON.parse(text);
    }),
  );
  fs.writeFileSync(merged, `${JSON.stringify(entries, null, 2)}\n`);
  process.stdout.write(`${formatTable(results)}\n${formatDetails(results)}\n`);
  const code = exitCode(results);
  if (code === EXIT_WARN) process.stdout.write('\nReport-only checks have findings: the job passes with a warning (ADR-0082).\n');
  process.exitCode = code;
}

runMain(import.meta, main);
