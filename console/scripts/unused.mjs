#!/usr/bin/env node
/**
 * Unused-code report (check `unused-console`, ADR-0082): runs knip with `knip.json` and turns every
 * issue — unused files, dependencies and exports, unlisted or unresolved imports — into a finding.
 * Writes `reports/quality/unused-console/`.
 */
import process from 'node:process';
import { pathToFileURL } from 'node:url';

import { printReport, repoPath, runTool, writeReports } from './quality-report.mjs';

const CHECK = 'unused-console';

// knip's JSON issue types, in report order: [label, severity].
const ISSUE_TYPES = {
  files: ['unused file', 'major'],
  dependencies: ['unused dependency', 'major'],
  devDependencies: ['unused devDependency', 'major'],
  optionalPeerDependencies: ['unused optional peer dependency', 'major'],
  unlisted: ['unlisted dependency', 'major'],
  binaries: ['unlisted binary', 'major'],
  unresolved: ['unresolved import', 'major'],
  exports: ['unused export', 'minor'],
  types: ['unused exported type', 'minor'],
  enumMembers: ['unused enum member', 'minor'],
  classMembers: ['unused class member', 'minor'],
  namespaceMembers: ['unused namespace member', 'minor'],
  duplicates: ['duplicate export', 'minor'],
  catalog: ['unused catalog entry', 'minor'],
};

/** One finding per issue of knip's JSON reporter output, and the count per issue type. */
export function knipFindings(output) {
  const counts = Object.fromEntries(Object.keys(ISSUE_TYPES).map((type) => [type, 0]));
  const findings = [];
  for (const entry of output.issues) {
    for (const [type, [label, severity]] of Object.entries(ISSUE_TYPES)) {
      for (const issue of entry[type] ?? []) {
        // A duplicate is the group of names one value is exported under.
        const items = Array.isArray(issue) ? issue : [issue];
        counts[type] += 1;
        findings.push({
          path: repoPath(entry.file),
          line: items[0]?.line ?? 1,
          message: type === 'files' ? label : `${label} ${items.map((item) => item.name).join(' / ')}`,
          severity,
        });
      }
    }
  }
  return { findings, counts };
}

function main() {
  const output = JSON.parse(runTool('knip', ['--reporter', 'json', '--no-exit-code', '--no-progress']));
  const { findings, counts } = knipFindings(output);
  const report = writeReports(CHECK, { title: 'Unused code (knip)', findings, extra: counts });
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
