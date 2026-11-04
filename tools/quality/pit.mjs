/**
 * Backend mutation testing (PIT pilot, ADR-0082): turns PIT's `mutations.xml` into the shared
 * report files of check `mutation-backend`, one finding per survived or uncovered mutant.
 * Mutants that only remove a call the Kotlin compiler adds are ignored: outside the score, no finding.
 * Usage (repository root): node tools/quality/pit.mjs [mutations.xml] [out dir]
 *
 * Not a quality-gate check yet: the nightly job only shows the report in its job summary
 * (tools/quality/step-summary.mjs).
 */
import fs from 'node:fs';
import { mutationScore, runMain, writeCheck } from './lib.mjs';

export const CHECK = 'mutation-backend';
export const REPORT_DIR = 'app/app/build/reports/pitest';
export const SOURCE_ROOT = 'app/app/src/main/kotlin';
const FINDING_STATUSES = { SURVIVED: 'survived', NO_COVERAGE: 'no coverage' };
// Null checks, coroutine result checks and index-overflow guards the Kotlin compiler emits: removing
// one changes nothing a test can observe. Filtered here, not by avoidCallsTo: see ADR-0082.
const COMPILER_CALL = /^removed call to kotlin\/(jvm\/internal\/Intrinsics|ResultKt|collections\/CollectionsKt)::(check|throw)/;
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

/** Whether the mutant only removes a call the Kotlin compiler added. */
export const isCompilerCallMutant = (m) => COMPILER_CALL.test(m.description);

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

/** Writes the check's report files for `all` mutants, compiler-call mutants left out; returns the report text. */
export function writeReport(outDir, title, all) {
  const mutations = all.filter((m) => !isCompilerCallMutant(m));
  const stats = { ...pitStats(mutations), ignored: all.length - mutations.length };
  const score = stats.score === 'n/a' ? 'no mutants' : `score ${stats.score}%`;
  return writeCheck(outDir, CHECK, `${title} (${score})`, pitFindings(mutations), stats);
}

/** The `<mutation>` elements of PIT's XML report `xmlFile`. */
export function readMutations(xmlFile) {
  if (!fs.existsSync(xmlFile)) throw new Error(`no ${xmlFile}: did :app:pitest run?`);
  return parseMutations(fs.readFileSync(xmlFile, 'utf8'));
}

export function main([xmlFile = `${REPORT_DIR}/mutations.xml`, outDir = REPORT_DIR]) {
  return writeReport(outDir, 'Backend mutation testing', readMutations(xmlFile));
}

runMain(import.meta, main);
