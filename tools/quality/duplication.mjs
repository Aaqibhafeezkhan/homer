/**
 * duplication: copy-pasted blocks across the backend and console production sources, found by
 * jscpd (ADR-0082). Run from the repository root: node tools/quality/duplication.mjs [out dir]
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { runMain, sha1, writeCheck } from './lib.mjs';

const JSCPD = path.join(import.meta.dirname, 'node_modules', '.bin', 'jscpd');
const FORMATS = 'kotlin,typescript,tsx';
// Production code only: tests, build scripts, generated shadcn primitives, fixtures and tooling are left out.
const IGNORE = [
  '**/node_modules/**',
  '**/build/**',
  '**/.next/**',
  '**/coverage/**',
  '**/reports/**',
  '**/src/test/**',
  '**/*.test.*',
  '**/*.gradle.kts',
  'console/components/ui/**',
  'app/e2e/**',
  'app/buildSrc/**',
  'app/architecture/**',
  'tools/**',
  'example/**',
  'plugins/**',
  'docs/**',
  'clients/**',
  '.claude/**',
].join(',');
// Import and package lines repeat between any two files that use the same APIs; they are not copied logic.
const IMPORTS = String.raw`(?m)^\s*(import|package)\s.*$`;
const MAJOR_LINES = 50;

/** jscpd's JSON report → findings, one per clone at its first occurrence, largest first. */
export function toFindings(report) {
  return report.duplicates
    .map((clone) => {
      const other = `${clone.secondFile.name}:${clone.secondFile.start}`;
      return {
        path: clone.firstFile.name,
        line: clone.firstFile.start,
        lines: clone.lines,
        message: `${clone.lines} duplicated lines (${clone.tokens} tokens), also at ${other}`,
        // The fragment, not its line numbers, identifies the clone when the code around it moves.
        stableKey: `${clone.secondFile.name}|${sha1(clone.fragment ?? '')}`,
        severity: clone.lines >= MAJOR_LINES ? 'major' : 'minor',
      };
    })
    .sort((a, b) => b.lines - a.lines || a.path.localeCompare(b.path) || a.line - b.line);
}

export function main([outDir = 'reports/quality/duplication']) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'jscpd-'));
  try {
    execFileSync(JSCPD, ['.', '--format', FORMATS, '--ignore', IGNORE, '--ignore-pattern', IMPORTS, '--reporters', 'json', '--output', tmp, '--silent'], {
      stdio: ['ignore', 'ignore', 'inherit'],
    });
    const report = JSON.parse(fs.readFileSync(path.join(tmp, 'jscpd-report.json'), 'utf8'));
    const total = report.statistics.total;
    const extra = { duplicatedLines: total.duplicatedLines, percentage: total.percentage.toFixed(2), files: total.sources };
    return writeCheck(outDir, 'duplication', 'Duplicated code', toFindings(report), extra).split('\n').slice(0, 21).join('\n') + '\n';
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

runMain(import.meta, main);
