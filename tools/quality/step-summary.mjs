/**
 * Renders one check's report files (`<check>-summary.txt`, `<check>-report.txt`) as Markdown for a
 * GitHub Actions job summary: the summary values as a table, the findings in a collapsed list.
 * Usage (repository root): node tools/quality/step-summary.mjs <dir> <check> <heading> >> "$GITHUB_STEP_SUMMARY"
 *
 * Findings quote source text (Stryker replacements, PIT descriptions), so every value is
 * HTML-escaped: the summary renders HTML.
 */
import fs from 'node:fs';
import path from 'node:path';
import { parseSummary, runMain } from './lib.mjs';

export const MAX_FINDINGS = 200;

export const escapeHtml = (text) => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

const cell = (text) => escapeHtml(text).replace(/\|/g, '&#124;').replace(/\r?\n/g, ' ');

/** The Markdown section for one check; `summary` and `report` are the file texts, undefined when missing. */
export function renderSummary(heading, summary, report) {
  const out = [`### ${cell(heading)}`, ''];
  if (summary === undefined) return `${[...out, 'No report: the run wrote none, see the job log.'].join('\n')}\n`;
  const values = Object.entries(parseSummary(summary));
  if (values.length > 0) out.push(`| ${values.map(([key]) => cell(key)).join(' | ')} |`, `|${' --- |'.repeat(values.length)}`, `| ${values.map(([, value]) => cell(value)).join(' | ')} |`);
  const findings = (report ?? '').trimEnd().split('\n').slice(1).filter(Boolean);
  if (findings.length > 0) {
    const shown = findings.slice(0, MAX_FINDINGS).map(escapeHtml);
    if (findings.length > MAX_FINDINGS) shown.push(`… ${findings.length - MAX_FINDINGS} more in the job artifact`);
    out.push('', `<details><summary>${findings.length} finding(s)</summary>`, '', `<pre>${shown.join('\n')}</pre>`, '', '</details>');
  }
  return `${out.join('\n')}\n`;
}

const readIfExists = (file) => (fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : undefined);

export function main([dir, check, heading]) {
  if (!dir || !check || !heading) throw new Error('usage: step-summary.mjs <dir> <check> <heading>');
  return renderSummary(heading, readIfExists(path.join(dir, `${check}-summary.txt`)), readIfExists(path.join(dir, `${check}-report.txt`)));
}

runMain(import.meta, main);
