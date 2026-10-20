#!/usr/bin/env node
/**
 * Console architecture report (check `architecture-console`, ADR-0082): runs dependency-cruiser with
 * `.dependency-cruiser.mjs` over the source directories and turns its violations into findings.
 * Writes `reports/quality/architecture-console/`.
 */
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';

import { CONSOLE_DIR, printReport, repoPath, runTool, writeReports } from './quality-report.mjs';

const CHECK = 'architecture-console';
const ROOTS = ['app', 'components', 'lib', 'types', 'middleware.ts'];

/** 1-based line of the first quoted occurrence of `specifier` in `sourceText`, else 1. */
export function importLine(sourceText, specifier) {
  const quoted = ["'", '"', '`'].map((quote) => `${quote}${specifier}${quote}`);
  const index = sourceText.split('\n').findIndex((line) => quoted.some((q) => line.includes(q)));
  return index + 1 || 1;
}

function describe(violation) {
  if (violation.type === 'cycle') {
    return `cycle ${[violation.from, ...violation.cycle.map((step) => step.name)].join(' → ')}`;
  }
  if (violation.type === 'dependency') return `imports ${violation.to}`;
  return violation.to && violation.to !== violation.from ? `${violation.from} → ${violation.to}` : violation.from;
}

/**
 * One finding per dependency-cruiser violation (`summary.violations` of its JSON output), placed on
 * the import that breaks the rule. `readSource` maps a console-relative path to its text.
 */
export function violationFindings(violations, readSource) {
  return violations.map((violation) => {
    const specifier = violation.unresolvedTo ?? violation.to;
    const line = violation.type === 'dependency' || violation.type === 'cycle' ? importLine(readSource(violation.from), specifier) : 1;
    return {
      path: repoPath(violation.from),
      line,
      message: `${violation.rule.name}: ${describe(violation)}`,
      severity: violation.rule.severity === 'error' ? 'major' : 'minor',
    };
  });
}

function main() {
  const output = runTool('depcruise', ['--config', '.dependency-cruiser.mjs', '--output-type', 'json', ...ROOTS]);
  const { summary } = JSON.parse(output);
  const findings = violationFindings(summary.violations, (file) => fs.readFileSync(path.join(CONSOLE_DIR, file), 'utf8'));
  const report = writeReports(CHECK, {
    title: 'Console architecture',
    findings,
    detail: `(${summary.totalCruised} modules cruised)`,
    extra: { modules: summary.totalCruised },
  });
  printReport(CHECK, report);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    main();
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}
