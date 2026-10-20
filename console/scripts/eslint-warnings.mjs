#!/usr/bin/env node
/**
 * ESLint warnings report (check `eslint-warnings`, ADR-0082): one ESLint run over the console with
 * its own config, warnings as findings, errors only counted — `npm run lint` already fails on those.
 * Writes `reports/quality/eslint-warnings/`.
 */
import process from 'node:process';
import { pathToFileURL } from 'node:url';
import { ESLint } from 'eslint';

import { CONSOLE_DIR, printReport, repoPath, writeReports } from './quality-report.mjs';

const CHECK = 'eslint-warnings';

/** One finding per warning in ESLint's `LintResult[]`. */
export function warningFindings(results, consoleDir = CONSOLE_DIR) {
  return results.flatMap((result) =>
    result.messages
      .filter((message) => message.severity === 1)
      .map((message) => ({
        path: repoPath(result.filePath, consoleDir),
        line: message.line ?? 1,
        message: `${message.ruleId ?? 'eslint'}: ${message.message}`,
        severity: 'minor',
      })),
  );
}

export function errorCount(results) {
  return results.reduce((sum, result) => sum + result.errorCount, 0);
}

async function main() {
  const results = await new ESLint({ cwd: CONSOLE_DIR }).lintFiles(['.']);
  const findings = warningFindings(results);
  const errors = errorCount(results);
  const report = writeReports(CHECK, { title: 'ESLint warnings', findings, detail: `(${errors} error(s))`, extra: { errors } });
  printReport(CHECK, report);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(error.stack ?? error.message);
    process.exit(1);
  });
}
