/**
 * detekt: the findings of each module's type-resolved `detektMain` SARIF report (ADR-0082).
 * Usage (repository root): node tools/quality/detekt.mjs [out dir]
 *
 * The rule sets come from app/config/detekt/detekt.yml. Coroutine and potential-bug findings are
 * major, as they point at a defect; exception-handling findings are minor.
 */
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { repoRelative, runMain, writeCheck } from './lib.mjs';

const REPORTS = 'app/*/build/reports/detekt/main.sarif';
const MAJOR_RULE_SETS = new Set(['coroutines', 'potential-bugs']);

const pathOf = (uri, root) => repoRelative(root, uri.startsWith('file:') ? fileURLToPath(uri) : decodeURIComponent(uri));

/** Findings of one SARIF document; `ruleId` is `detekt.<rule set>.<rule>`. */
export function parseSarif(sarif, root) {
  const findings = [];
  for (const run of sarif.runs ?? []) {
    for (const result of run.results ?? []) {
      const location = result.locations?.[0]?.physicalLocation;
      if (!location?.artifactLocation?.uri) continue;
      const rule = result.ruleId.replace(/^detekt\./, '');
      findings.push({
        path: pathOf(location.artifactLocation.uri, root),
        line: location.region?.startLine ?? 1,
        message: `${rule}: ${result.message?.text ?? ''}`.trim(),
        severity: MAJOR_RULE_SETS.has(rule.split('.')[0]) ? 'major' : 'minor',
      });
    }
  }
  return findings;
}

export const sortFindings = (findings) =>
  [...findings].sort(
    (a, b) => (a.severity === b.severity ? 0 : a.severity === 'major' ? -1 : 1) || a.path.localeCompare(b.path) || a.line - b.line,
  );

export function main([outDir = 'reports/quality/detekt']) {
  const reports = fs.globSync(REPORTS).sort();
  if (reports.length === 0) throw new Error(`no ${REPORTS}: did detektMain run?`);
  const findings = sortFindings(reports.flatMap((file) => parseSarif(JSON.parse(fs.readFileSync(file, 'utf8')), process.cwd())));
  const title = 'detekt findings (coroutines, exceptions, potential bugs)';
  return writeCheck(outDir, 'detekt', title, findings, { reports: reports.length }).split('\n').slice(0, 21).join('\n') + '\n';
}

runMain(import.meta, main);
