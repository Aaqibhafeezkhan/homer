#!/usr/bin/env node
/**
 * Size report (check `size-console`, ADR-0082): files over MAX_FILE_LINES and functions over
 * MAX_FUNCTION_LINES in console production code, blank lines and comments not counted — the backend's
 * limits. Its own ESLint run: only these two rules run, so they never reach `npm run lint` or the
 * `eslint-warnings` check. Writes `reports/quality/size-console/`.
 */
import process from 'node:process';
import { pathToFileURL } from 'node:url';
import { ESLint } from 'eslint';

import { CONSOLE_DIR, printReport, repoPath, writeReports } from './quality-report.mjs';

const CHECK = 'size-console';
const MAX_FILE_LINES = 600;
const MAX_FUNCTION_LINES = 60;

// Shipped code: what coverage and CRAP score, plus the DTO types. Tests and the shadcn primitives are not.
const PRODUCTION_FILES = ['app/**/*.{ts,tsx}', 'components/**/*.{ts,tsx}', 'lib/**/*.{ts,tsx}', 'types/**/*.ts', 'middleware.ts'];
const NOT_PRODUCTION = ['**/*.test.*', 'components/ui/**', '**/*.d.ts'];

const COUNTING = { skipBlankLines: true, skipComments: true };
const SIZE_RULES = {
  'max-lines': ['warn', { max: MAX_FILE_LINES, ...COUNTING }],
  'max-lines-per-function': ['warn', { max: MAX_FUNCTION_LINES, ...COUNTING }],
};

/** One finding per size-rule message; the fingerprint key leaves out the line count, which moves with every edit. */
export function sizeFindings(results, consoleDir = CONSOLE_DIR) {
  return results.flatMap((result) =>
    result.messages
      .filter((message) => message.ruleId in SIZE_RULES)
      .map((message) => ({
        path: repoPath(result.filePath, consoleDir),
        line: message.line ?? 1,
        message: `${message.ruleId}: ${message.message}`,
        stableKey: `${message.ruleId}: ${message.message.replace(/ \(\d+\)/, '')}`,
        severity: 'minor',
      })),
  );
}

/** Messages that are not size findings mean the run itself is broken (a parse error, a bad directive). */
export function otherMessages(results) {
  return results.flatMap((result) =>
    result.messages.filter((message) => !(message.ruleId in SIZE_RULES)).map((message) => `${result.filePath}:${message.line}: ${message.message}`),
  );
}

async function main() {
  const eslint = new ESLint({
    cwd: CONSOLE_DIR,
    overrideConfig: { files: PRODUCTION_FILES, ignores: NOT_PRODUCTION, rules: SIZE_RULES },
    ruleFilter: ({ ruleId }) => ruleId in SIZE_RULES,
  });
  const results = await eslint.lintFiles(PRODUCTION_FILES);
  const broken = otherMessages(results);
  if (broken.length > 0) throw new Error(`size-console: unexpected ESLint messages:\n${broken.join('\n')}`);
  const findings = sizeFindings(results);
  const count = (rule) => findings.filter((f) => f.message.startsWith(`${rule}:`)).length;
  const extra = {
    files: count('max-lines'),
    functions: count('max-lines-per-function'),
    maxFileLines: MAX_FILE_LINES,
    maxFunctionLines: MAX_FUNCTION_LINES,
  };
  const detail = `(${extra.files} file(s) > ${MAX_FILE_LINES} lines, ${extra.functions} function(s) > ${MAX_FUNCTION_LINES} lines)`;
  const report = writeReports(CHECK, { title: 'Console file and function size', findings, detail, extra });
  printReport(CHECK, report);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(error.stack ?? error.message);
    process.exit(1);
  });
}
