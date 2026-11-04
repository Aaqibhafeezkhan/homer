/**
 * Backend mutation testing (PIT pilot, ADR-0082): turns PIT's `mutations.xml` into the shared
 * report files of check `mutation-backend`, one finding per survived or uncovered mutant.
 * Usage (repository root): node tools/quality/pit.mjs [mutations.xml] [out dir]
 *
 * Not a quality-gate check yet: the nightly job only shows the report in its job summary
 * (tools/quality/step-summary.mjs).
 */
import fs from 'node:fs';
import { mutationScore, runMain, writeCheck } from './lib.mjs';

const CHECK = 'mutation-backend';
const REPORT_DIR = 'app/app/build/reports/pitest';
const SOURCE_ROOT = 'app/app/src/main/kotlin';
const FINDING_STATUSES = { SURVIVED: 'survived', NO_COVERAGE: 'no coverage' };
const ENTITIES = { lt: '<', gt: '>', amp: '&', apos: "'", quot: '"' };

/** XML text with its predefined and numeric character references resolved. */
export function decodeXml(text) {
  return text.replace(/&(#x[0-9a-fA-F]+|#\d+|lt|gt|amp|apos|quot);/g, (reference, ref) => {
    if (ref[0] !== '#') return ENTITIES[ref];
    const codePoint = ref[1] === 'x' ? parseInt(ref.slice(2), 16) : Number(ref.slice(1));
    return codePoint <= 0x10ffff ? String.fromCodePoint(codePoint) : reference;
  });
}

const element = (body, name) => {
  const match = new RegExp(`<${name}>([\\s\\S]*?)</${name}>`).exec(body);
  return match ? decodeXml(match[1]) : '';
};

/** The `<mutation>` elements of a PIT XML report: status, source path, line, method and description. */
export function parseMutations(xml) {
  return [...xml.matchAll(/<mutation\b([^>]*)>([\s\S]*?)<\/mutation>/g)].map(([, attributes, body]) => {
    const mutatedClass = element(body, 'mutatedClass');
    const packageDir = mutatedClass.includes('.') ? mutatedClass.slice(0, mutatedClass.lastIndexOf('.')).replaceAll('.', '/') : '';
    return {
      status: /\bstatus=['"]([A-Z_]+)['"]/.exec(attributes)?.[1] ?? 'UNKNOWN',
      path: [SOURCE_ROOT, packageDir, element(body, 'sourceFile')].filter(Boolean).join('/'),
      line: Number(element(body, 'lineNumber')) || 1,
      method: element(body, 'mutatedMethod'),
      description: element(body, 'description'),
    };
  });
}

// The report and the job summary read one line per finding, and PIT's XML writer keeps newlines.
const oneLine = (text) => text.replace(/\s+/g, ' ').trim();

/** One finding per survived or uncovered mutant, in file and line order. */
export function pitFindings(mutations) {
  return mutations
    .filter((m) => Object.hasOwn(FINDING_STATUSES, m.status))
    .map((m) => ({
      path: m.path,
      line: m.line,
      message: `${FINDING_STATUSES[m.status]}: ${oneLine(m.method)} → ${oneLine(m.description)}`,
      severity: 'minor',
    }))
    .sort((a, b) => a.path.localeCompare(b.path) || a.line - b.line);
}

const SCORED = { KILLED: 'killed', SURVIVED: 'survived', NO_COVERAGE: 'noCoverage', TIMED_OUT: 'timeout' };

/**
 * Mutant counts in the console report's terms (console/scripts/mutation.mjs) and the shared
 * score (`mutationScore`). Every other status (memory and run errors, non-viable, not started,
 * one PIT adds later) is an error, outside the score, so the counts always add up to the total.
 */
export function pitStats(mutations) {
  const stats = { killed: 0, survived: 0, noCoverage: 0, timeout: 0, errors: 0 };
  for (const m of mutations) stats[Object.hasOwn(SCORED, m.status) ? SCORED[m.status] : 'errors'] += 1;
  return { score: mutationScore(stats), ...stats };
}

export function main([xmlFile = `${REPORT_DIR}/mutations.xml`, outDir = REPORT_DIR]) {
  if (!fs.existsSync(xmlFile)) throw new Error(`no ${xmlFile}: did :app:pitest run?`);
  const mutations = parseMutations(fs.readFileSync(xmlFile, 'utf8'));
  const stats = pitStats(mutations);
  return writeCheck(outDir, CHECK, `Backend mutation testing (score ${stats.score}%)`, pitFindings(mutations), stats);
}

runMain(import.meta, main);
